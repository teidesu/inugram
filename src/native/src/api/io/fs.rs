use std::cell::Cell;
use std::ffi::OsStr;
use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::rc::Rc;

use cap_std::ambient_authority;
use cap_std::fs::Dir;
use rquickjs::function::Opt;
use rquickjs::{Ctx, Function, Object, Result as JsResult, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{mtime_millis, BlobExport, BlobFault, BlobHandle, MATERIALIZE_LIMIT_BYTES};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::utils::qjs::qjs_read_typed_bytes;

pub const DEFAULT_QUOTA_BYTES: u64 = 50 * 1024 * 1024;

pub const UNCAPPED: u64 = u64::MAX;

const EXDEV: i32 = 18;

#[cfg(target_os = "macos")]
const ELOOP: i32 = 62;
#[cfg(not(target_os = "macos"))]
const ELOOP: i32 = 40;

pub const ANDROID_DIR_NAMES: [&str; 5] = ["files", "images", "videos", "audios", "documents"];

enum Storage {
  Missing,
  Confined { root: PathBuf, dir: Dir },
  Ambient { root: PathBuf },
}

impl Storage {
  fn root(&self) -> Option<&Path> {
    match self {
      Storage::Missing => None,
      Storage::Confined { root, .. } | Storage::Ambient { root } => Some(root),
    }
  }
}

pub struct FsState {
  grants: Rc<dyn GrantHost>,
  storage: Storage,
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
  Escape(String),
  Invalid(String),
  NotFound(String),
  Quota { usage: u64, quota: u64, message: String },
  Io(String),
  Gone(String),
}

impl Fault {
  fn throw<T>(self, ctx: &Ctx<'_>) -> JsResult<T> {
    match self {
      Fault::Escape(path) => PluginErrorCode::NotGranted("unsafe.fs")
        .throw(ctx, &format!("'{path}' is outside this plugin's directory; only @grant unsafe.fs reaches there")),
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

  fn is_refusal(&self) -> bool {
    matches!(self, Fault::Escape(_) | Fault::Invalid(_))
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

/// cap-std reports a path leaving its directory as a synthesized `PermissionDenied` with no OS error
fn io_at(what: &str, path: &str, e: std::io::Error) -> Fault {
  match e.kind() {
    std::io::ErrorKind::PermissionDenied if e.raw_os_error().is_none() => Fault::Escape(path.to_string()),
    _ if e.raw_os_error() == Some(ELOOP) => {
      Fault::Invalid(format!("{what}: too many symbolic links resolving '{path}'"))
    }
    _ => io(what, e),
  }
}

struct Entry {
  is_file: bool,
  is_dir: bool,
  len: u64,
  mtime: i64,
  ctime: i64,
}

impl Entry {
  fn of_parts(
    is_file: bool,
    is_dir: bool,
    len: u64,
    modified: Option<std::time::SystemTime>,
    ctime: i64,
    ctime_nsec: i64,
  ) -> Entry {
    let mtime = mtime_millis(modified);
    Entry {
      is_file,
      is_dir,
      len,
      mtime,
      ctime: unix_ctime_millis(ctime, ctime_nsec).unwrap_or(mtime),
    }
  }

  fn of_ambient(meta: &fs::Metadata) -> Entry {
    use std::os::unix::fs::MetadataExt;
    Entry::of_parts(meta.is_file(), meta.is_dir(), meta.len(), meta.modified().ok(), meta.ctime(), meta.ctime_nsec())
  }

  fn of_confined(meta: &cap_std::fs::Metadata) -> Entry {
    use cap_std::fs::MetadataExt;
    Entry::of_parts(
      meta.is_file(),
      meta.is_dir(),
      meta.len(),
      meta.modified().ok().map(|time| time.into_std()),
      meta.ctime(),
      meta.ctime_nsec(),
    )
  }
}

enum Target<'a> {
  Confined { root: &'a Path, dir: &'a Dir, path: PathBuf },
  Ambient(PathBuf),
}

impl Target<'_> {
  fn join(&self, name: &OsStr) -> Self {
    match self {
      Target::Confined { root, dir, path } => Target::Confined { root, dir, path: path.join(name) },
      Target::Ambient(path) => Target::Ambient(path.join(name)),
    }
  }

  fn metadata(&self) -> std::io::Result<Entry> {
    match self {
      Target::Confined { dir, path, .. } => dir.metadata(path).map(|meta| Entry::of_confined(&meta)),
      Target::Ambient(path) => fs::metadata(path).map(|meta| Entry::of_ambient(&meta)),
    }
  }

  fn is_directory_itself(&self) -> std::io::Result<bool> {
    match self {
      Target::Confined { dir, path, .. } => dir.symlink_metadata(path).map(|meta| meta.is_dir()),
      Target::Ambient(path) => fs::symlink_metadata(path).map(|meta| meta.is_dir()),
    }
  }

  fn size(&self) -> u64 {
    self.metadata().map(|entry| if entry.is_file { entry.len } else { 0 }).unwrap_or(0)
  }

  fn is_root(&self) -> bool {
    match self {
      Target::Confined { dir, path, .. } => {
        dir.canonicalize(path).is_ok_and(|canonical| canonical.components().all(|c| c == Component::CurDir))
      }
      Target::Ambient(_) => false,
    }
  }

  fn read(&self) -> std::io::Result<Vec<u8>> {
    match self {
      Target::Confined { dir, path, .. } => dir.read(path),
      Target::Ambient(path) => fs::read(path),
    }
  }

  fn open_for_write(&self, append: bool) -> std::io::Result<fs::File> {
    match self {
      Target::Confined { dir, path, .. } => {
        let mut options = cap_std::fs::OpenOptions::new();
        options.write(true).create(true).append(append).truncate(!append);
        dir.open_with(path, &options).map(cap_std::fs::File::into_std)
      }
      Target::Ambient(path) => {
        fs::OpenOptions::new().write(true).create(true).append(append).truncate(!append).open(path)
      }
    }
  }

  fn create_dir_all(&self) -> std::io::Result<()> {
    match self {
      Target::Confined { dir, path, .. } => dir.create_dir_all(path),
      Target::Ambient(path) => fs::create_dir_all(path),
    }
  }

  fn remove_file(&self) -> std::io::Result<()> {
    match self {
      Target::Confined { dir, path, .. } => dir.remove_file(path),
      Target::Ambient(path) => fs::remove_file(path),
    }
  }

  fn remove_dir(&self) -> std::io::Result<()> {
    match self {
      Target::Confined { dir, path, .. } => dir.remove_dir(path),
      Target::Ambient(path) => fs::remove_dir(path),
    }
  }

  fn remove_dir_all(&self) -> std::io::Result<()> {
    match self {
      Target::Confined { dir, path, .. } => dir.remove_dir_all(path),
      Target::Ambient(path) => fs::remove_dir_all(path),
    }
  }

  fn read_dir_names(&self) -> std::io::Result<Vec<std::ffi::OsString>> {
    match self {
      Target::Confined { dir, path, .. } => Ok(dir.read_dir(path)?.flatten().map(|entry| entry.file_name()).collect()),
      Target::Ambient(path) => Ok(fs::read_dir(path)?.flatten().map(|entry| entry.file_name()).collect()),
    }
  }

  fn copy_to(&self, to: &Target<'_>) -> std::io::Result<()> {
    match (self, to) {
      (Target::Confined { dir, path, .. }, Target::Confined { dir: to_dir, path: to_path, .. }) => {
        let mut reader = dir.open(path)?.into_std();
        let mut options = cap_std::fs::OpenOptions::new();
        options.write(true).create(true).truncate(true);
        let mut writer = to_dir.open_with(to_path, &options)?.into_std();
        std::io::copy(&mut reader, &mut writer).map(|_| ())
      }
      (Target::Ambient(path), Target::Ambient(to_path)) => fs::copy(path, to_path).map(|_| ()),
      _ => Err(std::io::Error::other("the two paths are in different storage")),
    }
  }

  fn rename_to(&self, to: &Target<'_>) -> std::io::Result<()> {
    match (self, to) {
      (Target::Confined { dir, path, .. }, Target::Confined { dir: to_dir, path: to_path, .. }) => {
        dir.rename(path, to_dir, to_path)
      }
      (Target::Ambient(path), Target::Ambient(to_path)) => fs::rename(path, to_path),
      _ => Err(std::io::Error::other("the two paths are in different storage")),
    }
  }
}

impl FsState {
  fn resolve_path(&self, input: &str) -> FsResult<Target<'_>> {
    let Some(root) = self.storage.root() else {
      return Err(Fault::Io("fs: this plugin has no storage directory".to_string()));
    };
    if input.is_empty() {
      return Err(Fault::Invalid("fs: the path is empty".to_string()));
    }
    if input.contains('\0') {
      return Err(Fault::Invalid("fs: the path contains a NUL".to_string()));
    }
    let raw = Path::new(input);
    match &self.storage {
      Storage::Confined { root, dir } if !raw.is_absolute() => {
        Ok(Target::Confined { root, dir, path: raw.to_path_buf() })
      }
      Storage::Confined { .. } => Err(Fault::Escape(input.to_string())),
      _ => Ok(Target::Ambient(root.join(raw))),
    }
  }

  pub(crate) fn resolve_external(&self, ctx: &Ctx<'_>, input: &str) -> JsResult<PathBuf> {
    self.grants.check_grant(ctx, self.grant(), None, MATCH_EXACT)?;
    let resolved = self.resolve_path(input).and_then(|target| match target {
      Target::Confined { root, dir, path } => {
        dir.canonicalize(&path).map(|canonical| root.join(canonical)).map_err(|e| io_at("fs", input, e))
      }
      Target::Ambient(path) => Ok(path),
    });
    match resolved {
      Ok(path) => Ok(path),
      Err(fault) => fault.throw(ctx),
    }
  }
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

impl FsState {
  fn usage(&self) -> u64 {
    if let Some(cached) = self.usage.get() {
      return cached;
    }
    let total = self.storage.root().map(walk_usage).unwrap_or(0);
    self.usage.set(Some(total));
    total
  }

  fn check_quota(&self, adding: u64, replacing: u64) -> FsResult<u64> {
    let usage = self.usage();
    let after = usage.saturating_sub(replacing).saturating_add(adding);
    if self.unscoped {
      return Ok(after);
    }
    if after > self.quota {
      return Err(Fault::Quota {
        usage: after,
        quota: self.quota,
        message: format!(
          "fs: this would leave {after} bytes in a directory capped at {}; ask for more with @grant fs(...)",
          self.quota,
        ),
      });
    }
    Ok(after)
  }
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
      Source::Blob(export) => export.write_to(file).map_err(|fault| match fault {
        BlobFault::Gone(message) => Fault::Gone(format!("fs: {message}")),
        other => Fault::Io(format!("fs: {}", other.message())),
      }),
    }
  }
}

impl FsState {
  fn read_source(&self, value: &Value<'_>) -> FsResult<Source> {
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      let Some(bytes) = qjs_read_typed_bytes(&typed, <[u8]>::to_vec) else {
        return Err(Fault::Invalid("fs: the array is detached".to_string()));
      };
      return Ok(Source::Bytes(bytes));
    }
    if let Some(export) = crate::api::io::blob::export_blob(value) {
      return Ok(Source::Blob(export));
    }
    if rquickjs::Class::<BlobHandle>::from_value(value).is_ok() {
      return Err(Fault::Gone("fs: the blob being written was disposed".to_string()));
    }
    Err(Fault::Invalid("fs: expected a Blob or a Uint8Array".to_string()))
  }

  fn op_read<'js>(&self, ctx: &Ctx<'js>, input: &str) -> FsResult<Value<'js>> {
    let target = self.resolve_path(input)?;
    let entry = target.metadata().map_err(|e| io_at("read", input, e))?;
    if !entry.is_file {
      return Err(Fault::Invalid(format!("read: '{input}' is not a file")));
    }
    if entry.len > MATERIALIZE_LIMIT_BYTES {
      return Err(Fault::Quota {
        usage: entry.len,
        quota: MATERIALIZE_LIMIT_BYTES,
        message: format!(
          "read: {} bytes is past the {MATERIALIZE_LIMIT_BYTES} one read may take into javascript",
          entry.len,
        ),
      });
    }
    let bytes = target.read().map_err(|e| io_at("read", input, e))?;
    TypedArray::<u8>::new(ctx.clone(), bytes)
      .map(|array| array.into_value())
      .map_err(|e| Fault::Io(format!("read: {e:?}")))
  }

  fn op_write(&self, input: &str, source: Source, append: bool) -> FsResult<()> {
    let what = if append { "append" } else { "write" };
    let target = self.resolve_path(input)?;
    if target.metadata().is_ok_and(|entry| entry.is_dir) {
      return Err(Fault::Invalid(format!("{what}: '{input}' is a directory")));
    }
    let replacing = if append { 0 } else { target.size() };
    let after = self.check_quota(source.len(), replacing)?;

    let mut file = target.open_for_write(append).map_err(|e| io_at(what, input, e))?;
    if let Err(e) = source.write_into(&mut file) {
      self.usage.set(None);
      return Err(e);
    }
    self.usage.set(Some(after));
    Ok(())
  }

  fn op_mkdir(&self, input: &str) -> FsResult<()> {
    self.resolve_path(input)?.create_dir_all().map_err(|e| io_at("mkdir", input, e))
  }

  fn op_rm(&self, input: &str, recursive: bool) -> FsResult<()> {
    let target = self.resolve_path(input)?;
    let is_root = match &target {
      Target::Ambient(path) => self.storage.root().is_some_and(|root| fs::canonicalize(path).is_ok_and(|c| c == root)),
      Target::Confined { .. } => target.is_root(),
    };
    if is_root {
      return Err(Fault::Invalid("rm: the plugin's own directory cannot be removed".to_string()));
    }
    let is_dir = match target.is_directory_itself() {
      Ok(is_dir) => is_dir,
      Err(e) => {
        let fault = io_at("rm", input, e);
        return if fault.is_refusal() { Err(fault) } else { Ok(()) };
      }
    };
    self.usage.set(None);
    if !is_dir {
      return target.remove_file().map_err(|e| io_at("rm", input, e));
    }
    if recursive {
      target.remove_dir_all().map_err(|e| io_at("rm", input, e))
    } else {
      target
        .remove_dir()
        .map_err(|_| Fault::Invalid(format!("rm: '{input}' is a directory; pass {{ recursive: true }}")))
    }
  }

  fn op_exists(&self, input: &str) -> FsResult<bool> {
    match self.resolve_path(input)?.metadata() {
      Ok(_) => Ok(true),
      Err(e) => {
        let fault = io_at("exists", input, e);
        if fault.is_refusal() {
          Err(fault)
        } else {
          Ok(false)
        }
      }
    }
  }

  fn op_readdir(&self, input: &str) -> FsResult<Vec<String>> {
    let target = self.resolve_path(input)?;
    let mut names: Vec<String> = target
      .read_dir_names()
      .map_err(|e| io_at("readdir", input, e))?
      .into_iter()
      .map(|name| name.to_string_lossy().into_owned())
      .collect();
    names.sort();
    Ok(names)
  }

  fn op_stat(&self, input: &str) -> FsResult<Entry> {
    let mut entry = self.resolve_path(input)?.metadata().map_err(|e| io_at("stat", input, e))?;
    if !entry.is_file {
      entry.len = 0;
    }
    Ok(entry)
  }
}

fn unix_ctime_millis(seconds: i64, nanos: i64) -> Option<i64> {
  if seconds == 0 && nanos == 0 {
    return None;
  }
  Some(seconds.saturating_mul(1000).saturating_add(nanos / 1_000_000))
}

impl FsState {
  fn op_copy(&self, src_input: &str, dest_input: &str) -> FsResult<()> {
    let src = self.resolve_path(src_input)?;
    let dest = self.resolve_path(dest_input)?;
    let entry = src.metadata().map_err(|e| io_at("copy", src_input, e))?;
    if !entry.is_file {
      return Err(Fault::Invalid(format!("copy: '{src_input}' is not a file")));
    }
    self.check_quota(entry.len, dest.size())?;
    self.usage.set(None);
    src.copy_to(&dest).map_err(|e| io_at("copy", dest_input, e))
  }

  fn op_move(&self, src_input: &str, dest_input: &str) -> FsResult<()> {
    let src = self.resolve_path(src_input)?;
    let dest = self.resolve_path(dest_input)?;
    if let Err(e) = src.is_directory_itself() {
      let fault = io_at("move", src_input, e);
      return Err(if fault.is_refusal() {
        fault
      } else {
        Fault::NotFound(format!("move: '{src_input}' does not exist"))
      });
    }
    self.usage.set(None);
    match src.rename_to(&dest) {
      Ok(()) => Ok(()),
      Err(e) if e.raw_os_error() == Some(EXDEV) => {
        copy_tree(&src, &dest).map_err(|e| io_at("move", dest_input, e))?;
        remove_tree(&src).map_err(|e| io_at("move", src_input, e))
      }
      Err(e) => Err(io_at("move", dest_input, e)),
    }
  }
}

fn copy_tree(src: &Target<'_>, dest: &Target<'_>) -> std::io::Result<()> {
  if !src.metadata()?.is_dir {
    return src.copy_to(dest);
  }
  dest.create_dir_all()?;
  for name in src.read_dir_names()? {
    copy_tree(&src.join(&name), &dest.join(&name))?;
  }
  Ok(())
}

fn remove_tree(target: &Target<'_>) -> std::io::Result<()> {
  if target.metadata()?.is_dir {
    target.remove_dir_all()
  } else {
    target.remove_file()
  }
}

#[allow(clippy::too_many_arguments)]
pub fn install_fs<'js>(
  ctx: &Ctx<'js>,
  grants: Rc<dyn GrantHost>,
  root: &Path,
  quota: u64,
  unscoped: bool,
  android_dirs: &str,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FsState>> {
  let storage = open_storage(root, unscoped);
  let state = Rc::new(FsState {
    grants,
    storage,
    quota,
    unscoped,
    usage: Cell::new(None),
    android_dirs: android_dirs.split('\n').map(str::to_string).collect(),
  });

  let fs_obj = Object::new(ctx.clone())?;

  set_fn!(fs_obj, "read", ctx, state, move |ctx: Ctx<'js>, path: String| -> JsResult<Value<'js>> {
    state.gate(&ctx)?;
    state.op_read(&ctx, &path).or_else(|fault| fault.throw(&ctx))
  });

  for (name, append) in [("write", false), ("append", true)] {
    let state = state.clone();
    fs_obj.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, path: String, data: Value<'js>| -> JsResult<()> {
        state.gate(&ctx)?;
        state
          .read_source(&data)
          .and_then(|source| state.op_write(&path, source, append))
          .or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  set_fn!(fs_obj, "mkdir", ctx, state, move |ctx: Ctx<'js>, path: String| -> JsResult<()> {
    state.gate(&ctx)?;
    state.op_mkdir(&path).or_else(|fault| fault.throw(&ctx))
  });

  set_fn!(fs_obj, "rm", ctx, state, move |ctx: Ctx<'js>,
                                          path: String,
                                          options: Opt<Value<'js>>|
        -> JsResult<()> {
    state.gate(&ctx)?;
    let recursive = match options.0.as_ref().and_then(|v| v.as_object()) {
      Some(options) => options.get::<_, Option<bool>>("recursive")?.unwrap_or(false),
      None => false,
    };
    state.op_rm(&path, recursive).or_else(|fault| fault.throw(&ctx))
  });

  set_fn!(fs_obj, "exists", ctx, state, move |ctx: Ctx<'js>, path: String| -> JsResult<bool> {
    state.gate(&ctx)?;
    state.op_exists(&path).or_else(|fault| fault.throw(&ctx))
  });

  set_fn!(fs_obj, "readdir", ctx, state, move |ctx: Ctx<'js>, path: String| -> JsResult<Vec<String>> {
    state.gate(&ctx)?;
    state.op_readdir(&path).or_else(|fault| fault.throw(&ctx))
  });

  set_fn!(fs_obj, "stat", ctx, state, move |ctx: Ctx<'js>, path: String| -> JsResult<Object<'js>> {
    state.gate(&ctx)?;
    let stat = state.op_stat(&path).or_else(|fault| fault.throw(&ctx))?;
    let obj = Object::new(ctx.clone())?;
    obj.set("isFile", stat.is_file)?;
    obj.set("isDirectory", stat.is_dir)?;
    obj.set("size", stat.len as f64)?;
    obj.set("mtime", stat.mtime as f64)?;
    obj.set("ctime", stat.ctime as f64)?;
    Ok(obj)
  });

  for (name, is_move) in [("copy", false), ("move", true)] {
    let state = state.clone();
    fs_obj.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, src: String, dest: String| -> JsResult<()> {
        state.gate(&ctx)?;
        let done = if is_move { state.op_move(&src, &dest) } else { state.op_copy(&src, &dest) };
        done.or_else(|fault| fault.throw(&ctx))
      })?,
    )?;
  }

  set_fn!(fs_obj, "usage", ctx, state, move |ctx: Ctx<'js>| -> JsResult<f64> {
    state.gate(&ctx)?;
    if state.storage.root().is_none() {
      return Fault::Io("fs: this plugin has no storage directory".to_string()).throw(&ctx);
    }
    Ok(state.usage() as f64)
  });

  set_fn!(fs_obj, "quota", ctx, state, move |ctx: Ctx<'js>| -> JsResult<f64> {
    state.gate(&ctx)?;
    Ok(if state.quota == UNCAPPED { f64::INFINITY } else { state.quota as f64 })
  });

  globals.inu.set("fs", fs_obj)?;
  state.install_android_dirs(ctx, globals)?;
  Ok(state)
}

fn open_storage(root: &Path, unscoped: bool) -> Storage {
  if root.as_os_str().is_empty() {
    return Storage::Missing;
  }
  if unscoped {
    return Storage::Ambient {
      root: fs::canonicalize(root).unwrap_or_else(|_| root.to_path_buf()),
    };
  }
  let opened = fs::create_dir_all(root)
    .and_then(|()| fs::canonicalize(root))
    .and_then(|root| Dir::open_ambient_dir(&root, ambient_authority()).map(|dir| (root, dir)));
  match opened {
    Ok((root, dir)) => Storage::Confined { root, dir },
    Err(_) => Storage::Missing,
  }
}

impl FsState {
  fn install_android_dirs<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
    let android = globals.get_namespace(ctx, "android")?;

    for (name, index) in [("getPluginsDir", 0usize), ("getCacheDir", 1)] {
      let state = self.clone();
      let f =
        Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<String> { state.android_dir(&ctx, index, name) })?;
      android.set(name, f)?;
    }
    {
      let state = self.clone();
      android.set(
        "getMediaDir",
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, kind: String| -> JsResult<String> {
          let Some(index) = ANDROID_DIR_NAMES.iter().position(|k| *k == kind) else {
            return PluginErrorCode::InvalidArgument
              .throw(&ctx, &format!("getMediaDir: '{kind}' is not one of {}", ANDROID_DIR_NAMES.join(", ")));
          };
          state.android_dir(&ctx, 2 + index, "getMediaDir")
        })?,
      )?;
    }
    Ok(())
  }

  fn android_dir(&self, ctx: &Ctx<'_>, index: usize, what: &str) -> JsResult<String> {
    self.grants.check_grant(ctx, "unsafe.fs", None, MATCH_EXACT)?;
    match self.android_dirs.get(index) {
      Some(path) if !path.is_empty() => Ok(path.clone()),
      _ => PluginErrorCode::NotFound.throw(ctx, &format!("{what}: the app has no such directory")),
    }
  }

  fn gate(&self, ctx: &Ctx<'_>) -> JsResult<()> {
    self.grants.check_grant(ctx, self.grant(), None, MATCH_EXACT)
  }
}

#[cfg(test)]
#[path = "fs_tests.rs"]
pub(crate) mod tests;
