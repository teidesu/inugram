use rquickjs::{Ctx, Object, Result as JsResult};

use crate::utils::qjs::qjs_load_prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/message.qbc"));

pub fn install_message<'js>(ctx: &Ctx<'js>, shared: &Object<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
  let plugin_error = globals.plugin_error.clone();

  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  let class = factory.call((shared.clone(), plugin_error))?;

  globals.set_message(class)
}

#[cfg(test)]
#[path = "message_tests.rs"]
mod tests;
