//! The small apis that answer to one host each, and the state they share: `inu.onUnload` and
//! `inu.onAppVisibilityChange` live here, one namespace per module beside this one.
//! JNI-free behind [`ApiHost`]; `lib.rs` wires the JNI-backed host.
//!
//! [`ApiHost::clipboard_read`] is the one upcall in this folder that is **not** a tagged wire and
//! cannot be: it carries whatever the user last copied, so a leading `E` is a plain clipboard far
//! more often than it is an error wire. It answers text or the empty string, and so does a host
//! that cannot read the clipboard.

pub(crate) mod clipboard;
pub(crate) mod dialogs;
pub(crate) mod kv;
pub(crate) mod open_url;

pub(crate) use dialogs::{resolve_chooser, resolve_dialog};

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Result as JsResult, Runtime, Value};

use crate::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::error::get_or_create_inu;
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};
use crate::telegram::rpc::{format_exception, pump_jobs, PendingSettle};

/// stand-in for the Kotlin `QuickJs.ApiListener` interface
pub trait ApiHost {
    /// tagged wire string: `S`/`N`/`J`/`E`/`P` (see module doc). unused key/value args are ""
    fn kv(&self, op: i32, key: &str, value: &str) -> String;
    fn ui_toast(&self, text: &str);
    /// `None` == shown (settled later via [`resolve_dialog`]), `Some(msg)` == immediate error
    fn ui_dialog(&self, request_id: i64, options_json: &str) -> Option<String>;
    /// `inu.ui.chooser(options)`; same contract as [`ApiHost::ui_dialog`], settled by
    /// [`resolve_chooser`]. `options_json` is `{title?, multiple, items: [{text, subtitle?,
    /// danger}], selected: [index...]}` - `selected` is a list in both modes, so the host renders
    /// one shape and `multiple` alone decides what comes back
    fn ui_chooser(&self, request_id: i64, options_json: &str) -> Option<String>;
    /// already screened by [`screen_external_url`]; fire-and-forget, there being no ui to fail into
    fn open_url(&self, url: &str);
    /// the clipboard's plain text, or "" for anything this cannot answer. never a wire (module doc)
    fn clipboard_read(&self) -> String;
    fn clipboard_write(&self, text: &str);
}

pub struct ApiState {
    host: Rc<dyn ApiHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    pub(crate) log: crate::Log,
    next_request_id: crate::sandbox::registry::RequestIds,
    pending_dialogs: RefCell<HashMap<i64, PendingSettle>>,
    /// a chooser remembers the mode it was opened in: the host answers with a list either way, and
    /// what a single-select promise resolves to is a number
    pending_choosers: RefCell<HashMap<i64, (PendingSettle, bool)>>,
    unload_fns: CallbackRegistry,
    visibility_fns: CallbackRegistry,
    /// what the plugin was last told; a fresh engine starts foreground and the host corrects it
    /// before the plugin's own code runs
    visible: Cell<bool>,
}

/// `JSON` is an ordinary writable global and plugin code shares this context, so reading
/// `parse`/`stringify` off it would hand a plugin every host wire *before* the grant gates that
/// rebuild the exposed object from it run. `JS_ParseJSON`/`JS_JSONStringify` cannot be interposed.
pub(crate) fn json_parse<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
    ctx.json_parse(json)
}

pub(crate) fn json_stringify<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Option<String>> {
    Ok(match ctx.json_stringify(value)? {
        Some(s) => Some(s.to_string()?),
        None => None,
    })
}

pub fn install_api<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ApiHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
) -> JsResult<Rc<ApiState>> {
    let state = Rc::new(ApiState {
        host,
        grants,
        lifecycle,
        log,
        next_request_id: crate::sandbox::registry::RequestIds::default(),
        pending_dialogs: RefCell::new(HashMap::new()),
        pending_choosers: RefCell::new(HashMap::new()),
        unload_fns: CallbackRegistry::default(),
        visibility_fns: CallbackRegistry::default(),
        visible: Cell::new(true),
    });

    let inu = get_or_create_inu(ctx)?;

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
            if state2.lifecycle.is_unloading() {
                return noop_disposer(&ctx);
            }
            let token = state2.unload_fns.alloc();
            state2.unload_fns.register(&ctx, token, None, cb);
            let state = state2.clone();
            make_disposer(&ctx, move |ctx| {
                state.unload_fns.dispose(ctx, token);
            })
        })?;
        inu.set("onUnload", f)?;
    }

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
            if state2.lifecycle.is_unloading() {
                return noop_disposer(&ctx);
            }
            check_grant(&ctx, &state2.grants, "onAppVisibilityChange", None, MATCH_EXACT)?;
            let token = state2.visibility_fns.alloc();
            state2.visibility_fns.register(&ctx, token, None, cb);
            let state = state2.clone();
            make_disposer(&ctx, move |ctx| {
                state.visibility_fns.dispose(ctx, token);
            })
        })?;
        inu.set("onAppVisibilityChange", f)?;
    }

    kv::install(ctx, &state, &inu)?;
    dialogs::install(ctx, &state, &inu)?;
    clipboard::install(ctx, &state, &inu)?;
    open_url::install(ctx, &state, &inu)?;

    Ok(state)
}

/// the app moved to the foreground or the background. fires on a transition only, so a host that
/// re-announces the state it already reported costs the plugin nothing.
///
/// this is the signal a plugin does its catch-up work from: [`crate::sandbox::timers`] floors the wheel
/// while hidden and a suspended interval fires once on return rather than replaying the backlog,
/// so a timer cannot be read as a clock across a background stretch.
pub fn app_visibility_changed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>, visible: bool) {
    if state.visible.replace(visible) == visible {
        return;
    }
    context.with(|ctx| {
        let mode = if visible { "foreground" } else { "background" };
        for f in state.visibility_fns.snapshot(&ctx) {
            match f.call::<_, Value>((mode,)) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!(
                        "onAppVisibilityChange callback threw: {}",
                        format_exception(&ctx),
                    )));
                }
                Err(e) => (state.log)(&format!("onAppVisibilityChange callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// runs every registered unload callback (in registration order), logging but not propagating
/// throws, then drains microtasks. call once, right before tearing the engine down.
pub fn notify_unload(rt: &Runtime, context: &rquickjs::Context, state: &Rc<ApiState>) {
    state.lifecycle.begin_unload();
    context.with(|ctx| {
        for f in state.unload_fns.take_all(&ctx) {
            match f.call::<_, Value>(()) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!("onUnload callback threw: {}", format_exception(&ctx))));
                }
                Err(e) => (state.log)(&format!("onUnload callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::telegram::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<ApiState>) {
    context.with(|ctx| {
        state.unload_fns.release_all(&ctx);
        state.visibility_fns.release_all(&ctx);
        for (_, pending) in state.pending_dialogs.borrow_mut().drain() {
            pending.release(&ctx);
        }
        for (_, (pending, _)) in state.pending_choosers.borrow_mut().drain() {
            pending.release(&ctx);
        }
    });
}

#[cfg(test)]
mod tests;
