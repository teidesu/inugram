use crate::runtime::Dispose;
use std::collections::HashMap;
use std::ops::Deref;
use std::rc::Rc;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::api::lifecycle::LifecycleState;
use crate::api::platform::clipboard::ClipboardHost;
use crate::api::platform::open_url::OpenUrlHost;
use crate::api::ui::dialogs::{DialogHost, DialogState};
use crate::sandbox::grants::TestGrantHost;
use crate::sandbox::registry::Lifecycle;
use rquickjs::{Context, Ctx, Runtime};
use std::cell::{Cell, RefCell};

/// One TL object behind a fake handle: its constructor name, and each field already as a wire.
pub(crate) struct FakeObject {
  pub(crate) name: String,
  pub(crate) fields: Vec<(String, String)>,
}

/// Fake TL handle table for testing other modules' bridge calls. Stores minted objects and
/// implements `TlHost` upcalls.
///
/// `tl_own_keys` joins names with commas, matching `proxy::keys_to_array`; any other separator
/// would turn the whole list into one key.
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

/// Evaluates for a string, reporting a thrown exception the way the engine formats one for the
/// host rather than as rquickjs's opaque `Error::Exception`.
/// The engine globals, which a real engine builds once in `nativeCreate` and hands to every
/// `install_*`. Tests get-or-create the same shared object here.
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

/// [`eval_string`] for code evaluated for its effect.
pub(crate) fn eval_unit(ctx: &Context, code: &str) {
  eval_or_panic(ctx, code)
}

/// the runtime and context every fixture starts from
pub(crate) fn new_engine() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  (rt, ctx)
}

/// [`eval_string`] over `JSON.stringify`, for asserting on a shape rather than on a scalar.
pub(crate) fn eval_json(ctx: &Context, code: &str) -> String {
  eval_string(ctx, &format!("JSON.stringify({code})"))
}

/// What a refusal looks like from JS: `[is a PluginError, code, grant, message]`, or `'no-throw'`.
/// The grant is part of it because `not-granted` naming the wrong scope is the failure a test of a
/// gate is written to catch.
pub(crate) fn catch_json(ctx: &Context, code: &str) -> String {
  eval_string(
    ctx,
    &format!(
      r#"(() => {{
                try {{ {code}; return 'no-throw'; }}
                catch (e) {{
                    return JSON.stringify([e instanceof inu.PluginError, e.code, e.grant ?? null, e.message]);
                }}
            }})()"#
    ),
  )
}

/// What a test reads a module's diagnostics out of.
///
/// [`crate::Log`] is `Send + Sync`, so the `Rc<RefCell<Vec<String>>>` the suite used to build one
/// out of no longer fits. Keeps `borrow`/`borrow_mut` rather than exposing the lock, so an
/// assertion reads exactly as it did.
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

/// the [`crate::Log`] a module writes into this sink through
pub(crate) fn log_sink(logs: &Arc<Logs>) -> crate::Log {
  let logs = logs.clone();
  Arc::new(move |msg: &str| logs.borrow_mut().push(msg.to_string()))
}

/// Disposes module state when the fixture drops, including after failed assertions. `Persistent`
/// has no `Drop`; retained GC roots would make `JS_FreeRuntime` abort the entire test process.
///
/// Keeps a Context clone so the Runtime stays alive regardless of local drop order.
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

/// Runs a bundled oracle with captured console output, then drains pending jobs and returns the
/// log. Oracles using only engine APIs need no device.
pub(crate) fn run_capturing_console(rt: &Runtime, ctx: &Context, source: &str) -> Vec<String> {
  let lines = install_capturing_console(ctx);
  eval_unit(ctx, source);
  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  let lines = lines.borrow();
  lines.clone()
}

/// The console half of [`run_capturing_console`], for an oracle whose assertions only run once the
/// host has fed it something: install this, evaluate the source, drive the host, then read the lines.
pub(crate) fn install_capturing_console(ctx: &Context) -> Arc<Logs> {
  let lines = Logs::new();
  let sink = lines.clone();
  ctx.with(|ctx| {
    crate::jni::log::install_console(&ctx, move |_level, line| sink.borrow_mut().push(line.to_string())).unwrap()
  });
  lines
}

/// Requires each oracle to reach its final line with exactly the expected assertion count. No FAIL
/// output alone is insufficient: the plugin may have stopped early. A missing API can also satisfy
/// `expectThrow`, so an exact count catches cases a lower bound would miss.
///
/// Reject SKIPs. These harnesses provide the peer, network, and login conditions that device
/// oracles may lack.
pub(crate) fn assert_oracle_exact(lines: &[String], done: &str, count: usize) {
  assert_oracle_exact_skipping(lines, done, count, &[]);
}

/// Like [`assert_oracle_exact`], but permits an explicit set of skips for unavailable network,
/// chat, or forum data. Fails on missing or unexpected skips.
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

/// Reads grants from the oracle's own manifest, as the device does. A hard-coded test grant list
/// could hide a missing permission in the shipped plugin.
pub(crate) fn manifest_grants(source: &str) -> Vec<&str> {
  header_lines(source)
    .filter_map(|line| line.trim().strip_prefix("// @grant"))
    .map(str::trim)
    .collect()
}

/// Reads the oracle's manifest with lowercased base keys and one entry per value, matching
/// `QuickJs.installInfo` and `PluginManifest.raw`. Build `inu.info().header` from this instead of
/// test literals.
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

/// The four surfaces that answer to one upcall each, faked together: [`setup_apis`] installs all of
/// them over one of these, because each is a handful of members and the two bundled oracles reach
/// across them. `fail_*` holds a verbatim error wire to answer with.
#[derive(Default)]
pub(crate) struct RecordingHost {
  pub(crate) storage_file: TempPath,
  pub(crate) toasts: RefCell<Vec<String>>,
  pub(crate) bulletins: RefCell<Vec<(i64, String)>>,
  pub(crate) dialogs: RefCell<Vec<(i64, String)>>,
  pub(crate) fail_dialog: RefCell<Option<String>>,
  pub(crate) opened: RefCell<Vec<String>>,
  pub(crate) clipboard: RefCell<String>,
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
    self.clipboard.borrow().clone()
  }

  fn write(&self, text: &str) {
    self.writes.borrow_mut().push(text.to_string());
    *self.clipboard.borrow_mut() = text.to_string();
  }
}

/// disposes on drop, so a failing assertion is one failed test rather than an abort in
/// `JS_FreeRuntime` that takes the whole suite's reporting with it
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
    let _ = std::fs::remove_file(crate::api::io::local_storage::staged_path(&self.0));
    let _ = std::fs::remove_file(crate::api::io::local_storage::quarantine_path(&self.0));
  }
}

pub(crate) fn setup_apis(grants: &[&str]) -> ApiFixture {
  let (rt, ctx) = new_engine();
  let host = Rc::new(RecordingHost::default());
  let grants = TestGrantHost::new(grants).as_host();
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
