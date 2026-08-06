//! Lazy JS `Proxy` bridge over live Kotlin TL handles (`desu.inugram.helpers.plugins.tl.TlHandles`).
//!
//! A handle is an opaque `i64` Kotlin minted for a `TLObject` or a TL vector; the proxy's traps
//! round-trip one field at a time through [`TlHost`]. Every value crossing carries a [`TlWire`]
//! tag, mirroring `desu.inugram.core.plugins.TlWire` byte-for-byte.
//!
//! Identification is duck-typed: the `get` trap self-answers `Symbol.for("inu.tl.handle")` with
//! its own wire tag, so [`js_value_to_wire`] needs no access to rquickjs's crate-private `Proxy`
//! internals. The target is a [`Class<HandleBox>`](rquickjs::Class) rather than a plain object so
//! that quickjs's finalizer runs [`HandleBox`]'s `Drop` and releases the handle.
//!
//! A view's lifetime is not on the wire - it comes from the entry point, and a child inherits its
//! parent's, which is exact because Kotlin mints children under the parent entry's scope id.
//!
//! A plugin-lifetime *object* view memoizes reads in a bag hung off its proxy target under a
//! private symbol; any write through any view bumps a context-wide epoch and an older-stamped bag
//! is emptied on next touch, Kotlin owning the flag words so one write can change the visibility of
//! a sibling field or of one seen through another view over the same Java object. Dispatch-scoped
//! views never cache (they must observe other middleware's rewrites and expire loudly), and neither
//! do vector views (the firebreak keeping a walk of a long vector from pinning a handle per
//! element).

use std::cell::Cell;
use std::rc::Rc;

use rquickjs::atom::PredefinedAtom;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::proxy::ProxyHandler;
use rquickjs::{
    Array, Class, Constructor, Ctx, Exception, Filter, Function, IntoJs, JsLifetime, Object, Proxy, Result as JsResult,
    Symbol, TypedArray, Value,
};

/// sentinel key marking a byte-array value inside a `J` JSON payload (mirrors `TlJson.BYTES_KEY`
/// Kotlin-side): `{"$inuBytes": "<base64>"}`. Plain JSON can't carry a Uint8Array, so snapshots
/// wrap bytes in this shape and [`json_parse_tl`] revives them into real Uint8Arrays (and
/// [`json_stringify_tl`] re-wraps them going the other way). Collision-safe: a real TL object is
/// always `{"_": ...}`-shaped and TL field names are Java identifiers, which can't contain `$inu`.
const BYTES_MARKER_KEY: &str = "$inuBytes";

/// stand-in for the Kotlin `QuickJs.TlListener` interface (rust: `JniBridge` in `lib.rs`)
pub trait TlHost {
    /// `get` trap; `key == "_"` reads the TL type name, `"length"` a vector's size
    fn tl_get(&self, handle: i64, key: &str) -> String;
    /// `set`/`deleteProperty` trap (`value_wire` is `N` for delete); `None` on success
    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String>;
    /// `has`/`getOwnPropertyDescriptor` existence probe: 1 = present, 0 = absent, -1 = expired.
    /// O(1) Kotlin-side (a map/range lookup) - deliberately not answered via [`Self::tl_own_keys`],
    /// which would rebuild the full key list per probed key
    fn tl_has(&self, handle: i64, key: &str) -> i32;
    /// `ownKeys` trap (object handles only); a comma-separated key list (keys are Java field
    /// names, so the separator is unambiguous); `None` if `handle` is expired
    fn tl_own_keys(&self, handle: i64) -> Option<String>;
    /// `obj.toJSON()` full detached snapshot; `None` if `handle` is expired
    fn tl_copy(&self, handle: i64) -> Option<String>;
    /// the handle's backing proxy target ([`HandleBox`]) was garbage-collected; frees the entry
    /// Kotlin-side. Called from [`HandleBox`]'s `Drop`, always on the engine's own thread (see its
    /// doc comment) - never crosses threads, same as every other `TlHost` method.
    fn tl_release(&self, handle: i64);
}

/// Backing store for a live proxy's `target`.
///
/// QuickJS only runs GC while executing on the engine's `Context`, which for this engine only
/// ever happens on `Utilities.globalQueue`, so `tl_release`'s upcall never needs to cross
/// threads.
///
/// The handle may already have been freed by the time this drops - dispatch scopes are
/// hard-invalidated in bulk whether or not GC has run. `TlHandles.tlRelease` is a plain
/// `HashMap.remove`, a no-op for a missing key, so the double release needs no guard here.
struct HandleBox {
    host: Rc<dyn TlHost>,
    handle: i64,
}

impl Drop for HandleBox {
    fn drop(&mut self) {
        self.host.tl_release(self.handle);
    }
}

impl<'js> Trace<'js> for HandleBox {
    fn trace<'a>(&self, _tracer: Tracer<'a, 'js>) {}
}

unsafe impl<'js> JsLifetime<'js> for HandleBox {
    type Changed<'to> = HandleBox;
}

impl<'js> JsClass<'js> for HandleBox {
    const NAME: &'static str = "TlHandle";
    type Mutable = Readable;

    fn constructor(_ctx: &Ctx<'js>) -> JsResult<Option<Constructor<'js>>> {
        Ok(None)
    }
}

/// per-context view factory: the host every view upcalls into, plus the write epoch their field
/// caches are stamped against
pub struct TlViews {
    host: Rc<dyn TlHost>,
    epoch: Cell<u64>,
}

impl TlViews {
    pub fn new(host: Rc<dyn TlHost>) -> Rc<Self> {
        Rc::new(TlViews { host, epoch: Cell::new(0) })
    }

    fn epoch(&self) -> u64 {
        self.epoch.get()
    }

    fn bump(&self) {
        self.epoch.set(self.epoch.get() + 1);
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ViewLife {
    Dispatch,
    Plugin,
}

struct ViewState {
    views: Rc<TlViews>,
    handle: i64,
    is_vector: bool,
    read_only: bool,
    life: ViewLife,
    stamp: Cell<u64>,
}

impl ViewState {
    fn cacheable(&self) -> bool {
        self.life == ViewLife::Plugin && !self.is_vector
    }

    fn host(&self) -> &Rc<dyn TlHost> {
        &self.views.host
    }
}

/// mirrors `TlWire.HANDLE_EXPIRED_MESSAGE` Kotlin-side
const HANDLE_EXPIRED_MESSAGE: &str =
    "TL handle expired — object escaped back to native code; copy fields you need before returning";

const READ_ONLY_MESSAGE: &str = "this TL view is read-only; take a copy with toJSON() to edit it";

const DESCRIPTOR_MESSAGE: &str =
    "a TL view only supports plain value assignment; defineProperty needs a data descriptor carrying a value, and cannot change a field's attributes";

const NOT_EXTENSIBLE_MESSAGE: &str = "a TL view cannot be sealed or frozen; take a copy with toJSON() to freeze it";

const MARKER_DESCRIPTION: &str = "inu.tl.handle";
const CACHE_MARKER_DESCRIPTION: &str = "inu.tl.cache";

const SECTION_PERM: &str = "perm";
const SECTION_VAL: &str = "val";
const SECTION_HAS: &str = "has";
const KEYS_ENTRY: &str = "keys";
const TO_JSON_KEY: &str = "toJSON";
const THEN_KEY: &str = "then";

fn marker<'js>(ctx: &Ctx<'js>) -> JsResult<Symbol<'js>> {
    Symbol::new_global(ctx.clone(), MARKER_DESCRIPTION)
}

fn cache_marker<'js>(ctx: &Ctx<'js>) -> JsResult<Symbol<'js>> {
    Symbol::new_global(ctx.clone(), CACHE_MARKER_DESCRIPTION)
}

fn encode_handle(is_vector: bool, read_only: bool, id: i64) -> String {
    format!("H{}{}{}", if is_vector { 'V' } else { 'O' }, if read_only { 'R' } else { 'W' }, id)
}

fn parse_handle(payload: &str) -> Option<(bool, bool, i64)> {
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
    let id: i64 = chars.as_str().parse().ok()?;
    Some((is_vector, read_only, id))
}

/// encodes an `E`-tagged wire error value (mirrors `TlWire.encodeError` Kotlin-side); used by
/// `rpc.rs` for a middleware's thrown/rejected error before crossing back into the host.
pub fn encode_error(message: &str) -> String {
    format!("E{message}")
}

/// encodes an `R`-tagged rpc error (mirrors `TlWire.encodeRpcError` Kotlin-side)
pub fn encode_rpc_error(code: i32, text: &str) -> String {
    format!("R{code}:{text}")
}

/// `Some((code, text))` if `wire` is an `R`-tagged rpc error value, `None` otherwise
pub fn wire_rpc_error(wire: &str) -> Option<(i32, &str)> {
    let payload = wire.strip_prefix('R')?;
    let (code, text) = payload.split_once(':')?;
    Some((code.parse().ok()?, text))
}

fn throw_tl<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    Err(Exception::throw_message(ctx, message))
}

/// the host's out-of-band expiry answers (`tl_own_keys`/`tl_copy`'s `null`, `tl_has`'s `-1`) carry
/// no wire, so they raise the same `handle-expired` error the wire path produces
fn throw_expired<'js, T>(ctx: &Ctx<'js>) -> JsResult<T> {
    crate::engine::error::throw_plugin_error(ctx, "handle-expired", HANDLE_EXPIRED_MESSAGE, None, None, None)
}

fn throw_read_only<'js, T>(ctx: &Ctx<'js>) -> JsResult<T> {
    crate::engine::error::throw_plugin_error(ctx, "forbidden", READ_ONLY_MESSAGE, None, None, None)
}

fn throw_unsupported<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    crate::engine::error::throw_plugin_error(ctx, "unsupported", message, None, None, None)
}

/// QuickJS normalizes a descriptor through `js_obj_to_desc`/`js_create_desc` before the trap sees
/// it, so it arrives carrying exactly the attributes the caller wrote, each already a real boolean.
/// A `false` attribute has to be refused rather than honoured: the proxy target holds no own
/// property, and QuickJS answers a `configurable: false` define over one with a bare
/// "inconsistent defineProperty" TypeError of its own.
fn descriptor_value<'js>(ctx: &Ctx<'js>, descriptor: &Value<'js>) -> JsResult<Value<'js>> {
    let Some(obj) = descriptor.as_object() else {
        return throw_unsupported(ctx, DESCRIPTOR_MESSAGE);
    };
    let mut value = None;
    for entry in obj.own_props::<String, Value>(Filter::new().string()) {
        let (attribute, given) = entry?;
        match attribute.as_str() {
            "value" => value = Some(given),
            "writable" | "enumerable" | "configurable" if given.as_bool() == Some(true) => {}
            _ => return throw_unsupported(ctx, DESCRIPTOR_MESSAGE),
        }
    }
    match value {
        Some(value) => Ok(value),
        None => throw_unsupported(ctx, DESCRIPTOR_MESSAGE),
    }
}

pub(crate) fn base64_encode(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s).ok()
}

/// a real Uint8Array whose own `toJSON` re-emits the `{"$inuBytes": base64}` wrapper, so a
/// plugin-side `JSON.stringify` of a snapshot round-trips bytes instead of producing an
/// index-map (`{"0":1,...}`) that nothing can decode
fn make_bytes_value<'js>(ctx: &Ctx<'js>, bytes: Vec<u8>) -> JsResult<Value<'js>> {
    let b64 = base64_encode(&bytes);
    let arr = TypedArray::<u8>::new_copy(ctx.clone(), bytes)?;
    let to_json = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
        let wrapper = Object::new(ctx.clone())?;
        wrapper.set(BYTES_MARKER_KEY, b64.as_str())?;
        Ok(wrapper)
    })?;
    arr.set(TO_JSON_KEY, to_json)?;
    arr.into_js(ctx)
}

/// `JSON.parse` reviving `{"$inuBytes": base64}` wrappers into real Uint8Arrays
pub(crate) fn json_parse_tl<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let parse: Function = json_obj.get("parse")?;
    let reviver =
        Function::new(ctx.clone(), |ctx: Ctx<'js>, _key: Value<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
            let Some(obj) = value.as_object() else {
                return Ok(value);
            };
            let Some(b64) = obj.get::<_, Option<String>>(BYTES_MARKER_KEY)? else {
                return Ok(value);
            };
            let Some(bytes) = base64_decode(&b64) else {
                return Ok(value);
            };
            make_bytes_value(&ctx, bytes)
        })?;
    parse.call((json, reviver))
}

/// `JSON.stringify` wrapping any Uint8Array (plugin-created ones included - revived ones already
/// self-wrap via their own `toJSON`) into `{"$inuBytes": base64}`
pub(crate) fn json_stringify_tl<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let stringify: Function = json_obj.get("stringify")?;
    let replacer =
        Function::new(ctx.clone(), |ctx: Ctx<'js>, _key: Value<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
            if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
                if let Some(bytes) = typed.as_bytes() {
                    let wrapper = Object::new(ctx.clone())?;
                    wrapper.set(BYTES_MARKER_KEY, base64_encode(bytes))?;
                    return wrapper.into_js(&ctx);
                }
            }
            Ok(value)
        })?;
    stringify.call((value, replacer))
}

/// the tags that carry a value rather than a reference, shared with [`crate::platform::jvm`]: those are the
/// same five bytes on either bridge, and a second implementation of them is a second `Y` that
/// forgets it is base64. `None` for a tag this does not own (`H`/`J`, and jvm's `G`).
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
        'Y' => match base64_decode(payload) {
            Some(bytes) => make_bytes_value(ctx, bytes),
            None => Err(Exception::throw_message(ctx, "tl wire: bad base64")),
        },
        _ => return None,
    })
}

/// decodes a single [`TlHost::tl_get`]/`next()`/`invokeRpc()`/`onUpdate` value into a JS value;
/// `life` is the lifetime any view built here (and, transitively, its children) gets.
/// Error-tagged wire values (`E`/`R`/`P`) throw rather than returning - callers that expect a
/// thrown error (vs. a value) should route the wire through [`crate::engine::error::wire_error_to_js`]
/// themselves before calling this.
pub fn wire_to_js_value<'js>(ctx: &Ctx<'js>, views: &Rc<TlViews>, wire: &str, life: ViewLife) -> JsResult<Value<'js>> {
    if let Some(built) = crate::engine::error::wire_error_to_js(ctx, wire) {
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
            let (is_vector, read_only, id) =
                parse_handle(payload).ok_or_else(|| Exception::throw_message(ctx, "tl wire: bad handle"))?;
            build_proxy(ctx, views.clone(), is_vector, read_only, life, id)?.into_js(ctx)
        }
        'J' => json_parse_tl(ctx, payload),
        other => throw_tl(ctx, &format!("tl wire: unknown tag '{other}'")),
    }
}

/// encodes an outbound JS value: a live proxy re-uses its handle (`H`) with zero copying, a plain
/// value falls back to a JSON construct payload (`J`). Used for `set`'s new value, `next(req)`'s
/// argument, `invokeRpc(obj)`'s argument, and a middleware's short-circuit return value.
pub fn js_value_to_wire<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
    if value.is_null() {
        return Ok("N".to_string());
    }
    if let Some(handle_wire) = try_read_marker(ctx, &value)? {
        return Ok(handle_wire);
    }
    if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
        if let Some(bytes) = typed.as_bytes() {
            return Ok(format!("Y{}", base64_encode(bytes)));
        }
    }
    let json = json_stringify_tl(ctx, value)?;
    Ok(format!("J{json}"))
}

fn try_read_marker<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<Option<String>> {
    let Some(obj) = value.as_object() else {
        return Ok(None);
    };
    let key = marker(ctx)?;
    match obj.get::<_, Value>(key.as_atom()) {
        Ok(v) => Ok(v.as_string().and_then(|s| s.to_string().ok())),
        Err(_) => Ok(None),
    }
}

/// null-prototype throughout: section keys are raw TL field names, and on a normal object
/// `contains_key` would answer for `toString`/`constructor`, while `set("__proto__", v)` would
/// reparent the section instead of storing a field
fn new_section<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    Object::new_proto(ctx.clone(), None)
}

fn read_bag<'js>(ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<Option<Object<'js>>> {
    let Some(obj) = target.as_object() else {
        return Ok(None);
    };
    Ok(obj.get::<_, Value>(cache_marker(ctx)?.as_atom())?.into_object())
}

fn bag_read<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<Option<Object<'js>>> {
    if !state.cacheable() {
        return Ok(None);
    }
    read_bag(ctx, target)
}

fn bag_write<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<Option<Object<'js>>> {
    if !state.cacheable() {
        return Ok(None);
    }
    if let Some(bag) = read_bag(ctx, target)? {
        return Ok(Some(bag));
    }
    let Some(obj) = target.as_object() else {
        return Ok(None);
    };
    let bag = new_section(ctx)?;
    bag.set(SECTION_PERM, new_section(ctx)?)?;
    bag.set(SECTION_VAL, new_section(ctx)?)?;
    bag.set(SECTION_HAS, new_section(ctx)?)?;
    obj.set(cache_marker(ctx)?.as_atom(), bag.clone())?;
    Ok(Some(bag))
}

fn cache_section<'js>(bag: &Object<'js>, name: &str) -> JsResult<Object<'js>> {
    bag.get(name)
}

fn sync_epoch<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<()> {
    if !state.cacheable() {
        return Ok(());
    }
    let epoch = state.views.epoch();
    if state.stamp.get() == epoch {
        return Ok(());
    }
    if let Some(bag) = read_bag(ctx, target)? {
        bag.set(SECTION_VAL, new_section(ctx)?)?;
        bag.set(SECTION_HAS, new_section(ctx)?)?;
        bag.remove(KEYS_ENTRY)?;
    }
    state.stamp.set(epoch);
    Ok(())
}

fn read_field<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>, key: &str) -> JsResult<Value<'js>> {
    sync_epoch(state, ctx, target)?;
    let name = if key == "_" { SECTION_PERM } else { SECTION_VAL };
    if let Some(bag) = bag_read(state, ctx, target)? {
        let section = cache_section(&bag, name)?;
        if section.contains_key(key)? {
            return section.get(key);
        }
    }
    let wire = state.host().tl_get(state.handle, key);
    let value = wire_to_js_value(ctx, &state.views, &wire, state.life)?;
    if let Some(bag) = bag_write(state, ctx, target)? {
        cache_section(&bag, name)?.set(key, value.clone())?;
    }
    Ok(value)
}

/// the raw presence path, without the `get` trap's synthetic answers: `getOwnPropertyDescriptor`
/// must keep reporting `undefined` for `toJSON` even though `'toJSON' in view` is true
fn has_field<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>, key: &str) -> JsResult<bool> {
    sync_epoch(state, ctx, target)?;
    if let Some(bag) = bag_read(state, ctx, target)? {
        let section = cache_section(&bag, SECTION_HAS)?;
        if section.contains_key(key)? {
            return section.get(key);
        }
    }
    let present = match state.host().tl_has(state.handle, key) {
        1 => true,
        0 => false,
        _ => return throw_expired(ctx),
    };
    if let Some(bag) = bag_write(state, ctx, target)? {
        cache_section(&bag, SECTION_HAS)?.set(key, present)?;
    }
    Ok(present)
}

fn write_field<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>, key: &str, wire: &str) -> JsResult<bool> {
    let result = state.host().tl_set(state.handle, key, wire);
    // `TlHandles.setObjectField` assigns the java field and only then runs `syncFlagBit`, so a
    // reported failure can still have mutated the object: invalidate whatever the answer is
    state.views.bump();
    sync_epoch(state, ctx, target)?;
    match result {
        None => Ok(true),
        Some(err) => Err(ctx.throw(crate::engine::error::host_error_to_js(ctx, &err)?)),
    }
}

fn assign_property<'js>(
    state: &ViewState,
    ctx: &Ctx<'js>,
    target: &Value<'js>,
    prop: &Value<'js>,
    value: Value<'js>,
) -> JsResult<bool> {
    let key = property_key_string(ctx, prop)?;
    let wire = js_value_to_wire(ctx, value)?;
    write_field(state, ctx, target, &key, &wire)
}

fn read_to_json<'js>(state: &ViewState, ctx: &Ctx<'js>, target: &Value<'js>) -> JsResult<Value<'js>> {
    if let Some(bag) = bag_read(state, ctx, target)? {
        let perm = cache_section(&bag, SECTION_PERM)?;
        if perm.contains_key(TO_JSON_KEY)? {
            return perm.get(TO_JSON_KEY);
        }
    }
    let host = state.host().clone();
    let handle = state.handle;
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
        match host.tl_copy(handle) {
            Some(json) => json_parse_tl(&ctx, &json),
            None => throw_expired(&ctx),
        }
    })?;
    let value = f.into_js(ctx)?;
    if let Some(bag) = bag_write(state, ctx, target)? {
        cache_section(&bag, SECTION_PERM)?.set(TO_JSON_KEY, value.clone())?;
    }
    Ok(value)
}

fn keys_to_array<'js>(ctx: &Ctx<'js>, keys: &str) -> JsResult<Array<'js>> {
    let arr = Array::new(ctx.clone())?;
    for (i, key) in keys.split(',').filter(|k| !k.is_empty()).enumerate() {
        arr.set(i, key)?;
    }
    Ok(arr)
}

/// A trap closure must never capture a JS value: `RustFunction`'s `Trace` is a no-op, so anything
/// it holds is an untraced GC root - the cache is reached through the `target` argument instead,
/// and [`ViewState`] is deliberately JS-free.
fn build_proxy<'js>(
    ctx: &Ctx<'js>,
    views: Rc<TlViews>,
    is_vector: bool,
    read_only: bool,
    life: ViewLife,
    handle: i64,
) -> JsResult<Proxy<'js>> {
    let target = Class::instance(ctx.clone(), HandleBox { host: views.host.clone(), handle })?;
    let state = Rc::new(ViewState { stamp: Cell::new(views.epoch()), views, handle, is_vector, read_only, life });
    let handler_obj = Object::new(ctx.clone())?;

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::Getter,
            Function::new(
                ctx.clone(),
                move |ctx: Ctx<'js>,
                      target: Value<'js>,
                      prop: Value<'js>,
                      _receiver: Value<'js>|
                      -> JsResult<Value<'js>> {
                    if let Some(sym) = prop.as_symbol() {
                        if sym == &marker(&ctx)? {
                            return encode_handle(state.is_vector, state.read_only, state.handle).into_js(&ctx);
                        }
                        if state.is_vector && sym == &Symbol::iterator(ctx.clone()) {
                            return make_vector_iterator(&ctx, &state)?.into_js(&ctx);
                        }
                        return Ok(Value::new_undefined(ctx.clone()));
                    }
                    let key = property_key_string(&ctx, &prop)?;
                    // resolving a promise with an object [[Get]]s its "then" to see if it's a thenable,
                    // and every view reaches JS through one (invokeRpc, next()), so answering "no such
                    // field" here would reject the very promise carrying the view. no TL field is named
                    // `then`, so nothing is shadowed
                    if key == THEN_KEY {
                        return Ok(Value::new_undefined(ctx.clone()));
                    }
                    sync_epoch(&state, &ctx, &target)?;
                    // JSON.stringify [[Get]]s "toJSON"; neither a TL object nor a vector has such a
                    // field, so instead of throwing "no such field" we hand back a snapshot fn
                    // producing a detached plain value (an array, for a vector)
                    if key == TO_JSON_KEY {
                        return read_to_json(&state, &ctx, &target);
                    }
                    read_field(&state, &ctx, &target, &key)
                },
            )?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::Setter,
            Function::new(
                ctx.clone(),
                move |ctx: Ctx<'js>,
                      target: Value<'js>,
                      prop: Value<'js>,
                      value: Value<'js>,
                      _receiver: Value<'js>|
                      -> JsResult<bool> {
                    if prop.as_symbol().is_some() {
                        return throw_tl(&ctx, "tl proxy: cannot set a symbol-keyed property");
                    }
                    if state.read_only {
                        return throw_read_only(&ctx);
                    }
                    assign_property(&state, &ctx, &target, &prop, value)
                },
            )?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::DefineProperty,
            Function::new(
                ctx.clone(),
                move |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>, descriptor: Value<'js>| -> JsResult<bool> {
                    if state.read_only {
                        return throw_read_only(&ctx);
                    }
                    if prop.as_symbol().is_some() {
                        return throw_tl(&ctx, "tl proxy: cannot define a symbol-keyed property");
                    }
                    let value = descriptor_value(&ctx, &descriptor)?;
                    assign_property(&state, &ctx, &target, &prop, value)
                },
            )?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::Has,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
                // mirror exactly what the get trap self-answers, so `in` never lies about it
                if let Some(sym) = prop.as_symbol() {
                    if sym == &marker(&ctx)? {
                        return Ok(true);
                    }
                    return Ok(state.is_vector && sym == &Symbol::iterator(ctx.clone()));
                }
                let key = property_key_string(&ctx, &prop)?;
                if key == TO_JSON_KEY {
                    return Ok(true);
                }
                has_field(&state, &ctx, &target, &key)
            })?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::DeleteProperty,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
                if state.read_only {
                    return throw_read_only(&ctx);
                }
                // as the `set` and `defineProperty` traps do: `property_key_string` answers a
                // symbol with its *description*, so without this `delete v[Symbol.for('message')]`
                // would null the app's real `message` field
                if prop.as_symbol().is_some() {
                    return throw_tl(&ctx, "tl proxy: cannot delete a symbol-keyed property");
                }
                let key = property_key_string(&ctx, &prop)?;
                write_field(&state, &ctx, &target, &key, "N")
            })?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::OwnKeys,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, target: Value<'js>| -> JsResult<Array<'js>> {
                if state.is_vector {
                    let arr = Array::new(ctx.clone())?;
                    let len = vector_length(&ctx, state.host(), state.handle)?;
                    let mut i = 0usize;
                    for idx in 0..len {
                        arr.set(i, idx.to_string())?;
                        i += 1;
                    }
                    arr.set(i, "length")?;
                    return Ok(arr);
                }
                sync_epoch(&state, &ctx, &target)?;
                if let Some(bag) = bag_read(&state, &ctx, &target)? {
                    if bag.contains_key(KEYS_ENTRY)? {
                        return keys_to_array(&ctx, &bag.get::<_, String>(KEYS_ENTRY)?);
                    }
                }
                let Some(keys) = state.host().tl_own_keys(state.handle) else {
                    return throw_expired(&ctx);
                };
                if let Some(bag) = bag_write(&state, &ctx, &target)? {
                    bag.set(KEYS_ENTRY, keys.as_str())?;
                }
                keys_to_array(&ctx, &keys)
            })?,
        )?;
    }

    {
        let state = state.clone();
        handler_obj.set(
            PredefinedAtom::GetOwnPropertyDescriptor,
            Function::new(
                ctx.clone(),
                move |ctx: Ctx<'js>, target: Value<'js>, prop: Value<'js>| -> JsResult<Value<'js>> {
                    if prop.as_symbol().is_some() {
                        return Ok(Value::new_undefined(ctx.clone()));
                    }
                    let key = property_key_string(&ctx, &prop)?;
                    if !has_field(&state, &ctx, &target, &key)? {
                        return Ok(Value::new_undefined(ctx.clone()));
                    }
                    let value = read_field(&state, &ctx, &target, &key)?;
                    let descriptor = Object::new(ctx.clone())?;
                    descriptor.set("value", value)?;
                    descriptor.set("writable", !state.read_only)?;
                    descriptor.set("enumerable", true)?;
                    // the target genuinely has no such own property, and a proxy reporting a
                    // non-configurable descriptor for one is a TypeError - `writable: false` is only
                    // legal here alongside `configurable: true`
                    descriptor.set("configurable", true)?;
                    descriptor.into_js(&ctx)
                },
            )?,
        )?;
    }

    // refusing keeps the target extensible, which every `configurable: true` descriptor the
    // getOwnPropertyDescriptor trap reports depends on: QuickJS rejects a proxy claiming an own
    // property the target lacks once that target stops being extensible
    handler_obj.set(
        PredefinedAtom::PreventExtensions,
        Function::new(ctx.clone(), |ctx: Ctx<'js>, _target: Value<'js>| -> JsResult<bool> {
            throw_unsupported(&ctx, NOT_EXTENSIBLE_MESSAGE)
        })?,
    )?;

    let handler = ProxyHandler::from_object(handler_obj)?;
    Proxy::new(ctx.clone(), target, handler)
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

/// throws (instead of silently reading 0) on an expired handle or malformed wire, so iterating a
/// stale vector fails as loudly as reading a stale object field does
fn vector_length<'js>(ctx: &Ctx<'js>, host: &Rc<dyn TlHost>, handle: i64) -> JsResult<i64> {
    let wire = host.tl_get(handle, "length");
    if let Some(n) = wire.strip_prefix('I').and_then(|p| p.parse().ok()) {
        return Ok(n);
    }
    match crate::engine::error::wire_error_to_js(ctx, &wire) {
        Some(built) => Err(ctx.throw(built?)),
        None => throw_tl(ctx, "tl vector: bad length"),
    }
}

fn make_vector_iterator<'js>(ctx: &Ctx<'js>, state: &Rc<ViewState>) -> JsResult<Function<'js>> {
    let state = state.clone();
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
        let index = Cell::new(0i64);
        let state = state.clone();
        let iterator = Object::new(ctx.clone())?;
        let next_fn = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
            let result = Object::new(ctx.clone())?;
            let len = vector_length(&ctx, state.host(), state.handle)?;
            let i = index.get();
            if i >= len {
                result.set("done", true)?;
                result.set("value", Value::new_undefined(ctx.clone()))?;
            } else {
                index.set(i + 1);
                let wire = state.host().tl_get(state.handle, &i.to_string());
                result.set("done", false)?;
                result.set("value", wire_to_js_value(&ctx, &state.views, &wire, state.life)?)?;
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
