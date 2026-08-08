//! `inu.utils`. The four codecs are native; the formatters and `utils.peers` are prelude js in
//! `utils.js`, handed the same `utils` object to finish and freeze.
//!
//! **The formatters do not reach the app's own formatter.** There is no bridge to
//! `LocaleController`, so this is the engine's own arithmetic with English month/weekday names and
//! a 24-hour clock; `common.d.ts` says so, and the output is for display, never for parsing.
//!
//! [`install_utils`] returns an object because `message.js` shares its TL name normalization and
//! peer arithmetic.

use base64::Engine;
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};

use crate::engine::error::{get_or_create_inu, throw_plugin_error};

const PRELUDE: &str = include_str!("utils.js");

const HEX_DIGITS: &[u8; 16] = b"0123456789abcdef";

pub fn install_utils<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    let utils = Object::new(ctx.clone())?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, bytes: Value<'js>| {
        read_bytes(&ctx, &bytes, "toBase64").map(|bytes| base64::engine::general_purpose::STANDARD.encode(bytes))
    })?;
    utils.set("toBase64", f)?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, text: String| -> JsResult<TypedArray<'js, u8>> {
        match decode_base64(&text) {
            Some(bytes) => TypedArray::<u8>::new(ctx, bytes),
            None => throw_plugin_error(&ctx, "invalid-argument", "fromBase64: not base64", None, None, None),
        }
    })?;
    utils.set("fromBase64", f)?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, bytes: Value<'js>| {
        read_bytes(&ctx, &bytes, "toHex").map(|bytes| encode_hex(&bytes))
    })?;
    utils.set("toHex", f)?;

    let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, text: String| -> JsResult<TypedArray<'js, u8>> {
        match decode_hex(&text) {
            Some(bytes) => TypedArray::<u8>::new(ctx, bytes),
            None => {
                throw_plugin_error(&ctx, "invalid-argument", "fromHex: expected hex digits, in pairs", None, None, None)
            }
        }
    })?;
    utils.set("fromHex", f)?;

    let inu = get_or_create_inu(ctx)?;
    // captured at install so a plugin reassigning `inu.PluginError` cannot decide what the prelude
    // throws, the same reason `globals.js` takes its natives as an argument
    let plugin_error: Value = inu.get("PluginError")?;

    let mut options = rquickjs::context::EvalOptions::default();
    options.filename = Some("<inu:utils>".to_string());
    let factory: Function = ctx.eval_with_options(PRELUDE, options)?;
    let shared: Object = factory.call((utils.clone(), plugin_error))?;

    inu.set("utils", utils)?;
    Ok(shared)
}

fn read_bytes<'js>(ctx: &Ctx<'js>, value: &Value<'js>, what: &str) -> JsResult<Vec<u8>> {
    let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) else {
        return Err(Exception::throw_type(ctx, &format!("{what}: expected a Uint8Array")));
    };
    let Some(bytes) = typed.as_bytes() else {
        return Err(Exception::throw_type(ctx, &format!("{what}: the array is detached")));
    };
    Ok(bytes.to_vec())
}

/// padded is what [`install_utils`] emits and what a `$inuBytes` wrapper carries, but unpadded is
/// what a great deal of the web hands out, so both decode rather than one of them being a puzzle
fn decode_base64(text: &str) -> Option<Vec<u8>> {
    let standard = base64::engine::general_purpose::STANDARD;
    if let Ok(bytes) = standard.decode(text) {
        return Some(bytes);
    }
    base64::engine::general_purpose::STANDARD_NO_PAD.decode(text).ok()
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX_DIGITS[(byte >> 4) as usize] as char);
        out.push(HEX_DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

fn decode_hex(text: &str) -> Option<Vec<u8>> {
    let digits = text.as_bytes();
    if !digits.len().is_multiple_of(2) {
        return None;
    }
    let mut out = Vec::with_capacity(digits.len() / 2);
    for pair in digits.chunks_exact(2) {
        let high = (pair[0] as char).to_digit(16)?;
        let low = (pair[1] as char).to_digit(16)?;
        out.push((high * 16 + low) as u8);
    }
    Some(out)
}

#[cfg(test)]
#[path = "utils_tests.rs"]
mod tests;
