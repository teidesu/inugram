use super::*;
use crate::api::error::install_plugin_error;
use crate::sandbox::grants::TestGrantHost;
use rquickjs::Context;

/// the names the app's own `NotificationCenter` would answer to; a closed vocabulary is the
/// point, so the fake has one too
const KNOWN: &[&str] = &["dialogsNeedReload", "closeChats", "updateInterfaces", "messagesDeleted"];

#[derive(Default)]
struct TestNotificationHost {
    registered: RefCell<Vec<(u32, Vec<String>)>>,
    unregistered: RefCell<Vec<u32>>,
}

impl NotificationHost for TestNotificationHost {
    fn notification_register(&self, callback_id: u32, events: &[String]) -> Option<String> {
        if let Some(bad) = events.iter().find(|event| !KNOWN.contains(&event.as_str())) {
            return Some(format!("Pinvalid-argument\n\n\n\nno notification named '{bad}'"));
        }
        self.registered.borrow_mut().push((callback_id, events.to_vec()));
        None
    }

    fn notification_unregister(&self, callback_id: u32) {
        self.unregistered.borrow_mut().push(callback_id);
    }
}

type Disposing = crate::testing::harness::DisposeOnDrop<NotificationState>;
type Fixture = (Runtime, Context, Rc<TestNotificationHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

fn setup(grants: &[&str]) -> Fixture {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    let host = Rc::new(TestNotificationHost::default());
    let host_dyn: Rc<dyn NotificationHost> = host.clone();
    let grants = TestGrantHost::new(grants).as_host();
    let logs = crate::testing::harness::Logs::new();
    let log = crate::testing::harness::log_sink(&logs);
    let state = ctx.with(|ctx| {
        let inu = crate::testing::harness::inu_namespace(&ctx);
        install_plugin_error(&ctx, &inu).unwrap();
        install_notifications(&ctx, host_dyn, grants, Lifecycle::new(), log.clone(), &inu).unwrap()
    });
    let state = Disposing::new(&ctx, state, dispose);
    (rt, ctx, host, state, logs)
}

const GRANTED: &[&str] = &["unsafe.notificationCenter"];

use crate::testing::harness::eval_json;

use crate::testing::harness::catch_json;

fn arm(ctx: &Context) {
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__seen = [];
               globalThis.__d = inu.android.addNotificationCenterDelegate({
                 dialogsNeedReload: (...args) => __seen.push(['dialogsNeedReload', args]),
                 closeChats: (...args) => __seen.push(['closeChats', args]),
               });"#,
        )
        .unwrap()
    });
}

#[test]
fn the_delegate_is_refused_without_the_grant_and_nothing_reaches_the_host() {
    let (_rt, ctx, host, _state, _logs) = setup(&["kv"]);
    assert_eq!(
        catch_json(&ctx, "inu.android.addNotificationCenterDelegate({ closeChats: () => {} })"),
        r#"[true,"not-granted","unsafe.notificationCenter","missing grant: unsafe.notificationCenter"]"#,
    );
    assert!(host.registered.borrow().is_empty());
}

#[test]
fn a_handler_is_called_with_the_account_and_then_the_events_own_arguments() {
    let (rt, ctx, host, state, logs) = setup(GRANTED);
    arm(&ctx);
    let token = host.registered.borrow()[0].0;
    dispatch_notification(&rt, &ctx, &state, token, "closeChats", 1, "[-1001]");
    dispatch_notification(&rt, &ctx, &state, token, "dialogsNeedReload", -1, "[true]");
    // a payload the host could only encode as nulls still arrives, in place
    dispatch_notification(&rt, &ctx, &state, token, "closeChats", 0, "[null,\"text\",4.5]");
    assert_eq!(
        eval_json(&ctx, "globalThis.__seen"),
        r#"[["closeChats",[1,-1001]],["dialogsNeedReload",[-1,true]],["closeChats",[0,null,"text",4.5]]]"#,
    );
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

/// only the handler the event names runs, and an event this delegate never named is not its
/// business even if the host asks
#[test]
fn only_the_named_handler_runs() {
    let (rt, ctx, host, state, logs) = setup(GRANTED);
    arm(&ctx);
    let token = host.registered.borrow()[0].0;
    dispatch_notification(&rt, &ctx, &state, token, "messagesDeleted", 0, "[]");
    assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]");
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn the_names_the_plugin_asked_for_are_what_the_host_is_told_to_observe() {
    let (_rt, ctx, host, _state, _logs) = setup(GRANTED);
    arm(&ctx);
    let registered = host.registered.borrow().clone();
    assert_eq!(registered.len(), 1);
    assert_eq!(registered[0].1, vec!["dialogsNeedReload".to_string(), "closeChats".to_string()]);
}

/// the host owns the observers, so a disposer that did not tell it would leave one on the app's
/// centre holding this engine for the life of the process
#[test]
fn disposing_unregisters_once_and_stops_the_dispatches() {
    let (rt, ctx, host, state, _logs) = setup(GRANTED);
    arm(&ctx);
    let token = host.registered.borrow()[0].0;
    ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
    assert_eq!(*host.unregistered.borrow(), vec![token], "a disposer called twice unregisters once");
    dispatch_notification(&rt, &ctx, &state, token, "closeChats", 0, "[7]");
    assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]");
}

/// same rule as every other registry here: a registration mid-dispatch lands next dispatch, and
/// unkeyed ones stack rather than replace
#[test]
fn delegates_stack_and_each_gets_its_own_token() {
    let (rt, ctx, host, state, _logs) = setup(GRANTED);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            r#"globalThis.__ran = [];
               inu.android.addNotificationCenterDelegate({ closeChats: () => __ran.push('first') });
               inu.android.addNotificationCenterDelegate({ closeChats: () => __ran.push('second') });"#,
        )
        .unwrap()
    });
    let tokens: Vec<u32> = host.registered.borrow().iter().map(|(token, _)| *token).collect();
    assert_eq!(tokens.len(), 2);
    assert_ne!(tokens[0], tokens[1]);
    for token in tokens {
        dispatch_notification(&rt, &ctx, &state, token, "closeChats", 0, "[]");
    }
    assert_eq!(eval_json(&ctx, "globalThis.__ran"), r#"["first","second"]"#);
}

#[test]
fn a_throwing_handler_is_a_fault() {
    let (rt, ctx, host, state, logs) = setup(GRANTED);
    ctx.with(|ctx| {
        ctx.eval::<(), _>(
            "inu.android.addNotificationCenterDelegate({ closeChats: () => { throw new Error('bus-boom') } })",
        )
        .unwrap()
    });
    let token = host.registered.borrow()[0].0;
    dispatch_notification(&rt, &ctx, &state, token, "closeChats", 0, "[]");
    let entry = logs.borrow().iter().find(|l| l.contains("bus-boom")).cloned();
    let entry = entry.expect("expected a diagnostic for the throwing handler");
    assert_eq!(
        crate::classify_log(&entry).0,
        crate::LEVEL_FAULT,
        "a throwing notification handler must disable the plugin",
    );
}

/// the host cannot decode what it was handed - its bad day, not the plugin's
#[test]
fn a_payload_that_is_not_json_is_an_ordinary_error() {
    let (rt, ctx, host, state, logs) = setup(GRANTED);
    arm(&ctx);
    let token = host.registered.borrow()[0].0;
    dispatch_notification(&rt, &ctx, &state, token, "closeChats", 0, "not json");
    assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]");
    let entry = logs.borrow().first().cloned().expect("expected a diagnostic");
    assert_eq!(crate::classify_log(&entry).0, crate::LEVEL_ERROR);
}

/// the vocabulary is the app's, so only the host can refuse a name - and a refused registration
/// leaves nothing behind on either side
#[test]
fn a_host_refusal_is_what_the_plugin_is_thrown() {
    let (_rt, ctx, host, state, _logs) = setup(GRANTED);
    assert_eq!(
        catch_json(&ctx, "inu.android.addNotificationCenterDelegate({ closeChats: () => {}, nope: () => {} })"),
        r#"[true,"invalid-argument",null,"no notification named 'nope'"]"#,
    );
    assert!(host.registered.borrow().is_empty(), "one bad name refuses the whole delegate");
    assert!(host.unregistered.borrow().is_empty(), "a refused registration has nothing to unregister");
    assert!(state.delegates.is_empty(), "and nothing was kept in the engine either");
}

#[test]
fn a_delegate_that_is_not_an_object_of_functions_is_refused() {
    let (_rt, ctx, host, _state, _logs) = setup(GRANTED);
    // the last two carry a usable handler as well: a bad key must refuse the whole delegate,
    // not be skipped past into a registration the plugin did not ask for
    for bad in [
        "null",
        "42",
        "{}",
        "{ closeChats: 7 }",
        "{ closeChats: null }",
        "{ closeChats: () => {}, updateInterfaces: 7 }",
        "{ closeChats: 7, updateInterfaces: () => {} }",
    ] {
        let caught = catch_json(&ctx, &format!("inu.android.addNotificationCenterDelegate({bad})"));
        assert!(caught.starts_with(r#"[true,"invalid-argument""#), "{bad}: {caught}");
    }
    assert!(host.registered.borrow().is_empty());
}

#[test]
fn registering_after_unload_began_is_a_no_op() {
    let (rt, ctx, host, state, _logs) = setup(GRANTED);
    state.lifecycle.begin_unload();
    let shape: String = ctx.with(|ctx| {
        ctx.eval::<String, _>(
            r#"globalThis.__ran = 0;
               typeof inu.android.addNotificationCenterDelegate({ closeChats: () => { __ran++ } });"#,
        )
        .unwrap()
    });
    assert_eq!(shape, "function");
    assert!(host.registered.borrow().is_empty());
    dispatch_notification(&rt, &ctx, &state, 1, "closeChats", 0, "[]");
    assert_eq!(eval_json(&ctx, "globalThis.__ran"), "0");
}

/// the observers are the host's, and an engine going away is the one teardown no disposer runs
#[test]
fn dispose_releases_the_callbacks_and_tells_the_host_to_stop_observing() {
    let (_rt, ctx, host, state, _logs) = setup(GRANTED);
    arm(&ctx);
    dispose(&ctx, &state);
    assert!(state.delegates.is_empty());
    assert!(state.invoke.borrow().is_none());
    // the host is told through its own detach rather than one upcall per token: an engine being
    // torn down cannot answer another one. What this pins is that nothing is left in the engine
    assert!(host.unregistered.borrow().is_empty());
    // rt/ctx drop after this without aborting == the roots were released
}

const ORACLE: &str = include_str!("../../../../res/assets-debug/inu_plugins/notifications-test.js");

/// the bundled oracle is the only test this surface gets on a device, and every assertion in it
/// is a function of what it was handed - so the posts it wants are synthesised here
#[test]
fn the_bundled_notifications_test_plugin_passes() {
    let (rt, ctx, host, state, logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
    let lines = crate::testing::harness::install_capturing_console(&ctx);
    ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
        Ok(()) => {}
        Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
        Err(e) => panic!("{e:?}"),
    });
    let token = host.registered.borrow().last().expect("the oracle registered nothing").0;
    dispatch_notification(&rt, &ctx, &state, token, "dialogsNeedReload", 0, "[true]");
    dispatch_notification(&rt, &ctx, &state, token, "updateInterfaces", -1, "[512,null]");
    let lines = lines.borrow().clone();
    crate::testing::harness::assert_oracle_exact(&lines, "notifications test done", 12);
    assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}
