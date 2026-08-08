//! The JNI surface: `desu.inugram.helpers.plugins.QuickJs`'s native methods, and the upcalls back
//! into it.
//!
//! Every export here arms an execution deadline before it can reach plugin JS ([`crate::sandbox::limits`]).

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
    /// one per engine, shared by `rpc` and `reads`: a view's field cache is invalidated by an epoch
    /// this owns, and `common.d.ts` promises a write invalidates every cached field *everywhere* -
    /// two of these would leave each family blind to the other's writes.
    pub(crate) views: Rc<crate::api::tl::proxy::TlViews>,
    /// the blob bookkeeping `globals` mints, kept because the two surfaces that take a `Blob`
    /// without its bytes ever entering js (`fs`, `fetch`) read it from here
    pub(crate) blobs: Option<Rc<crate::api::io::blob::BlobState>>,
    /// what `utils.js` handed back, kept because `installRpc` runs in a later JNI call than
    /// `installApi` and `send_message.js` normalizes a peer through the same one implementation the read
    /// and write surfaces do. Released in `nativeDestroy`: a `Persistent` has no `Drop`.
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

/// drains microtasks, logging job errors + unhandled rejections through the engine's rpc log
/// (or dropping them silently pre-installRpc, when there's nowhere to log yet)
pub(crate) fn pump(engine: &Engine) {
    if let Some(state) = engine.rpc.as_ref() {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else if let Some(state) = engine.lifecycle_state.as_ref() {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else {
        crate::api::telegram::rpc::pump_jobs(&engine._rt, &engine.ctx, &|_| {});
    }
}
