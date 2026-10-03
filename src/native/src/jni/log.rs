use jni::objects::{Global, JMethodID, JObject, JValue};
use jni::refs::IntoAuto;
use jni::signature::{Primitive, ReturnType};
use rquickjs::{Ctx, Exception, Function, JsLifetime, Object, Result as JsResult};
use std::sync::Arc;

use super::env::{clear_exception, with_current_env};
use crate::classify_log;
use crate::utils::qjs::qjs_load_prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/console.qbc"));

pub(crate) struct ConsoleSink {
  pub(crate) target: Global<JObject<'static>>,
  pub(crate) on_console: JMethodID,
}

impl ConsoleSink {
  pub(crate) fn emit(&self, level: i32, message: &str) {
    with_current_env(|env| {
      let Ok(jmsg) = env.new_string(message) else {
        clear_exception(env);
        return;
      };
      let jmsg = jmsg.auto();
      let args = [JValue::Int(level).as_jni(), JValue::Object(&jmsg).as_jni()];
      // SAFETY: `on_console` was looked up on `target`'s class as `(ILjava/lang/String;)V`
      let _ = unsafe {
        env.call_method_unchecked(&self.target, self.on_console, ReturnType::Primitive(Primitive::Void), &args)
      };
      clear_exception(env);
    });
  }
}

pub(crate) fn make_log(console: Arc<ConsoleSink>) -> crate::Log {
  Arc::new(move |msg: &str| {
    let (level, message) = classify_log(msg);
    console.emit(level, message);
  })
}

/// the console's value printer, for whatever else prints a value the way `console.log` does
#[derive(Clone, JsLifetime)]
pub(crate) struct Inspect<'js>(pub(crate) Function<'js>);

pub(crate) fn install_console<'js>(ctx: &Ctx<'js>, emit: impl Fn(i32, &str) + 'static) -> JsResult<()> {
  let emit = Function::new(ctx.clone(), move |level: i32, line: String| emit(level, &line))?;
  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  let parts: Object = factory.call((emit,))?;
  ctx.globals().set("console", parts.get::<_, Object>("console")?)?;
  ctx
    .store_userdata(Inspect(parts.get("inspect")?))
    .map_err(|_| Exception::throw_message(ctx, "console is already installed"))?;
  Ok(())
}

#[cfg(test)]
#[path = "log_tests.rs"]
mod tests;
