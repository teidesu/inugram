use crate::runtime::enter_js;
use std::cell::RefCell;
use std::collections::HashMap;

use rquickjs::function::{Constructor, IntoArgs};
use rquickjs::{Coerced, Context, Ctx, Exception, Function, JsLifetime, Result as JsResult, Runtime, Value};

use crate::api::Globals;

pub(crate) fn format_thrown<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
  let message = crate::sandbox::limits::describe_heap_exhaustion(value)
    .unwrap_or_else(|| describe_value(ctx, value, "JS exception"));
  let _ = ctx.catch();
  message
}

fn describe_value<'js>(ctx: &Ctx<'js>, value: &Value<'js>, fallback: &str) -> String {
  use rquickjs::FromJs;

  let mut message = Coerced::<String>::from_js(ctx, value.clone())
    .map(|coerced| coerced.0)
    .unwrap_or_else(|_| fallback.to_string());
  if let Some(object) = value.as_object() {
    if let Ok(stack) = object.get::<_, String>("stack") {
      if !stack.is_empty() {
        message.push('\n');
        message.push_str(&stack);
      }
    }
  }
  message
}

pub(crate) fn format_exception(ctx: &Ctx<'_>) -> String {
  format_thrown(ctx, &ctx.catch())
}

pub(crate) fn report_callback_error(log: &crate::Log, ctx: &Ctx<'_>, what: &str, error: rquickjs::Error) {
  if error.is_exception() {
    log(&crate::fault(format_args!("{what} threw: {}", format_exception(ctx))));
  } else {
    log(&format!("{what} failed: {error:?}"));
  }
}

pub(crate) fn call_callback<'js, A: IntoArgs<'js>>(
  ctx: &Ctx<'js>,
  log: &crate::Log,
  what: &str,
  callback: &Function<'js>,
  args: A,
) -> Option<Value<'js>> {
  match callback.call::<_, Value>(args) {
    Ok(value) => Some(value),
    Err(e) => {
      report_callback_error(log, ctx, what, e);
      None
    }
  }
}

pub(crate) fn describe_js_error(ctx: &Ctx<'_>, error: rquickjs::Error) -> String {
  match error {
    rquickjs::Error::Exception => format_exception(ctx),
    other => other.to_string(),
  }
}

pub(crate) fn error_value_to_string<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
  use rquickjs::FromJs;

  if let Some(object) = value.as_object() {
    if let Ok(message) = object.get::<_, String>("message") {
      if !message.is_empty() {
        return message;
      }
    }
  }
  Coerced::<String>::from_js(ctx, value.clone())
    .map(|coerced| coerced.0)
    .unwrap_or_else(|_| "unknown error".to_string())
}

#[derive(JsLifetime)]
struct Rejections {
  log: crate::Log,
  pending: RefCell<HashMap<u64, String>>,
}

fn get_value_hash(value: &Value<'_>) -> u64 {
  use std::collections::hash_map::DefaultHasher;
  use std::hash::{Hash, Hasher};

  let mut hasher = DefaultHasher::new();
  value.hash(&mut hasher);
  hasher.finish()
}

pub(crate) fn install_rejection_tracker(runtime: &Runtime, context: &Context, log: crate::Log) -> JsResult<()> {
  enter_js(context, |ctx| {
    ctx.store_userdata(Rejections {
      log,
      pending: RefCell::new(HashMap::new()),
    })
  })?;
  runtime.set_host_promise_rejection_tracker(Some(Box::new(|ctx, promise, reason, is_handled| {
    let Some(rejections) = ctx.userdata::<Rejections>() else {
      return;
    };
    let promise_hash = get_value_hash(&promise);
    if is_handled {
      rejections.pending.borrow_mut().remove(&promise_hash);
    } else {
      let message = format_thrown(&ctx, &reason);
      rejections.pending.borrow_mut().insert(promise_hash, message);
    }
  })));
  Ok(())
}

pub(crate) fn report_rejections(ctx: &Ctx<'_>) {
  let Some(rejections) = ctx.userdata::<Rejections>() else {
    return;
  };
  let messages = rejections.pending.borrow_mut().drain().map(|(_, message)| message).collect::<Vec<_>>();
  for message in messages {
    (rejections.log)(&crate::fault(format_args!("unhandled promise rejection: {message}")));
  }
}

#[derive(Clone, Copy)]
pub enum PluginErrorCode<'a> {
  InvalidArgument,
  NotFound,
  NotGranted(&'a str),
  Forbidden,
  HandleExpired,
  Unsupported,
  Internal,
  TimedOut,
  Aborted,
  QuotaExceeded(i64, i64),
}

impl<'a> PluginErrorCode<'a> {
  pub(crate) fn name(self) -> &'static str {
    match self {
      Self::InvalidArgument => "invalid-argument",
      Self::NotFound => "not-found",
      Self::NotGranted(_) => "not-granted",
      Self::Forbidden => "forbidden",
      Self::HandleExpired => "handle-expired",
      Self::Unsupported => "unsupported",
      Self::Internal => "internal",
      Self::TimedOut => "timed-out",
      Self::Aborted => "aborted",
      Self::QuotaExceeded(..) => "quota-exceeded",
    }
  }

  pub fn throw<'js, T>(self, ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    let (grant, usage, quota) = match self {
      Self::NotGranted(grant) => (Some(grant), None, None),
      Self::QuotaExceeded(usage, quota) => (None, Some(usage), Some(quota)),
      _ => (None, None, None),
    };
    let value = make_plugin_error(ctx, self.name(), message, grant, usage, quota)?;
    Err(ctx.throw(value))
  }
}

pub fn install_plugin_error<'js>(ctx: &Ctx<'js>) -> JsResult<()> {
  if ctx.userdata::<Globals>().is_some() {
    return Ok(());
  }
  let ctor: Constructor = ctx.eval(
    r"(class PluginError extends Error {
      constructor(code, message) {
        super(message);
        this.name = 'PluginError';
        this.code = String(code);
      }
    })",
  )?;
  Globals::install(ctx, ctor)
}

pub fn make_plugin_error<'js>(
  ctx: &Ctx<'js>,
  code: &str,
  message: &str,
  grant: Option<&str>,
  usage: Option<i64>,
  quota: Option<i64>,
) -> JsResult<Value<'js>> {
  let globals = Globals::get(ctx)?;
  let obj = globals.plugin_error.construct::<_, rquickjs::Object>((code, message))?;
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

struct PluginErrorWire<'a> {
  code: &'a str,
  grant: Option<&'a str>,
  usage: Option<i64>,
  quota: Option<i64>,
  message: &'a str,
}

fn parse_optional_int(s: &str) -> Option<Option<i64>> {
  if s.is_empty() {
    return Some(None);
  }
  s.parse().ok().map(Some)
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
    grant: if grant.is_empty() { None } else { Some(grant) },
    usage: parse_optional_int(usage)?,
    quota: parse_optional_int(quota)?,
    message,
  })
}

fn structured_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
  if let Some((code, text)) = crate::api::tl::proxy::wire_rpc_error(wire) {
    return Some(Globals::get(ctx).and_then(|globals| globals.get_rpc_error(ctx)?.construct((code, text))));
  }
  let parsed = parse_plugin_error(wire.strip_prefix('P')?)?;
  Some(make_plugin_error(ctx, parsed.code, parsed.message, parsed.grant, parsed.usage, parsed.quota))
}

pub fn wire_error_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> Option<JsResult<Value<'js>>> {
  if let Some(message) = wire.strip_prefix('E') {
    return Some(Exception::from_message(ctx.clone(), message).map(Exception::into_value));
  }
  structured_error_to_js(ctx, wire)
}

pub(crate) fn throw_wire_error(ctx: &Ctx<'_>, wire: &str) -> JsResult<()> {
  match wire_error_to_js(ctx, wire) {
    Some(built) => Err(ctx.throw(built?)),
    None => Ok(()),
  }
}

/// Decodes `P`/`R` errors from an error-only channel. Other strings represent bridge failures and
/// become `internal` errors.
pub fn host_error_to_js<'js>(ctx: &Ctx<'js>, err: &str) -> JsResult<Value<'js>> {
  match structured_error_to_js(ctx, err) {
    Some(value) => value,
    None => make_plugin_error(ctx, "internal", err, None, None, None),
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
