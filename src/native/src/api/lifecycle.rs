use std::cell::Cell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult, Runtime, Value};

use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};

pub struct LifecycleState {
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  pub(crate) log: crate::Log,
  unload_fns: CallbackRegistry,
  visibility_fns: CallbackRegistry,
  visible: Cell<bool>,
}

pub fn install_lifecycle<'js>(
  ctx: &Ctx<'js>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<LifecycleState>> {
  let state = Rc::new(LifecycleState {
    grants,
    lifecycle,
    log,
    unload_fns: CallbackRegistry::default(),
    visibility_fns: CallbackRegistry::default(),
    visible: Cell::new(true),
  });

  let state2 = state.clone();
  globals.inu.set(
    "onUnload",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
      if state2.lifecycle.is_unloading() {
        return noop_disposer(&ctx);
      }
      let token = state2.unload_fns.alloc();
      state2.unload_fns.register(&ctx, token, None, cb);
      let state = state2.clone();
      make_disposer(&ctx, move |ctx| {
        state.unload_fns.dispose(ctx, token);
      })
    })?,
  )?;

  let state2 = state.clone();
  globals.inu.set(
    "onAppVisibilityChange",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
      if state2.lifecycle.is_unloading() {
        return noop_disposer(&ctx);
      }
      state2.grants.check_grant(&ctx, "onAppVisibilityChange", None, MATCH_EXACT)?;
      let token = state2.visibility_fns.alloc();
      state2.visibility_fns.register(&ctx, token, None, cb);
      let state = state2.clone();
      make_disposer(&ctx, move |ctx| {
        state.visibility_fns.dispose(ctx, token);
      })
    })?,
  )?;

  Ok(state)
}

impl LifecycleState {
  pub fn app_visibility_changed(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, visible: bool) {
    let state = self;
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

  pub fn notify_unload(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context) {
    let state = self;
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

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      state.unload_fns.release_all(&ctx);
      state.visibility_fns.release_all(&ctx);
    });
  }
}

#[cfg(test)]
#[path = "lifecycle_tests.rs"]
mod lifecycle_tests;
