use super::*;
use crate::sandbox::grants::TestGrantHost;
use rquickjs::Context;

#[derive(Default)]
pub(crate) struct TestAccountHost {
    pub(crate) json: RefCell<String>,
    pub(crate) reads: Cell<u32>,
    /// stands in for a JNI failure: the host is there, it just cannot answer right now
    pub(crate) readable: Cell<bool>,
}

impl TestAccountHost {
    pub(crate) fn with(json: &str) -> Rc<Self> {
        Rc::new(TestAccountHost {
            json: RefCell::new(json.to_string()),
            reads: Cell::new(0),
            readable: Cell::new(true),
        })
    }
}

impl AccountHost for TestAccountHost {
    fn accounts(&self) -> Option<String> {
        self.reads.set(self.reads.get() + 1);
        if !self.readable.get() {
            return None;
        }
        Some(self.json.borrow().clone())
    }
}

pub(crate) const TWO_ACCOUNTS: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false},{"id":1,"userId":222,"isCurrent":false,"isPremium":true}]"#;
const SWITCHED: &str = r#"[{"id":0,"userId":111,"isCurrent":false,"isPremium":false},{"id":1,"userId":222,"isCurrent":true,"isPremium":true}]"#;

type Disposing = crate::testing::harness::DisposeOnDrop<AccountState>;
type Fixture = (Runtime, Context, Rc<TestAccountHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

/// installs the account api over [`TestAccountHost`], with `grants` as the manifest's tokens
pub(crate) fn setup(grants: &[&str], accounts: &str) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = TestAccountHost::with(accounts);
    let host_dyn: Rc<dyn AccountHost> = host.clone();
    let grants = TestGrantHost::new(grants).as_host();
    let logs = crate::testing::harness::Logs::new();
    let log = crate::testing::harness::log_sink(&logs);
    let state = ctx.with(|ctx| {
        error::install_plugin_error(&ctx).unwrap();
        install_account(&ctx, host_dyn, grants, Lifecycle::new(), log).unwrap()
    });
    let state = Disposing::new(&ctx, state, dispose);
    (rt, ctx, host, state, logs)
}

use crate::testing::harness::eval_unit as eval;

use crate::testing::harness::eval_json;

/// evaluates `code`, returning the caught error as `[isPluginError, code, grant, message]` json
use crate::testing::harness::catch_json;

#[test]
fn account_needs_no_grant_and_defaults_to_the_selected_slot() {
    let (_rt, ctx, _host, _state, _logs) = setup(&[], TWO_ACCOUNTS);
    assert_eq!(
        eval_json(&ctx, "[inu.account().id, inu.account().isCurrent(), inu.account(1).id]"),
        "[0,true,1]",
        "minting a handle and reading the parts that aren't identity costs nothing",
    );
}

/// `inu.accounts()` sits behind `account.read(self)` because a user id is the user's identity;
/// an ungated `userId` on a handle a plugin can mint per slot would hand it over anyway
#[test]
fn user_id_needs_the_self_grant_on_every_handle() {
    let (_rt, denied, _host, _state, _logs) = setup(&[], TWO_ACCOUNTS);
    assert_eq!(
        catch_json(&denied, "inu.account().userId"),
        r#"[true,"not-granted","account.read(self)","missing grant: account.read(self)"]"#,
    );
    assert_eq!(
        catch_json(&denied, "inu.account(1).userId"),
        r#"[true,"not-granted","account.read(self)","missing grant: account.read(self)"]"#,
    );
    assert_eq!(
        catch_json(&denied, "inu.withCurrentAccount((a) => { globalThis.__x = a.userId; })"),
        "no-throw",
        "the scope still runs; only the read inside it is refused",
    );

    let (_rt, ctx, _host, _state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    assert_eq!(eval_json(&ctx, "[inu.account().userId, inu.account(1).userId]"), "[111,222]");
}

#[test]
fn a_handle_stays_pinned_across_a_switch_and_only_is_current_flips() {
    let (rt, ctx, host, state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(&ctx, "globalThis.__a = inu.account();");
    assert_eq!(eval_json(&ctx, "[__a.id, __a.userId, __a.isCurrent()]"), "[0,111,true]");

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);

    assert_eq!(
        eval_json(&ctx, "[__a.id, __a.userId, __a.isCurrent()]"),
        "[0,111,false]",
        "the handle denotes the same slot; only isCurrent() moves",
    );
    assert_eq!(eval_json(&ctx, "inu.account().id"), "1");
}

#[test]
fn an_unknown_slot_refreshes_once_before_giving_up() {
    let (_rt, ctx, host, _state, _logs) =
        setup(&["account.read(self)"], r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#);
    let reads = host.reads.get();
    *host.json.borrow_mut() = TWO_ACCOUNTS.to_string();
    assert_eq!(eval_json(&ctx, "inu.account(1).userId"), "222", "a miss re-reads the host");
    assert_eq!(host.reads.get(), reads + 1);

    assert_eq!(
        catch_json(&ctx, "inu.account(7)"),
        r#"[true,"not-found",null,"account: no account is logged in as #7"]"#,
    );
}

#[test]
fn account_with_no_login_and_a_non_numeric_id_both_throw() {
    let (_rt, ctx, _host, _state, _logs) = setup(&[], "[]");
    assert_eq!(catch_json(&ctx, "inu.account()"), r#"[true,"not-found",null,"account: no account is logged in"]"#,);
    for id in ["'0'", "true", "{}", "[]", "() => 0"] {
        assert_eq!(
            catch_json(&ctx, &format!("inu.account({id})")),
            r#"[true,"invalid-argument",null,"account: 'id' must be a number"]"#,
            "id: {id}",
        );
    }
}

/// `as i32` saturates, so a coerced id answers with a real slot: `NaN` used to mean slot 0
#[test]
fn a_non_integral_id_is_refused_rather_than_coerced() {
    let (_rt, ctx, _host, _state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    for id in ["NaN", "Infinity", "-Infinity", "1.5", "-0.5", "1e12", "-1e12"] {
        assert_eq!(
            catch_json(&ctx, &format!("inu.account({id})")),
            r#"[true,"invalid-argument",null,"account: 'id' must be an integer slot index"]"#,
            "id: {id}",
        );
    }
    assert_eq!(eval_json(&ctx, "[inu.account(1.0).id, inu.account(-0).id]"), "[1,0]");
}

#[test]
fn accounts_lists_every_slot_but_needs_the_self_grant() {
    let (_rt, ctx, _host, _state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    assert_eq!(eval_json(&ctx, "inu.accounts()"), TWO_ACCOUNTS);

    let (_rt, denied, _host, _state, _logs) = setup(&[], TWO_ACCOUNTS);
    assert_eq!(
        catch_json(&denied, "inu.accounts()"),
        r#"[true,"not-granted","account.read(self)","missing grant: account.read(self)"]"#,
    );
}

#[test]
fn on_accounts_changed_fires_with_the_new_list_and_its_disposer_stops_it() {
    let (rt, ctx, host, state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__seen = [];
        globalThis.__d = inu.onAccountsChanged((accounts) => {
            __seen.push(accounts.map((a) => `${a.id}:${a.isCurrent}`).join(','));
        });
        "#,
    );

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__seen"), r#"["0:false,1:true"]"#);

    eval(&ctx, "__d(); __d();");
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__seen"), r#"["0:false,1:true"]"#);
}

#[test]
fn on_accounts_changed_without_a_grant_throws_not_granted() {
    let (_rt, ctx, _host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    assert_eq!(
        catch_json(&ctx, "inu.onAccountsChanged(() => {})"),
        r#"[true,"not-granted","account.read(self)","missing grant: account.read(self)"]"#,
    );
    assert!(state.changed_fns.is_empty());
}

#[test]
fn with_current_account_runs_now_and_tears_down_before_the_next_account() {
    let (rt, ctx, host, state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.withCurrentAccount((account) => {
            __log.push(`setup:${account.id}:${account.userId}`);
            return () => { __log.push(`teardown:${account.id}`); };
        });
        "#,
    );
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:0:111"]"#);

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:0:111","teardown:0","setup:1:222"]"#);
}

#[test]
fn a_change_that_leaves_the_account_alone_does_not_re_run_the_scope() {
    let (rt, ctx, host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__runs = 0;
        inu.withCurrentAccount(() => { __runs++; });
        "#,
    );
    // the other slot went premium; the selected account is untouched
    *host.json.borrow_mut() =
        r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false},{"id":1,"userId":222,"isCurrent":false,"isPremium":false}]"#.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__runs"), "1");
}

/// the slot index is reused once its account logs out, so it alone cannot decide "same account"
#[test]
fn a_slot_re_used_by_another_login_re_runs_the_scope() {
    let (rt, ctx, host, state, _logs) =
        setup(&["account.read(self)"], r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false}]"#);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.withCurrentAccount((account) => {
            __log.push(`setup:${account.userId}`);
            return () => { __log.push(`teardown:${account.userId}`); };
        });
        "#,
    );
    *host.json.borrow_mut() = r#"[{"id":0,"userId":999,"isCurrent":true,"isPremium":false}]"#.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:111","teardown:111","setup:999"]"#);
}

#[test]
fn with_no_account_logged_in_the_scope_waits_for_one() {
    let (rt, ctx, host, state, _logs) = setup(&[], "[]");
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.withCurrentAccount((account) => {
            __log.push(`setup:${account.id}`);
            return () => { __log.push('teardown'); };
        });
        "#,
    );
    assert_eq!(eval_json(&ctx, "__log"), "[]");

    *host.json.borrow_mut() = TWO_ACCOUNTS.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:0"]"#);

    // everyone logged out again: the scope is torn down and left waiting
    *host.json.borrow_mut() = "[]".to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:0","teardown"]"#);
}

#[test]
fn disposing_a_scope_tears_it_down_and_stops_re_runs() {
    let (rt, ctx, host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        globalThis.__d = inu.withCurrentAccount(() => {
            __log.push('setup');
            return () => { __log.push('teardown'); };
        });
        __d();
        __d();
        "#,
    );
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup","teardown"]"#, "a disposer called twice tears down once");

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup","teardown"]"#);
    assert!(state.scopes.is_empty());
}

/// the walk is over a snapshot, so a scope disposed by an earlier scope's callback used to be
/// re-entered afterwards - and the teardown that re-entry returned was parked on an entry the
/// registry no longer held, so it never ran at all
#[test]
fn a_scope_disposed_mid_walk_is_not_re_entered() {
    let (rt, ctx, host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.withCurrentAccount(() => {
            __log.push('A');
            if (globalThis.__db) __db();
            return () => { __log.push('A-teardown'); };
        });
        globalThis.__db = inu.withCurrentAccount(() => {
            __log.push('B');
            return () => { __log.push('B-teardown'); };
        });
        "#,
    );
    assert_eq!(eval_json(&ctx, "__log"), r#"["A","B"]"#);

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(
        eval_json(&ctx, "__log"),
        r#"["A","B","A-teardown","A","B-teardown"]"#,
        "B is disposed while the walk is inside A's callback, so it is torn down and never re-runs",
    );
    assert_eq!(state.scopes.len(), 1);

    notify_unload(&rt, &ctx, &state);
    assert_eq!(
        eval_json(&ctx, "__log"),
        r#"["A","B","A-teardown","A","B-teardown","A-teardown"]"#,
        "every setup is matched by exactly one teardown",
    );
}

/// a teardown may dispose its own scope, which ends it: the re-run must not happen either
#[test]
fn a_scope_its_own_teardown_disposes_is_not_re_entered() {
    let (rt, ctx, host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        globalThis.__d = inu.withCurrentAccount(() => {
            __log.push('setup');
            return () => { __log.push('teardown'); __d(); };
        });
        "#,
    );
    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup","teardown"]"#);
    assert!(state.scopes.is_empty());
}

/// common.d.ts: the teardown runs before the next invocation, when the disposer is called, and
/// once more on unload. a callback that disposes its own scope used to hit none of the three -
/// the teardown was stored on an entry the disposer had already removed.
#[test]
fn a_scope_that_disposes_itself_from_its_own_callback_still_tears_down() {
    let (rt, ctx, host, state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        globalThis.__d = inu.withCurrentAccount((a) => {
            __log.push(`setup:${a.id}`);
            if (a.id === 1) __d();
            return () => { __log.push(`teardown:${a.id}`); };
        });
        "#,
    );
    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:0","teardown:0","setup:1","teardown:1"]"#,);
    assert!(state.scopes.is_empty());
}

#[test]
fn unload_runs_every_teardown_once_more() {
    let (rt, ctx, _host, state, _logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.withCurrentAccount(() => () => { __log.push('a'); });
        inu.withCurrentAccount(() => () => { __log.push('b'); });
        "#,
    );
    notify_unload(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["a","b"]"#);
    assert!(state.scopes.is_empty());
}

#[test]
fn registering_after_unload_began_is_a_no_op_returning_a_no_op_disposer() {
    let (rt, ctx, _host, state, _logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    state.lifecycle.begin_unload();
    eval(
        &ctx,
        r#"
        globalThis.__ran = [];
        globalThis.__shapes = [
            typeof inu.onAccountsChanged(() => { __ran.push('changed'); }),
            typeof inu.withCurrentAccount(() => { __ran.push('scope'); }),
        ];
        "#,
    );
    assert_eq!(eval_json(&ctx, "__shapes"), r#"["function","function"]"#);
    assert_eq!(eval_json(&ctx, "__ran"), "[]");
    assert!(state.changed_fns.is_empty());
    assert!(state.scopes.is_empty());

    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__ran"), "[]");
}

#[test]
fn a_throwing_scope_callback_faults_and_the_others_still_run() {
    let (_rt, ctx, _host, _state, logs) = setup(&[], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__ran = [];
        inu.withCurrentAccount(() => { throw new Error('scope-boom'); });
        inu.withCurrentAccount(() => { __ran.push('second'); });
        "#,
    );
    assert_eq!(eval_json(&ctx, "__ran"), r#"["second"]"#);
    assert_fault(&logs, "scope-boom");
}

/// the level of the one diagnostic mentioning `needle`. Only [`crate::LEVEL_FAULT`] reaches
/// `PluginManager`'s `fail(...)`, so a plugin whose callback throws is switched off by this and
/// by nothing else.
fn assert_fault(logs: &std::sync::Arc<crate::testing::harness::Logs>, needle: &str) {
    let logs = logs.borrow();
    let Some(line) = logs.iter().find(|l| l.contains(needle)) else {
        panic!("no diagnostic for '{needle}', got: {logs:?}");
    };
    let (level, message) = crate::classify_log(line);
    assert_eq!(level, crate::LEVEL_FAULT, "'{message}' must disable the plugin");
}

/// `common.d.ts`: "a fault like any other callback that throws: your plugin is switched off".
/// The teardown is held to it as well, on the unload path too - `onUnload` throwing already
/// faults there (`PluginFailure.Site.UNLOAD`), and a teardown is the same plugin code failing
/// in the same place.
#[test]
fn every_throwing_account_callback_is_a_fault_including_a_teardown_on_unload() {
    let (rt, ctx, host, state, logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        inu.onAccountsChanged(() => { throw new Error('changed-boom'); });
        inu.withCurrentAccount(() => () => { throw new Error('teardown-boom'); });
        "#,
    );

    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_fault(&logs, "changed-boom");
    assert_fault(&logs, "teardown-boom");

    logs.borrow_mut().clear();
    notify_unload(&rt, &ctx, &state);
    assert_fault(&logs, "teardown-boom");
}

#[test]
fn an_unreadable_host_snapshot_is_logged_and_leaves_the_cache_alone() {
    let (rt, ctx, host, state, logs) = setup(&[], TWO_ACCOUNTS);
    *host.json.borrow_mut() = "not json".to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "inu.account().id"), "0");
    assert!(
        logs.borrow().iter().any(|l| l.contains("unreadable host snapshot")),
        "expected a logged diagnostic, got: {:?}",
        logs.borrow(),
    );
}

/// a host that cannot answer says nothing about who is logged in, so it must not read as a
/// logout: doing so tore down every live scope over a transient JNI failure
#[test]
fn a_host_that_cannot_be_read_is_not_an_empty_account_list() {
    let (rt, ctx, host, state, logs) = setup(&["account.read(self)"], TWO_ACCOUNTS);
    eval(
        &ctx,
        r#"
        globalThis.__log = [];
        inu.onAccountsChanged((accounts) => { __log.push(`changed:${accounts.length}`); });
        inu.withCurrentAccount((a) => {
            __log.push(`setup:${a.userId}`);
            return () => { __log.push('teardown'); };
        });
        "#,
    );
    host.readable.set(false);
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:111"]"#);
    assert_eq!(eval_json(&ctx, "inu.accounts().length"), "2", "the cache is left alone");
    assert!(
        logs.borrow().iter().any(|l| l.contains("could not read the account list")),
        "expected a logged diagnostic, got: {:?}",
        logs.borrow(),
    );

    host.readable.set(true);
    *host.json.borrow_mut() = SWITCHED.to_string();
    accounts_changed(&rt, &ctx, &state);
    assert_eq!(eval_json(&ctx, "__log"), r#"["setup:111","changed:2","teardown","setup:222"]"#);
}
