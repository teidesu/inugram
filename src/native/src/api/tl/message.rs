use rquickjs::{Ctx, Object, Result as JsResult, Value};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/message.qbc"));

pub fn install_message<'js>(ctx: &Ctx<'js>, shared: &Object<'js>, inu: &Object<'js>) -> JsResult<()> {
    let plugin_error: Value = inu.get("PluginError")?;

    let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
    let class: Value = factory.call((shared.clone(), plugin_error))?;

    inu.set("Message", class)?;
    Ok(())
}

#[cfg(test)]
#[path = "message_tests.rs"]
mod tests;
