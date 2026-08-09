use std::cell::Cell;
use std::rc::Rc;

use rquickjs::atom::PredefinedAtom;
use rquickjs::class::{JsClass, Readable, Trace, Tracer};
use rquickjs::proxy::ProxyHandler;
use rquickjs::{
    Array, Class, Constructor, Ctx, Exception, Filter, Function, IntoJs, JsLifetime, Object, Proxy, Result as JsResult,
    Symbol, TypedArray, Value,
};

const BYTES_MARKER_KEY: &str = "$inuBytes";

pub trait TlHost {
    fn tl_get(&self, handle: i64, key: &str) -> String;
    fn tl_set(&self, handle: i64, key: &str, value_wire: &str) -> Option<String>;
    fn tl_has(&self, handle: i64, key: &str) -> i32;
    fn tl_own_keys(&self, handle: i64) -> Option<String>;
    fn tl_copy(&self, handle: i64) -> Option<String>;
    fn tl_release(&self, handle: i64);
}

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

fn throw_expired<'js, T>(ctx: &Ctx<'js>) -> JsResult<T> {
    crate::api::error::throw_handle_expired(ctx, HANDLE_EXPIRED_MESSAGE)
}

fn throw_read_only<'js, T>(ctx: &Ctx<'js>) -> JsResult<T> {
    crate::api::error::throw_forbidden(ctx, READ_ONLY_MESSAGE)
}

fn throw_unsupported<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    crate::api::error::throw_plugin_error(ctx, "unsupported", message, None, None, None)
}

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
            Some(bytes) => make_bytes_value(ctx, bytes),
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
                    wrapper.set(BYTES_MARKER_KEY, base64_encode(bytes))?;
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

pub fn wire_to_js_value<'js>(ctx: &Ctx<'js>, views: &Rc<TlViews>, wire: &str, life: ViewLife) -> JsResult<Value<'js>> {
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
            let (is_vector, read_only, id) =
                parse_handle(payload).ok_or_else(|| Exception::throw_message(ctx, "tl wire: bad handle"))?;
            build_proxy(ctx, views.clone(), is_vector, read_only, life, id)?.into_js(ctx)
        }
        'J' => json_parse_tl(ctx, payload),
        other => throw_tl(ctx, &format!("tl wire: unknown tag '{other}'")),
    }
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
    state.views.bump();
    sync_epoch(state, ctx, target)?;
    match result {
        None => Ok(true),
        Some(err) => Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?)),
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
                    if key == THEN_KEY {
                        return Ok(Value::new_undefined(ctx.clone()));
                    }
                    sync_epoch(&state, &ctx, &target)?;
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
                    let len = vector_length(&ctx, state.host(), state.handle)?.max(0) as usize;
                    for idx in 0..len {
                        arr.set(idx, idx.to_string())?;
                    }
                    arr.set(len, "length")?;
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
                    descriptor.set("configurable", true)?;
                    descriptor.into_js(&ctx)
                },
            )?,
        )?;
    }

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

fn vector_length<'js>(ctx: &Ctx<'js>, host: &Rc<dyn TlHost>, handle: i64) -> JsResult<i64> {
    let wire = host.tl_get(handle, "length");
    if let Some(n) = wire.strip_prefix('I').and_then(|p| p.parse().ok()) {
        return Ok(n);
    }
    match crate::api::error::wire_error_to_js(ctx, &wire) {
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
