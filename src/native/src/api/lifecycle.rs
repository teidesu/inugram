//! What a plugin is told about its own lifetime and the app's: `inu.onUnload` and
//! `inu.onAppVisibilityChange`.
//!
//! The flag every other subsystem reads to refuse a late registration is
//! [`crate::sandbox::registry::Lifecycle`], which lives with the registration rules it is part of;
//! this is the surface built on top of it.

use std::cell::Cell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};

pub struct LifecycleState {
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    pub(crate) log: crate::Log,
    unload_fns: CallbackRegistry,
    visibility_fns: CallbackRegistry,
    /// what the plugin was last told; a fresh engine starts foreground and the host corrects it
    /// before the plugin's own code runs
    visible: Cell<bool>,
}

pub fn install_lifecycle<'js>(
    ctx: &Ctx<'js>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
    inu: &Object<'js>,
) -> JsResult<Rc<LifecycleState>> {
    let state = Rc::new(LifecycleState {
        grants,
        lifecycle,
        log,
        unload_fns: CallbackRegistry::default(),
        visibility_fns: CallbackRegistry::default(),
        visible: Cell::new(true),
    });

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

    Ok(state)
}

/// the app moved to the foreground or the background. fires on a transition only, so a host that
/// re-announces the state it already reported costs the plugin nothing.
///
/// this is the signal a plugin does its catch-up work from: [`crate::api::timers`] floors the wheel
/// while hidden and a suspended interval fires once on return rather than replaying the backlog,
/// so a timer cannot be read as a clock across a background stretch.
pub fn app_visibility_changed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<LifecycleState>, visible: bool) {
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
pub fn notify_unload(rt: &Runtime, context: &rquickjs::Context, state: &Rc<LifecycleState>) {
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

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::api::telegram::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<LifecycleState>) {
    context.with(|ctx| {
        state.unload_fns.release_all(&ctx);
        state.visibility_fns.release_all(&ctx);
    });
}

#[cfg(test)]
#[path = "lifecycle_tests.rs"]
mod lifecycle_tests;
