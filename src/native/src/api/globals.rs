use std::path::Path;
use std::rc::Rc;

use rquickjs::{ArrayBuffer, Ctx, Exception, Function, Object, Result as JsResult, TypedArray, Value};

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

  let blobs = blob::install(ctx, spill_dir, external.clone())?;

  crate::api::url::install_url(ctx)?;

  let natives = Object::new(ctx.clone())?;
  natives.set("cloneBlob", blob::make_clone_fn(ctx)?)?;

  let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, input: String| TypedArray::<u8>::new(ctx, input.into_bytes()))?;
  natives.set("encodeUtf8", f)?;

  let f = Function::new(ctx.clone(), |ctx: Ctx<'js>, input: Value<'js>| decode_utf8(&ctx, input))?;
  natives.set("decodeUtf8", f)?;

  let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, array: Value<'js>| random_fill(&ctx, host.as_ref(), array))?;
  natives.set("randomFill", f)?;

  let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
  factory.call::<_, ()>((natives,))?;
  Ok(blobs)
}

fn decode_utf8(ctx: &Ctx<'_>, input: Value<'_>) -> JsResult<String> {
  if let Ok(bytes) = TypedArray::<u8>::from_value(input.clone()) {
    let Some(bytes) = bytes.as_bytes() else {
      return Err(Exception::throw_type(ctx, "TextDecoder: the array is detached"));
    };
    return Ok(String::from_utf8_lossy(bytes).into_owned());
  }
  if let Some(buffer) = ArrayBuffer::from_value(input) {
    let Some(bytes) = buffer.as_bytes() else {
      return Err(Exception::throw_type(ctx, "TextDecoder: the buffer is detached"));
    };
    return Ok(String::from_utf8_lossy(bytes).into_owned());
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

  let Some(raw) = typed.as_raw() else {
    return Err(Exception::throw_type(ctx, "getRandomValues: the array is detached"));
  };
  if raw.len != bytes.len() {
    return Err(Exception::throw_type(ctx, "getRandomValues: the array was resized"));
  }
  unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), raw.ptr.as_ptr(), raw.len) };
  Ok(array)
}

#[cfg(test)]
#[path = "globals_tests.rs"]
mod tests;
