use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{
  Array, Coerced, Context, Ctx, FromJs, Function, IntoJs, Object, Persistent, Result as JsResult, Runtime, TypedArray,
  Value,
};

use crate::api::error::{throw_internal, throw_quota_exceeded, wire_error_to_js, PluginErrorCode};
use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{CallbackRegistry, Lifecycle};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/jvm.qbc"));

pub trait JvmHost {
  fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String;
}

const OP_CLASS: i32 = 0;
const OP_NEW: i32 = 1;
const OP_GET: i32 = 2;
const OP_SET: i32 = 3;
const OP_CALL: i32 = 4;
const OP_METHOD: i32 = 5;
const OP_FIELD: i32 = 6;
const OP_INVOKE: i32 = 7;
const OP_MEMBER_GET: i32 = 8;
const OP_MEMBER_SET: i32 = 9;
const OP_RUNNABLE: i32 = 10;
const OP_LOAD_DEX: i32 = 11;
const OP_RELEASE: i32 = 12;
const OP_CURRENT_FRAGMENT: i32 = 13;
const OP_CURRENT_ACTIVITY: i32 = 14;

pub const GRANT: &str = "unsafe.jvm";

pub const VALUE_LIMIT_BYTES: usize = 1024 * 1024;

pub const DEX_LIMIT_BYTES: usize = 8 * 1024 * 1024;

pub struct JvmState {
  host: Rc<dyn JvmHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  callbacks: CallbackRegistry,
  prelude: RefCell<Option<Prelude>>,
}

struct Prelude {
  mint: Persistent<Function<'static>>,
  id_of: Persistent<Function<'static>>,
}

fn bounded(value: &str, limit: usize) -> bool {
  value.len() <= limit
}

fn throw_too_big<'js, T>(ctx: &Ctx<'js>, what: &str, size: usize, limit: usize) -> JsResult<T> {
  throw_quota_exceeded(
    ctx,
    &format!("jvm: {what} is {size} bytes, over the {limit} this bridge carries"),
    size as i64,
    limit as i64,
  )
}

pub(crate) fn handle_id<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, value: &Value<'js>) -> JsResult<i64> {
  let borrowed = state.prelude.borrow();
  let Some(prelude) = borrowed.as_ref() else {
    return Ok(-1);
  };
  let id_of = prelude.id_of.clone().restore(ctx)?;
  id_of.call((value.clone(),))
}

pub(crate) fn arg_to_wire<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, value: &Value<'js>) -> JsResult<String> {
  if value.is_null() || value.is_undefined() {
    return Ok("N".to_string());
  }
  if let Some(b) = value.as_bool() {
    return Ok(if b { "B1" } else { "B0" }.to_string());
  }
  if let Some(i) = value.as_int() {
    return Ok(format!("I{i}"));
  }
  if let Some(f) = value.as_float() {
    if f.fract() == 0.0 && f.abs() <= 9007199254740991.0 {
      return Ok(format!("I{}", f as i64));
    }
    return Ok(format!("D{f}"));
  }
  if value.is_big_int() {
    let text = Coerced::<String>::from_js(ctx, value.clone())?.0;
    return match text.parse::<i64>() {
      Ok(v) => Ok(format!("I{v}")),
      Err(_) => PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: {text} does not fit in a java long")),
    };
  }
  if let Some(s) = value.as_string() {
    let s = s.to_string()?;
    if !bounded(&s, VALUE_LIMIT_BYTES) {
      return throw_too_big(ctx, "a string argument", s.len(), VALUE_LIMIT_BYTES);
    }
    return Ok(format!("S{s}"));
  }
  if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
    if let Some(bytes) = typed.as_bytes() {
      if !bounded_bytes(bytes, VALUE_LIMIT_BYTES) {
        return throw_too_big(ctx, "a byte[] argument", bytes.len(), VALUE_LIMIT_BYTES);
      }
      return Ok(format!("Y{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes)));
    }
  }
  let id = handle_id(ctx, state, value)?;
  if id >= 0 {
    return Ok(format!("G{id}"));
  }
  PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: cannot hand a {} to java", value.type_of()))
}

fn bounded_bytes(bytes: &[u8], limit: usize) -> bool {
  bytes.len() <= limit
}

pub(crate) fn wire_to_value<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, wire: &str) -> JsResult<Value<'js>> {
  if let Some(built) = wire_error_to_js(ctx, wire) {
    return Err(ctx.throw(built?));
  }
  let mut chars = wire.chars();
  let Some(tag) = chars.next() else {
    return throw_internal(ctx, "jvm: the host answered with an empty wire");
  };
  let payload = chars.as_str();
  if tag == 'I' {
    let Ok(value) = payload.parse::<i64>() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the host answered with a bad int");
    };
    if value.unsigned_abs() > 9007199254740991 {
      return Value::new_big_int(ctx.clone(), value);
    }
    return value.into_js(ctx);
  }
  if tag == 'G' {
    let mut kind = payload.chars();
    let Some(kind) = kind.next() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the host answered with a bad handle");
    };
    let Ok(id) = payload[kind.len_utf8()..].parse::<i64>() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the host answered with a bad handle");
    };
    let borrowed = state.prelude.borrow();
    let Some(prelude) = borrowed.as_ref() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the prelude is not installed");
    };
    let mint = prelude.mint.clone().restore(ctx)?;
    return mint.call((kind.to_string(), id));
  }
  match crate::api::tl::proxy::scalar_wire_to_js(ctx, tag, payload) {
    Some(value) => value,
    None => PluginErrorCode::Internal.throw(ctx, &format!("jvm: the host answered with an unknown tag '{tag}'")),
  }
}

fn ask<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<JvmState>,
  op: i32,
  target: i64,
  name: &str,
  args: &[String],
) -> JsResult<Value<'js>> {
  let wire = state.host.jvm(op, target, name, args);
  wire_to_value(ctx, state, &wire)
}

fn js_op<'js>(
  ctx: &Ctx<'js>,
  state: &Rc<JvmState>,
  op: i32,
  target: i64,
  name: String,
  args: Array<'js>,
) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  let mut wires = Vec::new();
  for arg in crate::utils::arguments::array_values(ctx, &args, "jvm")? {
    wires.push(arg_to_wire(ctx, state, &arg)?);
  }
  ask(ctx, state, op, target, &name, &wires)
}

fn js_cls<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, name: String) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, Some(&name), MATCH_NAMESPACE)?;
  ask(ctx, state, OP_CLASS, 0, &name, &[])
}

fn js_runnable<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, callback: Function<'js>) -> JsResult<Value<'js>> {
  check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
  let token = state.callbacks.alloc();
  let handle = ask(ctx, state, OP_RUNNABLE, 0, "", &[format!("I{token}")])?;
  if !state.lifecycle.is_unloading() {
    state.callbacks.register(ctx, token, None, callback);
  }
  Ok(handle)
}

fn js_load_dex<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, source: Value<'js>) -> JsResult<()> {
  check_grant(ctx, &state.grants, GRANT, Some("*"), MATCH_NAMESPACE)?;
  if let Some(path) = source.as_string() {
    let path = path.to_string()?;
    ask(ctx, state, OP_LOAD_DEX, 0, &path, &[])?;
    return Ok(());
  }
  if let Ok(typed) = TypedArray::<u8>::from_value(source.clone()) {
    if let Some(bytes) = typed.as_bytes() {
      if !bounded_bytes(bytes, DEX_LIMIT_BYTES) {
        return throw_too_big(ctx, "a dex", bytes.len(), DEX_LIMIT_BYTES);
      }
      let wire = format!("Y{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes));
      ask(ctx, state, OP_LOAD_DEX, 0, "", &[wire])?;
      return Ok(());
    }
  }
  PluginErrorCode::InvalidArgument.throw(ctx, "loadDex: expected an absolute path or a Uint8Array")
}

pub fn install_jvm<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn JvmHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  inu: &Object<'js>,
) -> JsResult<Rc<JvmState>> {
  let state = Rc::new(JvmState {
    host,
    grants,
    lifecycle,
    log,
    callbacks: CallbackRegistry::default(),
    prelude: RefCell::new(None),
  });

  let natives = Object::new(ctx.clone())?;
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [
    ("construct", OP_NEW),
    ("get", OP_GET),
    ("set", OP_SET),
    ("call", OP_CALL),
    ("method", OP_METHOD),
    ("field", OP_FIELD),
    ("invoke", OP_INVOKE),
    ("memberGet", OP_MEMBER_GET),
    ("memberSet", OP_MEMBER_SET),
  ] {
    ops.set(name, op)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, op: i32, target: i64, name: String, args: Array<'js>| {
      js_op(&ctx, &state, op, target, name, args)
    })?;
    natives.set("op", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: String| js_cls(&ctx, &state, name))?;
    natives.set("cls", f)?;
  }
  {
    let state = state.clone();
    let f =
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Function<'js>| js_runnable(&ctx, &state, callback))?;
    natives.set("runnable", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| js_load_dex(&ctx, &state, source))?;
    natives.set("loadDex", f)?;
  }
  {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |target: i64| {
      state.host.jvm(OP_RELEASE, target, "", &[]);
    })?;
    natives.set("release", f)?;
  }

  let plugin_error: Value = inu.get("PluginError")?;

  let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
  let built: Object = factory.call((natives, plugin_error, ops))?;
  let jvm: Object = built.get("jvm")?;
  let mint: Function = built.get("mint")?;
  let id_of: Function = built.get("idOf")?;
  *state.prelude.borrow_mut() = Some(Prelude {
    mint: Persistent::save(ctx, mint),
    id_of: Persistent::save(ctx, id_of),
  });
  inu.set("jvm", jvm)?;
  install_android_screen(ctx, &state, inu)?;

  Ok(state)
}

fn install_android_screen<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, inu: &Object<'js>) -> JsResult<()> {
  let android: Object = match inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      inu.set("android", o.clone())?;
      o
    }
  };
  for (name, op) in [("getCurrentFragment", OP_CURRENT_FRAGMENT), ("getCurrentActivity", OP_CURRENT_ACTIVITY)] {
    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
      check_grant(&ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
      ask(&ctx, &state, op, 0, "", &[])
    })?;
    android.set(name, f)?;
  }
  Ok(())
}

pub fn dispatch_callback(rt: &Runtime, context: &Context, state: &Rc<JvmState>, callback_id: u32) {
  context.with(|ctx| {
    let Some(callback) = state.callbacks.restore(&ctx, callback_id) else {
      return;
    };
    match callback.call::<_, Value>(()) {
      Ok(_) => {}
      Err(rquickjs::Error::Exception) => {
        (state.log)(&crate::fault(format_args!("jvm.runnable callback threw: {}", format_exception(&ctx))));
      }
      Err(e) => (state.log)(&format!("jvm.runnable callback failed: {e:?}")),
    }
  });
  pump_jobs(rt, context, state.log.as_ref());
}

pub fn dispose(context: &Context, state: &Rc<JvmState>) {
  context.with(|ctx| {
    state.callbacks.release_all(&ctx);
    if let Some(prelude) = state.prelude.borrow_mut().take() {
      let _ = prelude.mint.restore(&ctx);
      let _ = prelude.id_of.restore(&ctx);
    }
  });
}

#[cfg(test)]
#[path = "jvm_tests.rs"]
pub(crate) mod tests;
