//! Lazy JS `Proxy` bridge over live Kotlin TL handles (`desu.inugram.helpers.plugins.TlHandles`).
//!
//! A "handle" is an opaque `i64` minted Kotlin-side for a `TLObject` or a TL vector. Rather than
//! snapshotting the object graph into JSON, this module wraps a handle in a `Proxy` whose
//! get/set/has/deleteProperty/ownKeys/getOwnPropertyDescriptor traps round-trip through
//! [`TlHost`] - one field at a time, never the whole graph - onto the *real* Java object.
//!
//! Every value crossing this boundary (in either direction) is a single [`TlWire`]-shaped tag
//! string (mirrors `desu.inugram.core.plugins.TlWire` byte-for-byte): `N`=null, `S`=string,
//! `I`=int, `D`=double, `B`=bool, `Y`=bytes(base64), `H`=handle(`O`bject/`V`ector + id),
//! `J`=json(construct a plain value Kotlin-side), `E`=error message, `R`=rpc error
//! (`code:text`, surfaced to JS as an `inu.RpcError` - see `rpc.rs`).
//!
//! A handle is identified on the JS side purely by duck-typing: the proxy's own `get` trap
//! self-answers (no upcall) when queried with the well-known `Symbol.for("inu.tl.handle")`,
//! returning its own `H..` wire tag directly. [`js_value_to_wire`] uses this to encode outbound
//! values without needing raw FFI access into rquickjs's (crate-private) `Proxy` internals.
//!
//! Each proxy's `target` is a [`Class<HandleBox>`](rquickjs::Class) instance (not a plain
//! `Object`) precisely so QuickJS's GC/refcount finalizer - which drops the boxed Rust value once
//! the target becomes unreachable - fires [`HandleBox`]'s `Drop`, which calls [`TlHost::tl_release`].
//! Every handle is owned by an intercept-dispatch scope and hard-invalidated in bulk at
//! end-of-dispatch by Kotlin's `TlHandles.releaseScope` regardless of GC; the finalizer frees a
//! handle early if its proxy dies before the dispatch settles, and is a harmless no-op after
//! (see [`HandleBox`]'s doc). `invokeRpc` responses never mint handles at all - they resolve as
//! detached `J` snapshots.

use std::rc::Rc;

use rquickjs::atom::PredefinedAtom;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::proxy::ProxyHandler;
use rquickjs::{Array, Class, Constructor, Ctx, Exception, Function, IntoJs, JsLifetime, Object, Proxy, Result as JsResult, Symbol, TypedArray, Value};

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

/// Backing store for a live proxy's `target` - see this module's doc for why it's a `Class`
/// instance rather than a plain `Object`.
///
/// # Threading
/// QuickJS only runs GC (and therefore only ever calls this type's `Drop`, via the finalizer
/// installed by [`JsClass`]) while executing on the engine's `Runtime`/`Context` - which, for this
/// engine, only ever happens on `Utilities.globalQueue` (see `TlHandles.kt`'s doc comment: "the
/// same thread every engine runs on"). So `tl_release`'s JNI upcall never needs to cross threads,
/// exactly like every other `TlHost` upcall already made from proxy traps.
///
/// # Idempotency
/// A `HandleBox` is dropped exactly once, so `tl_release` is called exactly once *from this type*.
/// But the handle it names may already have been freed by the time that happens - handles are
/// hard-invalidated in bulk at end-of-dispatch (`TlHandles.releaseScope`) regardless of whether GC
/// has run yet. `TlHandles.tlRelease` is a plain `HashMap.remove`, which is a no-op (not an error)
/// for a missing key, so this "double release" is safe by construction - no additional guard is
/// needed here.
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

/// mirrors `TlWire.HANDLE_EXPIRED_MESSAGE` Kotlin-side
const HANDLE_EXPIRED_MESSAGE: &str =
    "TL handle expired — object escaped back to native code; copy fields you need before returning";

const MARKER_DESCRIPTION: &str = "inu.tl.handle";

fn marker<'js>(ctx: &Ctx<'js>) -> JsResult<Symbol<'js>> {
    Symbol::new_global(ctx.clone(), MARKER_DESCRIPTION)
}

fn encode_handle(is_vector: bool, id: i64) -> String {
    format!("H{}{}", if is_vector { "V" } else { "O" }, id)
}

fn parse_handle(payload: &str) -> Option<(bool, i64)> {
    let kind = payload.chars().next()?;
    let id: i64 = payload[1..].parse().ok()?;
    Some((kind == 'V', id))
}

/// encodes an `E`-tagged wire error value (mirrors `TlWire.encodeError` Kotlin-side); used by
/// `rpc.rs` for a middleware's thrown/rejected error before crossing back into the host.
pub fn encode_error(message: &str) -> String {
    format!("E{message}")
}

/// `Some(message)` if `wire` is an `E`-tagged error value, `None` otherwise - lets `rpc.rs` route
/// a whole request/response wire value into reject-vs-resolve before decoding it into a JS value.
pub fn wire_error_message(wire: &str) -> Option<&str> {
    wire.strip_prefix('E')
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

// -- base64 (Kotlin's `android.util.Base64.NO_WRAP` is a standard, padded, single-line alphabet) --

fn base64_encode(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s).ok()
}

// -- JSON <-> JS with byte-array revival (see BYTES_MARKER_KEY) --

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
    arr.set("toJSON", to_json)?;
    arr.into_js(ctx)
}

/// `JSON.parse` reviving `{"$inuBytes": base64}` wrappers into real Uint8Arrays
pub(crate) fn json_parse_tl<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Value<'js>> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let parse: Function = json_obj.get("parse")?;
    let reviver = Function::new(
        ctx.clone(),
        |ctx: Ctx<'js>, _key: Value<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
            let Some(obj) = value.as_object() else { return Ok(value) };
            let Some(b64) = obj.get::<_, Option<String>>(BYTES_MARKER_KEY)? else {
                return Ok(value);
            };
            let Some(bytes) = base64_decode(&b64) else { return Ok(value) };
            make_bytes_value(&ctx, bytes)
        },
    )?;
    parse.call((json, reviver))
}

/// `JSON.stringify` wrapping any Uint8Array (plugin-created ones included - revived ones already
/// self-wrap via their own `toJSON`) into `{"$inuBytes": base64}`
pub(crate) fn json_stringify_tl<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<String> {
    let json_obj: Object = ctx.globals().get("JSON")?;
    let stringify: Function = json_obj.get("stringify")?;
    let replacer = Function::new(
        ctx.clone(),
        |ctx: Ctx<'js>, _key: Value<'js>, value: Value<'js>| -> JsResult<Value<'js>> {
            if let Ok(typed) = TypedArray::<u8>::from_value(value.clone()) {
                if let Some(bytes) = typed.as_bytes() {
                    let wrapper = Object::new(ctx.clone())?;
                    wrapper.set(BYTES_MARKER_KEY, base64_encode(bytes))?;
                    return wrapper.into_js(&ctx);
                }
            }
            Ok(value)
        },
    )?;
    stringify.call((value, replacer))
}

// -- wire -> JS (values coming from Kotlin) --

/// decodes a single [`TlHost::tl_get`]/`next()`/`invokeRpc()` result value into a JS value.
/// `E`-tagged wire values throw (see [`throw_tl`]) rather than returning - callers that expect a
/// thrown error (vs. a value) should match on the tag themselves before calling this.
pub fn wire_to_js_value<'js>(ctx: &Ctx<'js>, host: &Rc<dyn TlHost>, wire: &str) -> JsResult<Value<'js>> {
    if wire.is_empty() {
        return throw_tl(ctx, "tl wire: empty value");
    }
    let tag = wire.as_bytes()[0] as char;
    let payload = &wire[1..];
    match tag {
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
        'Y' => {
            let bytes = base64_decode(payload).ok_or_else(|| Exception::throw_message(ctx, "tl wire: bad base64"))?;
            make_bytes_value(ctx, bytes)
        }
        'H' => {
            let (is_vector, id) = parse_handle(payload).ok_or_else(|| Exception::throw_message(ctx, "tl wire: bad handle"))?;
            build_proxy(ctx, host.clone(), is_vector, id)?.into_js(ctx)
        }
        'J' => json_parse_tl(ctx, payload),
        'E' => throw_tl(ctx, payload),
        other => throw_tl(ctx, &format!("tl wire: unknown tag '{other}'")),
    }
}

// -- JS -> wire (values going to Kotlin) --

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
    let Some(obj) = value.as_object() else { return Ok(None) };
    let key = marker(ctx)?;
    match obj.get::<_, Value>(key.as_atom()) {
        Ok(v) => Ok(v.as_string().and_then(|s| s.to_string().ok())),
        Err(_) => Ok(None),
    }
}

// -- proxy construction --

fn build_proxy<'js>(ctx: &Ctx<'js>, host: Rc<dyn TlHost>, is_vector: bool, handle: i64) -> JsResult<Proxy<'js>> {
    let target = Class::instance(ctx.clone(), HandleBox { host: host.clone(), handle })?;
    let handler_obj = Object::new(ctx.clone())?;

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::Getter,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>, prop: Value<'js>, _receiver: Value<'js>| -> JsResult<Value<'js>> {
                if let Some(sym) = prop.as_symbol() {
                    if sym == &marker(&ctx)? {
                        return encode_handle(is_vector, handle).into_js(&ctx);
                    }
                    if is_vector && sym == &Symbol::iterator(ctx.clone()) {
                        return make_vector_iterator(&ctx, host.clone(), handle)?.into_js(&ctx);
                    }
                    return Ok(Value::new_undefined(ctx.clone()));
                }
                let key = property_key_string(&ctx, &prop)?;
                // JSON.stringify([[Get]]s "toJSON"; TL objects never have such a field, so instead of
                // throwing "no such field" we hand back a snapshot fn producing a detached plain value.
                // vectors already stringify natively as arrays.
                if !is_vector && key == "toJSON" {
                    let host = host.clone();
                    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
                        match host.tl_copy(handle) {
                            Some(json) => json_parse_tl(&ctx, &json),
                            None => throw_tl(&ctx, HANDLE_EXPIRED_MESSAGE),
                        }
                    })?;
                    return f.into_js(&ctx);
                }
                let wire = host.tl_get(handle, &key);
                wire_to_js_value(&ctx, &host, &wire)
            })?,
        )?;
    }

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::Setter,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>, prop: Value<'js>, value: Value<'js>, _receiver: Value<'js>| -> JsResult<bool> {
                if prop.as_symbol().is_some() {
                    return throw_tl(&ctx, "tl proxy: cannot set a symbol-keyed property");
                }
                let key = property_key_string(&ctx, &prop)?;
                let wire = js_value_to_wire(&ctx, value)?;
                match host.tl_set(handle, &key, &wire) {
                    None => Ok(true),
                    Some(err) => throw_tl(&ctx, &err),
                }
            })?,
        )?;
    }

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::Has,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
                // mirror exactly what the get trap self-answers, so `in` never lies about it
                if let Some(sym) = prop.as_symbol() {
                    if sym == &marker(&ctx)? {
                        return Ok(true);
                    }
                    return Ok(is_vector && sym == &Symbol::iterator(ctx.clone()));
                }
                let key = property_key_string(&ctx, &prop)?;
                if !is_vector && key == "toJSON" {
                    return Ok(true);
                }
                Ok(host.tl_has(handle, &key) == 1)
            })?,
        )?;
    }

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::DeleteProperty,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>, prop: Value<'js>| -> JsResult<bool> {
                let key = property_key_string(&ctx, &prop)?;
                match host.tl_set(handle, &key, "N") {
                    None => Ok(true),
                    Some(err) => throw_tl(&ctx, &err),
                }
            })?,
        )?;
    }

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::OwnKeys,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>| -> JsResult<Array<'js>> {
                let arr = Array::new(ctx.clone())?;
                let mut i = 0usize;
                if is_vector {
                    let len = vector_length(&ctx, &host, handle)?;
                    for idx in 0..len {
                        arr.set(i, idx.to_string())?;
                        i += 1;
                    }
                    arr.set(i, "length")?;
                } else {
                    let Some(keys) = host.tl_own_keys(handle) else {
                        return throw_tl(&ctx, HANDLE_EXPIRED_MESSAGE);
                    };
                    for key in keys.split(',').filter(|k| !k.is_empty()) {
                        arr.set(i, key)?;
                        i += 1;
                    }
                }
                Ok(arr)
            })?,
        )?;
    }

    {
        let host = host.clone();
        handler_obj.set(
            PredefinedAtom::GetOwnPropertyDescriptor,
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, _target: Value<'js>, prop: Value<'js>| -> JsResult<Value<'js>> {
                if prop.as_symbol().is_some() {
                    return Ok(Value::new_undefined(ctx.clone()));
                }
                let key = property_key_string(&ctx, &prop)?;
                if host.tl_has(handle, &key) != 1 {
                    return Ok(Value::new_undefined(ctx.clone()));
                }
                let wire = host.tl_get(handle, &key);
                let value = wire_to_js_value(&ctx, &host, &wire)?;
                let descriptor = Object::new(ctx.clone())?;
                descriptor.set("value", value)?;
                descriptor.set("writable", true)?;
                descriptor.set("enumerable", true)?;
                descriptor.set("configurable", true)?;
                descriptor.into_js(&ctx)
            })?,
        )?;
    }

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
    match wire_error_message(&wire) {
        Some(msg) => throw_tl(ctx, msg),
        None => throw_tl(ctx, "tl vector: bad length"),
    }
}

fn make_vector_iterator<'js>(ctx: &Ctx<'js>, host: Rc<dyn TlHost>, handle: i64) -> JsResult<Function<'js>> {
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
        let index = std::cell::Cell::new(0i64);
        let host = host.clone();
        let iterator = Object::new(ctx.clone())?;
        let next_fn = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Object<'js>> {
            let result = Object::new(ctx.clone())?;
            let len = vector_length(&ctx, &host, handle)?;
            let i = index.get();
            if i >= len {
                result.set("done", true)?;
                result.set("value", Value::new_undefined(ctx.clone()))?;
            } else {
                index.set(i + 1);
                let wire = host.tl_get(handle, &i.to_string());
                result.set("done", false)?;
                result.set("value", wire_to_js_value(&ctx, &host, &wire)?)?;
            }
            Ok(result)
        })?;
        iterator.set("next", next_fn)?;
        Ok(iterator)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::{Context, Runtime};
    use std::cell::{Cell, RefCell};
    use std::collections::HashMap;

    /// in-memory stand-in for `TlHandles.kt`'s handle table, driven entirely through wire strings
    /// so the traps under test (`build_proxy` and friends) exercise the exact same encoding the
    /// real JNI-backed `TlHost` would produce/consume.
    ///
    /// A field can hold either an already-final wire string ([`FakeValue::Wire`] - scalars, bytes,
    /// or a handle-ref that already names a *specific* minted id) or a *shared* nested TL value
    /// ([`FakeValue::Nested`]). The latter mirrors real `TlHandles.kt`'s `encodeFieldValue`: reading
    /// a TLObject/vector-typed field mints a **fresh handle id every access**, all pointing at the
    /// *same* underlying Java object - never a cached, reused id. `Rc<RefCell<FakeEntry>>` is this
    /// fixture's stand-in for "same underlying object, independently-numbered handles".
    #[derive(Clone)]
    enum FakeValue {
        Wire(String),
        Nested(Rc<RefCell<FakeEntry>>),
    }

    enum FakeEntry {
        Object { type_name: String, fields: HashMap<String, FakeValue> },
        Vector(Vec<FakeValue>),
    }

    #[derive(Default)]
    struct FakeTlHost {
        table: RefCell<HashMap<i64, Rc<RefCell<FakeEntry>>>>,
        next_id: Cell<i64>,
    }

    impl FakeTlHost {
        fn mint(&self, entry: FakeEntry) -> i64 {
            self.mint_shared(Rc::new(RefCell::new(entry)))
        }

        /// mints a brand-new handle id naming an *existing* shared entry - the fixture's equivalent
        /// of real Kotlin minting a fresh handle for the same underlying TLObject/ArrayList target.
        fn mint_shared(&self, entry: Rc<RefCell<FakeEntry>>) -> i64 {
            let id = self.next_id.get() + 1;
            self.next_id.set(id);
            self.table.borrow_mut().insert(id, entry);
            id
        }

        fn is_alive(&self, handle: i64) -> bool {
            self.table.borrow().contains_key(&handle)
        }
    }

    /// renders a scalar wire tag (`N`/`S`/`I`/`D`/`B`) as a JSON literal - enough for [`FakeTlHost`]
    /// to build `tl_copy`'s plain-value payload without pulling in a full wire->JSON codec.
    fn wire_to_test_json(wire: &str) -> String {
        let payload = &wire[1..];
        match wire.as_bytes()[0] as char {
            'N' => "null".to_string(),
            'S' => format!("\"{payload}\""),
            'I' | 'D' => payload.to_string(),
            'B' => if payload == "1" { "true" } else { "false" }.to_string(),
            'Y' => format!("{{\"{BYTES_MARKER_KEY}\":\"{payload}\"}}"),
            _ => "null".to_string(),
        }
    }

    fn fake_value_to_json(value: &FakeValue) -> String {
        match value {
            FakeValue::Wire(w) => wire_to_test_json(w),
            FakeValue::Nested(entry) => fake_entry_to_json(&entry.borrow()),
        }
    }

    fn fake_entry_to_json(entry: &FakeEntry) -> String {
        match entry {
            FakeEntry::Object { type_name, fields } => {
                let mut keys: Vec<&String> = fields.keys().collect();
                keys.sort();
                let body = keys
                    .iter()
                    .map(|k| format!("\"{k}\":{}", fake_value_to_json(&fields[*k])))
                    .collect::<Vec<_>>()
                    .join(",");
                format!("{{\"_\":\"{type_name}\",{body}}}")
            }
            FakeEntry::Vector(items) => {
                format!("[{}]", items.iter().map(fake_value_to_json).collect::<Vec<_>>().join(","))
            }
        }
    }

    /// mints a fresh handle for `value` if it's a [`FakeValue::Nested`] (matching real Kotlin's
    /// per-access minting), or returns the already-final wire as-is.
    fn fake_value_to_wire(host: &FakeTlHost, value: &FakeValue) -> String {
        match value {
            FakeValue::Wire(w) => w.clone(),
            FakeValue::Nested(entry) => {
                let is_vector = matches!(&*entry.borrow(), FakeEntry::Vector(_));
                let id = host.mint_shared(entry.clone());
                format!("H{}{id}", if is_vector { 'V' } else { 'O' })
            }
        }
    }

    impl TlHost for FakeTlHost {
        fn tl_get(&self, handle: i64, key: &str) -> String {
            let Some(entry_rc) = self.table.borrow().get(&handle).cloned() else {
                return encode_error("handle expired");
            };
            let result = match &*entry_rc.borrow() {
                FakeEntry::Object { type_name, fields } => {
                    if key == "_" {
                        format!("S{type_name}")
                    } else {
                        match fields.get(key) {
                            None => encode_error(&format!("no such field '{key}'")),
                            Some(value) => fake_value_to_wire(self, value),
                        }
                    }
                }
                FakeEntry::Vector(items) => {
                    if key == "length" {
                        format!("I{}", items.len())
                    } else {
                        match key.parse::<usize>().ok().and_then(|i| items.get(i)) {
                            None => encode_error("index out of range"),
                            Some(value) => fake_value_to_wire(self, value),
                        }
                    }
                }
            };
            result
        }

        fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String> {
            let entry_rc = self.table.borrow().get(&handle).cloned()?;
            let result = match &mut *entry_rc.borrow_mut() {
                FakeEntry::Object { fields, .. } => {
                    fields.insert(key.to_string(), FakeValue::Wire(value_wire.to_string()));
                    None
                }
                FakeEntry::Vector(items) => {
                    if key == "length" {
                        // mirrors TlHandles.setVectorProp: outbound scalar sets are always `J`-tagged
                        // JSON (never a raw `I` tag), so accept both here.
                        let n: usize = value_wire
                            .strip_prefix('I')
                            .or_else(|| value_wire.strip_prefix('J'))
                            .and_then(|s| s.parse().ok())?;
                        items.truncate(n);
                        None
                    } else {
                        let idx: usize = key.parse().ok()?;
                        if idx == items.len() {
                            items.push(FakeValue::Wire(value_wire.to_string()));
                            None
                        } else if idx < items.len() {
                            items[idx] = FakeValue::Wire(value_wire.to_string());
                            None
                        } else {
                            Some("index out of range".to_string())
                        }
                    }
                }
            };
            result
        }

        fn tl_has(&self, handle: i64, key: &str) -> i32 {
            let Some(entry_rc) = self.table.borrow().get(&handle).cloned() else { return -1 };
            let present = match &*entry_rc.borrow() {
                FakeEntry::Object { fields, .. } => key == "_" || fields.contains_key(key),
                FakeEntry::Vector(items) => {
                    key == "length" || key.parse::<usize>().is_ok_and(|i| i < items.len())
                }
            };
            if present { 1 } else { 0 }
        }

        fn tl_own_keys(&self, handle: i64) -> Option<String> {
            let entry_rc = self.table.borrow().get(&handle).cloned()?;
            let result = match &*entry_rc.borrow() {
                FakeEntry::Object { fields, .. } => {
                    let mut keys: Vec<&String> = fields.keys().collect();
                    keys.sort();
                    let mut out = vec!["_"];
                    out.extend(keys.iter().map(|k| k.as_str()));
                    Some(out.join(","))
                }
                FakeEntry::Vector(_) => None,
            };
            result
        }

        fn tl_copy(&self, handle: i64) -> Option<String> {
            let entry_rc = self.table.borrow().get(&handle).cloned()?;
            let json = fake_entry_to_json(&entry_rc.borrow());
            Some(json)
        }

        fn tl_release(&self, handle: i64) {
            self.table.borrow_mut().remove(&handle);
        }
    }

    fn make_ctx() -> (Runtime, Context) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        (rt, ctx)
    }

    #[test]
    fn object_reads_type_name_fields_has_and_own_keys() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let mut fields = HashMap::new();
        fields.insert("x".to_string(), FakeValue::Wire("I1".to_string()));
        fields.insert("name".to_string(), FakeValue::Wire("Shello".to_string()));
        let id = host.mint(FakeEntry::Object { type_name: "myNamespace.myClass".to_string(), fields });
        let host_dyn: Rc<dyn TlHost> = host;

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();

            let type_name: String = ctx.eval("obj._").unwrap();
            assert_eq!(type_name, "myNamespace.myClass");
            let x: i64 = ctx.eval("obj.x").unwrap();
            assert_eq!(x, 1);
            let name: String = ctx.eval("obj.name").unwrap();
            assert_eq!(name, "hello");
            let has_x: bool = ctx.eval("'x' in obj").unwrap();
            assert!(has_x);
            let has_z: bool = ctx.eval("'z' in obj").unwrap();
            assert!(!has_z);
            let mut keys: Vec<String> = ctx.eval("Object.keys(obj)").unwrap();
            keys.sort();
            assert_eq!(keys, vec!["_".to_string(), "name".to_string(), "x".to_string()]);
        });
    }

    #[test]
    fn object_set_field_mutates_the_real_handle_target() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields: HashMap::new() });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();
            ctx.eval::<(), _>("obj.x = 42").unwrap();
        });

        // outbound scalar sets are always `J`-tagged JSON, never a raw `I` tag - see js_value_to_wire
        let entry_rc = host.table.borrow().get(&id).unwrap().clone();
        match &*entry_rc.borrow() {
            FakeEntry::Object { fields, .. } => {
                assert!(matches!(fields.get("x"), Some(FakeValue::Wire(w)) if w == "J42"))
            }
            _ => panic!("expected object entry"),
        };
    }

    #[test]
    fn vector_length_indexing_push_and_iteration() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let id = host.mint(FakeEntry::Vector(vec![FakeValue::Wire("I10".to_string()), FakeValue::Wire("I20".to_string())]));
        let host_dyn: Rc<dyn TlHost> = host;

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HV{id}")).unwrap();
            ctx.globals().set("vec", proxy).unwrap();

            let len: i64 = ctx.eval("vec.length").unwrap();
            assert_eq!(len, 2);
            let first: i64 = ctx.eval("vec[0]").unwrap();
            assert_eq!(first, 10);

            ctx.eval::<(), _>("vec[2] = 30").unwrap();
            let len2: i64 = ctx.eval("vec.length").unwrap();
            assert_eq!(len2, 3);

            let summed: i64 = ctx.eval("[...vec].reduce((a, b) => a + b, 0)").unwrap();
            assert_eq!(summed, 60);

            ctx.eval::<(), _>("vec.length = 1").unwrap();
            let len3: i64 = ctx.eval("vec.length").unwrap();
            assert_eq!(len3, 1);
        });
    }

    #[test]
    fn bytes_field_roundtrips_through_uint8array_and_base64() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let mut fields = HashMap::new();
        fields.insert("data".to_string(), FakeValue::Wire(format!("Y{}", base64_encode(&[1, 2, 3, 255]))));
        let id = host.mint(FakeEntry::Object { type_name: "x".to_string(), fields });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();

            let is_typed: bool = ctx.eval("obj.data instanceof Uint8Array").unwrap();
            assert!(is_typed);
            let sum: i64 = ctx.eval("obj.data[0] + obj.data[1] + obj.data[2] + obj.data[3]").unwrap();
            assert_eq!(sum, 1 + 2 + 3 + 255);

            ctx.eval::<(), _>("obj.data = new Uint8Array([9, 8, 7])").unwrap();
        });

        let entry_rc = host.table.borrow().get(&id).unwrap().clone();
        match &*entry_rc.borrow() {
            FakeEntry::Object { fields, .. } => {
                assert!(
                    matches!(fields.get("data"), Some(FakeValue::Wire(w)) if w == &format!("Y{}", base64_encode(&[9, 8, 7])))
                );
            }
            _ => panic!("expected object entry"),
        };
    }

    /// mirrors real `TlHandles.kt`: reading the same TLObject-typed field twice mints two
    /// *independent* handle ids naming the same underlying value - so the first, transient proxy
    /// becoming unreachable and GC'd (freeing its own id) must not affect the second read.
    #[test]
    fn nested_object_field_yields_its_own_live_proxy() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let mut child_fields = HashMap::new();
        child_fields.insert("y".to_string(), FakeValue::Wire("I7".to_string()));
        let child_entry = Rc::new(RefCell::new(FakeEntry::Object { type_name: "child.type".to_string(), fields: child_fields }));
        let mut parent_fields = HashMap::new();
        parent_fields.insert("child".to_string(), FakeValue::Nested(child_entry));
        let parent_id = host.mint(FakeEntry::Object { type_name: "parent.type".to_string(), fields: parent_fields });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{parent_id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();

            let child_type: String = ctx.eval("obj.child._").unwrap();
            assert_eq!(child_type, "child.type");
            let y: i64 = ctx.eval("obj.child.y").unwrap();
            assert_eq!(y, 7);
        });

        // two reads of "child" minted two distinct handle ids (both != parent_id); a fresh id
        // was allocated for each access, matching real Kotlin's no-caching design
        assert!(host.next_id.get() >= parent_id + 2);
    }

    #[test]
    fn dropping_last_reference_and_running_gc_releases_the_handle() {
        let (rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields: HashMap::new() });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();
            ctx.eval::<(), _>("obj = undefined").unwrap();
        });

        // QuickJS frees non-cyclic garbage via refcounting as soon as the last reference drops
        // (here, immediately on `obj = undefined`); `run_gc()` only sweeps reference cycles, so
        // it's a no-op backstop here - the handle is already gone by the time it runs
        rt.run_gc();
        assert!(!host.is_alive(id), "dropping the proxy's last reference must have released its handle");
    }

    #[test]
    fn double_release_is_a_no_op() {
        let host = FakeTlHost::default();
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields: HashMap::new() });

        host.tl_release(id);
        assert!(!host.is_alive(id));
        // releasing again (e.g. GC finalizer firing after `releaseScope` already
        // freed it in bulk) must be a harmless no-op, not a panic
        host.tl_release(id);
        assert!(!host.is_alive(id));
    }

    #[test]
    fn expired_chain_handle_does_not_crash_when_its_proxy_is_later_gcd() {
        let (rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields: HashMap::new() });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();
        });

        // simulates `TlHandles.releaseScope` hard-invalidating the handle out from under a proxy
        // that's still JS-reachable at the time
        host.tl_release(id);
        assert!(!host.is_alive(id));

        ctx.with(|ctx| {
            assert!(ctx.eval::<Value, _>("obj.x").is_err(), "already-released handle must read as expired, not crash");
            ctx.eval::<(), _>("obj = undefined").unwrap();
        });

        // the proxy's HandleBox finalizer now fires `tl_release(id)` a second time once GC
        // collects it - must not panic even though the entry is long gone
        rt.run_gc();
        assert!(!host.is_alive(id));
    }

    #[test]
    fn passing_a_live_proxy_back_reuses_its_handle_wire_without_copying() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let id = host.mint(FakeEntry::Object { type_name: "x".to_string(), fields: HashMap::new() });
        let host_dyn: Rc<dyn TlHost> = host;

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            let wire_out = js_value_to_wire(&ctx, proxy).unwrap();
            assert_eq!(wire_out, format!("HO{id}"));
        });
    }

    #[test]
    fn plain_object_falls_back_to_json_wire() {
        let (_rt, ctx) = make_ctx();
        ctx.with(|ctx| {
            let value: Value = ctx.eval("({a: 1})").unwrap();
            let wire = js_value_to_wire(&ctx, value).unwrap();
            assert_eq!(wire, "J{\"a\":1}");
        });
    }

    #[test]
    fn expired_handle_get_throws() {
        let (_rt, ctx) = make_ctx();
        let host: Rc<dyn TlHost> = Rc::new(FakeTlHost::default());

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host, "HO999").unwrap();
            ctx.globals().set("obj", proxy).unwrap();
            assert!(ctx.eval::<Value, _>("obj.x").is_err());
            assert!(ctx.eval::<Value, _>("Object.keys(obj)").is_err());
        });
    }

    #[test]
    fn tojson_detaches_object_into_a_plain_mutation_safe_value() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let mut fields = HashMap::new();
        fields.insert("x".to_string(), FakeValue::Wire("I5".to_string()));
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();

            let json: String = ctx
                .eval("(() => { const c = obj.toJSON(); c.x = 999; return JSON.stringify(c); })()")
                .unwrap();
            assert_eq!(json, r#"{"_":"foo","x":999}"#);
        });

        let entry_rc = host.table.borrow().get(&id).unwrap().clone();
        match &*entry_rc.borrow() {
            FakeEntry::Object { fields, .. } => {
                assert!(matches!(fields.get("x"), Some(FakeValue::Wire(w)) if w == "I5"))
            }
            _ => panic!("expected object entry"),
        };
    }

    #[test]
    fn snapshot_bytes_revive_as_uint8array_and_round_trip_through_stringify() {
        let (_rt, ctx) = make_ctx();
        ctx.with(|ctx| {
            // AQID = [1, 2, 3]
            let value = json_parse_tl(&ctx, r#"{"_":"foo","data":{"$inuBytes":"AQID"}}"#).unwrap();
            ctx.globals().set("snap", value).unwrap();

            let is_u8: bool = ctx.eval("snap.data instanceof Uint8Array").unwrap();
            assert!(is_u8);
            let bytes: Vec<u8> = ctx.eval::<Vec<u8>, _>("Array.from(snap.data)").unwrap();
            assert_eq!(bytes, vec![1, 2, 3]);

            // plugin-side stringify re-wraps via the array's own toJSON
            let json: String = ctx.eval("JSON.stringify(snap)").unwrap();
            assert_eq!(json, r#"{"_":"foo","data":{"$inuBytes":"AQID"}}"#);

            // a plugin-created Uint8Array (no toJSON) is wrapped by the outbound replacer
            let literal: Value = ctx.eval("({_: 'bar', data: new Uint8Array([9, 8])})").unwrap();
            let wire = js_value_to_wire(&ctx, literal).unwrap();
            assert_eq!(wire, r#"J{"_":"bar","data":{"$inuBytes":"CQg="}}"#);
        });
    }

    #[test]
    fn json_stringify_on_a_proxy_uses_tojson_snapshot() {
        let (_rt, ctx) = make_ctx();
        let host = Rc::new(FakeTlHost::default());
        let mut fields = HashMap::new();
        fields.insert("x".to_string(), FakeValue::Wire("I5".to_string()));
        let id = host.mint(FakeEntry::Object { type_name: "foo".to_string(), fields });
        let host_dyn: Rc<dyn TlHost> = host.clone();

        ctx.with(|ctx| {
            let proxy = wire_to_js_value(&ctx, &host_dyn, &format!("HO{id}")).unwrap();
            ctx.globals().set("obj", proxy).unwrap();

            // JSON.stringify must not throw "no such field 'toJSON'"; it serializes the snapshot
            let json: String = ctx.eval("JSON.stringify(obj)").unwrap();
            assert_eq!(json, r#"{"_":"foo","x":5}"#);
            // toJSON is directly callable and detached from the live object
            let typ: String = ctx.eval("typeof obj.toJSON").unwrap();
            assert_eq!(typ, "function");
        });
    }
}
