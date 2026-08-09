use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::rc::{Rc, Weak};
use std::time::{SystemTime, UNIX_EPOCH};

use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::{Constructor, Opt, This};
use rquickjs::object::Property;
use rquickjs::{
  ArrayBuffer, Class, Coerced, Ctx, Exception, FromJs, Function, JsLifetime, Object, Result as JsResult, TypedArray,
  Value,
};

use crate::api::error::{make_plugin_error, throw_plugin_error};
use crate::sandbox::limits::{ExternalCharge, ExternalMemory, EXTERNAL_LIMIT_BYTES, HEAP_LIMIT_BYTES};
use crate::utils::shape::{define_getter, define_method};

pub const SPILL_THRESHOLD_BYTES: u64 = 2 * 1024 * 1024;

pub const MATERIALIZE_LIMIT_BYTES: u64 = (HEAP_LIMIT_BYTES / 2) as u64;

pub const TEXT_LIMIT_BYTES: u64 = MATERIALIZE_LIMIT_BYTES / 2;

pub const BUILD_LIMIT_BYTES: u64 = 32 * 1024 * 1024;

pub const SPILL_LIMIT_BYTES: u64 = 2 * 1024 * 1024 * 1024;

pub const SPILL_FILE_LIMIT: usize = 64;

const COPY_CHUNK_BYTES: usize = 256 * 1024;

const MIN_MEMORY_CAPACITY: usize = 4096;

const EXPORT_SWEEP_AT: usize = 64;

const ENOSPC: i32 = 28;

pub struct BlobState {
  spill_dir: PathBuf,
  external: Rc<ExternalMemory>,
  limits: BlobLimits,
  spilled: Cell<u64>,
  open_spills: Cell<usize>,
  next_file: Cell<u64>,
  next_export: Cell<i64>,
  exported: RefCell<HashMap<i64, Export>>,
}

#[derive(Clone, Copy)]
pub(crate) struct BlobLimits {
  pub build: u64,
  pub spill_bytes: u64,
  pub spill_files: usize,
}

impl Default for BlobLimits {
  fn default() -> Self {
    BlobLimits {
      build: BUILD_LIMIT_BYTES,
      spill_bytes: SPILL_LIMIT_BYTES,
      spill_files: SPILL_FILE_LIMIT,
    }
  }
}

impl BlobState {
  fn can_spill(&self) -> bool {
    !self.spill_dir.as_os_str().is_empty()
  }

  fn open_spill(self: &Rc<Self>, ctx: &Ctx<'_>) -> Result<SpillFile, BlobFault> {
    if !self.can_spill() {
      return Err(BlobFault::Io("this engine has no spill directory".to_string()));
    }
    if self.open_spills.get() >= self.limits.spill_files {
      ctx.run_gc();
    }
    if self.open_spills.get() >= self.limits.spill_files {
      return Err(BlobFault::Quota {
        usage: self.open_spills.get() as u64 + 1,
        quota: self.limits.spill_files as u64,
        message: format!(
          "this plugin already holds {} blobs too large to keep in memory, which is all the open files it may have; dispose the ones it is done with",
          self.open_spills.get(),
        ),
      });
    }
    fs::create_dir_all(&self.spill_dir).map_err(io_fault)?;
    let index = self.next_file.get();
    self.next_file.set(index + 1);
    let path = self.spill_dir.join(format!("{index}.bin"));
    let file = fs::OpenOptions::new()
      .read(true)
      .write(true)
      .create(true)
      .truncate(true)
      .open(&path)
      .map_err(io_fault)?;
    self.open_spills.set(self.open_spills.get() + 1);
    Ok(SpillFile {
      state: self.clone(),
      file,
      path,
      charged: Cell::new(0),
    })
  }

  fn release_spill(&self, bytes: u64) {
    self.spilled.set(self.spilled.get().saturating_sub(bytes));
    self.open_spills.set(self.open_spills.get().saturating_sub(1));
  }

  #[cfg(test)]
  fn spilled_bytes(&self) -> u64 {
    self.spilled.get()
  }

  #[cfg(test)]
  fn open_spills(&self) -> usize {
    self.open_spills.get()
  }

  #[cfg(test)]
  fn exported_ids(&self) -> usize {
    self.exported.borrow().len()
  }

  #[cfg(test)]
  pub fn charged_bytes(&self) -> usize {
    self.external.charged_bytes()
  }
}

struct SpillFile {
  state: Rc<BlobState>,
  file: fs::File,
  path: PathBuf,
  charged: Cell<u64>,
}

impl SpillFile {
  fn reserve(&self, ctx: &Ctx<'_>, total: u64) -> Result<(), BlobFault> {
    let extra = total.saturating_sub(self.charged.get());
    if extra == 0 {
      return Ok(());
    }
    if self.state.spilled.get().saturating_add(extra) > self.state.limits.spill_bytes {
      ctx.run_gc();
    }
    let live = self.state.spilled.get();
    let wanted = live.saturating_add(extra);
    if wanted > self.state.limits.spill_bytes {
      return Err(BlobFault::Quota {
        usage: wanted,
        quota: self.state.limits.spill_bytes,
        message: format!(
          "this plugin holds {:.1} MB of spilled blob content and asked for {:.1} MB more, past its ceiling of {} MB",
          live as f64 / (1024.0 * 1024.0),
          extra as f64 / (1024.0 * 1024.0),
          self.state.limits.spill_bytes / (1024 * 1024),
        ),
      });
    }
    self.state.spilled.set(wanted);
    self.charged.set(total);
    Ok(())
  }
}

impl Drop for SpillFile {
  fn drop(&mut self) {
    let _ = fs::remove_file(&self.path);
    self.state.release_spill(self.charged.get());
  }
}

enum BackingKind {
  Memory { bytes: Vec<u8>, _charge: Option<ExternalCharge> },
  Spill(SpillFile),
  AppFile { path: PathBuf, mtime_ms: i64 },
  Freed,
}

pub struct Backing {
  kind: RefCell<BackingKind>,
  len: u64,
}

impl Backing {
  #[cfg(test)]
  fn memory_capacity(&self) -> Option<usize> {
    match &*self.kind.borrow() {
      BackingKind::Memory { bytes, .. } => Some(bytes.capacity()),
      _ => None,
    }
  }

  fn alive(&self) -> bool {
    !matches!(*self.kind.borrow(), BackingKind::Freed)
  }

  fn release(&self) {
    let kind = std::mem::replace(&mut *self.kind.borrow_mut(), BackingKind::Freed);
    drop(kind);
  }

  fn read(&self, start: u64, end: u64) -> Result<Vec<u8>, BlobFault> {
    let len = end.saturating_sub(start);
    if len == 0 {
      return Ok(Vec::new());
    }
    match &*self.kind.borrow() {
      BackingKind::Freed => Err(disposed_backing()),
      BackingKind::Memory { bytes, .. } => {
        bytes.get(start as usize..end as usize).map(<[u8]>::to_vec).ok_or_else(disposed_backing)
      }
      BackingKind::Spill(spill) => {
        read_exact_at(&spill.file, start, len as usize).map_err(|e| BlobFault::Io(format!("spilled blob: {e}")))
      }
      BackingKind::AppFile { path, mtime_ms } => {
        let file = fs::File::open(path).map_err(|_| vanished())?;
        let meta = file.metadata().map_err(|_| vanished())?;
        if meta.len() < self.len || mtime_millis(&meta) != *mtime_ms {
          return Err(vanished());
        }
        read_exact_at(&file, start, len as usize).map_err(|_| vanished())
      }
    }
  }

  fn copy_into(&self, ctx: &Ctx<'_>, start: u64, end: u64, sink: &mut Accumulator) -> Result<(), BlobFault> {
    sink.check_room(end.saturating_sub(start))?;
    if let BackingKind::Memory { bytes, .. } = &*self.kind.borrow() {
      let slice = bytes.get(start as usize..end as usize).ok_or_else(disposed_backing)?;
      return sink.write(ctx, slice);
    }
    let mut at = start;
    while at < end {
      let take = (end - at).min(COPY_CHUNK_BYTES as u64);
      let chunk = self.read(at, at + take)?;
      sink.write(ctx, &chunk)?;
      at += take;
    }
    Ok(())
  }
}

impl Drop for Backing {
  fn drop(&mut self) {
    self.release();
  }
}

fn disposed_backing() -> BlobFault {
  BlobFault::Gone("the blob this content belongs to was disposed".to_string())
}

fn vanished() -> BlobFault {
  BlobFault::Gone("the file behind this blob is gone or has been replaced".to_string())
}

fn io_fault(e: std::io::Error) -> BlobFault {
  if e.raw_os_error() == Some(ENOSPC) {
    return BlobFault::Quota {
      usage: 0,
      quota: 0,
      message: "there is no room left on the device for this blob".to_string(),
    };
  }
  BlobFault::Io(format!("blob storage: {e}"))
}

fn mtime_millis(meta: &fs::Metadata) -> i64 {
  let Ok(modified) = meta.modified() else {
    return 0;
  };
  match modified.duration_since(UNIX_EPOCH) {
    Ok(d) => d.as_millis() as i64,
    Err(e) => -(e.duration().as_millis() as i64),
  }
}

fn read_exact_at(file: &fs::File, mut offset: u64, len: usize) -> std::io::Result<Vec<u8>> {
  let mut buf = vec![0u8; len];
  let mut done = 0;
  while done < len {
    let read = file.read_at(&mut buf[done..], offset)?;
    if read == 0 {
      return Err(std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "the content ended before the range did"));
    }
    done += read;
    offset += read as u64;
  }
  Ok(buf)
}

pub(crate) enum BlobFault {
  Gone(String),
  Quota { usage: u64, quota: u64, message: String },
  Io(String),
}

impl BlobFault {
  fn to_value<'js>(&self, ctx: &Ctx<'js>) -> JsResult<Value<'js>> {
    match self {
      BlobFault::Gone(message) => make_plugin_error(ctx, "handle-expired", message, None, None, None),
      BlobFault::Quota { usage, quota, message } => make_plugin_error(
        ctx,
        "quota-exceeded",
        message,
        None,
        (*usage > 0).then_some(*usage as i64),
        (*quota > 0).then_some(*quota as i64),
      ),
      BlobFault::Io(message) => make_plugin_error(ctx, "internal", message, None, None, None),
    }
  }

  fn throw<'js, T>(&self, ctx: &Ctx<'js>) -> JsResult<T> {
    let value = self.to_value(ctx)?;
    Err(ctx.throw(value))
  }
}

struct Accumulator {
  state: Rc<BlobState>,
  memory: Vec<u8>,
  charge: Option<ExternalCharge>,
  spill: Option<SpillFile>,
  len: u64,
  limit: u64,
}

impl Accumulator {
  fn new(state: Rc<BlobState>, limit: u64) -> Self {
    Accumulator {
      state,
      memory: Vec::new(),
      charge: None,
      spill: None,
      len: 0,
      limit,
    }
  }

  fn check_room(&self, add: u64) -> Result<(), BlobFault> {
    let after = self.len.saturating_add(add);
    if after <= self.limit {
      return Ok(());
    }
    Err(BlobFault::Quota {
      usage: after,
      quota: self.limit,
      message: format!(
        "building a blob of {:.1} MB is past the {} MB one may assemble in a single call; join it in pieces or write it out with inu.fs",
        after as f64 / (1024.0 * 1024.0),
        self.limit / (1024 * 1024),
      ),
    })
  }

  fn write(&mut self, ctx: &Ctx<'_>, data: &[u8]) -> Result<(), BlobFault> {
    if data.is_empty() {
      return Ok(());
    }
    self.check_room(data.len() as u64)?;
    let after = self.len + data.len() as u64;
    if self.spill.is_none() && after > SPILL_THRESHOLD_BYTES && self.state.can_spill() {
      self.start_spilling(ctx)?;
    }
    if self.spill.is_none() && !self.reserve_memory(ctx, after)? {
      self.start_spilling(ctx)?;
    }
    match self.spill.as_mut() {
      Some(spill) => {
        spill.reserve(ctx, after)?;
        spill.file.write_all(data).map_err(io_fault)?;
      }
      None => self.memory.extend_from_slice(data),
    }
    self.len = after;
    Ok(())
  }

  fn reserve_memory(&mut self, ctx: &Ctx<'_>, after: u64) -> Result<bool, BlobFault> {
    if after <= self.memory.capacity() as u64 {
      return Ok(true);
    }
    let target = after
      .max((self.memory.capacity() as u64).saturating_mul(2))
      .max(MIN_MEMORY_CAPACITY as u64)
      .min(self.limit.max(after));
    if !self.charge_up_to(ctx, target) {
      if !self.state.can_spill() {
        let held = self.charge.as_ref().map_or(0, ExternalCharge::bytes) as u64;
        let wanted = target - held;
        return Err(BlobFault::Quota {
          usage: self.state.external.charged_bytes() as u64 + wanted,
          quota: EXTERNAL_LIMIT_BYTES as u64,
          message: format!(
            "this plugin holds {:.1} MB of native memory, this blob needs {:.1} MB more, and this engine has nowhere to spill it to",
            self.state.external.charged_bytes() as f64 / (1024.0 * 1024.0),
            wanted as f64 / (1024.0 * 1024.0),
          ),
        });
      }
      return Ok(false);
    }
    self.memory.reserve_exact(target as usize - self.memory.len());
    let _ = self.charge_up_to(ctx, self.memory.capacity() as u64);
    Ok(true)
  }

  fn charge_up_to(&mut self, ctx: &Ctx<'_>, total: u64) -> bool {
    let held = self.charge.as_ref().map_or(0, ExternalCharge::bytes) as u64;
    let Some(extra) = total.checked_sub(held).filter(|extra| *extra > 0) else {
      return true;
    };
    match self.charge.as_mut() {
      Some(charge) => charge.try_grow(ctx, extra as usize),
      None => match self.state.external.try_charge(ctx, extra as usize) {
        Some(charge) => {
          self.charge = Some(charge);
          true
        }
        None => false,
      },
    }
  }

  fn start_spilling(&mut self, ctx: &Ctx<'_>) -> Result<(), BlobFault> {
    let spill = self.state.open_spill(ctx)?;
    spill.reserve(ctx, self.len)?;
    (&spill.file).write_all(&self.memory).map_err(io_fault)?;
    self.memory = Vec::new();
    self.charge = None;
    self.spill = Some(spill);
    Ok(())
  }

  fn finish(self) -> Rc<Backing> {
    let Accumulator { mut memory, mut charge, spill, len, .. } = self;
    if let Some(spill) = spill {
      return Rc::new(Backing {
        kind: RefCell::new(BackingKind::Spill(spill)),
        len,
      });
    }
    memory.shrink_to_fit();
    if let Some(charge) = charge.as_mut() {
      charge.shrink_to(memory.capacity());
    }
    Rc::new(Backing {
      kind: RefCell::new(BackingKind::Memory { bytes: memory, _charge: charge }),
      len,
    })
  }
}

struct FileMeta {
  name: String,
  last_modified: f64,
}

pub struct BlobHandle {
  backing: RefCell<Option<Rc<Backing>>>,
  start: u64,
  end: u64,
  mime: String,
  export_id: Cell<Option<i64>>,
  owns_backing: bool,
  meta: Option<FileMeta>,
}

impl<'js> Trace<'js> for BlobHandle {
  fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
}

unsafe impl<'js> JsLifetime<'js> for BlobHandle {
  type Changed<'to> = BlobHandle;
}

impl<'js> JsClass<'js> for BlobHandle {
  const NAME: &'static str = "Blob";
  type Mutable = Readable;

  fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
    Ok(None)
  }
}

impl BlobHandle {
  fn size(&self) -> u64 {
    self.end - self.start
  }

  fn live(&self, ctx: &Ctx<'_>) -> JsResult<Rc<Backing>> {
    match self.backing.borrow().clone() {
      Some(backing) => Ok(backing),
      None => throw_plugin_error(ctx, "handle-expired", "this blob was disposed", None, None, None),
    }
  }

  fn dispose(&self) {
    let Some(backing) = self.backing.borrow_mut().take() else {
      return;
    };
    if self.owns_backing {
      backing.release();
    }
  }

  fn read_all(&self, limit: u64) -> Result<Vec<u8>, BlobFault> {
    let Some(backing) = self.backing.borrow().clone() else {
      return Err(BlobFault::Gone("this blob was disposed".to_string()));
    };
    if self.size() > limit {
      return Err(BlobFault::Quota {
        usage: self.size(),
        quota: limit,
        message: format!(
          "reading {:.1} MB into javascript is past the {} MB a single read may take; slice it or write it out with inu.fs",
          self.size() as f64 / (1024.0 * 1024.0),
          limit / (1024 * 1024),
        ),
      });
    }
    backing.read(self.start, self.end)
  }
}

fn make_view(backing: Rc<Backing>, start: u64, end: u64, mime: String, meta: Option<FileMeta>) -> BlobHandle {
  BlobHandle {
    backing: RefCell::new(Some(backing)),
    start,
    end,
    mime,
    owns_backing: false,
    export_id: Cell::new(None),
    meta,
  }
}

const LABEL_LIMIT_CHARS: usize = 1024;

fn truncate_label(raw: &str) -> String {
  match raw.char_indices().nth(LABEL_LIMIT_CHARS) {
    Some((at, _)) => raw[..at].to_string(),
    None => raw.to_string(),
  }
}

fn normalize_mime(raw: &str) -> String {
  if raw.chars().any(|c| !(' '..='~').contains(&c)) {
    return String::new();
  }
  truncate_label(&raw.to_ascii_lowercase())
}

fn read_mime_option<'js>(options: &Opt<Value<'js>>) -> JsResult<String> {
  let Some(options) = options.0.as_ref().and_then(|v| v.as_object()) else {
    return Ok(String::new());
  };
  match options.get::<_, Option<Coerced<String>>>("type")? {
    Some(value) => Ok(normalize_mime(&value.0)),
    None => Ok(String::new()),
  }
}

fn clamp_index(value: Option<f64>, size: u64, default: u64) -> u64 {
  let Some(value) = value else { return default };
  if value.is_nan() {
    return 0;
  }
  let value = value.trunc();
  if value < 0.0 {
    let from_end = size as f64 + value;
    if from_end <= 0.0 {
      return 0;
    }
    return from_end as u64;
  }
  if value >= size as f64 {
    return size;
  }
  value as u64
}

fn append_part<'js>(ctx: &Ctx<'js>, sink: &mut Accumulator, part: Value<'js>) -> JsResult<()> {
  if let Ok(class) = Class::<BlobHandle>::from_value(&part) {
    let (backing, start, end) = {
      let handle = class.borrow();
      (handle.live(ctx)?, handle.start, handle.end)
    };
    return match backing.copy_into(ctx, start, end, sink) {
      Ok(()) => Ok(()),
      Err(fault) => fault.throw(ctx),
    };
  }
  if let Some(buffer) = ArrayBuffer::from_value(part.clone()) {
    let Some(bytes) = buffer.as_bytes() else {
      return Err(Exception::throw_type(ctx, "Blob: this ArrayBuffer is detached"));
    };
    return match sink.write(ctx, bytes) {
      Ok(()) => Ok(()),
      Err(fault) => fault.throw(ctx),
    };
  }
  if append_buffer_view(ctx, sink, &part)? {
    return Ok(());
  }
  let text = Coerced::<String>::from_js(ctx, part)?;
  match sink.write(ctx, text.0.as_bytes()) {
    Ok(()) => Ok(()),
    Err(fault) => fault.throw(ctx),
  }
}

fn append_buffer_view<'js>(ctx: &Ctx<'js>, sink: &mut Accumulator, value: &Value<'js>) -> JsResult<bool> {
  let Some(object) = value.as_object() else {
    return Ok(false);
  };
  let Ok(buffer) = object.get::<_, Value<'js>>("buffer") else {
    return Ok(false);
  };
  let Some(buffer) = ArrayBuffer::from_value(buffer) else {
    return Ok(false);
  };
  let offset = object.get::<_, Option<Coerced<f64>>>("byteOffset")?;
  let length = object.get::<_, Option<Coerced<f64>>>("byteLength")?;
  let (Some(offset), Some(length)) = (offset, length) else {
    return Ok(false);
  };
  let Some(bytes) = buffer.as_bytes() else {
    return Err(Exception::throw_type(ctx, "Blob: this view's buffer is detached"));
  };
  let start = offset.0.max(0.0) as usize;
  let end = start.saturating_add(length.0.max(0.0) as usize);
  let Some(window) = bytes.get(start..end) else {
    return Err(Exception::throw_type(ctx, "Blob: this view was resized"));
  };
  match sink.write(ctx, window) {
    Ok(()) => Ok(true),
    Err(fault) => fault.throw(ctx),
  }
}

fn build_blob<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<BlobState>,
  parts: Opt<Value<'js>>,
  options: Opt<Value<'js>>,
  meta: Option<FileMeta>,
) -> JsResult<Value<'js>> {
  let mime = read_mime_option(&options)?;
  let mut sink = Accumulator::new(state.clone(), state.limits.build);
  if let Some(parts) = parts.0 {
    if !parts.is_undefined() && !parts.is_null() {
      let Some(array) = parts.as_array() else {
        return Err(Exception::throw_type(ctx, "Blob: expected an array of parts"));
      };
      for part in crate::utils::arguments::array_values(ctx, array, "Blob")? {
        append_part(ctx, &mut sink, part)?;
      }
    }
  }
  let backing = sink.finish();
  let len = backing.len;
  let handle = BlobHandle {
    backing: RefCell::new(Some(backing)),
    start: 0,
    end: len,
    mime,
    owns_backing: true,
    export_id: Cell::new(None),
    meta,
  };
  Ok(Class::instance(ctx.clone(), handle)?.into_value())
}

fn now_millis() -> f64 {
  SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as f64).unwrap_or(0.0)
}

pub fn mint_app_file<'js>(
  ctx: &Ctx<'js>,
  path: &Path,
  size: u64,
  mime: &str,
  name: Option<&str>,
  mtime_ms: i64,
) -> JsResult<Value<'js>> {
  let backing = Rc::new(Backing {
    kind: RefCell::new(BackingKind::AppFile { path: path.to_path_buf(), mtime_ms }),
    len: size,
  });
  let handle = BlobHandle {
    backing: RefCell::new(Some(backing)),
    start: 0,
    end: size,
    mime: normalize_mime(mime),
    owns_backing: true,
    export_id: Cell::new(None),
    meta: name.map(|name| FileMeta {
      name: sanitize_name(name),
      last_modified: mtime_ms as f64,
    }),
  };
  let instance = Class::instance(ctx.clone(), handle)?;
  if name.is_some() {
    if let Some(proto) = file_prototype(ctx)? {
      instance.as_inner().set_prototype(Some(&proto))?;
    }
  }
  Ok(instance.into_value())
}

fn sanitize_name(name: &str) -> String {
  truncate_label(&name.replace('/', ":"))
}

struct Export {
  backing: Weak<Backing>,
  start: u64,
  end: u64,
}

pub fn export_for_host(state: &Rc<BlobState>, value: &Value<'_>) -> Option<String> {
  let class = Class::<BlobHandle>::from_value(value).ok()?;
  let handle = class.borrow();
  let backing = handle.backing.borrow().clone()?;
  if !backing.alive() {
    return None;
  }
  let mut exported = state.exported.borrow_mut();
  if let Some(id) = handle.export_id.get() {
    if exported.contains_key(&id) {
      return Some(format!("B{id}:{}:{}", handle.start, handle.size()));
    }
  }
  let id = state.next_export.get();
  state.next_export.set(id + 1);
  handle.export_id.set(Some(id));
  if exported.len() >= EXPORT_SWEEP_AT {
    exported.retain(|_, export| export.backing.strong_count() > 0);
  }
  exported.insert(
    id,
    Export {
      backing: Rc::downgrade(&backing),
      start: handle.start,
      end: handle.end,
    },
  );
  Some(format!("B{id}:{}:{}", handle.start, handle.size()))
}

pub fn resolve_export(state: &Rc<BlobState>, id: i64) -> Option<BlobExport> {
  let mut exported = state.exported.borrow_mut();
  let export = exported.get(&id)?;
  let Some(backing) = export.backing.upgrade().filter(|backing| backing.alive()) else {
    exported.remove(&id);
    return None;
  };
  Some(BlobExport {
    backing,
    start: export.start,
    end: export.end,
  })
}

pub struct BlobExport {
  backing: Rc<Backing>,
  start: u64,
  end: u64,
}

impl BlobExport {
  pub fn len(&self) -> u64 {
    self.end - self.start
  }

  pub fn read(&self, offset: u64, len: u64) -> Result<Vec<u8>, BlobFault> {
    let end = offset.checked_add(len).filter(|end| *end <= self.len()).ok_or_else(|| {
      BlobFault::Io(format!("this blob is {} bytes; a read of {len} at {offset} is past its end", self.len(),))
    })?;
    self.backing.read(self.start + offset, self.start + end)
  }
}

const FILE_PROTO_KEY: &str = "inu.blob.fileProto";

fn file_prototype<'js>(ctx: &Ctx<'js>) -> JsResult<Option<Object<'js>>> {
  let Some(blob_proto) = Class::<BlobHandle>::prototype(ctx)? else {
    return Ok(None);
  };
  let key = rquickjs::Symbol::new_global(ctx.clone(), FILE_PROTO_KEY)?;
  Ok(blob_proto.get::<_, Value<'js>>(key.as_atom())?.into_object())
}

pub fn make_clone_fn<'js>(ctx: &Ctx<'js>) -> JsResult<Function<'js>> {
  Function::new(ctx.clone(), |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
    let Ok(class) = Class::<BlobHandle>::from_value(&value) else {
      return Ok(Value::new_undefined(ctx.clone()));
    };
    let clone = {
      let handle = class.borrow();
      let backing = handle.live(&ctx)?;
      let meta = handle.meta.as_ref().map(|meta| FileMeta {
        name: meta.name.clone(),
        last_modified: meta.last_modified,
      });
      make_view(backing, handle.start, handle.end, handle.mime.clone(), meta)
    };
    let instance = Class::instance(ctx.clone(), clone)?;
    if let Some(proto) = class.as_inner().get_prototype() {
      instance.as_inner().set_prototype(Some(&proto))?;
    }
    Ok(instance.into_value())
  })
}

pub fn install<'js>(ctx: &Ctx<'js>, spill_dir: &Path, external: Rc<ExternalMemory>) -> JsResult<Rc<BlobState>> {
  install_with_limits(ctx, spill_dir, external, BlobLimits::default())
}

pub(crate) fn install_with_limits<'js>(
  ctx: &Ctx<'js>,
  spill_dir: &Path,
  external: Rc<ExternalMemory>,
  limits: BlobLimits,
) -> JsResult<Rc<BlobState>> {
  let state = Rc::new(BlobState {
    spill_dir: spill_dir.to_path_buf(),
    external,
    limits,
    spilled: Cell::new(0),
    open_spills: Cell::new(0),
    next_file: Cell::new(1),
    next_export: Cell::new(1),
    exported: RefCell::new(HashMap::new()),
  });

  let blob_proto = Class::<BlobHandle>::prototype(ctx)?
    .ok_or_else(|| Exception::throw_message(ctx, "Blob: the class has no prototype"))?;
  install_blob_members(ctx, &blob_proto)?;

  let file_proto = Object::new(ctx.clone())?;
  file_proto.set_prototype(Some(&blob_proto))?;
  install_file_members(&file_proto)?;
  let key = rquickjs::Symbol::new_global(ctx.clone(), FILE_PROTO_KEY)?;
  blob_proto.prop(key.as_atom(), Property::from(file_proto.clone()))?;

  let state2 = state.clone();
  let blob_ctor = Constructor::new_class::<BlobHandle, _, _>(
    ctx.clone(),
    move |ctx: Ctx<'js>, parts: Opt<Value<'js>>, options: Opt<Value<'js>>| {
      build_blob(&ctx, &state2, parts, options, None)
    },
  )?;

  let state2 = state.clone();
  let file_ctor = Constructor::new_prototype(
    ctx,
    file_proto,
    move |ctx: Ctx<'js>, parts: Opt<Value<'js>>, name: Opt<Coerced<String>>, options: Opt<Value<'js>>| {
      let Some(name) = name.0 else {
        return Err(Exception::throw_type(&ctx, "File: a name is required"));
      };
      let last_modified = match options.0.as_ref().and_then(|v| v.as_object()) {
        Some(options) => {
          options.get::<_, Option<Coerced<f64>>>("lastModified")?.map(|v| v.0).unwrap_or_else(now_millis)
        }
        None => now_millis(),
      };
      let meta = FileMeta {
        name: sanitize_name(&name.0),
        last_modified,
      };
      build_blob(&ctx, &state2, parts, options, Some(meta))
    },
  )?;

  ctx.globals().set("Blob", blob_ctor)?;
  ctx.globals().set("File", file_ctor)?;
  Ok(state)
}

fn install_blob_members<'js>(ctx: &Ctx<'js>, proto: &Object<'js>) -> JsResult<()> {
  define_getter(proto, "size", |ctx: Ctx<'js>, this: This<Class<'js, BlobHandle>>| {
    let handle = this.0.borrow();
    handle.live(&ctx)?;
    Ok::<_, rquickjs::Error>(handle.size() as f64)
  })?;
  define_getter(proto, "type", |ctx: Ctx<'js>, this: This<Class<'js, BlobHandle>>| {
    let handle = this.0.borrow();
    handle.live(&ctx)?;
    Ok::<_, rquickjs::Error>(handle.mime.clone())
  })?;

  let f = Function::new(
    ctx.clone(),
    |ctx: Ctx<'js>,
     this: This<Class<'js, BlobHandle>>,
     start: Opt<Value<'js>>,
     end: Opt<Value<'js>>,
     content_type: Opt<Value<'js>>|
     -> JsResult<Value<'js>> {
      let coerce = |v: Option<Value<'js>>| -> JsResult<Option<f64>> {
        v.map(|v| Ok(Coerced::<f64>::from_js(&ctx, v)?.0)).transpose()
      };
      let start = coerce(crate::utils::arguments::opt(start))?;
      let end = coerce(crate::utils::arguments::opt(end))?;
      let handle = this.0.borrow();
      let backing = handle.live(&ctx)?;
      let size = handle.size();
      let from = clamp_index(start, size, 0);
      let to = clamp_index(end, size, size).max(from);
      let mime = match crate::utils::arguments::opt(content_type) {
        Some(v) => normalize_mime(&Coerced::<String>::from_js(&ctx, v)?.0),
        None => String::new(),
      };
      let slice = make_view(backing, handle.start + from, handle.start + to, mime, None);
      Ok(Class::instance(ctx.clone(), slice)?.into_value())
    },
  )?;
  define_method(proto, "slice", f)?;

  for (name, kind) in [("bytes", ReadAs::Bytes), ("arrayBuffer", ReadAs::Buffer), ("text", ReadAs::Text)] {
    let f =
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, this: This<Class<'js, BlobHandle>>| -> JsResult<Value<'js>> {
        read_promise(&ctx, &this.0, kind)
      })?;
    define_method(proto, name, f)?;
  }

  let f = Function::new(ctx.clone(), |this: This<Class<'js, BlobHandle>>| {
    this.0.borrow().dispose();
  })?;
  define_method(proto, "dispose", f)?;
  Ok(())
}

fn install_file_members<'js>(proto: &Object<'js>) -> JsResult<()> {
  define_getter(proto, "name", |ctx: Ctx<'js>, this: This<Class<'js, BlobHandle>>| {
    let handle = this.0.borrow();
    handle.live(&ctx)?;
    Ok::<_, rquickjs::Error>(handle.meta.as_ref().map(|meta| meta.name.clone()).unwrap_or_default())
  })?;
  define_getter(proto, "lastModified", |ctx: Ctx<'js>, this: This<Class<'js, BlobHandle>>| {
    let handle = this.0.borrow();
    handle.live(&ctx)?;
    Ok::<_, rquickjs::Error>(handle.meta.as_ref().map(|meta| meta.last_modified).unwrap_or(0.0))
  })?;
  Ok(())
}

#[derive(Clone, Copy)]
enum ReadAs {
  Bytes,
  Buffer,
  Text,
}

impl ReadAs {
  fn limit(self) -> u64 {
    match self {
      ReadAs::Bytes | ReadAs::Buffer => MATERIALIZE_LIMIT_BYTES,
      ReadAs::Text => TEXT_LIMIT_BYTES,
    }
  }
}

fn read_promise<'js>(ctx: &Ctx<'js>, class: &Class<'js, BlobHandle>, kind: ReadAs) -> JsResult<Value<'js>> {
  let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
  let read = class.borrow().read_all(kind.limit());
  match read {
    Ok(bytes) => {
      let value = match kind {
        ReadAs::Bytes => TypedArray::<u8>::new(ctx.clone(), bytes)?.into_value(),
        ReadAs::Buffer => ArrayBuffer::new(ctx.clone(), bytes)?.into_value(),
        ReadAs::Text => {
          use rquickjs::IntoJs;
          String::from_utf8_lossy(&bytes).into_owned().into_js(ctx)?
        }
      };
      resolve.call::<_, Value>((value,))?;
    }
    Err(fault) => {
      let value = fault.to_value(ctx)?;
      reject.call::<_, Value>((value,))?;
    }
  }
  Ok(promise.into_value())
}

#[cfg(test)]
#[path = "blob_tests.rs"]
mod tests;
