use crate::runtime::pump_jobs;
use std::cell::Cell;
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
    telegram::{
      account::AccountState,
      reads::ReadsState,
      rpc::RpcState,
      writes::WritesState,
    },
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
mod tests;

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

/// the lease-free part of an engine's slot: what a java thread reaches without entering the
/// engine, which is how `PluginJvm` encodes a reference while the engine is busy elsewhere
struct EngineSlot {
  engine: Arc<Serialized<TransferEngine>>,
  jvm_refs: Option<Arc<crate::api::platform::jvm::RefTable>>,
}

static ENGINES: OnceLock<Mutex<SlotMap<EngineKey, EngineSlot>>> = OnceLock::new();

pub(crate) fn insert_engine(engine: Engine) -> jlong {
  let jvm_refs = engine.jvm.as_ref().map(|state| state.refs().clone());
  ENGINES
    .get_or_init(|| Mutex::new(SlotMap::with_key()))
    .lock()
    .unwrap_or_else(|e| e.into_inner())
    .insert(EngineSlot { engine: Serialized::new(TransferEngine(engine)), jvm_refs })
    .data()
    .as_ffi() as jlong
}

pub(crate) fn engine_jvm_refs(handle: jlong) -> Option<Arc<crate::api::platform::jvm::RefTable>> {
  ENGINES.get()?.lock().unwrap_or_else(|e| e.into_inner()).get(get_engine_key(handle))?.jvm_refs.clone()
}

fn get_engine(handle: jlong) -> Option<Lease<TransferEngine>> {
  enter_engine(handle, None)
}

fn enter_engine(handle: jlong, timeout: Option<Duration>) -> Option<Lease<TransferEngine>> {
  try_enter_engine(handle, timeout).ok()
}

fn engine_slot(handle: jlong) -> Result<Arc<Serialized<TransferEngine>>, EntryError> {
  Ok(
    ENGINES
      .get()
      .ok_or(EntryError::Closed)?
      .lock()
      .unwrap_or_else(|e| e.into_inner())
      .get(get_engine_key(handle))
      .ok_or(EntryError::Closed)?
      .engine
      .clone(),
  )
}

fn try_enter_engine(handle: jlong, timeout: Option<Duration>) -> Result<Lease<TransferEngine>, EntryError> {
  engine_slot(handle)?.enter(timeout)
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
  let slot = ENGINES.get()?.lock().unwrap_or_else(|e| e.into_inner()).get(get_engine_key(handle))?.engine.clone();
  let engine = slot.close().ok()??;
  ENGINES.get()?.lock().unwrap_or_else(|e| e.into_inner()).remove(get_engine_key(handle));
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
  pub(crate) timers: Rc<TimerState>,
  pub(crate) notifications: Rc<NotificationState>,
  pub(crate) jvm: Option<Rc<JvmState>>,
  pub(crate) xposed: Option<Rc<XposedState>>,
}

impl Engine {
  pub(crate) fn pump(&self) {
    pump_jobs(&self._rt, &self.ctx, self.rpc.log.as_ref());
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
