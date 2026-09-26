use crate::runtime::Dispose;
use std::cell::RefCell;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Value};

use crate::api::error::{call_callback, describe_js_error};
use crate::api::telegram::account::{self, AccountState};
use crate::runtime::enter_js;
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{CallbackRegistry, Lifecycle};

pub trait ScreenHost {
  fn current_screen(&self) -> String;
}

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

impl ScreenState {
  fn build_screen<'js>(&self, ctx: &Ctx<'js>, raw: &Value<'js>) -> JsResult<Value<'js>> {
    let Some(obj) = raw.as_object() else {
      return Ok(Value::new_null(ctx.clone()));
    };
    let out = Object::new(ctx.clone())?;
    out.set("type", obj.get::<_, String>("type")?)?;
    if self.grants.is_granted("account.read", Some("dialogs"), MATCH_EXACT) {
      if let Some(dialog_id) = obj.get::<_, Option<f64>>("dialogId")? {
        out.set("dialogId", dialog_id)?;
      }
      if let Some(topic_id) = obj.get::<_, Option<i32>>("topicId")? {
        out.set("topicId", topic_id)?;
      }
    }
    let account_id: i32 = obj.get("account")?;
    out.set("account", account::dispatch_account(ctx, &self.accounts, account_id)?)?;
    Ok(out.into_value())
  }

  fn build_stack<'js>(&self, ctx: &Ctx<'js>, stack_json: &str) -> JsResult<Value<'js>> {
    let parsed = ctx.json_parse(stack_json)?;
    let out = Array::new(ctx.clone())?;
    if let Some(source) = parsed.as_array() {
      for (i, entry) in source.iter::<Value>().enumerate() {
        out.set(i, self.build_screen(ctx, &entry?)?)?;
      }
    }
    Ok(out.into_value())
  }
}

pub fn install_screens<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn ScreenHost>,
  grants: Rc<dyn GrantHost>,
  accounts: Option<Rc<AccountState>>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
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

  let ui = globals.get_namespace(ctx, "ui")?;

  let state2 = state.clone();
  ui.set(
    "getCurrentScreen",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
      let wire = state2.host.current_screen();
      let Some(json) = wire.strip_prefix('J') else {
        return Ok(Value::new_null(ctx.clone()));
      };
      let raw = ctx.json_parse(json)?;
      state2.build_screen(&ctx, &raw)
    })?,
  )?;

  let state2 = state.clone();
  ui.set(
    "onScreenChanged",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
      CallbackRegistry::subscribe(&ctx, &state2, &state2.lifecycle, |s| &s.changed_fns, cb)
    })?,
  )?;

  let factory: Function = ctx.eval::<Function, _>(EVENT_FACTORY_SRC)?;
  let state2 = state.clone();
  let materialize =
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, stack_json: String| state2.build_stack(&ctx, &stack_json))?;
  let builder: Function = factory.call((materialize,))?;
  *state.event_factory.borrow_mut() = Some(Persistent::save(ctx, builder));

  Ok(state)
}

impl ScreenState {
  fn build_event<'js>(&self, ctx: &Ctx<'js>, change_json: &str, stack_json: &str) -> JsResult<Value<'js>> {
    let change = ctx.json_parse(change_json)?;
    let change = change
      .as_object()
      .ok_or_else(|| rquickjs::Exception::throw_type(ctx, "onScreenChanged: expected an object"))?;
    let action: String = change.get("action")?;
    let screen = self.build_screen(ctx, &change.get::<_, Value>("screen")?)?;
    let previous = self.build_screen(ctx, &change.get::<_, Value>("previous")?)?;
    let builder = self
      .event_factory
      .borrow()
      .clone()
      .ok_or_else(|| rquickjs::Exception::throw_message(ctx, "onScreenChanged: no event factory"))?
      .restore(ctx)?;
    builder.call((action, screen, previous, stack_json))
  }
}

impl ScreenState {
  pub fn dispatch_change(self: &Rc<Self>, context: &rquickjs::Context, change_json: &str, stack_json: &str) {
    if self.lifecycle.is_unloading() {
      return;
    }
    if self.changed_fns.is_empty() {
      return;
    }
    enter_js(context, |ctx| {
      let event = match self.build_event(&ctx, change_json, stack_json) {
        Ok(event) => event,
        Err(e) => {
          (self.log)(&format!("onScreenChanged: bad change payload: {}", describe_js_error(&ctx, e)));
          return;
        }
      };
      for f in self.changed_fns.snapshot(&ctx) {
        call_callback(&ctx, &self.log, "onScreenChanged callback", &f, (event.clone(),));
      }
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl Dispose for ScreenState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |ctx| {
      self.changed_fns.release_all(&ctx);
      if let Some(factory) = self.event_factory.borrow_mut().take() {
        let _ = factory.restore(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "screens_tests.rs"]
mod tests;
