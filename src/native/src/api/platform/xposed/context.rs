use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::This;
use rquickjs::{Array, Class, Constructor, Ctx, Function, JsLifetime, Object, Result as JsResult, Value};

use super::{Invocation, JavaValues, Returned, FIRST_ARG_INDEX, KEEP_ARGUMENT};
use crate::api::error::PluginErrorCode;
use crate::api::platform::jvm::JvmState;
use crate::utils::arguments::array_values;
use crate::utils::shape::{define_accessor, define_method, get_class_prototype};

const METHOD_INDEX: usize = 0;
const THIS_INDEX: usize = 1;

pub(super) struct HookContext<'js> {
  jvm: Rc<JvmState>,
  invocation: RefCell<Rc<dyn JavaValues>>,
  arg_count: usize,
  live: Cell<bool>,
  method: RefCell<Option<Value<'js>>>,
  this_object: RefCell<Option<Value<'js>>>,
  args: RefCell<Option<Value<'js>>>,
  arg_originals: RefCell<Option<Vec<String>>>,
  returned: RefCell<Option<Returned>>,
  return_value: RefCell<Option<Value<'js>>>,
  throwable: RefCell<Option<Value<'js>>>,
  answered: Cell<bool>,
  answered_throwable: RefCell<Option<Value<'js>>>,
}

impl<'js> Trace<'js> for HookContext<'js> {
  fn trace<'a>(&self, tracer: Tracer<'a, 'js>) {
    for slot in
      [&self.method, &self.this_object, &self.args, &self.return_value, &self.throwable, &self.answered_throwable]
    {
      if let Ok(value) = slot.try_borrow() {
        value.trace(tracer);
      }
    }
  }
}

// SAFETY: every JavaScript-lifetime-bound field uses the struct's `'js` lifetime.
unsafe impl<'js> JsLifetime<'js> for HookContext<'js> {
  type Changed<'to> = HookContext<'to>;
}

impl<'js> JsClass<'js> for HookContext<'js> {
  const NAME: &'static str = "XposedHookContext";
  type Mutable = Readable;

  fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
    Ok(None)
  }
}

type Me<'js> = This<Class<'js, HookContext<'js>>>;

pub(super) fn create_hook_context<'js>(
  jvm: &Rc<JvmState>,
  call: &Invocation,
  proto: Object<'js>,
) -> JsResult<Class<'js, HookContext<'js>>> {
  Class::instance_proto(
    HookContext {
      jvm: jvm.clone(),
      invocation: RefCell::new(call.values.clone()),
      arg_count: call.args,
      live: Cell::new(true),
      method: RefCell::new(None),
      this_object: RefCell::new(None),
      args: RefCell::new(None),
      arg_originals: RefCell::new(None),
      returned: RefCell::new(None),
      return_value: RefCell::new(None),
      throwable: RefCell::new(None),
      answered: Cell::new(false),
      answered_throwable: RefCell::new(None),
    },
    proto,
  )
}

impl<'js> HookContext<'js> {
  /// the values are the caller's own, so they die with the call that lent them. What a phase read
  /// while it ran stays readable; anything it did not is gone.
  pub(super) fn expire(&self) {
    self.live.set(false);
  }

  /// the same call's later phase, with the values it was handed this time
  pub(super) fn revive(&self, call: &Invocation) {
    *self.invocation.borrow_mut() = call.values.clone();
    self.live.set(true);
  }

  fn read_value(&self, ctx: &Ctx<'js>, values: &Rc<dyn JavaValues>, index: usize) -> JsResult<Value<'js>> {
    if !self.live.get() {
      return PluginErrorCode::HandleExpired
        .throw(ctx, "xposed: a hook context reads the call it was given, and that call has returned");
    }
    values.read(ctx, index)
  }

  fn read_invocation(&self, ctx: &Ctx<'js>, slot: &RefCell<Option<Value<'js>>>, index: usize) -> JsResult<Value<'js>> {
    if let Some(value) = slot.borrow().clone() {
      return Ok(value);
    }
    let values = self.invocation.borrow().clone();
    let value = self.read_value(ctx, &values, index)?;
    *slot.borrow_mut() = Some(value.clone());
    Ok(value)
  }

  fn read_args(&self, ctx: &Ctx<'js>) -> JsResult<Value<'js>> {
    if let Some(value) = self.args.borrow().clone() {
      return Ok(value);
    }
    let array = Array::new(ctx.clone())?;
    let mut originals = Vec::with_capacity(self.arg_count);
    let values = self.invocation.borrow().clone();
    for index in 0..self.arg_count {
      let value = self.read_value(ctx, &values, FIRST_ARG_INDEX + index)?;
      originals.push(self.jvm.arg_to_wire(ctx, &value)?);
      array.set(index, value)?;
    }
    *self.arg_originals.borrow_mut() = Some(originals);
    let array = array.into_value();
    *self.args.borrow_mut() = Some(array.clone());
    Ok(array)
  }

  fn replace_args(&self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
    self.read_args(ctx)?;
    *self.args.borrow_mut() = Some(value);
    Ok(())
  }

  fn read_result(&self, ctx: &Ctx<'js>, slot: &RefCell<Option<Value<'js>>>, thrown: bool) -> JsResult<Value<'js>> {
    if let Some(value) = slot.borrow().clone() {
      return Ok(value);
    }
    let returned = self.returned.borrow().clone();
    let value = match returned {
      Some(returned) if returned.threw == thrown => self.read_value(ctx, &returned.values, returned.index)?,
      _ => Value::new_null(ctx.clone()),
    };
    *slot.borrow_mut() = Some(value.clone());
    Ok(value)
  }

  fn answer_with(&self, value: Value<'js>, throwable: Option<Value<'js>>) {
    *self.return_value.borrow_mut() = Some(value);
    *self.answered_throwable.borrow_mut() = throwable;
    self.answered.set(true);
  }

  fn clear_answer(&self) {
    self.answered.set(false);
    *self.answered_throwable.borrow_mut() = None;
  }

  pub(super) fn publish_answer(&self, ctx: &Ctx<'js>, wire: &str) -> JsResult<()> {
    self.clear_answer();
    *self.returned.borrow_mut() = None;
    let null = Value::new_null(ctx.clone());
    let (return_value, throwable) = match wire.strip_prefix('T') {
      Some(thrown) => (null, self.jvm.wire_to_value(ctx, thrown)?),
      None => (self.jvm.wire_to_value(ctx, wire)?, null),
    };
    *self.return_value.borrow_mut() = Some(return_value);
    *self.throwable.borrow_mut() = Some(throwable);
    Ok(())
  }

  pub(super) fn publish_returned(&self, returned: &Returned) {
    self.clear_answer();
    *self.returned.borrow_mut() = Some(returned.clone());
    *self.return_value.borrow_mut() = None;
    *self.throwable.borrow_mut() = None;
  }

  pub(super) fn get_answer(&self, ctx: &Ctx<'js>) -> JsResult<Option<String>> {
    if !self.answered.get() {
      return Ok(None);
    }
    let thrown = self.answered_throwable.borrow().clone();
    if let Some(thrown) = thrown.filter(|value| !value.is_null() && !value.is_undefined()) {
      return Ok(Some(format!("T{}", self.jvm.arg_to_wire(ctx, &thrown)?)));
    }
    let value = self.read_result(ctx, &self.return_value, false)?;
    Ok(Some(self.jvm.arg_to_wire(ctx, &value)?))
  }

  pub(super) fn get_call_args(&self, ctx: &Ctx<'js>) -> JsResult<Vec<String>> {
    let originals = self.arg_originals.borrow().clone();
    let Some(originals) = originals else {
      return Ok(vec![KEEP_ARGUMENT.to_string(); self.arg_count]);
    };
    let args = self.args.borrow().clone();
    let Some(array) = args.as_ref().and_then(|value| value.as_array()) else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "xposed: 'args' must be an array");
    };
    let mut wires = Vec::new();
    for (index, value) in array_values(ctx, array, "xposed: 'args'")?.iter().enumerate() {
      let wire = self.jvm.arg_to_wire(ctx, value)?;
      wires.push(if originals.get(index) == Some(&wire) { KEEP_ARGUMENT.to_string() } else { wire });
    }
    Ok(wires)
  }
}

/// the prototype comes back to be kept: looking it up through the class registry is a hash of the
/// class's type id, which a dispatch would pay on every call
pub(super) fn install_hook_context<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  let proto = get_class_prototype::<HookContext>(ctx)?;
  define_accessor(
    &proto,
    "method",
    |ctx: Ctx<'js>, this: Me<'js>| {
      let context = this.0.borrow();
      context.read_invocation(&ctx, &context.method, METHOD_INDEX)
    },
    |this: Me<'js>, value: Value<'js>| *this.0.borrow().method.borrow_mut() = Some(value),
  )?;
  define_accessor(
    &proto,
    "thisObject",
    |ctx: Ctx<'js>, this: Me<'js>| {
      let context = this.0.borrow();
      context.read_invocation(&ctx, &context.this_object, THIS_INDEX)
    },
    |this: Me<'js>, value: Value<'js>| *this.0.borrow().this_object.borrow_mut() = Some(value),
  )?;
  define_accessor(
    &proto,
    "args",
    |ctx: Ctx<'js>, this: Me<'js>| this.0.borrow().read_args(&ctx),
    |ctx: Ctx<'js>, this: Me<'js>, value: Value<'js>| this.0.borrow().replace_args(&ctx, value),
  )?;
  define_accessor(
    &proto,
    "returnValue",
    |ctx: Ctx<'js>, this: Me<'js>| {
      let context = this.0.borrow();
      context.read_result(&ctx, &context.return_value, false)
    },
    |this: Me<'js>, value: Value<'js>| *this.0.borrow().return_value.borrow_mut() = Some(value),
  )?;
  define_accessor(
    &proto,
    "throwable",
    |ctx: Ctx<'js>, this: Me<'js>| {
      let context = this.0.borrow();
      context.read_result(&ctx, &context.throwable, true)
    },
    |this: Me<'js>, value: Value<'js>| *this.0.borrow().throwable.borrow_mut() = Some(value),
  )?;
  define_method(
    &proto,
    "setReturnValue",
    Function::new(ctx.clone(), |this: Me<'js>, value: Value<'js>| this.0.borrow().answer_with(value, None))?,
  )?;
  define_method(
    &proto,
    "setThrowable",
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: Me<'js>, value: Value<'js>| {
      this.0.borrow().answer_with(Value::new_null(ctx), Some(value))
    })?,
  )?;
  Ok(proto)
}
