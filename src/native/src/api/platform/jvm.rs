use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{
  object::Filter, Array, Coerced, Context, Ctx, FromJs, Function, IntoJs, Object, Persistent, Result as JsResult,
  Runtime, TypedArray, Value,
};

use crate::api::error::{wire_error_to_js, PluginErrorCode};
use crate::api::telegram::rpc::{format_exception, pump_jobs};
use crate::sandbox::grants::{GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{CallbackRegistry, Lifecycle};
use crate::utils::arguments::array_values;
use crate::utils::prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/jvm.qbc"));

pub(crate) mod dex;

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
const OP_BUNDLE_METHOD: i32 = 15;
const OP_ROUTINE: i32 = 16;
const OP_XPOSED_ROUTINE: i32 = 17;
const OP_PREPARE_CLASS: i32 = 18;
const OP_COPY_REF: i32 = 19;
const OP_LOAD_CLASS: i32 = 20;
const OP_CANCEL_CLASS: i32 = 21;

pub const GRANT: &str = "unsafe.jvm";

pub const VALUE_LIMIT_BYTES: usize = 1024 * 1024;

pub const DEX_LIMIT_BYTES: usize = 8 * 1024 * 1024;

pub struct JvmState {
  host: Rc<dyn JvmHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  callbacks: CallbackRegistry,
  cleanup_callbacks: RefCell<std::collections::HashSet<u32>>,
  prelude: RefCell<Option<Prelude>>,
}

struct Prelude {
  mint: Persistent<Function<'static>>,
  id_of: Persistent<Function<'static>>,
  xposed_routine: Persistent<Function<'static>>,
}

fn bounded(value: &str, limit: usize) -> bool {
  value.len() <= limit
}

fn throw_too_big<'js, T>(ctx: &Ctx<'js>, what: &str, size: usize, limit: usize) -> JsResult<T> {
  {
    let message: &str = &format!("jvm: {what} is {size} bytes, over the {limit} this bridge carries");
    let usage = size as i64;
    let quota = limit as i64;
    PluginErrorCode::QuotaExceeded(usage, quota).throw(ctx, message)
  }
}

impl JvmState {
  pub(crate) fn dispatch_method<'js>(
    &self,
    ctx: &Ctx<'js>,
    callback_id: u32,
    self_wire: &str,
    args: &[String],
  ) -> String {
    let result = (|| {
      let Some(callback) = self.callbacks.restore(ctx, callback_id) else {
        return PluginErrorCode::HandleExpired.throw(ctx, "defineClass: callback has expired");
      };
      let read_argument = |wire: &str| {
        if let Some(handle) = wire.strip_prefix('G') {
          let id = handle.get(1..).and_then(|id| id.parse::<i64>().ok()).ok_or(rquickjs::Error::Unknown)?;
          self.ask(ctx, OP_COPY_REF, id, "", &[])
        } else {
          self.wire_to_value(ctx, wire)
        }
      };
      let mut call_args = rquickjs::function::Args::new(ctx.clone(), args.len() + 1);
      call_args.push_arg(read_argument(self_wire)?)?;
      for wire in args {
        call_args.push_arg(read_argument(wire)?)?;
      }
      let value: Value = callback.call_arg(call_args)?;
      if value.is_promise() {
        return PluginErrorCode::InvalidArgument.throw(ctx, "defineClass: method bodies must be synchronous");
      }
      let id = self.handle_id(ctx, &value)?;
      if id >= 0 {
        Ok(self.host.jvm(OP_COPY_REF, id, "", &[]))
      } else {
        self.arg_to_wire(ctx, &value)
      }
    })();
    match result {
      Ok(wire) => wire,
      Err(rquickjs::Error::Exception) => format!("E{}", format_exception(ctx)),
      Err(error) => format!("EdefineClass: callback failed: {error}"),
    }
  }

  fn js_define_class<'js>(&self, ctx: &Ctx<'js>, definition: String, values: Array<'js>) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, Some("*"), MATCH_NAMESPACE)?;
    if definition.len() > VALUE_LIMIT_BYTES {
      return throw_too_big(ctx, "class definition", definition.len(), VALUE_LIMIT_BYTES);
    }
    let mut tokens = Vec::new();
    let result = (|| {
      let mut wires = Vec::new();
      let mut bytes = 0usize;
      for value in array_values(ctx, &values, "defineClass")? {
        let is_handle = self.handle_id(ctx, &value)? >= 0;
        let wire = if let Some(callback) = value.as_function().filter(|_| !is_handle) {
          let token = self.callbacks.alloc();
          self.callbacks.register(ctx, token, None, callback.clone());
          tokens.push(token);
          format!("I{token}")
        } else {
          self.arg_to_wire(ctx, &value)?
        };
        bytes = bytes.saturating_add(wire.len());
        if bytes > VALUE_LIMIT_BYTES {
          return throw_too_big(ctx, "class captures", bytes, VALUE_LIMIT_BYTES);
        }
        wires.push(wire);
      }
      let prepared = self.ask(ctx, OP_PREPARE_CLASS, 0, &definition, &wires)?;
      let json = String::from_js(ctx, prepared)?;
      let metadata = Object::from_js(ctx, ctx.json_parse(json)?)?;
      let ticket: String = metadata.get("ticket")?;
      let ticket = ticket.parse::<i64>().map_err(|_| rquickjs::Error::Unknown)?;
      let result = (|| {
        let name: String = metadata.get("name")?;
        let superclass: String = metadata.get("superclass")?;
        let interfaces: Vec<String> = metadata.get("interfaces")?;
        let fields: Vec<Vec<String>> = metadata.get("fields")?;
        let methods: Vec<Vec<String>> = metadata.get("methods")?;
        let bytes = match dex::build(&name, &superclass, &interfaces, &fields, &methods) {
          Ok(bytes) => bytes,
          Err(error) => return PluginErrorCode::InvalidArgument.throw(ctx, &format!("defineClass: {error}")),
        };
        let wire = format!("Y{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes));
        self.ask(ctx, OP_LOAD_CLASS, ticket, "", &[wire])
      })();
      if result.is_err() {
        self.host.jvm(OP_CANCEL_CLASS, ticket, "", &[]);
      }
      result
    })();
    if result.is_err() {
      for token in tokens {
        self.callbacks.dispose(ctx, token);
      }
    }
    result
  }

  pub(crate) fn build_xposed_routine<'js>(&self, ctx: &Ctx<'js>, builder: Value<'js>) -> JsResult<Value<'js>> {
    let factory = self
      .prelude
      .borrow()
      .as_ref()
      .ok_or(rquickjs::Error::Unknown)?
      .xposed_routine
      .clone()
      .restore(ctx)?;
    factory.call((builder,))
  }

  pub(crate) fn handle_id<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<i64> {
    let borrowed = self.prelude.borrow();
    let Some(prelude) = borrowed.as_ref() else {
      return Ok(-1);
    };
    let id_of = prelude.id_of.clone().restore(ctx)?;
    id_of.call((value.clone(),))
  }

  pub(crate) fn arg_to_wire<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<String> {
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
      if f.fract() == 0.0 && f.abs() <= 9_007_199_254_740_991.0 {
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
    let id = self.handle_id(ctx, value)?;
    if id >= 0 {
      return Ok(format!("G{id}"));
    }
    PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: cannot hand a {} to java", value.type_of()))
  }
}

fn bounded_bytes(bytes: &[u8], limit: usize) -> bool {
  bytes.len() <= limit
}

impl JvmState {
  pub(crate) fn wire_to_value<'js>(&self, ctx: &Ctx<'js>, wire: &str) -> JsResult<Value<'js>> {
    if let Some(built) = wire_error_to_js(ctx, wire) {
      return Err(ctx.throw(built?));
    }
    let mut chars = wire.chars();
    let Some(tag) = chars.next() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the host answered with an empty wire");
    };
    let payload = chars.as_str();
    if tag == 'I' {
      let Ok(value) = payload.parse::<i64>() else {
        return PluginErrorCode::Internal.throw(ctx, "jvm: the host answered with a bad int");
      };
      if value.unsigned_abs() > 9_007_199_254_740_991 {
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
      let borrowed = self.prelude.borrow();
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

  fn ask<'js>(&self, ctx: &Ctx<'js>, op: i32, target: i64, name: &str, args: &[String]) -> JsResult<Value<'js>> {
    let wire = self.host.jvm(op, target, name, args);
    self.wire_to_value(ctx, &wire)
  }

  fn js_op<'js>(&self, ctx: &Ctx<'js>, op: i32, target: i64, name: String, args: Array<'js>) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    if op == OP_XPOSED_ROUTINE {
      self.grants.check_grant(ctx, "unsafe.xposed", None, MATCH_NAMESPACE)?;
    }
    if name.len() > VALUE_LIMIT_BYTES {
      return throw_too_big(ctx, "operation definition", name.len(), VALUE_LIMIT_BYTES);
    }
    let mut wires = Vec::new();
    let mut wire_bytes = 0usize;
    for arg in array_values(ctx, &args, "jvm")? {
      let wire = self.arg_to_wire(ctx, &arg)?;
      wire_bytes = wire_bytes.saturating_add(wire.len());
      if (op == OP_ROUTINE || op == OP_XPOSED_ROUTINE) && wire_bytes > VALUE_LIMIT_BYTES {
        return throw_too_big(ctx, "routine captures", wire_bytes, VALUE_LIMIT_BYTES);
      }
      wires.push(wire);
    }
    self.ask(ctx, op, target, &name, &wires)
  }

  fn js_cls<'js>(&self, ctx: &Ctx<'js>, name: String) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, Some(&name), MATCH_NAMESPACE)?;
    self.ask(ctx, OP_CLASS, 0, &name, &[])
  }

  fn js_runnable<'js>(&self, ctx: &Ctx<'js>, callback: Function<'js>) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    let token = self.callbacks.alloc();
    let handle = self.ask(ctx, OP_RUNNABLE, 0, "", &[format!("I{token}")])?;
    if !self.lifecycle.is_unloading() || self.lifecycle.is_cleaning_up() {
      if self.lifecycle.is_cleaning_up() {
        self.cleanup_callbacks.borrow_mut().insert(token);
      }
      self.callbacks.register(ctx, token, None, callback);
    }
    Ok(handle)
  }

  fn js_load_dex<'js>(&self, ctx: &Ctx<'js>, source: Value<'js>) -> JsResult<()> {
    self.grants.check_grant(ctx, GRANT, Some("*"), MATCH_NAMESPACE)?;
    if let Some(path) = source.as_string() {
      let path = path.to_string()?;
      self.ask(ctx, OP_LOAD_DEX, 0, &path, &[])?;
      return Ok(());
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(source.clone()) {
      if let Some(bytes) = typed.as_bytes() {
        if !bounded_bytes(bytes, DEX_LIMIT_BYTES) {
          return throw_too_big(ctx, "a dex", bytes.len(), DEX_LIMIT_BYTES);
        }
        let wire = format!("Y{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes));
        self.ask(ctx, OP_LOAD_DEX, 0, "", &[wire])?;
        return Ok(());
      }
    }
    PluginErrorCode::InvalidArgument.throw(ctx, "loadDex: expected an absolute path or a Uint8Array")
  }

  fn put_bundle_value<'js>(&self, ctx: &Ctx<'js>, bundle_id: i64, key: &str, value: &Value<'js>) -> JsResult<()> {
    let method = if value.as_bool().is_some() {
      "putBoolean"
    } else if value.is_big_int() {
      "putLong"
    } else if value.as_int().is_some() {
      "putInt"
    } else if let Some(number) = value.as_float() {
      if !number.is_finite() {
        return PluginErrorCode::InvalidArgument.throw(ctx, &format!("bundle: '{key}' must be finite"));
      }
      if number.fract() == 0.0 && number.abs() <= 9_007_199_254_740_991.0 {
        "putLong"
      } else {
        "putDouble"
      }
    } else if value.as_string().is_some() {
      "putString"
    } else if TypedArray::<u8>::from_value(value.clone()).is_ok() {
      "putByteArray"
    } else {
      let handle = self.handle_id(ctx, value)?;
      if handle < 0 {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("bundle: '{key}' has unsupported type {}", value.type_of()));
      }
      let key_value = key.into_js(ctx)?;
      let key_wire = self.arg_to_wire(ctx, &key_value)?;
      let value_wire = format!("G{handle}");
      let wire = self.host.jvm(OP_BUNDLE_METHOD, handle, "", &[]);
      let method = self.wire_to_value(ctx, &wire)?;
      let Some(method) = method.as_string() else {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("bundle: '{key}' is not a Bundle-compatible java object"));
      };
      self.ask(ctx, OP_CALL, bundle_id, &method.to_string()?, &[key_wire, value_wire])?;
      return Ok(());
    };
    let key_value = key.into_js(ctx)?;
    let key_wire = self.arg_to_wire(ctx, &key_value)?;
    let value_wire = self.arg_to_wire(ctx, value)?;
    self.ask(ctx, OP_CALL, bundle_id, method, &[key_wire, value_wire])?;
    Ok(())
  }

  fn js_bundle<'js>(&self, ctx: &Ctx<'js>, values: Value<'js>) -> JsResult<Value<'js>> {
    let Some(values) = values.as_object() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "bundle: expected an object");
    };
    if values.as_array().is_some() || self.handle_id(ctx, &values.clone().into_value())? >= 0 {
      return PluginErrorCode::InvalidArgument.throw(ctx, "bundle: expected an object");
    }
    let class = self.js_cls(ctx, "android.os.Bundle".to_string())?;
    let class_id = self.handle_id(ctx, &class)?;
    let bundle = self.ask(ctx, OP_NEW, class_id, "", &[])?;
    let bundle_id = self.handle_id(ctx, &bundle)?;
    for entry in values.own_props::<String, Value>(Filter::new().string().enum_only()) {
      let (key, value) = entry?;
      self.put_bundle_value(ctx, bundle_id, &key, &value)?;
    }
    Ok(bundle)
  }
}

pub fn install_jvm<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn JvmHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<JvmState>> {
  let state = Rc::new(JvmState {
    host,
    grants,
    lifecycle,
    log,
    callbacks: CallbackRegistry::default(),
    cleanup_callbacks: RefCell::new(std::collections::HashSet::new()),
    prelude: RefCell::new(None),
  });

  let natives = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    natives.set(
      "defineClass",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, definition: String, values: Array<'js>| {
        state.js_define_class(&ctx, definition, values)
      })?,
    )?;
  }
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [
    ("routine", OP_ROUTINE),
    ("xposedRoutine", OP_XPOSED_ROUTINE),
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
    natives.set(
      "op",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, op: i32, target: i64, name: String, args: Array<'js>| {
        state.js_op(&ctx, op, target, name, args)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set("cls", Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: String| state.js_cls(&ctx, name))?)?;
  }
  {
    let state = state.clone();
    natives.set(
      "runnable",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Function<'js>| state.js_runnable(&ctx, callback))?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "loadDex",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| state.js_load_dex(&ctx, source))?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "release",
      Function::new(ctx.clone(), move |target: i64| {
        state.host.jvm(OP_RELEASE, target, "", &[]);
      })?,
    )?;
  }

  let plugin_error = globals.plugin_error.clone();

  let factory = prelude::load(ctx, PRELUDE)?;
  let built: Object = factory.call((natives, plugin_error, ops))?;
  let jvm: Object = built.get("jvm")?;
  let mint: Function = built.get("mint")?;
  let id_of: Function = built.get("idOf")?;
  *state.prelude.borrow_mut() = Some(Prelude {
    mint: Persistent::save(ctx, mint),
    id_of: Persistent::save(ctx, id_of),
    xposed_routine: Persistent::save(ctx, built.get::<_, Function>("xposedRoutine")?),
  });
  globals.inu.set("jvm", jvm)?;
  state.install_android(ctx, globals)?;

  Ok(state)
}

impl JvmState {
  fn install_android<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
    let android: Object = match globals.inu.get::<_, Object>("android") {
      Ok(o) => o,
      Err(_) => {
        let o = Object::new(ctx.clone())?;
        globals.inu.set("android", o.clone())?;
        o
      }
    };
    let state = self.clone();
    android.set(
      "bundle",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, values: Value<'js>| state.js_bundle(&ctx, values))?,
    )?;
    for (name, op) in [("getCurrentFragment", OP_CURRENT_FRAGMENT), ("getCurrentActivity", OP_CURRENT_ACTIVITY)] {
      let state = self.clone();
      android.set(
        name,
        Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
          state.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
          state.ask(&ctx, op, 0, "", &[])
        })?,
      )?;
    }
    Ok(())
  }
}

impl JvmState {
  pub(crate) fn accepts_cleanup_callback(&self, callback_id: u32) -> bool {
    self.lifecycle.is_cleaning_up() && self.cleanup_callbacks.borrow().contains(&callback_id)
  }

  pub fn dispatch_callback(self: &Rc<Self>, rt: &Runtime, context: &Context, callback_id: u32) {
    let state = self;
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

  pub fn dispose(self: &Rc<Self>, context: &Context) {
    let state = self;
    context.with(|ctx| {
      state.callbacks.release_all(&ctx);
      if let Some(prelude) = state.prelude.borrow_mut().take() {
        let _ = prelude.mint.restore(&ctx);
        let _ = prelude.id_of.restore(&ctx);
        let _ = prelude.xposed_routine.restore(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "jvm_tests.rs"]
pub(crate) mod tests;
