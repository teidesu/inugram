use rquickjs::{Context, Object, Persistent, Runtime};
use std::rc::Rc;

use crate::sandbox::registry::Lifecycle;

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
    pub(crate) views: Rc<crate::api::tl::proxy::TlViews>,
    pub(crate) blobs: Option<Rc<crate::api::io::blob::BlobState>>,
    pub(crate) shared: Option<Persistent<Object<'static>>>,
    pub(crate) rpc: Option<Rc<crate::api::telegram::rpc::RpcState>>,
    pub(crate) deserialize: Option<Rc<crate::api::telegram::deserialize::DeserializeState>>,
    pub(crate) lifecycle_state: Option<Rc<crate::api::lifecycle::LifecycleState>>,
    pub(crate) dialogs: Option<Rc<crate::api::ui::dialogs::DialogState>>,
    pub(crate) ui: Option<Rc<crate::api::ui::pages::UiState>>,
    pub(crate) screens: Option<Rc<crate::api::ui::screens::ScreenState>>,
    pub(crate) actions: Option<Rc<crate::api::ui::actions::ActionState>>,
    pub(crate) account: Option<Rc<crate::api::telegram::account::AccountState>>,
    pub(crate) reads: Option<Rc<crate::api::telegram::reads::ReadsState>>,
    pub(crate) writes: Option<Rc<crate::api::telegram::writes::WritesState>>,
    pub(crate) fs: Option<Rc<crate::api::io::fs::FsState>>,
    pub(crate) fetch: Option<Rc<crate::api::io::fetch::FetchState>>,
    pub(crate) canvas: Option<Rc<crate::api::canvas::CanvasState>>,
    pub(crate) timers: Option<Rc<crate::api::timers::TimerState>>,
    pub(crate) notifications: Option<Rc<crate::api::platform::notifications::NotificationState>>,
    pub(crate) jvm: Option<Rc<crate::api::platform::jvm::JvmState>>,
    pub(crate) xposed: Option<Rc<crate::api::platform::xposed::XposedState>>,
}

pub(crate) fn pump(engine: &Engine) {
    if let Some(state) = engine.rpc.as_ref() {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else if let Some(state) = engine.lifecycle_state.as_ref() {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, &|_| {});
    }
}
