//! Calls JVM members using cached `jmethodID`s, `jvalue` arguments, and one JNI invocation.
//!
//! Kotlin's `PluginJvm.jvmResolve` resolves names once per class and name. Rust caches that plan
//! and handles overload selection, conversion, invocation, and result wrapping without string
//! encoding.
//!
//! Keep conversions aligned with `PluginJvm.convert`, which handles routines, `defineClass` bodies,
//! and Xposed results on Java threads. `jvm-test.js` and `PluginJvmTest` check that they agree.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, OnceLock};

use jni::objects::{
  Global, JByteArray, JClass, JFieldID, JMethodID, JObject, JObjectArray, JStaticFieldID, JStaticMethodID, JString,
  JThrowable, JValue, JValueOwned,
};
use jni::signature::{JavaType, MethodSignature, Primitive, RuntimeMethodSignature};
use jni::strings::JNIString;
use jni::sys::jvalue;
use jni::Env;
use rquickjs::{Class, Coerced, Ctx, FromJs, IntoJs, Result as JsResult, TypedArray, Value};

use super::refs::{Entry, JvmRef, RefTable, KIND_CLASS, KIND_CONSTRUCTOR, KIND_FIELD, KIND_METHOD, KIND_OBJECT};
use super::VALUE_LIMIT_BYTES;
use crate::api::error::{wire_error_to_js, PluginErrorCode};
use crate::jni::env::with_current_env;

pub(crate) const RESOLVE_METHODS: i32 = 0;
pub(crate) const RESOLVE_CONSTRUCTORS: i32 = 1;
pub(crate) const RESOLVE_FIELD: i32 = 2;
pub(crate) const RESOLVE_MEMBER: i32 = 3;

/// member, parameter classes, descriptor, static, abstract, refusal: what `PluginJvm.methodsAnswer` lays out
const CANDIDATE_WIDTH: usize = 6;

/// the kotlin side of resolution, reached through the bridge; `None` in a harness without a vm
pub trait JvmReflectHost {
  /// `PluginJvm.jvmResolve`: an `Object[]` whose layout `Native::read_plan` reads
  fn jvm_resolve<'l>(
    &self,
    env: &mut Env<'l>,
    target: &JObject,
    name: &str,
    mode: i32,
  ) -> Result<JObjectArray<'l, JObject<'l>>, String>;
}

pub(crate) enum OpError {
  Js(rquickjs::Error),
  Jni(jni::errors::Error),
}

impl From<rquickjs::Error> for OpError {
  fn from(e: rquickjs::Error) -> Self {
    OpError::Js(e)
  }
}

impl From<jni::errors::Error> for OpError {
  fn from(e: jni::errors::Error) -> Self {
    OpError::Jni(e)
  }
}

type OpResult<T> = Result<T, OpError>;

fn throw<T>(ctx: &Ctx<'_>, code: PluginErrorCode<'_>, message: &str) -> OpResult<T> {
  Err(OpError::Js(code.throw::<()>(ctx, message).unwrap_err()))
}

fn throw_wire<T>(ctx: &Ctx<'_>, wire: &str) -> OpResult<T> {
  match wire_error_to_js(ctx, wire) {
    Some(built) => Err(OpError::Js(ctx.throw(built?))),
    None => throw(ctx, PluginErrorCode::Internal, &format!("jvm: unreadable refusal: {wire}")),
  }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum BoxKind {
  Bool,
  Byte,
  Char,
  Short,
  Int,
  Long,
  Float,
  Double,
}

pub(crate) struct RefParam {
  cls: Global<JClass<'static>>,
  exact_box: Option<BoxKind>,
  accepts_string: bool,
  accepts_bytes: bool,
  accepts_boolean: bool,
  accepts_integer: bool,
  accepts_long: bool,
  accepts_double: bool,
}

pub(crate) enum ParamKind {
  Bool,
  Byte,
  Char,
  Short,
  Int,
  Long,
  Float,
  Double,
  Ref(RefParam),
}

impl ParamKind {
  fn numeric_rank(&self) -> Option<u8> {
    match self {
      ParamKind::Byte => Some(0),
      ParamKind::Short | ParamKind::Char => Some(1),
      ParamKind::Int => Some(2),
      ParamKind::Long => Some(3),
      ParamKind::Float => Some(4),
      ParamKind::Double => Some(5),
      _ => None,
    }
  }

  fn is_primitive(&self) -> bool {
    !matches!(self, ParamKind::Ref(_))
  }

  fn java_type(&self) -> JavaType {
    match self {
      ParamKind::Bool => JavaType::Primitive(Primitive::Boolean),
      ParamKind::Byte => JavaType::Primitive(Primitive::Byte),
      ParamKind::Char => JavaType::Primitive(Primitive::Char),
      ParamKind::Short => JavaType::Primitive(Primitive::Short),
      ParamKind::Int => JavaType::Primitive(Primitive::Int),
      ParamKind::Long => JavaType::Primitive(Primitive::Long),
      ParamKind::Float => JavaType::Primitive(Primitive::Float),
      ParamKind::Double => JavaType::Primitive(Primitive::Double),
      ParamKind::Ref(_) => JavaType::Object,
    }
  }
}

#[derive(Clone, Copy)]
pub(crate) enum RetKind {
  Void,
  Bool,
  Byte,
  Char,
  Short,
  Int,
  Long,
  Float,
  Double,
  Object,
}

impl RetKind {
  fn java_type(self) -> JavaType {
    match self {
      RetKind::Void => JavaType::Primitive(Primitive::Void),
      RetKind::Bool => JavaType::Primitive(Primitive::Boolean),
      RetKind::Byte => JavaType::Primitive(Primitive::Byte),
      RetKind::Char => JavaType::Primitive(Primitive::Char),
      RetKind::Short => JavaType::Primitive(Primitive::Short),
      RetKind::Int => JavaType::Primitive(Primitive::Int),
      RetKind::Long => JavaType::Primitive(Primitive::Long),
      RetKind::Float => JavaType::Primitive(Primitive::Float),
      RetKind::Double => JavaType::Primitive(Primitive::Double),
      RetKind::Object => JavaType::Object,
    }
  }
}

#[derive(Clone, Copy)]
enum Dispatch {
  Virtual,
  Nonvirtual,
}

enum MethodId {
  Instance(JMethodID),
  Static(JStaticMethodID),
  Constructor(JMethodID),
}

pub(crate) struct Candidate {
  /// the `Method`/`Constructor` object itself: what a pinned handle is minted from
  pub(crate) member: Global<JObject<'static>>,
  id: MethodId,
  owner: Global<JClass<'static>>,
  params: Vec<ParamKind>,
  ret: RetKind,
  descriptor: String,
  is_abstract: bool,
  refusal: Option<String>,
  /// static members and constructors initialize the class on first use, as `Method.invoke` does
  initialized: Cell<bool>,
}

impl Candidate {
  fn is_static(&self) -> bool {
    matches!(self.id, MethodId::Static(_))
  }

  fn is_constructor(&self) -> bool {
    matches!(self.id, MethodId::Constructor(_))
  }
}

enum FieldId {
  Instance(JFieldID),
  Static(JStaticFieldID),
}

pub(crate) struct FieldPlan {
  pub(crate) field: Global<JObject<'static>>,
  id: FieldId,
  owner: Global<JClass<'static>>,
  owner_name: String,
  ty: ParamKind,
  name: String,
  type_name: String,
  is_final: bool,
  refusal: Option<String>,
  initialized: Cell<bool>,
}

pub(crate) enum Pinned {
  Method(Rc<Candidate>),
  Field(Rc<FieldPlan>),
}

struct Plan {
  class_name: String,
  candidates: Vec<Rc<Candidate>>,
  /// `pick` decides on the shape of the arguments, never their values, so a shape seen once is settled
  picks: RefCell<HashMap<PickKey, Rc<Candidate>>>,
}

/// everything `matches` looks at in an argument: an int by which widths hold it, a double by
/// whether a float does, a string by whether it is one char, a handle by its runtime class
#[derive(PartialEq, Eq, Hash, Clone, Copy)]
enum ArgShape {
  Null,
  Bool,
  Int(u8),
  Double(bool),
  Str(bool),
  Bytes,
  Ref(u8, usize),
}

#[derive(PartialEq, Eq, Hash)]
struct PickKey {
  static_only: bool,
  shapes: Vec<ArgShape>,
}

/// how an instance of a class crosses: settled once per class, so a result costs one class lookup
#[derive(Clone, Copy)]
enum Shape {
  String,
  Boxed(BoxKind),
  Bytes,
  Class,
  Method,
  Constructor,
  Field,
  Object,
}

struct ClassInfo {
  cls: Global<JClass<'static>>,
  shape: Option<Shape>,
  methods: HashMap<String, Result<Rc<Plan>, String>>,
  constructors: HashMap<String, Result<Rc<Plan>, String>>,
  fields: HashMap<String, Result<Rc<FieldPlan>, String>>,
}

/// the handful of platform classes and members every call may need, looked up once per process
struct WellKnown {
  string: Global<JClass<'static>>,
  class: Global<JClass<'static>>,
  method: Global<JClass<'static>>,
  constructor: Global<JClass<'static>>,
  field: Global<JClass<'static>>,
  byte_array: Global<JClass<'static>>,
  boxes: [(BoxKind, Global<JClass<'static>>, JStaticMethodID, JMethodID); 8],
  to_string: JMethodID,
  get_declaring_class: JMethodID,
  system: Global<JClass<'static>>,
  identity_hash: JStaticMethodID,
  class_for_name: JStaticMethodID,
  class_get_name: JMethodID,
  class_get_class_loader: JMethodID,
}

static WELL_KNOWN: OnceLock<Option<WellKnown>> = OnceLock::new();

fn signature(text: &str) -> jni::errors::Result<RuntimeMethodSignature> {
  RuntimeMethodSignature::from_str(text)
}

fn find(env: &mut Env, name: &str) -> jni::errors::Result<Global<JClass<'static>>> {
  let cls = env.find_class(JNIString::from(name))?;
  env.new_global_ref(&cls)
}

fn method_id(env: &mut Env, cls: &Global<JClass<'static>>, name: &str, sig: &str) -> jni::errors::Result<JMethodID> {
  let parsed = signature(sig)?;
  env.get_method_id(cls, JNIString::from(name), MethodSignature::from(&parsed))
}

fn static_method_id(
  env: &mut Env,
  cls: &Global<JClass<'static>>,
  name: &str,
  sig: &str,
) -> jni::errors::Result<JStaticMethodID> {
  let parsed = signature(sig)?;
  env.get_static_method_id(cls, JNIString::from(name), MethodSignature::from(&parsed))
}

/// Reads IDs from reflected members without initializing their declaring class. Unlike
/// `GetMethodID`, this preserves `Class.getDeclaredMethod` behavior and does not run static
/// initializers.
fn reflected_method_id(env: &mut Env, member: &JObject) -> jni::errors::Result<jni::sys::jmethodID> {
  let raw = env.get_raw();
  let id = unsafe { ((**raw).v1_2.FromReflectedMethod)(raw, member.as_raw()) };
  if id.is_null() {
    return Err(jni::errors::Error::NullPtr("FromReflectedMethod"));
  }
  Ok(id)
}

fn reflected_field_id(env: &mut Env, field: &JObject) -> OpResult<jni::sys::jfieldID> {
  let raw = env.get_raw();
  let id = unsafe { ((**raw).v1_2.FromReflectedField)(raw, field.as_raw()) };
  if id.is_null() {
    return Err(OpError::Jni(jni::errors::Error::NullPtr("FromReflectedField")));
  }
  Ok(id)
}

impl WellKnown {
  fn load(env: &mut Env) -> jni::errors::Result<Self> {
    let boxed = |env: &mut Env, kind: BoxKind, name: &str, prim: &str, unbox: &str| {
      let cls = find(env, name)?;
      let value_of = static_method_id(env, &cls, "valueOf", &format!("({prim})L{name};"))?;
      let unbox = method_id(env, &cls, unbox, &format!("(){prim}"))?;
      Ok::<_, jni::errors::Error>((kind, cls, value_of, unbox))
    };
    let object = find(env, "java/lang/Object")?;
    let member = find(env, "java/lang/reflect/Member")?;
    let system = find(env, "java/lang/System")?;
    let class = find(env, "java/lang/Class")?;
    Ok(Self {
      string: find(env, "java/lang/String")?,
      class_for_name: static_method_id(
        env,
        &class,
        "forName",
        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
      )?,
      class_get_name: method_id(env, &class, "getName", "()Ljava/lang/String;")?,
      class_get_class_loader: method_id(env, &class, "getClassLoader", "()Ljava/lang/ClassLoader;")?,
      class,
      method: find(env, "java/lang/reflect/Method")?,
      constructor: find(env, "java/lang/reflect/Constructor")?,
      field: find(env, "java/lang/reflect/Field")?,
      byte_array: find(env, "[B")?,
      boxes: [
        boxed(env, BoxKind::Bool, "java/lang/Boolean", "Z", "booleanValue")?,
        boxed(env, BoxKind::Byte, "java/lang/Byte", "B", "byteValue")?,
        boxed(env, BoxKind::Char, "java/lang/Character", "C", "charValue")?,
        boxed(env, BoxKind::Short, "java/lang/Short", "S", "shortValue")?,
        boxed(env, BoxKind::Int, "java/lang/Integer", "I", "intValue")?,
        boxed(env, BoxKind::Long, "java/lang/Long", "J", "longValue")?,
        boxed(env, BoxKind::Float, "java/lang/Float", "F", "floatValue")?,
        boxed(env, BoxKind::Double, "java/lang/Double", "D", "doubleValue")?,
      ],
      to_string: method_id(env, &object, "toString", "()Ljava/lang/String;")?,
      get_declaring_class: method_id(env, &member, "getDeclaringClass", "()Ljava/lang/Class;")?,
      identity_hash: static_method_id(env, &system, "identityHashCode", "(Ljava/lang/Object;)I")?,
      system,
    })
  }

  fn get(env: &mut Env) -> Option<&'static Self> {
    WELL_KNOWN
      .get_or_init(|| match Self::load(env) {
        Ok(known) => Some(known),
        // a failed lookup leaves its throw pending, and the next jni call on this thread would abort on it
        Err(_) => {
          crate::jni::env::clear_exception(env);
          None
        }
      })
      .as_ref()
  }

  fn boxed(&self, kind: BoxKind) -> &(BoxKind, Global<JClass<'static>>, JStaticMethodID, JMethodID) {
    self.boxes.iter().find(|entry| entry.0 == kind).expect("every box kind is loaded")
  }
}

fn box_of(descriptor: &str) -> Option<BoxKind> {
  Some(match descriptor.as_bytes().first()? {
    b'Z' => BoxKind::Bool,
    b'B' => BoxKind::Byte,
    b'C' => BoxKind::Char,
    b'S' => BoxKind::Short,
    b'I' => BoxKind::Int,
    b'J' => BoxKind::Long,
    b'F' => BoxKind::Float,
    b'D' => BoxKind::Double,
    _ => return None,
  })
}

fn refuse_call(env: &mut Env, message: &str) -> jni::errors::Error {
  if let Err(error) = env.throw_new(jni::jni_str!("java/lang/IllegalArgumentException"), JNIString::from(message)) {
    return error;
  }
  jni::errors::Error::JavaException
}

/// `PluginJvm.Native.nativeCallNonvirtual`: runs exactly [method] on [receiver], the way a routine's
/// `callSuper` needs it. The arguments are already converted to [params] on the kotlin side, but a
/// mismatch here is memory corruption rather than an exception, so each one is checked again.
pub(crate) fn call_nonvirtual_boxed<'l>(
  env: &mut Env<'l>,
  method: &JObject,
  descriptor: &str,
  params: &JObjectArray<JObject>,
  receiver: &JObject,
  args: &JObjectArray<JObject>,
) -> jni::errors::Result<JObject<'l>> {
  let Some(known) = WellKnown::get(env) else {
    return Err(refuse_call(env, "jvm: the platform classes did not load"));
  };
  let owner = unsafe { env.call_method_unchecked(method, known.get_declaring_class, JavaType::Object, &[])? }.l()?;
  let owner = unsafe { JClass::from_raw(env, owner.into_raw() as jni::sys::jclass) };
  if receiver.is_null() || !env.is_instance_of(receiver, &owner)? {
    return Err(refuse_call(env, "jvm: that receiver is not an instance of the class declaring the method"));
  }
  let (param_descriptors, ret_descriptor) = Native::split_descriptor(descriptor);
  if param_descriptors.len() != params.len(env)? || param_descriptors.len() != args.len(env)? {
    return Err(refuse_call(env, "jvm: the arguments do not match the method"));
  }
  let mut values = Vec::with_capacity(param_descriptors.len());
  for (index, param_descriptor) in param_descriptors.iter().enumerate() {
    let arg = args.get_element(env, index)?;
    match box_of(param_descriptor) {
      Some(kind) => {
        if arg.is_null() || !env.is_instance_of(&arg, &known.boxed(kind).1)? {
          return Err(refuse_call(env, "jvm: a primitive parameter was handed something else"));
        }
        values.push(Native::unbox(env, known, kind, &arg)?.as_jni());
      }
      None => {
        let param = params.get_element(env, index)?;
        let param = unsafe { JClass::from_raw(env, param.into_raw() as jni::sys::jclass) };
        if !arg.is_null() && !env.is_instance_of(&arg, &param)? {
          return Err(refuse_call(env, "jvm: an argument does not match its parameter"));
        }
        values.push(JValue::Object(&arg).as_jni());
      }
    }
  }
  let ret = Native::ret_kind(&ret_descriptor);
  let id = unsafe { JMethodID::from_raw(reflected_method_id(env, method)?) };
  let value = unsafe { env.call_nonvirtual_method_unchecked(receiver, &owner, id, ret.java_type(), &values)? };
  let kind = match value {
    JValueOwned::Object(obj) => return Ok(obj),
    JValueOwned::Void => return Ok(JObject::null()),
    JValueOwned::Bool(_) => BoxKind::Bool,
    JValueOwned::Byte(_) => BoxKind::Byte,
    JValueOwned::Char(_) => BoxKind::Char,
    JValueOwned::Short(_) => BoxKind::Short,
    JValueOwned::Int(_) => BoxKind::Int,
    JValueOwned::Long(_) => BoxKind::Long,
    JValueOwned::Float(_) => BoxKind::Float,
    JValueOwned::Double(_) => BoxKind::Double,
  };
  Native::boxed_value(env, known, kind, value.borrow())
}

/// what a js argument is before any java type is known: the same reading `arg_to_wire` gives
pub(crate) enum Arg<'js> {
  Null,
  Bool(bool),
  Int(i64),
  Double(f64),
  Str(String),
  Bytes(Vec<u8>),
  Ref(Class<'js, JvmRef>),
}

pub(crate) fn read_arg<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Arg<'js>> {
  if value.is_null() || value.is_undefined() {
    return Ok(Arg::Null);
  }
  if let Some(b) = value.as_bool() {
    return Ok(Arg::Bool(b));
  }
  if let Some(i) = value.as_int() {
    return Ok(Arg::Int(i as i64));
  }
  if let Some(f) = value.as_float() {
    if f.fract() == 0.0 && f.abs() <= 9_007_199_254_740_991.0 {
      return Ok(Arg::Int(f as i64));
    }
    return Ok(Arg::Double(f));
  }
  if value.is_big_int() {
    let text = Coerced::<String>::from_js(ctx, value.clone())?.0;
    return match text.parse::<i64>() {
      Ok(v) => Ok(Arg::Int(v)),
      Err(_) => PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: {text} does not fit in a java long")),
    };
  }
  if let Some(s) = value.as_string() {
    let s = s.to_string()?;
    if s.len() > VALUE_LIMIT_BYTES {
      return too_big(ctx, "a string argument", s.len());
    }
    return Ok(Arg::Str(s));
  }
  if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
    // SAFETY: no javascript runs while the slice is borrowed; the error past the limit is built after
    // its last use
    if let Some(bytes) = unsafe { typed.as_bytes() } {
      if bytes.len() > VALUE_LIMIT_BYTES {
        return too_big(ctx, "a byte[] argument", bytes.len());
      }
      return Ok(Arg::Bytes(bytes.to_vec()));
    }
  }
  if let Some(handle) = super::ref_of(value) {
    return Ok(Arg::Ref(handle));
  }
  PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: cannot hand a {} to java", value.type_of()))
}

fn too_big<T>(ctx: &Ctx<'_>, what: &str, size: usize) -> JsResult<T> {
  super::throw_too_big(ctx, what, size, VALUE_LIMIT_BYTES)
}

/// a minted result, for `JvmState::make_handle` to wrap
pub(crate) struct HandleSpec {
  pub(crate) id: i64,
  pub(crate) kind: u8,
  pub(crate) class_key: Option<usize>,
  pub(crate) pinned: Option<Rc<Pinned>>,
}

pub(crate) enum Outcome<'js> {
  Value(Value<'js>),
  Handle(HandleSpec),
}

/// per engine: the class cache and the reference table are both the plugin's own
pub(crate) struct Native {
  host: Rc<dyn JvmReflectHost>,
  refs: Arc<RefTable>,
  classes: RefCell<Vec<ClassInfo>>,
  by_hash: RefCell<HashMap<i32, Vec<usize>>>,
}

impl Native {
  pub(crate) fn new(host: Rc<dyn JvmReflectHost>, refs: Arc<RefTable>) -> Rc<Self> {
    Rc::new(Self {
      host,
      refs,
      classes: RefCell::new(Vec::new()),
      by_hash: RefCell::new(HashMap::new()),
    })
  }

  fn with_env<'js, T>(
    &self,
    ctx: &Ctx<'js>,
    f: impl FnOnce(&mut Env, &'static WellKnown) -> OpResult<T>,
  ) -> JsResult<T> {
    let ran = with_current_env(|env| {
      let Some(known) = WellKnown::get(env) else {
        return throw(ctx, PluginErrorCode::Internal, "jvm: the platform classes did not load");
      };
      env.with_local_frame(32, |env| f(env, known))
    });
    match ran {
      Some(Ok(value)) => Ok(value),
      Some(Err(OpError::Js(e))) => Err(e),
      Some(Err(OpError::Jni(e))) => PluginErrorCode::Internal.throw(ctx, &format!("jvm: jni failed: {e}")),
      None => PluginErrorCode::Internal.throw(ctx, "jvm: no jni environment on this thread"),
    }
  }

  fn entry_of(&self, ctx: &Ctx<'_>, handle: &Class<'_, JvmRef>) -> OpResult<Entry> {
    let id = handle.borrow().id;
    match self.refs.get(id) {
      Some(entry) if !self.refs.is_closed() => Ok(entry),
      _ => throw(
        ctx,
        PluginErrorCode::HandleExpired,
        "jvm: that handle was released; a plugin's handles do not outlive it",
      ),
    }
  }

  /// a global reference the table holds from here on, or the refusal every expired mint gets
  fn mint_ref(&self, ctx: &Ctx<'_>, obj: Global<JObject<'static>>, kind: u8) -> OpResult<i64> {
    match self.refs.mint(obj, kind) {
      Some(id) => Ok(id),
      None => throw(ctx, PluginErrorCode::HandleExpired, "jvm: this plugin's handles have been released"),
    }
  }

  /// the class a `getDeclared*` is asked on: the handle must name one, not be an instance of one
  fn class_target(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    target: &Class<'_, JvmRef>,
  ) -> OpResult<usize> {
    let entry = self.entry_of(ctx, target)?;
    if entry.kind != KIND_CLASS {
      return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a class");
    }
    self.class_key_of(ctx, env, known, target, &entry)
  }

  /// the field a pinned handle stands for, and the receiver it was handed
  fn pinned_field(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    target: &Class<'_, JvmRef>,
    receiver: &Arg<'_>,
  ) -> OpResult<(Rc<FieldPlan>, Option<Entry>)> {
    let entry = self.entry_of(ctx, target)?;
    if entry.kind != KIND_FIELD {
      return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a field");
    }
    let pinned = self.pinned_of(ctx, env, known, target, &entry)?;
    let Pinned::Field(plan) = &*pinned else {
      return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a field");
    };
    let plan = plan.clone();
    let receiver = self.receiver_arg(ctx, env, receiver, Self::field_owner(&plan))?;
    Ok((plan, receiver))
  }

  fn key_of(&self, env: &mut Env, known: &WellKnown, cls: &JObject) -> OpResult<usize> {
    let hash = unsafe {
      env.call_static_method_unchecked(
        &known.system,
        known.identity_hash,
        JavaType::Primitive(Primitive::Int),
        &[JValue::Object(cls).as_jni()],
      )?
    }
    .i()?;
    if let Some(bucket) = self.by_hash.borrow().get(&hash) {
      for &key in bucket {
        let same = env.is_same_object(self.classes.borrow()[key].cls.as_obj(), cls)?;
        if same {
          return Ok(key);
        }
      }
    }
    let view = unsafe { JClass::from_raw(env, cls.as_raw()) };
    let global = env.new_global_ref(&view)?;
    let mut classes = self.classes.borrow_mut();
    let key = classes.len();
    classes.push(ClassInfo {
      cls: global,
      shape: None,
      methods: HashMap::new(),
      constructors: HashMap::new(),
      fields: HashMap::new(),
    });
    self.by_hash.borrow_mut().entry(hash).or_default().push(key);
    Ok(key)
  }

  /// a second global ref to the same class, so the cache borrow does not have to outlive the call
  fn class_of(&self, env: &mut Env, key: usize) -> OpResult<Global<JClass<'static>>> {
    let classes = self.classes.borrow();
    Ok(env.new_global_ref(&*classes[key].cls)?)
  }

  /// the class a handle is asked about: the class it names, or the runtime class of its object
  fn class_key_of(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    handle: &Class<'_, JvmRef>,
    entry: &Entry,
  ) -> OpResult<usize> {
    if let Some(key) = handle.borrow().class_key.get() {
      return Ok(key);
    }
    let key = if entry.kind == KIND_CLASS {
      self.key_of(env, known, entry.obj.as_obj())?
    } else {
      let cls = env.get_object_class(entry.obj.as_obj())?;
      self.key_of(env, known, cls.as_ref())?
    };
    let _ = ctx;
    handle.borrow().class_key.set(Some(key));
    Ok(key)
  }

  fn shape_of(&self, env: &mut Env, known: &WellKnown, key: usize) -> OpResult<Shape> {
    if let Some(shape) = self.classes.borrow()[key].shape {
      return Ok(shape);
    }
    let cls = self.class_of(env, key)?;
    let mut shape = Shape::Object;
    if env.is_assignable_from(&cls, &known.string)? {
      shape = Shape::String;
    } else if env.is_assignable_from(&cls, &known.byte_array)? {
      shape = Shape::Bytes;
    } else if env.is_assignable_from(&cls, &known.class)? {
      shape = Shape::Class;
    } else if env.is_assignable_from(&cls, &known.method)? {
      shape = Shape::Method;
    } else if env.is_assignable_from(&cls, &known.constructor)? {
      shape = Shape::Constructor;
    } else if env.is_assignable_from(&cls, &known.field)? {
      shape = Shape::Field;
    } else {
      for (kind, box_cls, _, _) in &known.boxes {
        if env.is_assignable_from(&cls, box_cls)? {
          shape = Shape::Boxed(*kind);
          break;
        }
      }
    }
    self.classes.borrow_mut()[key].shape = Some(shape);
    Ok(shape)
  }

  fn read_string(env: &mut Env, obj: &JObject) -> OpResult<Option<String>> {
    if obj.is_null() {
      return Ok(None);
    }
    let text = unsafe { JString::from_raw(env, obj.as_raw()) };
    Ok(Some(text.try_to_string(env)?))
  }

  fn string_at(env: &mut Env, array: &JObjectArray<JObject>, at: usize) -> OpResult<Option<String>> {
    let element = array.get_element(env, at)?;
    Self::read_string(env, &element)
  }

  fn bool_at(env: &mut Env, known: &WellKnown, array: &JObjectArray<JObject>, at: usize) -> OpResult<bool> {
    let element = array.get_element(env, at)?;
    Self::read_bool(env, known, &element)
  }

  fn read_bool(env: &mut Env, known: &WellKnown, obj: &JObject) -> OpResult<bool> {
    let unbox = known.boxed(BoxKind::Bool).3;
    Ok(unsafe { env.call_method_unchecked(obj, unbox, JavaType::Primitive(Primitive::Boolean), &[])? }.z()?)
  }

  fn class_name(env: &mut Env, known: &WellKnown, cls: &JObject) -> OpResult<String> {
    let name = unsafe { env.call_method_unchecked(cls, known.class_get_name, JavaType::Object, &[])? }.l()?;
    Ok(Self::read_string(env, &name)?.unwrap_or_default())
  }

  fn as_class(env: &mut Env, obj: &JObject) -> OpResult<Global<JClass<'static>>> {
    let view = unsafe { JClass::from_raw(env, obj.as_raw()) };
    Ok(env.new_global_ref(&view)?)
  }

  fn declaring_class(env: &mut Env, known: &WellKnown, member: &JObject) -> OpResult<Global<JClass<'static>>> {
    let cls = unsafe { env.call_method_unchecked(member, known.get_declaring_class, JavaType::Object, &[])? }.l()?;
    Self::as_class(env, &cls)
  }

  fn param_kind(env: &mut Env, known: &WellKnown, descriptor: &str, cls: &JObject) -> OpResult<ParamKind> {
    Ok(match descriptor.as_bytes()[0] {
      b'Z' => ParamKind::Bool,
      b'B' => ParamKind::Byte,
      b'C' => ParamKind::Char,
      b'S' => ParamKind::Short,
      b'I' => ParamKind::Int,
      b'J' => ParamKind::Long,
      b'F' => ParamKind::Float,
      b'D' => ParamKind::Double,
      _ => {
        let global = Self::as_class(env, cls)?;
        let assignable = |env: &mut Env, from: &Global<JClass<'static>>| env.is_assignable_from(from, &global);
        let mut exact_box = None;
        for (kind, box_cls, _, _) in &known.boxes {
          if env.is_same_object(box_cls, &global)? {
            exact_box = Some(*kind);
          }
        }
        let accepts_string = assignable(env, &known.string)?;
        let accepts_bytes = assignable(env, &known.byte_array)?;
        let accepts_boolean = assignable(env, &known.boxed(BoxKind::Bool).1)?;
        let accepts_integer = assignable(env, &known.boxed(BoxKind::Int).1)?;
        let accepts_long = assignable(env, &known.boxed(BoxKind::Long).1)?;
        let accepts_double = assignable(env, &known.boxed(BoxKind::Double).1)?;
        ParamKind::Ref(RefParam {
          cls: global,
          exact_box,
          accepts_string,
          accepts_bytes,
          accepts_boolean,
          accepts_integer,
          accepts_long,
          accepts_double,
        })
      }
    })
  }

  /// the parameter descriptors of `(...)R`, one string each, and the return descriptor
  fn split_descriptor(descriptor: &str) -> (Vec<String>, String) {
    let inner = descriptor.strip_prefix('(').unwrap_or(descriptor);
    let (params, ret) = inner.split_once(')').unwrap_or((inner, "V"));
    let bytes = params.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
      let start = i;
      while i < bytes.len() && bytes[i] == b'[' {
        i += 1;
      }
      if i < bytes.len() && bytes[i] == b'L' {
        while i < bytes.len() && bytes[i] != b';' {
          i += 1;
        }
      }
      // a truncated descriptor must not index past the end, and a class name may hold any java
      // identifier char, so the cut has to land on a boundary: this crate aborts on panic
      i = (i + 1).min(bytes.len());
      while i < bytes.len() && !params.is_char_boundary(i) {
        i += 1;
      }
      out.push(params[start..i].to_string());
    }
    (out, ret.to_string())
  }

  fn ret_kind(descriptor: &str) -> RetKind {
    match descriptor.as_bytes().first().copied().unwrap_or(b'V') {
      b'V' => RetKind::Void,
      b'Z' => RetKind::Bool,
      b'B' => RetKind::Byte,
      b'C' => RetKind::Char,
      b'S' => RetKind::Short,
      b'I' => RetKind::Int,
      b'J' => RetKind::Long,
      b'F' => RetKind::Float,
      b'D' => RetKind::Double,
      _ => RetKind::Object,
    }
  }

  fn read_candidate(
    env: &mut Env,
    known: &WellKnown,
    array: &JObjectArray<JObject>,
    at: usize,
  ) -> OpResult<Rc<Candidate>> {
    let member = array.get_element(env, at)?;
    let params = array.get_element(env, at + 1)?;
    let descriptor = Self::string_at(env, array, at + 2)?.unwrap_or_default();
    let is_static = Self::bool_at(env, known, array, at + 3)?;
    let is_abstract = Self::bool_at(env, known, array, at + 4)?;
    let refusal = Self::string_at(env, array, at + 5)?;
    let is_constructor = env.is_instance_of(&member, &known.constructor)?;
    let (param_descriptors, ret_descriptor) = Self::split_descriptor(&descriptor);
    let params_array = unsafe { JObjectArray::<JObject>::from_raw(env, params.as_raw() as jni::sys::jobjectArray) };
    let mut kinds = Vec::with_capacity(param_descriptors.len());
    for (index, param_descriptor) in param_descriptors.iter().enumerate() {
      let cls = params_array.get_element(env, index)?;
      kinds.push(Self::param_kind(env, known, param_descriptor, &cls)?);
    }
    let owner = Self::declaring_class(env, known, &member)?;
    let raw_id = reflected_method_id(env, &member)?;
    let id = if is_constructor {
      MethodId::Constructor(unsafe { JMethodID::from_raw(raw_id) })
    } else if is_static {
      MethodId::Static(unsafe { JStaticMethodID::from_raw(raw_id) })
    } else {
      MethodId::Instance(unsafe { JMethodID::from_raw(raw_id) })
    };
    Ok(Rc::new(Candidate {
      member: env.new_global_ref(&member)?,
      id,
      owner,
      params: kinds,
      ret: if is_constructor { RetKind::Object } else { Self::ret_kind(&ret_descriptor) },
      descriptor,
      is_abstract,
      refusal,
      initialized: Cell::new(!is_static && !is_constructor),
    }))
  }

  fn read_field(
    env: &mut Env,
    known: &WellKnown,
    array: &JObjectArray<JObject>,
    owner_name: String,
    at: usize,
  ) -> OpResult<Rc<FieldPlan>> {
    let field = array.get_element(env, at)?;
    let ty = array.get_element(env, at + 1)?;
    let descriptor = Self::string_at(env, array, at + 2)?.unwrap_or_default();
    let is_static = Self::bool_at(env, known, array, at + 3)?;
    let is_final = Self::bool_at(env, known, array, at + 4)?;
    let refusal = Self::string_at(env, array, at + 5)?;
    let type_name = Self::string_at(env, array, at + 6)?.unwrap_or_default();
    let name = Self::string_at(env, array, at + 7)?.unwrap_or_default();
    let owner = Self::declaring_class(env, known, &field)?;
    let raw_id = reflected_field_id(env, &field)?;
    let id = if is_static {
      FieldId::Static(unsafe { JStaticFieldID::from_raw(raw_id) })
    } else {
      FieldId::Instance(unsafe { JFieldID::from_raw(raw_id) })
    };
    Ok(Rc::new(FieldPlan {
      field: env.new_global_ref(&field)?,
      id,
      owner,
      owner_name,
      ty: Self::param_kind(env, known, &descriptor, &ty)?,
      name,
      type_name,
      is_final,
      refusal,
      initialized: Cell::new(!is_static),
    }))
  }

  /// `[tag, ...]`: `E` + wire, `M` + class name + groups of [CANDIDATE_WIDTH], `F` + class name + one field of eight
  fn resolve(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    target: &JObject,
    name: &str,
    mode: i32,
  ) -> OpResult<Result<(String, Vec<Rc<Candidate>>, Option<Rc<FieldPlan>>), String>> {
    let array = match self.host.jvm_resolve(env, target, name, mode) {
      Ok(array) => array,
      Err(error) => return throw(ctx, PluginErrorCode::Internal, &error),
    };
    let tag = Self::string_at(env, &array, 0)?.unwrap_or_default();
    if tag == "E" {
      let wire = Self::string_at(env, &array, 1)?.unwrap_or_default();
      return Ok(Err(wire));
    }
    let class_name = Self::string_at(env, &array, 1)?.unwrap_or_default();
    if tag == "F" {
      let field = Self::read_field(env, known, &array, class_name.clone(), 2)?;
      return Ok(Ok((class_name, Vec::new(), Some(field))));
    }
    let length = array.len(env)?;
    let mut candidates = Vec::new();
    let mut at = 2;
    while at + CANDIDATE_WIDTH <= length {
      candidates.push(Self::read_candidate(env, known, &array, at)?);
      at += CANDIDATE_WIDTH;
    }
    Ok(Ok((class_name, candidates, None)))
  }

  fn method_plan(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    key: usize,
    name: &str,
    constructors: bool,
  ) -> OpResult<Rc<Plan>> {
    let cached = {
      let classes = self.classes.borrow();
      let info = &classes[key];
      let table = if constructors { &info.constructors } else { &info.methods };
      table.get(name).cloned()
    };
    let answer = match cached {
      Some(answer) => answer,
      None => {
        let cls = self.class_of(env, key)?;
        let mode = if constructors { RESOLVE_CONSTRUCTORS } else { RESOLVE_METHODS };
        let answer = match self.resolve(ctx, env, known, &cls, name, mode)? {
          Ok((class_name, candidates, _)) => Ok(Rc::new(Plan {
            class_name,
            candidates,
            picks: RefCell::new(HashMap::new()),
          })),
          Err(wire) => Err(wire),
        };
        let mut classes = self.classes.borrow_mut();
        let info = &mut classes[key];
        let table = if constructors { &mut info.constructors } else { &mut info.methods };
        table.insert(name.to_string(), answer.clone());
        answer
      }
    };
    match answer {
      Ok(plan) => Ok(plan),
      Err(wire) => throw_wire(ctx, &wire),
    }
  }

  fn field_plan(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    key: usize,
    name: &str,
  ) -> OpResult<Rc<FieldPlan>> {
    let cached = self.classes.borrow()[key].fields.get(name).cloned();
    let answer = match cached {
      Some(answer) => answer,
      None => {
        let cls = self.class_of(env, key)?;
        let answer = match self.resolve(ctx, env, known, &cls, name, RESOLVE_FIELD)? {
          Ok((_, _, Some(field))) => Ok(field),
          Ok(_) => Err(crate::api::tl::proxy::encode_error("jvm: the host answered a field lookup with no field")),
          Err(wire) => Err(wire),
        };
        self.classes.borrow_mut()[key].fields.insert(name.to_string(), answer.clone());
        answer
      }
    };
    match answer {
      Ok(plan) => Ok(plan),
      Err(wire) => throw_wire(ctx, &wire),
    }
  }

  /// a member that arrived as a value rather than through `getDeclaredMethod`: described once
  fn pinned_of(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    handle: &Class<'_, JvmRef>,
    entry: &Entry,
  ) -> OpResult<Rc<Pinned>> {
    if let Some(pinned) = handle.borrow().pinned.borrow().as_ref() {
      return Ok(pinned.clone());
    }
    let pinned = match self.resolve(ctx, env, known, entry.obj.as_obj(), "", RESOLVE_MEMBER)? {
      Ok((_, _, Some(field))) => Pinned::Field(field),
      Ok((_, candidates, None)) if candidates.len() == 1 => Pinned::Method(candidates[0].clone()),
      Ok(_) => {
        return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a method, constructor or field")
      }
      Err(wire) => return throw_wire(ctx, &wire),
    };
    let pinned = Rc::new(pinned);
    *handle.borrow().pinned.borrow_mut() = Some(pinned.clone());
    Ok(pinned)
  }

  fn matches(&self, ctx: &Ctx<'_>, env: &mut Env, param: &ParamKind, arg: &Arg<'_>) -> OpResult<bool> {
    Ok(match (param, arg) {
      (ParamKind::Ref(_), Arg::Null) => true,
      (_, Arg::Null) => false,
      (ParamKind::Bool, Arg::Bool(_)) => true,
      (ParamKind::Ref(r), Arg::Bool(_)) => r.accepts_boolean,
      (ParamKind::Byte, Arg::Int(v)) => (i8::MIN as i64..=i8::MAX as i64).contains(v),
      (ParamKind::Short, Arg::Int(v)) => (i16::MIN as i64..=i16::MAX as i64).contains(v),
      (ParamKind::Int, Arg::Int(v)) => (i32::MIN as i64..=i32::MAX as i64).contains(v),
      (ParamKind::Char, Arg::Int(v)) => (0..=0xffff).contains(v),
      (ParamKind::Long | ParamKind::Float | ParamKind::Double, Arg::Int(_)) => true,
      (ParamKind::Ref(r), Arg::Int(v)) => match r.exact_box {
        Some(BoxKind::Byte) => (i8::MIN as i64..=i8::MAX as i64).contains(v),
        Some(BoxKind::Short) => (i16::MIN as i64..=i16::MAX as i64).contains(v),
        Some(BoxKind::Int) => (i32::MIN as i64..=i32::MAX as i64).contains(v),
        Some(BoxKind::Char) => (0..=0xffff).contains(v),
        Some(BoxKind::Long | BoxKind::Float | BoxKind::Double) => true,
        Some(BoxKind::Bool) => false,
        None => {
          if (i32::MIN as i64..=i32::MAX as i64).contains(v) {
            r.accepts_integer
          } else {
            r.accepts_long
          }
        }
      },
      (ParamKind::Double, Arg::Double(_)) => true,
      (ParamKind::Float, Arg::Double(v)) => !(v.is_finite() && v.abs() > f32::MAX as f64),
      (ParamKind::Ref(r), Arg::Double(v)) => match r.exact_box {
        Some(BoxKind::Double) => true,
        Some(BoxKind::Float) => !(v.is_finite() && v.abs() > f32::MAX as f64),
        Some(_) => false,
        None => r.accepts_double,
      },
      (ParamKind::Char, Arg::Str(s)) => is_one_code_unit(s),
      (ParamKind::Ref(r), Arg::Str(s)) => match r.exact_box {
        Some(BoxKind::Char) => is_one_code_unit(s),
        _ => r.accepts_string,
      },
      (ParamKind::Ref(r), Arg::Bytes(_)) => r.accepts_bytes,
      (ParamKind::Ref(r), Arg::Ref(handle)) => {
        let entry = self.entry_of(ctx, handle)?;
        env.is_instance_of(entry.obj.as_obj(), &r.cls)?
      }
      _ => false,
    })
  }

  fn boxed_value<'l>(
    env: &mut Env<'l>,
    known: &WellKnown,
    kind: BoxKind,
    value: JValue,
  ) -> jni::errors::Result<JObject<'l>> {
    let (_, cls, value_of, _) = known.boxed(kind);
    unsafe { env.call_static_method_unchecked(cls, *value_of, JavaType::Object, &[value.as_jni()])? }.l()
  }

  /// `PluginJvm.convert` for one argument, after `matches` said it fits
  fn prepare<'l>(
    &self,
    env: &mut Env<'l>,
    known: &WellKnown,
    param: &ParamKind,
    arg: &Arg<'_>,
  ) -> OpResult<Prepared<'l>> {
    Ok(match (param, arg) {
      (_, Arg::Null) => Prepared::Null,
      (ParamKind::Bool, Arg::Bool(b)) => Prepared::Primitive(JValue::Bool(*b)),
      (ParamKind::Byte, Arg::Int(v)) => Prepared::Primitive(JValue::Byte(*v as i8)),
      (ParamKind::Short, Arg::Int(v)) => Prepared::Primitive(JValue::Short(*v as i16)),
      (ParamKind::Char, Arg::Int(v)) => Prepared::Primitive(JValue::Char(*v as u16)),
      (ParamKind::Int, Arg::Int(v)) => Prepared::Primitive(JValue::Int(*v as i32)),
      (ParamKind::Long, Arg::Int(v)) => Prepared::Primitive(JValue::Long(*v)),
      (ParamKind::Float, Arg::Int(v)) => Prepared::Primitive(JValue::Float(*v as f32)),
      (ParamKind::Double, Arg::Int(v)) => Prepared::Primitive(JValue::Double(*v as f64)),
      (ParamKind::Float, Arg::Double(v)) => Prepared::Primitive(JValue::Float(*v as f32)),
      (ParamKind::Double, Arg::Double(v)) => Prepared::Primitive(JValue::Double(*v)),
      (ParamKind::Char, Arg::Str(s)) => Prepared::Primitive(JValue::Char(first_code_unit(s))),
      (ParamKind::Ref(_), Arg::Bool(b)) => {
        Prepared::Local(Self::boxed_value(env, known, BoxKind::Bool, JValue::Bool(*b))?)
      }
      (ParamKind::Ref(r), Arg::Int(v)) => {
        let kind = match r.exact_box {
          Some(kind) => kind,
          None => {
            if (i32::MIN as i64..=i32::MAX as i64).contains(v) {
              BoxKind::Int
            } else {
              BoxKind::Long
            }
          }
        };
        let value = match kind {
          BoxKind::Byte => JValue::Byte(*v as i8),
          BoxKind::Short => JValue::Short(*v as i16),
          BoxKind::Char => JValue::Char(*v as u16),
          BoxKind::Int => JValue::Int(*v as i32),
          BoxKind::Long => JValue::Long(*v),
          BoxKind::Float => JValue::Float(*v as f32),
          BoxKind::Double => JValue::Double(*v as f64),
          BoxKind::Bool => JValue::Bool(false),
        };
        Prepared::Local(Self::boxed_value(env, known, kind, value)?)
      }
      (ParamKind::Ref(r), Arg::Double(v)) => Prepared::Local(match r.exact_box {
        Some(BoxKind::Float) => Self::boxed_value(env, known, BoxKind::Float, JValue::Float(*v as f32))?,
        _ => Self::boxed_value(env, known, BoxKind::Double, JValue::Double(*v))?,
      }),
      (ParamKind::Ref(r), Arg::Str(s)) => Prepared::Local(if r.exact_box == Some(BoxKind::Char) {
        Self::boxed_value(env, known, BoxKind::Char, JValue::Char(first_code_unit(s)))?
      } else {
        JObject::from(JString::new(env, s)?)
      }),
      (ParamKind::Ref(_), Arg::Bytes(bytes)) => Prepared::Local(JObject::from(env.byte_array_from_slice(bytes)?)),
      (ParamKind::Ref(_), Arg::Ref(handle)) => match self.refs.get(handle.borrow().id) {
        Some(entry) => Prepared::Shared(entry),
        None => return Err(OpError::Jni(jni::errors::Error::NullPtr("that handle was released"))),
      },
      _ => return Err(OpError::Jni(jni::errors::Error::WrongJValueType("argument", "parameter"))),
    })
  }

  fn narrower(&self, env: &mut Env, a: &ParamKind, b: &ParamKind) -> OpResult<bool> {
    match (a.numeric_rank(), b.numeric_rank()) {
      (Some(ra), Some(rb)) => return Ok(ra < rb),
      (Some(_), None) | (None, Some(_)) => return Ok(false),
      _ => {}
    }
    match (a, b) {
      (ParamKind::Ref(ra), ParamKind::Ref(rb)) => Ok(env.is_assignable_from(&ra.cls, &rb.cls)?),
      _ => Ok(false),
    }
  }

  fn same_param(&self, env: &mut Env, a: &ParamKind, b: &ParamKind) -> OpResult<bool> {
    match (a, b) {
      (ParamKind::Ref(ra), ParamKind::Ref(rb)) => Ok(env.is_same_object(&ra.cls, &rb.cls)?),
      _ => Ok(a.is_primitive() && b.is_primitive() && std::mem::discriminant(a) == std::mem::discriminant(b)),
    }
  }

  fn more_specific(&self, env: &mut Env, a: &Candidate, b: &Candidate) -> OpResult<bool> {
    if a.params.len() != b.params.len() {
      return Ok(false);
    }
    let mut strictly = false;
    for (pa, pb) in a.params.iter().zip(&b.params) {
      if self.same_param(env, pa, pb)? {
        continue;
      }
      if !self.narrower(env, pa, pb)? {
        return Ok(false);
      }
      strictly = true;
    }
    Ok(strictly)
  }

  /// `PluginJvm.convertsTextToChar`: a js string is a `String` before it is a `char`
  fn converts_text_to_char(candidate: &Candidate, args: &[Arg<'_>]) -> bool {
    candidate.params.iter().zip(args).any(|(param, arg)| {
      matches!(arg, Arg::Str(_))
        && match param {
          ParamKind::Char => true,
          ParamKind::Ref(r) => r.exact_box == Some(BoxKind::Char),
          _ => false,
        }
    })
  }

  fn fits(&self, ctx: &Ctx<'_>, env: &mut Env, candidate: &Candidate, args: &[Arg<'_>]) -> OpResult<bool> {
    if candidate.params.len() != args.len() {
      return Ok(false);
    }
    for (param, arg) in candidate.params.iter().zip(args) {
      if !self.matches(ctx, env, param, arg)? {
        return Ok(false);
      }
    }
    Ok(true)
  }

  fn arg_shape(&self, ctx: &Ctx<'_>, env: &mut Env, known: &WellKnown, arg: &Arg<'_>) -> OpResult<ArgShape> {
    Ok(match arg {
      Arg::Null => ArgShape::Null,
      Arg::Bool(_) => ArgShape::Bool,
      Arg::Int(v) => {
        let mut widths = 0u8;
        if (i8::MIN as i64..=i8::MAX as i64).contains(v) {
          widths |= 1;
        }
        if (i16::MIN as i64..=i16::MAX as i64).contains(v) {
          widths |= 2;
        }
        if (i32::MIN as i64..=i32::MAX as i64).contains(v) {
          widths |= 4;
        }
        if (0..=0xffff).contains(v) {
          widths |= 8;
        }
        ArgShape::Int(widths)
      }
      Arg::Double(v) => ArgShape::Double(!(v.is_finite() && v.abs() > f32::MAX as f64)),
      Arg::Str(s) => ArgShape::Str(is_one_code_unit(s)),
      Arg::Bytes(_) => ArgShape::Bytes,
      Arg::Ref(handle) => {
        let entry = self.entry_of(ctx, handle)?;
        let key = self.class_key_of(ctx, env, known, handle, &entry)?;
        ArgShape::Ref(entry.kind, key)
      }
    })
  }

  /// `PluginJvm.resolve` + `pick`: the same refusals, in the same order
  #[allow(clippy::too_many_arguments)]
  fn pick(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    plan: &Plan,
    what: impl Fn() -> String,
    pinned: bool,
    static_only: bool,
    args: &[Arg<'_>],
  ) -> OpResult<Rc<Candidate>> {
    let mut shapes = Vec::with_capacity(args.len());
    for arg in args {
      shapes.push(self.arg_shape(ctx, env, known, arg)?);
    }
    let key = PickKey { static_only, shapes };
    if let Some(picked) = plan.picks.borrow().get(&key) {
      return Ok(picked.clone());
    }
    let picked = self.pick_uncached(ctx, env, plan, what, pinned, static_only, args)?;
    plan.picks.borrow_mut().insert(key, picked.clone());
    Ok(picked)
  }

  fn pick_uncached(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    plan: &Plan,
    what: impl Fn() -> String,
    pinned: bool,
    static_only: bool,
    args: &[Arg<'_>],
  ) -> OpResult<Rc<Candidate>> {
    let mut candidates: Vec<&Rc<Candidate>> = plan.candidates.iter().collect();
    if static_only {
      candidates.retain(|c| c.is_static());
    }
    if pinned && candidates.is_empty() {
      return throw(ctx, PluginErrorCode::NotFound, &format!("jvm: {} was not found", what()));
    }
    let mut fitting = Vec::new();
    for candidate in candidates {
      if self.fits(ctx, env, candidate, args)? {
        fitting.push(candidate.clone());
      }
    }
    if fitting.is_empty() {
      if pinned {
        return throw(ctx, PluginErrorCode::InvalidArgument, &format!("jvm: {} does not take these arguments", what()));
      }
      return throw(
        ctx,
        PluginErrorCode::NotFound,
        &format!("jvm: no {} takes {} argument(s) of these types", what(), args.len()),
      );
    }
    if fitting.len() == 1 {
      return Ok(fitting.remove(0));
    }
    let as_text: Vec<_> = fitting.iter().filter(|c| !Self::converts_text_to_char(c, args)).cloned().collect();
    if !as_text.is_empty() {
      fitting = as_text;
    }
    let mut narrowest = Vec::new();
    for candidate in &fitting {
      let mut beaten = false;
      for other in &fitting {
        if !Rc::ptr_eq(other, candidate) && self.more_specific(env, other, candidate)? {
          beaten = true;
          break;
        }
      }
      if !beaten {
        narrowest.push(candidate.clone());
      }
    }
    if narrowest.len() != 1 {
      let examples = fitting.iter().take(3).map(|c| c.descriptor.clone()).collect::<Vec<_>>().join(", ");
      return throw(
        ctx,
        PluginErrorCode::InvalidArgument,
        &format!("jvm: {} is ambiguous for these arguments; pin one with a descriptor, e.g. {examples}", what()),
      );
    }
    Ok(narrowest.remove(0))
  }

  #[allow(clippy::too_many_arguments)]
  fn invoke<'l>(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env<'l>,
    known: &WellKnown,
    candidate: &Candidate,
    receiver: Option<&JObject>,
    dispatch: Dispatch,
    args: &[Arg<'_>],
  ) -> OpResult<JValueOwned<'l>> {
    if let Some(wire) = &candidate.refusal {
      return throw_wire(ctx, wire);
    }
    self.ensure_initialized(ctx, env, known, &candidate.owner, &candidate.initialized)?;
    let mut prepared = Vec::with_capacity(args.len());
    for (param, arg) in candidate.params.iter().zip(args) {
      prepared.push(self.prepare(env, known, param, arg)?);
    }
    let values: Vec<jvalue> = prepared.iter().map(|value| value.borrow().as_jni()).collect();
    let called = match &candidate.id {
      MethodId::Constructor(id) => {
        unsafe { env.new_object_unchecked(&candidate.owner, *id, &values) }.map(JValueOwned::Object)
      }
      MethodId::Static(id) => unsafe {
        env.call_static_method_unchecked(&candidate.owner, *id, candidate.ret.java_type(), &values)
      },
      MethodId::Instance(id) => {
        let Some(receiver) = receiver else {
          return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: an instance method needs a receiver");
        };
        match dispatch {
          Dispatch::Virtual => unsafe { env.call_method_unchecked(receiver, *id, candidate.ret.java_type(), &values) },
          Dispatch::Nonvirtual => unsafe {
            let receiver = JObject::from_raw(env, receiver.as_raw());
            let owner = JClass::from_raw(env, candidate.owner.as_raw());
            env.call_nonvirtual_method_unchecked(receiver, &owner, *id, candidate.ret.java_type(), &values)
          },
        }
      }
    };
    drop(prepared);
    match called {
      Ok(value) => Ok(value),
      Err(jni::errors::Error::JavaException) => self.throw_java(ctx, env, known),
      Err(error) => Err(OpError::Jni(error)),
    }
  }

  /// Initialize the class on first static use, matching reflection. Member lookup and
  /// `Class.forName(name, false, ...)` deliberately skip initialization, so call `forName` with
  /// initialization enabled here. If it throws, leave the plan uninitialized and propagate the Java
  /// exception.
  fn ensure_initialized(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    owner: &Global<JClass<'static>>,
    initialized: &Cell<bool>,
  ) -> OpResult<()> {
    if initialized.get() {
      return Ok(());
    }
    let name = unsafe { env.call_method_unchecked(owner, known.class_get_name, JavaType::Object, &[])? }.l()?;
    let loader =
      unsafe { env.call_method_unchecked(owner, known.class_get_class_loader, JavaType::Object, &[])? }.l()?;
    let args = [JValue::Object(&name).as_jni(), JValue::Bool(true).as_jni(), JValue::Object(&loader).as_jni()];
    let loaded =
      unsafe { env.call_static_method_unchecked(&known.class, known.class_for_name, JavaType::Object, &args) };
    match loaded {
      Ok(_) => {
        initialized.set(true);
        Ok(())
      }
      Err(jni::errors::Error::JavaException) => self.throw_java(ctx, env, known),
      Err(error) => Err(OpError::Jni(error)),
    }
  }

  /// `InvocationTargetException`'s cause, described the way kotlin describes it: `Throwable.toString()`
  fn throw_java<T>(&self, ctx: &Ctx<'_>, env: &mut Env, known: &WellKnown) -> OpResult<T> {
    let Some(thrown) = env.exception_occurred() else {
      return throw(ctx, PluginErrorCode::Internal, "jvm: java reported an exception that is not there");
    };
    env.exception_clear();
    let text = Self::describe(env, known, &thrown).unwrap_or_else(|| "java exception".to_string());
    throw_wire(ctx, &format!("E{text}"))
  }

  fn describe(env: &mut Env, known: &WellKnown, thrown: &JThrowable) -> Option<String> {
    let text = unsafe { env.call_method_unchecked(thrown, known.to_string, JavaType::Object, &[]) };
    let text = match text {
      Ok(value) => value.l().ok()?,
      Err(_) => {
        env.exception_clear();
        return None;
      }
    };
    Self::read_string(env, &text).ok().flatten()
  }

  fn get_field<'l>(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env<'l>,
    known: &WellKnown,
    plan: &FieldPlan,
    receiver: Option<&JObject>,
  ) -> OpResult<JValueOwned<'l>> {
    if let Some(wire) = &plan.refusal {
      return throw_wire(ctx, wire);
    }
    self.ensure_initialized(ctx, env, known, &plan.owner, &plan.initialized)?;
    let read = match &plan.id {
      FieldId::Static(id) => unsafe { env.get_static_field_unchecked(&plan.owner, *id, plan.ty.java_type()) },
      FieldId::Instance(id) => {
        let Some(receiver) = receiver else {
          return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: an instance field needs a receiver");
        };
        unsafe { env.get_field_unchecked(receiver, *id, plan.ty.java_type()) }
      }
    };
    Ok(read?)
  }

  fn set_field(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    plan: &FieldPlan,
    receiver: Option<&JObject>,
    value: &Arg<'_>,
  ) -> OpResult<()> {
    if let Some(wire) = &plan.refusal {
      return throw_wire(ctx, wire);
    }
    if plan.is_final {
      return throw(ctx, PluginErrorCode::Forbidden, &format!("jvm: {}.{} is final", plan.owner_name, plan.name));
    }
    self.ensure_initialized(ctx, env, known, &plan.owner, &plan.initialized)?;
    if !self.matches(ctx, env, &plan.ty, value)? {
      return throw(ctx, PluginErrorCode::InvalidArgument, &format!("jvm: cannot assign that to a {}", plan.type_name));
    }
    let prepared = self.prepare(env, known, &plan.ty, value)?;
    match &plan.id {
      FieldId::Static(id) => unsafe { env.set_static_field_unchecked(&plan.owner, *id, prepared.borrow())? },
      FieldId::Instance(id) => {
        let Some(receiver) = receiver else {
          return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: an instance field needs a receiver");
        };
        unsafe { env.set_field_unchecked(receiver, *id, prepared.borrow())? }
      }
    }
    drop(prepared);
    Ok(())
  }

  fn unbox<'l>(
    env: &mut Env<'l>,
    known: &WellKnown,
    kind: BoxKind,
    obj: &JObject,
  ) -> jni::errors::Result<JValueOwned<'l>> {
    let ty = match kind {
      BoxKind::Bool => Primitive::Boolean,
      BoxKind::Byte => Primitive::Byte,
      BoxKind::Char => Primitive::Char,
      BoxKind::Short => Primitive::Short,
      BoxKind::Int => Primitive::Int,
      BoxKind::Long => Primitive::Long,
      BoxKind::Float => Primitive::Float,
      BoxKind::Double => Primitive::Double,
    };
    let unbox = known.boxed(kind).3;
    unsafe { env.call_method_unchecked(obj, unbox, JavaType::Primitive(ty), &[]) }
  }

  fn scalar_to_js<'js>(ctx: &Ctx<'js>, value: JValueOwned<'_>) -> OpResult<Option<Value<'js>>> {
    Ok(Some(match value {
      JValueOwned::Void => Value::new_null(ctx.clone()),
      JValueOwned::Bool(b) => Value::new_bool(ctx.clone(), b),
      JValueOwned::Byte(v) => (v as i32).into_js(ctx)?,
      JValueOwned::Short(v) => (v as i32).into_js(ctx)?,
      JValueOwned::Int(v) => v.into_js(ctx)?,
      JValueOwned::Long(v) => {
        if v.unsigned_abs() > 9_007_199_254_740_991 {
          Value::new_big_int(ctx.clone(), v)?
        } else {
          v.into_js(ctx)?
        }
      }
      JValueOwned::Char(c) => char::from_u32(c as u32).unwrap_or('\u{fffd}').to_string().into_js(ctx)?,
      JValueOwned::Float(v) => (v as f64).into_js(ctx)?,
      JValueOwned::Double(v) => v.into_js(ctx)?,
      JValueOwned::Object(_) => return Ok(None),
    }))
  }

  /// `PluginJvm.encodeValue`: scalars and strings cross as values, everything else is a checked handle
  fn result_to_js<'js>(
    &self,
    ctx: &Ctx<'js>,
    env: &mut Env,
    known: &WellKnown,
    value: JValueOwned<'_>,
  ) -> OpResult<Outcome<'js>> {
    let obj = match value {
      JValueOwned::Object(obj) => obj,
      scalar => return Ok(Outcome::Value(Self::scalar_to_js(ctx, scalar)?.expect("not an object"))),
    };
    if obj.is_null() {
      return Ok(Outcome::Value(Value::new_null(ctx.clone())));
    }
    let cls = env.get_object_class(&obj)?;
    let own_key = self.key_of(env, known, cls.as_ref())?;
    let (kind, checked_key) = match self.shape_of(env, known, own_key)? {
      Shape::String => {
        let text = Self::read_string(env, &obj)?.unwrap_or_default();
        if text.len() > VALUE_LIMIT_BYTES {
          return Err(OpError::Js(too_big::<()>(ctx, "a string", text.len()).unwrap_err()));
        }
        return Ok(Outcome::Value(text.into_js(ctx)?));
      }
      Shape::Boxed(kind) => {
        let unboxed = Self::unbox(env, known, kind, &obj)?;
        return Ok(Outcome::Value(Self::scalar_to_js(ctx, unboxed)?.expect("unboxed to a scalar")));
      }
      Shape::Bytes => {
        let array = unsafe { JByteArray::from_raw(env, obj.as_raw() as jni::sys::jbyteArray) };
        let bytes = env.convert_byte_array(&array)?;
        if bytes.len() > VALUE_LIMIT_BYTES {
          return Err(OpError::Js(too_big::<()>(ctx, "a byte[]", bytes.len()).unwrap_err()));
        }
        return Ok(Outcome::Value(crate::api::tl::proxy::make_bytes_value(ctx, &bytes)?));
      }
      // a `Class` is checked as the class it *names*, and a member by the class it declares
      Shape::Class => (KIND_CLASS, self.key_of(env, known, &obj)?),
      Shape::Method => {
        let declaring = Self::declaring_class(env, known, &obj)?;
        (KIND_METHOD, self.key_of(env, known, declaring.as_obj())?)
      }
      Shape::Constructor => {
        let declaring = Self::declaring_class(env, known, &obj)?;
        (KIND_CONSTRUCTOR, self.key_of(env, known, declaring.as_obj())?)
      }
      Shape::Field => {
        let declaring = Self::declaring_class(env, known, &obj)?;
        (KIND_FIELD, self.key_of(env, known, declaring.as_obj())?)
      }
      Shape::Object => (KIND_OBJECT, own_key),
    };
    let global = env.new_global_ref(&obj)?;
    let id = self.mint_ref(ctx, global, kind)?;
    let class_key = if kind == KIND_CLASS || kind == KIND_OBJECT { Some(checked_key) } else { None };
    Ok(Outcome::Handle(HandleSpec { id, kind, class_key, pinned: None }))
  }

  fn receiver_of(entry: &Entry) -> Option<&JObject<'static>> {
    if entry.kind == KIND_CLASS {
      None
    } else {
      Some(entry.obj.as_obj())
    }
  }

  pub(crate) fn call<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    name: &str,
    args: &[Arg<'js>],
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let entry = self.entry_of(ctx, target)?;
      let key = self.class_key_of(ctx, env, known, target, &entry)?;
      let plan = self.method_plan(ctx, env, known, key, name, false)?;
      let what = || format!("{}.{}", plan.class_name, name.split('(').next().unwrap_or(name));
      let candidate = self.pick(ctx, env, known, &plan, what, name.contains('('), entry.kind == KIND_CLASS, args)?;
      let value = self.invoke(ctx, env, known, &candidate, Self::receiver_of(&entry), Dispatch::Virtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  /// SAFETY: `array` is the caller's local reference, kept readable only for its own JNI call by
  /// the hook context that owns it
  pub(crate) fn element_to_js<'js>(
    &self,
    ctx: &Ctx<'js>,
    array: jni::sys::jobjectArray,
    index: usize,
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let array = unsafe { JObjectArray::<JObject>::from_raw(env, array) };
      let element = array.get_element(env, index)?;
      self.result_to_js(ctx, env, known, JValueOwned::Object(element))
    })
  }

  pub(crate) fn construct<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    args: &[Arg<'js>],
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let key = self.class_target(ctx, env, known, target)?;
      let plan = self.method_plan(ctx, env, known, key, "", true)?;
      let what = || format!("{} constructor", plan.class_name);
      let candidate = self.pick(ctx, env, known, &plan, what, false, false, args)?;
      let value = self.invoke(ctx, env, known, &candidate, None, Dispatch::Virtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  /// `super.name(args)` as written in [cls]: resolved on its superclass, and dispatched to exactly
  /// the member picked rather than to the receiver's override of it
  pub(crate) fn call_super<'js>(
    &self,
    ctx: &Ctx<'js>,
    cls: &Class<'js, JvmRef>,
    receiver: &Arg<'js>,
    name: &str,
    args: &[Arg<'js>],
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let key = self.class_target(ctx, env, known, cls)?;
      let cls = self.class_of(env, key)?;
      let receiver = match receiver {
        Arg::Ref(handle) => self.entry_of(ctx, handle)?,
        _ => return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: callSuper needs a java object to call on"),
      };
      if !env.is_instance_of(receiver.obj.as_obj(), &cls)? {
        let class_name = Self::class_name(env, known, &cls)?;
        let message = format!("jvm: that receiver is not an instance of {class_name}");
        return throw(ctx, PluginErrorCode::InvalidArgument, &message);
      }
      let Some(parent) = env.get_superclass(&cls)? else {
        let class_name = Self::class_name(env, known, &cls)?;
        return throw(ctx, PluginErrorCode::InvalidArgument, &format!("jvm: {class_name} has no superclass"));
      };
      let parent_key = self.key_of(env, known, &parent)?;
      let plan = self.method_plan(ctx, env, known, parent_key, name, false)?;
      let what = || format!("{}.{}", plan.class_name, name.split('(').next().unwrap_or(name));
      let candidate = self.pick(ctx, env, known, &plan, what, name.contains('('), false, args)?;
      if candidate.is_abstract {
        return throw(
          ctx,
          PluginErrorCode::InvalidArgument,
          &format!("jvm: {}{} is abstract, so there is no super implementation to call", what(), candidate.descriptor),
        );
      }
      let value = self.invoke(ctx, env, known, &candidate, Some(receiver.obj.as_obj()), Dispatch::Nonvirtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  pub(crate) fn get<'js>(&self, ctx: &Ctx<'js>, target: &Class<'js, JvmRef>, name: &str) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let entry = self.entry_of(ctx, target)?;
      let key = self.class_key_of(ctx, env, known, target, &entry)?;
      let plan = self.field_plan(ctx, env, known, key, name)?;
      let value = self.get_field(ctx, env, known, &plan, Self::receiver_of(&entry))?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  pub(crate) fn set<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    name: &str,
    value: &Arg<'js>,
  ) -> JsResult<()> {
    self.with_env(ctx, |env, known| {
      let entry = self.entry_of(ctx, target)?;
      let key = self.class_key_of(ctx, env, known, target, &entry)?;
      let plan = self.field_plan(ctx, env, known, key, name)?;
      self.set_field(ctx, env, known, &plan, Self::receiver_of(&entry), value)
    })
  }

  /// `getDeclaredMethod`/`getDeclaredConstructor`: one member, minted with its plan attached
  pub(crate) fn method<'js>(&self, ctx: &Ctx<'js>, target: &Class<'js, JvmRef>, name: &str) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let key = self.class_target(ctx, env, known, target)?;
      let (plan, what) = if let Some(descriptor) = name.strip_prefix("<init>") {
        let plan = self.method_plan(ctx, env, known, key, descriptor, true)?;
        let what = format!("{} constructor", plan.class_name);
        (plan, what)
      } else {
        let plan = self.method_plan(ctx, env, known, key, name, false)?;
        let simple = name.split('(').next().unwrap_or(name);
        let what = format!("{}.{simple}", plan.class_name);
        (plan, what)
      };
      if plan.candidates.is_empty() {
        return throw(ctx, PluginErrorCode::NotFound, &format!("jvm: {what} was not found"));
      }
      if plan.candidates.len() > 1 {
        let examples = plan.candidates.iter().take(3).map(|c| c.descriptor.clone()).collect::<Vec<_>>().join(", ");
        return throw(
          ctx,
          PluginErrorCode::InvalidArgument,
          &format!("jvm: {what} is overloaded; pin one with a descriptor, e.g. {examples}"),
        );
      }
      let candidate = plan.candidates[0].clone();
      if let Some(wire) = &candidate.refusal {
        return throw_wire(ctx, wire);
      }
      let member = env.new_global_ref(candidate.member.as_obj())?;
      let kind = if candidate.is_constructor() { KIND_CONSTRUCTOR } else { KIND_METHOD };
      let id = self.mint_ref(ctx, member, kind)?;
      Ok(Outcome::Handle(HandleSpec {
        id,
        kind,
        class_key: None,
        pinned: Some(Rc::new(Pinned::Method(candidate))),
      }))
    })
  }

  pub(crate) fn field<'js>(&self, ctx: &Ctx<'js>, target: &Class<'js, JvmRef>, name: &str) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let key = self.class_target(ctx, env, known, target)?;
      let plan = self.field_plan(ctx, env, known, key, name)?;
      if let Some(wire) = &plan.refusal {
        return throw_wire(ctx, wire);
      }
      let field = env.new_global_ref(plan.field.as_obj())?;
      let id = self.mint_ref(ctx, field, KIND_FIELD)?;
      Ok(Outcome::Handle(HandleSpec {
        id,
        kind: KIND_FIELD,
        class_key: None,
        pinned: Some(Rc::new(Pinned::Field(plan))),
      }))
    })
  }

  /// A pinned member plan may come from a different class than the receiver. Validate the receiver
  /// before calling through its JNI ID; unlike `call`/`get`/`set`, resolution did not already
  /// establish compatibility.
  fn receiver_arg(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    arg: &Arg<'_>,
    instance_owner: Option<(&Global<JClass<'static>>, &str)>,
  ) -> OpResult<Option<Entry>> {
    match arg {
      Arg::Null => Ok(None),
      Arg::Ref(handle) => {
        let entry = self.entry_of(ctx, handle)?;
        if entry.kind == KIND_CLASS {
          return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: a class is not a receiver");
        }
        if let Some((owner, what)) = instance_owner {
          if !env.is_instance_of(entry.obj.as_obj(), owner)? {
            return throw(
              ctx,
              PluginErrorCode::InvalidArgument,
              &format!("jvm: that receiver is not an instance of the class declaring {what}"),
            );
          }
        }
        Ok(Some(entry))
      }
      _ => throw(ctx, PluginErrorCode::InvalidArgument, "jvm: the receiver must be a java object or null"),
    }
  }

  pub(crate) fn invoke_pinned<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    receiver: &Arg<'js>,
    args: &[Arg<'js>],
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let entry = self.entry_of(ctx, target)?;
      if entry.kind != KIND_METHOD && entry.kind != KIND_CONSTRUCTOR {
        return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a method or constructor");
      }
      let pinned = self.pinned_of(ctx, env, known, target, &entry)?;
      let Pinned::Method(candidate) = &*pinned else {
        return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: that handle is not a method or constructor");
      };
      let receiver = match &candidate.id {
        MethodId::Constructor(_) => None,
        MethodId::Static(_) => self.receiver_arg(ctx, env, receiver, None)?,
        MethodId::Instance(_) => {
          self.receiver_arg(ctx, env, receiver, Some((&candidate.owner, &candidate.descriptor)))?
        }
      };
      if !self.fits(ctx, env, candidate, args)? {
        let what = if candidate.is_constructor() { "constructor" } else { &candidate.descriptor };
        return throw(ctx, PluginErrorCode::InvalidArgument, &format!("jvm: {what} does not take these arguments"));
      }
      let receiver_obj = receiver.as_ref().map(|entry| entry.obj.as_obj());
      let value = self.invoke(ctx, env, known, candidate, receiver_obj, Dispatch::Virtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  /// `Class.isInstance`, over the handle the caller already narrowed to one (see `js_is_instance`)
  pub(crate) fn is_instance<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    value: &Arg<'js>,
  ) -> JsResult<bool> {
    self.with_env(ctx, |env, known| {
      let entry = self.entry_of(ctx, target)?;
      if entry.kind != KIND_CLASS {
        return throw(ctx, PluginErrorCode::InvalidArgument, "jvm: isInstance needs a class");
      }
      // resolved for its own sake: it is what caches the key and pins the scope check on the class
      let _ = self.class_key_of(ctx, env, known, target, &entry)?;
      let Arg::Ref(handle) = value else {
        return Ok(false);
      };
      let subject = self.entry_of(ctx, handle)?;
      let cls = unsafe { JClass::from_raw(env, entry.obj.as_obj().as_raw()) };
      Ok(env.is_instance_of(subject.obj.as_obj(), &cls)?)
    })
  }

  pub(crate) fn member_get<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    receiver: &Arg<'js>,
  ) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let (plan, receiver) = self.pinned_field(ctx, env, known, target, receiver)?;
      let receiver_obj = receiver.as_ref().map(|entry| entry.obj.as_obj());
      let value = self.get_field(ctx, env, known, &plan, receiver_obj)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  fn field_owner(plan: &FieldPlan) -> Option<(&Global<JClass<'static>>, &str)> {
    match plan.id {
      FieldId::Static(_) => None,
      FieldId::Instance(_) => Some((&plan.owner, &plan.name)),
    }
  }

  pub(crate) fn member_set<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    receiver: &Arg<'js>,
    value: &Arg<'js>,
  ) -> JsResult<()> {
    self.with_env(ctx, |env, known| {
      let (plan, receiver) = self.pinned_field(ctx, env, known, target, receiver)?;
      let receiver_obj = receiver.as_ref().map(|entry| entry.obj.as_obj());
      self.set_field(ctx, env, known, &plan, receiver_obj, value)
    })
  }
}

/// `PluginJvm.convert` takes a `char` from a string of one utf-16 unit, so an astral code point is refused rather than cut to its high surrogate
fn is_one_code_unit(text: &str) -> bool {
  text.encode_utf16().count() == 1
}

fn first_code_unit(text: &str) -> u16 {
  text.encode_utf16().next().unwrap_or(0)
}

/// one converted argument, alive for the call: a primitive, a local this call made, or a plugin
/// reference held so the table cannot drop it underneath the vm
enum Prepared<'l> {
  Null,
  Primitive(JValue<'static>),
  Local(JObject<'l>),
  Shared(Entry),
}

impl<'l> Prepared<'l> {
  fn borrow(&self) -> JValue<'_> {
    match self {
      Prepared::Null => JValue::Object(&NULL),
      Prepared::Primitive(value) => *value,
      Prepared::Local(obj) => JValue::Object(obj),
      Prepared::Shared(entry) => JValue::Object(entry.obj.as_obj()),
    }
  }
}

static NULL: JObject<'static> = JObject::null();
