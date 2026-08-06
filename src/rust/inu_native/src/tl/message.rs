//! `inu.Message` - the convenience wrapper over a raw TL message, per `common.d.ts`.
//!
//! Pure js in `message.js`; this module only evaluates it and hangs the class off `inu`. It takes
//! the helpers [`crate::tl::utils`]'s prelude returns, so TL name normalization and peer arithmetic
//! have one implementation between them.
//!
//! **It composes with the takeover filter for free, and must keep doing so.** Every getter is a
//! read through `raw`, and the filter lives at materialization (`TlFilter` in Kotlin), so
//! `message.text` is whatever `raw.message` is willing to answer - the redacted form on a message
//! from a service peer. Nothing here caches a field, snapshots `raw` at construction, or reads a
//! sender out of anything but the fields the filter itself seals; a wrapper that did any of those
//! would be a way to read a login code the filter had already decided to hide.

use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::engine::error::get_or_create_inu;

const PRELUDE: &str = include_str!("message.js");

/// `shared` is what [`crate::tl::utils::install_utils`] handed back
pub fn install_message<'js>(ctx: &Ctx<'js>, shared: &Object<'js>) -> JsResult<()> {
    let inu = get_or_create_inu(ctx)?;
    let plugin_error: Value = inu.get("PluginError")?;

    let mut options = rquickjs::context::EvalOptions::default();
    options.filename = Some("<inu:message>".to_string());
    let factory: Function = ctx.eval_with_options(PRELUDE, options)?;
    let class: Value = factory.call((shared.clone(), plugin_error))?;

    inu.set("Message", class)?;
    Ok(())
}

#[cfg(test)]
#[path = "message_tests.rs"]
mod tests;
