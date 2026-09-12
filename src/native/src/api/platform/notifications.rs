use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::function::Args;
use rquickjs::{Ctx, Exception, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::{host_error_to_js, PluginErrorCode};
use crate::api::error::format_exception;
use crate::runtime::{pump_jobs};
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry, Token};

pub trait NotificationHost {
  fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String>;

  fn notification_unregister(&self, callback_id: u32);
}

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
  delegates: Registry<Rc<Delegate>>,
}

pub fn install_notifications<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn NotificationHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<NotificationState>> {
  let state = Rc::new(NotificationState {
    host,
    grants,
    lifecycle,
    log,
    delegates: Registry::default(),
  });

  let android: Object = match globals.inu.get::<_, Object>("android") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("android", o.clone())?;
      o
    }
  };

  let state2 = state.clone();
  android.set(
    "addNotificationCenterDelegate",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, handlers: Value<'js>| state2.js_add_delegate(&ctx, handlers))?,
  )?;

  Ok(state)
}

impl NotificationState {
  fn js_add_delegate<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, handlers: Value<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, "unsafe.notificationCenter", None, MATCH_EXACT)?;

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
    args_json: &str,
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
      let args = match ctx.json_parse(args_json) {
        Ok(args) => args,
        Err(_) => {
          (state.log)(&format!("{name}: bad notification payload: {}", format_exception(&ctx)));
          return;
        }
      };
      let result = (|| -> JsResult<Value<'_>> {
        let Some(args) = args.as_array() else {
          return Err(Exception::throw_type(&ctx, "notification arguments must be an array"));
        };
        let mut call_args = Args::new(ctx.clone(), args.len() + 1);
        call_args.push_arg(account)?;
        for value in args.iter::<Value>() {
          call_args.push_arg(value?)?;
        }
        handler.call_arg(call_args)
      })();
      match result {
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

  pub fn dispose(self: &Rc<Self>, context: &rquickjs::Context) {
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
