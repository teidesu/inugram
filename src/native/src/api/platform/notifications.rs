use crate::runtime::Dispose;
use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::function::Args;
use rquickjs::{Ctx, Function, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::format_exception;
use crate::api::error::{host_error_to_js, report_callback_error, PluginErrorCode};
use crate::api::platform::jvm::JvmState;
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT, MATCH_NAMESPACE};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};

pub trait NotificationHost {
  fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String>;

  fn notification_unregister(&self, callback_id: u32);

  /// while at least one token is held, the app posts no notification of its own; `account` is
  /// [`ANY_ACCOUNT`] for a hold taken over every account at once
  fn notification_suppress(&self, token: u32, account: i32, on: bool);
}

const SUPPRESS_GRANT: &str = "notifications.suppress";

/// what an app-level hold names, since it belongs to no one account
pub const ANY_ACCOUNT: i32 = -1;

struct Delegate {
  handlers: RefCell<Vec<(String, Persistent<Function<'static>>)>>,
}

impl Delegate {
  fn handler<'js>(&self, ctx: &Ctx<'js>, name: &str) -> Option<Function<'js>> {
    self
      .handlers
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
  jvm: Option<Rc<JvmState>>,
  delegates: Registry<Rc<Delegate>>,
  suppressors: Registry<i32>,
}

pub fn install_notifications<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn NotificationHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  jvm: Option<Rc<JvmState>>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<NotificationState>> {
  let state = Rc::new(NotificationState {
    host,
    grants,
    lifecycle,
    log,
    jvm,
    delegates: Registry::default(),
    suppressors: Registry::default(),
  });

  let android = globals.get_namespace(ctx, "android")?;

  let state2 = state.clone();
  android.set(
    "addNotificationCenterDelegate",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, handlers: Value<'js>| state2.js_add_delegate(&ctx, handlers))?,
  )?;

  let notifications = globals.get_namespace(ctx, "notifications")?;

  let state3 = state.clone();
  notifications
    .set("suppress", Function::new(ctx.clone(), move |ctx: Ctx<'js>| state3.js_suppress(&ctx, ANY_ACCOUNT))?)?;

  Ok(state)
}

impl NotificationState {
  /// Suppresses notifications while any plugin holds a token. Teardown releases only that plugin's
  /// tokens, preserving other plugins' holds.
  fn js_suppress<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, account: i32) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, SUPPRESS_GRANT, None, MATCH_EXACT)?;
    let token = self.suppressors.alloc();
    self.suppressors.insert(token, None, account);
    self.host.notification_suppress(token, account, true);

    let state = self.clone();
    make_disposer(ctx, move |_| {
      let Some(account) = state.suppressors.remove(token) else {
        return;
      };
      state.host.notification_suppress(token, account, false);
    })
  }

  /// `Account.suppressNotifications`, layered on the prototype the reads and writes built, the way
  /// `invokeRpc` is: this installs before either of them exists.
  pub fn install_account_suppress<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    accounts: &Rc<crate::api::telegram::account::AccountState>,
  ) -> JsResult<()> {
    let prototype = rquickjs::Object::new(ctx.clone())?;
    let state = self.clone();
    prototype.set(
      "suppressNotifications",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, this: rquickjs::function::This<Value<'js>>| {
        let slot = crate::api::telegram::account::account_slot(&ctx, &this, "suppressNotifications")?;
        state.js_suppress(&ctx, slot)
      })?,
    )?;
    if let Some(inner) = accounts.take_prototype(ctx) {
      prototype.set_prototype(Some(&inner))?;
    }
    let object_ctor: rquickjs::Object = ctx.globals().get("Object")?;
    let freeze: Function = object_ctor.get("freeze")?;
    freeze.call::<_, Value>((prototype.clone(),))?;
    accounts.set_prototype(ctx, &prototype);
    Ok(())
  }

  fn js_add_delegate<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, handlers: Value<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, "unsafe.notificationCenter", None, MATCH_EXACT)?;
    self.grants.check_grant(ctx, crate::api::platform::jvm::GRANT, None, MATCH_NAMESPACE)?;
    if self.jvm.is_none() {
      return PluginErrorCode::Unsupported.throw(ctx, "addNotificationCenterDelegate: this build has no jvm bridge");
    }

    let Some(handlers) = handlers.as_object() else {
      return PluginErrorCode::InvalidArgument
        .throw(ctx, "addNotificationCenterDelegate: expected an object of handlers");
    };
    let mut entries: Vec<(String, Function<'js>)> = Vec::new();
    for key in handlers.keys::<String>() {
      let name = key?;
      let value: Value = handlers.get(name.as_str())?;
      let Some(callback) = value.as_function() else {
        return {
          let message: &str = &format!("addNotificationCenterDelegate: '{name}' is not a function");
          PluginErrorCode::InvalidArgument.throw(ctx, message)
        };
      };
      entries.push((name, callback.clone()));
    }
    if entries.is_empty() {
      return PluginErrorCode::InvalidArgument.throw(ctx, "addNotificationCenterDelegate: no handlers");
    }

    let names: Vec<String> = entries.iter().map(|(name, _)| name.clone()).collect();
    let token = self.delegates.alloc();
    if let Some(err) = self.host.notification_register(token, &names) {
      let value = host_error_to_js(ctx, &err)?;
      return Err(ctx.throw(value));
    }
    let delegate = Rc::new(Delegate {
      handlers: RefCell::new(
        entries.into_iter().map(|(name, callback)| (name, Persistent::save(ctx, callback))).collect(),
      ),
    });
    self.delegates.insert(token, None, delegate);

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      let Some(delegate) = state.delegates.remove(token) else {
        return;
      };
      delegate.release(ctx);
      state.host.notification_unregister(token);
    })
  }
}

impl NotificationState {
  pub fn dispatch(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    callback_id: Token,
    name: &str,
    account: i32,
    args: &[String],
  ) {
    let state = self;
    if state.lifecycle.is_unloading() {
      return;
    }
    context.with(|ctx| {
      let Some(delegate) = state.delegates.get(callback_id) else {
        return;
      };
      let Some(handler) = delegate.handler(&ctx, name) else {
        return;
      };
      let Some(jvm) = state.jvm.as_ref() else {
        return;
      };
      // Decode before calling the handler so malformed host wires are reported as host errors. Each
      // entry uses the Xposed value format: scalars pass through; other values use the JVM handle
      // table.
      let decoded: JsResult<Vec<Value<'_>>> = args.iter().map(|wire| jvm.wire_to_value(&ctx, wire)).collect();
      let decoded = match decoded {
        Ok(decoded) => decoded,
        Err(_) => {
          (state.log)(&format!("{name}: bad notification payload: {}", format_exception(&ctx)));
          return;
        }
      };
      let result = (|| -> JsResult<Value<'_>> {
        let mut call_args = Args::new(ctx.clone(), decoded.len() + 1);
        call_args.push_arg(account)?;
        for value in decoded {
          call_args.push_arg(value)?;
        }
        handler.call_arg(call_args)
      })();
      if let Err(error) = result {
        report_callback_error(&state.log, &ctx, &format!("notification handler for '{name}'"), error);
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }
}

impl Dispose for NotificationState {
  fn dispose(&self, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      for delegate in state.delegates.remove_matching(|_| true) {
        delegate.release(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "notifications_tests.rs"]
mod tests;
