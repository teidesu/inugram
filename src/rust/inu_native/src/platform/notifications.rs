//! `inu.android.addNotificationCenterDelegate`: the app's own internal event bus, behind
//! `unsafe.notificationCenter`. JNI-free behind [`NotificationHost`].
//!
//! **Only scalars cross, and that is a refusal rather than an approximation.** There is no runtime
//! value on the js side to make an arbitrary java object into, and a class name in its place would
//! be an approximation of the event rather than the event. Everything else lands as `null`, which
//! `android.notification-center.d.ts` narrows the handler's argument types to say. The payloads are
//! not TL, so there is no chokepoint to filter at, which is why this grant sits in the unsafe tier.
//!
//! An event name is a closed vocabulary read off the app's own `NotificationCenter`, so an unknown
//! one is refused at registration: a plugin cannot otherwise tell a typo from an event that never
//! fires.

use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::json_parse;
use crate::engine::error::{
    check_grant, get_or_create_inu, host_error_to_js, throw_plugin_error, GrantHost, MATCH_EXACT,
};
use crate::engine::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};
use crate::tg::rpc::{format_exception, pump_jobs};

/// stand-in for the Kotlin `QuickJs.NotificationListener`
pub trait NotificationHost {
    /// start observing `events` for `callback_id`; `None` == accepted, `Some` == an error message
    /// thrown at the registration call
    fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String>;

    /// the disposer ran, or the engine is going away: stop observing for `callback_id`
    fn notification_unregister(&self, callback_id: u32);
}

/// `(handler, account, args) => handler(account, ...args)`
const INVOKE_SRC: &str = "(handler, account, args) => handler(account, ...args)";

struct Delegate {
    /// drained by [`Delegate::release`], which is the only thing that frees these GC roots
    handlers: RefCell<Vec<(String, Persistent<Function<'static>>)>>,
}

impl Delegate {
    fn handler<'js>(&self, ctx: &Ctx<'js>, name: &str) -> Option<Function<'js>> {
        self.handlers
            .borrow()
            .iter()
            .find(|(event, _)| event == name)
            .and_then(|(_, callback)| callback.clone().restore(ctx).ok())
    }

    fn release(&self, ctx: &Ctx<'_>) {
        for (_, callback) in self.handlers.borrow_mut().drain(..) {
            let _ = callback.restore(ctx);
        }
    }
}

pub struct NotificationState {
    host: Rc<dyn NotificationHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
    delegates: Registry<Rc<Delegate>>,
    invoke: RefCell<Option<Persistent<Function<'static>>>>,
}

pub fn install_notifications<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn NotificationHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
) -> JsResult<Rc<NotificationState>> {
    let state = Rc::new(NotificationState {
        host,
        grants,
        lifecycle,
        log,
        delegates: Registry::default(),
        invoke: RefCell::new(None),
    });

    let inu = get_or_create_inu(ctx)?;
    let android: Object = match inu.get::<_, Object>("android") {
        Ok(o) => o,
        Err(_) => {
            let o = Object::new(ctx.clone())?;
            inu.set("android", o.clone())?;
            o
        }
    };

    let state2 = state.clone();
    let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, handlers: Value<'js>| {
        js_add_delegate(&ctx, &state2, handlers)
    })?;
    android.set("addNotificationCenterDelegate", f)?;

    let invoke: Function = ctx.eval(INVOKE_SRC)?;
    *state.invoke.borrow_mut() = Some(Persistent::save(ctx, invoke));

    Ok(state)
}

fn invalid<'js, T>(ctx: &Ctx<'js>, message: &str) -> JsResult<T> {
    throw_plugin_error(ctx, "invalid-argument", message, None, None, None)
}

fn js_add_delegate<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<NotificationState>,
    handlers: Value<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    check_grant(ctx, &state.grants, "unsafe.notificationCenter", None, MATCH_EXACT)?;

    let Some(handlers) = handlers.as_object() else {
        return invalid(ctx, "addNotificationCenterDelegate: expected an object of handlers");
    };
    let mut entries: Vec<(String, Function<'js>)> = Vec::new();
    for key in handlers.keys::<String>() {
        let name = key?;
        let value: Value = handlers.get(name.as_str())?;
        let Some(callback) = value.as_function() else {
            return invalid(ctx, &format!("addNotificationCenterDelegate: '{name}' is not a function"));
        };
        entries.push((name, callback.clone()));
    }
    if entries.is_empty() {
        return invalid(ctx, "addNotificationCenterDelegate: no handlers");
    }

    // the token before the entry, so a host that refuses leaves nothing behind - and the roots
    // after it, or a refusal would strand one
    let names: Vec<String> = entries.iter().map(|(name, _)| name.clone()).collect();
    let token = state.delegates.alloc();
    if let Some(err) = state.host.notification_register(token, &names) {
        let value = host_error_to_js(ctx, &err)?;
        return Err(ctx.throw(value));
    }
    let delegate = Rc::new(Delegate {
        handlers: RefCell::new(
            entries.into_iter().map(|(name, callback)| (name, Persistent::save(ctx, callback))).collect(),
        ),
    });
    state.delegates.insert(token, None, delegate);

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        let Some(delegate) = state.delegates.remove(token) else {
            return;
        };
        delegate.release(ctx);
        state.host.notification_unregister(token);
    })
}

/// The app posted `name` on the centre belonging to slot `account` (`-1` is the app-wide one).
/// `args_json` is the event's own arguments, already reduced to scalars by the host.
pub fn dispatch_notification(
    rt: &Runtime,
    context: &rquickjs::Context,
    state: &Rc<NotificationState>,
    callback_id: Token,
    name: &str,
    account: i32,
    args_json: &str,
) {
    context.with(|ctx| {
        let Some(delegate) = state.delegates.get(callback_id) else {
            return;
        };
        let Some(handler) = delegate.handler(&ctx, name) else {
            return;
        };
        let args = match json_parse(&ctx, args_json) {
            Ok(args) => args,
            Err(_) => {
                (state.log)(&format!("{name}: bad notification payload: {}", format_exception(&ctx)));
                return;
            }
        };
        let Some(invoke) = state.invoke.borrow().clone().and_then(|f| f.restore(&ctx).ok()) else {
            (state.log)(&format!("{name}: no notification invoker"));
            return;
        };
        match invoke.call::<_, Value>((handler, account, args)) {
            Ok(_) => {}
            Err(rquickjs::Error::Exception) => {
                (state.log)(&crate::fault(format_args!(
                    "notification handler for '{name}' threw: {}",
                    format_exception(&ctx),
                )));
            }
            Err(e) => (state.log)(&format!("notification handler for '{name}' failed: {e:?}")),
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

/// releases every `Persistent` GC root this state owns. The host's own observers are torn down by
/// `PluginNotifications.detach`, not by an upcall per token from here: an engine being destroyed
/// cannot answer one, and the host is what holds the strong reference that has to go
pub fn dispose(context: &rquickjs::Context, state: &Rc<NotificationState>) {
    context.with(|ctx| {
        for delegate in state.delegates.remove_matching(|_| true) {
            delegate.release(&ctx);
        }
        if let Some(invoke) = state.invoke.borrow_mut().take() {
            let _ = invoke.restore(&ctx);
        }
    });
}

#[cfg(test)]
#[path = "notifications_tests.rs"]
mod tests;
