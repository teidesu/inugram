use std::cell::{Ref, RefCell, RefMut};
use std::rc::Rc;
use std::sync::OnceLock;

use fragile::Fragile;
use jni::sys::jlong;
use rquickjs::{Context, Object, Persistent, Runtime};
use slotmap::{new_key_type, Key, KeyData, SlotMap};

use crate::{
  api::{
    canvas::CanvasState,
    io::{blob::BlobState, fetch::FetchState, fs::FsState},
    lifecycle::LifecycleState,
    platform::{jvm::JvmState, notifications::NotificationState, xposed::XposedState},
    telegram::{
      account::AccountState,
      deserialize::DeserializeState,
      reads::ReadsState,
      rpc::{pump_jobs, RpcState},
      writes::WritesState,
    },
    timers::TimerState,
    tl::proxy::TlViews,
    ui::{actions::ActionState, dialogs::DialogState, pages::UiState, screens::ScreenState},
  },
  sandbox::registry::Lifecycle,
};

pub(crate) mod bridge;
pub(crate) mod env;
pub(crate) mod exports;
pub(crate) mod hosts;
pub(crate) mod log;
#[cfg(test)]
mod tests;

use bridge::JniBridge;

new_key_type! {
  pub(crate) struct EngineKey;
}

// Initialized by `insert_engine` on Utilities.globalQueue. `Fragile` rejects access from every
// other thread, preserving QuickJS's thread affinity while keeping one process-wide handle map.
static ENGINES: OnceLock<Fragile<RefCell<SlotMap<EngineKey, Engine>>>> = OnceLock::new();

fn get_engine_store() -> Option<&'static RefCell<SlotMap<EngineKey, Engine>>> {
  ENGINES.get()?.try_get().ok()
}

pub(crate) fn insert_engine(engine: Engine) -> jlong {
  ENGINES
    .get_or_init(|| Fragile::new(RefCell::new(SlotMap::with_key())))
    .get()
    .borrow_mut()
    .insert(engine)
    .data()
    .as_ffi() as jlong
}

pub(crate) fn get_engine(handle: jlong) -> Option<Ref<'static, Engine>> {
  let store = get_engine_store()?;
  Ref::filter_map(store.borrow(), |engines| engines.get(get_engine_key(handle))).ok()
}

pub(crate) fn get_engine_mut(handle: jlong) -> Option<RefMut<'static, Engine>> {
  let store = get_engine_store()?;
  RefMut::filter_map(store.borrow_mut(), |engines| engines.get_mut(get_engine_key(handle))).ok()
}

pub(crate) fn get_engine_key(handle: jlong) -> EngineKey {
  EngineKey::from(KeyData::from_ffi(handle as u64))
}

pub(crate) fn remove_engine(handle: jlong) -> Option<Engine> {
  get_engine_store()?.borrow_mut().remove(get_engine_key(handle))
}

pub(crate) struct Engine {
  pub(crate) ctx: Context,
  pub(crate) _rt: Runtime,
  pub(crate) bridge: Rc<JniBridge>,
  pub(crate) lifecycle: Rc<Lifecycle>,
  pub(crate) inu: Persistent<Object<'static>>,
  pub(crate) views: Rc<TlViews>,
  pub(crate) blobs: Option<Rc<BlobState>>,
  pub(crate) shared: Option<Persistent<Object<'static>>>,
  pub(crate) rpc: Option<Rc<RpcState>>,
  pub(crate) deserialize: Option<Rc<DeserializeState>>,
  pub(crate) lifecycle_state: Option<Rc<LifecycleState>>,
  pub(crate) dialogs: Option<Rc<DialogState>>,
  pub(crate) ui: Option<Rc<UiState>>,
  pub(crate) screens: Option<Rc<ScreenState>>,
  pub(crate) actions: Option<Rc<ActionState>>,
  pub(crate) account: Option<Rc<AccountState>>,
  pub(crate) reads: Option<Rc<ReadsState>>,
  pub(crate) writes: Option<Rc<WritesState>>,
  pub(crate) fs: Option<Rc<FsState>>,
  pub(crate) fetch: Option<Rc<FetchState>>,
  pub(crate) canvas: Option<Rc<CanvasState>>,
  pub(crate) timers: Option<Rc<TimerState>>,
  pub(crate) notifications: Option<Rc<NotificationState>>,
  pub(crate) jvm: Option<Rc<JvmState>>,
  pub(crate) xposed: Option<Rc<XposedState>>,
}

pub(crate) fn pump(engine: &Engine) {
  if let Some(state) = engine.rpc.as_ref() {
    pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
  } else if let Some(state) = engine.lifecycle_state.as_ref() {
    pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
  } else {
    pump_jobs(&engine._rt, &engine.ctx, &|_| {});
  }
}
