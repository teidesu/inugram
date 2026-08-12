use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::PluginErrorCode;
use crate::api::telegram::rpc::pump_jobs;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry, Token};

#[cfg(test)]
use crate::api::error;

pub trait AccountHost {
  fn accounts(&self) -> Option<String>;
}

#[derive(Clone, PartialEq, Eq)]
pub struct AccountInfo {
  id: i32,
  user_id: i64,
  is_current: bool,
  is_premium: bool,
}

struct CurrentScope {
  token: Token,
  callback: RefCell<Option<Persistent<Function<'static>>>>,
  teardown: RefCell<Option<Persistent<Function<'static>>>>,
  account: Cell<Option<(i32, i64)>>,
}

impl CurrentScope {
  fn borrow_callback<'js>(&self, ctx: &Ctx<'js>) -> Option<Function<'js>> {
    let saved = self.callback.borrow().clone()?;
    saved.restore(ctx).ok()
  }

  fn release_callback(&self, ctx: &Ctx<'_>) {
    if let Some(saved) = self.callback.borrow_mut().take() {
      let _ = saved.restore(ctx);
    }
  }
}

pub struct AccountState {
  host: Rc<dyn AccountHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  pub(crate) log: crate::Log,
  accounts: RefCell<Vec<AccountInfo>>,
  changed_fns: CallbackRegistry,
  scopes: Registry<Rc<CurrentScope>>,
  prototype: RefCell<Option<Persistent<Object<'static>>>>,
}

impl AccountState {
  pub(crate) fn set_prototype<'js>(&self, ctx: &Ctx<'js>, prototype: &Object<'js>) {
    *self.prototype.borrow_mut() = Some(Persistent::save(ctx, prototype.clone()));
  }

  pub(crate) fn take_prototype<'js>(&self, ctx: &Ctx<'js>) -> Option<Object<'js>> {
    self.prototype.borrow_mut().take()?.restore(ctx).ok()
  }

  fn build_account<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, info: &AccountInfo) -> JsResult<Value<'js>> {
    let obj = Object::new(ctx.clone())?;
    if let Some(prototype) = self.prototype.borrow().clone() {
      obj.set_prototype(Some(&prototype.restore(ctx)?))?;
    }
    obj.set("id", info.id)?;
    {
      let state = self.clone();
      let user_id = info.user_id;
      let get = move |ctx: Ctx<'js>| -> JsResult<f64> {
        state.grants.check_grant(&ctx, "account.read", Some("self"), MATCH_EXACT)?;
        Ok(user_id as f64)
      };
      obj.prop("userId", Accessor::new_get(get).enumerable())?;
    }
    let state = self.clone();
    let id = info.id;
    let is_current =
      Function::new(ctx.clone(), move || state.accounts.borrow().iter().any(|a| a.id == id && a.is_current))?;
    obj.set("isCurrent", is_current)?;
    Ok(obj.into_value())
  }

  pub(crate) fn self_user_id(&self, account_id: i32) -> Option<i64> {
    self.find(account_id).map(|info| info.user_id)
  }

  fn build_account_infos<'js>(&self, ctx: &Ctx<'js>) -> JsResult<Value<'js>> {
    let list = self.accounts.borrow().clone();
    let array = Array::new(ctx.clone())?;
    for (index, info) in list.iter().enumerate() {
      let obj = Object::new(ctx.clone())?;
      obj.set("id", info.id)?;
      obj.set("userId", info.user_id as f64)?;
      obj.set("isCurrent", info.is_current)?;
      obj.set("isPremium", info.is_premium)?;
      array.set(index, obj)?;
    }
    Ok(array.into_value())
  }

  fn refresh(&self, ctx: &Ctx<'_>) -> bool {
    let Some(json) = self.host.accounts() else {
      (self.log)("accounts: the host could not read the account list");
      return false;
    };
    match parse_accounts(ctx, &json) {
      Ok(list) => {
        *self.accounts.borrow_mut() = list;
        true
      }
      Err(e) => {
        let msg = match e {
          rquickjs::Error::Exception => crate::api::telegram::rpc::format_exception(ctx),
          other => other.to_string(),
        };
        (self.log)(&format!("accounts: unreadable host snapshot: {msg}"));
        false
      }
    }
  }

  fn js_account<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, id: Option<Value<'js>>) -> JsResult<Value<'js>> {
    let wanted = match id {
      None => None,
      Some(id) if id.is_undefined() || id.is_null() => None,
      Some(id) => match id.as_number() {
        Some(n) if n.fract() == 0.0 && n >= i32::MIN as f64 && n <= i32::MAX as f64 => Some(n as i32),
        Some(_) => return PluginErrorCode::InvalidArgument.throw(ctx, "account: 'id' must be an integer slot index"),
        None => return PluginErrorCode::InvalidArgument.throw(ctx, "account: 'id' must be a number"),
      },
    };

    for attempt in 0..2 {
      let found = match wanted {
        Some(id) => self.find(id),
        None => self.current(),
      };
      if let Some(info) = found {
        return self.build_account(ctx, &info);
      }
      if attempt == 0 {
        let _ = self.refresh(ctx);
      }
    }
    match wanted {
      Some(id) => PluginErrorCode::NotFound.throw(ctx, &format!("account: no account is logged in as #{id}")),
      None => PluginErrorCode::NotFound.throw(ctx, "account: no account is logged in"),
    }
  }

  fn js_with_current_account<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, cb: Function<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    let token = self.scopes.alloc();
    let scope = Rc::new(CurrentScope {
      token,
      callback: RefCell::new(Some(Persistent::save(ctx, cb))),
      teardown: RefCell::new(None),
      account: Cell::new(None),
    });
    self.scopes.insert(token, None, scope.clone());
    self.enter_scope(ctx, &scope);

    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      if let Some(scope) = state.scopes.remove(token) {
        state.leave_scope(ctx, &scope);
        scope.release_callback(ctx);
      }
    })
  }

  fn run_teardown(&self, ctx: &Ctx<'_>, teardown: &Function<'_>) {
    match teardown.call::<_, Value>(()) {
      Ok(_) => {}
      Err(rquickjs::Error::Exception) => {
        (self.log)(&crate::fault(format_args!(
          "withCurrentAccount teardown threw: {}",
          crate::api::telegram::rpc::format_exception(ctx),
        )));
      }
      Err(e) => (self.log)(&format!("withCurrentAccount teardown failed: {e:?}")),
    }
  }

  fn leave_scope(&self, ctx: &Ctx<'_>, scope: &Rc<CurrentScope>) {
    scope.account.set(None);
    let Some(teardown) = scope.teardown.borrow_mut().take() else {
      return;
    };
    let Ok(teardown) = teardown.restore(ctx) else {
      return;
    };
    self.run_teardown(ctx, &teardown);
  }

  fn enter_scope(self: &Rc<Self>, ctx: &Ctx<'_>, scope: &Rc<CurrentScope>) {
    let Some(info) = self.current() else { return };
    let Some(callback) = scope.borrow_callback(ctx) else {
      return;
    };
    let account = match self.build_account(ctx, &info) {
      Ok(v) => v,
      Err(e) => {
        (self.log)(&format!("withCurrentAccount: cannot build the account handle: {e:?}"));
        return;
      }
    };
    scope.account.set(Some((info.id, info.user_id)));
    match callback.call::<_, Value>((account,)) {
      Ok(result) => {
        let Some(teardown) = result.into_function() else {
          return;
        };
        if self.is_live(scope) {
          *scope.teardown.borrow_mut() = Some(Persistent::save(ctx, teardown));
        } else {
          self.run_teardown(ctx, &teardown);
        }
      }
      Err(rquickjs::Error::Exception) => {
        (self.log)(&crate::fault(format_args!(
          "withCurrentAccount callback threw: {}",
          crate::api::telegram::rpc::format_exception(ctx),
        )));
      }
      Err(e) => (self.log)(&format!("withCurrentAccount callback failed: {e:?}")),
    }
  }

  pub(crate) fn notify_unload(&self, rt: &Runtime, context: &rquickjs::Context) {
    self.lifecycle.begin_unload();
    context.with(|ctx| {
      for scope in self.scopes.remove_matching(|_| true) {
        self.leave_scope(&ctx, &scope);
        scope.release_callback(&ctx);
      }
    });
    pump_jobs(rt, context, self.log.as_ref());
  }

  pub(crate) fn dispose(&self, context: &rquickjs::Context) {
    context.with(|ctx| {
      if let Some(prototype) = self.prototype.borrow_mut().take() {
        let _ = prototype.restore(&ctx);
      }
      self.changed_fns.release_all(&ctx);
      for scope in self.scopes.remove_matching(|_| true) {
        scope.release_callback(&ctx);
        if let Some(teardown) = scope.teardown.borrow_mut().take() {
          let _ = teardown.restore(&ctx);
        }
      }
    });
  }

  fn js_on_accounts_changed<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, cb: Function<'js>) -> JsResult<Function<'js>> {
    if self.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }
    self.grants.check_grant(ctx, "account.read", Some("self"), MATCH_EXACT)?;
    let token = self.changed_fns.alloc();
    self.changed_fns.register(ctx, token, None, cb);
    let state = self.clone();
    make_disposer(ctx, move |ctx| {
      state.changed_fns.dispose(ctx, token);
    })
  }

  pub(crate) fn accounts_changed(self: &Rc<Self>, rt: &Runtime, context: &rquickjs::Context) {
    context.with(|ctx| {
      if !self.refresh(&ctx) {
        return;
      }
      let infos = match self.build_account_infos(&ctx) {
        Ok(v) => v,
        Err(e) => {
          (self.log)(&format!("onAccountsChanged: cannot build the account list: {e:?}"));
          return;
        }
      };
      for f in self.changed_fns.snapshot(&ctx) {
        match f.call::<_, Value>((infos.clone(),)) {
          Ok(_) => {}
          Err(rquickjs::Error::Exception) => {
            (self.log)(&crate::fault(format_args!(
              "onAccountsChanged callback threw: {}",
              crate::api::telegram::rpc::format_exception(&ctx),
            )));
          }
          Err(e) => (self.log)(&format!("onAccountsChanged callback failed: {e:?}")),
        }
      }
      let current = self.current().map(|info| (info.id, info.user_id));
      for scope in self.scopes.values() {
        if !self.is_live(&scope) || scope.account.get() == current {
          continue;
        }
        self.leave_scope(&ctx, &scope);
        if !self.is_live(&scope) {
          continue;
        }
        self.enter_scope(&ctx, &scope);
      }
    });
    pump_jobs(rt, context, self.log.as_ref());
  }

  fn find(&self, id: i32) -> Option<AccountInfo> {
    self.accounts.borrow().iter().find(|a| a.id == id).cloned()
  }

  fn current(&self) -> Option<AccountInfo> {
    self.accounts.borrow().iter().find(|a| a.is_current).cloned()
  }

  fn is_live(&self, scope: &Rc<CurrentScope>) -> bool {
    self.scopes.contains(scope.token)
  }
}

fn parse_accounts<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Vec<AccountInfo>> {
  let value = ctx.json_parse(json)?;
  let array = value
    .as_array()
    .ok_or_else(|| rquickjs::Exception::throw_type(ctx, "accounts: expected an array"))?;
  let mut out = Vec::with_capacity(array.len());
  for item in array.iter::<Object>() {
    let item = item?;
    out.push(AccountInfo {
      id: item.get("id")?,
      user_id: item.get("userId")?,
      is_current: item.get("isCurrent")?,
      is_premium: item.get("isPremium")?,
    });
  }
  Ok(out)
}

pub fn dispatch_account<'js>(
  ctx: &Ctx<'js>,
  state: &Option<Rc<AccountState>>,
  account_id: i32,
) -> JsResult<Value<'js>> {
  let Some(state) = state else {
    return Ok(Value::new_undefined(ctx.clone()));
  };
  if let Some(info) = state.find(account_id) {
    return state.build_account(ctx, &info);
  }
  let _ = state.refresh(ctx);
  let info = state.find(account_id).unwrap_or(AccountInfo {
    id: account_id,
    user_id: 0,
    is_current: false,
    is_premium: false,
  });
  state.build_account(ctx, &info)
}

pub fn install_account<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn AccountHost>,
  grants: Rc<dyn GrantHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<AccountState>> {
  let state = Rc::new(AccountState {
    host,
    grants,
    lifecycle,
    log,
    accounts: RefCell::new(Vec::new()),
    changed_fns: CallbackRegistry::default(),
    scopes: Registry::default(),
    prototype: RefCell::new(None),
  });
  let _ = state.refresh(ctx);

  {
    let state = state.clone();
    globals.inu.set(
      "account",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Opt<Value<'js>>| state.js_account(&ctx, id.0))?,
    )?;
  }
  {
    let state = state.clone();
    globals.inu.set(
      "accounts",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
        state.grants.check_grant(&ctx, "account.read", Some("self"), MATCH_EXACT)?;
        state.build_account_infos(&ctx)
      })?,
    )?;
  }
  {
    let state = state.clone();
    globals.inu.set(
      "onAccountsChanged",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| state.js_on_accounts_changed(&ctx, cb))?,
    )?;
  }
  {
    let state = state.clone();
    globals.inu.set(
      "withCurrentAccount",
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| state.js_with_current_account(&ctx, cb))?,
    )?;
  }
  Ok(state)
}

#[cfg(test)]
#[path = "account_tests.rs"]
pub(crate) mod tests;
