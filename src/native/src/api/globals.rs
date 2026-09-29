use std::path::Path;
use std::rc::Rc;

use rquickjs::{ArrayBuffer, Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};

use crate::utils::qjs::{qjs_read_buffer_bytes, qjs_read_typed_bytes, qjs_write_typed_bytes};
use crate::{
  api::io::blob::{self, BlobState},
  sandbox::limits::ExternalMemory,
};

pub trait RandomHost {
  fn random_bytes(&self, out: &mut [u8]) -> bool;
}

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/globals.qbc"));

const REQUIRED_INTRINSICS: [&str; 8] =
  ["atob", "btoa", "DOMException", "performance", "queueMicrotask", "Proxy", "Reflect", "WeakRef"];

pub fn install_globals<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn RandomHost>,
  spill_dir: &Path,
  external: Rc<ExternalMemory>,
) -> JsResult<Rc<BlobState>> {
  let globals = ctx.globals();
  for name in REQUIRED_INTRINSICS {
    let present = globals.get::<_, Value>(name).is_ok_and(|value| !value.is_undefined());
    if !present {
      return Err(Exception::throw_message(
        ctx,
        &format!("sandbox globals: this context has no '{name}'; the engine needs Context::full"),
      ));
    }
  }

  let blobs = blob::install(ctx, spill_dir, external)?;

  crate::api::url::install_url(ctx)?;

  let natives = Object::new(ctx.clone())?;
  natives.set("cloneBlob", blob::make_clone_fn(ctx)?)?;

  natives.set(
    "encodeUtf8",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, input: String| TypedArray::<u8>::new(ctx, input.into_bytes()))?,
  )?;

  natives.set(
    "decodeUtf8",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, input: Value<'js>| decode_utf8(&ctx, input))?,
  )?;

  natives.set(
    "randomFill",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, array: Value<'js>| random_fill(&ctx, host.as_ref(), array))?,
  )?;

  let factory = crate::utils::qjs::qjs_load_prelude(ctx, PRELUDE)?;
  factory.call::<_, ()>((natives,))?;
  Ok(blobs)
}

fn decode_utf8(ctx: &Ctx<'_>, input: Value<'_>) -> JsResult<String> {
  if let Ok(bytes) = TypedArray::<u8>::from_value(input.clone()) {
    return qjs_read_typed_bytes(&bytes, |bytes| String::from_utf8_lossy(bytes).into_owned())
      .ok_or_else(|| Exception::throw_type(ctx, "TextDecoder: the array is detached"));
  }
  if let Some(buffer) = ArrayBuffer::from_value(input) {
    return qjs_read_buffer_bytes(&buffer, |bytes| String::from_utf8_lossy(bytes).into_owned())
      .ok_or_else(|| Exception::throw_type(ctx, "TextDecoder: the buffer is detached"));
  }
  Err(Exception::throw_type(ctx, "TextDecoder: expected a Uint8Array or an ArrayBuffer"))
}

fn random_fill<'js>(ctx: &Ctx<'js>, host: &dyn RandomHost, array: Value<'js>) -> JsResult<Value<'js>> {
  let Ok(typed) = TypedArray::<u8>::from_value(array.clone()) else {
    return Err(Exception::throw_type(ctx, "getRandomValues: expected a Uint8Array"));
  };

  let mut bytes = vec![0u8; typed.len()];
  if !host.random_bytes(&mut bytes) {
    return Err(Exception::throw_message(ctx, "getRandomValues: the host has no randomness to give"));
  }

  let copied = qjs_write_typed_bytes(&typed, |raw| {
    let fits = raw.len() == bytes.len();
    if fits {
      raw.copy_from_slice(&bytes);
    }
    fits
  });
  match copied {
    None => Err(Exception::throw_type(ctx, "getRandomValues: the array is detached")),
    Some(false) => Err(Exception::throw_type(ctx, "getRandomValues: the array was resized")),
    Some(true) => Ok(array),
  }
}

#[cfg(test)]
#[path = "globals_tests.rs"]
mod tests;
