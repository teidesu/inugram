use rquickjs::function::Opt;
use rquickjs::{Array, Ctx, Exception, Function, Object, Result as JsResult, Value};

pub const ARRAY_LIMIT: usize = 65536;

pub fn opt<'js>(value: Opt<Value<'js>>) -> Option<Value<'js>> {
  value.0.filter(|v| !v.is_undefined())
}

pub fn array_values<'js>(ctx: &Ctx<'js>, array: &Array<'js>, what: &str) -> JsResult<Vec<Value<'js>>> {
  let len = array_len(ctx, array, what)?;
  let mut out = Vec::with_capacity(len);
  for index in 0..len {
    out.push(array.get::<Value>(index)?);
  }
  Ok(out)
}

pub fn array_len(ctx: &Ctx<'_>, array: &Array<'_>, what: &str) -> JsResult<usize> {
  let len: f64 = array.as_object().get("length")?;
  if !(0.0..=ARRAY_LIMIT as f64).contains(&len) {
    crate::api::error::PluginErrorCode::InvalidArgument
      .throw(ctx, &format!("{what}: at most {ARRAY_LIMIT} elements"))?;
  }
  Ok(len as usize)
}

pub fn field<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Value<'js>> {
  obj.get(key).map_err(|_| Exception::throw_type(ctx, &format!("{what}: cannot read '{key}'")))
}

pub fn req_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<String> {
  let v = field(ctx, obj, what, key)?;
  match v.as_string() {
    Some(s) => Ok(s.to_string()?),
    None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
  }
}

pub fn opt_str<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<String>> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(None);
  }
  match v.as_string() {
    Some(s) => Ok(Some(s.to_string()?)),
    None => Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string"))),
  }
}

pub fn req_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
  let v = field(ctx, obj, what, key)?;
  v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

pub fn opt_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(false);
  }
  v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

pub fn req_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<f64> {
  let v = field(ctx, obj, what, key)?;
  if let Some(i) = v.as_int() {
    return Ok(i as f64);
  }
  v.as_float().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

pub fn opt_num<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<f64>> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(None);
  }
  if let Some(i) = v.as_int() {
    return Ok(Some(i as f64));
  }
  v.as_float()
    .map(Some)
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a number")))
}

pub fn req_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Function<'js>> {
  let v = field(ctx, obj, what, key)?;
  v.into_function()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

pub fn opt_fn<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<Function<'js>>> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(None);
  }
  v.into_function()
    .map(Some)
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a function")))
}

#[cfg(test)]
#[path = "arguments_tests.rs"]
mod tests;
