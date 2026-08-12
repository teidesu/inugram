use std::cell::Cell;
use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::rc::Rc;
use std::time::UNIX_EPOCH;

use rquickjs::function::Opt;
use rquickjs::{Ctx, Function, Object, Result as JsResult, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{export_for_host, resolve_export, BlobExport, BlobState, MATERIALIZE_LIMIT_BYTES};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};

const COPY_CHUNK_BYTES: u64 = 256 * 1024;

const MAX_SYMLINK_HOPS: u32 = 40;

pub const DEFAULT_QUOTA_BYTES: u64 = 50 * 1024 * 1024;

pub const UNCAPPED: u64 = u64::MAX;

const EXDEV: i32 = 18;

pub const ANDROID_DIR_NAMES: [&str; 5] = ["files", "images", "videos", "audios", "documents"];

pub struct FsState {
  grants: Rc<dyn GrantHost>,
  blobs: Rc<BlobState>,
  root: PathBuf,
  quota: u64,
  unscoped: bool,
  usage: Cell<Option<u64>>,
  android_dirs: Vec<String>,
}

impl FsState {
  fn grant(&self) -> &'static str {
    if self.unscoped {
      "unsafe.fs"
    } else {
      "fs"
    }
  }
}

#[cfg(test)]
const TEST_ANDROID_DIRS: &str = r#"/data/plugins
/data/cache
/media/files
/media/images

/media/audios
/media/documents"#;

enum Fault {
  Escape(PathBuf),
  Invalid(String),
  NotFound(String),
  Quota { usage: u64, quota: u64, message: String },
  Io(String),
  Gone(String),
}

impl Fault {
  fn throw<T>(self, ctx: &Ctx<'_>) -> JsResult<T> {
    match self {
      Fault::Escape(path) => PluginErrorCode::NotGranted("unsafe.fs").throw(
        ctx,
        &format!("'{}' is outside this plugin's directory; only @grant unsafe.fs reaches there", path.display(),),
      ),
      Fault::Invalid(message) => PluginErrorCode::InvalidArgument.throw(ctx, &message),
      Fault::NotFound(message) => PluginErrorCode::NotFound.throw(ctx, &message),
      Fault::Quota { usage, quota, message } => PluginErrorCode::QuotaExceeded(
        i64::try_from(usage).unwrap_or(i64::MAX),
        i64::try_from(quota).unwrap_or(i64::MAX),
      )
      .throw(ctx, &message),
      Fault::Io(message) => PluginErrorCode::Internal.throw(ctx, &message),
      Fault::Gone(message) => PluginErrorCode::HandleExpired.throw(ctx, &message),
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

fn resolve_path(state: &FsState, input: &str) -> FsResult<PathBuf> {
  if state.root.as_os_str().is_empty() {
    return Err(Fault::Io("fs: this plugin has no storage directory".to_string()));
  }
  if input.is_empty() {
    return Err(Fault::Invalid("fs: the path is empty".to_string()));
  }
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

pub(crate) fn resolve_external(ctx: &Ctx<'_>, state: &Rc<FsState>, input: &str) -> JsResult<PathBuf> {
  check_grant(ctx, &state.grants, state.grant(), None, MATCH_EXACT)?;
  match resolve_path(state, input) {
    Ok(path) => Ok(path),
    Err(fault) => fault.throw(ctx),
  }
}

fn walk(mut current: PathBuf, path: &Path, hops: &mut u32) -> FsResult<PathBuf> {
  for component in path.components() {
    match component {
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
        current = walk(current, &target, hops)?;
      }
    }
  }
  Ok(current)
}

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

fn current_size(path: &Path) -> u64 {
  fs::metadata(path).map(|meta| if meta.is_file() { meta.len() } else { 0 }).unwrap_or(0)
}

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

  fn write_into(&self, file: &mut fs::File) -> FsResult<()> {
    match self {
      Source::Bytes(bytes) => file.write_all(bytes).map_err(|e| io("fs", e)),
      Source::Blob(export) => {
        let mut at = 0;
        while at < export.len() {
          let take = (export.len() - at).min(COPY_CHUNK_BYTES);
          let chunk =
            export.read(at, take).map_err(|_| Fault::Gone("fs: the blob being written is gone".to_string()))?;
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
  if rquickjs::Class::<crate::api::io::blob::BlobHandle>::from_value(value).is_ok() {
    return Err(Fault::Gone("fs: the blob being written was disposed".to_string()));
  }
  Err(Fault::Invalid("fs: expected a Blob or a Uint8Array".to_string()))
}

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
  if path == state.root {
    return Err(Fault::Invalid("rm: the plugin's own directory cannot be removed".to_string()));
  }
  let Ok(meta) = fs::symlink_metadata(&path) else {
    return Ok(());
  };
  state.usage.set(None);
  if !meta.is_dir() {
    return fs::remove_file(&path).map_err(|e| io("rm", e));
  }
  if recursive {
    fs::remove_dir_all(&path).map_err(|e| io("rm", e))
  } else {
    fs::remove_dir(&path)
      .map_err(|_| Fault::Invalid(format!("rm: '{}' is a directory; pass {{ recursive: true }}", path.display(),)))
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
    Ok(duration) => i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
    Err(error) => i64::try_from(error.duration().as_millis()).map_or(i64::MIN, i64::saturating_neg),
  }
}

fn unix_ctime_millis(meta: &fs::Metadata) -> Option<i64> {
  use std::os::unix::fs::MetadataExt;
  let seconds = meta.ctime();
  let nanos = meta.ctime_nsec();
  if seconds == 0 && nanos == 0 {
    return None;
  }
  Some(seconds.saturating_mul(1000).saturating_add(nanos / 1_000_000))
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

fn op_move(state: &FsState, src: &str, dest: &str) -> FsResult<()> {
  let src = resolve_path(state, src)?;
  let dest = resolve_path(state, dest)?;
  if fs::symlink_metadata(&src).is_err() {
    return Err(Fault::NotFound(format!("move: '{}' does not exist", src.display())));
  }
  state.usage.set(None);
  match fs::rename(&src, &dest) {
    Ok(()) => Ok(()),
    Err(e) if e.raw_os_error() == Some(EXDEV) => {
      copy_tree(&src, &dest).map_err(|e| io("move", e))?;
      remove_tree(&src).map_err(|e| io("move", e))
    }
    Err(e) => Err(io("move", e)),
  }
}

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

#[allow(clippy::too_many_arguments)]
pub fn install_fs<'js>(
  ctx: &Ctx<'js>,
  grants: Rc<dyn GrantHost>,
  blobs: Rc<BlobState>,
  root: &Path,
  quota: u64,
  unscoped: bool,
  android_dirs: &str,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FsState>> {
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
    fs_obj.set(
      "read",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Value<'js>> {
        gate(&ctx, &state)?;
        op_read(&ctx, &state, &path).or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  for (name, append) in [("write", false), ("append", true)] {
    let state = state.clone();
    fs_obj.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String, data: Value<'js>| -> JsResult<()> {
        gate(&ctx, &state)?;
        read_source(&state, &data)
          .and_then(|source| op_write(&state, &path, source, append))
          .or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "mkdir",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<()> {
        gate(&ctx, &state)?;
        op_mkdir(&state, &path).or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "rm",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String, options: Opt<Value<'js>>| -> JsResult<()> {
        gate(&ctx, &state)?;
        let recursive = match options.0.as_ref().and_then(|v| v.as_object()) {
          Some(options) => options.get::<_, Option<bool>>("recursive")?.unwrap_or(false),
          None => false,
        };
        op_rm(&state, &path, recursive).or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "exists",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<bool> {
        gate(&ctx, &state)?;
        op_exists(&state, &path).or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "readdir",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Vec<String>> {
        gate(&ctx, &state)?;
        op_readdir(&state, &path).or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "stat",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String| -> JsResult<Object<'js>> {
        gate(&ctx, &state)?;
        let stat = op_stat(&state, &path).or_else(|fault| fault.throw(&ctx))?;
        let obj = Object::new(ctx.clone())?;
        obj.set("isFile", stat.is_file)?;
        obj.set("isDirectory", stat.is_directory)?;
        obj.set("size", stat.size as f64)?;
        obj.set("mtime", stat.mtime as f64)?;
        obj.set("ctime", stat.ctime as f64)?;
        Ok(obj)
      })?,
    )?;
  }

  for (name, is_move) in [("copy", false), ("move", true)] {
    let state = state.clone();
    fs_obj.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, src: String, dest: String| -> JsResult<()> {
        gate(&ctx, &state)?;
        let done = if is_move { op_move(&state, &src, &dest) } else { op_copy(&state, &src, &dest) };
        done.or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "usage",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<f64> {
        gate(&ctx, &state)?;
        if state.root.as_os_str().is_empty() {
          return Fault::Io("fs: this plugin has no storage directory".to_string()).throw(&ctx);
        }
        Ok(usage_of(&state) as f64)
      })?,
    )?;
  }

  {
    let state = state.clone();
    fs_obj.set(
      "quota",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<f64> {
        gate(&ctx, &state)?;
        Ok(if state.quota == UNCAPPED { f64::INFINITY } else { state.quota as f64 })
      })?,
    )?;
  }

  globals.inu.set("fs", fs_obj)?;
  install_android_dirs(ctx, &state, globals)?;
  Ok(state)
}

fn install_android_dirs<'js>(ctx: &Ctx<'js>, state: &Rc<FsState>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
  let android: Object = match globals.inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("android", o.clone())?;
      o
    }
  };

  for (name, index) in [("getPluginsDir", 0usize), ("getCacheDir", 1)] {
    let state = state.clone();
    let f =
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> { android_dir(&ctx, &state, index, name) })?;
    android.set(name, f)?;
  }
  {
    let state = state.clone();
    android.set(
      "getMediaDir",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, kind: String| -> JsResult<String> {
        let Some(index) = ANDROID_DIR_NAMES.iter().position(|k| *k == kind) else {
          return PluginErrorCode::InvalidArgument
            .throw(&ctx, &format!("getMediaDir: '{kind}' is not one of {}", ANDROID_DIR_NAMES.join(", ")));
        };
        android_dir(&ctx, &state, 2 + index, "getMediaDir")
      })?,
    )?;
  }
  Ok(())
}

fn android_dir(ctx: &Ctx<'_>, state: &Rc<FsState>, index: usize, what: &str) -> JsResult<String> {
  check_grant(ctx, &state.grants, "unsafe.fs", None, MATCH_EXACT)?;
  match state.android_dirs.get(index) {
    Some(path) if !path.is_empty() => Ok(path.clone()),
    _ => PluginErrorCode::NotFound.throw(ctx, &format!("{what}: the app has no such directory")),
  }
}

fn gate(ctx: &Ctx<'_>, state: &Rc<FsState>) -> JsResult<()> {
  check_grant(ctx, &state.grants, state.grant(), None, MATCH_EXACT)
}

#[cfg(test)]
#[path = "fs_tests.rs"]
pub(crate) mod tests;
