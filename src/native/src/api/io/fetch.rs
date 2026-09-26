use crate::api::url;
use crate::runtime::enter_js;
use crate::runtime::Dispose;
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::convert::Coerced;
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{self, mint_app_file_at, BlobHandle, BUILD_LIMIT_BYTES};
use crate::runtime::PendingTable;
use crate::sandbox::grants::{GrantHost, MATCH_DOMAIN};
use crate::utils::qjs::{qjs_load_prelude, qjs_read_typed_bytes};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fetch.qbc"));

pub struct Spec {
  pub method: String,
  pub redirect: String,
  /// `name, value` pairs, names lowercased, in the order the plugin wrote them
  pub headers: Vec<String>,
}

pub trait FetchHost {
  fn send(&self, request_id: i64, url: &str, spec: &Spec, body: Option<&[u8]>) -> Option<String>;

  fn abort(&self, request_id: i64);
}

pub struct FetchState {
  host: Rc<dyn FetchHost>,
  grants: Rc<dyn GrantHost>,
  log: crate::Log,
  pending: PendingTable<()>,
}

/// rfc7230's token, which is what a header name and a method are allowed to be
fn is_token(value: &str) -> bool {
  !value.is_empty() && value.bytes().all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}

/// headers the transport owns: okhttp has no restricted-name list of its own and supplies `Host`
/// only when it is absent, so one of these from a plugin goes on the wire
const RESERVED_HEADERS: [&str; 8] =
  ["host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer"];

const REDIRECT_MODES: [&str; 3] = ["follow", "manual", "error"];

fn coerce_string<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
  Ok(
    value
      .get::<Coerced<String>>()
      .map_err(|_| Exception::throw_type(ctx, "fetch: expected a string"))?
      .0,
  )
}

const HTTP_WHITESPACE: [char; 4] = ['\t', '\n', '\r', ' '];

fn is_header_value(value: &str) -> bool {
  !value.chars().any(|c| (c < ' ' && c != '\t') || c == '\u{7f}' || c > '\u{ff}')
}

fn normalize_header_name<'js>(ctx: &Ctx<'js>, name: &str) -> JsResult<String> {
  if !is_token(name) {
    return Err(Exception::throw_type(ctx, &format!("'{name}' is not a header name")));
  }
  Ok(name.to_ascii_lowercase())
}

fn normalize_header_value<'js>(ctx: &Ctx<'js>, value: &str) -> JsResult<String> {
  let value = value.trim_matches(HTTP_WHITESPACE);
  if !is_header_value(value) {
    return Err(Exception::throw_type(ctx, &format!("'{value}' is not a header value")));
  }
  Ok(value.to_string())
}

/// `headers` is the flat `name, value` list the prelude built, re-checked here: the prelude shares
/// the plugin's realm, so its `Headers` may have been handed anything
fn read_header_pairs<'js>(ctx: &Ctx<'js>, headers: Value<'js>) -> JsResult<Vec<String>> {
  let Some(array) = headers.as_array() else {
    return Err(Exception::throw_type(ctx, "fetch: headers must be a flat name/value list"));
  };
  let mut pairs = Vec::with_capacity(array.len());
  for value in array.iter::<Value>() {
    let Some(text) = value?.as_string().cloned() else {
      return Err(Exception::throw_type(ctx, "fetch: a header name or value must be a string"));
    };
    pairs.push(text.to_string()?);
  }
  if pairs.len() % 2 != 0 {
    return Err(Exception::throw_type(ctx, "fetch: headers must be a flat name/value list"));
  }
  for pair in pairs.chunks_exact_mut(2) {
    let name = normalize_header_name(ctx, &pair[0])?;
    if RESERVED_HEADERS.contains(&name.as_str()) {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, &format!("fetch: the '{name}' header belongs to the transport"));
    }
    if !is_header_value(&pair[1]) {
      return Err(Exception::throw_type(ctx, &format!("fetch: the '{name}' header has a control character in it")));
    }
    pair[0] = name;
  }
  Ok(pairs)
}

fn read_spec<'js>(ctx: &Ctx<'js>, method: Value<'js>, headers: Value<'js>, redirect: Value<'js>) -> JsResult<Spec> {
  let redirect = if redirect.is_undefined() { "follow".to_string() } else { coerce_string(ctx, redirect)? };
  if !REDIRECT_MODES.contains(&redirect.as_str()) {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("fetch: '{redirect}' is not a redirect mode"));
  }
  let method = if method.is_undefined() { "GET".to_string() } else { coerce_string(ctx, method)? };
  if !is_token(&method) {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("fetch: '{method}' is not a method"));
  }
  Ok(Spec {
    method: method.to_ascii_uppercase(),
    redirect,
    headers: read_header_pairs(ctx, headers)?,
  })
}

impl FetchState {
  fn read_body<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Option<Vec<u8>>> {
    if value.is_undefined() || value.is_null() {
      return Ok(None);
    }
    if let Some(text) = value.as_string() {
      return Ok(Some(text.to_string()?.into_bytes()));
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      let Some(bytes) = qjs_read_typed_bytes(&typed, <[u8]>::to_vec) else {
        return PluginErrorCode::InvalidArgument.throw(ctx, "fetch: the body array is detached");
      };
      return Ok(Some(bytes));
    }
    if let Some(export) = blob::export_blob(value) {
      if export.len() > BUILD_LIMIT_BYTES {
        return PluginErrorCode::QuotaExceeded(export.len() as i64, BUILD_LIMIT_BYTES as i64).throw(
          ctx,
          &format!("fetch: a request body is capped at {BUILD_LIMIT_BYTES} bytes; use uploadFile for anything bigger"),
        );
      }
      return match export.read(0, export.len()) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(_) => PluginErrorCode::HandleExpired.throw(ctx, "fetch: the body blob is gone"),
      };
    }
    if rquickjs::Class::<BlobHandle>::from_value(value).is_ok() {
      return PluginErrorCode::HandleExpired.throw(ctx, "fetch: the body blob was disposed");
    }
    PluginErrorCode::InvalidArgument.throw(ctx, "fetch: the body must be a string, a Uint8Array or a Blob")
  }

  fn js_send<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, url: String, spec: Spec, body: Value<'js>) -> JsResult<Object<'js>> {
    let host = match url::parse_http_url("fetch", &url) {
      Ok(host) => host,
      Err(message) => return PluginErrorCode::InvalidArgument.throw(ctx, &message),
    };
    self.grants.check_grant(ctx, "fetch", Some(&host), MATCH_DOMAIN)?;

    let body = self.read_body(ctx, &body)?;

    let mut request_id = 0;
    let promise = self.pending.park(ctx, (), |id| {
      request_id = id;
      self.host.send(id, &url, &spec, body.as_deref())
    })?;

    let handle = Object::new(ctx.clone())?;
    handle.set("id", request_id)?;
    handle.set("promise", promise)?;
    Ok(handle)
  }
}

pub fn install_fetch<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn FetchHost>,
  grants: Rc<dyn GrantHost>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FetchState>> {
  let state = Rc::new(FetchState {
    host,
    grants,
    log,
    pending: PendingTable::default(),
  });

  let natives = Object::new(ctx.clone())?;
  set_fn!(natives, "send", ctx, state, move |ctx: Ctx<'js>,
                                             url: String,
                                             method: Value<'js>,
                                             headers: Value<'js>,
                                             redirect: Value<'js>,
                                             body: Value<'js>| {
    let spec = read_spec(&ctx, method, headers, redirect)?;
    state.js_send(&ctx, url, spec, body)
  });
  natives.set(
    "headerName",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, name: Value<'js>| {
      let name = coerce_string(&ctx, name)?;
      normalize_header_name(&ctx, &name)
    })?,
  )?;
  natives.set(
    "headerValue",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, value: Value<'js>| {
      let value = coerce_string(&ctx, value)?;
      normalize_header_value(&ctx, &value)
    })?,
  )?;
  set_fn!(natives, "abort", ctx, state, move |ctx: Ctx<'js>, request_id: i64| {
    state.pending.forget(&ctx, request_id);
    state.host.abort(request_id);
  });

  let plugin_error = globals.plugin_error.clone();
  let timers = Object::new(ctx.clone())?;
  for name in ["setTimeout", "clearTimeout"] {
    let f: Value = ctx.globals().get(name)?;
    timers.set(name, f)?;
  }

  let factory = qjs_load_prelude(ctx, PRELUDE)?;
  factory.call::<_, ()>((natives, plugin_error, timers))?;
  Ok(state)
}

fn mint_body<'js>(ctx: &Ctx<'js>, body: &Object<'js>) -> JsResult<Value<'js>> {
  let path: String = body.get("path")?;
  let mime: String = body.get("type").unwrap_or_default();
  mint_app_file_at(ctx, &PathBuf::from(path), &mime)
}

impl FetchState {
  pub fn settle(self: &Rc<Self>, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    self.pending.settle_and_pump(context, &self.log, "fetch", request_id, result_wire, |ctx, _, wire| {
      let json = wire
        .strip_prefix('J')
        .ok_or_else(|| rquickjs::Exception::throw_message(ctx, "fetch: malformed host response"))?;
      let value = ctx.json_parse(json)?;
      let object = value
        .as_object()
        .cloned()
        .ok_or_else(|| rquickjs::Exception::throw_message(ctx, "fetch: malformed host response"))?;
      let body: Option<Object> = object.get("body")?;
      let blob = match body {
        Some(body) => mint_body(ctx, &body)?,
        None => Value::new_null(ctx.clone()),
      };
      object.set("body", blob)?;
      Ok(object.into_value())
    });
  }
}

impl Dispose for FetchState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "fetch_tests.rs"]
mod tests;
