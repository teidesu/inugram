use crate::api::telegram::account::{account_slot, AccountState};
use crate::runtime::enter_js;
use crate::runtime::Dispose;
use crate::utils::qjs::qjs_object_freeze;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::function::{Args, Opt};
use rquickjs::{Ctx, Exception, Function, Persistent, Result as JsResult, Value};

use crate::api::error::format_exception;
use crate::api::error::{call_callback, host_error_to_js, report_callback_error, PluginErrorCode};
use crate::api::platform::jvm::JvmState;
use crate::api::ui::icons;
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT, MATCH_NAMESPACE};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry};
use crate::utils::arguments::{self, opt_bool, opt_str, req_str, stringify_json};

pub trait NotificationHost {
  fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String>;

  fn notification_unregister(&self, callback_id: u32);

  /// while at least one token is held, the app posts no notification of its own; `account` is
  /// [`ANY_ACCOUNT`] for a hold taken over every account at once
  fn notification_suppress(&self, token: u32, account: i32, on: bool);

  fn notification_post(&self, token: u32, options_json: &str) -> Option<String>;

  fn notification_cancel(&self, token: u32);

  /// empty `id` cancels every notification the plugin has shown, including ones from earlier runs
  fn notification_cancel_by_id(&self, id: &str);

  /// the host holds clicks for a plugin until it first subscribes, so a click that launched the app is not lost
  fn notification_listen_clicks(&self);
}

const SUPPRESS_GRANT: &str = "notifications.suppress";
const POST_GRANT: &str = "notifications.post";
const POST: &str = "notifications.post";

/// carried in the notification's pending intent, which lives in the system server
const DATA_LIMIT_BYTES: usize = 4 * 1024;
const ID_LIMIT: usize = 64;

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
  posted: Registry<()>,
  click_fns: CallbackRegistry,
  listening_clicks: Cell<bool>,
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
    posted: Registry::default(),
    click_fns: CallbackRegistry::default(),
    listening_clicks: Cell::new(false),
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

  let state4 = state.clone();
  notifications
    .set("post", Function::new(ctx.clone(), move |ctx: Ctx<'js>, options: Value<'js>| state4.js_post(&ctx, options))?)?;

  let state6 = state.clone();
  notifications.set(
    "cancel",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Opt<Value<'js>>| state6.js_cancel(&ctx, id.0))?,
  )?;

  let state5 = state.clone();
  notifications.set(
    "onClick",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, callback: Function<'js>| state5.js_on_click(&ctx, callback))?,
  )?;

  Ok(state)
}

fn check_id(ctx: &Ctx<'_>, what: &str, id: &str) -> JsResult<()> {
  if id.is_empty() || id.len() > ID_LIMIT {
    return Err(Exception::throw_type(ctx, &format!("{what}: 'id' must be 1 to {ID_LIMIT} bytes")));
  }
  Ok(())
}

impl NotificationState {
  /// Teardown releases only this plugin's tokens.
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

  fn js_post<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, options: Value<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, POST_GRANT, None, MATCH_EXACT)?;
    let Some(options) = options.as_object() else {
      return Err(Exception::throw_type(ctx, &format!("{POST}: expected an options object")));
    };
    let out = rquickjs::Object::new(ctx.clone())?;
    out.set("title", req_str(ctx, options, POST, "title")?)?;
    if let Some(text) = opt_str(ctx, options, POST, "text")? {
      out.set("text", text)?;
    }
    if let Some(id) = opt_str(ctx, options, POST, "id")? {
      check_id(ctx, POST, &id)?;
      out.set("id", id)?;
    }
    if let Some(silent) = opt_bool(ctx, options, POST, "silent")? {
      out.set("silent", silent)?;
    }
    if let Some(icon) = icons::opt_icon(ctx, options, POST, self.jvm.as_ref())? {
      // the status bar draws only a resource or a bitmap mask
      if !icon.spec.starts_with(['r', 's']) {
        return PluginErrorCode::Unsupported.throw(ctx, &format!("{POST}: only common and svg icons can be used"));
      }
      out.set("icon", icon.spec)?;
    }
    let data = arguments::field(ctx, options, POST, "data")?;
    if !data.is_undefined() {
      let json = stringify_json(ctx, data, &format!("{POST}: 'data' is not JSON-serializable"))?;
      if json.len() > DATA_LIMIT_BYTES {
        return PluginErrorCode::QuotaExceeded(json.len() as i64, DATA_LIMIT_BYTES as i64)
          .throw(ctx, &format!("{POST}: 'data' exceeds {DATA_LIMIT_BYTES} bytes as JSON"));
      }
      out.set("data", json)?;
    }
    let json = stringify_json(ctx, out.into_value(), POST)?;

    let token = self.posted.alloc();
    if let Some(err) = self.host.notification_post(token, &json) {
      let value = host_error_to_js(ctx, &err)?;
      return Err(ctx.throw(value));
    }
    self.posted.insert(token, None, ());

    let state = self.clone();
    make_disposer(ctx, move |_| {
      if state.posted.remove(token).is_some() {
        state.host.notification_cancel(token);
      }
    })
  }

  fn js_cancel<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, id: Option<Value<'js>>) -> JsResult<()> {
    const CANCEL: &str = "notifications.cancel";
    if self.lifecycle.is_unloading() {
      return Ok(());
    }
    self.grants.check_grant(ctx, POST_GRANT, None, MATCH_EXACT)?;
    let id = match id.filter(|id| !id.is_undefined()) {
      None => String::new(),
      Some(id) => {
        let Some(id) = id.as_string() else {
          return Err(Exception::throw_type(ctx, &format!("{CANCEL}: 'id' must be a string")));
        };
        let id = id.to_string()?;
        check_id(ctx, CANCEL, &id)?;
        id
      }
    };
    self.host.notification_cancel_by_id(&id);
    Ok(())
  }

  fn js_on_click<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, callback: Function<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, POST_GRANT, None, MATCH_EXACT)?;
    let disposer = CallbackRegistry::subscribe(ctx, self, &self.lifecycle, |s| &s.click_fns, callback)?;
    if !self.listening_clicks.replace(true) {
      self.host.notification_listen_clicks();
    }
    Ok(disposer)
  }

  /// Layered on the account prototype like `invokeRpc`: this installs before reads and writes build it.
  pub fn install_account_suppress<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, accounts: &Rc<AccountState>) -> JsResult<()> {
    let prototype = rquickjs::Object::new(ctx.clone())?;
    let state = self.clone();
    prototype.set(
      "suppressNotifications",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, this: rquickjs::function::This<Value<'js>>| {
        let slot = account_slot(&ctx, &this, "suppressNotifications")?;
        state.js_suppress(&ctx, slot)
      })?,
    )?;
    if let Some(inner) = accounts.take_prototype(ctx) {
      prototype.set_prototype(Some(&inner))?;
    }
    qjs_object_freeze(&prototype)?;
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
      return Err(Exception::throw_type(ctx, "addNotificationCenterDelegate: expected an object of handlers"));
    };
    let mut entries: Vec<(String, Function<'js>)> = Vec::new();
    for key in handlers.keys::<String>() {
      let name = key?;
      let value: Value = handlers.get(name.as_str())?;
      let Some(callback) = value.as_function() else {
        return {
          let message: &str = &format!("addNotificationCenterDelegate: '{name}' is not a function");
          Err(Exception::throw_type(ctx, message))
        };
      };
      entries.push((name, callback.clone()));
    }
    if entries.is_empty() {
      return Err(Exception::throw_type(ctx, "addNotificationCenterDelegate: no handlers"));
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
    context: &rquickjs::Context,
    callback_id: u32,
    name: &str,
    account: i32,
    args: &[String],
  ) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let Some(delegate) = self.delegates.get(callback_id) else {
        return;
      };
      let Some(handler) = delegate.handler(&ctx, name) else {
        return;
      };
      let Some(jvm) = self.jvm.as_ref() else {
        return;
      };
      // decoded before the handler runs, so a malformed wire is a host error
      let decoded: JsResult<Vec<Value<'_>>> = args.iter().map(|wire| jvm.wire_to_value(&ctx, wire)).collect();
      let decoded = match decoded {
        Ok(decoded) => decoded,
        Err(_) => {
          (self.log)(&format!("{name}: bad notification payload: {}", format_exception(&ctx)));
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
        report_callback_error(&self.log, &ctx, &format!("notification handler for '{name}'"), error);
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl NotificationState {
  /// `id` and `data_json` (what `post` stringified) are empty for a notification posted without them
  pub fn dispatch_click(self: &Rc<Self>, context: &rquickjs::Context, id: &str, data_json: &str) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let data = if data_json.is_empty() {
        Value::new_undefined(ctx.clone())
      } else {
        match ctx.json_parse(data_json) {
          Ok(data) => data,
          Err(_) => {
            (self.log)(&format!("notifications.onClick: bad click payload: {}", format_exception(&ctx)));
            return;
          }
        }
      };
      let id = (!id.is_empty()).then_some(id);
      for callback in self.click_fns.snapshot(&ctx) {
        call_callback(&ctx, &self.log, "notifications.onClick callback", &callback, (id, data.clone()));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl Dispose for NotificationState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| {
      for delegate in self.delegates.remove_matching(|_| true) {
        delegate.release(&ctx);
      }
      self.click_fns.release_all(&ctx);
    });
  }
}

#[cfg(test)]
#[path = "notifications_tests.rs"]
mod tests;
