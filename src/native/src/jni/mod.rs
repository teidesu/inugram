use crate::api::ui::files::FilesState;
use crate::runtime::Dispose;
use crate::runtime::{
  pump_jobs, SETTLE_CANVAS, SETTLE_FETCH, SETTLE_FILES, SETTLE_INVOKE, SETTLE_MODAL, SETTLE_READS, SETTLE_WRITES,
};
use crate::sandbox::limits::fit_stack_limit;
use std::cell::{Cell, RefCell};
use std::ops::Deref;
use std::rc::Rc;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use jni::sys::jlong;
use rquickjs::{Context, Object, Persistent, Runtime};
use slotmap::{new_key_type, Key, KeyData, SlotMap};

use crate::{
  api::{
    canvas::CanvasState,
    io::fetch::FetchState,
    lifecycle::LifecycleState,
    platform::{jvm::JvmState, notifications::NotificationState, xposed::XposedState},
    telegram::{account::AccountState, reads::ReadsState, rpc::RpcState, writes::WritesState},
    timers::TimerState,
    ui::{actions::ActionState, dialogs::DialogState, pages::UiState, screens::ScreenState},
  },
  sandbox::registry::Lifecycle,
};

pub(crate) mod bridge;
pub(crate) mod env;
pub(crate) mod exports;
pub(crate) mod hosts;
pub(crate) mod log;
pub(crate) mod pixels;

#[cfg(test)]
#[path = "caller_thread_tests.rs"]
mod caller_thread_tests;

use bridge::JniBridge;

new_key_type! {
  pub(crate) struct EngineKey;
}

mod serialized;
use serialized::{EntryError, Lease, Serialized};

// SAFETY: the whole engine graph moves together. Its Rc/RefCell/Persistent values never leave
// an entry lease; JNI hosts retain only integer handles and Java global references. A lease is
// thread-bound and exclusive, including destruction. QuickJS uses its parallel runtime lock.
// Do not return cloned engine state from a JNI entry or add externally shared Rc owners.
pub(crate) struct TransferEngine(Engine);
unsafe impl Send for TransferEngine {}

impl Deref for TransferEngine {
  type Target = Engine;
  fn deref(&self) -> &Engine {
    &self.0
  }
}

struct EngineSlot {
  engine: Arc<Serialized<TransferEngine>>,
  jvm_refs: Option<Arc<crate::api::platform::jvm::RefTable>>,
}

static ENGINES: OnceLock<Mutex<SlotMap<EngineKey, EngineSlot>>> = OnceLock::new();

/// the table, once it exists; a poisoned lock is still the table
fn lock_engines() -> Option<std::sync::MutexGuard<'static, SlotMap<EngineKey, EngineSlot>>> {
  Some(ENGINES.get()?.lock().unwrap_or_else(|e| e.into_inner()))
}

pub(crate) fn insert_engine(engine: Engine) -> jlong {
  let jvm_refs = engine.jvm.as_ref().map(|state| state.refs().clone());
  ENGINES
    .get_or_init(|| Mutex::new(SlotMap::with_key()))
    .lock()
    .unwrap_or_else(|e| e.into_inner())
    .insert(EngineSlot {
      engine: Serialized::new(TransferEngine(engine)),
      jvm_refs,
    })
    .data()
    .as_ffi() as jlong
}

pub(crate) fn engine_jvm_refs(handle: jlong) -> Option<Arc<crate::api::platform::jvm::RefTable>> {
  lock_engines()?.get(get_engine_key(handle))?.jvm_refs.clone()
}

/// A caller-thread entry waits out the engine thread's longest turn: that thread lends the engine
/// across every call into arbitrary Java, so it holds the lease only while its JS runs.
pub(crate) const CALLER_LEASE_WAIT: Duration = Duration::from_millis(crate::sandbox::limits::ENTRY_DEADLINE_MS + 250);

fn enter_engine(handle: jlong, timeout: Option<Duration>) -> Option<EngineLease> {
  try_enter_engine(handle, timeout).ok()
}

fn engine_slot(handle: jlong) -> Result<Arc<Serialized<TransferEngine>>, EntryError> {
  let engines = lock_engines().ok_or(EntryError::Closed)?;
  let slot = engines.get(get_engine_key(handle)).ok_or(EntryError::Closed)?;
  Ok(slot.engine.clone())
}

/// Only an untimed entry lends: that is the engine thread, whose frames below hold no app locks a
/// borrower could need. A caller thread may be inside a synchronized app method, so it keeps the lease.
/// An untimed entry made from Java this thread lent across is a caller entry: those frames may hold
/// locks, and the lender's JS is paused mid-statement, so it neither waits unbounded nor pumps.
fn try_enter_engine(handle: jlong, timeout: Option<Duration>) -> Result<EngineLease, EntryError> {
  let nested = timeout.is_none() && LENDING.with(|depth| depth.get()) > 0;
  let lease = engine_slot(handle)?.enter(if nested { Some(CALLER_LEASE_WAIT) } else { timeout })?;
  let lendable = timeout.is_none() && !nested && !lease.is_borrowed();
  let lendable = lendable.then(|| (lease.slot().clone(), &*lease as *const TransferEngine));
  let previous = LENDABLE.with(|current| current.replace(lendable));
  let caller = nested.then(CallerEntry::new);
  Ok(EngineLease { previous, lease, caller })
}

type LendableLease = (Arc<Serialized<TransferEngine>>, *const TransferEngine);

thread_local! {
  static LENDABLE: RefCell<Option<LendableLease>> = const { RefCell::new(None) };
  static LENDING: Cell<u32> = const { Cell::new(0) };
  /// Set by a nested entry that left jobs for the lender's own entry to pump once its JS is done.
  static PUMP_OWED: Cell<bool> = const { Cell::new(false) };
}

struct EngineLease {
  previous: Option<LendableLease>,
  lease: Lease<TransferEngine>,
  caller: Option<CallerEntry>,
}

impl Deref for EngineLease {
  type Target = Lease<TransferEngine>;
  fn deref(&self) -> &Lease<TransferEngine> {
    &self.lease
  }
}

impl Drop for EngineLease {
  fn drop(&mut self) {
    LENDABLE.with(|current| current.replace(self.previous.take()));
    if self.caller.is_some() {
      if crate::runtime::is_job_pending(&self.lease.ctx) {
        PUMP_OWED.with(|owed| owed.set(true));
      }
    } else if !self.lease.is_borrowed() && LENDING.with(|depth| depth.get()) == 0 && PUMP_OWED.with(|owed| owed.take())
    {
      self.lease.pump();
    }
  }
}

/// Runs `f`, a call into arbitrary Java, with this thread's engine open to caller threads for its
/// duration, so a hook or callback waiting on this engine runs instead of stalling. A no-op on any
/// thread but the engine thread's own entry.
pub(crate) fn lend_engine<R>(f: impl FnOnce() -> R) -> R {
  let Some((slot, engine)) = LENDABLE.with(|current| current.take()) else {
    return f();
  };
  // SAFETY: registered by this thread's innermost entry, which owns the lease and outlives this call
  LENDING.with(|depth| depth.set(depth.get() + 1));
  let result = crate::sandbox::limits::suspend_deadline(|| unsafe { slot.lend(engine, f) });
  LENDING.with(|depth| depth.set(depth.get() - 1));
  LENDABLE.with(|current| current.replace(Some((slot, engine))));
  // SAFETY: the lease is owned again, and `engine` is the value it holds
  let engine = unsafe { &*engine };
  fit_stack_limit(&engine.ctx);
  result
}

fn stop_engine_callbacks(handle: jlong) {
  if let Ok(slot) = engine_slot(handle) {
    slot.stop_admitting();
  }
}

pub(crate) fn get_engine_key(handle: jlong) -> EngineKey {
  EngineKey::from(KeyData::from_ffi(handle as u64))
}

pub(crate) fn remove_engine(handle: jlong) -> Option<Engine> {
  let slot = lock_engines()?.get(get_engine_key(handle))?.engine.clone();
  let engine = slot.close().ok()??;
  lock_engines()?.remove(get_engine_key(handle));
  Some(engine.0)
}

pub(crate) struct Engine {
  pub(crate) ctx: Context,
  pub(crate) _rt: Runtime,
  pub(crate) bridge: Rc<JniBridge>,
  pub(crate) lifecycle: Rc<Lifecycle>,
  pub(crate) shared: Option<Persistent<Object<'static>>>,
  pub(crate) rpc: Rc<RpcState>,
  pub(crate) lifecycle_state: Rc<LifecycleState>,
  pub(crate) dialogs: Rc<DialogState>,
  pub(crate) ui: Rc<UiState>,
  pub(crate) screens: Rc<ScreenState>,
  pub(crate) actions: Rc<ActionState>,
  pub(crate) account: Rc<AccountState>,
  pub(crate) reads: Rc<ReadsState>,
  pub(crate) writes: Rc<WritesState>,
  pub(crate) fetch: Rc<FetchState>,
  pub(crate) canvas: Rc<CanvasState>,
  pub(crate) files: Rc<FilesState>,
  pub(crate) timers: Rc<TimerState>,
  pub(crate) notifications: Rc<NotificationState>,
  pub(crate) jvm: Option<Rc<JvmState>>,
  pub(crate) xposed: Option<Rc<XposedState>>,
}

impl Engine {
  /// in the order they are released
  pub(crate) fn disposables(&self) -> Vec<&dyn Dispose> {
    let mut all: Vec<&dyn Dispose> = vec![
      &*self.rpc,
      &*self.lifecycle_state,
      &*self.dialogs,
      &*self.ui,
      &*self.files,
      &*self.screens,
      &*self.actions,
      &*self.writes,
      &*self.reads,
      &*self.account,
      &*self.fetch,
      &*self.canvas,
      &*self.timers,
      &*self.notifications,
    ];
    all.extend(self.xposed.as_deref().map(|state| state as &dyn Dispose));
    all.extend(self.jvm.as_deref().map(|state| state as &dyn Dispose));
    all
  }

  pub(crate) fn pump(&self) {
    pump_jobs(&self.ctx, self.rpc.log.as_ref());
  }

  pub(crate) fn settle(&self, api: i32, request_id: i64, wire: &str) {
    let ctx = &self.ctx;
    match api {
      SETTLE_FETCH => self.fetch.settle(ctx, request_id, wire),
      SETTLE_CANVAS => self.canvas.settle(ctx, request_id, wire),
      SETTLE_MODAL => self.dialogs.settle(ctx, request_id, wire),
      SETTLE_FILES => self.files.settle(ctx, request_id, wire),
      SETTLE_READS => self.reads.settle(ctx, request_id, wire),
      SETTLE_WRITES => self.writes.settle(ctx, request_id, wire),
      SETTLE_INVOKE => self.rpc.settle(ctx, request_id, wire),
      _ => (self.rpc.log)(&format!("settle: there is no api {api} to answer request {request_id}")),
    }
  }

  pub(crate) fn settle_bytes(&self, api: i32, request_id: i64, bytes: &[u8]) {
    match api {
      SETTLE_INVOKE => self.rpc.settle_bytes(&self.ctx, request_id, bytes),
      _ => (self.rpc.log)(&format!("settle: api {api} does not answer request {request_id} with bytes")),
    }
  }
}

thread_local! {
  static CALLER_ENTRY: Cell<bool> = const { Cell::new(false) };
}

pub(crate) fn is_caller_entry() -> bool {
  CALLER_ENTRY.with(|entry| entry.get())
}

struct CallerEntry(bool);
impl CallerEntry {
  fn new() -> Self {
    Self(CALLER_ENTRY.with(|entry| entry.replace(true)))
  }
}
impl Drop for CallerEntry {
  fn drop(&mut self) {
    CALLER_ENTRY.with(|entry| entry.set(self.0));
  }
}
