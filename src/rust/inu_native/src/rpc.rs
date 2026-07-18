//! Pure JS-plumbing for the `inu.interceptRpc` / `inu.invokeRpc` / `inu.onUpdate` bridge.
//!
//! Deliberately JNI-free: all "Java" interaction is behind [`RpcHost`]/[`crate::tl_proxy::TlHost`],
//! so this module is exercised directly with rquickjs in cargo tests using Rust closure/struct
//! stand-ins for the upcalls. `src/rust/inu_native/src/lib.rs` wires JNI-backed hosts and forwards
//! the JNI exports into the `pub fn`s here.
//!
//! Every request/response value crossing the host boundary is a single [`crate::tl_proxy`]-shaped
//! wire string (`H<O|V><id>` a live handle, `J<json>` a plain-value construct, `E<message>` an
//! error) rather than raw JSON - see `tl_proxy.rs`'s module doc for the full tag list.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Coerced, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::tl_proxy::{self, TlHost};

/// stand-in for the Kotlin `QuickJs.RpcListener` interface; `None` == ok, `Some(msg)` == error.
/// `*_wire` params/returns are [`tl_proxy`]-tagged strings, never raw JSON.
pub trait RpcHost {
    fn on_register(&self, methods: &[String], callback_id: u32) -> Option<String>;
    fn on_invoke(&self, invoke_id: i64, request_wire: &str) -> Option<String>;
    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String>;
    fn on_complete(&self, dispatch_id: i64, result_wire: &str);
    fn on_update_register(&self, callback_id: u32) -> Option<String>;
}

pub(crate) struct PendingSettle {
    pub(crate) resolve: Persistent<Function<'static>>,
    pub(crate) reject: Persistent<Function<'static>>,
}

impl PendingSettle {
    /// mints a promise and saves its resolvers as roots
    pub(crate) fn new<'js>(ctx: &Ctx<'js>) -> JsResult<(rquickjs::Promise<'js>, Self)> {
        let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
        Ok((
            promise,
            PendingSettle {
                resolve: Persistent::save(ctx, resolve),
                reject: Persistent::save(ctx, reject),
            },
        ))
    }

    /// releases both roots and rejects with an `Error(msg)`
    pub(crate) fn reject_with(self, ctx: &Ctx<'_>, msg: &str) -> JsResult<()> {
        let error_val = match make_error(ctx, msg) {
            Ok(v) => v,
            Err(e) => {
                self.release(ctx);
                return Err(e);
            }
        };
        self.reject_with_value(ctx, error_val)
    }

    /// releases both roots and rejects with an already-built error value
    pub(crate) fn reject_with_value<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
        let reject = self.reject.restore(ctx)?;
        let _ = self.resolve.restore(ctx);
        reject.call::<_, Value>((value,))?;
        Ok(())
    }

    /// releases both roots and resolves with [`value`]
    pub(crate) fn resolve_with<'js>(self, ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<()> {
        let resolve = self.resolve.restore(ctx)?;
        let _ = self.reject.restore(ctx);
        resolve.call::<_, Value>((value,))?;
        Ok(())
    }

    /// releases both roots without settling (dispose paths)
    pub(crate) fn release(self, ctx: &Ctx<'_>) {
        let _ = self.resolve.restore(ctx);
        let _ = self.reject.restore(ctx);
    }
}

#[derive(Default)]
struct DispatchState {
    called: Cell<bool>,
    settled: Cell<bool>,
    want_passthrough: Cell<bool>,
    next_response: RefCell<Option<String>>,
    next_resolvers: RefCell<Option<PendingSettle>>,
}

pub struct RpcState {
    host: Rc<dyn RpcHost>,
    tl_host: Rc<dyn TlHost>,
    pub(crate) log: Rc<dyn Fn(&str)>,
    next_callback_id: Cell<u32>,
    next_invoke_id: Cell<i64>,
    intercept_fns: RefCell<HashMap<u32, Persistent<Function<'static>>>>,
    update_fns: RefCell<HashMap<u32, Persistent<Function<'static>>>>,
    dispatches: RefCell<HashMap<i64, Rc<DispatchState>>>,
    pending_invoke: RefCell<HashMap<i64, PendingSettle>>,
}

impl RpcState {
    fn alloc_callback_id(&self) -> u32 {
        let id = self.next_callback_id.get();
        self.next_callback_id.set(id + 1);
        id
    }

    fn alloc_invoke_id(&self) -> i64 {
        let id = self.next_invoke_id.get();
        self.next_invoke_id.set(id + 1);
        id
    }
}

// -- small JSON/error/wire helpers --

pub(crate) fn make_error<'js>(ctx: &Ctx<'js>, message: &str) -> JsResult<Value<'js>> {
    let ctor: rquickjs::function::Constructor = ctx.globals().get("Error")?;
    ctor.construct((message,))
}

fn get_rpc_error_ctor<'js>(ctx: &Ctx<'js>) -> JsResult<rquickjs::function::Constructor<'js>> {
    ctx.globals().get::<_, Object>("inu")?.get("RpcError")
}

fn make_rpc_error<'js>(ctx: &Ctx<'js>, code: i32, text: &str) -> JsResult<Value<'js>> {
    get_rpc_error_ctor(ctx)?.construct((code, text))
}

/// `Some(R-wire)` if `value` is an `inu.RpcError` instance (code/text preserved structurally)
fn rpc_error_to_wire<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> Option<String> {
    let obj = value.as_object()?;
    let ctor = get_rpc_error_ctor(ctx).ok()?;
    if !obj.is_instance_of(ctor.into_value()) {
        return None;
    }
    let code = obj.get::<_, i32>("code").ok()?;
    let text = obj.get::<_, String>("text").ok()?;
    Some(tl_proxy::encode_rpc_error(code, &text))
}

/// encodes a middleware's thrown/rejected value into a result wire: `R` when it's an
/// `inu.RpcError`, `E` with its message otherwise
fn thrown_to_result_wire<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    rpc_error_to_wire(ctx, value)
        .unwrap_or_else(|| tl_proxy::encode_error(&error_value_to_string(ctx, value)))
}

/// resolves or rejects `settle` with a decoded wire value, routing on its `E`/`R`-tag.
/// consumes the settle: both roots are released whatever happens
fn settle_from_wire<'js>(ctx: &Ctx<'js>, tl_host: &Rc<dyn TlHost>, settle: PendingSettle, wire: &str) -> JsResult<()> {
    let built = if let Some((code, text)) = tl_proxy::wire_rpc_error(wire) {
        make_rpc_error(ctx, code, text).map(|v| (v, true))
    } else if let Some(message) = tl_proxy::wire_error_message(wire) {
        make_error(ctx, message).map(|v| (v, true))
    } else {
        tl_proxy::wire_to_js_value(ctx, tl_host, wire).map(|v| (v, false))
    };
    match built {
        Ok((value, is_error)) => {
            if is_error {
                settle.reject_with_value(ctx, value)
            } else {
                settle.resolve_with(ctx, value)
            }
        }
        Err(e) => {
            settle.release(ctx);
            Err(e)
        }
    }
}

/// formats a thrown/rejected JS value into "message\nstack" (stack appended when present)
fn format_thrown<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    use rquickjs::FromJs;
    let mut msg = Coerced::<String>::from_js(ctx, value.clone())
        .map(|c| c.0)
        .unwrap_or_else(|_| "JS exception".to_string());
    if let Some(obj) = value.as_object() {
        if let Ok(stack) = obj.get::<_, String>("stack") {
            if !stack.is_empty() {
                msg.push('\n');
                msg.push_str(&stack);
            }
        }
    }
    msg
}

/// formats the pending exception (already raised; caught via ctx.catch) into "message\nstack"
pub(crate) fn format_exception(ctx: &Ctx) -> String {
    format_thrown(ctx, &ctx.catch())
}

struct RejectionSlot {
    log: Rc<dyn Fn(&str)>,
    pending: HashMap<u64, String>,
}

thread_local! {
    // per-JSContext (keyed by its raw pointer) rejection state. engines run single-threaded on
    // globalQueue, so a thread-local keyed by context is enough to isolate them without locking.
    static REJECTIONS: RefCell<HashMap<usize, RejectionSlot>> = RefCell::new(HashMap::new());
}

fn ctx_key(ctx: &Ctx) -> usize {
    ctx.as_raw().as_ptr() as usize
}

/// identity key for a promise value (Value's Hash is tag+bits, i.e. the heap pointer for objects),
/// stable across the false/true tracker callbacks for the same promise
fn value_hash(value: &Value) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut h = DefaultHasher::new();
    value.hash(&mut h);
    h.finish()
}

/// installs a host tracker that surfaces unhandled promise rejections (e.g. an `async` onUpdate
/// handler that throws, or any plugin code awaiting a rejecting promise without a `.catch`) —
/// quickjs otherwise drops them silently. quickjs reports a rejection with `is_handled=false` at
/// rejection time and `is_handled=true` if a handler is attached later (the `Promise.reject(x)
/// .catch(...)` case), so we only *record* here and let [`report_rejections`] surface whatever is
/// still unhandled once the microtask queue drains. Installed per-engine regardless of RPC grant.
pub fn install_rejection_tracker(rt: &Runtime, log: Rc<dyn Fn(&str)>) {
    rt.set_host_promise_rejection_tracker(Some(Box::new(move |ctx, promise, reason, is_handled| {
        let key = ctx_key(&ctx);
        let phash = value_hash(&promise);
        if is_handled {
            REJECTIONS.with(|r| {
                if let Some(slot) = r.borrow_mut().get_mut(&key) {
                    slot.pending.remove(&phash);
                }
            });
        } else {
            let msg = format_thrown(&ctx, &reason);
            REJECTIONS.with(|r| {
                r.borrow_mut()
                    .entry(key)
                    .or_insert_with(|| RejectionSlot { log: log.clone(), pending: HashMap::new() })
                    .pending
                    .insert(phash, msg);
            });
        }
    })));
}

/// logs every rejection that stayed unhandled for [`ctx`]'s context, then clears them
fn report_rejections(ctx: &Ctx) {
    let drained = REJECTIONS.with(|r| {
        let mut map = r.borrow_mut();
        let slot = map.get_mut(&ctx_key(ctx))?;
        if slot.pending.is_empty() {
            return None;
        }
        let msgs: Vec<String> = slot.pending.drain().map(|(_, v)| v).collect();
        Some((slot.log.clone(), msgs))
    });
    if let Some((log, msgs)) = drained {
        for msg in msgs {
            log(&format!("unhandled promise rejection: {msg}"));
        }
    }
}

/// drops a context's rejection-tracking slot; call on engine teardown so a later context reusing
/// the same address can't inherit stale pending entries
pub fn dispose_rejection_tracker(ctx: &Ctx) {
    REJECTIONS.with(|r| {
        r.borrow_mut().remove(&ctx_key(ctx));
    });
}

fn error_value_to_string<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    use rquickjs::FromJs;
    if let Some(obj) = value.as_object() {
        if let Ok(msg) = obj.get::<_, String>("message") {
            if !msg.is_empty() {
                return msg;
            }
        }
    }
    Coerced::<String>::from_js(ctx, value.clone())
        .map(|c| c.0)
        .unwrap_or_else(|_| "unknown error".to_string())
}

/// normalizes `result` (a value, a thenable or a Promise) via `Promise.resolve(result).then(ok, err)`
fn resolve_and_then<'js>(
    ctx: &Ctx<'js>,
    result: Value<'js>,
    ok: Function<'js>,
    err: Function<'js>,
) -> JsResult<()> {
    use rquickjs::function::This;
    let promise_ctor: Object = ctx.globals().get("Promise")?;
    let resolve_fn: Function = promise_ctor.get("resolve")?;
    let resolved: Object = resolve_fn.call((This(promise_ctor), result))?;
    let then_fn: Function = resolved.get("then")?;
    then_fn.call::<_, Value>((This(resolved), ok, err))?;
    Ok(())
}

/// drains the runtime's microtask queue (logging but not propagating job exceptions), then surfaces
/// any promise rejections that stayed unhandled once the queue settled
pub fn pump_jobs(rt: &Runtime, context: &rquickjs::Context, log: &dyn Fn(&str)) {
    loop {
        match rt.execute_pending_job() {
            Ok(true) => continue,
            Ok(false) => break,
            Err(e) => {
                log(&format!("unhandled error running microtask: {e:?}"));
                break;
            }
        }
    }
    context.with(|ctx| report_rejections(&ctx));
}

// -- install --

pub(crate) fn get_or_create_inu<'js>(ctx: &Ctx<'js>) -> JsResult<Object<'js>> {
    match ctx.globals().get::<_, Object>("inu") {
        Ok(o) => Ok(o),
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            ctx.globals().set("inu", o.clone())?;
            Ok(o)
        }
    }
}

pub fn install_rpc<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn RpcHost>,
    tl_host: Rc<dyn TlHost>,
    log: Rc<dyn Fn(&str)>,
    allow_intercept: bool,
    allow_invoke: bool,
    allow_updates: bool,
) -> JsResult<Rc<RpcState>> {
    let state = Rc::new(RpcState {
        host,
        tl_host,
        log,
        next_callback_id: Cell::new(1),
        next_invoke_id: Cell::new(1),
        intercept_fns: RefCell::new(HashMap::new()),
        update_fns: RefCell::new(HashMap::new()),
        dispatches: RefCell::new(HashMap::new()),
        pending_invoke: RefCell::new(HashMap::new()),
    });

    let inu = get_or_create_inu(ctx)?;

    let rpc_error_ctor: Value = ctx.eval(
        r#"(class RpcError extends Error {
            constructor(code, text) {
                super(code + ': ' + text);
                this.name = 'RpcError';
                this.code = code | 0;
                this.text = String(text ?? '');
            }
        })"#,
    )?;
    inu.set("RpcError", rpc_error_ctor)?;

    if allow_intercept {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, methods: Value<'js>, cb: Function<'js>| {
            js_intercept_rpc(&ctx, &state2, methods, cb)
        })?;
        inu.set("interceptRpc", f)?;
    }
    if allow_invoke {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, obj: Value<'js>| {
            js_invoke_rpc(&ctx, &state2, obj)
        })?;
        inu.set("invokeRpc", f)?;
    }
    if allow_updates {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
            js_on_update(&ctx, &state2, cb)
        })?;
        inu.set("onUpdate", f)?;
    }
    Ok(state)
}

// -- inu.interceptRpc --

fn js_intercept_rpc<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    methods: Value<'js>,
    cb: Function<'js>,
) -> JsResult<()> {
    let list: Vec<String> = if let Some(s) = methods.as_string() {
        vec![s.to_string()?]
    } else if let Some(arr) = methods.as_array() {
        let mut out = Vec::with_capacity(arr.len());
        for item in arr.iter::<Value>() {
            let item = item?;
            let s = item
                .as_string()
                .ok_or_else(|| Exception::throw_type(ctx, "interceptRpc: method list must contain only strings"))?
                .to_string()?;
            out.push(s);
        }
        out
    } else {
        return Err(Exception::throw_type(ctx, "interceptRpc: method must be a string or an array of strings"));
    };
    if list.is_empty() {
        return Err(Exception::throw_type(ctx, "interceptRpc: method list must not be empty"));
    }

    let callback_id = state.alloc_callback_id();
    if let Some(err) = state.host.on_register(&list, callback_id) {
        return Err(Exception::throw_type(ctx, &err));
    }
    state
        .intercept_fns
        .borrow_mut()
        .insert(callback_id, Persistent::save(ctx, cb));
    Ok(())
}

// -- inu.invokeRpc --

fn js_invoke_rpc<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>, obj: Value<'js>) -> JsResult<Value<'js>> {
    let wire = tl_proxy::js_value_to_wire(ctx, obj)?;
    let invoke_id = state.alloc_invoke_id();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_invoke.borrow_mut().insert(invoke_id, pending);

    if let Some(err) = state.host.on_invoke(invoke_id, &wire) {
        if let Some(pending) = state.pending_invoke.borrow_mut().remove(&invoke_id) {
            pending.reject_with(ctx, &err)?;
        }
    }
    Ok(promise.into_value())
}

/// NOTE on locking: `Context::with` holds the runtime's lock for its whole closure. `pump_jobs`
/// re-locks the same runtime via `Runtime::execute_pending_job`, so it must run *after* the
/// `with` block returns - never nested inside one, or the reentrant lock panics (and, since job
/// callbacks run through quickjs's C job queue, that panic unwinds across an FFI boundary and
/// aborts the process instead of failing gracefully). Every public entry point below follows the
/// `context.with(|ctx| { ...sync work... }); pump_jobs(rt, ...);` shape for this reason.
pub fn resolve_invoke(rt: &Runtime, context: &rquickjs::Context, state: &Rc<RpcState>, invoke_id: i64, result_wire: &str) {
    context.with(|ctx| {
        if let Some(pending) = state.pending_invoke.borrow_mut().remove(&invoke_id) {
            if let Err(e) = settle_from_wire(&ctx, &state.tl_host, pending, result_wire) {
                (state.log)(&format!("resolveInvoke({invoke_id}) failed: {e:?}"));
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

// -- inu.onUpdate --

fn js_on_update<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>, cb: Function<'js>) -> JsResult<()> {
    let callback_id = state.alloc_callback_id();
    if let Some(err) = state.host.on_update_register(callback_id) {
        return Err(Exception::throw_type(ctx, &err));
    }
    state.update_fns.borrow_mut().insert(callback_id, Persistent::save(ctx, cb));
    Ok(())
}

pub fn dispatch_update(rt: &Runtime, context: &rquickjs::Context, state: &Rc<RpcState>, update_json: &str) {
    if state.update_fns.borrow().is_empty() {
        return;
    }
    context.with(|ctx| {
        let value = match tl_proxy::json_parse_tl(&ctx, update_json) {
            Ok(v) => v,
            Err(e) => {
                (state.log)(&format!("dispatchUpdate: bad update JSON: {e:?}"));
                return;
            }
        };
        let ids: Vec<u32> = state.update_fns.borrow().keys().copied().collect();
        for id in ids {
            let persistent = match state.update_fns.borrow().get(&id) {
                Some(p) => p.clone(),
                None => continue,
            };
            let f = match persistent.restore(&ctx) {
                Ok(f) => f,
                Err(e) => {
                    (state.log)(&format!("dispatchUpdate: failed to restore callback {id}: {e:?}"));
                    continue;
                }
            };
            match f.call::<_, Value>((value.clone(),)) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&format!("onUpdate callback threw: {}", format_exception(&ctx)));
                }
                Err(e) => {
                    (state.log)(&format!("onUpdate callback failed: {e:?}"));
                }
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

// -- inu.interceptRpc dispatch/settle --

fn complete_dispatch(ctx: &Ctx<'_>, state: &Rc<RpcState>, dstate: &Rc<DispatchState>, dispatch_id: i64, result_wire: &str) {
    if dstate.settled.replace(true) {
        return;
    }
    state.dispatches.borrow_mut().remove(&dispatch_id);
    // if the middleware settled without ever awaiting its own next() call, the next() promise's
    // resolve/reject are still rooted in dstate.next_resolvers - release them here so they don't
    // outlive the runtime (Persistent has no Drop; an unreleased root aborts JS_FreeRuntime).
    if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
        pending.release(ctx);
    }
    state.host.on_complete(dispatch_id, result_wire);
}

fn try_dispatch_rpc<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    callback_id: u32,
    dispatch_id: i64,
    method: &str,
    request_wire: &str,
) -> JsResult<()> {
    let persistent = state
        .intercept_fns
        .borrow()
        .get(&callback_id)
        .cloned()
        .ok_or_else(|| Exception::throw_message(ctx, "no such interceptor"))?;
    let middleware = persistent.restore(ctx)?;
    let request_value = tl_proxy::wire_to_js_value(ctx, &state.tl_host, request_wire)?;

    let dstate = Rc::new(DispatchState::default());
    state.dispatches.borrow_mut().insert(dispatch_id, dstate.clone());

    let next_fn = {
        let state = state.clone();
        let dstate = dstate.clone();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, req: Value<'js>| -> JsResult<Value<'js>> {
            if dstate.called.replace(true) {
                return Err(Exception::throw_type(&ctx, "next() may only be called once"));
            }
            let wire = tl_proxy::js_value_to_wire(&ctx, req)?;
            let (promise, pending) = PendingSettle::new(&ctx)?;
            *dstate.next_resolvers.borrow_mut() = Some(pending);
            if let Some(err) = state.host.on_next(dispatch_id, &wire) {
                if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
                    pending.reject_with(&ctx, &err)?;
                }
            }
            Ok(promise.into_value())
        })?
    };

    let call_result = middleware.call::<_, Value>((request_value, next_fn));
    let result_value = match call_result {
        Ok(v) => v,
        Err(rquickjs::Error::Exception) => {
            let caught = ctx.catch();
            (state.log)(&format!("interceptRpc({method}) callback threw: {}", format_thrown(ctx, &caught)));
            let wire = thrown_to_result_wire(ctx, &caught);
            complete_dispatch(ctx, state, &dstate, dispatch_id, &wire);
            return Ok(());
        }
        Err(e) => return Err(e),
    };

    let ok_fn = {
        let state = state.clone();
        let dstate = dstate.clone();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<()> {
            if value.is_undefined() {
                if dstate.called.get() {
                    let stored = dstate.next_response.borrow().clone();
                    match stored {
                        Some(wire) => complete_dispatch(&ctx, &state, &dstate, dispatch_id, &wire),
                        None => dstate.want_passthrough.set(true),
                    }
                } else {
                    complete_dispatch(&ctx, &state, &dstate, dispatch_id, &tl_proxy::encode_error("middleware returned undefined"));
                }
            } else {
                let wire = match rpc_error_to_wire(&ctx, &value) {
                    Some(wire) => wire,
                    None => tl_proxy::js_value_to_wire(&ctx, value)?,
                };
                complete_dispatch(&ctx, &state, &dstate, dispatch_id, &wire);
            }
            Ok(())
        })?
    };
    let err_fn = {
        let state = state.clone();
        let dstate = dstate.clone();
        let method = method.to_string();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| -> JsResult<()> {
            (state.log)(&format!("interceptRpc({method}) callback rejected: {}", error_value_to_string(&ctx, &value)));
            let wire = thrown_to_result_wire(&ctx, &value);
            complete_dispatch(&ctx, &state, &dstate, dispatch_id, &wire);
            Ok(())
        })?
    };

    resolve_and_then(ctx, result_value, ok_fn, err_fn)
}

pub fn dispatch_rpc(rt: &Runtime, context: &rquickjs::Context, state: &Rc<RpcState>, callback_id: u32, dispatch_id: i64, method: &str, request_wire: &str) {
    context.with(|ctx| {
        if let Err(e) = try_dispatch_rpc(&ctx, state, callback_id, dispatch_id, method, request_wire) {
            let msg = match e {
                rquickjs::Error::Exception => format_exception(&ctx),
                other => other.to_string(),
            };
            (state.log)(&format!("interceptRpc({method}) dispatch failed: {msg}"));
            state.host.on_complete(dispatch_id, &tl_proxy::encode_error(&msg));
            if let Some(dstate) = state.dispatches.borrow_mut().remove(&dispatch_id) {
                if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
                    pending.release(&ctx);
                }
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

pub fn complete_next(rt: &Runtime, context: &rquickjs::Context, state: &Rc<RpcState>, dispatch_id: i64, result_wire: &str) {
    context.with(|ctx| {
        let dstate = match state.dispatches.borrow().get(&dispatch_id).cloned() {
            Some(d) => d,
            None => return,
        };
        *dstate.next_response.borrow_mut() = Some(result_wire.to_string());

        if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
            if let Err(e) = settle_from_wire(&ctx, &state.tl_host, pending, result_wire) {
                (state.log)(&format!("completeNext({dispatch_id}) failed to settle next(): {e:?}"));
            }
        }

        if dstate.want_passthrough.get() {
            complete_dispatch(&ctx, state, &dstate, dispatch_id, result_wire);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// Restores every `Persistent` this state still owns into `ctx` and immediately drops the
/// resulting `Value`, releasing quickjs's GC roots. Must run (with a live `Runtime`/`Context`)
/// before the engine's `Runtime` is dropped — `Persistent` has no `Drop` impl of its own, and an
/// unreleased root left dangling makes `JS_FreeRuntime` abort the process (see rquickjs's
/// `Persistent` docs). Call once, right before tearing down the engine.
pub fn dispose(context: &rquickjs::Context, state: &Rc<RpcState>) {
    context.with(|ctx| {
        for (_, p) in state.intercept_fns.borrow_mut().drain() {
            let _ = p.restore(&ctx);
        }
        for (_, p) in state.update_fns.borrow_mut().drain() {
            let _ = p.restore(&ctx);
        }
        for (_, pending) in state.pending_invoke.borrow_mut().drain() {
            pending.release(&ctx);
        }
        for (_, dstate) in state.dispatches.borrow_mut().drain() {
            if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
                pending.release(&ctx);
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::Context;

    #[derive(Default)]
    struct TestHost {
        registered: RefCell<Vec<(Vec<String>, u32)>>,
        next_calls: RefCell<Vec<(i64, String)>>,
        invoke_calls: RefCell<Vec<(i64, String)>>,
        update_registered: RefCell<Vec<u32>>,
        completes: RefCell<Vec<(i64, String)>>,
        register_err: RefCell<Option<String>>,
    }

    impl RpcHost for TestHost {
        fn on_register(&self, methods: &[String], callback_id: u32) -> Option<String> {
            self.registered.borrow_mut().push((methods.to_vec(), callback_id));
            self.register_err.borrow().clone()
        }
        fn on_invoke(&self, invoke_id: i64, request_wire: &str) -> Option<String> {
            self.invoke_calls.borrow_mut().push((invoke_id, request_wire.to_string()));
            None
        }
        fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String> {
            self.next_calls.borrow_mut().push((dispatch_id, request_wire.to_string()));
            None
        }
        fn on_complete(&self, dispatch_id: i64, result_wire: &str) {
            self.completes.borrow_mut().push((dispatch_id, result_wire.to_string()));
        }
        fn on_update_register(&self, callback_id: u32) -> Option<String> {
            self.update_registered.borrow_mut().push(callback_id);
            None
        }
    }

    /// stand-in [`TlHost`]: tests only exercise plain-value (`J`-tagged) request/response wires,
    /// never live handles, so every trap upcall here is unreachable and intentionally stubbed.
    struct TestTlHost;
    impl TlHost for TestTlHost {
        fn tl_get(&self, _handle: i64, _key: &str) -> String {
            tl_proxy::encode_error("TestTlHost: no live handles in tests")
        }
        fn tl_set(&self, _handle: i64, _key: &str, _value_wire: &str) -> Option<String> {
            Some("TestTlHost: no live handles in tests".to_string())
        }
        fn tl_has(&self, _handle: i64, _key: &str) -> i32 {
            -1
        }
        fn tl_own_keys(&self, _handle: i64) -> Option<String> {
            None
        }
        fn tl_copy(&self, _handle: i64) -> Option<String> {
            None
        }
        fn tl_release(&self, _handle: i64) {}
    }

    fn wire_json(json: &str) -> String {
        format!("J{json}")
    }

    fn setup(allow_intercept: bool, allow_invoke: bool, allow_updates: bool) -> (Runtime, Context, Rc<TestHost>, Rc<RpcState>) {
        let (rt, ctx, host, state, _log) = setup_logging(allow_intercept, allow_invoke, allow_updates);
        (rt, ctx, host, state)
    }

    /// like [`setup`] but captures every `log()` upcall so tests can assert on emitted diagnostics
    fn setup_logging(
        allow_intercept: bool,
        allow_invoke: bool,
        allow_updates: bool,
    ) -> (Runtime, Context, Rc<TestHost>, Rc<RpcState>, Rc<RefCell<Vec<String>>>) {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let host = Rc::new(TestHost::default());
        let host_dyn: Rc<dyn RpcHost> = host.clone();
        let tl_host: Rc<dyn TlHost> = Rc::new(TestTlHost);
        let logs = Rc::new(RefCell::new(Vec::<String>::new()));
        let logs2 = logs.clone();
        let log: Rc<dyn Fn(&str)> = Rc::new(move |msg: &str| logs2.borrow_mut().push(msg.to_string()));
        let state = ctx.with(|ctx| install_rpc(&ctx, host_dyn, tl_host, log, allow_intercept, allow_invoke, allow_updates).unwrap());
        (rt, ctx, host, state, logs)
    }

    #[test]
    fn middleware_transforms_request_then_passes_through_next_response() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    req.x = req.x + 1;
                    return next(req);
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 100, "foo.bar", &wire_json(r#"{"_":"foo.bar","x":1}"#));

        assert_eq!(host.next_calls.borrow().len(), 1);
        assert_eq!(host.next_calls.borrow()[0].1, wire_json(r#"{"_":"foo.bar","x":2}"#));
        assert!(host.completes.borrow().is_empty());

        complete_next(&rt, &ctx, &state, 100, &wire_json(r#"{"_":"foo.bar","x":2,"ok":true}"#));

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (100, wire_json(r#"{"_":"foo.bar","x":2,"ok":true}"#)));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn middleware_returns_undefined_without_awaiting_passes_through_next() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    next(req);
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 500, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        assert!(host.completes.borrow().is_empty());

        complete_next(&rt, &ctx, &state, 500, &wire_json(r#"{"_":"foo.bar","done":true}"#));
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (500, wire_json(r#"{"_":"foo.bar","done":true}"#)));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn short_circuit_without_next() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    return { _: 'foo.bar', short: true };
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 200, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));

        assert!(host.next_calls.borrow().is_empty());
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (200, wire_json(r#"{"_":"foo.bar","short":true}"#)));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn async_middleware_promise_result() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', async (req, next) => {
                    await Promise.resolve();
                    return { _: 'foo.bar', async: true };
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 300, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (300, wire_json(r#"{"_":"foo.bar","async":true}"#)));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn next_called_twice_throws_type_error() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    next(req);
                    next(req);
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 400, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0].0, 400);
        assert!(completes[0].1.starts_with('E'));
        assert!(completes[0].1.contains("only be called once"));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn invoke_rpc_resolves_and_rejects() {
        let (rt, ctx, host, state) = setup(false, true, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__ok = null;
                globalThis.__err = null;
                inu.invokeRpc({_:'foo.bar'}).then(r => { globalThis.__ok = r; });
                "#,
            )
            .unwrap();
        });

        assert_eq!(host.invoke_calls.borrow().len(), 1);
        let invoke_id = host.invoke_calls.borrow()[0].0;
        resolve_invoke(&rt, &ctx, &state, invoke_id, &wire_json(r#"{"_":"foo.bar","ok":true}"#));
        let ok: String = ctx.with(|ctx| ctx.eval::<String, _>("JSON.stringify(globalThis.__ok)").unwrap());
        assert_eq!(ok, r#"{"_":"foo.bar","ok":true}"#);

        ctx.with(|ctx| {
            ctx.eval::<(), _>(r#"inu.invokeRpc({_:'foo.baz'}).catch(e => { globalThis.__err = e.message; });"#)
                .unwrap();
        });
        assert_eq!(host.invoke_calls.borrow().len(), 2);
        let invoke_id2 = host.invoke_calls.borrow()[1].0;
        resolve_invoke(&rt, &ctx, &state, invoke_id2, "EBAD_REQUEST: oops");
        let err: String = ctx.with(|ctx| ctx.eval::<String, _>("globalThis.__err").unwrap());
        assert_eq!(err, "BAD_REQUEST: oops");
        dispose(&ctx, &state);
    }

    #[test]
    fn rpc_error_wire_rejects_as_rpc_error_instance_and_rethrow_round_trips() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__caught = null;
                inu.interceptRpc('foo.bar', async (req, next) => {
                    try {
                        return await next(req);
                    } catch (e) {
                        globalThis.__caught = { isRpc: e instanceof inu.RpcError, code: e.code, text: e.text, message: e.message };
                        throw e;
                    }
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 900, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        complete_next(&rt, &ctx, &state, 900, "R400:PEER_ID_INVALID");

        let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
        assert_eq!(caught, r#"{"isRpc":true,"code":400,"text":"PEER_ID_INVALID","message":"400: PEER_ID_INVALID"}"#);
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (900, "R400:PEER_ID_INVALID".to_string()));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn thrown_rpc_error_completes_with_r_wire() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    throw new inu.RpcError(420, 'FLOOD_WAIT_3');
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 901, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (901, "R420:FLOOD_WAIT_3".to_string()));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn returned_rpc_error_completes_with_r_wire() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    return new inu.RpcError(403, 'FORBIDDEN');
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 902, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (902, "R403:FORBIDDEN".to_string()));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn invoke_rejection_with_rpc_error_wire_is_an_rpc_error_instance() {
        let (rt, ctx, host, state) = setup(false, true, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__caught = null;
                inu.invokeRpc({_:'foo.bar'}).catch(e => {
                    globalThis.__caught = { isRpc: e instanceof inu.RpcError, code: e.code, text: e.text };
                });
                "#,
            )
            .unwrap();
        });

        let invoke_id = host.invoke_calls.borrow()[0].0;
        resolve_invoke(&rt, &ctx, &state, invoke_id, "R-503:Timeout");
        let caught: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__caught)").unwrap());
        assert_eq!(caught, r#"{"isRpc":true,"code":-503,"text":"Timeout"}"#);
        dispose(&ctx, &state);
    }

    #[test]
    fn null_completion_resolves_next_as_null_and_round_trips() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__got = 'unset';
                inu.interceptRpc('foo.bar', async (req, next) => {
                    const r = await next(req);
                    globalThis.__got = r;
                    return r;
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 903, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        complete_next(&rt, &ctx, &state, 903, "N");

        let got_is_null: bool = ctx.with(|ctx| ctx.eval("globalThis.__got === null").unwrap());
        assert!(got_is_null);
        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (903, "N".to_string()));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn on_update_fan_out_survives_throwing_callback() {
        let (rt, ctx, host, state) = setup(false, false, true);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                globalThis.__seen = null;
                inu.onUpdate(u => { throw new Error('boom'); });
                inu.onUpdate(u => { globalThis.__seen = u.a; });
                "#,
            )
            .unwrap();
        });

        assert_eq!(host.update_registered.borrow().len(), 2);
        dispatch_update(&rt, &ctx, &state, r#"{"_":"updateFoo","a":42}"#);
        let seen: i32 = ctx.with(|ctx| ctx.eval::<i32, _>("globalThis.__seen").unwrap());
        assert_eq!(seen, 42);
        dispose(&ctx, &state);
    }

    #[test]
    fn registration_rejected_drops_callback() {
        let (_rt, ctx, _host, state) = setup(true, false, false);
        *_host.register_err.borrow_mut() = Some("not granted".to_string());
        let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.interceptRpc('foo.bar', (req,next) => req);").is_err());
        assert!(threw);
        assert!(state.intercept_fns.borrow().is_empty());
    }

    #[test]
    fn middleware_error_rejects_next_and_completes_with_error_wire() {
        let (rt, ctx, host, state) = setup(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    return next(req).catch(e => ({ _: 'foo.bar', caught: e.message }));
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 600, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));
        complete_next(&rt, &ctx, &state, 600, "Eboom");

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0], (600, wire_json(r#"{"_":"foo.bar","caught":"boom"}"#)));
        drop(completes);
        dispose(&ctx, &state);
    }

    #[test]
    fn throwing_interceptor_is_logged_and_completes_with_error() {
        let (rt, ctx, host, state, logs) = setup_logging(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', (req, next) => {
                    throw new Error('kaboom');
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 700, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert_eq!(completes[0].0, 700);
        assert!(completes[0].1.starts_with('E'));
        assert!(completes[0].1.contains("kaboom"));
        drop(completes);

        assert!(
            logs.borrow().iter().any(|l| l.contains("interceptRpc(foo.bar) callback threw") && l.contains("kaboom")),
            "expected a logged diagnostic, got: {:?}",
            logs.borrow(),
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn rejecting_interceptor_is_logged_and_completes_with_error() {
        let (rt, ctx, host, state, logs) = setup_logging(true, false, false);
        ctx.with(|ctx| {
            ctx.eval::<(), _>(
                r#"
                inu.interceptRpc('foo.bar', async (req, next) => {
                    throw new Error('async-boom');
                });
                "#,
            )
            .unwrap();
        });

        dispatch_rpc(&rt, &ctx, &state, 1, 800, "foo.bar", &wire_json(r#"{"_":"foo.bar"}"#));

        let completes = host.completes.borrow();
        assert_eq!(completes.len(), 1);
        assert!(completes[0].1.starts_with('E'));
        assert!(completes[0].1.contains("async-boom"));
        drop(completes);

        assert!(
            logs.borrow().iter().any(|l| l.contains("interceptRpc(foo.bar) callback rejected") && l.contains("async-boom")),
            "expected a logged diagnostic, got: {:?}",
            logs.borrow(),
        );
        dispose(&ctx, &state);
    }

    #[test]
    fn unhandled_rejection_is_logged() {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let logs = Rc::new(RefCell::new(Vec::<String>::new()));
        let logs2 = logs.clone();
        let log: Rc<dyn Fn(&str)> = Rc::new(move |m: &str| logs2.borrow_mut().push(m.to_string()));
        install_rejection_tracker(&rt, log.clone());

        ctx.with(|ctx| {
            // an async handler that throws with nothing awaiting/catching it (the async onUpdate case)
            ctx.eval::<(), _>(r#"(async () => { throw new Error('nope'); })();"#).unwrap();
        });
        pump_jobs(&rt, &ctx, log.as_ref());

        assert!(
            logs.borrow().iter().any(|l| l.contains("unhandled promise rejection") && l.contains("nope")),
            "expected an unhandled-rejection diagnostic, got: {:?}",
            logs.borrow(),
        );
    }

    #[test]
    fn handled_rejection_is_not_logged() {
        let rt = Runtime::new().unwrap();
        let ctx = Context::full(&rt).unwrap();
        let logs = Rc::new(RefCell::new(Vec::<String>::new()));
        let logs2 = logs.clone();
        let log: Rc<dyn Fn(&str)> = Rc::new(move |m: &str| logs2.borrow_mut().push(m.to_string()));
        install_rejection_tracker(&rt, log.clone());

        ctx.with(|ctx| {
            ctx.eval::<(), _>(r#"Promise.reject(new Error('caught')).catch(() => {});"#).unwrap();
        });
        pump_jobs(&rt, &ctx, log.as_ref());

        assert!(logs.borrow().is_empty(), "a caught rejection must not log, got: {:?}", logs.borrow());
    }

}
