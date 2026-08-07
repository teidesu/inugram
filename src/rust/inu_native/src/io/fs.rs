//! `inu.fs`: the plugin's own durable directory, per `src/plugins/fs.d.ts`.
//!
//! **The whole api is native** - the host hands over one directory and one quota at [`install_fs`]
//! and nothing else crosses. Routing `fs.write`'s bytes through an upcall would put every written
//! megabyte on the app-wide *Java* heap, which is the lever the per-plugin ceilings exist to take
//! away, so blob -> file is a [`COPY_CHUNK_BYTES`]-chunked copy inside rust.
//!
//! **Normalization happens before containment, and that ordering is the security property.** A
//! containment check on the path a plugin typed passes for `a/../../etc` (a string prefix), for
//! `a//..//b` (a `..` behind a doubled separator) and for a symlink the plugin planted in its own
//! directory. So [`walk`] takes one component at a time, popping on `..`, skipping `.` and reading
//! through every link it meets ([`MAX_SYMLINK_HOPS`]), and `starts_with` runs on the *result* -
//! component-wise, so `<root>-evil` is not inside `<root>`. `..` pops what was already resolved
//! rather than being collapsed lexically, so a link followed by `..` lands where the kernel would
//! have gone. The root is canonicalized once at install, or a data directory reached through a link
//! makes every op read as an escape.
//!
//! [`FsState::unscoped`] is the same code with containment and the quota off. The quota is charged
//! before a byte is written and not at all for a `move` (inside the scope it cannot add, outside
//! there is no cap).

use std::cell::Cell;
use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::rc::Rc;
use std::time::UNIX_EPOCH;

use rquickjs::function::Opt;
use rquickjs::{Ctx, Function, Object, Result as JsResult, TypedArray, Value};

use crate::engine::error::{check_grant, get_or_create_inu, throw_plugin_error, GrantHost, MATCH_EXACT};
use crate::io::blob::{export_for_host, resolve_export, BlobExport, BlobState, MATERIALIZE_LIMIT_BYTES};

/// how many bytes move between a blob and a file at a time. The same size [`crate::io::blob`] joins its
/// own parts in, and for the same reason: writing a 200 MB blob must not be a 200 MB allocation.
const COPY_CHUNK_BYTES: u64 = 256 * 1024;

/// near enough the kernel's own `ELOOP` bound. A cycle of symlinks is the one input to
/// [`resolve_path`] that does not terminate on its own.
const MAX_SYMLINK_HOPS: u32 = 40;

/// what `@grant fs` alone buys, per `fs.d.ts`. The host derives the real number from the manifest
/// (`fs(200mb)`); this is what an engine installed without one falls back to.
pub const DEFAULT_QUOTA_BYTES: u64 = 50 * 1024 * 1024;

/// `quota()` under `unsafe.fs`, which `fs.d.ts` declares as `Infinity`
pub const UNCAPPED: u64 = u64::MAX;

/// EXDEV. `std::io::ErrorKind::CrossesDevices` is still unstable, and the distinction decides
/// whether a move may fall back to a copy or is a real failure.
const EXDEV: i32 = 18;

/// the app's own directories, in the order [`install_fs`] is handed them: the plugin store, the
/// cache, then the five media kinds `android.d.ts` names. Absolute paths outside the scoped root,
/// so nothing here is reachable without `unsafe.fs`; an entry the host could not answer is empty.
pub const ANDROID_DIR_NAMES: [&str; 5] = ["files", "images", "videos", "audios", "documents"];

pub struct FsState {
    grants: Rc<dyn GrantHost>,
    blobs: Rc<BlobState>,
    /// canonicalized at install. Empty == the host could not make one, and every op then fails
    /// rather than landing somewhere this plugin does not own
    root: PathBuf,
    quota: u64,
    unscoped: bool,
    /// the tree's byte total, or `None` when it has to be walked again
    usage: Cell<Option<u64>>,
    /// `[plugins, cache, ..ANDROID_DIR_NAMES]`, as the host answered them at install
    android_dirs: Vec<String>,
}

impl FsState {
    /// the token the one gate asks for. It follows the mode the host installed, so a plugin the
    /// host believed held `unsafe.fs` is still refused if the manifest says otherwise.
    fn grant(&self) -> &'static str {
        if self.unscoped {
            "unsafe.fs"
        } else {
            "fs"
        }
    }
}

/// what the host answers with on a device: the plugin store, the cache, then the five media kinds.
/// The fourth is empty on purpose - a media directory the app has not made yet is a real answer,
/// and it is the one the `not-found` refusal exists for.
#[cfg(test)]
const TEST_ANDROID_DIRS: &str =
    "/data/plugins\n/data/cache\n/media/files\n/media/images\n\n/media/audios\n/media/documents";

/// every way an `inu.fs` call fails, and the `PluginError` code each earns
enum Fault {
    /// the path resolved outside the plugin's own directory. `not-granted` rather than `forbidden`,
    /// because `unsafe.fs` is exactly what would allow it - which is what that code means
    Escape(PathBuf),
    Invalid(String),
    NotFound(String),
    Quota {
        usage: u64,
        quota: u64,
        message: String,
    },
    Io(String),
    Gone(String),
}

impl Fault {
    fn throw<T>(self, ctx: &Ctx<'_>) -> JsResult<T> {
        match self {
            Fault::Escape(path) => throw_plugin_error(
                ctx,
                "not-granted",
                &format!(
                    "'{}' is outside this plugin's directory; only @grant unsafe.fs reaches there",
                    path.display(),
                ),
                Some("unsafe.fs"),
                None,
                None,
            ),
            Fault::Invalid(message) => throw_plugin_error(ctx, "invalid-argument", &message, None, None, None),
            Fault::NotFound(message) => throw_plugin_error(ctx, "not-found", &message, None, None, None),
            Fault::Quota { usage, quota, message } => {
                throw_plugin_error(ctx, "quota-exceeded", &message, None, Some(usage as i64), Some(quota as i64))
            }
            Fault::Io(message) => throw_plugin_error(ctx, "internal", &message, None, None, None),
            Fault::Gone(message) => throw_plugin_error(ctx, "handle-expired", &message, None, None, None),
        }
    }
}

type FsResult<T> = Result<T, Fault>;

fn io(what: &str, e: std::io::Error) -> Fault {
    match e.kind() {
        std::io::ErrorKind::NotFound => Fault::NotFound(format!("{what}: no such file or directory")),
        std::io::ErrorKind::PermissionDenied => Fault::Io(format!("{what}: permission denied")),
        _ => Fault::Io(format!("{what}: {e}")),
    }
}

/// Resolves `input` the way the kernel would, **then** requires the result to be inside the
/// plugin's own directory. See the module doc for why that order is the whole point.
///
/// A component that does not exist is not a failure: a `write` names a file that is not there yet,
/// and something that does not exist cannot be a symlink either, so appending it is exactly what
/// the kernel does.
fn resolve_path(state: &FsState, input: &str) -> FsResult<PathBuf> {
    if state.root.as_os_str().is_empty() {
        return Err(Fault::Io("fs: this plugin has no storage directory".to_string()));
    }
    if input.is_empty() {
        return Err(Fault::Invalid("fs: the path is empty".to_string()));
    }
    // a NUL truncates the path at the syscall boundary, so a path carrying one names something
    // other than what it reads as
    if input.contains('\0') {
        return Err(Fault::Invalid("fs: the path contains a NUL".to_string()));
    }

    let raw = Path::new(input);
    if raw.is_absolute() && !state.unscoped {
        return Err(Fault::Escape(raw.to_path_buf()));
    }

    let start = if raw.is_absolute() { PathBuf::from("/") } else { state.root.clone() };
    let mut hops = 0;
    let resolved = walk(start, raw, &mut hops)?;

    if !state.unscoped && !resolved.starts_with(&state.root) {
        return Err(Fault::Escape(resolved));
    }
    Ok(resolved)
}

/// Resolves a path on behalf of another api that takes `{ path }` (`inu.canvas.load`/`loadFont`),
/// applying this module's own gate rather than a second copy of it: the grant the engine was
/// installed with, and then containment. A relative path lands in the plugin's scoped directory,
/// which is the whole reason the resolution belongs here and not at the call site.
pub(crate) fn resolve_external(ctx: &Ctx<'_>, state: &Rc<FsState>, input: &str) -> JsResult<PathBuf> {
    check_grant(ctx, &state.grants, state.grant(), None, MATCH_EXACT)?;
    match resolve_path(state, input) {
        Ok(path) => Ok(path),
        Err(fault) => fault.throw(ctx),
    }
}

/// One component at a time, reading through every symlink. `current` is always already resolved,
/// which is what makes `..` the parent of where the links actually led rather than the parent of
/// what the plugin typed.
fn walk(mut current: PathBuf, path: &Path, hops: &mut u32) -> FsResult<PathBuf> {
    for component in path.components() {
        match component {
            // windows only; no shape of it means anything here
            Component::Prefix(_) => return Err(Fault::Invalid("fs: the path has a drive prefix".to_string())),
            Component::RootDir => current = PathBuf::from("/"),
            Component::CurDir => {}
            Component::ParentDir => {
                current.pop();
            }
            Component::Normal(name) => {
                let next = current.join(name);
                let is_link = fs::symlink_metadata(&next).map(|meta| meta.file_type().is_symlink()).unwrap_or(false);
                if !is_link {
                    current = next;
                    continue;
                }
                *hops += 1;
                if *hops > MAX_SYMLINK_HOPS {
                    return Err(Fault::Invalid(format!("fs: too many symbolic links resolving '{}'", path.display(),)));
                }
                let target = fs::read_link(&next).map_err(|e| io("fs", e))?;
                // an absolute target opens with a `RootDir` component, which resets `current` itself
                current = walk(current, &target, hops)?;
            }
        }
    }
    Ok(current)
}

/// the tree's byte total. Directory entries are not counted: what the user is paying for is
/// content, and a per-entry estimate is a number no filesystem agrees on.
fn walk_usage(dir: &Path) -> u64 {
    let Ok(entries) = fs::read_dir(dir) else {
        return 0;
    };
    let mut total = 0;
    for entry in entries.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            total += walk_usage(&entry.path());
        } else if meta.is_file() {
            total += meta.len();
        }
    }
    total
}

fn usage_of(state: &FsState) -> u64 {
    if let Some(cached) = state.usage.get() {
        return cached;
    }
    let total = walk_usage(&state.root);
    state.usage.set(Some(total));
    total
}

/// what a file currently costs, so a rewrite is charged for its difference rather than its size
fn current_size(path: &Path) -> u64 {
    fs::metadata(path).map(|meta| if meta.is_file() { meta.len() } else { 0 }).unwrap_or(0)
}

/// refuses *before* anything is written, so a plugin that crosses the cap is left with neither a
/// truncated file nor a partly grown one
fn check_quota(state: &FsState, adding: u64, replacing: u64) -> FsResult<u64> {
    let usage = usage_of(state);
    let after = usage.saturating_sub(replacing).saturating_add(adding);
    if state.unscoped {
        return Ok(after);
    }
    if after > state.quota {
        return Err(Fault::Quota {
            usage: after,
            quota: state.quota,
            message: format!(
                "fs: this would leave {after} bytes in a directory capped at {}; ask for more with @grant fs(...)",
                state.quota,
            ),
        });
    }
    Ok(after)
}

/// `Blob | Uint8Array`, resolved to something a file can be filled from without either heap ever
/// holding the whole of it
enum Source {
    Bytes(Vec<u8>),
    Blob(BlobExport),
}

impl Source {
    fn len(&self) -> u64 {
        match self {
            Source::Bytes(bytes) => bytes.len() as u64,
            Source::Blob(export) => export.len(),
        }
    }

    /// chunked for the blob case, so the transient allocation is [`COPY_CHUNK_BYTES`] whatever the
    /// content's size
    fn write_into(&self, file: &mut fs::File) -> FsResult<()> {
        match self {
            Source::Bytes(bytes) => file.write_all(bytes).map_err(|e| io("fs", e)),
            Source::Blob(export) => {
                let mut at = 0;
                while at < export.len() {
                    let take = (export.len() - at).min(COPY_CHUNK_BYTES);
                    let chunk = export
                        .read(at, take)
                        .map_err(|_| Fault::Gone("fs: the blob being written is gone".to_string()))?;
                    file.write_all(&chunk).map_err(|e| io("fs", e))?;
                    at += take;
                }
                Ok(())
            }
        }
    }
}

fn read_source(state: &FsState, value: &Value<'_>) -> FsResult<Source> {
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        let Some(bytes) = typed.as_bytes() else {
            return Err(Fault::Invalid("fs: the array is detached".to_string()));
        };
        return Ok(Source::Bytes(bytes.to_vec()));
    }
    if let Some(export) = blob_export(state, value) {
        return Ok(Source::Blob(export));
    }
    // a real blob whose content is gone reaches here too, and is the one case worth telling apart:
    // the plugin handed over something that *was* a blob
    if rquickjs::Class::<crate::io::blob::BlobHandle>::from_value(value).is_ok() {
        return Err(Fault::Gone("fs: the blob being written was disposed".to_string()));
    }
    Err(Fault::Invalid("fs: expected a Blob or a Uint8Array".to_string()))
}

/// A blob crosses to the host as `B<id>:<start>:<len>`. Here the host *is* this process, so the
/// wire is minted and resolved back in one breath rather than [`crate::io::blob`] growing a second
/// accessor for a caller that never leaves it.
fn blob_export(state: &FsState, value: &Value<'_>) -> Option<BlobExport> {
    let wire = export_for_host(&state.blobs, value)?;
    let id = wire.strip_prefix('B')?.split(':').next()?.parse().ok()?;
    resolve_export(&state.blobs, id)
}

fn op_read<'js>(ctx: &Ctx<'js>, state: &FsState, path: &str) -> FsResult<Value<'js>> {
    let path = resolve_path(state, path)?;
    let meta = fs::metadata(&path).map_err(|e| io("read", e))?;
    if !meta.is_file() {
        return Err(Fault::Invalid(format!("read: '{}' is not a file", path.display())));
    }
    // the ceiling `blob.bytes()` has, refused for the same reason: without it the read succeeds and
    // the engine then dies of an out-of-memory nothing can attribute to this line
    if meta.len() > MATERIALIZE_LIMIT_BYTES {
        return Err(Fault::Quota {
            usage: meta.len(),
            quota: MATERIALIZE_LIMIT_BYTES,
            message: format!(
                "read: {} bytes is past the {MATERIALIZE_LIMIT_BYTES} one read may take into javascript",
                meta.len(),
            ),
        });
    }
    let bytes = fs::read(&path).map_err(|e| io("read", e))?;
    TypedArray::<u8>::new(ctx.clone(), bytes)
        .map(|array| array.into_value())
        .map_err(|e| Fault::Io(format!("read: {e:?}")))
}

fn op_write(state: &FsState, path: &str, source: Source, append: bool) -> FsResult<()> {
    let what = if append { "append" } else { "write" };
    let path = resolve_path(state, path)?;
    if fs::metadata(&path).map(|meta| meta.is_dir()).unwrap_or(false) {
        return Err(Fault::Invalid(format!("{what}: '{}' is a directory", path.display())));
    }
    let replacing = if append { 0 } else { current_size(&path) };
    let after = check_quota(state, source.len(), replacing)?;

    let mut file = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .append(append)
        .truncate(!append)
        .open(&path)
        .map_err(|e| io(what, e))?;
    if let Err(e) = source.write_into(&mut file) {
        // a partial file is still bytes on disk, so the cached total is no longer known
        state.usage.set(None);
        return Err(e);
    }
    state.usage.set(Some(after));
    Ok(())
}

fn op_mkdir(state: &FsState, path: &str) -> FsResult<()> {
    let path = resolve_path(state, path)?;
    fs::create_dir_all(&path).map_err(|e| io("mkdir", e))
}

fn op_rm(state: &FsState, path: &str, recursive: bool) -> FsResult<()> {
    let path = resolve_path(state, path)?;
    // the plugin's own directory is not a thing it may delete: what would be left is a root that no
    // longer exists, and every later op would fail on a directory only the host ever creates
    if path == state.root {
        return Err(Fault::Invalid("rm: the plugin's own directory cannot be removed".to_string()));
    }
    let Ok(meta) = fs::symlink_metadata(&path) else {
        // idempotent, like every other disposal in this api: removing what is already gone is what
        // the caller asked for
        return Ok(());
    };
    state.usage.set(None);
    if !meta.is_dir() {
        return fs::remove_file(&path).map_err(|e| io("rm", e));
    }
    if recursive {
        fs::remove_dir_all(&path).map_err(|e| io("rm", e))
    } else {
        fs::remove_dir(&path).map_err(|_| {
            Fault::Invalid(format!("rm: '{}' is a directory; pass {{ recursive: true }}", path.display(),))
        })
    }
}

fn op_exists(state: &FsState, path: &str) -> FsResult<bool> {
    let path = resolve_path(state, path)?;
    Ok(fs::symlink_metadata(&path).is_ok())
}

fn op_readdir(state: &FsState, path: &str) -> FsResult<Vec<String>> {
    let path = resolve_path(state, path)?;
    let entries = fs::read_dir(&path).map_err(|e| io("readdir", e))?;
    let mut names: Vec<String> =
        entries.flatten().map(|entry| entry.file_name().to_string_lossy().into_owned()).collect();
    // the order a directory hands entries back in is the filesystem's business and changes as it is
    // written to, which makes an unsorted answer a plugin bug waiting to happen
    names.sort();
    Ok(names)
}

struct Stat {
    is_file: bool,
    is_directory: bool,
    size: u64,
    mtime: i64,
    ctime: i64,
}

fn op_stat(state: &FsState, path: &str) -> FsResult<Stat> {
    let path = resolve_path(state, path)?;
    let meta = fs::metadata(&path).map_err(|e| io("stat", e))?;
    let mtime = system_time_millis(meta.modified().ok());
    Ok(Stat {
        is_file: meta.is_file(),
        is_directory: meta.is_dir(),
        size: if meta.is_file() { meta.len() } else { 0 },
        mtime,
        ctime: unix_ctime_millis(&meta).unwrap_or(mtime),
    })
}

fn system_time_millis(time: Option<std::time::SystemTime>) -> i64 {
    let Some(time) = time else { return 0 };
    match time.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_millis() as i64,
        Err(e) => -(e.duration().as_millis() as i64),
    }
}

fn unix_ctime_millis(meta: &fs::Metadata) -> Option<i64> {
    use std::os::unix::fs::MetadataExt;
    let seconds = meta.ctime();
    let nanos = meta.ctime_nsec();
    if seconds == 0 && nanos == 0 {
        return None;
    }
    Some(seconds * 1000 + nanos / 1_000_000)
}

fn op_copy(state: &FsState, src: &str, dest: &str) -> FsResult<()> {
    let src = resolve_path(state, src)?;
    let dest = resolve_path(state, dest)?;
    let meta = fs::metadata(&src).map_err(|e| io("copy", e))?;
    if !meta.is_file() {
        return Err(Fault::Invalid(format!("copy: '{}' is not a file", src.display())));
    }
    check_quota(state, meta.len(), current_size(&dest))?;
    state.usage.set(None);
    fs::copy(&src, &dest).map_err(|e| io("copy", e))?;
    Ok(())
}

/// no quota check: inside the scope a move cannot raise the total, and only `unsafe.fs` can name an
/// endpoint outside it, where there is no cap to check against
fn op_move(state: &FsState, src: &str, dest: &str) -> FsResult<()> {
    let src = resolve_path(state, src)?;
    let dest = resolve_path(state, dest)?;
    if fs::symlink_metadata(&src).is_err() {
        return Err(Fault::NotFound(format!("move: '{}' does not exist", src.display())));
    }
    state.usage.set(None);
    match fs::rename(&src, &dest) {
        Ok(()) => Ok(()),
        // a rename cannot cross a mount, and under `unsafe.fs` the two ends may be on different
        // ones. Copy-then-remove is what every `mv` does about it - recursively, `move` taking a
        // directory where `copy` does not
        Err(e) if e.raw_os_error() == Some(EXDEV) => {
            copy_tree(&src, &dest).map_err(|e| io("move", e))?;
            remove_tree(&src).map_err(|e| io("move", e))
        }
        Err(e) => Err(io("move", e)),
    }
}

/// every path here has already been through [`resolve_path`], which reads through every link it
/// meets, so there is nothing left to follow and no cycle to guard against
fn copy_tree(src: &Path, dest: &Path) -> std::io::Result<()> {
    if !fs::metadata(src)?.is_dir() {
        fs::copy(src, dest)?;
        return Ok(());
    }
    fs::create_dir_all(dest)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        copy_tree(&entry.path(), &dest.join(entry.file_name()))?;
    }
    Ok(())
}

fn remove_tree(path: &Path) -> std::io::Result<()> {
    if fs::metadata(path)?.is_dir() {
        fs::remove_dir_all(path)
    } else {
        fs::remove_file(path)
    }
}

/// `root` is this plugin's own durable directory (`PluginFs.dirFor`), or "" when the host could not
/// make one - which leaves every call failing rather than landing somewhere else. `quota` is what
/// the manifest asked for, [`UNCAPPED`] under `unsafe.fs`.
pub fn install_fs<'js>(
    ctx: &Ctx<'js>,
    grants: Rc<dyn GrantHost>,
    blobs: Rc<BlobState>,
    root: &Path,
    quota: u64,
    unscoped: bool,
    android_dirs: &str,
) -> JsResult<Rc<FsState>> {
    // once, here: containment compares a resolved path against this prefix, and a root reached
    // through a link would make every op read as an escape
    let root = if root.as_os_str().is_empty() {
        PathBuf::new()
    } else {
        fs::canonicalize(root).unwrap_or_else(|_| root.to_path_buf())
    };
    let state = Rc::new(FsState {
        grants,
        blobs,
        root,
        quota,
        unscoped,
        usage: Cell::new(None),
        android_dirs: android_dirs.split('\n').map(str::to_string).collect(),
    });

    let fs_obj = Object::new(ctx.clone())?;

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Value<'js>> {
            gate(&ctx, &state)?;
            op_read(&ctx, &state, &path).or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set("read", f)?;
    }

    for (name, append) in [("write", false), ("append", true)] {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String, data: Value<'js>| -> JsResult<()> {
            gate(&ctx, &state)?;
            read_source(&state, &data)
                .and_then(|source| op_write(&state, &path, source, append))
                .or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set(name, f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<()> {
            gate(&ctx, &state)?;
            op_mkdir(&state, &path).or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set("mkdir", f)?;
    }

    {
        let state = state.clone();
        let f =
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String, options: Opt<Value<'js>>| -> JsResult<()> {
                gate(&ctx, &state)?;
                let recursive = match options.0.as_ref().and_then(|v| v.as_object()) {
                    Some(options) => options.get::<_, Option<bool>>("recursive")?.unwrap_or(false),
                    None => false,
                };
                op_rm(&state, &path, recursive).or_else(|fault| fault.throw(&ctx))
            })?;
        fs_obj.set("rm", f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<bool> {
            gate(&ctx, &state)?;
            op_exists(&state, &path).or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set("exists", f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Vec<String>> {
            gate(&ctx, &state)?;
            op_readdir(&state, &path).or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set("readdir", f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Object<'js>> {
            gate(&ctx, &state)?;
            let stat = op_stat(&state, &path).or_else(|fault| fault.throw(&ctx))?;
            let obj = Object::new(ctx.clone())?;
            obj.set("isFile", stat.is_file)?;
            obj.set("isDirectory", stat.is_directory)?;
            obj.set("size", stat.size as f64)?;
            obj.set("mtime", stat.mtime as f64)?;
            obj.set("ctime", stat.ctime as f64)?;
            Ok(obj)
        })?;
        fs_obj.set("stat", f)?;
    }

    for (name, is_move) in [("copy", false), ("move", true)] {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, src: String, dest: String| -> JsResult<()> {
            gate(&ctx, &state)?;
            let done = if is_move { op_move(&state, &src, &dest) } else { op_copy(&state, &src, &dest) };
            done.or_else(|fault| fault.throw(&ctx))
        })?;
        fs_obj.set(name, f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<f64> {
            gate(&ctx, &state)?;
            if state.root.as_os_str().is_empty() {
                return Fault::Io("fs: this plugin has no storage directory".to_string()).throw(&ctx);
            }
            Ok(usage_of(&state) as f64)
        })?;
        fs_obj.set("usage", f)?;
    }

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<f64> {
            gate(&ctx, &state)?;
            Ok(if state.quota == UNCAPPED { f64::INFINITY } else { state.quota as f64 })
        })?;
        fs_obj.set("quota", f)?;
    }

    get_or_create_inu(ctx)?.set("fs", fs_obj)?;
    install_android_dirs(ctx, &state)?;
    Ok(state)
}

/// `inu.android.getPluginsDir`/`getCacheDir`/`getMediaDir`. They live here because they are paths
/// and because they are only ever actionable through this api: every one of them is outside the
/// scoped root, so `unsafe.fs` is what a plugin needs to do anything with the string, and asking
/// for it here rather than at the first `read` is the difference between a refusal and a
/// disclosure. Not an upcall either, for the module's own reason - the host answers all of them
/// once, at install, and they do not change for the life of the process.
fn install_android_dirs<'js>(ctx: &Ctx<'js>, state: &Rc<FsState>) -> JsResult<()> {
    let inu = get_or_create_inu(ctx)?;
    let android: Object = match inu.get::<_, Object>("android") {
        Ok(o) => o,
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            inu.set("android", o.clone())?;
            o
        }
    };

    for (name, index) in [("getPluginsDir", 0usize), ("getCacheDir", 1)] {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> {
            android_dir(&ctx, &state, index, name)
        })?;
        android.set(name, f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, kind: String| -> JsResult<String> {
            let Some(index) = ANDROID_DIR_NAMES.iter().position(|k| *k == kind) else {
                return throw_plugin_error(
                    &ctx,
                    "invalid-argument",
                    &format!("getMediaDir: '{kind}' is not one of {}", ANDROID_DIR_NAMES.join(", ")),
                    None,
                    None,
                    None,
                );
            };
            android_dir(&ctx, &state, 2 + index, "getMediaDir")
        })?;
        android.set("getMediaDir", f)?;
    }
    Ok(())
}

fn android_dir(ctx: &Ctx<'_>, state: &Rc<FsState>, index: usize, what: &str) -> JsResult<String> {
    check_grant(ctx, &state.grants, "unsafe.fs", None, MATCH_EXACT)?;
    match state.android_dirs.get(index) {
        Some(path) if !path.is_empty() => Ok(path.clone()),
        _ => throw_plugin_error(ctx, "not-found", &format!("{what}: the app has no such directory"), None, None, None),
    }
}

/// the one gate, run before any of these touches a path
fn gate(ctx: &Ctx<'_>, state: &Rc<FsState>) -> JsResult<()> {
    check_grant(ctx, &state.grants, state.grant(), None, MATCH_EXACT)
}

#[cfg(test)]
#[path = "fs_tests.rs"]
pub(crate) mod tests;
