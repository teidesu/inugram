//! The JNI surface: `desu.inugram.helpers.plugins.QuickJs`'s native methods, and the upcalls back
//! into it.
//!
//! Every export here arms an execution deadline before it can reach plugin JS ([`crate::engine::deadline`]).

use rquickjs::{Context, Object, Persistent, Runtime};
use std::rc::Rc;

use crate::engine::registry::Lifecycle;

pub(crate) mod bridge;
pub(crate) mod env;
pub(crate) mod exports;
pub(crate) mod hosts;
pub(crate) mod info;
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
    pub(crate) views: Rc<crate::tl::proxy::TlViews>,
    /// the blob bookkeeping `globals` mints, kept because the two surfaces that take a `Blob`
    /// without its bytes ever entering js (`fs`, `fetch`) read it from here
    pub(crate) blobs: Option<Rc<crate::io::blob::BlobState>>,
    /// what `utils.js` handed back, kept because `installRpc` runs in a later JNI call than
    /// `installApi` and `sendmsg.js` normalizes a peer through the same one implementation the read
    /// and write surfaces do. Released in `nativeDestroy`: a `Persistent` has no `Drop`.
    pub(crate) shared: Option<Persistent<Object<'static>>>,
    pub(crate) rpc: Option<Rc<crate::tg::rpc::RpcState>>,
    pub(crate) deserialize: Option<Rc<crate::tg::deserialize::DeserializeState>>,
    pub(crate) api: Option<Rc<crate::api::ApiState>>,
    pub(crate) ui: Option<Rc<crate::ui::pages::UiState>>,
    pub(crate) screens: Option<Rc<crate::ui::screens::ScreenState>>,
    pub(crate) actions: Option<Rc<crate::ui::actions::ActionState>>,
    pub(crate) account: Option<Rc<crate::tg::account::AccountState>>,
    pub(crate) reads: Option<Rc<crate::tg::reads::ReadsState>>,
    pub(crate) writes: Option<Rc<crate::tg::writes::WritesState>>,
    pub(crate) fs: Option<Rc<crate::io::fs::FsState>>,
    pub(crate) fetch: Option<Rc<crate::io::fetch::FetchState>>,
    pub(crate) canvas: Option<Rc<crate::draw::canvas::CanvasState>>,
    pub(crate) timers: Option<Rc<crate::engine::timers::TimerState>>,
    pub(crate) notifications: Option<Rc<crate::platform::notifications::NotificationState>>,
    pub(crate) jvm: Option<Rc<crate::platform::jvm::JvmState>>,
    pub(crate) xposed: Option<Rc<crate::platform::xposed::XposedState>>,
}

/// drains microtasks, logging job errors + unhandled rejections through the engine's rpc log
/// (or dropping them silently pre-installRpc, when there's nowhere to log yet)
pub(crate) fn pump(engine: &Engine) {
    if let Some(state) = engine.rpc.as_ref() {
        crate::tg::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else if let Some(state) = engine.api.as_ref() {
        crate::tg::rpc::pump_jobs(&engine._rt, &engine.ctx, state.log.as_ref());
    } else {
        crate::tg::rpc::pump_jobs(&engine._rt, &engine.ctx, &|_| {});
    }
}
