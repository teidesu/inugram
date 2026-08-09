use rquickjs::function::Constructor;
use rquickjs::{Ctx, Object, Result as JsResult, Value};

use crate::api::telegram::rpc;

pub fn install_plugin_error<'js>(ctx: &Ctx<'js>, inu: &Object<'js>) -> JsResult<()> {
    let ctor: Value = ctx.eval(
        r#"(class PluginError extends Error {
            constructor(code, message) {
                super(message);
                this.name = 'PluginError';
                this.code = String(code);
            }
        })"#,
    )?;
    inu.set("PluginError", ctor)?;
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

pub fn make_quota_error<'js>(ctx: &Ctx<'js>, message: &str, usage: i64, quota: i64) -> JsResult<Value<'js>> {
    make_plugin_error(ctx, "quota-exceeded", message, None, Some(usage), Some(quota))
}

pub fn throw_invalid_argument<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "invalid-argument", message, None, None, None)
}

pub fn throw_not_found<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "not-found", message, None, None, None)
}

pub fn throw_not_granted<'js, T>(ctx: &Ctx<'js>, message: &str, grant: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "not-granted", message, Some(grant), None, None)
}

pub fn throw_forbidden<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "forbidden", message, None, None, None)
}

pub fn throw_handle_expired<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "handle-expired", message, None, None, None)
}

pub fn throw_internal<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "internal", message, None, None, None)
}

pub fn throw_quota_exceeded<'js, T>(ctx: &Ctx<'js>, message: &str, usage: i64, quota: i64) -> JsResult<T> {
    throw_plugin_error(ctx, "quota-exceeded", message, None, Some(usage), Some(quota))
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

fn structured_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
    if let Some((code, text)) = crate::api::tl::proxy::wire_rpc_error(wire) {
        return Some(rpc::make_rpc_error(ctx, code, text));
    }
    let parsed = parse_plugin_error(wire.strip_prefix('P')?)?;
    Some(make_plugin_error(ctx, parsed.code, parsed.message, parsed.grant, parsed.usage, parsed.quota))
}

pub fn wire_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
    if let Some(message) = wire.strip_prefix('E') {
        return Some(rpc::make_error(ctx, message));
    }
    structured_error_to_js(ctx, wire)
}

pub fn host_error_to_js<'js>(ctx: &Ctx<'js>, err: &str) -> JsResult<Value<'js>> {
    match structured_error_to_js(ctx, err) {
        Some(value) => value,
        None => rpc::make_error(ctx, err),
    }
}

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
