//! `inu.xposed`: method hooking, per `android.xposed.d.ts`.
//!
//! **The registry is here and nothing about it is java's.** A `de.robv.android.xposed.XposedBridge`
//! with `hook0` on it would *be* this grant, reachable by anyone holding `unsafe.jvm` - which every
//! holder of this one does, `inu.jvm.cls` being the only thing that mints the `JavaMethod` these
//! entry points take. So the property has to be structural: no java method installs a hook.
//!
//! Values reuse `jvm.rs`'s wire and its per-engine handle table, so a `JavaObject` a hook is handed
//! is one `inu.jvm` can call methods on. There is deliberately no second handle space.
//!
//! A dispatch is two engine entries with the original's call between them: the app's thread parks
//! on [`dispatch_before`], **calls the original itself** (a hooked method may be one only the ui
//! thread may run), then parks on [`dispatch_after`]. Entering an engine from an app thread races
//! every piece of `globalQueue`-confined bridge state, and re-entering it on one thread is a
//! `BorrowMutError` abort. Past [`HOOK_BUDGET_MS`] the host runs the original as the app called it.

pub(crate) mod elf;
pub(crate) mod lsplant;

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Array, Context, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::throw_plugin_error;
use crate::api::platform::jvm::{arg_to_wire, handle_id, wire_to_value, JvmState};
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_NAMESPACE};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry};
use crate::utils::namespace::get_or_create_inu;
use rquickjs::function::This;

use crate::api::telegram::rpc::{format_exception, pump_jobs};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/xposed.qbc"));

/// stand-in for the Kotlin `QuickJs.XposedListener`
pub trait XposedHost {
    /// one hooking op. `target` is a `jvm` handle id or a site id depending on the op, `name` a
    /// method name where one is needed, `args` one `jvm` wire per argument. The answer is a `jvm`
    /// wire too: a value, a handle, or an error (`E`/`P`).
    fn xposed(&self, op: i32, target: i64, name: &str, args: &[String]) -> String;
}

// keep in sync with Kotlin `PluginXposed.OP_*` and `xposed.js`
const OP_HOOK: i32 = 0;
const OP_HOOK_ALL: i32 = 1;
const OP_UNHOOK: i32 = 2;
const OP_CALL_ORIGINAL: i32 = 3;

/// the single grant this api is behind; its scopes are class namespaces, like `unsafe.jvm`'s, and
/// are checked against the *declaring* class by the host - the only side that knows it.
pub const GRANT: &str = "unsafe.xposed";

/// how many hooks one plugin may hold at once.
///
/// Every one of them is an ART method whose entry point was rewritten for the life of the process:
/// unlike a registration, it is not undone by the engine going away, and it costs a dispatch on
/// every call of a method the app may run in a loop.
pub const HOOK_LIMIT: usize = 512;

/// how long the thread that called a hooked method waits for one phase of its dispatch.
///
/// It is an app thread - often the ui one - parked on a queue every other plugin shares, so this is
/// short on purpose: past it the host runs the original as the app called it and the phase is
/// skipped rather than raced. Lives here rather than with the waiting so there is one of it, and is
/// read out through `QuickJs.nativeXposedBudgetMs`.
pub const HOOK_BUDGET_MS: i64 = 250;

#[derive(Clone)]
struct Hook {
    site: i64,
    before: Option<Persistent<Function<'static>>>,
    after: Option<Persistent<Function<'static>>>,
}

pub struct XposedState {
    host: Rc<dyn XposedHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    jvm: Rc<JvmState>,
    log: crate::Log,
    hooks: Registry<Hook>,
    /// how many live hooks each site carries, so the host is told to uninstall exactly once - when
    /// the last one goes. Installing twice over one ART method is undefined behaviour in lsplant.
    sites: RefCell<HashMap<i64, usize>>,
    /// dispatches parked between their two phases, keyed by the id the host allocated. Only a
    /// dispatch with an `after` to run is in here; everything else finishes inside its `before`.
    pending: RefCell<HashMap<i64, PendingDispatch>>,
}

/// one dispatch's `after` phase, held while the app's thread runs the original.
///
/// The `after` callbacks are re-saved rather than re-snapshotted at the far end: the rule is that a
/// hook disposed mid-dispatch still finishes its run and one registered mid-dispatch joins from the
/// next, and re-reading the registry a hop later would break both directions.
struct PendingDispatch {
    context: Persistent<Object<'static>>,
    after: Vec<Persistent<Function<'static>>>,
}

impl XposedState {
    fn release(&self, ctx: &Ctx<'_>, hook: Hook) {
        if let Some(before) = hook.before {
            let _ = before.restore(ctx);
        }
        if let Some(after) = hook.after {
            let _ = after.restore(ctx);
        }
        let mut sites = self.sites.borrow_mut();
        let Some(count) = sites.get_mut(&hook.site) else {
            return;
        };
        *count -= 1;
        if *count == 0 {
            sites.remove(&hook.site);
            drop(sites);
            self.host.xposed(OP_UNHOOK, hook.site, "", &[]);
        }
    }

    /// The callback pairs one dispatch walks, restored up front rather than kept as roots.
    ///
    /// Cloning a `Persistent` duplicates the root, so a clone that is never restored is a leak that
    /// aborts `JS_FreeRuntime`; a restored `Function<'js>` releases itself on drop, so bailing out
    /// mid-walk cannot strand one.
    fn snapshot<'js>(&self, ctx: &Ctx<'js>, site: i64) -> Vec<Phase<'js>> {
        self.hooks
            .values()
            .into_iter()
            .filter(|hook| hook.site == site)
            .map(|hook| Phase {
                before: hook.before.and_then(|f| f.restore(ctx).ok()),
                after: hook.after.and_then(|f| f.restore(ctx).ok()),
            })
            .collect()
    }
}

struct Phase<'js> {
    before: Option<Function<'js>>,
    after: Option<Function<'js>>,
}

fn ask<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    op: i32,
    target: i64,
    name: &str,
    args: &[String],
) -> JsResult<Value<'js>> {
    let wire = state.host.xposed(op, target, name, args);
    wire_to_value(ctx, &state.jvm, &wire)
}

/// The `jvm` handle id behind a `JavaMethod`/`JavaClass`.
///
/// Read through `jvm.js`'s own accessor rather than off the object, so `idOf` stays inside that
/// prelude's closure and a plugin cannot mint a handle by copying a property onto an object.
fn require_handle<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, value: &Value<'js>, what: &str) -> JsResult<i64> {
    let id = handle_id(ctx, &state.jvm, value)?;
    if id < 0 {
        return throw_plugin_error(
            ctx,
            "invalid-argument",
            &format!("xposed: {what} expected a java class or method"),
            None,
            None,
            None,
        );
    }
    Ok(id)
}

/// The site ids one hook op answered with.
///
/// `hookAllOverloads` installs several at once, so the answer is a comma-separated list carried in
/// the `jvm` wire's own string tag rather than an array, which that wire has no way to express.
fn sites_from<'js>(ctx: &Ctx<'js>, value: Value<'js>) -> JsResult<Vec<i64>> {
    let listed = value.as_string().and_then(|text| text.to_string().ok()).unwrap_or_default();
    let sites: Option<Vec<i64>> =
        listed.split(',').filter(|part| !part.is_empty()).map(|part| part.parse().ok()).collect();
    match sites {
        Some(sites) if !sites.is_empty() => Ok(sites),
        // a method the host declined to hook is not an error the plugin can act on, but it is one
        // it must not be able to mistake for a hook that took
        _ => throw_plugin_error(ctx, "internal", "xposed: the host installed no hook site", None, None, None),
    }
}

/// One `MethodHook` object, checked here rather than in the prelude so that "neither half given" is
/// the same refusal whichever entry point was called.
struct Callbacks<'js> {
    before: Option<Function<'js>>,
    after: Option<Function<'js>>,
}

fn callbacks_of<'js>(ctx: &Ctx<'js>, hook: &Object<'js>) -> JsResult<Callbacks<'js>> {
    let before: Option<Function> = hook.get("before").ok().flatten();
    let after: Option<Function> = hook.get("after").ok().flatten();
    if before.is_none() && after.is_none() {
        return throw_plugin_error(
            ctx,
            "invalid-argument",
            "xposed: a hook needs a before or an after callback",
            None,
            None,
            None,
        );
    }
    Ok(Callbacks { before, after })
}

/// Registers one callback pair against every site the host installed, and answers the `Disposer`
/// that takes all of them back down.
fn install_hooks<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    sites: Vec<i64>,
    callbacks: Callbacks<'js>,
) -> JsResult<Function<'js>> {
    let mut tokens = Vec::with_capacity(sites.len());
    for site in sites {
        let token = state.hooks.alloc();
        *state.sites.borrow_mut().entry(site).or_insert(0) += 1;
        state.hooks.insert(
            token,
            None,
            Hook {
                site,
                before: callbacks.before.clone().map(|f| Persistent::save(ctx, f)),
                after: callbacks.after.clone().map(|f| Persistent::save(ctx, f)),
            },
        );
        tokens.push(token);
    }

    // the ceiling is checked here rather than at the entry point because `hookAllOverloads` is one
    // call installing a site per overload, and the host is what knows how many that is. Backing the
    // excess out goes through the disposer's own path, which unhooks a site only when its last
    // holder goes - two registrations may name one already-rewritten ART method.
    let held = state.hooks.len();
    if held > HOOK_LIMIT {
        for token in &tokens {
            if let Some(hook) = state.hooks.remove(*token) {
                state.release(ctx, hook);
            }
        }
        return throw_plugin_error(
            ctx,
            "quota-exceeded",
            &format!("xposed: this plugin may hold at most {HOOK_LIMIT} hooks"),
            None,
            Some(held as i64),
            Some(HOOK_LIMIT as i64),
        );
    }

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        for token in &tokens {
            if let Some(hook) = state.hooks.remove(*token) {
                state.release(ctx, hook);
            }
        }
    })
}

fn js_hook<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    op: i32,
    target: Value<'js>,
    name: String,
    hook: Object<'js>,
) -> JsResult<Function<'js>> {
    // the scope check that matters is the host's, against the declaring class of the method it is
    // about to rewrite; this is the coarse gate every api keeps at its entry point
    check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
    let callbacks = callbacks_of(ctx, &hook)?;
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    let target = require_handle(ctx, state, &target, "hook")?;
    let answered = ask(ctx, state, op, target, &name, &[])?;
    let sites = sites_from(ctx, answered)?;
    install_hooks(ctx, state, sites, callbacks)
}

fn js_call_original<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    method: Value<'js>,
    this: Value<'js>,
    args: Array<'js>,
) -> JsResult<Value<'js>> {
    check_grant(ctx, &state.grants, GRANT, None, MATCH_NAMESPACE)?;
    let method = require_handle(ctx, state, &method, "callOriginalMethod")?;
    let mut wires = vec![arg_to_wire(ctx, &state.jvm, &this)?];
    for arg in crate::utils::arguments::array_values(ctx, &args, "callOriginalMethod")? {
        wires.push(arg_to_wire(ctx, &state.jvm, &arg)?);
    }
    ask(ctx, state, OP_CALL_ORIGINAL, method, "", &wires)
}

pub fn install_xposed<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn XposedHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    jvm: Rc<JvmState>,
    log: crate::Log,
) -> JsResult<Rc<XposedState>> {
    let state = Rc::new(XposedState {
        host,
        grants,
        lifecycle,
        jvm,
        log,
        hooks: Registry::default(),
        sites: RefCell::new(HashMap::new()),
        pending: RefCell::new(HashMap::new()),
    });

    let natives = Object::new(ctx.clone())?;
    {
        let state = state.clone();
        let f = Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>, op: i32, target: Value<'js>, name: String, hook: Object<'js>| {
                js_hook(&ctx, &state, op, target, name, hook)
            },
        )?;
        natives.set("hook", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>, method: Value<'js>, this: Value<'js>, args: Array<'js>| {
                js_call_original(&ctx, &state, method, this, args)
            },
        )?;
        natives.set("callOriginal", f)?;
    }
    {
        let ops = Object::new(ctx.clone())?;
        ops.set("hook", OP_HOOK)?;
        ops.set("hookAll", OP_HOOK_ALL)?;
        natives.set("ops", ops)?;
    }

    let inu = get_or_create_inu(ctx)?;
    // captured at install like every other prelude's, so what this one throws is not decidable by a
    // plugin reassigning `inu.PluginError`
    let plugin_error: Value = inu.get("PluginError")?;

    let factory = crate::utils::prelude::load(ctx, PRELUDE)?;
    let xposed: Object = factory.call((natives, plugin_error))?;
    inu.set("xposed", xposed)?;

    Ok(state)
}

/// What a `before` callback decided, and what a dispatch answers with.
enum Verdict {
    /// call the original, with whatever the callbacks left in `args`
    Proceed,
    /// a `setReturnValue`, or a `setThrowable` (already a `T` wire)
    Answered(String),
}

fn read_context<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>) -> JsResult<Verdict> {
    let answered: bool = context.get("__answered").unwrap_or(false);
    if !answered {
        return Ok(Verdict::Proceed);
    }
    let thrown: Option<Value> = context.get("__throwable").ok().flatten();
    if let Some(thrown) = thrown {
        if !thrown.is_null() && !thrown.is_undefined() {
            return Ok(Verdict::Answered(format!("T{}", arg_to_wire(ctx, &state.jvm, &thrown)?)));
        }
    }
    let value: Value = context.get("returnValue")?;
    Ok(Verdict::Answered(arg_to_wire(ctx, &state.jvm, &value)?))
}

fn run_callback<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    callback: &Function<'js>,
    context: &Object<'js>,
    phase: &str,
) {
    match callback.call::<_, Value>((context.clone(),)) {
        Ok(_) => {}
        Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!("xposed {phase} hook threw: {}", format_exception(ctx))));
        }
        Err(e) => (state.log)(&format!("xposed {phase} hook failed: {e:?}")),
    }
}

/// `method` is the `jvm` wire of the member lsplant is dispatching for - taken from the host rather
/// than from the registration, because the bulk forms install one hook over several overloads and
/// only the host knows which of them was called. `this`/`args` are `jvm` wires too.
pub struct Invocation<'a> {
    pub method: &'a str,
    pub this: &'a str,
    pub args: &'a [String],
}

/// The `before` half of a dispatch, run on `globalQueue` with the app's thread parked on it.
///
/// The answer is read positionally by the host: `["A", wire]` means answer the app with `wire` and
/// run nothing, and `["P0" | "P1", ...args]` means call the original with those args - `P1` also
/// meaning this `dispatch_id` is parked and owes [`dispatch_after`] a call, `P0` that it does not.
pub fn dispatch_before(
    rt: &Runtime,
    context: &Context,
    state: &Rc<XposedState>,
    dispatch_id: i64,
    site: i64,
    call: &Invocation,
) -> Vec<String> {
    let Invocation { method, this, args } = *call;
    let answer = context.with(|ctx| -> JsResult<Vec<String>> {
        // taken before the first callback runs: a hook registered by one joins from the next
        // dispatch, and one disposed by it still finishes this run
        let hooks = state.snapshot(&ctx, site);
        if hooks.is_empty() {
            // the site outlived its last hook, which a dispatch already in flight can reach
            return Ok(proceed_with(false, args));
        }

        let context_object = build_context(&ctx, state, method, this, args)?;

        let mut verdict = Verdict::Proceed;
        for hook in &hooks {
            let Some(before) = &hook.before else { continue };
            run_callback(&ctx, state, before, &context_object, "before");
            if let Verdict::Answered(wire) = read_context(&ctx, state, &context_object)? {
                verdict = Verdict::Answered(wire);
                break;
            }
        }

        let wants_after = hooks.iter().any(|hook| hook.after.is_some());
        if let Verdict::Answered(wire) = verdict {
            // nothing runs the original, so there is no hop for the `after` phase to wait behind
            let afters: Vec<&Function> = hooks.iter().filter_map(|hook| hook.after.as_ref()).collect();
            let wire = run_after(&ctx, state, &afters, &context_object, &wire)?;
            return Ok(vec!["A".to_string(), wire]);
        }

        let call_args = read_args(&ctx, state, &context_object)?;
        if wants_after {
            let after = hooks
                .iter()
                .filter_map(|hook| hook.after.as_ref())
                .map(|f| Persistent::save(&ctx, f.clone()))
                .collect();
            state
                .pending
                .borrow_mut()
                .insert(dispatch_id, PendingDispatch { context: Persistent::save(&ctx, context_object), after });
        }
        Ok(proceed_with(wants_after, &call_args))
    });

    pump_jobs(rt, context, state.log.as_ref());
    // the bridge failing is not the plugin's answer to give: the app gets its own method, which is
    // also what the host falls back to when this phase does not land inside its budget
    answer.unwrap_or_else(|_| proceed_with(false, args))
}

fn proceed_with(wants_after: bool, args: &[String]) -> Vec<String> {
    let mut out = Vec::with_capacity(args.len() + 1);
    out.push(if wants_after { "P1" } else { "P0" }.to_string());
    out.extend(args.iter().cloned());
    out
}

/// The `after` half, owed to every dispatch [`dispatch_before`] answered `P1`. `result` is the `jvm`
/// wire the original answered, or a `T`-prefixed one when it threw.
pub fn dispatch_after(
    rt: &Runtime,
    context: &Context,
    state: &Rc<XposedState>,
    dispatch_id: i64,
    result: &str,
) -> String {
    let answer = context.with(|ctx| -> JsResult<String> {
        let Some(pending) = state.pending.borrow_mut().remove(&dispatch_id) else {
            return Ok(result.to_string());
        };
        let context_object = pending.context.restore(&ctx)?;
        let afters: Vec<Function> = pending.after.into_iter().filter_map(|f| f.restore(&ctx).ok()).collect();
        run_after(&ctx, state, &afters.iter().collect::<Vec<_>>(), &context_object, result)
    });
    pump_jobs(rt, context, state.log.as_ref());
    answer.unwrap_or_else(|_| result.to_string())
}

/// drops a parked dispatch the host stopped waiting on, which is the only thing that releases its
/// GC roots when no `after` phase follows
pub fn release_dispatch(context: &Context, state: &Rc<XposedState>, dispatch_id: i64) {
    let Some(pending) = state.pending.borrow_mut().remove(&dispatch_id) else {
        return;
    };
    context.with(|ctx| {
        let _ = pending.context.restore(&ctx);
        for f in pending.after {
            let _ = f.restore(&ctx);
        }
    });
}

fn run_after<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    afters: &[&Function<'js>],
    context_object: &Object<'js>,
    result: &str,
) -> JsResult<String> {
    if afters.is_empty() {
        return Ok(result.to_string());
    }
    publish_result(ctx, state, context_object, result)?;
    for after in afters {
        run_callback(ctx, state, after, context_object, "after");
    }
    Ok(match read_context(ctx, state, context_object)? {
        Verdict::Answered(wire) => wire,
        // an `after` that set nothing leaves whatever the original answered
        Verdict::Proceed => result.to_string(),
    })
}

fn build_context<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<XposedState>,
    method: &str,
    this: &str,
    args: &[String],
) -> JsResult<Object<'js>> {
    let object = Object::new(ctx.clone())?;
    object.set("method", wire_to_value(ctx, &state.jvm, method)?)?;
    object.set("thisObject", wire_to_value(ctx, &state.jvm, this)?)?;
    let array = Array::new(ctx.clone())?;
    for (index, arg) in args.iter().enumerate() {
        array.set(index, wire_to_value(ctx, &state.jvm, arg)?)?;
    }
    object.set("args", array)?;
    object.set("returnValue", Value::new_null(ctx.clone()))?;
    object.set("throwable", Value::new_null(ctx.clone()))?;
    object.set("__answered", false)?;
    object.set("__throwable", Value::new_null(ctx.clone()))?;

    // both setters reach the context through `this` rather than capturing it. A closure holding an
    // `Object<'js>` is a strong reference from *outside* the js heap, so an object owning a function
    // that captured it is a cycle quickjs cannot see and therefore never collects.
    {
        let f = Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
            let null = Value::new_null(this.0.ctx().clone());
            this.0.set("returnValue", value)?;
            this.0.set("__throwable", null)?;
            this.0.set("__answered", true)
        })?;
        object.set("setReturnValue", f)?;
    }
    {
        let f = Function::new(ctx.clone(), |this: This<Object<'js>>, value: Value<'js>| -> JsResult<()> {
            // clears any return value an earlier hook set, per `android.xposed.d.ts`
            let null = Value::new_null(this.0.ctx().clone());
            this.0.set("returnValue", null)?;
            this.0.set("__throwable", value)?;
            this.0.set("__answered", true)
        })?;
        object.set("setThrowable", f)?;
    }
    Ok(object)
}

/// `args` after the `before` callbacks: the array is live, per `android.xposed.d.ts`, so what the
/// original is called with is read back rather than being the wire we were handed.
fn read_args<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>) -> JsResult<Vec<String>> {
    let array: Array = context.get("args")?;
    let mut wires = Vec::new();
    for value in crate::utils::arguments::array_values(ctx, &array, "xposed: 'args'")? {
        wires.push(arg_to_wire(ctx, &state.jvm, &value)?);
    }
    Ok(wires)
}

fn publish_result<'js>(ctx: &Ctx<'js>, state: &Rc<XposedState>, context: &Object<'js>, result: &str) -> JsResult<()> {
    // an `after` starts from what actually happened, so the verdict an earlier `before` may have
    // left is cleared first
    context.set("__answered", false)?;
    context.set("__throwable", Value::new_null(ctx.clone()))?;
    match result.strip_prefix('T') {
        Some(thrown) => {
            context.set("returnValue", Value::new_null(ctx.clone()))?;
            context.set("throwable", wire_to_value(ctx, &state.jvm, thrown)?)
        }
        None => {
            context.set("throwable", Value::new_null(ctx.clone()))?;
            context.set("returnValue", wire_to_value(ctx, &state.jvm, result)?)
        }
    }
}

/// Every hook this engine installed comes down with it. Unlike a registration, an ART method whose
/// entry point was rewritten stays rewritten, so leaving one behind would keep dispatching into an
/// engine that no longer exists.
pub fn dispose(context: &Context, state: &Rc<XposedState>) {
    context.with(|ctx| {
        for hook in state.hooks.take_values() {
            state.release(&ctx, hook);
        }
        // a dispatch whose parked thread never came back for its `after` phase still holds roots
        for (_, pending) in state.pending.borrow_mut().drain() {
            let _ = pending.context.restore(&ctx);
            for f in pending.after {
                let _ = f.restore(&ctx);
            }
        }
    });
}

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
