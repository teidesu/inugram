use std::fs;
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Result as JsResult, Runtime, TypedArray, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{mint_app_file, BlobState, BUILD_LIMIT_BYTES};
use crate::runtime::{pump_jobs, PendingTable};
use crate::sandbox::grants::{GrantHost, MATCH_DOMAIN};
use crate::utils::prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fetch.qbc"));

pub trait FetchHost {
  fn send(&self, request_id: i64, url: &str, spec_json: &str, body: Option<&[u8]>) -> Option<String>;

  fn abort(&self, request_id: i64);
}

pub struct FetchState {
  host: Rc<dyn FetchHost>,
  grants: Rc<dyn GrantHost>,
  blobs: Rc<BlobState>,
  log: crate::Log,
  pending: PendingTable<()>,
}

fn parse_target(url: &str) -> Result<String, String> {
  crate::api::url::parse_http_url("fetch", url)
}

enum BodyError {
  HandleExpired(String),
  InvalidArgument(String),
  QuotaExceeded { usage: u64, message: String },
}

impl BodyError {
  fn message(&self) -> &str {
    match self {
      Self::HandleExpired(message) | Self::InvalidArgument(message) => message,
      Self::QuotaExceeded { message, .. } => message,
    }
  }

  fn code(&self) -> PluginErrorCode<'_> {
    match self {
      Self::HandleExpired(_) => PluginErrorCode::HandleExpired,
      Self::InvalidArgument(_) => PluginErrorCode::InvalidArgument,
      Self::QuotaExceeded { usage, .. } => PluginErrorCode::QuotaExceeded(
        i64::try_from(*usage).unwrap_or(i64::MAX),
        i64::try_from(BUILD_LIMIT_BYTES).unwrap_or(i64::MAX),
      ),
    }
  }
}

impl FetchState {
  fn read_body(&self, value: &Value<'_>) -> Result<Option<Vec<u8>>, BodyError> {
    if value.is_undefined() || value.is_null() {
      return Ok(None);
    }
    if let Some(text) = value.as_string() {
      let text = text.to_string().map_err(|e| BodyError::InvalidArgument(format!("fetch: {e:?}")))?;
      return Ok(Some(text.into_bytes()));
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
      let Some(bytes) = typed.as_bytes() else {
        return Err(BodyError::InvalidArgument("fetch: the body array is detached".to_string()));
      };
      return Ok(Some(bytes.to_vec()));
    }
    if let Some(wire) = self.blobs.export_for_host(value) {
      let id = wire
        .strip_prefix('B')
        .and_then(|rest| rest.split(':').next())
        .and_then(|id| id.parse().ok())
        .unwrap_or(0);
      let Some(export) = self.blobs.resolve_export(id) else {
        return Err(BodyError::HandleExpired("fetch: the body blob is gone".to_string()));
      };
      if export.len() > BUILD_LIMIT_BYTES {
        return Err(BodyError::QuotaExceeded {
          usage: export.len(),
          message: format!(
            "fetch: a request body is capped at {BUILD_LIMIT_BYTES} bytes; use uploadFile for anything bigger"
          ),
        });
      }
      let bytes = export
        .read(0, export.len())
        .map_err(|_| BodyError::HandleExpired("fetch: the body blob is gone".to_string()))?;
      return Ok(Some(bytes));
    }
    if rquickjs::Class::<crate::api::io::blob::BlobHandle>::from_value(value).is_ok() {
      return Err(BodyError::HandleExpired("fetch: the body blob was disposed".to_string()));
    }
    Err(BodyError::InvalidArgument("fetch: the body must be a string, a Uint8Array or a Blob".to_string()))
  }

  fn js_send<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    url: String,
    spec: Value<'js>,
    body: Value<'js>,
  ) -> JsResult<Object<'js>> {
    let spec_json = ctx.json_stringify(spec)?.map(|s| s.to_string()).transpose()?.unwrap_or_else(|| "{}".to_string());
    let host = match parse_target(&url) {
      Ok(host) => host,
      Err(message) => return PluginErrorCode::InvalidArgument.throw(ctx, &message),
    };
    self.grants.check_grant(ctx, "fetch", Some(&host), MATCH_DOMAIN)?;

    let body = match self.read_body(&body) {
      Ok(body) => body,
      Err(error) => return error.code().throw(ctx, error.message()),
    };

    let mut request_id = 0;
    let promise = self.pending.park(ctx, (), |id| {
      request_id = id;
      self.host.send(id, &url, &spec_json, body.as_deref())
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
  blobs: Rc<BlobState>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<FetchState>> {
  let state = Rc::new(FetchState {
    host,
    grants,
    blobs,
    log,
    pending: PendingTable::default(),
  });

  let natives = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    natives.set(
      "send",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, url: String, spec: Value<'js>, body: Value<'js>| {
        state.js_send(&ctx, url, spec, body)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "abort",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, request_id: i64| {
        state.pending.forget(&ctx, request_id);
        state.host.abort(request_id);
      })?,
    )?;
  }

  let plugin_error = globals.plugin_error.clone();
  let timers = Object::new(ctx.clone())?;
  for name in ["setTimeout", "clearTimeout"] {
    let f: Value = ctx.globals().get(name)?;
    timers.set(name, f)?;
  }

  let factory = prelude::load(ctx, PRELUDE)?;
  factory.call::<_, ()>((natives, plugin_error, timers))?;
  Ok(state)
}

fn mint_body<'js>(ctx: &Ctx<'js>, body: &Object<'js>) -> JsResult<Value<'js>> {
  let path: String = body.get("path")?;
  let mime: String = body.get("type").unwrap_or_default();
  let path = PathBuf::from(path);
  let (size, mtime) = match fs::metadata(&path) {
    Ok(meta) => (
      meta.len(),
      meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0),
    ),
    Err(_) => (0, 0),
  };
  mint_app_file(ctx, &path, size, &mime, None, mtime)
}

impl FetchState {
  pub fn settle(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context, request_id: i64, result_wire: &str) {
    let state = self;
    context.with(|ctx| {
      let settled = state.pending.settle(&ctx, request_id, result_wire, false, |ctx, _, wire| {
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
      if let Err(why) = settled {
        (state.log)(&format!("fetch({request_id}) settle failed: {why}"));
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
    context.with(|ctx| self.pending.dispose(&ctx));
  }
}

#[cfg(test)]
#[path = "fetch_tests.rs"]
mod tests;
