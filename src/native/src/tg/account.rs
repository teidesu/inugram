//! `inu.account` / `inu.accounts` / `inu.onAccountsChanged` / `inu.withCurrentAccount`. JNI-free
//! behind [`AccountHost`].
//!
//! An `Account` is **pinned to a slot for life**: `id` and `userId` are read at mint time and
//! `isCurrent()` is the only part that moves, so handing one to a helper cannot silently retarget
//! on a switch. Minting costs no grant and neither do `id`/`isCurrent()`; `userId` is behind
//! `account.read(self)`, or the gate on `inu.accounts()` would be decorative, since a plugin can
//! mint one handle per slot. Slot *existence* stays ungated: it reveals how many logins there are,
//! never whose.
//!
//! The slot list is cached rather than fetched per call, because every `onUpdate` payload and every
//! `interceptRpc` dispatch carries an `Account`. A lookup that misses refreshes once before giving
//! up, so a change the host failed to announce costs a stale read and not a wrong answer.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use rquickjs::function::Opt;
use rquickjs::object::Accessor;
use rquickjs::{Array, Ctx, Function, Object, Persistent, Result as JsResult, Runtime, Value};

use crate::api::json_parse;
use crate::engine::error::{self, check_grant, get_or_create_inu, GrantHost, MATCH_EXACT};
use crate::engine::registry::{make_disposer, noop_disposer, CallbackRegistry, Lifecycle, Registry, Token};
use crate::tg::rpc::pump_jobs;

/// stand-in for the accounts half of the Kotlin `QuickJs.ApiListener`
pub trait AccountHost {
    /// every logged-in slot as json: `[{"id":0,"userId":1,"isCurrent":true,"isPremium":false}]`.
    /// `None` means the list could not be read at all, which is not the same answer as `[]`.
    fn accounts(&self) -> Option<String>;
}

#[derive(Clone, PartialEq)]
pub struct AccountInfo {
    id: i32,
    user_id: i64,
    is_current: bool,
    is_premium: bool,
}

/// one live `withCurrentAccount` registration. [`account`] is the slot its last invocation ran
/// for, paired with the user id so a slot re-used by a different login re-runs the callback.
struct CurrentScope {
    token: Token,
    /// an `Option` so releasing it can `take` it: `Persistent` has no `Drop`, and restoring a
    /// *clone* only gives back the reference the clone added, leaving the original's GC root held
    /// for the life of the runtime
    callback: RefCell<Option<Persistent<Function<'static>>>>,
    teardown: RefCell<Option<Persistent<Function<'static>>>>,
    account: Cell<Option<(i32, i64)>>,
}

impl CurrentScope {
    /// a clone to call through, the original staying rooted until [`Self::release_callback`]
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
    /// the read surface [`crate::tg::reads`] installs, shared by every handle: a dispatch mints one of
    /// these per update and per intercepted request, so its dozen getters are built once per engine
    /// rather than once per mint. Absent until `installRpc`, and a handle minted before then simply
    /// has none - nothing can call one, since no plugin code has run yet.
    prototype: RefCell<Option<Persistent<Object<'static>>>>,
}

impl AccountState {
    fn find(&self, id: i32) -> Option<AccountInfo> {
        self.accounts.borrow().iter().find(|a| a.id == id).cloned()
    }

    fn current(&self) -> Option<AccountInfo> {
        self.accounts.borrow().iter().find(|a| a.is_current).cloned()
    }

    /// a scope stays live only while the registry still holds it, and anything that runs plugin JS
    /// (a callback, a teardown, another scope's callback) can dispose it mid-walk
    fn is_live(&self, scope: &Rc<CurrentScope>) -> bool {
        self.scopes.contains(scope.token)
    }
}

fn parse_accounts<'js>(ctx: &Ctx<'js>, json: &str) -> JsResult<Vec<AccountInfo>> {
    let value = json_parse(ctx, json)?;
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

/// `false` when the cache was left alone because the host's answer was unusable, which callers must
/// not confuse with "the list is now empty"
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
                rquickjs::Error::Exception => crate::tg::rpc::format_exception(ctx),
                other => other.to_string(),
            };
            (state.log)(&format!("accounts: unreadable host snapshot: {msg}"));
            false
        }
    }
}

/// hands every `Account` handle the getters in [`crate::tg::reads`]; called once, at install
pub fn set_prototype<'js>(ctx: &Ctx<'js>, state: &Rc<AccountState>, prototype: &Object<'js>) {
    *state.prototype.borrow_mut() = Some(Persistent::save(ctx, prototype.clone()));
}

/// what a later surface chains its own prototype behind, so the two families share one handle
/// without either file knowing the other's members.
///
/// It *takes* rather than reads: a `Persistent` has no `Drop`, so leaving this one in place while
/// [`set_prototype`] overwrites it would leak a GC root and abort `JS_FreeRuntime`. The caller is
/// the only holder afterwards, which is what makes the chain it builds the one thing keeping the
/// old prototype alive.
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

/// the slot's own user id, with no grant check. This exists for `sendmsg.js`'s `peer` getter and is
/// handed to that prelude as a factory argument, so nothing reachable from plugin code holds it:
/// `common.d.ts` promises reading `OutgoingMessage.peer` needs no grant, and the one peer form that
/// has to be resolved through the account is `inputPeerSelf`. Going through the gated `userId`
/// accessor there fails the *user's* send and faults the plugin, which is worse than the disclosure
/// under either reading of the contract.
pub fn self_user_id(state: &Option<Rc<AccountState>>, account_id: i32) -> Option<i64> {
    state.as_ref()?.find(account_id).map(|info| info.user_id)
}

/// the `Account` handed to a dispatch (`onUpdate`, `interceptRpc`). A slot the cache doesn't know
/// is refreshed once before falling back: the host only dispatches for live accounts, so a miss
/// means the cache is behind, and a handle with a zero `userId` is the last resort rather than the
/// answer.
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

    let inu = get_or_create_inu(ctx)?;
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
            // a slot index is an exact i32, so NaN/Infinity/1.5/1e12 are refused rather than
            // truncated - `as i32` saturates, and would answer `inu.account(NaN)` with slot 0
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

    // resolved once, here: the handle is pinned, so a later switch must not move it
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
        // disposing ends the scope, so its teardown runs here as it would before a re-run
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
                crate::tg::rpc::format_exception(ctx),
            )));
        }
        Err(e) => (state.log)(&format!("withCurrentAccount teardown failed: {e:?}")),
    }
}

/// runs the scope's teardown, if it left one, and forgets which account it was set up for
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

/// invokes the scope's callback for whichever account is selected now, keeping the teardown it
/// returns. a no-op while no account is logged in - the callback takes an `Account`, so there is
/// nothing to hand it until one is.
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
                // the callback disposed its own scope: storing the teardown would park it on an
                // entry nothing walks again, so the disposal that raced it runs it now instead
                run_teardown(ctx, state, &teardown);
            }
        }
        Err(rquickjs::Error::Exception) => {
            (state.log)(&crate::fault(format_args!(
                "withCurrentAccount callback threw: {}",
                crate::tg::rpc::format_exception(ctx),
            )));
        }
        Err(e) => (state.log)(&format!("withCurrentAccount callback failed: {e:?}")),
    }
}

/// the logged-in set changed (login, logout or switch): re-reads it, fans the new list out to
/// `onAccountsChanged`, then re-runs every `withCurrentAccount` whose account moved.
pub fn accounts_changed(rt: &Runtime, context: &rquickjs::Context, state: &Rc<AccountState>) {
    context.with(|ctx| {
        // a read the host could not answer is not a changed list: announcing the stale cache as new
        // is noise, and re-running the scopes off it would tear every one of them down
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
                        crate::tg::rpc::format_exception(&ctx),
                    )));
                }
                Err(e) => (state.log)(&format!("onAccountsChanged callback failed: {e:?}")),
            }
        }
        let current = state.current().map(|info| (info.id, info.user_id));
        // the walk is over a snapshot, and every callback and teardown it runs can dispose any
        // scope in it - including one already visited - so liveness is re-read per iteration and
        // again after the teardown, never assumed from having been live a step ago
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

/// runs every live `withCurrentAccount` teardown one last time. call before
/// [`crate::api::notify_unload`], so a teardown still sees a plugin that has not been told goodbye.
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

/// releases every `Persistent` GC root this state still owns - same contract as [`crate::tg::rpc::dispose`]
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
