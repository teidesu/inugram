//! Keep conversions aligned with `PluginJvm.convert`; `jvm-test.js` and `PluginJvmTest` check they agree.

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
use crate::api::error::{throw_wire_error, PluginErrorCode};
use crate::jni::env::with_current_env;
use crate::utils::qjs::qjs_read_typed_bytes;

pub(crate) const RESOLVE_METHODS: i32 = 0;
pub(crate) const RESOLVE_CONSTRUCTORS: i32 = 1;
pub(crate) const RESOLVE_FIELD: i32 = 2;
pub(crate) const RESOLVE_MEMBER: i32 = 3;

/// `PluginJvm.methodsAnswer` layout: member, parameter classes, descriptor, static, abstract, refusal
const CANDIDATE_WIDTH: usize = 6;

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

fn throw_wire<T>(ctx: &Ctx<'_>, wire: &str) -> OpResult<T> {
  throw_wire_error(ctx, wire)?;
  PluginErrorCode::Internal
    .throw(ctx, &format!("jvm: unreadable refusal: {wire}"))
    .map_err(OpError::Js)
}

pub(crate) struct RefParam {
  cls: Global<JClass<'static>>,
  exact_box: Option<Primitive>,
  accepts_string: bool,
  accepts_bytes: bool,
  accepts_boolean: bool,
  accepts_integer: bool,
  accepts_long: bool,
  accepts_double: bool,
}

pub(crate) enum ParamKind {
  Prim(Primitive),
  Ref(RefParam),
}

impl ParamKind {
  fn numeric_rank(&self) -> Option<u8> {
    match self {
      ParamKind::Prim(Primitive::Byte) => Some(0),
      ParamKind::Prim(Primitive::Short | Primitive::Char) => Some(1),
      ParamKind::Prim(Primitive::Int) => Some(2),
      ParamKind::Prim(Primitive::Long) => Some(3),
      ParamKind::Prim(Primitive::Float) => Some(4),
      ParamKind::Prim(Primitive::Double) => Some(5),
      _ => None,
    }
  }

  fn java_type(&self) -> JavaType {
    match self {
      ParamKind::Prim(p) => JavaType::Primitive(*p),
      ParamKind::Ref(_) => JavaType::Object,
    }
  }
}

/// `PluginJvm.convert`'s range rules: a js integer fits a `p` it does not truncate in
fn int_fits(p: Primitive, v: i64) -> bool {
  match p {
    Primitive::Byte => i8::try_from(v).is_ok(),
    Primitive::Short => i16::try_from(v).is_ok(),
    Primitive::Int => i32::try_from(v).is_ok(),
    Primitive::Char => u16::try_from(v).is_ok(),
    Primitive::Long | Primitive::Float | Primitive::Double => true,
    Primitive::Boolean | Primitive::Void => false,
  }
}

fn float_fits(p: Primitive, v: f64) -> bool {
  match p {
    Primitive::Double => true,
    Primitive::Float => !(v.is_finite() && v.abs() > f32::MAX as f64),
    _ => false,
  }
}

fn int_value(p: Primitive, v: i64) -> JValue<'static> {
  match p {
    Primitive::Byte => JValue::Byte(v as i8),
    Primitive::Short => JValue::Short(v as i16),
    Primitive::Char => JValue::Char(v as u16),
    Primitive::Int => JValue::Int(v as i32),
    Primitive::Long => JValue::Long(v),
    Primitive::Float => JValue::Float(v as f32),
    Primitive::Double => JValue::Double(v as f64),
    Primitive::Boolean | Primitive::Void => JValue::Bool(false),
  }
}

fn float_value(p: Primitive, v: f64) -> JValue<'static> {
  match p {
    Primitive::Float => JValue::Float(v as f32),
    _ => JValue::Double(v),
  }
}

/// the box `Integer` or `Long` a js integer takes when the parameter names no box of its own
fn int_box(v: i64) -> Primitive {
  if int_fits(Primitive::Int, v) {
    Primitive::Int
  } else {
    Primitive::Long
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
  pub(crate) member: Global<JObject<'static>>,
  id: MethodId,
  owner: Global<JClass<'static>>,
  params: Vec<ParamKind>,
  ret: JavaType,
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

#[derive(Clone, Copy)]
enum Shape {
  String,
  Boxed(Primitive),
  Bytes,
  Class,
  Method,
  Constructor,
  Field,
  Object,
}

type Resolved = (String, Vec<Rc<Candidate>>, Option<Rc<FieldPlan>>);
type PlanTable<P> = HashMap<String, Result<Rc<P>, String>>;

struct ClassInfo {
  cls: Global<JClass<'static>>,
  shape: Option<Shape>,
  methods: PlanTable<Plan>,
  constructors: PlanTable<Plan>,
  fields: PlanTable<FieldPlan>,
}

struct WellKnown {
  string: Global<JClass<'static>>,
  class: Global<JClass<'static>>,
  method: Global<JClass<'static>>,
  constructor: Global<JClass<'static>>,
  field: Global<JClass<'static>>,
  byte_array: Global<JClass<'static>>,
  boxes: [(Primitive, Global<JClass<'static>>, JStaticMethodID, JMethodID); 8],
  to_string: JMethodID,
  get_declaring_class: JMethodID,
  system: Global<JClass<'static>>,
  identity_hash: JStaticMethodID,
  class_for_name: JStaticMethodID,
  class_get_name: JMethodID,
  class_get_class_loader: JMethodID,
}

static WELL_KNOWN: OnceLock<Option<WellKnown>> = OnceLock::new();

fn find(env: &mut Env, name: &str) -> jni::errors::Result<Global<JClass<'static>>> {
  let cls = env.find_class(JNIString::from(name))?;
  env.new_global_ref(&cls)
}

fn method_id(env: &mut Env, cls: &Global<JClass<'static>>, name: &str, sig: &str) -> jni::errors::Result<JMethodID> {
  let parsed = RuntimeMethodSignature::from_str(sig)?;
  env.get_method_id(cls, JNIString::from(name), MethodSignature::from(&parsed))
}

fn static_method_id(
  env: &mut Env,
  cls: &Global<JClass<'static>>,
  name: &str,
  sig: &str,
) -> jni::errors::Result<JStaticMethodID> {
  let parsed = RuntimeMethodSignature::from_str(sig)?;
  env.get_static_method_id(cls, JNIString::from(name), MethodSignature::from(&parsed))
}

/// `obj.<id>()` for a cached no-argument, object-returning method of a class `obj` is an instance of
fn call_object<'l>(env: &mut Env<'l>, obj: &JObject, id: JMethodID) -> jni::errors::Result<JObject<'l>> {
  // SAFETY: `id` was looked up on `obj`'s class with signature `()L...;`, which is what is called
  unsafe { env.call_method_unchecked(obj, id, JavaType::Object, &[]) }?.l()
}

/// The id of a `java.lang.reflect.Method` or `Constructor`. Unlike `GetMethodID`, this does not run
/// the declaring class's static initializers. jni does not wrap `FromReflectedMethod`.
fn reflected_method_id(env: &mut Env, member: &JObject) -> jni::errors::Result<JMethodID> {
  let raw = env.get_raw();
  // SAFETY: `member` is a live reflected method or constructor, and the id is checked for null
  let id = unsafe { ((**raw).v1_2.FromReflectedMethod)(raw, member.as_raw()) };
  if id.is_null() {
    return Err(jni::errors::Error::NullPtr("FromReflectedMethod"));
  }
  // SAFETY: a non-null id `FromReflectedMethod` handed out
  Ok(unsafe { JMethodID::from_raw(id) })
}

fn reflected_field_id(env: &mut Env, field: &JObject) -> jni::errors::Result<jni::sys::jfieldID> {
  let raw = env.get_raw();
  // SAFETY: `field` is a live `java.lang.reflect.Field`, and the id is checked for null
  let id = unsafe { ((**raw).v1_2.FromReflectedField)(raw, field.as_raw()) };
  if id.is_null() {
    return Err(jni::errors::Error::NullPtr("FromReflectedField"));
  }
  Ok(id)
}

impl WellKnown {
  fn load(env: &mut Env) -> jni::errors::Result<Self> {
    let boxed = |env: &mut Env, kind: Primitive, name: &str, unbox: &str| {
      let cls = find(env, name)?;
      let value_of = static_method_id(env, &cls, "valueOf", &format!("({kind})L{name};"))?;
      let unbox = method_id(env, &cls, unbox, &format!("(){kind}"))?;
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
        boxed(env, Primitive::Boolean, "java/lang/Boolean", "booleanValue")?,
        boxed(env, Primitive::Byte, "java/lang/Byte", "byteValue")?,
        boxed(env, Primitive::Char, "java/lang/Character", "charValue")?,
        boxed(env, Primitive::Short, "java/lang/Short", "shortValue")?,
        boxed(env, Primitive::Int, "java/lang/Integer", "intValue")?,
        boxed(env, Primitive::Long, "java/lang/Long", "longValue")?,
        boxed(env, Primitive::Float, "java/lang/Float", "floatValue")?,
        boxed(env, Primitive::Double, "java/lang/Double", "doubleValue")?,
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

  fn boxed(&self, kind: Primitive) -> &(Primitive, Global<JClass<'static>>, JStaticMethodID, JMethodID) {
    self.boxes.iter().find(|entry| entry.0 == kind).expect("every box kind is loaded")
  }
}

fn refuse_call(env: &mut Env, message: &str) -> jni::errors::Error {
  if let Err(error) = env.throw_new(jni::jni_str!("java/lang/IllegalArgumentException"), JNIString::from(message)) {
    return error;
  }
  jni::errors::Error::JavaException
}

/// A mismatched argument here is memory corruption, not an exception, so each is checked again
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
  let owner = call_object(env, method, known.get_declaring_class)?;
  let owner = env.cast_local::<JClass>(owner)?;
  if receiver.is_null() || !env.is_instance_of(receiver, &owner)? {
    return Err(refuse_call(env, "jvm: that receiver is not an instance of the class declaring the method"));
  }
  let Ok(signature) = RuntimeMethodSignature::from_str(descriptor) else {
    return Err(refuse_call(env, "jvm: that is not a method descriptor"));
  };
  let signature = signature.method_signature();
  if signature.args().len() != params.len(env)? || signature.args().len() != args.len(env)? {
    return Err(refuse_call(env, "jvm: the arguments do not match the method"));
  }
  let mut values = Vec::with_capacity(signature.args().len());
  for (index, ty) in signature.args().iter().enumerate() {
    let arg = args.get_element(env, index)?;
    match ty {
      JavaType::Primitive(kind) => {
        if arg.is_null() || !env.is_instance_of(&arg, &known.boxed(*kind).1)? {
          return Err(refuse_call(env, "jvm: a primitive parameter was handed something else"));
        }
        values.push(Native::unbox(env, known, *kind, &arg)?.as_jni());
      }
      JavaType::Object | JavaType::Array => {
        let param = params.get_element(env, index)?;
        let param = env.cast_local::<JClass>(param)?;
        if !arg.is_null() && !env.is_instance_of(&arg, &param)? {
          return Err(refuse_call(env, "jvm: an argument does not match its parameter"));
        }
        values.push(JValue::Object(&arg).as_jni());
      }
    }
  }
  let id = reflected_method_id(env, method)?;
  // SAFETY: the receiver, every argument and the return type were checked against the method above
  let value = unsafe { env.call_nonvirtual_method_unchecked(receiver, &owner, id, signature.ret(), &values)? };
  match value.primitive_type() {
    None => value.l(),
    Some(Primitive::Void) => Ok(JObject::null()),
    Some(kind) => Native::boxed_value(env, known, kind, value.borrow()),
  }
}

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
      return super::throw_too_big(ctx, "a string argument", s.len(), VALUE_LIMIT_BYTES);
    }
    return Ok(Arg::Str(s));
  }
  if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
    // an error thrown past the limit is built after the last use of the bytes
    let arg = qjs_read_typed_bytes(&typed, |bytes| {
      if bytes.len() > VALUE_LIMIT_BYTES {
        return super::throw_too_big(ctx, "a byte[] argument", bytes.len(), VALUE_LIMIT_BYTES);
      }
      Ok(Arg::Bytes(bytes.to_vec()))
    });
    if let Some(arg) = arg {
      return arg;
    }
  }
  if let Some(handle) = super::get_jvm_ref(value) {
    return Ok(Arg::Ref(handle));
  }
  PluginErrorCode::InvalidArgument.throw(ctx, &format!("jvm: cannot hand a {} to java", value.type_of()))
}

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
        return PluginErrorCode::Internal.throw(ctx, "jvm: the platform classes did not load").map_err(OpError::Js);
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

  fn get_live_entry(&self, ctx: &Ctx<'_>, handle: &Class<'_, JvmRef>) -> OpResult<Entry> {
    let id = handle.borrow().id;
    match self.refs.get(id) {
      Some(entry) if !self.refs.is_closed() => Ok(entry),
      _ => PluginErrorCode::HandleExpired
        .throw(ctx, "jvm: that handle was released; a plugin's handles do not outlive it")
        .map_err(OpError::Js),
    }
  }

  fn mint_ref(&self, ctx: &Ctx<'_>, obj: Global<JObject<'static>>, kind: u8) -> OpResult<i64> {
    match self.refs.mint(obj, kind) {
      Some(id) => Ok(id),
      None => PluginErrorCode::HandleExpired
        .throw(ctx, "jvm: this plugin's handles have been released")
        .map_err(OpError::Js),
    }
  }

  fn class_target(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    target: &Class<'_, JvmRef>,
  ) -> OpResult<usize> {
    let entry = self.get_live_entry(ctx, target)?;
    if entry.kind != KIND_CLASS {
      return PluginErrorCode::InvalidArgument.throw(ctx, "jvm: that handle is not a class").map_err(OpError::Js);
    }
    self.resolve_class_key(env, known, target, &entry)
  }

  fn pinned_field(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    target: &Class<'_, JvmRef>,
    receiver: &Arg<'_>,
  ) -> OpResult<(Rc<FieldPlan>, Option<Entry>)> {
    let entry = self.get_live_entry(ctx, target)?;
    if entry.kind != KIND_FIELD {
      return PluginErrorCode::InvalidArgument.throw(ctx, "jvm: that handle is not a field").map_err(OpError::Js);
    }
    let pinned = self.get_pinned(ctx, env, known, target, &entry)?;
    let Pinned::Field(plan) = &*pinned else {
      return PluginErrorCode::InvalidArgument.throw(ctx, "jvm: that handle is not a field").map_err(OpError::Js);
    };
    let plan = plan.clone();
    let receiver = self.receiver_arg(ctx, env, receiver, Self::field_owner(&plan))?;
    Ok((plan, receiver))
  }

  fn intern_class_key(&self, env: &mut Env, known: &WellKnown, cls: &JObject) -> OpResult<usize> {
    // SAFETY: `identityHashCode(Object)I`, handed one object
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
    let global = Self::as_class(env, cls)?;
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
  fn get_class(&self, env: &mut Env, key: usize) -> OpResult<Global<JClass<'static>>> {
    let classes = self.classes.borrow();
    Ok(env.new_global_ref(&*classes[key].cls)?)
  }

  fn resolve_class_key(
    &self,
    env: &mut Env,
    known: &WellKnown,
    handle: &Class<'_, JvmRef>,
    entry: &Entry,
  ) -> OpResult<usize> {
    if let Some(key) = handle.borrow().class_key.get() {
      return Ok(key);
    }
    let key = if entry.kind == KIND_CLASS {
      self.intern_class_key(env, known, entry.obj.as_obj())?
    } else {
      let cls = env.get_object_class(entry.obj.as_obj())?;
      self.intern_class_key(env, known, cls.as_ref())?
    };
    handle.borrow().class_key.set(Some(key));
    Ok(key)
  }

  fn resolve_class_shape(&self, env: &mut Env, known: &WellKnown, key: usize) -> OpResult<Shape> {
    if let Some(shape) = self.classes.borrow()[key].shape {
      return Ok(shape);
    }
    let cls = self.get_class(env, key)?;
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
    // SAFETY: callers hand over a `java.lang.String`: a result `resolve_class_shape` said is one, or a string slot
    // of the host's answer
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
    Ok(Self::unbox(env, known, Primitive::Boolean, obj)?.z()?)
  }

  fn class_name(env: &mut Env, known: &WellKnown, cls: &JObject) -> OpResult<String> {
    let name = call_object(env, cls, known.class_get_name)?;
    Ok(Self::read_string(env, &name)?.unwrap_or_default())
  }

  fn as_class(env: &mut Env, obj: &JObject) -> OpResult<Global<JClass<'static>>> {
    Ok(env.new_cast_global_ref::<JClass>(obj)?)
  }

  fn declaring_class(env: &mut Env, known: &WellKnown, member: &JObject) -> OpResult<Global<JClass<'static>>> {
    let cls = call_object(env, member, known.get_declaring_class)?;
    Self::as_class(env, &cls)
  }

  fn param_kind(env: &mut Env, known: &WellKnown, ty: JavaType, cls: &JObject) -> OpResult<ParamKind> {
    Ok(match ty {
      JavaType::Primitive(p) => ParamKind::Prim(p),
      JavaType::Object | JavaType::Array => {
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
        let accepts_boolean = assignable(env, &known.boxed(Primitive::Boolean).1)?;
        let accepts_integer = assignable(env, &known.boxed(Primitive::Int).1)?;
        let accepts_long = assignable(env, &known.boxed(Primitive::Long).1)?;
        let accepts_double = assignable(env, &known.boxed(Primitive::Double).1)?;
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
    let signature = RuntimeMethodSignature::from_str(&descriptor)?;
    let signature = signature.method_signature();
    let params = env.cast_local::<JObjectArray<JObject>>(params)?;
    let mut kinds = Vec::with_capacity(signature.args().len());
    for (index, ty) in signature.args().iter().enumerate() {
      let cls = params.get_element(env, index)?;
      kinds.push(Self::param_kind(env, known, *ty, &cls)?);
    }
    let owner = Self::declaring_class(env, known, &member)?;
    let id = reflected_method_id(env, &member)?;
    let id = if is_constructor {
      MethodId::Constructor(id)
    } else if is_static {
      // SAFETY: the host read the member's static modifier, so this id names a static method
      MethodId::Static(unsafe { JStaticMethodID::from_raw(id.into_raw()) })
    } else {
      MethodId::Instance(id)
    };
    Ok(Rc::new(Candidate {
      member: env.new_global_ref(&member)?,
      id,
      owner,
      params: kinds,
      ret: if is_constructor { JavaType::Object } else { signature.ret() },
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
    // SAFETY: a non-null `FromReflectedField` id, static exactly when the host read the field as static
    let id = unsafe {
      if is_static {
        FieldId::Static(JStaticFieldID::from_raw(raw_id))
      } else {
        FieldId::Instance(JFieldID::from_raw(raw_id))
      }
    };
    Ok(Rc::new(FieldPlan {
      field: env.new_global_ref(&field)?,
      id,
      owner,
      owner_name,
      ty: Self::param_kind(env, known, descriptor.parse()?, &ty)?,
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
  ) -> OpResult<Result<Resolved, String>> {
    let array = match self.host.jvm_resolve(env, target, name, mode) {
      Ok(array) => array,
      Err(error) => return PluginErrorCode::Internal.throw(ctx, &error).map_err(OpError::Js),
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
    let (mode, table): (_, fn(&mut ClassInfo) -> &mut PlanTable<Plan>) = if constructors {
      (RESOLVE_CONSTRUCTORS, |info| &mut info.constructors)
    } else {
      (RESOLVE_METHODS, |info| &mut info.methods)
    };
    self.get_cached_plan(ctx, env, known, (key, name, mode), table, |(class_name, candidates, _)| {
      Ok(Rc::new(Plan {
        class_name,
        candidates,
        picks: RefCell::new(HashMap::new()),
      }))
    })
  }

  fn field_plan(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    key: usize,
    name: &str,
  ) -> OpResult<Rc<FieldPlan>> {
    self.get_cached_plan(
      ctx,
      env,
      known,
      (key, name, RESOLVE_FIELD),
      |info| &mut info.fields,
      |(_, _, field)| {
        field.ok_or_else(|| crate::api::tl::proxy::encode_error("jvm: the host answered a field lookup with no field"))
      },
    )
  }

  fn get_cached_plan<P>(
    &self,
    ctx: &Ctx<'_>,
    env: &mut Env,
    known: &WellKnown,
    (key, name, mode): (usize, &str, i32),
    table: fn(&mut ClassInfo) -> &mut PlanTable<P>,
    build: impl FnOnce(Resolved) -> Result<Rc<P>, String>,
  ) -> OpResult<Rc<P>> {
    let cached = table(&mut self.classes.borrow_mut()[key]).get(name).cloned();
    let answer = match cached {
      Some(answer) => answer,
      None => {
        let cls = self.get_class(env, key)?;
        let answer = self.resolve(ctx, env, known, &cls, name, mode)?.and_then(build);
        table(&mut self.classes.borrow_mut()[key]).insert(name.to_string(), answer.clone());
        answer
      }
    };
    answer.or_else(|wire| throw_wire(ctx, &wire))
  }

  fn get_pinned(
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
        return PluginErrorCode::InvalidArgument
          .throw(ctx, "jvm: that handle is not a method, constructor or field")
          .map_err(OpError::Js)
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
      (ParamKind::Prim(p), Arg::Bool(_)) => *p == Primitive::Boolean,
      (ParamKind::Ref(r), Arg::Bool(_)) => r.accepts_boolean,
      (ParamKind::Prim(p), Arg::Int(v)) => int_fits(*p, *v),
      (ParamKind::Ref(r), Arg::Int(v)) => match r.exact_box {
        Some(p) => int_fits(p, *v),
        None if int_box(*v) == Primitive::Int => r.accepts_integer,
        None => r.accepts_long,
      },
      (ParamKind::Prim(p), Arg::Double(v)) => float_fits(*p, *v),
      (ParamKind::Ref(r), Arg::Double(v)) => match r.exact_box {
        Some(p) => float_fits(p, *v),
        None => r.accepts_double,
      },
      (ParamKind::Prim(p), Arg::Str(s)) => *p == Primitive::Char && is_one_code_unit(s),
      (ParamKind::Ref(r), Arg::Str(s)) => match r.exact_box {
        Some(Primitive::Char) => is_one_code_unit(s),
        _ => r.accepts_string,
      },
      (ParamKind::Ref(r), Arg::Bytes(_)) => r.accepts_bytes,
      (ParamKind::Ref(r), Arg::Ref(handle)) => {
        let entry = self.get_live_entry(ctx, handle)?;
        env.is_instance_of(entry.obj.as_obj(), &r.cls)?
      }
      _ => false,
    })
  }

  fn boxed_value<'l>(
    env: &mut Env<'l>,
    known: &WellKnown,
    kind: Primitive,
    value: JValue,
  ) -> jni::errors::Result<JObject<'l>> {
    let (_, cls, value_of, _) = known.boxed(kind);
    // SAFETY: `valueOf` of the box for `kind`, handed a `kind` value
    unsafe { env.call_static_method_unchecked(cls, *value_of, JavaType::Object, &[value.as_jni()]) }?.l()
  }

  /// `PluginJvm.convert` for one argument, after `matches` said it fits
  fn prepare<'l>(
    &self,
    env: &mut Env<'l>,
    known: &WellKnown,
    param: &ParamKind,
    arg: &Arg<'_>,
  ) -> OpResult<Prepared<'l>> {
    let boxed = |env: &mut Env<'l>, kind, value| Self::boxed_value(env, known, kind, value).map(Prepared::Local);
    Ok(match (param, arg) {
      (_, Arg::Null) => Prepared::Null,
      (ParamKind::Prim(_), Arg::Bool(b)) => Prepared::Primitive(JValue::Bool(*b)),
      (ParamKind::Prim(p), Arg::Int(v)) => Prepared::Primitive(int_value(*p, *v)),
      (ParamKind::Prim(p), Arg::Double(v)) => Prepared::Primitive(float_value(*p, *v)),
      (ParamKind::Prim(_), Arg::Str(s)) => Prepared::Primitive(JValue::Char(first_code_unit(s))),
      (ParamKind::Ref(_), Arg::Bool(b)) => boxed(env, Primitive::Boolean, JValue::Bool(*b))?,
      (ParamKind::Ref(r), Arg::Int(v)) => {
        let kind = r.exact_box.unwrap_or_else(|| int_box(*v));
        boxed(env, kind, int_value(kind, *v))?
      }
      (ParamKind::Ref(r), Arg::Double(v)) => {
        let kind = if r.exact_box == Some(Primitive::Float) { Primitive::Float } else { Primitive::Double };
        boxed(env, kind, float_value(kind, *v))?
      }
      (ParamKind::Ref(r), Arg::Str(s)) if r.exact_box == Some(Primitive::Char) => {
        boxed(env, Primitive::Char, JValue::Char(first_code_unit(s)))?
      }
      (ParamKind::Ref(_), Arg::Str(s)) => Prepared::Local(JObject::from(JString::new(env, s)?)),
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
      (ParamKind::Prim(pa), ParamKind::Prim(pb)) => Ok(pa == pb),
      _ => Ok(false),
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
          ParamKind::Prim(p) => *p == Primitive::Char,
          ParamKind::Ref(r) => r.exact_box == Some(Primitive::Char),
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
      Arg::Int(v) => ArgShape::Int(
        [Primitive::Byte, Primitive::Short, Primitive::Int, Primitive::Char]
          .iter()
          .enumerate()
          .fold(0, |widths, (bit, p)| widths | (u8::from(int_fits(*p, *v)) << bit)),
      ),
      Arg::Double(v) => ArgShape::Double(float_fits(Primitive::Float, *v)),
      Arg::Str(s) => ArgShape::Str(is_one_code_unit(s)),
      Arg::Bytes(_) => ArgShape::Bytes,
      Arg::Ref(handle) => {
        let entry = self.get_live_entry(ctx, handle)?;
        let key = self.resolve_class_key(env, known, handle, &entry)?;
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
      return PluginErrorCode::NotFound.throw(ctx, &format!("jvm: {} was not found", what())).map_err(OpError::Js);
    }
    let mut fitting = Vec::new();
    for candidate in candidates {
      if self.fits(ctx, env, candidate, args)? {
        fitting.push(candidate.clone());
      }
    }
    if fitting.is_empty() {
      if pinned {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("jvm: {} does not take these arguments", what()))
          .map_err(OpError::Js);
      }
      return PluginErrorCode::NotFound
        .throw(ctx, &format!("jvm: no {} takes {} argument(s) of these types", what(), args.len()))
        .map_err(OpError::Js);
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
      return PluginErrorCode::InvalidArgument
        .throw(
          ctx,
          &format!("jvm: {} is ambiguous for these arguments; pin one with a descriptor, e.g. {examples}", what()),
        )
        .map_err(OpError::Js);
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
    // SAFETY: `fits` checked every argument against its parameter and `prepare` converted it to that
    // parameter's type, the id was resolved for this owner, and a receiver is checked against the owner
    // before it reaches here
    if matches!(candidate.id, MethodId::Instance(_)) && receiver.is_none() {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "jvm: an instance method needs a receiver")
        .map_err(OpError::Js);
    }
    let called = crate::jni::lend_engine(|| unsafe {
      match (&candidate.id, receiver) {
        (MethodId::Constructor(id), _) => {
          env.new_object_unchecked(&candidate.owner, *id, &values).map(JValueOwned::Object)
        }
        (MethodId::Static(id), _) => env.call_static_method_unchecked(&candidate.owner, *id, candidate.ret, &values),
        (MethodId::Instance(_), None) => unreachable!(),
        (MethodId::Instance(id), Some(receiver)) => match dispatch {
          Dispatch::Virtual => env.call_method_unchecked(receiver, *id, candidate.ret, &values),
          Dispatch::Nonvirtual => {
            let owner: &JClass = &candidate.owner;
            env.call_nonvirtual_method_unchecked(receiver, owner, *id, candidate.ret, &values)
          }
        },
      }
    });
    drop(prepared);
    match called {
      Ok(value) => Ok(value),
      Err(jni::errors::Error::JavaException) => self.throw_java(ctx, env, known),
      Err(error) => Err(OpError::Jni(error)),
    }
  }

  /// Lookup skips class initialization, so the first static use initializes it, as reflection does.
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
    let name = call_object(env, owner, known.class_get_name)?;
    let loader = call_object(env, owner, known.class_get_class_loader)?;
    let args = [JValue::Object(&name).as_jni(), JValue::Bool(true).as_jni(), JValue::Object(&loader).as_jni()];
    // SAFETY: `Class.forName(String, boolean, ClassLoader)`, handed exactly those
    let loaded = crate::jni::lend_engine(|| unsafe {
      env.call_static_method_unchecked(&known.class, known.class_for_name, JavaType::Object, &args)
    });
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
      return PluginErrorCode::Internal
        .throw(ctx, "jvm: java reported an exception that is not there")
        .map_err(OpError::Js);
    };
    env.exception_clear();
    let text = Self::describe(env, known, &thrown).unwrap_or_else(|| "java exception".to_string());
    throw_wire(ctx, &format!("E{text}"))
  }

  fn describe(env: &mut Env, known: &WellKnown, thrown: &JThrowable) -> Option<String> {
    let text = match call_object(env, thrown, known.to_string) {
      Ok(text) => text,
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
    // SAFETY: the id was resolved for the owner with the field's own type, and a receiver is checked
    // against the owner before it reaches here
    let read = unsafe {
      match &plan.id {
        FieldId::Static(id) => env.get_static_field_unchecked(&plan.owner, *id, plan.ty.java_type()),
        FieldId::Instance(id) => {
          let Some(receiver) = receiver else {
            return PluginErrorCode::InvalidArgument
              .throw(ctx, "jvm: an instance field needs a receiver")
              .map_err(OpError::Js);
          };
          env.get_field_unchecked(receiver, *id, plan.ty.java_type())
        }
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
      return PluginErrorCode::Forbidden
        .throw(ctx, &format!("jvm: {}.{} is final", plan.owner_name, plan.name))
        .map_err(OpError::Js);
    }
    self.ensure_initialized(ctx, env, known, &plan.owner, &plan.initialized)?;
    if !self.matches(ctx, env, &plan.ty, value)? {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, &format!("jvm: cannot assign that to a {}", plan.type_name))
        .map_err(OpError::Js);
    }
    let prepared = self.prepare(env, known, &plan.ty, value)?;
    // SAFETY: as in `get_field`, with the value `matches` accepted and `prepare` converted to the field's type
    unsafe {
      match &plan.id {
        FieldId::Static(id) => env.set_static_field_unchecked(&plan.owner, *id, prepared.borrow())?,
        FieldId::Instance(id) => {
          let Some(receiver) = receiver else {
            return PluginErrorCode::InvalidArgument
              .throw(ctx, "jvm: an instance field needs a receiver")
              .map_err(OpError::Js);
          };
          env.set_field_unchecked(receiver, *id, prepared.borrow())?
        }
      }
    }
    drop(prepared);
    Ok(())
  }

  fn unbox<'l>(
    env: &mut Env<'l>,
    known: &WellKnown,
    kind: Primitive,
    obj: &JObject,
  ) -> jni::errors::Result<JValueOwned<'l>> {
    let unbox = known.boxed(kind).3;
    // SAFETY: `<kind>Value()` of the box for `kind`, called on an instance of that box
    unsafe { env.call_method_unchecked(obj, unbox, JavaType::Primitive(kind), &[]) }
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
    let own_key = self.intern_class_key(env, known, cls.as_ref())?;
    let (kind, checked_key) = match self.resolve_class_shape(env, known, own_key)? {
      Shape::String => {
        let text = Self::read_string(env, &obj)?.unwrap_or_default();
        if text.len() > VALUE_LIMIT_BYTES {
          return super::throw_too_big(ctx, "a string", text.len(), VALUE_LIMIT_BYTES).map_err(OpError::Js);
        }
        return Ok(Outcome::Value(text.into_js(ctx)?));
      }
      Shape::Boxed(kind) => {
        let unboxed = Self::unbox(env, known, kind, &obj)?;
        return Ok(Outcome::Value(Self::scalar_to_js(ctx, unboxed)?.expect("unboxed to a scalar")));
      }
      Shape::Bytes => {
        // SAFETY: `resolve_class_shape` found the object's class assignable to `byte[]`
        let array = unsafe { JByteArray::from_raw(env, obj.as_raw() as jni::sys::jbyteArray) };
        let bytes = env.convert_byte_array(&array)?;
        if bytes.len() > VALUE_LIMIT_BYTES {
          return super::throw_too_big(ctx, "a byte[]", bytes.len(), VALUE_LIMIT_BYTES).map_err(OpError::Js);
        }
        return Ok(Outcome::Value(crate::api::tl::proxy::make_bytes_value(ctx, &bytes)?));
      }
      // a `Class` is checked as the class it *names*, and a member by the class it declares
      Shape::Class => (KIND_CLASS, self.intern_class_key(env, known, &obj)?),
      member @ (Shape::Method | Shape::Constructor | Shape::Field) => {
        let kind = match member {
          Shape::Method => KIND_METHOD,
          Shape::Constructor => KIND_CONSTRUCTOR,
          _ => KIND_FIELD,
        };
        let declaring = Self::declaring_class(env, known, &obj)?;
        (kind, self.intern_class_key(env, known, declaring.as_obj())?)
      }
      Shape::Object => (KIND_OBJECT, own_key),
    };
    let global = env.new_global_ref(&obj)?;
    let id = self.mint_ref(ctx, global, kind)?;
    let class_key = if kind == KIND_CLASS || kind == KIND_OBJECT { Some(checked_key) } else { None };
    Ok(Outcome::Handle(HandleSpec { id, kind, class_key, pinned: None }))
  }

  fn get_receiver(entry: &Entry) -> Option<&JObject<'static>> {
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
      let entry = self.get_live_entry(ctx, target)?;
      let key = self.resolve_class_key(env, known, target, &entry)?;
      let plan = self.method_plan(ctx, env, known, key, name, false)?;
      let what = || format!("{}.{}", plan.class_name, name.split('(').next().unwrap_or(name));
      let candidate = self.pick(ctx, env, known, &plan, what, name.contains('('), entry.kind == KIND_CLASS, args)?;
      let value = self.invoke(ctx, env, known, &candidate, Self::get_receiver(&entry), Dispatch::Virtual, args)?;
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
      // SAFETY: see the function doc
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
      let cls = self.get_class(env, key)?;
      let receiver = match receiver {
        Arg::Ref(handle) => self.get_live_entry(ctx, handle)?,
        _ => {
          return PluginErrorCode::InvalidArgument
            .throw(ctx, "jvm: callSuper needs a java object to call on")
            .map_err(OpError::Js)
        }
      };
      if !env.is_instance_of(receiver.obj.as_obj(), &cls)? {
        let class_name = Self::class_name(env, known, &cls)?;
        let message = format!("jvm: that receiver is not an instance of {class_name}");
        return PluginErrorCode::InvalidArgument.throw(ctx, &message).map_err(OpError::Js);
      }
      let Some(parent) = env.get_superclass(&cls)? else {
        let class_name = Self::class_name(env, known, &cls)?;
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("jvm: {class_name} has no superclass"))
          .map_err(OpError::Js);
      };
      let parent_key = self.intern_class_key(env, known, &parent)?;
      let plan = self.method_plan(ctx, env, known, parent_key, name, false)?;
      let what = || format!("{}.{}", plan.class_name, name.split('(').next().unwrap_or(name));
      let candidate = self.pick(ctx, env, known, &plan, what, name.contains('('), false, args)?;
      if candidate.is_abstract {
        return PluginErrorCode::InvalidArgument
          .throw(
            ctx,
            &format!(
              "jvm: {}{} is abstract, so there is no super implementation to call",
              what(),
              candidate.descriptor
            ),
          )
          .map_err(OpError::Js);
      }
      let value = self.invoke(ctx, env, known, &candidate, Some(receiver.obj.as_obj()), Dispatch::Nonvirtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  pub(crate) fn get<'js>(&self, ctx: &Ctx<'js>, target: &Class<'js, JvmRef>, name: &str) -> JsResult<Outcome<'js>> {
    self.with_env(ctx, |env, known| {
      let entry = self.get_live_entry(ctx, target)?;
      let key = self.resolve_class_key(env, known, target, &entry)?;
      let plan = self.field_plan(ctx, env, known, key, name)?;
      let value = self.get_field(ctx, env, known, &plan, Self::get_receiver(&entry))?;
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
      let entry = self.get_live_entry(ctx, target)?;
      let key = self.resolve_class_key(env, known, target, &entry)?;
      let plan = self.field_plan(ctx, env, known, key, name)?;
      self.set_field(ctx, env, known, &plan, Self::get_receiver(&entry), value)
    })
  }

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
        return PluginErrorCode::NotFound.throw(ctx, &format!("jvm: {what} was not found")).map_err(OpError::Js);
      }
      if plan.candidates.len() > 1 {
        let examples = plan.candidates.iter().take(3).map(|c| c.descriptor.clone()).collect::<Vec<_>>().join(", ");
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("jvm: {what} is overloaded; pin one with a descriptor, e.g. {examples}"))
          .map_err(OpError::Js);
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

  /// A pinned plan may come from another class, so the receiver is checked before calling through its id.
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
        let entry = self.get_live_entry(ctx, handle)?;
        if entry.kind == KIND_CLASS {
          return PluginErrorCode::InvalidArgument.throw(ctx, "jvm: a class is not a receiver").map_err(OpError::Js);
        }
        if let Some((owner, what)) = instance_owner {
          if !env.is_instance_of(entry.obj.as_obj(), owner)? {
            return PluginErrorCode::InvalidArgument
              .throw(ctx, &format!("jvm: that receiver is not an instance of the class declaring {what}"))
              .map_err(OpError::Js);
          }
        }
        Ok(Some(entry))
      }
      _ => PluginErrorCode::InvalidArgument
        .throw(ctx, "jvm: the receiver must be a java object or null")
        .map_err(OpError::Js),
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
      let entry = self.get_live_entry(ctx, target)?;
      if entry.kind != KIND_METHOD && entry.kind != KIND_CONSTRUCTOR {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, "jvm: that handle is not a method or constructor")
          .map_err(OpError::Js);
      }
      let pinned = self.get_pinned(ctx, env, known, target, &entry)?;
      let Pinned::Method(candidate) = &*pinned else {
        return PluginErrorCode::InvalidArgument
          .throw(ctx, "jvm: that handle is not a method or constructor")
          .map_err(OpError::Js);
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
        return PluginErrorCode::InvalidArgument
          .throw(ctx, &format!("jvm: {what} does not take these arguments"))
          .map_err(OpError::Js);
      }
      let receiver_obj = receiver.as_ref().map(|entry| entry.obj.as_obj());
      let value = self.invoke(ctx, env, known, candidate, receiver_obj, Dispatch::Virtual, args)?;
      self.result_to_js(ctx, env, known, value)
    })
  }

  pub(crate) fn is_instance<'js>(
    &self,
    ctx: &Ctx<'js>,
    target: &Class<'js, JvmRef>,
    value: &Arg<'js>,
  ) -> JsResult<bool> {
    self.with_env(ctx, |env, known| {
      let entry = self.get_live_entry(ctx, target)?;
      if entry.kind != KIND_CLASS {
        return PluginErrorCode::InvalidArgument.throw(ctx, "jvm: isInstance needs a class").map_err(OpError::Js);
      }
      // resolved for its own sake: it is what caches the key and pins the scope check on the class
      let _ = self.resolve_class_key(env, known, target, &entry)?;
      let Arg::Ref(handle) = value else {
        return Ok(false);
      };
      let subject = self.get_live_entry(ctx, handle)?;
      let cls = env.new_cast_local_ref::<JClass>(entry.obj.as_obj())?;
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
