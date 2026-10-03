use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::context::EvalOptions;
use rquickjs::{Ctx, Function, Result as JsResult, Value};

use super::log::Inspect;
use crate::api::error::describe_js_error;
use crate::utils::qjs::resolve_and_then;

pub(crate) const DROPPED: &str =
  "the result was dropped before it settled: the plugin unloaded, or nothing can settle the promise any more";

/// Answers exactly once: with the settled value, or with [DROPPED] when the promise is freed
/// unsettled, which is what an engine closing under it does
pub(crate) struct EvalReply(RefCell<Option<Box<dyn FnOnce(bool, &str)>>>);

impl EvalReply {
  pub(crate) fn new(send: impl FnOnce(bool, &str) + 'static) -> Rc<Self> {
    Rc::new(EvalReply(RefCell::new(Some(Box::new(send)))))
  }

  fn send(&self, ok: bool, text: &str) {
    let send = self.0.borrow_mut().take();
    if let Some(send) = send {
      send(ok, text);
    }
  }
}

impl Drop for EvalReply {
  fn drop(&mut self) {
    self.send(false, DROPPED);
  }
}

/// `inu eval`: runs [code] as a global script, so it sees the plugin's top-level bindings, and
/// answers with its completion value, awaited and printed the way `console.log` prints it
pub(crate) fn evaluate_inspected(ctx: &Ctx<'_>, code: Vec<u8>, reply: Rc<EvalReply>) {
  if let Err(e) = try_evaluate(ctx, code, &reply) {
    reply.send(false, &describe_js_error(ctx, e));
  }
}

fn try_evaluate<'js>(ctx: &Ctx<'js>, code: Vec<u8>, reply: &Rc<EvalReply>) -> JsResult<()> {
  let mut options = EvalOptions::default();
  options.filename = Some("<eval>".to_string());
  let value = ctx.eval_with_options::<Value, _>(code, options)?;
  let settle = |ok: bool| {
    let reply = reply.clone();
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| reply.send(ok, &inspect(&ctx, value)))
  };
  resolve_and_then(ctx, value, settle(true)?, settle(false)?)
}

fn inspect<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> String {
  let Some(inspect) = ctx.userdata::<Inspect>() else {
    return "[console is not installed]".to_string();
  };
  inspect.0.call::<_, String>((value,)).unwrap_or_else(|e| describe_js_error(ctx, e))
}

#[cfg(test)]
#[path = "eval_tests.rs"]
mod tests;
