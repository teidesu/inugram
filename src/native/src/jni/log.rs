use jni::objects::{Global, JMethodID, JObject, JValue};
use jni::refs::IntoAuto;
use jni::signature::{Primitive, ReturnType};
use rquickjs::function::Rest;
use rquickjs::{Coerced, Ctx, Function, Object};
use std::rc::Rc;
use std::sync::Arc;

use super::env::{clear_exception, with_current_env};
use crate::classify_log;

use super::bridge::JniBridge;

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

pub(crate) fn install_console(ctx: &Ctx, bridge: Rc<JniBridge>) -> rquickjs::Result<()> {
  let console = Object::new(ctx.clone())?;
  for (name, level) in [("log", 0), ("info", 1), ("warn", 2), ("error", 3), ("debug", 4)] {
    let bridge = bridge.clone();
    console.set(
      name,
      Function::new(ctx.clone(), move |args: Rest<Coerced<String>>| {
        let joined = args.0.iter().map(|c| c.0.as_str()).collect::<Vec<_>>().join(" ");
        bridge.emit_console(level, &joined);
      })?,
    )?;
  }
  ctx.globals().set("console", console)?;
  Ok(())
}
