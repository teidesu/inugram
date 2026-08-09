use rquickjs::{Context, Object, Persistent, Runtime};
use std::rc::Rc;

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
