use jni::objects::{Global, JMethodID, JObject, JValue};
use jni::refs::IntoAuto;
use jni::signature::{Primitive, ReturnType};
use rquickjs::{Ctx, Function, Object, Result as JsResult};
use std::sync::Arc;

use super::env::{clear_exception, with_current_env};
use crate::classify_log;

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

pub(crate) fn install_console<'js>(ctx: &Ctx<'js>, emit: impl Fn(i32, &str) + 'static) -> JsResult<()> {
  let emit = Function::new(ctx.clone(), move |level: i32, line: String| emit(level, &line))?;
  let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
  let console: Object = factory.call((emit,))?;
  ctx.globals().set("console", console)
}

#[cfg(test)]
#[path = "log_tests.rs"]
mod tests;
