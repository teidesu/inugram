use std::cell::{Ref, RefCell};
use std::rc::Rc;
use std::sync::OnceLock;

use fragile::Fragile;
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
      deserialize::DeserializeState,
      reads::ReadsState,
      rpc::{pump_jobs, RpcState},
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
  pub(crate) shared: Option<Persistent<Object<'static>>>,
  pub(crate) rpc: Rc<RpcState>,
  pub(crate) deserialize: Rc<DeserializeState>,
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

pub(crate) fn pump(engine: &Engine) {
  pump_jobs(&engine._rt, &engine.ctx, engine.rpc.log.as_ref());
}
