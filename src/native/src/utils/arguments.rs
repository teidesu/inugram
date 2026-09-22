use rquickjs::function::Opt;
use rquickjs::{Array, Ctx, Exception, Function, Object, Result as JsResult, Value};

use crate::api::error::PluginErrorCode;

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
    PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: at most {ARRAY_LIMIT} elements"))?;
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

/// Reads `InputText` from `common.d.ts`: a string or `{ text, entities }`.
///
/// Passes entities through unchanged. Hosts that support formatting convert them to spans; other
/// hosts use the plain text.
pub fn read_input_text<'js>(
  ctx: &Ctx<'js>,
  value: &Value<'js>,
  what: &str,
  key: &str,
) -> JsResult<(String, Option<Value<'js>>)> {
  if let Some(text) = value.as_string() {
    return Ok((text.to_string()?, None));
  }
  let Some(obj) = value.as_object() else {
    return Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string or {{ text, entities }}")));
  };
  let text = field(ctx, obj, what, "text")?;
  let Some(text) = text.as_string() else {
    return Err(Exception::throw_type(ctx, &format!("{what}: '{key}' must be a string or {{ text, entities }}")));
  };
  let entities = field(ctx, obj, what, "entities")?;
  if entities.is_undefined() || entities.is_null() {
    return Ok((text.to_string()?, None));
  }
  if entities.as_array().is_none() {
    return Err(Exception::throw_type(ctx, &format!("{what}: '{key}' entities must be an array")));
  }
  Ok((text.to_string()?, Some(entities)))
}

/// Writes [`read_input_text`] output as the text key and a `<key>Entities` key. Hosts that render
/// spans read both; other hosts read the text only.
pub fn write_input_text<'js>(out: &Object<'js>, key: &str, value: (String, Option<Value<'js>>)) -> JsResult<()> {
  out.set(key, value.0)?;
  if let Some(entities) = value.1 {
    out.set(format!("{key}Entities"), entities)?;
  }
  Ok(())
}

pub fn req_text<'js>(
  ctx: &Ctx<'js>,
  obj: &Object<'js>,
  what: &str,
  key: &str,
) -> JsResult<(String, Option<Value<'js>>)> {
  let v = field(ctx, obj, what, key)?;
  read_input_text(ctx, &v, what, key)
}

pub fn opt_text<'js>(
  ctx: &Ctx<'js>,
  obj: &Object<'js>,
  what: &str,
  key: &str,
) -> JsResult<Option<(String, Option<Value<'js>>)>> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(None);
  }
  read_input_text(ctx, &v, what, key).map(Some)
}

pub fn req_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
  let v = field(ctx, obj, what, key)?;
  v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

pub fn opt_bool<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<bool> {
  opt_bool_or(ctx, obj, what, key, false)
}

pub fn opt_bool_or<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str, default: bool) -> JsResult<bool> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(default);
  }
  v.as_bool().ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a boolean")))
}

/// an optional whole number, for a field naming something counted rather than measured
pub fn opt_int<'js>(ctx: &Ctx<'js>, obj: &Object<'js>, what: &str, key: &str) -> JsResult<Option<i32>> {
  let v = field(ctx, obj, what, key)?;
  if v.is_undefined() || v.is_null() {
    return Ok(None);
  }
  v.as_int()
    .or_else(|| v.as_float().filter(|f| f.fract() == 0.0).map(|f| f as i32))
    .map(Some)
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: '{key}' must be a whole number")))
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

/// a value as the host takes it, refusing what `JSON.stringify` drops entirely
pub fn stringify_json<'js>(ctx: &Ctx<'js>, value: Value<'js>, message: &str) -> JsResult<String> {
  ctx
    .json_stringify(value)?
    .map(|text| text.to_string())
    .transpose()?
    .ok_or_else(|| Exception::throw_message(ctx, message))
}

/// a selected-item index: an integer inside the list, refusing a fraction and a NaN alike
pub fn read_index<'js>(ctx: &Ctx<'js>, value: &Value<'js>, what: &str, len: usize) -> JsResult<i32> {
  let index = value
    .as_int()
    .or_else(|| value.as_float().filter(|f| f.fract() == 0.0).map(|f| f as i32))
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'selected' must be an integer index")))?;
  if index < 0 || index as usize >= len {
    return Err(Exception::throw_type(ctx, &format!("{what}: 'selected' out of range")));
  }
  Ok(index)
}
