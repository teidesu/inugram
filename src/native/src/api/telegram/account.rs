use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::error::{self};
use crate::api::telegram::rpc::pump_jobs;
use crate::sandbox::grants::{check_grant, GrantHost, MATCH_EXACT};
use crate::sandbox::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry, Token};

pub trait AccountHost {
    fn accounts(&self) -> Option<String>;
}

#[derive(Clone, PartialEq)]
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
    let array = value.as_array().ok_or_else(|| rquickjs::Exception::throw_type(ctx, "accounts: expected an array"))?;
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

fn refresh(ctx: &Ctx<'_>, state: &Rc<AccountState>) -> bool {
    let Some(json) = state.host.accounts() else {
        (state.log)("accounts: the host could not read the account list");
        return false;
    };
    match parse_accounts(ctx, &json) {
        Ok(list) => {
            *state.accounts.borrow_mut() = list;
            true
        }
        Err(e) => {
            let msg = match e {
                rquickjs::Error::Exception => crate::api::telegram::rpc::format_exception(ctx),
                other => other.to_string(),
            };
            (state.log)(&format!("accounts: unreadable host snapshot: {msg}"));
            false
        }
    }
}

pub fn set_prototype<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>, prototype: &Object<'js>) {
    *state.prototype.borrow_mut() = Some(Persistent::save(ctx, prototype.clone()));
}

pub fn take_prototype<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>) -> Option<Object<'js>> {
    state.prototype.borrow_mut().take()?.restore(ctx).ok()
}

fn build_account<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>, info: &AccountInfo) -> JsResult<Value<'js>> {
    let obj = Object::new(ctx.clone())?;
    if let Some(prototype) = state.prototype.borrow().clone() {
        obj.set_prototype(Some(&prototype.restore(ctx)?))?;
    }
    obj.set("id", info.id)?;
    {
        let state = state.clone();
        let user_id = info.user_id;
        let get = move |ctx: Ctx<'js>| -> JsResult<f64> {
            check_grant(&ctx, &state.grants, "account.read", Some("self"), MATCH_EXACT)?;
            Ok(user_id as f64)
        };
        obj.prop("userId", Accessor::new_get(get).enumerable())?;
    }
    let state = state.clone();
    let id = info.id;
    let is_current =
        Function::new(ctx.clone(), move || state.accounts.borrow().iter().any(|a| a.id == id && a.is_current))?;
    obj.set("isCurrent", is_current)?;
    Ok(obj.into_value())
}

pub fn self_user_id(state: &Option<Rc<AccountState>>, account_id: i32) -> Option<i64> {
    state.as_ref()?.find(account_id).map(|info| info.user_id)
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
        return build_account(ctx, state, &info);
    }
    let _ = refresh(ctx, state);
    let info = state.find(account_id).unwrap_or(AccountInfo {
        id: account_id,
        user_id: 0,
        is_current: false,
        is_premium: false,
    });
    build_account(ctx, state, &info)
}

fn build_account_infos<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>) -> JsResult<Value<'js>> {
    let list = state.accounts.borrow().clone();
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

pub fn install_account<'js>(
    ctx: &Ctx<'js>,
    host: Rc<dyn AccountHost>,
    grants: Rc<dyn GrantHost>,
    lifecycle: Rc<Lifecycle>,
    log: crate::Log,
    inu: &Object<'js>,
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
    let _ = refresh(ctx, &state);

    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: Opt<Value<'js>>| js_account(&ctx, &state, id.0))?;
        inu.set("account", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>| -> JsResult<Value<'js>> {
            check_grant(&ctx, &state.grants, "account.read", Some("self"), MATCH_EXACT)?;
            build_account_infos(&ctx, &state)
        })?;
        inu.set("accounts", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
            js_on_accounts_changed(&ctx, &state, cb)
        })?;
        inu.set("onAccountsChanged", f)?;
    }
    {
        let state = state.clone();
        let f = Function::new(ctx.clone(), move |ctx: Ctx<'js>, cb: Function<'js>| {
            js_with_current_account(&ctx, &state, cb)
        })?;
        inu.set("withCurrentAccount", f)?;
    }
    Ok(state)
}

fn js_account<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>, id: Option<Value<'js>>) -> JsResult<Value<'js>> {
    let wanted = match id {
        None => None,
        Some(id) if id.is_undefined() || id.is_null() => None,
        Some(id) => match id.as_number() {
            Some(n) if n.fract() == 0.0 && n >= i32::MIN as f64 && n <= i32::MAX as f64 => Some(n as i32),
            Some(_) => {
                return error::throw_plugin_error(
                    ctx,
                    "invalid-argument",
                    "account: 'id' must be an integer slot index",
                    None,
                    None,
                    None,
                )
            }
            None => {
                return error::throw_plugin_error(
                    ctx,
                    "invalid-argument",
                    "account: 'id' must be a number",
                    None,
                    None,
                    None,
                )
            }
        },
    };

    for attempt in 0..2 {
        let found = match wanted {
            Some(id) => state.find(id),
            None => state.current(),
        };
        if let Some(info) = found {
            return build_account(ctx, state, &info);
        }
        if attempt == 0 {
            let _ = refresh(ctx, state);
        }
    }
    match wanted {
        Some(id) => error::throw_plugin_error(
            ctx,
            "not-found",
            &format!("account: no account is logged in as #{id}"),
            None,
            None,
            None,
        ),
        None => error::throw_plugin_error(ctx, "not-found", "account: no account is logged in", None, None, None),
    }
}

fn js_on_accounts_changed<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>, cb: Function<'js>) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    check_grant(ctx, &state.grants, "account.read", Some("self"), MATCH_EXACT)?;
    let token = state.changed_fns.alloc();
    state.changed_fns.register(ctx, token, None, cb);
    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        state.changed_fns.dispose(ctx, token);
    })
}

fn js_with_current_account<'js>(
    ctx: &Ctx<'js>,
    state: &Rc<AccountState>,
    cb: Function<'js>,
) -> JsResult<Function<'js>> {
    if state.lifecycle.is_unloading() {
        return noop_disposer(ctx);
    }
    let token = state.scopes.alloc();
    let scope = Rc::new(CurrentScope {
        token,
        callback: RefCell::new(Some(Persistent::save(ctx, cb))),
        teardown: RefCell::new(None),
        account: Cell::new(None),
    });
    state.scopes.insert(token, None, scope.clone());
    enter_scope(ctx, state, &scope);

    let state = state.clone();
    make_disposer(ctx, move |ctx| {
        if let Some(scope) = state.scopes.remove(token) {
            leave_scope(ctx, &state, &scope);
            scope.release_callback(ctx);
        }
    })
}

fn run_teardown(ctx: &Ctx<'_>, state: &Rc<AccountState>, teardown: &Function<'_>) {
    match teardown.call::<_, Value>(()) {
        Ok(_) => {}
        Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!(
                "withCurrentAccount teardown threw: {}",
                crate::api::telegram::rpc::format_exception(ctx),
            )));
        }
        Err(e) => (state.log)(&format!("withCurrentAccount teardown failed: {e:?}")),
    }
}

fn leave_scope(ctx: &Ctx<'_>, state: &Rc<AccountState>, scope: &Rc<CurrentScope>) {
    scope.account.set(None);
    let Some(teardown) = scope.teardown.borrow_mut().take() else {
        return;
    };
    let Ok(teardown) = teardown.restore(ctx) else {
        return;
    };
    run_teardown(ctx, state, &teardown);
}

fn enter_scope(ctx: &Ctx<'_>, state: &Rc<AccountState>, scope: &Rc<CurrentScope>) {
    let Some(info) = state.current() else { return };
    let Some(callback) = scope.borrow_callback(ctx) else {
        return;
    };
    let account = match build_account(ctx, state, &info) {
        Ok(v) => v,
        Err(e) => {
            (state.log)(&format!("withCurrentAccount: cannot build the account handle: {e:?}"));
            return;
        }
    };
    scope.account.set(Some((info.id, info.user_id)));
    match callback.call::<_, Value>((account,)) {
        Ok(result) => {
            let Some(teardown) = result.into_function() else {
                return;
            };
            if state.is_live(scope) {
                *scope.teardown.borrow_mut() = Some(Persistent::save(ctx, teardown));
            } else {
                run_teardown(ctx, state, &teardown);
            }
        }
        Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!(
                "withCurrentAccount callback threw: {}",
                crate::api::telegram::rpc::format_exception(ctx),
            )));
        }
        Err(e) => (state.log)(&format!("withCurrentAccount callback failed: {e:?}")),
    }
}

pub fn accounts_changed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<AccountState>) {
    context.with(|ctx| {
        if !refresh(&ctx, state) {
            return;
        }
        let infos = match build_account_infos(&ctx, state) {
            Ok(v) => v,
            Err(e) => {
                (state.log)(&format!("onAccountsChanged: cannot build the account list: {e:?}"));
                return;
            }
        };
        for f in state.changed_fns.snapshot(&ctx) {
            match f.call::<_, Value>((infos.clone(),)) {
                Ok(_) => {}
                Err(rquickjs::Error::Exception) => {
                    (state.log)(&crate::fault(format_args!(
                        "onAccountsChanged callback threw: {}",
                        crate::api::telegram::rpc::format_exception(&ctx),
                    )));
                }
                Err(e) => (state.log)(&format!("onAccountsChanged callback failed: {e:?}")),
            }
        }
        let current = state.current().map(|info| (info.id, info.user_id));
        for scope in state.scopes.values() {
            if !state.is_live(&scope) || scope.account.get() == current {
                continue;
            }
            leave_scope(&ctx, state, &scope);
            if !state.is_live(&scope) {
                continue;
            }
            enter_scope(&ctx, state, &scope);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

pub fn notify_unload(rt: &Runtime, context: &rquickjs::Context, state: &Rc<AccountState>) {
    state.lifecycle.begin_unload();
    context.with(|ctx| {
        for scope in state.scopes.remove_matching(|_| true) {
            leave_scope(&ctx, state, &scope);
            scope.release_callback(&ctx);
        }
    });
    pump_jobs(rt, context, state.log.as_ref());
}

pub fn dispose(context: &rquickjs::Context, state: &Rc<AccountState>) {
    context.with(|ctx| {
        if let Some(prototype) = state.prototype.borrow_mut().take() {
            let _ = prototype.restore(&ctx);
        }
        state.changed_fns.release_all(&ctx);
        for scope in state.scopes.remove_matching(|_| true) {
            scope.release_callback(&ctx);
            if let Some(teardown) = scope.teardown.borrow_mut().take() {
                let _ = teardown.restore(&ctx);
            }
        }
    });
}

#[cfg(test)]
#[path = "account_tests.rs"]
pub(crate) mod tests;
