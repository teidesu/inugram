use crate::runtime::Dispose;
use std::collections::HashMap;
use std::ops::Deref;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use crate::api::globals::{install_globals, RandomHost};
use crate::api::io::blob::BlobState;
use crate::api::tl::proxy::TlHost;
use crate::sandbox::limits::ExternalMemory;
use rquickjs::Result as JsResult;

use crate::api::lifecycle::LifecycleState;
use crate::api::platform::clipboard::ClipboardHost;
use crate::api::platform::open_url::OpenUrlHost;
use crate::api::ui::dialogs::{DialogHost, DialogState};
use crate::sandbox::grants::CachedGrantHost;
use crate::sandbox::registry::Lifecycle;
use rquickjs::{Context, Ctx, Runtime};
use std::cell::{Cell, RefCell};

pub(crate) struct FakeObject {
  pub(crate) name: String,
  pub(crate) fields: Vec<(String, String)>,
}

/// `tl_own_keys` joins names with commas, matching `proxy::keys_to_array`
#[derive(Default)]
pub(crate) struct FakeHandles {
  objects: RefCell<HashMap<i64, FakeObject>>,
  next: Cell<i64>,
}

impl FakeHandles {
  pub(crate) fn mint<K: Into<String>>(&self, name: &str, fields: impl IntoIterator<Item = (K, String)>) -> i64 {
    let id = self.next.get() + 1;
    self.next.set(id);
    let fields = fields.into_iter().map(|(k, v)| (k.into(), v)).collect();
    self.objects.borrow_mut().insert(id, FakeObject { name: name.to_string(), fields });
    id
  }

  /// the read-only object wire, which is what `PluginReads.mint(readOnly = true)` answers with
  pub(crate) fn mint_wire<K: Into<String>>(&self, name: &str, fields: impl IntoIterator<Item = (K, String)>) -> String {
    format!("HOR{}", self.mint(name, fields))
  }

  pub(crate) fn get(&self, handle: i64, key: &str) -> String {
    let objects = self.objects.borrow();
    let Some(object) = objects.get(&handle) else {
      return "Phandle-expired\n\n\n\nexpired".to_string();
    };
    if key == "_" {
      return format!("S{}", object.name);
    }
    match object.fields.iter().find(|(name, _)| name == key) {
      Some((_, wire)) => wire.clone(),
      None => "N".to_string(),
    }
  }

  pub(crate) fn has(&self, handle: i64, key: &str) -> i32 {
    let objects = self.objects.borrow();
    match objects.get(&handle) {
      None => -1,
      Some(object) => i32::from(key == "_" || object.fields.iter().any(|(name, _)| name == key)),
    }
  }

  pub(crate) fn own_keys(&self, handle: i64) -> Option<String> {
    let objects = self.objects.borrow();
    let object = objects.get(&handle)?;
    let mut keys = vec!["_".to_string()];
    keys.extend(object.fields.iter().map(|(name, _)| name.clone()));
    Some(keys.join(","))
  }

  pub(crate) fn release(&self, handle: i64) {
    self.objects.borrow_mut().remove(&handle);
  }
}

const NO_WRITES: &str = "Pforbidden\n\n\n\nthe fake host takes no writes";

/// deliberately *not* the read-only refusal on writes: a test asserting on that one must be reading
/// the engine's own, which a writable handle would skip
impl TlHost for FakeHandles {
  fn tl_get(&self, handle: i64, key: &str) -> String {
    self.get(handle, key)
  }

  fn tl_set(&self, _handle: i64, _key: &str, _value_wire: &str) -> Option<String> {
    Some(NO_WRITES.to_string())
  }

  fn tl_set_bytes(&self, _handle: i64, _key: &str, _value: &[u8]) -> Option<String> {
    Some(NO_WRITES.to_string())
  }

  fn tl_has(&self, handle: i64, key: &str) -> i32 {
    self.has(handle, key)
  }

  fn tl_own_keys(&self, handle: i64) -> Option<String> {
    self.own_keys(handle)
  }

  fn tl_copy(&self, _handle: i64) -> Option<String> {
    None
  }

  fn tl_release(&self, handle: i64) {
    self.release(handle)
  }
}

pub(crate) fn get_api_globals<'js>(ctx: &Ctx<'js>) -> crate::api::Globals<'js> {
  crate::api::error::install_plugin_error(ctx).unwrap();
  crate::api::Globals::get(ctx).unwrap()
}

pub(crate) fn eval_or_panic<T: for<'js> rquickjs::FromJs<'js>>(ctx: &Context, code: &str) -> T {
  ctx.with(|ctx| match ctx.eval::<T, _>(code) {
    Ok(value) => value,
    Err(rquickjs::Error::Exception) => panic!("{}", crate::api::error::format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  })
}

pub(crate) fn eval_string(ctx: &Context, code: &str) -> String {
  eval_or_panic(ctx, code)
}

pub(crate) fn eval_unit(ctx: &Context, code: &str) {
  eval_or_panic(ctx, code)
}

pub(crate) fn new_engine() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  (rt, ctx)
}

pub(crate) fn eval_json(ctx: &Context, code: &str) -> String {
  eval_string(ctx, &format!("JSON.stringify({code})"))
}

/// `[is a PluginError, code, grant, message]`, or `'no-throw'`
pub(crate) fn catch_json(ctx: &Context, code: &str) -> String {
  eval_string(
    ctx,
    &format!(
      r#"
        (() => {{
          try {{ {code}; return 'no-throw'; }}
          catch (e) {{
            return JSON.stringify([e instanceof inu.PluginError, e.code, e.grant ?? null, e.message]);
          }}
        }})()
      "#
    ),
  )
}

/// keeps `borrow`/`borrow_mut` so an assertion reads as it would over a `RefCell`
pub(crate) struct Logs(Mutex<Vec<String>>);

impl Logs {
  pub(crate) fn new() -> Arc<Logs> {
    Arc::new(Logs(Mutex::new(Vec::new())))
  }

  pub(crate) fn borrow(&self) -> MutexGuard<'_, Vec<String>> {
    self.0.lock().expect("a test thread panicked holding the log")
  }

  pub(crate) fn borrow_mut(&self) -> MutexGuard<'_, Vec<String>> {
    self.borrow()
  }
}

pub(crate) fn log_sink(logs: &Arc<Logs>) -> crate::Log {
  let logs = logs.clone();
  Arc::new(move |msg: &str| logs.borrow_mut().push(msg.to_string()))
}

/// `Persistent` has no `Drop`, and a GC root still held at `JS_FreeRuntime` aborts the whole test process.
/// Holds a Context clone so the Runtime outlives the state whatever the local drop order.
pub(crate) struct DisposeOnDrop<S> {
  ctx: Context,
  state: Rc<S>,
  dispose: fn(&Context, &Rc<S>),
}

impl<S> DisposeOnDrop<S> {
  pub(crate) fn new(ctx: &Context, state: Rc<S>, dispose: fn(&Context, &Rc<S>)) -> Self {
    DisposeOnDrop { ctx: ctx.clone(), state, dispose }
  }
}

impl<S> Deref for DisposeOnDrop<S> {
  type Target = Rc<S>;

  fn deref(&self) -> &Rc<S> {
    &self.state
  }
}

impl<S> Drop for DisposeOnDrop<S> {
  fn drop(&mut self) {
    (self.dispose)(&self.ctx, &self.state);
  }
}

pub(crate) fn run_capturing_console(rt: &Runtime, ctx: &Context, source: &str) -> Vec<String> {
  let lines = install_capturing_console(ctx);
  eval_unit(ctx, source);
  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  let lines = lines.borrow();
  lines.clone()
}

pub(crate) fn install_capturing_console(ctx: &Context) -> Arc<Logs> {
  let lines = Logs::new();
  let sink = lines.clone();
  ctx.with(|ctx| {
    crate::jni::log::install_console(&ctx, move |_level, line| sink.borrow_mut().push(line.to_string())).unwrap()
  });
  lines
}

/// Exact rather than a floor: a plugin that stopped early prints no FAIL, and a missing API can satisfy
/// `expectThrow`. These harnesses provide the peer, network and login a device may lack, so no SKIPs.
pub(crate) fn assert_oracle_exact(lines: &[String], done: &str, count: usize) {
  assert_oracle_exact_skipping(lines, done, count, &[]);
}

pub(crate) fn assert_oracle_exact_skipping(lines: &[String], done: &str, count: usize, skips: &[&str]) {
  let failures: Vec<&String> = lines.iter().filter(|l| l.starts_with("FAIL")).collect();
  assert!(failures.is_empty(), "{failures:#?}");
  assert!(lines.iter().any(|l| l == done), "the oracle did not finish: {lines:#?}");
  let skipped: Vec<&str> = lines.iter().filter(|l| l.starts_with("SKIP")).map(String::as_str).collect();
  assert_eq!(skipped, skips, "the harness answers everything else, so nothing else may skip");
  assert_eq!(
    lines.iter().filter(|l| l.starts_with("PASS")).count(),
    count,
    "the oracle ran a different number of assertions than this floor pins: {lines:#?}",
  );
}

fn header_lines(source: &str) -> impl Iterator<Item = &str> {
  source.lines().take_while(|line| !line.contains("==/InuPlugin=="))
}

/// from the oracle's own manifest: a hard-coded list could hide a grant the shipped plugin lacks
pub(crate) fn manifest_grants(source: &str) -> Vec<&str> {
  header_lines(source)
    .filter_map(|line| line.trim().strip_prefix("// @grant"))
    .map(str::trim)
    .collect()
}

/// lowercased base keys, one entry per value, as `QuickJs.installInfo` and `PluginManifest.raw` build it
pub(crate) fn manifest_header(source: &str) -> Vec<(String, String)> {
  header_lines(source)
    .filter_map(|line| {
      let directive = line.trim().strip_prefix("//")?.trim().strip_prefix('@')?;
      let (key, value) = match directive.split_once(char::is_whitespace) {
        Some((key, value)) => (key, value.trim()),
        None => (directive, ""),
      };
      Some((key.to_ascii_lowercase(), value.to_string()))
    })
    .collect()
}

/// `fail_*` holds a verbatim error wire to answer with
#[derive(Default)]
pub(crate) struct RecordingHost {
  pub(crate) storage_file: TempPath,
  pub(crate) toasts: RefCell<Vec<String>>,
  pub(crate) bulletins: RefCell<Vec<(i64, String)>>,
  pub(crate) dialogs: RefCell<Vec<(i64, String)>>,
  pub(crate) fail_dialog: RefCell<Option<String>>,
  pub(crate) opened: RefCell<Vec<String>>,
  pub(crate) clipboard: RefCell<String>,
  pub(crate) clipboard_reads: Cell<usize>,
  pub(crate) writes: RefCell<Vec<String>>,
  pub(crate) choosers: RefCell<Vec<(i64, String)>>,
  pub(crate) fail_chooser: RefCell<Option<String>>,
  pub(crate) prompts: RefCell<Vec<(i64, String)>>,
}

impl DialogHost for RecordingHost {
  fn toast(&self, text: &str) {
    self.toasts.borrow_mut().push(text.to_string());
  }

  fn bulletin(&self, request_id: i64, options_json: &str) -> Option<String> {
    self.bulletins.borrow_mut().push((request_id, options_json.to_string()));
    None
  }

  fn dialog(&self, request_id: i64, options_json: &str) -> Option<String> {
    if let Some(err) = self.fail_dialog.borrow().as_ref() {
      return Some(err.clone());
    }
    self.dialogs.borrow_mut().push((request_id, options_json.to_string()));
    None
  }

  fn chooser(&self, request_id: i64, options_json: &str) -> Option<String> {
    if let Some(err) = self.fail_chooser.borrow().as_ref() {
      return Some(err.clone());
    }
    self.choosers.borrow_mut().push((request_id, options_json.to_string()));
    None
  }

  fn prompt(&self, request_id: i64, options_json: &str) -> Option<String> {
    self.prompts.borrow_mut().push((request_id, options_json.to_string()));
    None
  }
}

impl OpenUrlHost for RecordingHost {
  fn open_url(&self, url: &str) {
    self.opened.borrow_mut().push(url.to_string());
  }
}

impl ClipboardHost for RecordingHost {
  fn read(&self) -> String {
    self.clipboard_reads.set(self.clipboard_reads.get() + 1);
    self.clipboard.borrow().clone()
  }

  fn write(&self, text: &str) {
    self.writes.borrow_mut().push(text.to_string());
    *self.clipboard.borrow_mut() = text.to_string();
  }
}

pub(crate) type ApiFixture = (
  Runtime,
  Context,
  Rc<RecordingHost>,
  DisposeOnDrop<LifecycleState>,
  DisposeOnDrop<DialogState>,
  std::sync::Arc<Logs>,
);

/// a path in the temp dir that is one test's alone, removed with it along with anything staged beside it
pub(crate) struct TempPath(pub(crate) std::path::PathBuf);

impl Default for TempPath {
  fn default() -> Self {
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let serial = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    TempPath(std::env::temp_dir().join(format!("inu-test-{}-{serial}", std::process::id())))
  }
}

impl Drop for TempPath {
  fn drop(&mut self) {
    let _ = std::fs::remove_file(&self.0);
    let _ = std::fs::remove_dir_all(&self.0);
    let _ = std::fs::remove_file(self.0.with_added_extension("tmp"));
    let _ = std::fs::remove_file(self.0.with_added_extension("corrupt"));
  }
}

static NEXT_DIR: AtomicU64 = AtomicU64::new(0);
static SUITE_ROOT: OnceLock<PathBuf> = OnceLock::new();
/// every create/remove of the suite root is taken under this. The kernel fails a `mkdir` whose
/// parent is being unlinked under it (EINVAL on apfs), ~3% of parallel runs.
static LIVE_DIRS: Mutex<usize> = Mutex::new(0);

/// The one directory this process owns. The fs escape assertions check that nothing landed in
/// `<fixture>/..`, so a fixture straight in the machine's temp dir would read whatever others left there.
fn suite_root() -> &'static Path {
  SUITE_ROOT.get_or_init(|| {
    let path = std::env::temp_dir().join(format!("inu-suite-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&path);
    std::fs::create_dir_all(&path).unwrap();
    path
  })
}

pub(crate) struct TestDir(PathBuf);

impl TestDir {
  pub(crate) fn new(name: &str) -> Self {
    let unique = NEXT_DIR.fetch_add(1, Ordering::Relaxed);
    let path = suite_root().join(format!("{name}-{unique}"));
    let mut live = LIVE_DIRS.lock().unwrap_or_else(|e| e.into_inner());
    let _ = std::fs::remove_dir_all(&path);
    std::fs::create_dir_all(&path).unwrap();
    *live += 1;
    TestDir(path)
  }

  pub(crate) fn path(&self) -> &Path {
    &self.0
  }

  pub(crate) fn entries(&self) -> Vec<PathBuf> {
    let Ok(read) = std::fs::read_dir(&self.0) else {
      return Vec::new();
    };
    read.filter_map(|e| e.ok().map(|e| e.path())).collect()
  }
}

impl Drop for TestDir {
  fn drop(&mut self) {
    let _ = std::fs::remove_dir_all(&self.0);
    let mut live = LIVE_DIRS.lock().unwrap_or_else(|e| e.into_inner());
    *live -= 1;
    if *live == 0 {
      let _ = std::fs::remove_dir(suite_root());
    }
  }
}

/// counts up from 1, so a test can see the array really was written through
pub(crate) struct CountingRandom {
  next: Cell<u8>,
  pub(crate) available: Cell<bool>,
}

impl Default for CountingRandom {
  fn default() -> Self {
    CountingRandom {
      next: Cell::new(1),
      available: Cell::new(true),
    }
  }
}

impl RandomHost for CountingRandom {
  fn random_bytes(&self, out: &mut [u8]) -> bool {
    if !self.available.get() {
      return false;
    }
    for byte in out.iter_mut() {
      *byte = self.next.get();
      self.next.set(self.next.get().wrapping_add(1));
    }
    true
  }
}

pub(crate) fn install_sandbox_globals(ctx: &Ctx<'_>, spill_dir: &Path) -> JsResult<Rc<BlobState>> {
  install_globals(ctx, Rc::new(CountingRandom::default()), spill_dir, ExternalMemory::new())
}

pub(crate) fn setup_apis(grants: &[&str]) -> ApiFixture {
  let (rt, ctx) = new_engine();
  let host = Rc::new(RecordingHost::default());
  let grants = CachedGrantHost::new(grants);
  let logs = Logs::new();
  let log = log_sink(&logs);
  let (lifecycle, dialogs) = ctx.with(|ctx| {
    let inu = get_api_globals(&ctx);
    let lifecycle =
      crate::api::lifecycle::install_lifecycle(&ctx, grants.clone(), Lifecycle::new(), log.clone(), &inu).unwrap();
    crate::api::io::local_storage::install_local_storage(&ctx, host.storage_file.0.clone()).unwrap();
    crate::api::platform::clipboard::install_clipboard(&ctx, host.clone(), grants.clone(), &inu).unwrap();
    crate::api::platform::open_url::install_open_url(&ctx, host.clone(), grants.clone(), &inu).unwrap();
    let dialogs = crate::api::ui::dialogs::install_dialogs(&ctx, host.clone(), None, log.clone(), &inu).unwrap();
    (lifecycle, dialogs)
  });
  let lifecycle = DisposeOnDrop::new(&ctx, lifecycle, |ctx, state| state.dispose(ctx));
  let dialogs = DisposeOnDrop::new(&ctx, dialogs, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, lifecycle, dialogs, logs)
}
