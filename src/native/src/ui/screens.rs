//! `inu.ui.getCurrentScreen` and `inu.ui.onScreenChanged`. JNI-free behind [`ScreenHost`]. Its own
//! module rather than part of [`crate::ui::pages`] because it is the opposite arrow: the settings-page api
//! is the plugin driving the host, this is the host reporting a stack it owns.
//!
//! The host classifies; this side gates. `dialogId`/`topicId` cost `account.read(dialogs)` (which
//! chat the user is reading must not be cheaper to get for having come from the ui) and are
//! **omitted, never refused**, since `type` already says whether there was one to give. The event
//! itself needs no grant.
//!
//! `getCurrentScreen` answers from whatever the host last published or not at all; `N` covers every
//! "nothing to say" the host has (no activity, backgrounded, too early during startup).
//!
//! `ScreenChange.stack` is a js getter over the json ([`EVENT_FACTORY_SRC`]), memoized in the
//! closure, so a plugin that only reads `screen` never mints an `Account` per stack entry.

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::json_parse;
use crate::engine::error::{get_or_create_inu, GrantHost, MATCH_EXACT};
use crate::engine::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};
use crate::tg::account::{self, AccountState};
use crate::tg::rpc::{format_exception, pump_jobs};

/// stand-in for the navigation half of the Kotlin `QuickJs.ApiListener`
pub trait ScreenHost {
    /// the screen on top right now: `N` for none, or `J<json>` shaped like one `stack` entry
    fn current_screen(&self) -> String;
}

/// `(materialize) => (action, screen, previous, stackJson) => ScreenChange`.
///
/// In js because the memoized lazy getter needs a closure to keep the array in: a rust accessor
/// would have to hold it as a `Persistent`, which is a GC root with no owner to release it.
const EVENT_FACTORY_SRC: &str = r#"(materialize) => (action, screen, previous, stackJson) => {
    let stack
    return {
        action,
        screen,
        previous,
        get stack() {
            if (stack === undefined) stack = materialize(stackJson)
            return stack
        },
    }
}"#;

pub struct ScreenState {
    host: Rc<dyn ScreenHost>,
    grants: Rc<dyn GrantHost>,
    accounts: Option<Rc<AccountState>>,
    lifecycle: Rc<Lifecycle>,
    pub(crate) log: crate::Log,
    changed_fns: CallbackRegistry,
    event_factory: RefCell<Option<Persistent<Function<'static>>>>,
}

/// One `CurrentScreen` out of one host-side json object. Never throws for a missing grant: the
/// fields it gates are documented absent without it.
fn build_screen<'js>(ctx: &Ctx<'js>, state: &Rc<ScreenState>, raw: &Value<'js>) -> JsResult<Value<'js>> {
    let Some(obj) = raw.as_object() else {
        return Ok(Value::new_null(ctx.clone()));
    };
    let out = Object::new(ctx.clone())?;
    out.set("type", obj.get::<_, String>("type")?)?;
    if state.grants.is_granted("account.read", Some("dialogs"), MATCH_EXACT) {
        if let Some(dialog_id) = obj.get::<_, Option<f64>>("dialogId")? {
            out.set("dialogId", dialog_id)?;
        }
        if let Some(topic_id) = obj.get::<_, Option<i32>>("topicId")? {
            out.set("topicId", topic_id)?;
        }
    }
    let account_id: i32 = obj.get("account")?;
    out.set("account", account::dispatch_account(ctx, &state.accounts, account_id)?)?;
    Ok(out.into_value())
}

fn build_stack<'js>(ctx: &Ctx<'js>, state: &Rc<ScreenState>, stack_json: &str) -> JsResult<Value<'js>> {
    let parsed = json_parse(ctx, stack_json)?;
    let out = Array::new(ctx.clone())?;
    if let Some(source) = parsed.as_array() {
        for (i, entry) in source.iter::<Value>().enumerate() {
            out.set(i, build_screen(ctx, state, &entry?)?)?;
        }
    }
    Ok(out.into_value())
}

pub fn install_screens<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn ScreenHost>,
    grants: Rc<dyn GrantHost>,
    accounts: Option<Rc<AccountState>>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
) -> JsResult<Rc<ScreenState>> {
    let state = Rc::new(ScreenState {
        host,
        grants,
        accounts,
        lifecycle,
        log,
        changed_fns: CallbackRegistry::default(),
        event_factory: RefCell::new(None),
    });

    let inu = get_or_create_inu(ctx)?;
    let ui: Object = match inu.get::<_, Object>("ui") {
        Ok(o) => o,
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            inu.set("ui", o.clone())?;
            o
        }
    };

    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
            let wire = state2.host.current_screen();
            let Some(json) = wire.strip_prefix('J') else {
                return Ok(Value::new_null(ctx.clone()));
            };
            let raw = json_parse(&ctx, json)?;
            build_screen(&ctx, &state2, &raw)
        })?;
        ui.set("getCurrentScreen", f)?;
    }
    {
        let state2 = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
            if state2.lifecycle.is_unloading() {
                return noop_disposer(&ctx);
            }
            let token = state2.changed_fns.alloc();
            state2.changed_fns.register(&ctx, token, None, cb);
            let state = state2.clone();
            make_disposer(&ctx, move |ctx| {
                state.changed_fns.dispose(ctx, token);
            })
        })?;
        ui.set("onScreenChanged", f)?;
    }

    let factory: Function = ctx.eval::<Function, _>(EVENT_FACTORY_SRC)?;
    let state2 = state.clone();
    let materialize =
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, stack_json: String| build_stack(&ctx, &state2, &stack_json))?;
    let builder: Function = factory.call((materialize,))?;
    *state.event_factory.borrow_mut() = Some(Persistent::save(ctx, builder));

    Ok(state)
}

/// The user navigated. `change_json` is `{action, screen, previous}` (either screen `null` when
/// there is none) and `stack_json` the whole stack, bottom first, left as text until a plugin asks
/// for it.
///
/// The host has already decided that this *is* a change, per `common.d.ts`: a rebuild that ends on
/// the same screen is not dispatched, so every call here is one navigation.
pub fn dispatch_screen_change(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<ScreenState>,
    change_json: &str,
    stack_json: &str,
) {
    if state.changed_fns.is_empty() {
        return;
    }
    context.with(|ctx| {
        let event = match build_event(&ctx, state, change_json, stack_json) {
            Ok(event) => event,
            Err(rquickjs::Error::Exception) => {
                (state.log)(&format!("onScreenChanged: bad change payload: {}", format_exception(&ctx)));
                return;
            }
            Err(e) => {
                (state.log)(&format!("onScreenChanged: bad change payload: {e:?}"));
                return;
            }
        };
        for f in state.changed_fns.snapshot(&ctx) {
            match f.call::<_, Value>((event.clone(),)) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!(
                        "onScreenChanged callback threw: {}",
                        format_exception(&ctx),
                    )));
                }
                Err(e) => (state.log)(&format!("onScreenChanged callback failed: {e:?}")),
            }
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

fn build_event<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<ScreenState>,
    change_json: &str,
    stack_json: &str,
) -> JsResult<Value<'js>> {
    let change = json_parse(ctx, change_json)?;
    let change = change
        .as_object()
        .ok_or_else(|| rquickjs::Exception::throw_type(ctx, "onScreenChanged: expected an object"))?;
    let action: String = change.get("action")?;
    let screen = build_screen(ctx, state, &change.get::<_, Value>("screen")?)?;
    let previous = build_screen(ctx, state, &change.get::<_, Value>("previous")?)?;
    let builder = state
        .event_factory
        .borrow()
        .clone()
        .ok_or_else(|| rquickjs::Exception::throw_message(ctx, "onScreenChanged: no event factory"))?
        .restore(ctx)?;
    builder.call((action, screen, previous, stack_json))
}

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::tg::rpc::dispose`]
pub fn dispose(context: &rquickjs::Context, state: &Rc<ScreenState>) {
    context.with(|ctx| {
        state.changed_fns.release_all(&ctx);
        if let Some(factory) = state.event_factory.borrow_mut().take() {
            let _ = factory.restore(&ctx);
        }
    });
}

#[cfg(test)]
#[path = "screens_tests.rs"]
mod tests;
