//! `inu.jvm`: the reflection escape hatch, per `android.jvm.d.ts`.
//!
//! Which class a name resolves to, what an overload takes and what a member declares are facts
//! about a heap this side cannot see, so the whole of it is Kotlin (`PluginJvm`) and this module
//! is the wire plus two rules it *can* decide: the entry point's class name is scope-checked here
//! ([`js_cls`]), and `loadDex` needs the unscoped grant, since dex code runs with the app's own
//! permissions and never crosses this bridge again.
//!
//! `defineClass` and `callSuper` throw `unsupported`. `defineClass` needs the thing this engine
//! does not have: a synchronous answer for java, on whichever thread java called on. [`js_runnable`]
//! works only because it returns nothing and can therefore *post* into the one queue an engine may
//! be entered from. `callSuper` only means anything inside a body `defineClass` would have
//! produced, and `Method.invoke` dispatches virtually, so an approximation of it would recurse.
//!
//! A reflected call runs on `globalQueue` inside a JNI upcall, i.e. with this engine's `RefCell`
//! already borrowed, so a synchronous re-entry is a process abort. Nothing this module mints can
//! do that; what it cannot promise is that *reflection* will not reach a path that does, which is
//! why `PluginJvm` refuses the engine's own package outright.

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{
    Array, Coerced, Context, Ctx, FromJs, Function, IntoJs, Object, Persistent, Result as JsResult, Runtime,
    TypedArray, Value,
};

use crate::grants::{check_grant, GrantHost, MATCH_NAMESPACE};
use crate::sandbox::error::{get_or_create_inu, throw_plugin_error, wire_error_to_js};
use crate::sandbox::registry::{CallbackRegistry, Lifecycle};
use crate::telegram::rpc::{format_exception, pump_jobs};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/jvm.qbc"));

/// stand-in for the Kotlin `QuickJs.JvmListener`
pub trait JvmHost {
    /// one reflection op. `target` is a handle id (0 for the ops that name none), `name` the class
    /// or member, `args` one wire per argument. The answer is always a tagged wire: a scalar
    /// (`N`/`S`/`I`/`D`/`B`/`Y`), a handle (`G<kind><id>`), or an error (`E`/`P`).
    fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String;
}

// keep in sync with Kotlin `PluginJvm.OP_*` and `jvm.js`
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

/// the single grant this api is behind; its scopes are class namespaces
pub const GRANT: &str = "unsafe.jvm";

/// what one value may weigh in either direction, per `android.jvm.d.ts`. A string crosses as a
/// java `String` and a `byte[]` as base64, so the transient cost of one is several times its own
/// size on a heap the plugin's ceiling does not cover - the app's.
pub const VALUE_LIMIT_BYTES: usize = 1024 * 1024;

/// what `loadDex` may take, per `android.jvm.d.ts`, whichever way it arrives. Kotlin holds the same
/// number against the file it is handed; this is the in-memory form, which also has to be
/// base64'd across.
pub const DEX_LIMIT_BYTES: usize = 8 * 1024 * 1024;

pub struct JvmState {
    host: Rc<dyn JvmHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
    /// `inu.jvm.runnable` callbacks. Plugin-lifetime: the java object holding one may be anywhere
    /// in the app by then, so there is no reachability this side could key a release on.
    callbacks: CallbackRegistry,
    /// `jvm.js`'s two halves of the handle representation, saved because every op crosses them
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
    throw_plugin_error(
        ctx,
        "quota-exceeded",
        &format!("jvm: {what} is {size} bytes, over the {limit} this bridge carries"),
        None,
        Some(size as i64),
        Some(limit as i64),
    )
}

/// the id `jvm.js` carries on a handle, or `-1` for anything that is not one
pub(crate) fn handle_id<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, value: &Value<'js>) -> JsResult<i64> {
    let borrowed = state.prelude.borrow();
    let Some(prelude) = borrowed.as_ref() else {
        return Ok(-1);
    };
    let id_of = prelude.id_of.clone().restore(ctx)?;
    id_of.call((value.clone(),))
}

/// one argument on its way to java. Every shape here is a value java can be handed without asking
/// the plugin what type it meant; anything else is refused rather than guessed at.
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
        // an integral js number is an integer as far as java is concerned; the exponent form a
        // `{f}` would print for one is not something a java parse would take back
        if f.fract() == 0.0 && f.abs() <= 9007199254740991.0 {
            return Ok(format!("I{}", f as i64));
        }
        return Ok(format!("D{f}"));
    }
    if value.is_big_int() {
        // through its decimal text rather than `to_i64`, which truncates a bigint too wide for a
        // java long instead of failing - and a silently truncated one is the exact lie the bigint
        // exists to avoid
        let text = Coerced::<String>::from_js(ctx, value.clone())?.0;
        return match text.parse::<i64>() {
            Ok(v) => Ok(format!("I{v}")),
            Err(_) => throw_plugin_error(
                ctx,
                "invalid-argument",
                &format!("jvm: {text} does not fit in a java long"),
                None,
                None,
                None,
            ),
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
            return Ok(format!("Y{}", crate::tl::proxy::base64_encode(bytes)));
        }
    }
    let id = handle_id(ctx, state, value)?;
    if id >= 0 {
        return Ok(format!("G{id}"));
    }
    throw_plugin_error(
        ctx,
        "invalid-argument",
        &format!("jvm: cannot hand a {} to java", value.type_of()),
        None,
        None,
        None,
    )
}

fn bounded_bytes(bytes: &[u8], limit: usize) -> bool {
    bytes.len() <= limit
}

/// one value on its way back from java. The scalars are [`crate::tl::proxy`]'s, so a `Y` means the
/// same thing on both bridges; `G` is a handle, and an `I` too big for a js number is a `bigint`
/// rather than a rounded one - a java `long` is 64 bits wide and an `access_hash` uses all of them.
pub(crate) fn wire_to_value<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, wire: &str) -> JsResult<Value<'js>> {
    if let Some(built) = wire_error_to_js(ctx, wire) {
        return Err(ctx.throw(built?));
    }
    let mut chars = wire.chars();
    let Some(tag) = chars.next() else {
        return throw_plugin_error(ctx, "internal", "jvm: the host answered with an empty wire", None, None, None);
    };
    let payload = chars.as_str();
    if tag == 'I' {
        let Ok(value) = payload.parse::<i64>() else {
            return throw_plugin_error(ctx, "internal", "jvm: the host answered with a bad int", None, None, None);
        };
        if value.unsigned_abs() > 9007199254740991 {
            return Value::new_big_int(ctx.clone(), value);
        }
        return value.into_js(ctx);
    }
    if tag == 'G' {
        let mut kind = payload.chars();
        let Some(kind) = kind.next() else {
            return throw_plugin_error(ctx, "internal", "jvm: the host answered with a bad handle", None, None, None);
        };
        let Ok(id) = payload[kind.len_utf8()..].parse::<i64>() else {
            return throw_plugin_error(ctx, "internal", "jvm: the host answered with a bad handle", None, None, None);
        };
        let borrowed = state.prelude.borrow();
        let Some(prelude) = borrowed.as_ref() else {
            return throw_plugin_error(ctx, "internal", "jvm: the prelude is not installed", None, None, None);
        };
        let mint = prelude.mint.clone().restore(ctx)?;
        return mint.call((kind.to_string(), id));
    }
    match crate::tl::proxy::scalar_wire_to_js(ctx, tag, payload) {
        Some(value) => value,
        None => throw_plugin_error(
            ctx,
            "internal",
            &format!("jvm: the host answered with an unknown tag '{tag}'"),
            None,
            None,
            None,
        ),
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
    // the fine-grained check is the host's, on the class it is about to hand over or the member it
    // is about to reach; this is the same coarse gate every other api keeps at its entry point
    check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
    let mut wires = Vec::new();
    for arg in crate::sandbox::argv::array_values(ctx, &args, "jvm")? {
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
    // allocated before the host is asked, and registered only once it has accepted: the upcall
    // needs the id to build the java object around, and must be able to refuse without leaving a
    // registration behind
    let token = state.callbacks.alloc();
    let handle = ask(ctx, state, OP_RUNNABLE, 0, "", &[format!("I{token}")])?;
    if !state.lifecycle.is_unloading() {
        state.callbacks.register(ctx, token, None, callback);
    }
    Ok(handle)
}

fn js_load_dex<'js>(ctx: &Ctx<'js>, state: &Rc<JvmState>, source: Value<'js>) -> JsResult<()> {
    // not `None`: dex runs outside this bridge, so nothing a scope list says survives it. An
    // unscoped grant is `unsafe.jvm(*)` under NAMESPACE matching, which is what this asks for.
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
            let wire = format!("Y{}", crate::tl::proxy::base64_encode(bytes));
            ask(ctx, state, OP_LOAD_DEX, 0, "", &[wire])?;
            return Ok(());
        }
    }
    throw_plugin_error(ctx, "invalid-argument", "loadDex: expected an absolute path or a Uint8Array", None, None, None)
}

pub fn install_jvm<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn JvmHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
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
    {
        // the op numbers are handed over rather than restated in `jvm.js`: one wire, one place
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
        natives.set("ops", ops)?;
    }
    {
        let state = state.clone();
        let f =
            Function::new(ctx.clone(), move |ctx: Ctx<'js>, op: i32, target: i64, name: String, args: Array<'js>| {
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
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Function<'js>| {
            js_runnable(&ctx, &state, callback)
        })?;
        natives.set("runnable", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, source: Value<'js>| js_load_dex(&ctx, &state, source))?;
        natives.set("loadDex", f)?;
    }
    {
        // the finalizer's, so it can neither throw into a job nor be gated: the handle it names is
        // already unreachable, and refusing to forget it would only leak the java object behind it
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |target: i64| {
            state.host.jvm(OP_RELEASE, target, "", &[]);
        })?;
        natives.set("release", f)?;
    }

    let inu = get_or_create_inu(ctx)?;
    // captured at install, like every other prelude's: what this one throws must not be decidable
    // by a plugin reassigning `inu.PluginError`
    let plugin_error: Value = inu.get("PluginError")?;

    let factory = crate::sandbox::prelude::load(ctx, PRELUDE)?;
    let built: Object = factory.call((natives, plugin_error))?;
    let jvm: Object = built.get("jvm")?;
    let mint: Function = built.get("mint")?;
    let id_of: Function = built.get("idOf")?;
    *state.prelude.borrow_mut() =
        Some(Prelude { mint: Persistent::save(ctx, mint), id_of: Persistent::save(ctx, id_of) });
    inu.set("jvm", jvm)?;
    install_android_screen(ctx, &state, &inu)?;

    Ok(state)
}

/// `inu.android.getCurrentFragment`/`getCurrentActivity`. They belong to this module rather than to
/// `ui.rs` because what they answer is a `JavaObject` and nothing else: the handle is minted by the
/// same upcall every other reference goes through, so the host still checks the runtime class
/// against the scope list - a plugin scoped to one package cannot reach a fragment in another.
///
/// Synchronous, so they answer from whatever the app has right now or not at all: `null` covers a
/// process with no activity (one a push woke), one that is finishing, and a navigation stack that
/// is momentarily empty.
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

/// a java `Runnable` this engine minted was run. Always from a `globalQueue` post, never from
/// inside the call that handed the object over.
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

/// `Persistent` has no `Drop`: an unreleased root aborts `JS_FreeRuntime`.
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
