use super::*;
use crate::api::error::install_plugin_error;
use crate::api::telegram::account::tests::TestAccountHost;
use crate::sandbox::grants::TestGrantHost;
use rquickjs::Context;

const TWO_ACCOUNTS: &str = r#"[{"id":0,"userId":111,"isCurrent":true,"isPremium":false},{"id":1,"userId":222,"isCurrent":false,"isPremium":true}]"#;

#[derive(Default)]
struct TestScreenHost {
    screen: RefCell<String>,
    reads: std::cell::Cell<u32>,
}

impl ScreenHost for TestScreenHost {
    fn current_screen(&self) -> String {
        self.reads.set(self.reads.get() + 1);
        self.screen.borrow().clone()
    }
}

type Disposing = crate::testing::harness::DisposeOnDrop<ScreenState>;
type AccountDisposing = crate::testing::harness::DisposeOnDrop<AccountState>;
type Fixture =
    (Runtime, Context, Rc<TestScreenHost>, Disposing, AccountDisposing, std::sync::Arc<crate::testing::harness::Logs>);

fn setup(grants: &[&str]) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = Rc::new(TestScreenHost::default());
    *host.screen.borrow_mut() = "N".to_string();
    let host_dyn: Rc<dyn ScreenHost> = host.clone();
    let grants = TestGrantHost::new(grants).as_host();
    let logs = crate::testing::harness::Logs::new();
    let log = crate::testing::harness::log_sink(&logs);
    let lifecycle = Lifecycle::new();
    let (state, accounts) = ctx.with(|ctx| {
        install_plugin_error(&ctx).unwrap();
        let accounts = crate::api::telegram::account::install_account(
            &ctx,
            TestAccountHost::with(TWO_ACCOUNTS),
            grants.clone(),
            lifecycle.clone(),
            log.clone(),
        )
        .unwrap();
        let state = install_screens(&ctx, host_dyn, grants, Some(accounts.clone()), lifecycle, log.clone()).unwrap();
        (state, accounts)
    });
    let state = Disposing::new(&ctx, state, dispose);
    let accounts = AccountDisposing::new(&ctx, accounts, crate::api::telegram::account::dispose);
    (rt, ctx, host, state, accounts, logs)
}

const CHAT: &str = r#"{"type":"chat","dialogId":-1001,"topicId":7,"account":1}"#;
const DIALOGS: &str = r#"{"type":"dialogs","account":0}"#;

use crate::testing::harness::eval_json;

#[test]
fn current_screen_answers_null_for_every_shape_of_nothing() {
    let (_rt, ctx, host, _state, _accounts, _logs) = setup(&["account.read(dialogs)"]);
    for wire in ["N", "", "Esomething broke"] {
        *host.screen.borrow_mut() = wire.to_string();
        assert_eq!(eval_json(&ctx, "inu.ui.getCurrentScreen()"), "null", "wire: {wire:?}");
    }
}

#[test]
fn current_screen_carries_the_dialog_only_with_the_dialogs_grant() {
    let (_rt, ctx, host, _state, _accounts, _logs) = setup(&["account.read(dialogs)"]);
    *host.screen.borrow_mut() = format!("J{CHAT}");
    assert_eq!(
        eval_json(
            &ctx,
            "(s => [s.type, s.dialogId, s.topicId, s.account.id, s.account.isCurrent()])(inu.ui.getCurrentScreen())"
        ),
        r#"["chat",-1001,7,1,false]"#,
    );

    let (_rt, ctx, host, _state, _accounts, _logs) = setup(&[]);
    *host.screen.borrow_mut() = format!("J{CHAT}");
    assert_eq!(
        eval_json(&ctx, "(s => [s.type, 'dialogId' in s, 'topicId' in s, s.account.id])(inu.ui.getCurrentScreen())",),
        r#"["chat",false,false,1]"#,
        "without account.read(dialogs) the fields are absent, not null and not a throw",
    );
}

#[test]
fn a_hijacked_json_global_never_sees_the_ungated_screen_wire() {
    let (_rt, ctx, host, _state, _accounts, _logs) = setup(&[]);
    *host.screen.borrow_mut() = format!("J{CHAT}");
    crate::testing::harness::eval_unit(
        &ctx,
        r#"
        globalThis.__wires = [];
        globalThis.JSON = {
            parse: (s) => { globalThis.__wires.push(String(s)); return {type: 'chat', dialogId: -1001} },
            stringify: () => '"hijacked"',
        };
        "#,
    );
    let seen = crate::testing::harness::eval_string(
        &ctx,
        "(s => [s.type, 'dialogId' in s, globalThis.__wires.length].join(','))(inu.ui.getCurrentScreen())",
    );
    assert_eq!(seen, "chat,false,0", "the host wire carries dialogId; without the grant nothing may see it");
}

#[test]
fn current_screen_is_read_per_call_and_never_cached() {
    let (_rt, ctx, host, _state, _accounts, _logs) = setup(&[]);
    *host.screen.borrow_mut() = format!("J{DIALOGS}");
    assert_eq!(eval_json(&ctx, "inu.ui.getCurrentScreen().type"), r#""dialogs""#);
    *host.screen.borrow_mut() = format!("J{CHAT}");
    assert_eq!(eval_json(&ctx, "inu.ui.getCurrentScreen().type"), r#""chat""#);
    assert_eq!(host.reads.get(), 2);
}

fn arm(ctx: &Context) {
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__seen = [];
            globalThis.__stackReads = 0;
            globalThis.__d = inu.ui.onScreenChanged(change => {
                globalThis.__last = change;
                globalThis.__seen.push([
                    change.action,
                    change.screen === null ? null : change.screen.type,
                    change.previous === null ? null : change.previous.type,
                ]);
            });
            "#,
        )
        .unwrap();
    });
}

#[test]
fn a_change_carries_action_screen_and_previous() {
    let (rt, ctx, _host, state, _accounts, logs) = setup(&["account.read(dialogs)"]);
    arm(&ctx);
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        &format!(r#"{{"action":"push","screen":{CHAT},"previous":{DIALOGS}}}"#),
        &format!("[{DIALOGS},{CHAT}]"),
    );
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        &format!(r#"{{"action":"pop","screen":{DIALOGS},"previous":{CHAT}}}"#),
        &format!("[{DIALOGS}]"),
    );
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        r#"{"action":"pop","screen":null,"previous":{"type":"dialogs","account":0}}"#,
        "[]",
    );
    assert_eq!(
        eval_json(&ctx, "globalThis.__seen"),
        r#"[["push","chat","dialogs"],["pop","dialogs","chat"],["pop",null,"dialogs"]]"#,
    );
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn the_stack_is_a_memoized_getter_over_what_the_host_sent() {
    let (rt, ctx, _host, state, _accounts, _logs) = setup(&["account.read(dialogs)"]);
    arm(&ctx);
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        &format!(r#"{{"action":"push","screen":{CHAT},"previous":{DIALOGS}}}"#),
        &format!("[{DIALOGS},{CHAT}]"),
    );
    assert_eq!(
        eval_json(&ctx, "__last.stack.map(s => [s.type, s.dialogId ?? null, s.account.id])"),
        r#"[["dialogs",null,0],["chat",-1001,1]]"#,
    );
    assert_eq!(eval_json(&ctx, "__last.stack === __last.stack"), "true", "reading it twice must not rebuild the graph",);
    assert_eq!(eval_json(&ctx, "__last.stack[__last.stack.length - 1].type === __last.screen.type"), "true",);
}

#[test]
fn a_plugin_that_never_touches_the_stack_never_materializes_it() {
    let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
    arm(&ctx);
    // a stack json nothing could parse: reaching it at all is the failure this pins
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        &format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#),
        "not json at all",
    );
    assert_eq!(eval_json(&ctx, "globalThis.__seen"), r#"[["push","dialogs",null]]"#);
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
    let threw: String = ctx.with(|ctx| {
        ctx.eval("(() => { try { __last.stack; return 'no-throw'; } catch (e) { return 'threw'; } })()").unwrap()
    });
    assert_eq!(threw, "threw", "and touching it is where the cost, and the failure, lands");
}

#[test]
fn nothing_is_dispatched_and_nothing_parsed_without_a_registration() {
    let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
    dispatch_screen_change(&rt, &ctx, &state, "not json at all", "not json either");
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
    let _ = &ctx;
}

#[test]
fn registrations_stack_dispose_once_and_a_throw_faults() {
    let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"
            globalThis.__ran = [];
            inu.ui.onScreenChanged(() => { throw new Error('nav-boom'); });
            globalThis.__d = inu.ui.onScreenChanged(() => { __ran.push('second'); });
            inu.ui.onScreenChanged(() => { __ran.push('third'); });
            "#,
        )
        .unwrap();
    });
    let change = format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#);
    dispatch_screen_change(&rt, &ctx, &state, &change, "[]");
    assert_eq!(eval_json(&ctx, "globalThis.__ran"), r#"["second","third"]"#);
    let entry = logs.borrow().iter().find(|l| l.contains("nav-boom")).cloned();
    let entry = entry.expect("expected a diagnostic for the throwing callback");
    assert_eq!(
        crate::classify_log(&entry).0,
        crate::LEVEL_FAULT,
        "a throwing navigation callback must disable the plugin",
    );

    ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
    dispatch_screen_change(&rt, &ctx, &state, &change, "[]");
    assert_eq!(
        eval_json(&ctx, "globalThis.__ran"),
        r#"["second","third","third"]"#,
        "a disposer called twice unregisters once",
    );
}

#[test]
fn registering_after_unload_began_is_a_no_op() {
    let (rt, ctx, _host, state, _accounts, _logs) = setup(&[]);
    state.lifecycle.begin_unload();
    let shape: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"
            globalThis.__ran = 0;
            typeof inu.ui.onScreenChanged(() => { globalThis.__ran++; });
            "#,
        )
        .unwrap()
    });
    assert_eq!(shape, "function");
    assert!(state.changed_fns.is_empty());
    dispatch_screen_change(
        &rt,
        &ctx,
        &state,
        &format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#),
        "[]",
    );
    assert_eq!(eval_json(&ctx, "globalThis.__ran"), "0");
}

const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/nav-test.js");

/// the bundled oracle is the only test this surface gets on a device, and every assertion in it
/// is a function of the change it was handed, so the four navigations it wants are synthesised
/// here. the count is exact: a member that vanished reads as a refusal in a suite written out
/// of `expectThrow`
#[test]
fn the_bundled_nav_test_plugin_passes() {
    let (rt, ctx, host, state, _accounts, logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
    *host.screen.borrow_mut() = format!("J{DIALOGS}");
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });

    const SETTINGS: &str = r#"{"type":"settings","account":0}"#;
    for (top, change, stack) in [
        (DIALOGS, format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#), format!("[{DIALOGS}]")),
        (CHAT, format!(r#"{{"action":"push","screen":{CHAT},"previous":{DIALOGS}}}"#), format!("[{DIALOGS},{CHAT}]")),
        (DIALOGS, format!(r#"{{"action":"pop","screen":{DIALOGS},"previous":{CHAT}}}"#), format!("[{DIALOGS}]")),
        (
            SETTINGS,
            format!(r#"{{"action":"replace","screen":{SETTINGS},"previous":{DIALOGS}}}"#),
            format!("[{SETTINGS}]"),
        ),
    ] {
        *host.screen.borrow_mut() = format!("J{top}");
        dispatch_screen_change(&rt, &ctx, &state, &change, &stack);
    }

    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "nav test done", 38);
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn dispose_releases_the_callbacks_and_the_event_factory() {
    let (_rt, ctx, _host, state, _accounts, _logs) = setup(&[]);
    arm(&ctx);
    dispose(&ctx, &state);
    assert!(state.changed_fns.is_empty());
    assert!(state.event_factory.borrow().is_none());
    // rt/ctx drop after this without aborting == roots were released
}
