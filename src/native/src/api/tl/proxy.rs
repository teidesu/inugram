use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use base64::engine::general_purpose::STANDARD;
use rquickjs::atom::PredefinedAtom;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::function::This;
use rquickjs::proxy::ProxyHandler;
use rquickjs::{
  Array, Class, Constructor, Ctx, Exception, Filter, Function, IntoJs, JsLifetime, Object, Proxy, Result as JsResult,
  Symbol, TypedArray, Value,
};

use crate::api::error::PluginErrorCode;

const BYTES_MARKER_KEY: &str = "$inuBytes";

/// what [`TlHost::tl_read_field`] answers when it will not serve a read, sending it back to `tl_get`
pub const ORDINAL_FALLBACK: i32 = -1;

/// tags in the read buffer, mirrored in `TlHandles.kt`
mod tag {
  pub const NULL: u8 = 0;
  pub const BOOL: u8 = 1;
  pub const INT: u8 = 2;
  pub const LONG: u8 = 3;
  pub const DOUBLE: u8 = 4;
  pub const STRING: u8 = 5;
  pub const BYTES: u8 = 6;
  pub const HANDLE: u8 = 7;
  pub const HANDLE_PROJECTED: u8 = 8;
  pub const INT53: u8 = 9;
}

pub trait TlHost {
  fn tl_get(&self, handle: i64, key: &str) -> String;

  /// the ordinal [`Self::tl_read_field`] takes for this field, or [`ORDINAL_FALLBACK`]
  fn tl_resolve_field(&self, _class_id: i32, _key: &str) -> i32 {
    ORDINAL_FALLBACK
  }

  /// bytes written into [`Self::read_buffer`], or [`ORDINAL_FALLBACK`] to read it by name instead
  fn tl_read_field(&self, _handle: i64, _class_id: i32, _ordinal: i32) -> i32 {
    ORDINAL_FALLBACK
  }

  /// the host's reply buffer, valid until the next call into the host
  fn read_buffer(&self) -> &[u8] {
    &[]
  }
  fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String>;
  /// the write half of `TAG_BYTES`: a `Uint8Array` assignment goes over as a java `byte[]` rather
  /// than base64 in [`Self::tl_set`]'s wire, which is what reads have always done
  fn tl_set_bytes(&self, handle: i64, key: &str, value: &[u8]) -> Option<String>;
  fn tl_has(&self, handle: i64, key: &str) -> i32;
  fn tl_own_keys(&self, handle: i64) -> Option<String>;
  fn tl_copy(&self, handle: i64) -> Option<String>;
  fn tl_release(&self, handle: i64);
}

/// Stores each view's state and cache on its proxy target. All views in a context share one handler
/// ([`TlShared`]), which reads state from the target instead of a per-view closure. Each handle
/// allocates a target and a proxy.
struct HandleBox<'js> {
  views: Rc<TlViews>,
  handle: i64,
  is_vector: bool,
  read_only: bool,
  life: ViewLife,
  /// what the handle's wire named, or [`ORDINAL_FALLBACK`] when it named nothing
  class_id: i32,
  stamp: Cell<u64>,
  /// what survives a write anywhere: the type name and the `toJSON` function
  perm: RefCell<Option<Object<'js>>>,
  /// values under the field's name, presence under [`HAS_PREFIX`] + name, own keys under [`KEYS_ENTRY`]
  vol: RefCell<Option<Object<'js>>>,
}

impl Drop for HandleBox<'_> {
  fn drop(&mut self) {
    self.views.host.tl_release(self.handle);
  }
}

impl<'js> Trace<'js> for HandleBox<'js> {
  fn trace<'a>(&self, tracer: Tracer<'a, 'js>) {
    if let Ok(perm) = self.perm.try_borrow() {
      perm.trace(tracer);
    }
    if let Ok(vol) = self.vol.try_borrow() {
      vol.trace(tracer);
    }
  }
}

// SAFETY: every JavaScript-lifetime-bound field uses the struct's `'js` lifetime.
unsafe impl<'js> JsLifetime<'js> for HandleBox<'js> {
  type Changed<'to> = HandleBox<'to>;
}

impl<'js> JsClass<'js> for HandleBox<'js> {
  const NAME: &'static str = "TlHandle";
  type Mutable = Readable;

  fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
    Ok(None)
  }
}

/// per context: the one handler every view shares, and the symbols the traps compare against
struct TlShared<'js> {
  handler: Object<'js>,
  marker: Symbol<'js>,
}

// SAFETY: every JavaScript-lifetime-bound field uses the struct's `'js` lifetime.
unsafe impl<'js> JsLifetime<'js> for TlShared<'js> {
  type Changed<'to> = TlShared<'to>;
}

/// Release the handler and marker before freeing the context. They are JS values in runtime
/// userdata, which `UserDataMap::clear` would otherwise drop too late.
pub fn dispose_tl_shared(ctx: &Ctx<'_>) {
  drop(ctx.remove_userdata::<TlShared>());
}

impl<'js> TlShared<'js> {
  fn get(ctx: &Ctx<'js>) -> JsResult<(Object<'js>, Symbol<'js>)> {
    if let Some(shared) = ctx.userdata::<TlShared<'js>>() {
      return Ok((shared.handler.clone(), shared.marker.clone()));
    }
    let marker = Symbol::new_global(ctx.clone(), HANDLE_MARKER_DESCRIPTION)?;
    let handler = build_handler(ctx)?;
    if ctx
      .store_userdata(TlShared {
        handler: handler.clone(),
        marker: marker.clone(),
      })
      .is_err()
    {
      return throw_tl(ctx, "tl proxy: the shared handler could not be installed");
    }
    Ok((handler, marker))
  }

  fn marker(ctx: &Ctx<'js>) -> JsResult<Symbol<'js>> {
    Ok(Self::get(ctx)?.1)
  }
}

pub struct TlViews {
  host: Rc<dyn TlHost>,
  epoch: Cell<u64>,
  /// `(class, field) -> ordinal`, a refusal included: neither answer ever changes for a class
  ordinals: RefCell<HashMap<i32, HashMap<String, i32>>>,
}

impl TlViews {
  pub fn new(host: Rc<dyn TlHost>) -> Rc<Self> {
    Rc::new(Self {
      host,
      epoch: Cell::new(0),
      ordinals: RefCell::new(HashMap::new()),
    })
  }

  fn epoch(&self) -> u64 {
    self.epoch.get()
  }

  fn ordinal_of(&self, host: &dyn TlHost, class_id: i32, key: &str) -> i32 {
    if let Some(known) = self.ordinals.borrow().get(&class_id).and_then(|fields| fields.get(key)) {
      return *known;
    }
    let ordinal = host.tl_resolve_field(class_id, key);
    self.ordinals.borrow_mut().entry(class_id).or_default().insert(key.to_string(), ordinal);
    ordinal
  }

  fn bump(&self) {
    self.epoch.set(self.epoch.get() + 1);
  }

  /// every element of a `\n`-joined list wire, each read as [`Self::wire_to_js_value`] reads one
  pub fn wire_to_js_list<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, wire: &str, life: ViewLife) -> JsResult<Array<'js>> {
    let array = Array::new(ctx.clone())?;
    if wire.is_empty() {
      return Ok(array);
    }
    for (index, element) in wire.split('\n').enumerate() {
      array.set(index, self.wire_to_js_value(ctx, element, life)?)?;
    }
    Ok(array)
  }

  pub fn wire_to_js_value<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, wire: &str, life: ViewLife) -> JsResult<Value<'js>> {
    if let Some(built) = crate::api::error::wire_error_to_js(ctx, wire) {
      return Err(ctx.throw(built?));
    }
    let mut chars = wire.chars();
    let Some(tag) = chars.next() else {
      return throw_tl(ctx, "tl wire: empty value");
    };
    let payload = chars.as_str();
    if let Some(scalar) = scalar_wire_to_js(ctx, tag, payload) {
      return scalar;
    }
    match tag {
      'H' => {
        let (is_vector, read_only, id, class_id, projection) =
          parse_handle(payload).ok_or_else(|| Exception::throw_message(ctx, "tl wire: bad handle"))?;
        build_view(ctx, self.clone(), is_vector, read_only, life, id, class_id, projection)?.into_js(ctx)
      }
      'J' => json_parse_tl(ctx, payload),
      other => throw_tl(ctx, &format!("tl wire: unknown tag '{other}'")),
    }
  }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ViewLife {
  Dispatch,
  Plugin,
}

impl HandleBox<'_> {
  fn cacheable(&self) -> bool {
    self.life == ViewLife::Plugin && !self.is_vector
  }

  fn host(&self) -> &dyn TlHost {
    self.views.host.as_ref()
  }
}

const HANDLE_EXPIRED_MESSAGE: &str = "TL handle expired. Copy fields you need before returning";

const READ_ONLY_MESSAGE: &str = "this TL view is read-only; take a copy with toJSON() to edit it";

const DESCRIPTOR_MESSAGE: &str =
  "a TL view only supports plain value assignment; defineProperty needs a data descriptor carrying a value, and cannot change a field's attributes";

const NOT_EXTENSIBLE_MESSAGE: &str = "a TL view cannot be sealed or frozen; take a copy with toJSON() to freeze it";

const HANDLE_MARKER_DESCRIPTION: &str = "inu.tl.handle";

/// a field name never starts with one, so presence and values share the one cache object
const HAS_PREFIX: char = '#';
const RESERVED_PREFIX: char = '@';
const KEYS_ENTRY: &str = "@keys";
const TYPE_KEY: &str = "_";
const TO_JSON_KEY: &str = "toJSON";
const THEN_KEY: &str = "then";

/// `HOR12` encodes a handle. `HOR12|{...}` also includes scalar fields as JSON for reads without
/// bridge calls. Kotlin writes this with `PluginWire.encodeHandle`.
const PROJECTION_SEPARATOR: char = '|';

/// mirrored by `PluginWire.CLASS_SEPARATOR`
const CLASS_SEPARATOR: char = '.';

fn encode_handle(is_vector: bool, read_only: bool, id: i64) -> String {
  format!("H{}{}{}", if is_vector { 'V' } else { 'O' }, if read_only { 'R' } else { 'W' }, id)
}

fn parse_handle(payload: &str) -> Option<(bool, bool, i64, i32, Option<&str>)> {
  let mut chars = payload.chars();
  let is_vector = match chars.next()? {
    'O' => false,
    'V' => true,
    _ => return None,
  };
  let read_only = match chars.next()? {
    'W' => false,
    'R' => true,
    _ => return None,
  };
  let rest = chars.as_str();
  let (id, projection) = match rest.split_once(PROJECTION_SEPARATOR) {
    Some((id, projection)) => (id, Some(projection)),
    None => (rest, None),
  };
  // the class id is written after the id, so a wire from a minter that names no class parses here
  // exactly as it did before there was one
  let (id, class_id) = match id.split_once(CLASS_SEPARATOR) {
    Some((id, class_id)) => (id, class_id.parse().ok()?),
    None => (id, ORDINAL_FALLBACK),
  };
  Some((is_vector, read_only, id.parse().ok()?, class_id, projection))
}

/// reads what [`TlHost::tl_read_field`] wrote. Every length it trusts was written by the host beside
/// the bytes it counts, so a short read is a bug on that side rather than a plugin's doing
struct Reader<'a> {
  bytes: &'a [u8],
  at: usize,
}

impl<'a> Reader<'a> {
  fn take(&mut self, count: usize) -> Option<&'a [u8]> {
    let end = self.at.checked_add(count)?;
    let slice = self.bytes.get(self.at..end)?;
    self.at = end;
    Some(slice)
  }

  fn u8(&mut self) -> Option<u8> {
    self.take(1).map(|b| b[0])
  }

  fn i32(&mut self) -> Option<i32> {
    self.take(4).and_then(|b| b.try_into().ok()).map(i32::from_le_bytes)
  }

  fn i64(&mut self) -> Option<i64> {
    self.take(8).and_then(|b| b.try_into().ok()).map(i64::from_le_bytes)
  }

  fn f64(&mut self) -> Option<f64> {
    self.take(8).and_then(|b| b.try_into().ok()).map(f64::from_le_bytes)
  }

  fn str(&mut self) -> Option<&'a str> {
    let len = self.i32()?;
    std::str::from_utf8(self.take(usize::try_from(len).ok()?)?).ok()
  }
}

fn decode_read<'js>(ctx: &Ctx<'js>, views: &Rc<TlViews>, life: ViewLife, bytes: &[u8]) -> JsResult<Value<'js>> {
  let mut reader = Reader { bytes, at: 0 };
  let bad = || Exception::throw_message(ctx, "tl read: malformed reply");
  let tag = reader.u8().ok_or_else(bad)?;
  match tag {
    tag::NULL => Ok(Value::new_null(ctx.clone())),
    tag::BOOL => Ok(Value::new_bool(ctx.clone(), reader.u8().ok_or_else(bad)? != 0)),
    tag::INT => reader.i32().ok_or_else(bad)?.into_js(ctx),
    // a tl long is a string on this surface, as `android.tl.d.ts` types it, unless it is one that fits in a js number
    tag::LONG => reader.i64().ok_or_else(bad)?.to_string().into_js(ctx),
    tag::INT53 => (reader.i64().ok_or_else(bad)? as f64).into_js(ctx),
    tag::DOUBLE => reader.f64().ok_or_else(bad)?.into_js(ctx),
    tag::STRING => reader.str().ok_or_else(bad)?.into_js(ctx),
    tag::BYTES => {
      let len = reader.i32().ok_or_else(bad)?;
      let bytes = reader.take(usize::try_from(len).map_err(|_| bad())?).ok_or_else(bad)?;
      make_bytes_value(ctx, bytes)
    }
    tag::HANDLE | tag::HANDLE_PROJECTED => {
      let flags = reader.u8().ok_or_else(bad)?;
      let id = reader.i64().ok_or_else(bad)?;
      let class_id = reader.i32().ok_or_else(bad)?;
      let projection = if tag == tag::HANDLE_PROJECTED { Some(reader.str().ok_or_else(bad)?) } else { None };
      build_view(ctx, views.clone(), flags & 1 != 0, flags & 2 != 0, life, id, class_id, projection)?.into_js(ctx)
    }
    _ => Err(bad()),
  }
}

pub fn encode_error(message: &str) -> String {
  format!("E{message}")
}

pub fn encode_rpc_error(code: i32, text: &str) -> String {
  format!("R{code}:{text}")
}

pub fn wire_rpc_error(wire: &str) -> Option<(i32, &str)> {
  let payload = wire.strip_prefix('R')?;
  let (code, text) = payload.split_once(':')?;
  Some((code.parse().ok()?, text))
}

fn throw_tl<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
  Err(Exception::throw_message(ctx, message))
}

fn descriptor_value<'js>(ctx: &Ctx<'js>, descriptor: &Value<'js>) -> JsResult<Value<'js>> {
  let Some(obj) = descriptor.as_object() else {
    return PluginErrorCode::Unsupported.throw(ctx, DESCRIPTOR_MESSAGE);
  };
  let mut value = None;
  for entry in obj.own_props::<String, Value>(Filter::new().string()) {
    let (attribute, given) = entry?;
    match attribute.as_str() {
      "value" => value = Some(given),
      "writable" | "enumerable" | "configurable" if given.as_bool() == Some(true) => {}
      _ => return PluginErrorCode::Unsupported.throw(ctx, DESCRIPTOR_MESSAGE),
    }
  }
  match value {
    Some(value) => Ok(value),
    None => PluginErrorCode::Unsupported.throw(ctx, DESCRIPTOR_MESSAGE),
  }
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
  base64::Engine::decode(&STANDARD, s).ok()
}

/// Generate base64 from `this` only when `toJSON` is called. Eagerly encoding an `invokeRaw`
/// response would scan all bytes and retain a string 4/3 the array's size.
pub(crate) fn make_bytes_value<'js>(ctx: &Ctx<'js>, bytes: &[u8]) -> JsResult<Value<'js>> {
  let arr = TypedArray::<u8>::new_copy(ctx.clone(), bytes)?;
  let to_json =
    Function::new(ctx.clone(), |ctx: Ctx<'js>, this: This<TypedArray<'js, u8>>| -> JsResult<Object<'js>> {
      let wrapper = Object::new(ctx.clone())?;
      let Some(bytes) = this.0.as_bytes() else {
        return Err(Exception::throw_type(&ctx, "these bytes are gone: their buffer was detached"));
      };
      let b64 = base64::Engine::encode(&STANDARD, bytes);
      wrapper.set(BYTES_MARKER_KEY, b64.as_str())?;
      Ok(wrapper)
    })?;
  arr.set(TO_JSON_KEY, to_json)?;
  arr.into_js(ctx)
}

fn revive_bytes<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Value<'js>> {
  let Some(obj) = value.as_object() else {
    return Ok(value);
  };
  if let Some(arr) = obj.clone().into_array() {
    for idx in 0..arr.len() {
      let item: Value = arr.get(idx)?;
      let revived = revive_bytes(ctx, item)?;
      arr.set(idx, revived)?;
    }
    return Ok(value);
  }
  let keys: Vec<String> = obj.own_keys(Filter::new().string().enum_only()).collect::<JsResult<_>>()?;
  if keys.iter().any(|k| k == BYTES_MARKER_KEY) {
    return match obj.get::<_, Option<String>>(BYTES_MARKER_KEY)?.as_deref().and_then(base64_decode) {
      Some(bytes) => make_bytes_value(ctx, &bytes),
      None => Ok(value),
    };
  }
  for key in keys {
    let item: Value = obj.get(key.as_str())?;
    let revived = revive_bytes(ctx, item)?;
    obj.set(key.as_str(), revived)?;
  }
  Ok(value)
}

pub(crate) fn json_parse_tl<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
  let parsed = ctx.json_parse(json)?;
  revive_bytes(ctx, parsed)
}

pub(crate) fn json_stringify_tl<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
  let replacer =
    Function::new(ctx.clone(), |ctx: Ctx<'js>, _key: Value<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
      if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        if let Some(bytes) = typed.as_bytes() {
          let wrapper = Object::new(ctx.clone())?;
          wrapper.set(BYTES_MARKER_KEY, base64::Engine::encode(&STANDARD, bytes))?;
          return wrapper.into_js(&ctx);
        }
      }
      Ok(value)
    })?;
  match ctx.json_stringify_replacer(value, replacer)? {
    Some(json) => json.to_string(),
    None => Err(Exception::throw_message(ctx, "tl wire: value has no JSON representation")),
  }
}

/// a value wire that names no handle: a scalar, or `J` and its json
pub(crate) fn plain_wire_to_js<'js>(ctx: &Ctx<'js>, wire: &str) -> JsResult<Value<'js>> {
  let Some(tag) = wire.chars().next() else {
    return Err(Exception::throw_message(ctx, "wire: empty value"));
  };
  let payload = &wire[tag.len_utf8()..];
  if tag == 'J' {
    return ctx.json_parse(payload);
  }
  scalar_wire_to_js(ctx, tag, payload)
    .unwrap_or_else(|| Err(Exception::throw_message(ctx, &format!("wire: '{tag}' is not a plain value"))))
}

pub(crate) fn scalar_wire_to_js<'js>(ctx: &Ctx<'js>, tag: char, payload: &str) -> Option<JsResult<Value<'js>>> {
  Some(match tag {
    'N' => Ok(Value::new_null(ctx.clone())),
    'S' => payload.into_js(ctx),
    'I' => payload
      .parse::<i64>()
      .map_err(|_| Exception::throw_message(ctx, "tl wire: bad int"))
      .and_then(|n| n.into_js(ctx)),
    'D' => payload
      .parse::<f64>()
      .map_err(|_| Exception::throw_message(ctx, "tl wire: bad double"))
      .and_then(|n| n.into_js(ctx)),
    'B' => Ok(Value::new_bool(ctx.clone(), payload == "1")),
    'Y' => match base64::Engine::decode(&STANDARD, payload).ok() {
      Some(bytes) => make_bytes_value(ctx, &bytes),
      None => Err(Exception::throw_message(ctx, "tl wire: bad base64")),
    },
    _ => return None,
  })
}

/// the `Y<base64>` wire a byte string crosses as
pub fn encode_bytes_wire(bytes: &[u8]) -> String {
  format!("Y{}", base64::Engine::encode(&STANDARD, bytes))
}

pub fn js_value_to_wire<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
  if value.is_null() {
    return Ok("N".to_string());
  }
  if let Some(handle_wire) = try_read_marker(ctx, &value)? {
    return Ok(handle_wire);
  }
  if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
    if let Some(bytes) = typed.as_bytes() {
      return Ok(encode_bytes_wire(bytes));
    }
  }
  let json = json_stringify_tl(ctx, value)?;
  Ok(format!("J{json}"))
}

fn try_read_marker<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Option<String>> {
  let Some(obj) = value.as_object() else {
    return Ok(None);
  };
  let key = TlShared::marker(ctx)?;
  match obj.get::<_, Value>(key.as_atom()) {
    Ok(v) => Ok(v.as_string().and_then(|s| s.to_string().ok())),
    Err(_) => Ok(None),
  }
}

fn new_section<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  Object::new_proto(ctx.clone(), None)
}

fn open_section<'js>(ctx: &Ctx<'js>, section: &RefCell<Option<Object<'js>>>) -> JsResult<Object<'js>> {
  if let Some(open) = section.borrow().as_ref() {
    return Ok(open.clone());
  }
  let open = new_section(ctx)?;
  *section.borrow_mut() = Some(open.clone());
  Ok(open)
}

fn read_section<'js>(section: &RefCell<Option<Object<'js>>>, key: &str) -> JsResult<Option<Value<'js>>> {
  let section = section.borrow();
  let Some(section) = section.as_ref() else {
    return Ok(None);
  };
  if section.contains_key(key)? {
    return Ok(Some(section.get(key)?));
  }
  Ok(None)
}

impl<'js> HandleBox<'js> {
  fn perm(&self, ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    open_section(ctx, &self.perm)
  }

  fn vol(&self, ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    open_section(ctx, &self.vol)
  }

  /// the names the cache and the wire use for themselves; a TL field is a java identifier and is none of them
  fn reserved(key: &str) -> bool {
    key.starts_with(HAS_PREFIX) || key.starts_with(RESERVED_PREFIX)
  }

  fn cached(&self, key: &str) -> JsResult<Option<Value<'js>>> {
    read_section(&self.vol, key)
  }

  fn cached_perm(&self, key: &str) -> JsResult<Option<Value<'js>>> {
    read_section(&self.perm, key)
  }

  /// a write anywhere invalidates every value: two views may name the same object
  fn sync_epoch(&self) {
    if !self.cacheable() {
      return;
    }
    let epoch = self.views.epoch();
    if self.stamp.get() == epoch {
      return;
    }
    *self.vol.borrow_mut() = None;
    self.stamp.set(epoch);
  }

  /// the scalars kotlin sent with the handle become the cache, as if each had been read
  fn adopt_projection(&self, ctx: &Ctx<'js>, json: &str) -> JsResult<()> {
    let parsed = ctx.json_parse(json)?;
    if parsed.is_array() {
      return throw_tl(ctx, "tl wire: bad projection");
    }
    let Some(fields) = parsed.into_object() else {
      return throw_tl(ctx, "tl wire: bad projection");
    };
    self.adopt_fields(ctx, fields)
  }

  /// a projection carries scalars and a type name, never a child: a nested object is its own
  /// handle, minted when the field is read.
  fn adopt_fields(&self, ctx: &Ctx<'js>, fields: Object<'js>) -> JsResult<()> {
    fields.set_prototype(None)?;
    if fields.contains_key(TYPE_KEY)? {
      let type_name: Value = fields.get(TYPE_KEY)?;
      fields.remove(TYPE_KEY)?;
      self.perm(ctx)?.set(TYPE_KEY, type_name)?;
    }
    *self.vol.borrow_mut() = Some(fields);
    Ok(())
  }

  fn read_field(&self, ctx: &Ctx<'js>, key: &str) -> JsResult<Value<'js>> {
    self.sync_epoch();
    if key == TYPE_KEY {
      if let Some(value) = self.cached_perm(key)? {
        return Ok(value);
      }
    } else if !Self::reserved(key) {
      if let Some(value) = self.cached(key)? {
        return Ok(value);
      }
    }
    let value = match self.read_by_ordinal(ctx, key)? {
      Some(value) => value,
      None => {
        let wire = self.host().tl_get(self.handle, key);
        self.views.wire_to_js_value(ctx, &wire, self.life)?
      }
    };
    if self.cacheable() && !Self::reserved(key) {
      let section = if key == TYPE_KEY { self.perm(ctx)? } else { self.vol(ctx)? };
      section.set(key, value.clone())?;
    }
    Ok(value)
  }

  /// `None` when the host will not serve this field by ordinal and it has to be read by name
  fn read_by_ordinal(&self, ctx: &Ctx<'js>, key: &str) -> JsResult<Option<Value<'js>>> {
    if self.class_id == ORDINAL_FALLBACK {
      return Ok(None);
    }
    let ordinal = self.views.ordinal_of(self.host(), self.class_id, key);
    if ordinal == ORDINAL_FALLBACK {
      return Ok(None);
    }
    let Ok(written) = usize::try_from(self.host().tl_read_field(self.handle, self.class_id, ordinal)) else {
      return Ok(None);
    };
    let host = self.host();
    let bytes = host
      .read_buffer()
      .get(..written)
      .ok_or_else(|| Exception::throw_message(ctx, "tl read: short reply"))?;
    decode_read(ctx, &self.views, self.life, bytes).map(Some)
  }

  fn has_field(&self, ctx: &Ctx<'js>, key: &str) -> JsResult<bool> {
    self.sync_epoch();
    if Self::reserved(key) {
      return Ok(false);
    }
    let has_key = format!("{HAS_PREFIX}{key}");
    if let Some(value) = self.cached(&has_key)? {
      return Ok(value.as_bool().unwrap_or(false));
    }
    // a cached value proves the field is there; a cached null does not say which way the bit went
    if let Some(value) = self.cached(key)? {
      if !value.is_null() {
        return Ok(true);
      }
    }
    let present = match self.host().tl_has(self.handle, key) {
      1 => true,
      0 => false,
      _ => return PluginErrorCode::HandleExpired.throw(ctx, HANDLE_EXPIRED_MESSAGE),
    };
    if self.cacheable() {
      self.vol(ctx)?.set(has_key.as_str(), present)?;
    }
    Ok(present)
  }

  fn write_field(&self, ctx: &Ctx<'js>, key: &str, wire: &str) -> JsResult<bool> {
    self.finish_write(ctx, self.host().tl_set(self.handle, key, wire))
  }

  fn finish_write(&self, ctx: &Ctx<'js>, result: Option<String>) -> JsResult<bool> {
    self.views.bump();
    self.sync_epoch();
    match result {
      None => Ok(true),
      Some(err) => Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?)),
    }
  }

  fn assign_property(&self, ctx: &Ctx<'js>, prop: &Value<'js>, value: Value<'js>) -> JsResult<bool> {
    let key = property_key_string(ctx, prop)?;
    if let Some(bytes) = TypedArray::<u8>::from_value(value.clone())
      .ok()
      .and_then(|array| array.as_bytes().map(<[u8]>::to_vec))
    {
      let result = self.host().tl_set_bytes(self.handle, &key, &bytes);
      return self.finish_write(ctx, result);
    }
    let wire = js_value_to_wire(ctx, value)?;
    self.write_field(ctx, &key, &wire)
  }

  fn read_to_json(&self, ctx: &Ctx<'js>) -> JsResult<Value<'js>> {
    if let Some(value) = self.cached_perm(TO_JSON_KEY)? {
      return Ok(value);
    }
    let host = self.views.host.clone();
    let handle = self.handle;
    let value = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
      match host.tl_copy(handle) {
        Some(json) => json_parse_tl(&ctx, &json),
        None => {
          let ctx: &Ctx<'js> = &ctx;
          PluginErrorCode::HandleExpired.throw(ctx, HANDLE_EXPIRED_MESSAGE)
        }
      }
    })?
    .into_js(ctx)?;
    if self.cacheable() {
      self.perm(ctx)?.set(TO_JSON_KEY, value.clone())?;
    }
    Ok(value)
  }

  fn own_keys(&self, ctx: &Ctx<'js>) -> JsResult<Array<'js>> {
    if self.is_vector {
      let arr = Array::new(ctx.clone())?;
      let len = vector_length(ctx, self.host(), self.handle)?.max(0) as usize;
      for idx in 0..len {
        arr.set(idx, idx.to_string())?;
      }
      arr.set(len, "length")?;
      return Ok(arr);
    }
    self.sync_epoch();
    if let Some(keys) = self.cached(KEYS_ENTRY)? {
      if let Some(keys) = keys.as_string() {
        return keys_to_array(ctx, &keys.to_string()?);
      }
    }
    let Some(keys) = self.host().tl_own_keys(self.handle) else {
      return PluginErrorCode::HandleExpired.throw(ctx, HANDLE_EXPIRED_MESSAGE);
    };
    if self.cacheable() {
      self.vol(ctx)?.set(KEYS_ENTRY, keys.as_str())?;
    }
    keys_to_array(ctx, &keys)
  }
}

fn keys_to_array<'js>(ctx: &Ctx<'js>, keys: &str) -> JsResult<Array<'js>> {
  let arr = Array::new(ctx.clone())?;
  for (i, key) in keys.split(',').filter(|k| !k.is_empty()).enumerate() {
    arr.set(i, key)?;
  }
  Ok(arr)
}

fn check_writable<'js>(ctx: &Ctx<'js>, view: &HandleBox<'js>) -> JsResult<()> {
  if view.read_only {
    return PluginErrorCode::Forbidden.throw(ctx, READ_ONLY_MESSAGE);
  }
  Ok(())
}

fn box_of<'js>(ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<Class<'js, HandleBox<'js>>> {
  target
    .as_object()
    .and_then(Class::<HandleBox>::from_object)
    .ok_or_else(|| Exception::throw_message(ctx, "tl proxy: the target is not a TL handle"))
}

fn build_handler<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
  let handler = Object::new(ctx.clone())?;

  handler.set(
    PredefinedAtom::Getter,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>, _receiver: Value<'js>| -> JsResult<Value<'js>> {
        let handle = box_of(&ctx, &target)?;
        let view = handle.borrow();
        if let Some(sym) = prop.as_symbol() {
          if sym == &TlShared::marker(&ctx)? {
            return encode_handle(view.is_vector, view.read_only, view.handle).into_js(&ctx);
          }
          if view.is_vector && sym == &Symbol::iterator(ctx.clone()) {
            drop(view);
            return make_vector_iterator(&ctx, &handle)?.into_js(&ctx);
          }
          return Ok(Value::new_undefined(ctx.clone()));
        }
        let key = property_key_string(&ctx, &prop)?;
        if key == THEN_KEY {
          return Ok(Value::new_undefined(ctx.clone()));
        }
        if key == TO_JSON_KEY {
          return view.read_to_json(&ctx);
        }
        view.read_field(&ctx, &key)
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::Setter,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>,
       target: Value<'js>,
       prop: Value<'js>,
       value: Value<'js>,
       _receiver: Value<'js>|
       -> JsResult<bool> {
        let handle = box_of(&ctx, &target)?;
        let view = handle.borrow();
        if prop.as_symbol().is_some() {
          return throw_tl(&ctx, "tl proxy: cannot set a symbol-keyed property");
        }
        check_writable(&ctx, &view)?;
        view.assign_property(&ctx, &prop, value)
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::DefineProperty,
    Function::new(
      ctx.clone(),
      |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>, descriptor: Value<'js>| -> JsResult<bool> {
        let handle = box_of(&ctx, &target)?;
        let view = handle.borrow();
        check_writable(&ctx, &view)?;
        if prop.as_symbol().is_some() {
          return throw_tl(&ctx, "tl proxy: cannot define a symbol-keyed property");
        }
        let value = descriptor_value(&ctx, &descriptor)?;
        view.assign_property(&ctx, &prop, value)
      },
    )?,
  )?;

  handler.set(
    PredefinedAtom::Has,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
      let handle = box_of(&ctx, &target)?;
      let view = handle.borrow();
      if let Some(sym) = prop.as_symbol() {
        if sym == &TlShared::marker(&ctx)? {
          return Ok(true);
        }
        return Ok(view.is_vector && sym == &Symbol::iterator(ctx.clone()));
      }
      let key = property_key_string(&ctx, &prop)?;
      if key == TO_JSON_KEY {
        return Ok(true);
      }
      view.has_field(&ctx, &key)
    })?,
  )?;

  handler.set(
    PredefinedAtom::DeleteProperty,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
      let handle = box_of(&ctx, &target)?;
      let view = handle.borrow();
      check_writable(&ctx, &view)?;
      if prop.as_symbol().is_some() {
        return throw_tl(&ctx, "tl proxy: cannot delete a symbol-keyed property");
      }
      let key = property_key_string(&ctx, &prop)?;
      view.write_field(&ctx, &key, "N")
    })?,
  )?;

  handler.set(
    PredefinedAtom::OwnKeys,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>| -> JsResult<Array<'js>> {
      let handle = box_of(&ctx, &target)?;
      let view = handle.borrow();
      view.own_keys(&ctx)
    })?,
  )?;

  handler.set(
    PredefinedAtom::GetOwnPropertyDescriptor,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<Value<'js>> {
      let handle = box_of(&ctx, &target)?;
      let view = handle.borrow();
      if prop.as_symbol().is_some() {
        return Ok(Value::new_undefined(ctx.clone()));
      }
      let key = property_key_string(&ctx, &prop)?;
      if !view.has_field(&ctx, &key)? {
        return Ok(Value::new_undefined(ctx.clone()));
      }
      let value = view.read_field(&ctx, &key)?;
      let descriptor = Object::new(ctx.clone())?;
      descriptor.set("value", value)?;
      descriptor.set("writable", !view.read_only)?;
      descriptor.set("enumerable", true)?;
      descriptor.set("configurable", true)?;
      descriptor.into_js(&ctx)
    })?,
  )?;

  handler.set(
    PredefinedAtom::PreventExtensions,
    Function::new(ctx.clone(), |ctx: Ctx<'js>, _target: Value<'js>| -> JsResult<bool> {
      let ctx: &Ctx<'js> = &ctx;
      PluginErrorCode::Unsupported.throw(ctx, NOT_EXTENSIBLE_MESSAGE)
    })?,
  )?;

  Ok(handler)
}

#[allow(clippy::too_many_arguments)]
fn build_view<'js>(
  ctx: &Ctx<'js>,
  views: Rc<TlViews>,
  is_vector: bool,
  read_only: bool,
  life: ViewLife,
  handle: i64,
  class_id: i32,
  projection: Option<&str>,
) -> JsResult<Proxy<'js>> {
  let (handler, _) = TlShared::get(ctx)?;
  let stamp = views.epoch();
  let target = Class::instance(
    ctx.clone(),
    HandleBox {
      views,
      handle,
      is_vector,
      read_only,
      life,
      class_id,
      stamp: Cell::new(stamp),
      perm: RefCell::new(None),
      vol: RefCell::new(None),
    },
  )?;
  {
    let view = target.borrow();
    // a view that caches nothing drops what rode along; a projection owns no handle to release
    if view.cacheable() {
      if let Some(json) = projection {
        view.adopt_projection(ctx, json)?;
      }
    }
  }
  Proxy::new(ctx.clone(), target, ProxyHandler::from_object(handler)?)
}

fn property_key_string<'js>(ctx: &Ctx<'js>, prop: &Value<'js>) -> JsResult<String> {
  if let Some(s) = prop.as_string() {
    return s.to_string();
  }
  if let Some(sym) = prop.as_symbol() {
    return sym.as_atom().to_string();
  }
  throw_tl(ctx, "tl proxy: unsupported property key")
}

fn vector_length<'js>(ctx: &Ctx<'js>, host: &dyn TlHost, handle: i64) -> JsResult<i64> {
  let wire = host.tl_get(handle, "length");
  if let Some(n) = wire.strip_prefix('I').and_then(|p| p.parse().ok()) {
    return Ok(n);
  }
  match crate::api::error::wire_error_to_js(ctx, &wire) {
    Some(built) => Err(ctx.throw(built?)),
    None => throw_tl(ctx, "tl vector: bad length"),
  }
}

/// Retain the proxy target, not just its ID. `for (const x of view.vec)` can release the iterable
/// after obtaining its iterator; without this reference, an uncached view could release its handle
/// before the first `next`.
fn make_vector_iterator<'js>(ctx: &Ctx<'js>, target: &Class<'js, HandleBox<'js>>) -> JsResult<Function<'js>> {
  let target = target.clone();
  Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
    let index = Cell::new(0i64);
    let target = target.clone();
    let iterator = Object::new(ctx.clone())?;
    let next_fn = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
      let (views, handle, life) = {
        let view = target.borrow();
        (view.views.clone(), view.handle, view.life)
      };
      let result = Object::new(ctx.clone())?;
      let len = vector_length(&ctx, views.host.as_ref(), handle)?;
      let i = index.get();
      if i >= len {
        result.set("done", true)?;
        result.set("value", Value::new_undefined(ctx.clone()))?;
      } else {
        index.set(i + 1);
        let wire = views.host.tl_get(handle, &i.to_string());
        result.set("done", false)?;
        result.set("value", views.wire_to_js_value(&ctx, &wire, life)?)?;
      }
      Ok(result)
    })?;
    iterator.set("next", next_fn)?;
    Ok(iterator)
  })
}

#[cfg(test)]
#[path = "proxy_tests.rs"]
mod tests;
