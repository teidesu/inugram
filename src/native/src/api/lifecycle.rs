use std::cell::Cell;
use std::rc::Rc;

use rquickjs::{function::This, Ctx, Function, Result as JsResult, Runtime, Value};

use crate::api::error::format_exception;
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};

pub struct LifecycleState {
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  pub(crate) log: crate::Log,
  unload_fns: CallbackRegistry,
  visibility_fns: CallbackRegistry,
  visible: Cell<bool>,
  unload_started: Cell<bool>,
  pending_unloads: Rc<Cell<usize>>,
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
    unload_started: Cell::new(false),
    pending_unloads: Rc::new(Cell::new(0)),
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
    if state.lifecycle.is_unloading() || state.visible.replace(visible) == visible {
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
    if state.unload_started.replace(true) {
      return;
    }
    state.lifecycle.begin_cleanup();
    context.with(|ctx| {
      for f in state.unload_fns.take_all(&ctx) {
        match f.call::<_, Value>(()) {
          Ok(value) => {
            if let Some(promise) = value.as_promise() {
              let pending = state.pending_unloads.clone();
              pending.set(pending.get() + 1);
              let resolved_pending = pending.clone();
              let log = state.log.clone();
              let settled = Rc::new(Cell::new(false));
              let resolved_settled = settled.clone();
              let rejected_settled = settled.clone();
              let attach = (|| -> JsResult<()> {
                let resolved = Function::new(ctx.clone(), move || {
                  if !resolved_settled.replace(true) {
                    resolved_pending.set(resolved_pending.get().saturating_sub(1));
                  }
                })?;
                let rejected = Function::new(ctx.clone(), move |reason: Value<'_>| {
                  let ctx = reason.ctx().clone();
                  if rejected_settled.replace(true) {
                    return;
                  }
                  pending.set(pending.get().saturating_sub(1));
                  let _ = ctx.throw(reason);
                  log(&crate::fault(format_args!("onUnload promise rejected: {}", format_exception(&ctx))));
                })?;
                promise.then()?.call::<_, Value>((This(promise.clone()), resolved, rejected))?;
                Ok(())
              })();
              if let Err(error) = attach {
                if !settled.replace(true) {
                  state.pending_unloads.set(state.pending_unloads.get().saturating_sub(1));
                }
                if error.is_exception() {
                  (state.log)(&crate::fault(format_args!(
                    "onUnload promise handler failed: {}",
                    format_exception(&ctx)
                  )));
                } else {
                  (state.log)(&format!("onUnload promise handler failed: {error}"));
                }
              }
            }
          }
          Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!("onUnload callback threw: {}", format_exception(&ctx))));
          }
          Err(e) => (state.log)(&format!("onUnload callback failed: {e:?}")),
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn poll_unload(&self, rt: &Runtime, context: &rquickjs::Context) -> bool {
    pump_jobs(rt, context, self.log.as_ref());
    if self.pending_unloads.get() > 0 && self.lifecycle.is_cleaning_up() {
      return false;
    }
    if self.pending_unloads.get() > 0 {
      (self.log)("onUnload cleanup timed out after 2000 ms");
      self.pending_unloads.set(0);
    }
    self.lifecycle.finish_cleanup();
    true
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    let state = self;
    state.lifecycle.finish_cleanup();
    context.with(|ctx| {
      state.unload_fns.release_all(&ctx);
      state.visibility_fns.release_all(&ctx);
    });
  }
}

#[cfg(test)]
#[path = "lifecycle_tests.rs"]
mod lifecycle_tests;
