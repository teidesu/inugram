use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use rquickjs::function::Opt;
use rquickjs::{Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};
use std::rc::Rc;

use crate::api::error::PluginErrorCode;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/utils.qbc"));

pub const FORMAT_DATE: i32 = 0;
pub const FORMAT_TIME: i32 = 1;
pub const FORMAT_DATE_TIME: i32 = 2;
pub const FORMAT_RELATIVE_DATE: i32 = 3;
pub const FORMAT_NUMBER: i32 = 4;
pub const FORMAT_COMPACT_NUMBER: i32 = 5;
pub const FORMAT_FILE_SIZE: i32 = 6;
pub const FORMAT_DURATION: i32 = 7;

pub trait UtilsHost {
  fn format(&self, op: i32, value: i64) -> String;
}

#[cfg(test)]
pub fn install_utils<'js>(ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<Object<'js>> {
  install_utils_with_host(ctx, Rc::new(UnavailableUtilsHost), globals)
}

pub fn install_utils_with_host<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn UtilsHost>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Object<'js>> {
  let utils = Object::new(ctx.clone())?;

  utils.set(
    "toBase64",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, bytes: Value<'js>| {
      read_bytes(&ctx, &bytes, "toBase64").map(|bytes| STANDARD.encode(bytes))
    })?,
  )?;

  utils.set(
    "fromBase64",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, text: String| -> JsResult<TypedArray<'js, u8>> {
      let text: &str = &text;
      let bytes = STANDARD.decode(text).ok().or_else(|| STANDARD_NO_PAD.decode(text).ok());
      match bytes {
        Some(bytes) => TypedArray::<u8>::new(ctx, bytes),
        None => PluginErrorCode::InvalidArgument.throw(&ctx, "fromBase64: not base64"),
      }
    })?,
  )?;

  utils.set(
    "toHex",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, bytes: Value<'js>| read_bytes(&ctx, &bytes, "toHex").map(hex::encode))?,
  )?;

  utils.set(
    "fromHex",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, text: String| -> JsResult<TypedArray<'js, u8>> {
      match hex::decode(&text) {
        Ok(bytes) => TypedArray::<u8>::new(ctx, bytes),
        Err(_) => PluginErrorCode::InvalidArgument.throw(&ctx, "fromHex: expected hex digits, in pairs"),
      }
    })?,
  )?;

  {
    let host = host.clone();
    let f =
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, unix: Value<'js>, style: Opt<Value<'js>>| -> JsResult<String> {
        let value = format_integer(&ctx, &unix, "formatDate", i64::MIN, i64::MAX)?;
        let op = match style.0.filter(|style| !style.is_undefined() && !style.is_null()) {
          None => FORMAT_DATE_TIME,
          Some(style) => match style.as_string().and_then(|style| style.to_string().ok()).as_deref() {
            Some("date") => FORMAT_DATE,
            Some("time") => FORMAT_TIME,
            Some("dateTime") => FORMAT_DATE_TIME,
            Some("relative") => FORMAT_RELATIVE_DATE,
            Some(style) => {
              return PluginErrorCode::InvalidArgument.throw(&ctx, &format!("formatDate: unknown style '{style}'"))
            }
            None => return PluginErrorCode::InvalidArgument.throw(&ctx, "formatDate: unknown style"),
          },
        };
        Ok(host.format(op, value))
      })?;
    utils.set("formatDate", f)?;
  }
  {
    let host = host.clone();
    utils.set(
      "formatNumber",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, value: Value<'js>, options: Opt<Value<'js>>| -> JsResult<String> {
          let value = format_integer(&ctx, &value, "formatNumber", i64::MIN, i64::MAX)?;
          let compact = match options.0 {
            Some(options) if !options.is_undefined() && !options.is_null() => {
              options.as_object().is_some_and(|options| options.get::<_, bool>("compact").unwrap_or(false))
            }
            _ => false,
          };
          Ok(host.format(if compact { FORMAT_COMPACT_NUMBER } else { FORMAT_NUMBER }, value))
        },
      )?,
    )?;
  }
  {
    let host = host.clone();
    utils.set(
      "formatFileSize",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<String> {
        Ok(host.format(FORMAT_FILE_SIZE, format_integer(&ctx, &value, "formatFileSize", i64::MIN, i64::MAX)?))
      })?,
    )?;
  }
  {
    let host = host.clone();
    utils.set(
      "formatDuration",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<String> {
        Ok(host.format(FORMAT_DURATION, format_integer(&ctx, &value, "formatDuration", 0, i32::MAX as i64)?))
      })?,
    )?;
  }

  let plugin_error = globals.plugin_error.clone();
  let text = crate::api::tl::text::install_text(ctx)?;

  let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
  let shared: Object = factory.call((utils.clone(), plugin_error, text))?;

  globals.inu.set("utils", utils)?;
  Ok(shared)
}

#[cfg(test)]
struct UnavailableUtilsHost;

#[cfg(test)]
impl UtilsHost for UnavailableUtilsHost {
  fn format(&self, _: i32, _: i64) -> String {
    "unavailable".into()
  }
}

fn format_integer<'js>(ctx: &Ctx<'js>, value: &Value<'js>, what: &str, min: i64, max: i64) -> JsResult<i64> {
  let Some(value) = value.as_number() else {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: expected a safe integer"));
  };
  if !value.is_finite()
    || value.fract() != 0.0
    || value < min as f64
    || value > max as f64
    || value.abs() > 9_007_199_254_740_991.0
  {
    return PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: expected a safe integer"));
  }
  Ok(value as i64)
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

#[cfg(test)]
#[path = "utils_tests.rs"]
mod tests;
