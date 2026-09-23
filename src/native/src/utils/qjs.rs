use rquickjs::module::Evaluated;
use rquickjs::{qjs, ArrayBuffer, Atom, Ctx, Function, Module, Object, Promise, Result as JsResult, TypedArray, Value};

pub(crate) fn qjs_load_prelude<'js>(ctx: &Ctx<'js>, bytecode: &[u8]) -> JsResult<Function<'js>> {
  // SAFETY: build.rs produces the bytecode with this linked QuickJS build and explicitly sets its byte order.
  let module = unsafe { Module::load(ctx.clone(), bytecode) }?;
  let (module, _): (Module<Evaluated>, _) = module.eval()?;
  module.get("default")
}

pub(crate) fn qjs_symbol_dispose_atom<'js>(ctx: &Ctx<'js>) -> JsResult<Atom<'js>> {
  // from_atom_val is private, and dispose is not in the "predefined" table T_T

  // SAFETY: a static atom of this runtime, turned into a value this scope then owns
  let symbol = unsafe {
    let raw = qjs::JS_AtomToValue(ctx.as_raw().as_ptr(), qjs::JS_ATOM_Symbol_dispose as qjs::JSAtom);
    Value::from_raw(ctx.clone(), raw)
  };
  Atom::from_value(ctx.clone(), &symbol)
}

pub(crate) fn qjs_object_freeze(object: &Object<'_>) -> JsResult<()> {
  // SAFETY: `JS_FreezeObject` only borrows the object (`JSValueConst`)
  if unsafe { qjs::JS_FreezeObject(object.ctx().as_raw().as_ptr(), object.as_raw()) } < 0 {
    return Err(rquickjs::Error::Exception);
  }
  Ok(())
}

pub(crate) fn qjs_is_regexp(value: &Object<'_>) -> bool {
  // SAFETY: `JS_IsRegExp` reads the tag of a value it only borrows
  unsafe { qjs::JS_IsRegExp(value.as_raw()) }
}

/// `promise.then(ok, err)`, without reading a `then` the plugin may have replaced
pub(crate) fn qjs_promise_then<'js>(promise: &Promise<'js>, ok: &Function<'js>, err: &Function<'js>) -> JsResult<()> {
  let ctx = promise.ctx();
  // SAFETY: `JS_PromiseThen` only borrows its arguments (`JSValueConst`); the promise it returns
  // is owned here and freed when the value drops
  let chained = unsafe {
    Value::from_raw(
      ctx.clone(),
      qjs::JS_PromiseThen(ctx.as_raw().as_ptr(), promise.as_raw(), ok.as_raw(), err.as_raw()),
    )
  };
  if chained.is_exception() {
    return Err(rquickjs::Error::Exception);
  }
  Ok(())
}

/// `None` when the array is detached. `read` must not run javascript: a plugin could detach or
/// resize the buffer under the slice. A gc it triggers frees only what nothing references, and
/// `array` is referenced.
pub(crate) fn qjs_read_typed_bytes<R>(array: &TypedArray<'_, u8>, read: impl FnOnce(&[u8]) -> R) -> Option<R> {
  // SAFETY: the slice lives only for `read`, which runs no javascript
  unsafe { array.as_bytes() }.map(read)
}

/// [`qjs_read_typed_bytes`] for a whole `ArrayBuffer`, under the same rule for `read`
pub(crate) fn qjs_read_buffer_bytes<R>(buffer: &ArrayBuffer<'_>, read: impl FnOnce(&[u8]) -> R) -> Option<R> {
  // SAFETY: the slice lives only for `read`, which runs no javascript
  unsafe { buffer.as_bytes() }.map(read)
}

/// [`qjs_read_typed_bytes`], writable
pub(crate) fn qjs_write_typed_bytes<R>(array: &TypedArray<'_, u8>, write: impl FnOnce(&mut [u8]) -> R) -> Option<R> {
  let raw = array.as_raw()?;
  // SAFETY: `as_raw` hands out a live, non-detached buffer, and the slice lives only for `write`,
  // which runs no javascript
  Some(write(unsafe { &mut *raw.as_ptr() }))
}
