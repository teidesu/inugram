use rquickjs::{Ctx, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error;
use crate::jni::is_caller_entry;

pub(crate) struct PendingSettle {
  pub(crate) resolve: Persistent<Function<'static>>,
  pub(crate) reject: Persistent<Function<'static>>,
}

impl PendingSettle {
  pub(crate) fn new<'js>(ctx: &Ctx<'js>) -> JsResult<(rquickjs::Promise<'js>, Self)> {
    let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
    Ok((
      promise,
      PendingSettle {
        resolve: Persistent::save(ctx, resolve),
        reject: Persistent::save(ctx, reject),
      },
    ))
  }

  pub(crate) fn reject_with(self, ctx: &Ctx<'_>, msg: &str) -> JsResult<()> {
    let error_val = match error::host_error_to_js(ctx, msg) {
      Ok(v) => v,
      Err(e) => {
        self.release(ctx);
        return Err(e);
      }
    };
    self.reject_with_value(ctx, error_val)
  }

  pub(crate) fn reject_with_value<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let reject = self.reject.restore(ctx)?;
    let _ = self.resolve.restore(ctx);
    reject.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn resolve_with<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    let resolve = self.resolve.restore(ctx)?;
    let _ = self.reject.restore(ctx);
    resolve.call::<_, Value>((value,))?;
    Ok(())
  }

  pub(crate) fn release(self, ctx: &Ctx<'_>) {
    let _ = self.resolve.restore(ctx);
    let _ = self.reject.restore(ctx);
  }
}

pub fn pump_jobs(rt: &Runtime, context: &rquickjs::Context, log: &dyn Fn(&str)) {
  if is_caller_entry() { return; }
  loop {
    match rt.execute_pending_job() {
      Ok(true) => continue,
      Ok(false) => break,
      Err(e) => {
        log(&format!("unhandled error running microtask: {e:?}"));
        break;
      }
    }
  }
  context.with(|ctx| error::report_rejections(&ctx));
}

