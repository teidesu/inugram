use std::cell::RefCell;
use crate::runtime::Dispose;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::format_exception;
use crate::api::telegram::account::{self, AccountState};
use crate::runtime::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle};

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

  let ui: Object = match globals.inu.get::<_, Object>("ui") {
    Ok(o) => o,
    Err(_) => {
      let o = Object::new(ctx.clone())?;
      globals.inu.set("ui", o.clone())?;
      o
    }
  };

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
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| -> JsResult<Function<'js>> {
      if state2.lifecycle.is_unloading() {
        return noop_disposer(&ctx);
      }
      let token = state2.changed_fns.alloc();
      state2.changed_fns.register(&ctx, token, None, cb);
      let state = state2.clone();
      make_disposer(&ctx, move |ctx| {
        state.changed_fns.dispose(ctx, token);
      })
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
  pub fn dispatch_change(
    self: &Rc<Self>,
    rt: &Runtime,
    context: &rquickjs::Context,
    change_json: &str,
    stack_json: &str,
  ) {
    let state = self;
    if state.lifecycle.is_unloading() {
      return;
    }
    if state.changed_fns.is_empty() {
      return;
    }
    context.with(|ctx| {
      let event = match state.build_event(&ctx, change_json, stack_json) {
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
            (state.log)(&crate::fault(format_args!("onScreenChanged callback threw: {}", format_exception(&ctx),)));
          }
          Err(e) => (state.log)(&format!("onScreenChanged callback failed: {e:?}")),
        }
      }
    });
    pump_jobs(rt, context, state.log.as_ref());
  }

}

impl Dispose for ScreenState {
  fn dispose(&self, context: &rquickjs::Context) {
    let state = self;
    context.with(|ctx| {
      state.changed_fns.release_all(&ctx);
      if let Some(factory) = state.event_factory.borrow_mut().take() {
        let _ = factory.restore(&ctx);
      }
    });
  }
}

#[cfg(test)]
#[path = "screens_tests.rs"]
mod tests;
