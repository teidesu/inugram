//! `inu.PluginError`, and every other error shape a plugin can be handed. The gate that mints the
//! `not-granted` one is [`crate::sandbox::grants`].
//!
//! Errors cross the host boundary as [`crate::api::tl::proxy`]-tagged wire strings: `E<message>` a plain
//! `Error`, `R<code>:<text>` an `inu.RpcError`, and `P` an `inu.PluginError` shaped
//! `P<code>\n<grant>\n<usage>\n<quota>\n<message>` (mirrors `PluginWire.encodePluginError`
//! Kotlin-side): grant/usage/quota are empty when absent, and the message is everything past the
//! FOURTH newline, so it may contain newlines of its own.

use rquickjs::function::Constructor;
use rquickjs::{Ctx, Object, Result as JsResult, Value};

use crate::api::telegram::rpc;
use crate::utils::namespace::get_or_create_inu;

/// installs `inu.PluginError`; runs once per context at creation, before any plugin code, so a
/// plugin can `instanceof` it even against an api surface it holds no grant for
pub fn install_plugin_error(ctx: &Ctx) -> JsResult<()> {
    let ctor: Value = ctx.eval(
        r#"(class PluginError extends Error {
            constructor(code, message) {
                super(message);
                this.name = 'PluginError';
                this.code = String(code);
            }
        })"#,
    )?;
    get_or_create_inu(ctx)?.set("PluginError", ctor)?;
    Ok(())
}

fn get_plugin_error_ctor<'js>(ctx: &Ctx<'js>) -> JsResult<Constructor<'js>> {
    ctx.globals().get::<_, Object>("inu")?.get("PluginError")
}

pub fn make_plugin_error<'js>(
    ctx: &Ctx<'js>,
    code: &str,
    message: &str,
    grant: Option<&str>,
    usage: Option<i64>,
    quota: Option<i64>,
) -> JsResult<Value<'js>> {
    let obj: Object<'js> = get_plugin_error_ctor(ctx)?.construct((code, message))?;
    if let Some(grant) = grant {
        obj.set("grant", grant)?;
    }
    if let Some(usage) = usage {
        obj.set("usage", usage as f64)?;
    }
    if let Some(quota) = quota {
        obj.set("quota", quota as f64)?;
    }
    Ok(obj.into_value())
}

pub fn throw_plugin_error<'js, T>(
    ctx: &Ctx<'js>,
    code: &str,
    message: &str,
    grant: Option<&str>,
    usage: Option<i64>,
    quota: Option<i64>,
) -> JsResult<T> {
    let value = make_plugin_error(ctx, code, message, grant, usage, quota)?;
    Err(ctx.throw(value))
}

struct PluginErrorWire<'a> {
    code: &'a str,
    grant: Option<&'a str>,
    usage: Option<i64>,
    quota: Option<i64>,
    message: &'a str,
}

fn non_empty(s: &str) -> Option<&str> {
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

/// `Some(None)` == the field is absent, `None` == it is malformed
fn parse_optional_int(s: &str) -> Option<Option<i64>> {
    match non_empty(s) {
        None => Some(None),
        Some(s) => s.parse().ok().map(Some),
    }
}

fn parse_plugin_error(payload: &str) -> Option<PluginErrorWire<'_>> {
    let mut parts = payload.splitn(5, '\n');
    let code = parts.next()?;
    let grant = parts.next()?;
    let usage = parts.next()?;
    let quota = parts.next()?;
    let message = parts.next()?;
    if code.is_empty() {
        return None;
    }
    Some(PluginErrorWire {
        code,
        grant: non_empty(grant),
        usage: parse_optional_int(usage)?,
        quota: parse_optional_int(quota)?,
        message,
    })
}

/// the two tags a bare message cannot impersonate: `R` needs a colon and a parseable code, `P` a
/// four-newline header, and neither parses back into anything else. `E` is deliberately absent -
/// `PluginWire.encodeError` is `"E" + message` with nothing to validate.
fn structured_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
    if let Some((code, text)) = crate::api::tl::proxy::wire_rpc_error(wire) {
        return Some(rpc::make_rpc_error(ctx, code, text));
    }
    let parsed = parse_plugin_error(wire.strip_prefix('P')?)?;
    Some(make_plugin_error(ctx, parsed.code, parsed.message, parsed.grant, parsed.usage, parsed.quota))
}

/// `Some` when `wire` is an error-tagged value (`E`/`R`/`P`) and so must be thrown/rejected rather
/// than decoded into a value; `None` when it carries a value, or when its payload doesn't parse.
/// Only for channels where every string is a tagged wire (`tl_get`, `kv`, result and update wires):
/// one that also carries bare messages must go through [`host_error_to_js`] instead.
pub fn wire_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
    if let Some(message) = wire.strip_prefix('E') {
        return Some(rpc::make_error(ctx, message));
    }
    structured_error_to_js(ctx, wire)
}

/// the host's `Option<String>` error channel carries either a structured error wire or a bare
/// message, so an `E` wire here is indistinguishable from a message that starts with `E`. Both
/// surface as the same plain `Error`, so the tag buys nothing and stripping it would eat a real
/// message's first character: producers on this channel must not emit `E`.
pub fn host_error_to_js<'js>(ctx: &Ctx<'js>, err: &str) -> JsResult<Value<'js>> {
    match structured_error_to_js(ctx, err) {
        Some(value) => value,
        None => rpc::make_error(ctx, err),
    }
}

/// [`host_error_to_js`]'s counterpart for errors that go straight back out as a result wire instead
/// of into JS: an `R`/`P` wire is already one and survives untouched, a bare message gets tagged.
pub fn host_error_to_wire(err: &str) -> String {
    let structured = crate::api::tl::proxy::wire_rpc_error(err).is_some()
        || err.strip_prefix('P').and_then(parse_plugin_error).is_some();
    if structured {
        err.to_string()
    } else {
        crate::api::tl::proxy::encode_error(err)
    }
}

#[cfg(test)]
#[path = "error_tests.rs"]
mod error_tests;
