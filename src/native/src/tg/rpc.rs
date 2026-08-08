//! Pure JS-plumbing for the `inu.interceptRpc` / `inu.invokeRpc` / `inu.onUpdate` bridge.
//!
//! Deliberately JNI-free: all "Java" interaction is behind [`RpcHost`]/[`crate::tl::proxy::TlHost`],
//! so this module is exercised directly with rquickjs in cargo tests using Rust closure/struct
//! stand-ins for the upcalls. `src/native/src/lib.rs` wires JNI-backed hosts and forwards
//! the JNI exports into the `pub fn`s here.
//!
//! Every request/response/update value crossing the host boundary is a single
//! [`crate::tl::proxy`]-shaped wire string (`H<O|V><W|R><id>` a live handle, `J<json>` a plain-value
//! construct, `E`/`R`/`P` the three error shapes decoded by [`crate::engine::error`]) rather than raw JSON -
//! see `tl_proxy.rs`'s module doc for the full tag list.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::function::This;
use rquickjs::{Coerced, Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::engine::error::{self, check_grant, GrantHost, MATCH_EXACT};
use crate::engine::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry};
use crate::tg::account::{dispatch_account, AccountState};
use crate::tl::proxy::{self, TlViews, ViewLife};

/// stand-in for the Kotlin `QuickJs.RpcListener` interface; `None` == ok, `Some(msg)` == error.
/// `*_wire` params/returns are [`tl_proxy`]-tagged strings, never raw JSON.
pub trait RpcHost {
    /// `scope` is the grant the registration is gated on: `""` for `inu.interceptRpc`, where every
    /// method is its own scope, or `interceptSendMessage` for the narrowing of the same chain that
    /// api is - whose grant vocabulary is the api and not the four methods it is built from
    fn on_register(&self, methods: &[String], callback_id: u32, scope: &str) -> Option<String>;
    fn on_unregister(&self, callback_id: u32);
    /// `slot` is the account to send on, or [`ANY_ACCOUNT`] for the one the plugin started on
    /// (`inu.invokeRpc`, which names no account)
    fn on_invoke(&self, invoke_id: i64, slot: i32, request_wire: &str) -> Option<String>;
    fn on_next(&self, dispatch_id: i64, request_wire: &str) -> Option<String>;
    fn on_complete(&self, dispatch_id: i64, result_wire: &str);
    /// `types` is the registration's constructor list, which the host filters on: an update no
    /// registration named must never be materialized, let alone crossed into JS. `scope` is what
    /// the host gates the registration on - `""` for `inu.onUpdate`, where every constructor is its
    /// own grant scope, or the demuxed event name (`new_message`, ...) for the conveniences, whose
    /// grant vocabulary is the event and not the constructors it happens to be built from
    fn on_update_register(&self, callback_id: u32, types: &[String], scope: &str) -> Option<String>;
    fn on_update_unregister(&self, callback_id: u32);
    /// `inu.interceptUpdate`. A separate table from [`RpcHost::on_update_register`]'s: an
    /// observation registration only decides who a batch is fanned out to, while one of these
    /// decides whether the app is handed the batch at all.
    fn on_intercept_update_register(&self, callback_id: u32, types: &[String]) -> Option<String>;
    fn on_intercept_update_unregister(&self, callback_id: u32);
    /// one `interceptUpdate` stage's verdict. `deliver` false means the update is dropped, which
    /// ends the chain for it - the host walks no further stage.
    fn on_update_verdict(&self, dispatch_id: i64, deliver: bool);
}

pub(crate) struct PendingSettle {
    pub(crate) resolve: Persistent<Function<'static>>,
    pub(crate) reject: Persistent<Function<'static>>,
}

impl PendingSettle {
    /// mints a promise and saves its resolvers as roots
    pub(crate) fn new<'js>(ctx: &Ctx<'js>) -> JsResult<(rquickjs::Promise<'js>, Self)> {
        let (promise, resolve, reject) = rquickjs::Promise::new(ctx)?;
        Ok((promise, PendingSettle { resolve: Persistent::save(ctx, resolve), reject: Persistent::save(ctx, reject) }))
    }

    /// releases both roots and rejects with the host's error (an `E`/`R`/`P` wire, or a bare message)
    pub(crate) fn reject_with(self, ctx: &Ctx<'_>, msg: &str) -> JsResult<()> {
        let error_val = match error::host_error_to_js(ctx, msg) {
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

/// text of the synthetic `TL_error(-1000, ...)` `PluginRpc` tears a chain down with when the
/// *budget* ran out, as opposed to its other teardown reasons (`INTERCEPTOR_ABANDONED`: the plugin
/// owning a stage was stopped, the app cancelled, ...). Only the first is the plugin's fault, so
/// only the first may tell it so.
const CHAIN_TIMEOUT_TEXT: &str = "INTERCEPTOR_TIMEOUT";

#[derive(Default)]
struct DispatchState {
    called: Cell<bool>,
    settled: Cell<bool>,
    abandoned: Cell<bool>,
    timed_out: Cell<bool>,
    want_passthrough: Cell<bool>,
    next_response: RefCell<Option<String>>,
    next_resolvers: RefCell<Option<PendingSettle>>,
}

/// one `inu.onUpdate` registration. The constructor list is part of the registration rather than
/// of the plugin, so a plugin listening for two disjoint sets doesn't see either one's updates in
/// the other's callback.
///
/// A demuxed event ([`DEMUX_EVENTS`]) is one of these too, holding the listener `events.js` built
/// around the plugin's callback rather than the callback itself - so it narrows, dedups and expires
/// identically, and there is no second dispatch path for the host to keep in step with this one.
#[derive(Clone)]
struct UpdateReg {
    callback: Persistent<Function<'static>>,
    types: Rc<[String]>,
}

/// the demuxed event helpers, per `common.d.ts`: `(api name, grant scope, constructors)`.
///
/// The constructor lists are deliberately narrow. A scheduled message has not been sent, a quick
/// reply is a template, an ephemeral/business message belongs to another account's inbox and a
/// secret chat is somewhere plugin code never reaches - none of them is "a message arrived in a
/// dialog", which is the only thing these three promise.
const DEMUX_EVENTS: [(&str, &str, &[&str]); 3] = [
    ("onNewMessage", "new_message", &["updateNewMessage", "updateNewChannelMessage"]),
    ("onMessageEdited", "edit_message", &["updateEditMessage", "updateEditChannelMessage"]),
    ("onMessageDeleted", "delete_message", &["updateDeleteMessages", "updateDeleteChannelMessages"]),
];

/// what `inu.invokeRpc` sends on: the slot the plugin started on, which the host snapshots rather
/// than re-reading, so a user switching accounts cannot silently retarget a plugin's requests.
/// `Account.invokeRpc` names its own slot instead, which is the whole point of that form.
pub const ANY_ACCOUNT: i32 = -1;

const EVENTS_PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/events.qbc"));
const SEND_PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/sendmsg.qbc"));

/// what `inu.interceptSendMessage` registers for. The raw layer spreads one outgoing message across
/// these four (and their scheduled forms, which are a flag on the same methods), which is the whole
/// reason the api exists; `sendmsg.js` normalizes them into one `OutgoingMessage`.
const SEND_METHODS: [&str; 4] =
    ["messages.sendMessage", "messages.sendMedia", "messages.sendMultiMedia", "messages.editMessage"];

/// the grant `interceptSendMessage` registrations are gated on, in place of the four
/// `interceptRpc(...)` scopes they would otherwise need
const SEND_SCOPE: &str = "interceptSendMessage";

/// one `inu.interceptUpdate` stage in flight. There is no `next()` to park, so the only thing to
/// remember is whether something has already answered for it: a middleware that settles after the
/// host abandoned it must not answer a chain that has moved on.
#[derive(Default)]
struct UpdateDispatchState {
    settled: Cell<bool>,
}

pub struct RpcState {
    host: Rc<dyn RpcHost>,
    tl: Rc<TlViews>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    accounts: Option<Rc<AccountState>>,
    pub(crate) log: crate::Log,
    next_invoke_id: Cell<i64>,
    intercept_fns: CallbackRegistry,
    update_fns: Registry<UpdateReg>,
    intercept_update_fns: Registry<UpdateReg>,
    /// `events.js`'s factory, held as a root for the engine's life: it is what turns a demuxed
    /// registration into an ordinary [`UpdateReg`], and plugin code must not be able to reach it
    demux: RefCell<Option<Persistent<Function<'static>>>>,
    /// `sendmsg.js`'s factory, held for the same reason: it is what turns a verdict middleware into
    /// an ordinary `interceptRpc` one
    send_wrap: RefCell<Option<Persistent<Function<'static>>>>,
    /// the `Promise` constructor, its `resolve` and its prototype's `then`, captured at install for
    /// the reason every other prelude captures its constructors: how an intercepted request settles
    /// must not be decidable by a plugin reassigning `globalThis.Promise` or patching the prototype.
    /// The constructor is held because `Promise.resolve` reads its species off `this`.
    promise: RefCell<Option<PromiseTools>>,
    dispatches: RefCell<HashMap<i64, Rc<DispatchState>>>,
    update_dispatches: RefCell<HashMap<i64, Rc<UpdateDispatchState>>>,
    pending_invoke: RefCell<HashMap<i64, PendingSettle>>,
}

impl RpcState {
    fn alloc_invoke_id(&self) -> i64 {
        let id = self.next_invoke_id.get();
        self.next_invoke_id.set(id + 1);
        id
    }
}

pub(crate) fn make_error<'js>(ctx: &Ctx<'js>, message: &str) -> JsResult<Value<'js>> {
    let ctor: rquickjs::function::Constructor = ctx.globals().get("Error")?;
    ctor.construct((message,))
}

impl RpcState {
    /// The dispatch map is what "the app is parked behind this plugin" means, and the timer floor
    /// reads it to know a backgrounded chain must not be throttled into the chain budget. Every
    /// mutation goes through these three so the two can never drift; `no_raw_dispatch_mutation`
    /// pins that.
    fn insert_dispatch(&self, dispatch_id: i64, dstate: Rc<DispatchState>) {
        self.dispatches.borrow_mut().insert(dispatch_id, dstate);
        self.sync_blocking();
    }

    fn remove_dispatch(&self, dispatch_id: i64) -> Option<Rc<DispatchState>> {
        let removed = self.dispatches.borrow_mut().remove(&dispatch_id);
        self.sync_blocking();
        removed
    }

    fn drain_dispatches(&self) -> Vec<(i64, Rc<DispatchState>)> {
        let drained: Vec<_> = self.dispatches.borrow_mut().drain().collect();
        self.sync_blocking();
        drained
    }

    fn insert_update_dispatch(&self, dispatch_id: i64, ustate: Rc<UpdateDispatchState>) {
        self.update_dispatches.borrow_mut().insert(dispatch_id, ustate);
        self.sync_blocking();
    }

    fn remove_update_dispatch(&self, dispatch_id: i64) -> Option<Rc<UpdateDispatchState>> {
        let removed = self.update_dispatches.borrow_mut().remove(&dispatch_id);
        self.sync_blocking();
        removed
    }

    fn drain_update_dispatches(&self) {
        self.update_dispatches.borrow_mut().clear();
        self.sync_blocking();
    }

    /// an `interceptUpdate` stage blocks the app's whole arriving batch, exactly as an
    /// `interceptRpc` stage blocks its request, so both count toward the same "something is waiting
    /// on us" the background timer floor reads
    fn sync_blocking(&self) {
        let count = self.dispatches.borrow().len() + self.update_dispatches.borrow().len();
        self.lifecycle.set_blocking_dispatches(count);
    }
}

fn get_rpc_error_ctor<'js>(ctx: &Ctx<'js>) -> JsResult<rquickjs::function::Constructor<'js>> {
    ctx.globals().get::<_, Object>("inu")?.get("RpcError")
}

pub(crate) fn make_rpc_error<'js>(ctx: &Ctx<'js>, code: i32, text: &str) -> JsResult<Value<'js>> {
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
    Some(proxy::encode_rpc_error(code, &text))
}

/// encodes a middleware's thrown/rejected value into a result wire: `R` when it's an
/// `inu.RpcError`, `E` with its message otherwise
fn thrown_to_result_wire<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    rpc_error_to_wire(ctx, value).unwrap_or_else(|| proxy::encode_error(&error_value_to_string(ctx, value)))
}

/// how a stage's thrown/rejected value is logged. an `inu.RpcError` is not a bug: `common.d.ts`
/// documents throwing one as *the* way to fail an intercepted request, and a stage the host
/// abandoned has its parked `next()` rejected with one it did not cause (`INTERCEPTOR_TIMEOUT`,
/// `INTERCEPTOR_ABANDONED`). Faulting on either would disable a plugin for using the api as
/// written, or for another plugin's stall.
fn describe_stage_failure<'js>(ctx: &Ctx<'js>, what: std::fmt::Arguments, value: &Value<'js>) -> String {
    let detail = format_thrown(ctx, value);
    if rpc_error_to_wire(ctx, value).is_some() {
        format!("{what}: {detail}")
    } else {
        crate::fault(format_args!("{what}: {detail}"))
    }
}

/// resolves or rejects `settle` with a decoded wire value, routing on its `E`/`R`/`P`-tag.
/// consumes the settle: both roots are released whatever happens
fn settle_from_wire<'js>(
    ctx: &Ctx<'js>,
    tl: &Rc<TlViews>,
    settle: PendingSettle,
    wire: &str,
    life: ViewLife,
) -> JsResult<()> {
    let built = match error::wire_error_to_js(ctx, wire) {
        Some(value) => value.map(|v| (v, true)),
        None => proxy::wire_to_js_value(ctx, tl, wire, life).map(|v| (v, false)),
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

/// formats a thrown/rejected JS value into "message\nstack" (stack appended when present), or into
/// the quota it really was when the js heap ceiling raised it
fn format_thrown<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    let msg = describe_thrown(ctx, value);
    // reading the value can itself raise (a throwing `toString`, a `stack` getter, a Proxy), and
    // this runs where nothing else will look: the rejection tracker returns straight into quickjs,
    // and a raise left pending would fire at whatever unrelated call touched the context next
    let _ = ctx.catch();
    msg
}

fn describe_thrown<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> String {
    use rquickjs::FromJs;
    if let Some(message) = crate::engine::deadline::describe_heap_exhaustion(value) {
        return message;
    }
    let mut msg =
        Coerced::<String>::from_js(ctx, value.clone()).map(|c| c.0).unwrap_or_else(|_| "JS exception".to_string());
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
    log: crate::Log,
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
pub fn install_rejection_tracker(rt: &Runtime, log: crate::Log) {
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
            log(&crate::fault(format_args!("unhandled promise rejection: {msg}")));
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
    Coerced::<String>::from_js(ctx, value.clone()).map(|c| c.0).unwrap_or_else(|_| "unknown error".to_string())
}

/// normalizes `result` (a value, a thenable or a Promise) via `Promise.resolve(result).then(ok, err)`,
/// through the pair [`RpcState::promise`] captured at install rather than through the globals
fn resolve_and_then<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    result: Value<'js>,
    ok: Function<'js>,
    err: Function<'js>,
) -> JsResult<()> {
    use rquickjs::function::This;
    let Some(tools) = state.promise.borrow().clone() else {
        return Err(Exception::throw_message(ctx, "the promise machinery was not installed"));
    };
    let ctor = tools.ctor.restore(ctx)?;
    let resolved: Value = tools.resolve.restore(ctx)?.call((This(ctor), result))?;
    tools.then.restore(ctx)?.call::<_, Value>((This(resolved), ok, err))?;
    Ok(())
}

#[derive(Clone)]
struct PromiseTools {
    ctor: Persistent<Object<'static>>,
    resolve: Persistent<Function<'static>>,
    then: Persistent<Function<'static>>,
}

fn capture_promise_tools(ctx: &Ctx<'_>) -> JsResult<PromiseTools> {
    let ctor: Object = ctx.globals().get("Promise")?;
    let resolve: Function = ctor.get("resolve")?;
    let then: Function = ctor.get::<_, Object>("prototype")?.get("then")?;
    Ok(PromiseTools {
        ctor: Persistent::save(ctx, ctor),
        resolve: Persistent::save(ctx, resolve),
        then: Persistent::save(ctx, then),
    })
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

#[allow(clippy::too_many_arguments)]
pub fn install_rpc<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn RpcHost>,
    tl: Rc<TlViews>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    accounts: Option<Rc<AccountState>>,
    shared: Object<'js>,
    log: crate::Log,
) -> JsResult<Rc<RpcState>> {
    let state = Rc::new(RpcState {
        host,
        tl,
        grants,
        lifecycle,
        accounts,
        log,
        next_invoke_id: Cell::new(1),
        intercept_fns: CallbackRegistry::default(),
        update_fns: Registry::default(),
        intercept_update_fns: Registry::default(),
        demux: RefCell::new(None),
        send_wrap: RefCell::new(None),
        promise: RefCell::new(Some(capture_promise_tools(ctx)?)),
        dispatches: RefCell::new(HashMap::new()),
        update_dispatches: RefCell::new(HashMap::new()),
        pending_invoke: RefCell::new(HashMap::new()),
    });

    let inu = error::get_or_create_inu(ctx)?;

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

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, methods: Value<'js>, cb: Function<'js>| {
            js_intercept_rpc(&ctx, &state2, methods, cb)
        })?;
        inu.set("interceptRpc", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, obj: Value<'js>| {
            js_invoke_rpc(&ctx, &state2, ANY_ACCOUNT, obj)
        })?;
        inu.set("invokeRpc", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, types: Value<'js>, cb: Function<'js>| {
            js_on_update(&ctx, &state2, types, cb)
        })?;
        inu.set("onUpdate", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, types: Value<'js>, cb: Function<'js>| {
            js_intercept_update(&ctx, &state2, types, cb)
        })?;
        inu.set("interceptUpdate", f)?;
    }
    install_demuxed_events(ctx, &state, &inu)?;
    install_send_message(ctx, &state, &inu, shared)?;
    install_account_invoke(ctx, &state)?;
    Ok(state)
}

/// `Account.invokeRpc`, as a third link chained behind whatever prototype the read and write
/// surfaces built. It goes here rather than in `writes.js` because the pending-invoke table is this
/// module's, and it can go here at all because `installRpc` is a later JNI call than `installApi`.
///
/// The slot comes off `this.id`, for `utils.js`'s reason: one prototype serves every account, so a
/// method torn off a handle has to fail by name rather than send on slot 0.
fn install_account_invoke<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>) -> JsResult<()> {
    let Some(accounts) = state.accounts.clone() else {
        return Ok(());
    };
    let prototype = Object::new(ctx.clone())?;
    let state2 = state.clone();
    let f = Function::new(
        ctx.clone(),
        move |ctx: Ctx<'js>, this: This<Value<'js>>, obj: Value<'js>| -> JsResult<Value<'js>> {
            let slot = this
                .0
                .as_object()
                .and_then(|handle| handle.get::<_, Value>("id").ok())
                .and_then(|id| id.as_number())
                .filter(|id| id.fract() == 0.0 && *id >= 0.0)
                .map(|id| id as i32);
            let Some(slot) = slot else {
                return error::throw_plugin_error(
                    &ctx,
                    "invalid-argument",
                    "invokeRpc: not called on an account handle; use inu.account().invokeRpc(...)",
                    None,
                    None,
                    None,
                );
            };
            js_invoke_rpc(&ctx, &state2, slot, obj)
        },
    )?;
    prototype.set("invokeRpc", f)?;
    // taken rather than read, for `writes.rs`'s reason: overwriting the account's `Persistent`
    // without releasing it first leaks a GC root and aborts `JS_FreeRuntime`
    if let Some(inner) = crate::tg::account::take_prototype(ctx, &accounts) {
        prototype.set_prototype(Some(&inner))?;
    }
    let object_ctor: Object = ctx.globals().get("Object")?;
    let freeze: Function = object_ctor.get("freeze")?;
    freeze.call::<_, Value>((prototype.clone(),))?;
    crate::tg::account::set_prototype(ctx, &accounts, &prototype);
    Ok(())
}

/// hangs `inu.interceptSendMessage` off `inu` as a narrowing of the `interceptRpc` chain. The
/// prelude captures `inu.RpcError` here rather than at dispatch, for the reason
/// [`install_demuxed_events`] captures `inu.Message` here: this is the last moment it is still the
/// one this engine installed.
fn install_send_message<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    inu: &Object<'js>,
    shared: Object<'js>,
) -> JsResult<()> {
    let factory = crate::engine::prelude::load(ctx, SEND_PRELUDE)?;
    let plugin_error: Value = ctx.globals().get::<_, Object>("inu")?.get("PluginError")?;
    let rpc_error: Value = inu.get("RpcError")?;
    let accounts = state.accounts.clone();
    let self_user_id = Function::new(ctx.clone(), move |account_id: i32| {
        crate::tg::account::self_user_id(&accounts, account_id).map(|id| id as f64)
    })?;
    let build: Function = factory.call((shared, plugin_error, rpc_error, self_user_id))?;
    *state.send_wrap.borrow_mut() = Some(Persistent::save(ctx, build));

    let state = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
        js_intercept_send_message(&ctx, &state, cb)
    })?;
    inu.set("interceptSendMessage", f)?;
    Ok(())
}

/// hangs the three conveniences off `inu`, each closing over its own kind. `inu.Message` is read
/// here rather than at dispatch: `installApi` runs before `installRpc`, and both run before the
/// plugin's source, so this is the last moment the class is still the one this engine installed.
fn install_demuxed_events<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>, inu: &Object<'js>) -> JsResult<()> {
    let factory = crate::engine::prelude::load(ctx, EVENTS_PRELUDE)?;
    let message: Value = inu.get("Message")?;
    if !message.is_function() {
        // a late TypeError out of a handler would report this as the plugin's fault
        return Err(Exception::throw_type(ctx, "the demuxed events need the inu.Message installApi installs"));
    }
    let build: Function = factory.call((message,))?;
    *state.demux.borrow_mut() = Some(Persistent::save(ctx, build));

    for (name, kind, types) in DEMUX_EVENTS {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
            js_on_demuxed(&ctx, &state, kind, types, cb)
        })?;
        inu.set(name, f)?;
    }
    Ok(())
}

/// reads the `string | string[]` first argument every registration that names TL constructors
/// takes. Empty is refused rather than read as "everything": the whole point of the list is that
/// the filtering happens natively.
fn read_name_list<'js>(ctx: &Ctx<'js>, what: &str, names: Value<'js>, noun: &str) -> JsResult<Vec<String>> {
    let list: Vec<String> = if let Some(s) = names.as_string() {
        vec![s.to_string()?]
    } else if let Some(arr) = names.as_array() {
        let mut out = Vec::new();
        for item in crate::engine::argv::array_values(ctx, arr, what)? {
            let s = item
                .as_string()
                .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: {noun} list must contain only strings")))?
                .to_string()?;
            out.push(s);
        }
        out
    } else {
        return Err(Exception::throw_type(ctx, &format!("{what}: {noun} must be a string or an array of strings")));
    };
    if list.is_empty() {
        return Err(Exception::throw_type(ctx, &format!("{what}: {noun} list must not be empty")));
    }
    Ok(list)
}

fn js_intercept_rpc<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    methods: Value<'js>,
    cb: Function<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    let list = read_name_list(ctx, "interceptRpc", methods, "method")?;
    for method in list.iter() {
        check_grant(ctx, &state.grants, "interceptRpc", Some(method), MATCH_EXACT)?;
    }
    register_intercept(ctx, state, list, "", cb)
}

/// `inu.interceptSendMessage`: the same chain, over [`SEND_METHODS`], with the plugin's verdict
/// middleware wrapped by `sendmsg.js` into an ordinary one. The grant is the api's own, never the
/// four methods' - `interceptRpc(messages.sendMessage)` and this do not imply each other, the same
/// way the demuxed events and their constructors do not.
fn js_intercept_send_message<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>, cb: Function<'js>) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    check_grant(ctx, &state.grants, SEND_SCOPE, None, MATCH_EXACT)?;
    let build = match state.send_wrap.borrow().as_ref() {
        Some(build) => build.clone().restore(ctx)?,
        None => return Err(Exception::throw_type(ctx, "interceptSendMessage is not installed")),
    };
    let middleware: Function = build.call((cb,))?;
    let list = SEND_METHODS.iter().map(|m| m.to_string()).collect();
    register_intercept(ctx, state, list, SEND_SCOPE, middleware)
}

fn register_intercept<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    methods: Vec<String>,
    scope: &str,
    middleware: Function<'js>,
) -> JsResult<Function<'js>> {
    let callback_id = state.intercept_fns.alloc();
    if let Some(err) = state.host.on_register(&methods, callback_id, scope) {
        return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    state.intercept_fns.register(ctx, callback_id, None, middleware);

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        if state.intercept_fns.dispose(ctx, callback_id) {
            state.host.on_unregister(callback_id);
        }
    })
}

fn read_method_name<'js>(ctx: &Ctx<'js>, obj: &Value<'js>) -> JsResult<String> {
    let name = match obj.as_object() {
        Some(obj) => obj.get::<_, Value>("_")?,
        None => Value::new_undefined(ctx.clone()),
    };
    match name.as_string().and_then(|s| s.to_string().ok()) {
        Some(name) => Ok(name),
        None => error::throw_plugin_error(
            ctx,
            "invalid-argument",
            "invokeRpc: the request must carry its method name in '_'",
            None,
            None,
            None,
        ),
    }
}

fn js_invoke_rpc<'js>(ctx: &Ctx<'js>, state: &Rc<RpcState>, slot: i32, obj: Value<'js>) -> JsResult<Value<'js>> {
    let method = read_method_name(ctx, &obj)?;
    check_grant(ctx, &state.grants, "invokeRpc", Some(&method), MATCH_EXACT)?;

    let wire = proxy::js_value_to_wire(ctx, obj)?;
    let invoke_id = state.alloc_invoke_id();
    let (promise, pending) = PendingSettle::new(ctx)?;
    state.pending_invoke.borrow_mut().insert(invoke_id, pending);

    if let Some(err) = state.host.on_invoke(invoke_id, slot, &wire) {
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
pub fn resolve_invoke(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    invoke_id: i64,
    result_wire: &str,
) {
    context.with(|ctx| {
        if let Some(pending) = state.pending_invoke.borrow_mut().remove(&invoke_id) {
            // the response is the plugin's own: its views outlive this call and die with their proxies
            if let Err(e) = settle_from_wire(&ctx, &state.tl, pending, result_wire, ViewLife::Plugin) {
                (state.log)(&format!("resolveInvoke({invoke_id}) failed: {e:?}"));
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

fn js_on_update<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    types: Value<'js>,
    cb: Function<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    let list = read_name_list(ctx, "onUpdate", types, "type")?;
    for name in list.iter() {
        check_grant(ctx, &state.grants, "onUpdate", Some(name), MATCH_EXACT)?;
    }
    register_update_listener(ctx, state, list, "", cb)
}

/// `inu.interceptUpdate`. Its own registry rather than a flag on [`js_on_update`]'s: an observation
/// registration only decides who a batch reaches, one of these decides whether the app is handed
/// the batch at all, and the host walks the two lists at different points.
fn js_intercept_update<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    types: Value<'js>,
    cb: Function<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    let list = read_name_list(ctx, "interceptUpdate", types, "type")?;
    for name in list.iter() {
        check_grant(ctx, &state.grants, "interceptUpdate", Some(name), MATCH_EXACT)?;
    }
    let callback_id = state.intercept_update_fns.alloc();
    if let Some(err) = state.host.on_intercept_update_register(callback_id, &list) {
        return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    state.intercept_update_fns.insert(
        callback_id,
        None,
        UpdateReg { callback: Persistent::save(ctx, cb), types: list.into() },
    );

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        if let Some(reg) = state.intercept_update_fns.remove(callback_id) {
            let _ = reg.callback.restore(ctx);
            state.host.on_intercept_update_unregister(callback_id);
        }
    })
}

/// one of [`DEMUX_EVENTS`]. The grant is checked against the event's own scope, never against the
/// constructors: `common.d.ts` keeps the two vocabularies apart, so `onUpdate(new_message)` buys
/// this and nothing of the raw stream, and `onUpdate(updateNewMessage)` the other way round.
fn js_on_demuxed<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    kind: &str,
    types: &[&str],
    cb: Function<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    check_grant(ctx, &state.grants, "onUpdate", Some(kind), MATCH_EXACT)?;
    let build = match state.demux.borrow().as_ref() {
        Some(build) => build.clone().restore(ctx)?,
        None => return Err(Exception::throw_type(ctx, "the demuxed events are not installed")),
    };
    let listener: Function = build.call((kind, cb))?;
    register_update_listener(ctx, state, types.iter().map(|t| t.to_string()).collect(), kind, listener)
}

fn register_update_listener<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    types: Vec<String>,
    scope: &str,
    listener: Function<'js>,
) -> JsResult<Function<'js>> {
    let callback_id = state.update_fns.alloc();
    if let Some(err) = state.host.on_update_register(callback_id, &types, scope) {
        return Err(ctx.throw(error::host_error_to_js(ctx, &err)?));
    }
    state.update_fns.insert(
        callback_id,
        None,
        UpdateReg { callback: Persistent::save(ctx, listener), types: types.into() },
    );

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        if let Some(reg) = state.update_fns.remove(callback_id) {
            let _ = reg.callback.restore(ctx);
            state.host.on_update_unregister(callback_id);
        }
    })
}

/// the payload is a read-only, plugin-lifetime view: decoded even when no registration named
/// [`type_name`], because its handle is freed by the proxy's finalizer and nothing else would ever
/// release it. The host only dispatches an update at least one registration asked for, so this is
/// the narrow case of a plugin listening for several disjoint sets, not the bulk filtering - that
/// happens host-side, before a handle is minted.
pub fn dispatch_update(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
) {
    context.with(|ctx| {
        let value = match proxy::wire_to_js_value(&ctx, &state.tl, update_wire, ViewLife::Plugin) {
            Ok(v) => v,
            Err(e) => {
                let msg = match e {
                    rquickjs::Error::Exception => format_exception(&ctx),
                    other => other.to_string(),
                };
                (state.log)(&format!("dispatchUpdate: bad update wire: {msg}"));
                return;
            }
        };
        let listening: Vec<UpdateReg> =
            state.update_fns.values().into_iter().filter(|reg| reg.types.iter().any(|t| t == type_name)).collect();
        if listening.is_empty() {
            return;
        }
        let account = match dispatch_account(&ctx, &state.accounts, account_id) {
            Ok(v) => v,
            Err(e) => {
                (state.log)(&format!("dispatchUpdate: cannot build the account handle: {e:?}"));
                return;
            }
        };
        for reg in listening {
            let Ok(f) = reg.callback.restore(&ctx) else {
                continue;
            };
            match f.call::<_, Value>((value.clone(), account.clone())) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!("onUpdate callback threw: {}", format_exception(&ctx))));
                }
                Err(e) => {
                    (state.log)(&format!("onUpdate callback failed: {e:?}"));
                }
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// answers one `interceptUpdate` stage. `deliver` false ends the chain for that update.
///
/// Guarded on [`UpdateDispatchState::settled`] so the host is answered exactly once: a middleware
/// resolving after [`abandon_update_dispatch`] has moved the batch on must not retro-drop an update
/// the app has already been handed.
fn settle_update_verdict(state: &Rc<RpcState>, ustate: &Rc<UpdateDispatchState>, dispatch_id: i64, deliver: bool) {
    if ustate.settled.replace(true) {
        return;
    }
    state.remove_update_dispatch(dispatch_id);
    state.host.on_update_verdict(dispatch_id, deliver);
}

fn try_dispatch_update_intercept<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    callback_id: u32,
    dispatch_id: i64,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
) -> JsResult<()> {
    let ustate = Rc::new(UpdateDispatchState::default());
    let Some(reg) = state.intercept_update_fns.get(callback_id) else {
        // the host picked its chain before it could see a disposal, so this stage goes transparent -
        // the same answer [`passthrough_dispatch`] gives for the request chain
        (state.log)(&format!(
            "interceptUpdate({type_name}): dispatch {dispatch_id} names disposed interceptor {callback_id}, delivering"
        ));
        settle_update_verdict(state, &ustate, dispatch_id, true);
        return Ok(());
    };
    let middleware = reg.callback.restore(ctx)?;
    // writable and dispatch-scoped: rewriting in place is the point, and the view dies with the
    // batch the host released the scope for
    let update = proxy::wire_to_js_value(ctx, &state.tl, update_wire, ViewLife::Dispatch)?;
    let account = dispatch_account(ctx, &state.accounts, account_id)?;

    state.insert_update_dispatch(dispatch_id, ustate.clone());
    let call_result = middleware.call::<_, Value>((update, account));
    let result_value = match call_result {
        Ok(v) => v,
        Err(rquickjs::Error::Exception) => {
            let caught = ctx.catch();
            // delivered rather than dropped: a drop desyncs pts until the next catch-up, which is
            // not a thing to do because a plugin has a bug in it
            (state.log)(&crate::fault(format_args!(
                "interceptUpdate({type_name}) middleware threw, delivering: {}",
                format_thrown(ctx, &caught)
            )));
            settle_update_verdict(state, &ustate, dispatch_id, true);
            return Ok(());
        }
        Err(e) => return Err(e),
    };

    let ok_fn = {
        let state = state.clone();
        let ustate = ustate.clone();
        let type_name = type_name.to_string();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
            let verdict = value.as_string().and_then(|s| s.to_string().ok());
            let deliver = match verdict.as_deref() {
                Some("deliver") => true,
                Some("drop") => false,
                _ => {
                    (state.log)(&crate::fault(format_args!(
                        "interceptUpdate({type_name}) middleware returned {}, delivering: expected 'deliver' or 'drop'",
                        format_thrown(&ctx, &value)
                    )));
                    true
                }
            };
            settle_update_verdict(&state, &ustate, dispatch_id, deliver);
        })?
    };
    let err_fn = {
        let state = state.clone();
        let ustate = ustate.clone();
        let type_name = type_name.to_string();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
            (state.log)(&crate::fault(format_args!(
                "interceptUpdate({type_name}) middleware rejected, delivering: {}",
                format_thrown(&ctx, &value)
            )));
            settle_update_verdict(&state, &ustate, dispatch_id, true);
        })?
    };
    resolve_and_then(ctx, state, result_value, ok_fn, err_fn)
}

/// runs one `interceptUpdate` stage. The host answers itself when nothing can run, so every
/// dispatch is answered exactly once however this goes.
#[allow(clippy::too_many_arguments)]
pub fn dispatch_update_intercept(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    callback_id: u32,
    dispatch_id: i64,
    type_name: &str,
    account_id: i32,
    update_wire: &str,
) {
    context.with(|ctx| {
        if let Err(e) =
            try_dispatch_update_intercept(&ctx, state, callback_id, dispatch_id, type_name, account_id, update_wire)
        {
            let msg = match e {
                rquickjs::Error::Exception => format_exception(&ctx),
                other => other.to_string(),
            };
            (state.log)(&format!("interceptUpdate({type_name}) dispatch failed, delivering: {msg}"));
            if let Some(ustate) = state.remove_update_dispatch(dispatch_id) {
                settle_update_verdict_after_removal(state, &ustate, dispatch_id);
            } else {
                state.host.on_update_verdict(dispatch_id, true);
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// the failure tail of [`dispatch_update_intercept`], where the dispatch has already been taken off
/// the map: answers unless something already did
fn settle_update_verdict_after_removal(state: &Rc<RpcState>, ustate: &Rc<UpdateDispatchState>, dispatch_id: i64) {
    if ustate.settled.replace(true) {
        return;
    }
    state.host.on_update_verdict(dispatch_id, true);
}

/// the batch this stage belongs to has moved on (its budget ran out, or the plugin was stopped).
/// Nothing is rejected, there being no `next()` to park - the stage is simply made unable to answer,
/// so a middleware that resolves later cannot drop an update the app has already applied.
pub fn abandon_update_dispatch(rt: &Runtime, context: &rquickjs::Context, state: &Rc<RpcState>, dispatch_id: i64) {
    context.with(|_ctx| {
        if let Some(ustate) = state.remove_update_dispatch(dispatch_id) {
            ustate.settled.set(true);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

fn complete_dispatch(
    ctx: &Ctx<'_>,
    state: &Rc<RpcState>,
    dstate: &Rc<DispatchState>,
    dispatch_id: i64,
    result_wire: &str,
) {
    if dstate.settled.replace(true) {
        if dstate.abandoned.get() {
            (state.log)(&format!("interceptRpc: dispatch {dispatch_id} settled after being abandoned, result dropped"));
        }
        return;
    }
    state.remove_dispatch(dispatch_id);
    // if the middleware settled without ever awaiting its own next() call, the next() promise's
    // resolve/reject are still rooted in dstate.next_resolvers - release them here so they don't
    // outlive the runtime (Persistent has no Drop; an unreleased root aborts JS_FreeRuntime).
    if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
        pending.release(ctx);
    }
    state.host.on_complete(dispatch_id, result_wire);
}

/// answers a dispatch whose middleware is no longer registered exactly as a middleware that called
/// `next(req)` and returned `undefined` would: the stage goes transparent. The host picks a chain
/// before it can see a disposal that happened in the meantime, and a plugin disposing an
/// interceptor must not fail the app's request on its way out.
fn passthrough_dispatch(ctx: &Ctx<'_>, state: &Rc<RpcState>, dispatch_id: i64, request_wire: &str) {
    let dstate = Rc::new(DispatchState::default());
    dstate.called.set(true);
    dstate.want_passthrough.set(true);
    state.insert_dispatch(dispatch_id, dstate.clone());
    if let Some(err) = state.host.on_next(dispatch_id, request_wire) {
        complete_dispatch(ctx, state, &dstate, dispatch_id, &error::host_error_to_wire(&err));
    }
}

fn try_dispatch_rpc<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<RpcState>,
    callback_id: u32,
    dispatch_id: i64,
    method: &str,
    account_id: i32,
    request_wire: &str,
) -> JsResult<()> {
    let Some(middleware) = state.intercept_fns.restore(ctx, callback_id) else {
        (state.log)(&format!(
            "interceptRpc({method}): dispatch {dispatch_id} names disposed interceptor {callback_id}, passing through"
        ));
        passthrough_dispatch(ctx, state, dispatch_id, request_wire);
        return Ok(());
    };
    let request_value = proxy::wire_to_js_value(ctx, &state.tl, request_wire, ViewLife::Dispatch)?;

    let dstate = Rc::new(DispatchState::default());
    state.insert_dispatch(dispatch_id, dstate.clone());

    let next_fn = {
        let state = state.clone();
        let dstate = dstate.clone();
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, req: Value<'js>| -> JsResult<Value<'js>> {
            if dstate.abandoned.get() {
                let (code, message) = if dstate.timed_out.get() {
                    ("timed-out", "next(): the interceptor chain's budget expired and this stage was abandoned")
                } else {
                    ("aborted", "next(): the interceptor chain was torn down and this stage was abandoned")
                };
                return error::throw_plugin_error(&ctx, code, message, None, None, None);
            }
            if dstate.settled.get() {
                return error::throw_plugin_error(
                    &ctx,
                    "invalid-argument",
                    "next(): this dispatch already settled",
                    None,
                    None,
                    None,
                );
            }
            if dstate.called.replace(true) {
                return Err(Exception::throw_type(&ctx, "next() may only be called once"));
            }
            let wire = proxy::js_value_to_wire(&ctx, req)?;
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

    let account = dispatch_account(ctx, &state.accounts, account_id)?;
    let call_result = middleware.call::<_, Value>((request_value, next_fn, account));
    let result_value = match call_result {
        Ok(v) => v,
        Err(rquickjs::Error::Exception) => {
            let caught = ctx.catch();
            (state.log)(&describe_stage_failure(ctx, format_args!("interceptRpc({method}) callback threw"), &caught));
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
                    complete_dispatch(
                        &ctx,
                        &state,
                        &dstate,
                        dispatch_id,
                        &proxy::encode_error("middleware returned undefined"),
                    );
                }
            } else {
                let wire = match rpc_error_to_wire(&ctx, &value) {
                    Some(wire) => wire,
                    None => proxy::js_value_to_wire(&ctx, value)?,
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
            (state.log)(&describe_stage_failure(
                &ctx,
                format_args!("interceptRpc({method}) callback rejected"),
                &value,
            ));
            let wire = thrown_to_result_wire(&ctx, &value);
            complete_dispatch(&ctx, &state, &dstate, dispatch_id, &wire);
            Ok(())
        })?
    };

    resolve_and_then(ctx, state, result_value, ok_fn, err_fn)
}

#[allow(clippy::too_many_arguments)]
pub fn dispatch_rpc(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    callback_id: u32,
    dispatch_id: i64,
    method: &str,
    account_id: i32,
    request_wire: &str,
) {
    context.with(|ctx| {
        if let Err(e) = try_dispatch_rpc(&ctx, state, callback_id, dispatch_id, method, account_id, request_wire) {
            let msg = match e {
                rquickjs::Error::Exception => format_exception(&ctx),
                other => other.to_string(),
            };
            (state.log)(&format!("interceptRpc({method}) dispatch failed: {msg}"));
            let wire = proxy::encode_error(&msg);
            // through the same guard every other answer takes: a stage that parked its `next()`
            // before the failure has a passthrough in flight, and answering the host here *and*
            // from `complete_next` is two answers to one request
            let dstate = state.dispatches.borrow().get(&dispatch_id).cloned();
            match dstate {
                Some(dstate) => complete_dispatch(&ctx, state, &dstate, dispatch_id, &wire),
                None => state.host.on_complete(dispatch_id, &wire),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

pub fn complete_next(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    dispatch_id: i64,
    result_wire: &str,
) {
    context.with(|ctx| {
        let dstate = match state.dispatches.borrow().get(&dispatch_id).cloned() {
            Some(d) => d,
            None => return,
        };
        *dstate.next_response.borrow_mut() = Some(result_wire.to_string());

        if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
            if let Err(e) = settle_from_wire(&ctx, &state.tl, pending, result_wire, ViewLife::Dispatch) {
                (state.log)(&format!("completeNext({dispatch_id}) failed to settle next(): {e:?}"));
            }
        }

        if dstate.want_passthrough.get() {
            complete_dispatch(&ctx, state, &dstate, dispatch_id, result_wire);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// Drops a dispatch the host has already answered without it: rejects the parked `next()` with
/// `reason_wire`, and marks the dispatch settled so the middleware's eventual resolution becomes a
/// no-op in [`complete_dispatch`]. Deliberately does not call `on_complete` - the host is the one
/// abandoning, and it has already sent its own answer to the app. A later `next()` from the stage
/// throws `timed-out` only when `reason_wire` says the chain ran out of budget, `aborted`
/// otherwise: blaming the budget for an unrelated teardown sends the plugin author hunting a
/// timeout that never happened.
pub fn abandon_dispatch(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<RpcState>,
    dispatch_id: i64,
    reason_wire: &str,
) {
    context.with(|ctx| {
        let removed = state.remove_dispatch(dispatch_id);
        let Some(dstate) = removed else { return };
        dstate.abandoned.set(true);
        dstate.timed_out.set(proxy::wire_rpc_error(reason_wire).is_some_and(|(_, text)| text == CHAIN_TIMEOUT_TEXT));
        dstate.settled.set(true);

        let pending = dstate.next_resolvers.borrow_mut().take();
        if let Some(pending) = pending {
            if let Err(e) = pending.reject_with(&ctx, reason_wire) {
                (state.log)(&format!("abandonDispatch({dispatch_id}) failed to reject next(): {e:?}"));
            }
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
        state.intercept_fns.release_all(&ctx);
        for reg in state.update_fns.remove_matching(|_| true) {
            let _ = reg.callback.restore(&ctx);
        }
        for reg in state.intercept_update_fns.remove_matching(|_| true) {
            let _ = reg.callback.restore(&ctx);
        }
        if let Some(build) = state.demux.borrow_mut().take() {
            let _ = build.restore(&ctx);
        }
        if let Some(build) = state.send_wrap.borrow_mut().take() {
            let _ = build.restore(&ctx);
        }
        if let Some(tools) = state.promise.borrow_mut().take() {
            let _ = tools.ctor.restore(&ctx);
            let _ = tools.resolve.restore(&ctx);
            let _ = tools.then.restore(&ctx);
        }
        // the `Account` prototype chain, whose outermost link `install_account_invoke` put there.
        // Whichever of this and `account::dispose` runs first releases the whole chain, the inner
        // links being ordinary JS references by then; the other finds nothing left to take.
        if let Some(accounts) = state.accounts.as_ref() {
            let _ = crate::tg::account::take_prototype(&ctx, accounts);
        }
        state.drain_update_dispatches();
        for (_, pending) in state.pending_invoke.borrow_mut().drain() {
            pending.release(&ctx);
        }
        for (_, dstate) in state.drain_dispatches() {
            if let Some(pending) = dstate.next_resolvers.borrow_mut().take() {
                pending.release(&ctx);
            }
        }
    });
}

#[cfg(test)]
#[path = "rpc_tests.rs"]
mod tests;
