use crate::runtime::Dispose;
use std::cell::RefCell;
use std::rc::Rc;

use std::sync::Arc;

use rquickjs::function::Rest;
use rquickjs::{
  object::Filter, Array, Class, Context, Ctx, FromJs, Function, IntoJs, Object, Persistent, Result as JsResult,
  Runtime, TypedArray, Value,
};

use crate::api::error::{format_exception, report_callback_error, wire_error_to_js, PluginErrorCode};
use crate::api::tl::proxy::{encode_bytes_wire, TlViews, ViewLife};
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{CallbackRegistry, Lifecycle};
use crate::utils::arguments::array_values;
use crate::utils::prelude;

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/jvm.qbc"));

pub(crate) mod dex;
pub(crate) mod native;
pub(crate) mod refs;

pub use native::JvmReflectHost;
use native::{read_arg, Arg, HandleSpec, Native, Outcome};
pub(crate) use refs::RefTable;
use refs::{JvmRef, KIND_CLASS, KIND_CONSTRUCTOR, KIND_FIELD, KIND_METHOD};

/// The string-wire side of `inu.jvm`: what needs kotlin's loaders, dex, routines, and the
/// screen. Member access runs in [`native`] and never crosses as text.
pub trait JvmHost {
  fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String;
}

const HIDDEN_REF: &str = "__inuJvmRef";
const HANDLE_EXPIRED: &str = "jvm: that handle was released; a plugin's handles do not outlive it";

/// what a js value is a handle of: a `JvmRef` instance, or a class function carrying one
pub(crate) fn ref_of<'js>(value: &Value<'js>) -> Option<Class<'js, JvmRef>> {
  let object = value.as_object()?;
  if let Some(handle) = Class::<JvmRef>::from_object(object) {
    return Some(handle);
  }
  if !object.is_function() {
    return None;
  }
  let carried: Value = object.get(HIDDEN_REF).ok()?;
  carried.as_object().and_then(Class::<JvmRef>::from_object)
}

const OP_CLASS: i32 = 0;
const OP_RUNNABLE: i32 = 10;
const OP_LOAD_DEX: i32 = 11;
const OP_CURRENT_FRAGMENT: i32 = 13;
const OP_CURRENT_ACTIVITY: i32 = 14;
const OP_BUNDLE_METHOD: i32 = 15;
const OP_ROUTINE: i32 = 16;
const OP_XPOSED_ROUTINE: i32 = 17;
const OP_PREPARE_CLASS: i32 = 18;
const OP_LOAD_CLASS: i32 = 20;
const OP_CANCEL_CLASS: i32 = 21;
const OP_FROM_TL: i32 = 22;
const OP_TO_TL: i32 = 23;

pub const GRANT: &str = "unsafe.jvm";

pub const VALUE_LIMIT_BYTES: usize = 1024 * 1024;

pub const DEX_LIMIT_BYTES: usize = 8 * 1024 * 1024;

pub struct JvmState {
  views: Option<Rc<TlViews>>,
  host: Rc<dyn JvmHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  callbacks: CallbackRegistry,
  cleanup_callbacks: RefCell<std::collections::HashSet<u32>>,
  prelude: RefCell<Option<Prelude>>,
  refs: Arc<RefTable>,
  native: Option<Rc<Native>>,
}

struct Prelude {
  mint_class: Persistent<Function<'static>>,
  object_proto: Persistent<Object<'static>>,
  method_proto: Persistent<Object<'static>>,
  constructor_proto: Persistent<Object<'static>>,
  field_proto: Persistent<Object<'static>>,
  xposed_routine: Persistent<Function<'static>>,
}

pub(super) fn throw_too_big<'js, T>(ctx: &Ctx<'js>, what: &str, size: usize, limit: usize) -> JsResult<T> {
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
          self.copy_ref(ctx, id, handle.as_bytes()[0])
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
      match ref_of(&value) {
        Some(handle) => Ok(self.copy_wire(handle.borrow().id)),
        None => self.arg_to_wire(ctx, &value),
      }
    })();
    match result {
      Ok(wire) => wire,
      Err(rquickjs::Error::Exception) => format!("E{}", format_exception(ctx)),
      Err(error) => format!("EdefineClass: callback failed: {error}"),
    }
  }

  pub(crate) fn build_xposed_routine<'js>(
    &self,
    ctx: &Ctx<'js>,
    program: Value<'js>,
    captures: Value<'js>,
  ) -> JsResult<Value<'js>> {
    let factory = self
      .prelude
      .borrow()
      .as_ref()
      .ok_or(rquickjs::Error::Unknown)?
      .xposed_routine
      .clone()
      .restore(ctx)?;
    factory.call((program, captures))
  }

  /// member access runs through cached jni ids, and there is no text form of it to fall back to
  fn native<'js>(&self, ctx: &Ctx<'js>) -> JsResult<&Native> {
    match self.native.as_deref() {
      Some(native) => Ok(native),
      None => PluginErrorCode::Unsupported.throw(ctx, "jvm: member access needs a java vm"),
    }
  }

  pub(crate) fn handle_id<'js>(&self, _ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<i64> {
    Ok(ref_of(value).map(|handle| handle.borrow().id).unwrap_or(-1))
  }

  /// a second id for the reference behind `id`, for a handoff whose original the other side
  /// releases
  fn copy_wire(&self, id: i64) -> String {
    match self.refs.copy(id) {
      Some(copy) => format!("GO{copy}"),
      None => crate::api::tl::proxy::encode_error(HANDLE_EXPIRED),
    }
  }

  fn copy_ref<'js>(&self, ctx: &Ctx<'js>, id: i64, kind: u8) -> JsResult<Value<'js>> {
    match self.refs.copy(id) {
      Some(copy) => self.make_handle(
        ctx,
        HandleSpec {
          id: copy,
          kind,
          class_key: None,
          pinned: None,
        },
      ),
      None => PluginErrorCode::HandleExpired.throw(ctx, HANDLE_EXPIRED),
    }
  }

  /// the js object for a table entry: a class is a callable the prelude builds, the rest are
  /// `JvmRef` instances on the prototype their kind gets
  pub(crate) fn make_handle<'js>(&self, ctx: &Ctx<'js>, spec: HandleSpec) -> JsResult<Value<'js>> {
    let handle = JvmRef::new(self.refs.clone(), spec.id);
    handle.class_key.set(spec.class_key);
    *handle.pinned.borrow_mut() = spec.pinned;
    let borrowed = self.prelude.borrow();
    let Some(prelude) = borrowed.as_ref() else {
      return PluginErrorCode::Internal.throw(ctx, "jvm: the prelude is not installed");
    };
    let proto = match spec.kind {
      KIND_CLASS => {
        let instance = Class::instance(ctx.clone(), handle)?;
        let mint_class = prelude.mint_class.clone().restore(ctx)?;
        return mint_class.call((instance,));
      }
      KIND_METHOD => &prelude.method_proto,
      KIND_CONSTRUCTOR => &prelude.constructor_proto,
      KIND_FIELD => &prelude.field_proto,
      _ => &prelude.object_proto,
    };
    let proto = proto.clone().restore(ctx)?;
    Ok(Class::instance_proto(handle, proto)?.into_value())
  }

  pub(crate) fn element_to_value<'js>(
    &self,
    ctx: &Ctx<'js>,
    array: jni::sys::jobjectArray,
    index: usize,
  ) -> JsResult<Value<'js>> {
    let outcome = self.native(ctx)?.element_to_js(ctx, array, index)?;
    self.outcome_to_value(ctx, outcome)
  }

  fn outcome_to_value<'js>(&self, ctx: &Ctx<'js>, outcome: Outcome<'js>) -> JsResult<Value<'js>> {
    match outcome {
      Outcome::Value(value) => Ok(value),
      Outcome::Handle(spec) => self.make_handle(ctx, spec),
    }
  }

  fn handle_arg<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>, what: &str) -> JsResult<Class<'js, JvmRef>> {
    match ref_of(value) {
      Some(handle) => Ok(handle),
      None => {
        PluginErrorCode::InvalidArgument.throw(ctx, &format!("{what}: expected a java class, object, method or field"))
      }
    }
  }

  fn read_args<'js>(&self, ctx: &Ctx<'js>, values: &[Value<'js>]) -> JsResult<Vec<Arg<'js>>> {
    values.iter().map(|value| read_arg(ctx, value)).collect()
  }

  /// what every member operation starts with: the grant, the handle the name is looked up on, and
  /// the jni side that does the looking
  fn member_target<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Value<'js>,
    what: &str,
  ) -> JsResult<(&Native, Class<'js, JvmRef>)> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    let target = self.handle_arg(ctx, target, what)?;
    Ok((self.native(ctx)?, target))
  }

  pub(crate) fn js_call<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: Value<'js>,
    name: String,
    args: Rest<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    let (native, target) = self.member_target(ctx, &target, "call")?;
    let args = self.read_args(ctx, &args.0)?;
    let outcome = native.call(ctx, &target, &name, &args)?;
    self.outcome_to_value(ctx, outcome)
  }

  fn js_construct<'js>(&self, ctx: &Ctx<'js>, target: Value<'js>, args: Rest<Value<'js>>) -> JsResult<Value<'js>> {
    let (native, target) = self.member_target(ctx, &target, "new")?;
    let args = self.read_args(ctx, &args.0)?;
    let outcome = native.construct(ctx, &target, &args)?;
    self.outcome_to_value(ctx, outcome)
  }

  pub(crate) fn arg_to_wire<'js>(&self, ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<String> {
    Ok(match read_arg(ctx, value)? {
      Arg::Null => "N".to_string(),
      Arg::Bool(b) => if b { "B1" } else { "B0" }.to_string(),
      Arg::Int(i) => format!("I{i}"),
      Arg::Double(f) => format!("D{f}"),
      Arg::Str(s) => format!("S{s}"),
      Arg::Bytes(bytes) => encode_bytes_wire(&bytes),
      Arg::Ref(handle) => format!("G{}", handle.borrow().id),
    })
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
      return self.make_handle(
        ctx,
        HandleSpec {
          id,
          kind: kind as u8,
          class_key: None,
          pinned: None,
        },
      );
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

  fn js_cls<'js>(&self, ctx: &Ctx<'js>, name: String) -> JsResult<Value<'js>> {
    self.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
    self.ask(ctx, OP_CLASS, 0, &name, &[])
  }

  fn put_bundle_value<'js>(&self, ctx: &Ctx<'js>, bundle: &Value<'js>, key: &str, value: &Value<'js>) -> JsResult<()> {
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
      let Some(handle) = ref_of(value) else {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("bundle: '{key}' has unsupported type {}", value.type_of()));
      };
      let wire = self.host.jvm(OP_BUNDLE_METHOD, handle.borrow().id, "", &[]);
      let method = self.wire_to_value(ctx, &wire)?;
      let Some(method) = method.as_string() else {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("bundle: '{key}' is not a Bundle-compatible java object"));
      };
      let method = method.to_string()?;
      self.js_call(ctx, bundle.clone(), method, Rest(vec![key.into_js(ctx)?, value.clone()]))?;
      return Ok(());
    };
    self.js_call(ctx, bundle.clone(), method.to_string(), Rest(vec![key.into_js(ctx)?, value.clone()]))?;
    Ok(())
  }

  fn js_bundle<'js>(&self, ctx: &Ctx<'js>, values: Value<'js>) -> JsResult<Value<'js>> {
    let Some(values) = values.as_object() else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "bundle: expected an object");
    };
    if values.as_array().is_some() || ref_of(&values.clone().into_value()).is_some() {
      return PluginErrorCode::InvalidArgument.throw(ctx, "bundle: expected an object");
    }
    let class = self.js_cls(ctx, "android.os.Bundle".to_string())?;
    let bundle = self.js_construct(ctx, class, Rest(Vec::new()))?;
    for entry in values.own_props::<String, Value>(Filter::new().string().enum_only()) {
      let (key, value) = entry?;
      self.put_bundle_value(ctx, &bundle, &key, &value)?;
    }
    Ok(bundle)
  }
}

#[allow(clippy::too_many_arguments)]
pub fn install_jvm<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn JvmHost>,
  reflect: Option<Rc<dyn JvmReflectHost>>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  views: Option<Rc<TlViews>>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<JvmState>> {
  let refs = RefTable::new();
  let state = Rc::new(JvmState {
    views,
    host,
    grants,
    lifecycle,
    log,
    callbacks: CallbackRegistry::default(),
    cleanup_callbacks: RefCell::new(std::collections::HashSet::new()),
    prelude: RefCell::new(None),
    native: reflect.map(|reflect| Native::new(reflect, refs.clone())),
    refs,
  });

  let natives = Object::new(ctx.clone())?;
  {
    let state = state.clone();
    natives.set(
      "defineClass",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, definition: String, values: Array<'js>| {
        let this = &state;
        this.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
        if definition.len() > VALUE_LIMIT_BYTES {
          return throw_too_big(&ctx, "class definition", definition.len(), VALUE_LIMIT_BYTES);
        }
        let mut tokens = Vec::new();
        let result = (|| {
          let mut wires = Vec::new();
          let mut bytes = 0usize;
          for value in array_values(&ctx, &values, "defineClass")? {
            let is_handle = ref_of(&value).is_some();
            let wire = if let Some(callback) = value.as_function().filter(|_| !is_handle) {
              let token = this.callbacks.alloc();
              this.callbacks.register(&ctx, token, None, callback.clone());
              tokens.push(token);
              format!("I{token}")
            } else {
              this.arg_to_wire(&ctx, &value)?
            };
            bytes = bytes.saturating_add(wire.len());
            if bytes > VALUE_LIMIT_BYTES {
              return throw_too_big(&ctx, "class captures", bytes, VALUE_LIMIT_BYTES);
            }
            wires.push(wire);
          }
          let prepared = this.ask(&ctx, OP_PREPARE_CLASS, 0, &definition, &wires)?;
          let json = String::from_js(&ctx, prepared)?;
          let metadata = Object::from_js(&ctx, (&ctx).json_parse(json)?)?;
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
              Err(error) => return PluginErrorCode::InvalidArgument.throw(&ctx, &format!("defineClass: {error}")),
            };
            let wire = encode_bytes_wire(&bytes);
            this.ask(&ctx, OP_LOAD_CLASS, ticket, "", &[wire])
          })();
          if result.is_err() {
            this.host.jvm(OP_CANCEL_CLASS, ticket, "", &[]);
          }
          result
        })();
        if result.is_err() {
          for token in tokens {
            this.callbacks.dispose(&ctx, token);
          }
        }
        result
      })?,
    )?;
  }
  let ops = Object::new(ctx.clone())?;
  for (name, op) in [("routine", OP_ROUTINE), ("xposedRoutine", OP_XPOSED_ROUTINE)] {
    ops.set(name, op)?;
  }
  {
    let state = state.clone();
    natives.set(
      "op",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, op: i32, target: i64, name: String, args: Array<'js>| {
        let this = &state;
        this.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
        if op == OP_XPOSED_ROUTINE {
          this.grants.check_grant(&ctx, "unsafe.xposed", None, MATCH_NAMESPACE)?;
        }
        if name.len() > VALUE_LIMIT_BYTES {
          return throw_too_big(&ctx, "operation definition", name.len(), VALUE_LIMIT_BYTES);
        }
        let mut wires = Vec::new();
        let mut wire_bytes = 0usize;
        for arg in array_values(&ctx, &args, "jvm")? {
          let wire = this.arg_to_wire(&ctx, &arg)?;
          wire_bytes = wire_bytes.saturating_add(wire.len());
          if (op == OP_ROUTINE || op == OP_XPOSED_ROUTINE) && wire_bytes > VALUE_LIMIT_BYTES {
            return throw_too_big(&ctx, "routine captures", wire_bytes, VALUE_LIMIT_BYTES);
          }
          wires.push(wire);
        }
        this.ask(&ctx, op, target, &name, &wires)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "call",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, name: String, args: Rest<Value<'js>>| {
        state.js_call(&ctx, target, name, args)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "construct",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, args: Rest<Value<'js>>| {
        state.js_construct(&ctx, target, args)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "get",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, name: String| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "getField")?;
        let outcome = native.get(&ctx, &target, &name)?;
        this.outcome_to_value(&ctx, outcome)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "set",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, name: String, value: Value<'js>| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "setField")?;
        native.set(&ctx, &target, &name, &read_arg(&ctx, &value)?)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "method",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, name: String| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "getDeclaredMethod")?;
        let outcome = native.method(&ctx, &target, &name)?;
        this.outcome_to_value(&ctx, outcome)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "field",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, name: String| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "getDeclaredField")?;
        let outcome = native.field(&ctx, &target, &name)?;
        this.outcome_to_value(&ctx, outcome)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "invoke",
      Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, target: Value<'js>, receiver: Value<'js>, args: Rest<Value<'js>>| {
          let this = &state;
          let (native, target) = this.member_target(&ctx, &target, "invoke")?;
          let receiver = read_arg(&ctx, &receiver)?;
          let args = this.read_args(&ctx, &args.0)?;
          let outcome = native.invoke_pinned(&ctx, &target, &receiver, &args)?;
          this.outcome_to_value(&ctx, outcome)
        },
      )?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "memberGet",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, receiver: Value<'js>| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "get")?;
        let receiver = read_arg(&ctx, &receiver)?;
        let outcome = native.member_get(&ctx, &target, &receiver)?;
        this.outcome_to_value(&ctx, outcome)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "memberSet",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, receiver: Value<'js>, value: Value<'js>| {
        let this = &state;
        let (native, target) = this.member_target(&ctx, &target, "set")?;
        let receiver = read_arg(&ctx, &receiver)?;
        native.member_set(&ctx, &target, &receiver, &read_arg(&ctx, &value)?)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "isInstance",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, value: Value<'js>| {
        let this = &state;
        this.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
        let target = this.handle_arg(&ctx, &target, "isInstance")?;
        match read_arg(&ctx, &value)? {
          // java's own answer, and one the vm need not be asked for
          Arg::Null => Ok(false),
          value @ Arg::Ref(_) => this.native(&ctx)?.is_instance(&ctx, &target, &value),
          _ => {
            PluginErrorCode::InvalidArgument.throw(&ctx, "jvm: isInstance needs a java handle or null, not a scalar")
          }
        }
      })?,
    )?;
  }
  natives.set("isRef", Function::new(ctx.clone(), |value: Value<'js>| ref_of(&value).is_some())?)?;
  natives.set("hiddenRef", HIDDEN_REF)?;
  {
    let state = state.clone();
    natives.set("cls", Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: String| state.js_cls(&ctx, name))?)?;
  }
  {
    let state = state.clone();
    natives.set(
      "fromTl",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        let this = &state;
        let ctx: &Ctx<'js> = &ctx;
        this.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
        let wire = crate::api::tl::proxy::js_value_to_wire(ctx, value)?;
        this.ask(ctx, OP_FROM_TL, 0, &wire, &[])
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "toTl",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        let this = &state;
        this.grants.check_grant(&ctx, GRANT, None, MATCH_NAMESPACE)?;
        let handle = this.handle_arg(&ctx, &value, "toTl")?;
        let Some(views) = this.views.as_ref() else {
          return PluginErrorCode::Unsupported.throw(&ctx, "jvm: this build has no tl view table");
        };
        let id = handle.borrow().id;
        let wire = this.host.jvm(OP_TO_TL, id, "", &[]);
        if let Some(built) = wire_error_to_js(&ctx, &wire) {
          return Err((&ctx).throw(built?));
        }
        views.wire_to_js_value(&ctx, &wire, ViewLife::Plugin)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "runnable",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Function<'js>| -> JsResult<Value<'js>> {
        let this = &state;
        let ctx: &Ctx<'js> = &ctx;
        this.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
        let token = this.callbacks.alloc();
        let handle = this.ask(ctx, OP_RUNNABLE, 0, "", &[format!("I{token}")])?;
        if !this.lifecycle.is_unloading() || this.lifecycle.is_cleaning_up() {
          if this.lifecycle.is_cleaning_up() {
            this.cleanup_callbacks.borrow_mut().insert(token);
          }
          this.callbacks.register(ctx, token, None, callback);
        }
        Ok(handle)
      })?,
    )?;
  }
  {
    let state = state.clone();
    natives.set(
      "loadDex",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| {
        let this = &state;
        let ctx: &Ctx<'js> = &ctx;
        this.grants.check_grant(ctx, GRANT, None, MATCH_NAMESPACE)?;
        if let Some(path) = source.as_string() {
          let path = path.to_string()?;
          this.ask(ctx, OP_LOAD_DEX, 0, &path, &[])?;
          return Ok(());
        }
        if let Ok(typed) = TypedArray::<u8>::from_value(source.clone()) {
          if let Some(bytes) = typed.as_bytes() {
            if !bounded_bytes(bytes, DEX_LIMIT_BYTES) {
              return throw_too_big(ctx, "a dex", bytes.len(), DEX_LIMIT_BYTES);
            }
            let wire = encode_bytes_wire(&bytes);
            this.ask(ctx, OP_LOAD_DEX, 0, "", &[wire])?;
            return Ok(());
          }
        }
        PluginErrorCode::InvalidArgument.throw(ctx, "loadDex: expected an absolute path or a Uint8Array")
      })?,
    )?;
  }

  let plugin_error = globals.plugin_error.clone();

  let factory = prelude::load(ctx, PRELUDE)?;
  let built: Object = factory.call((natives, plugin_error, ops))?;
  let jvm: Object = built.get("jvm")?;
  let protos: Object = built.get("protos")?;
  *state.prelude.borrow_mut() = Some(Prelude {
    mint_class: Persistent::save(ctx, built.get::<_, Function>("mintClass")?),
    object_proto: Persistent::save(ctx, protos.get::<_, Object>("object")?),
    method_proto: Persistent::save(ctx, protos.get::<_, Object>("method")?),
    constructor_proto: Persistent::save(ctx, protos.get::<_, Object>("constructor")?),
    field_proto: Persistent::save(ctx, protos.get::<_, Object>("field")?),
    xposed_routine: Persistent::save(ctx, built.get::<_, Function>("xposedRoutine")?),
  });
  globals.inu.set("jvm", jvm)?;
  state.install_android(ctx, globals)?;

  Ok(state)
}

impl JvmState {
  fn install_android<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, globals: &crate::api::Globals<'js>) -> JsResult<()> {
    let android = globals.get_namespace(ctx, "android")?;
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
      if let Err(error) = callback.call::<_, Value>(()) {
        report_callback_error(&state.log, &ctx, "jvm.runnable callback", error);
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

  pub(crate) fn refs(&self) -> &Arc<RefTable> {
    &self.refs
  }
}

impl Dispose for JvmState {
  fn dispose(&self, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      state.callbacks.release_all(&ctx);
      if let Some(prelude) = state.prelude.borrow_mut().take() {
        let _ = prelude.mint_class.restore(&ctx);
        let _ = prelude.object_proto.restore(&ctx);
        let _ = prelude.method_proto.restore(&ctx);
        let _ = prelude.constructor_proto.restore(&ctx);
        let _ = prelude.field_proto.restore(&ctx);
        let _ = prelude.xposed_routine.restore(&ctx);
      }
    });
    state.refs.close();
  }
}

#[cfg(test)]
#[path = "jvm_tests.rs"]
pub(crate) mod tests;
