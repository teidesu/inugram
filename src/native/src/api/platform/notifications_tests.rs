use super::*;
use crate::api::platform::jvm::{install_jvm, JvmHost, JvmState};
use crate::sandbox::grants::TestGrantHost;
use rquickjs::Context;
use std::ops::Deref;

/// the names the app's own `NotificationCenter` would answer to; a closed vocabulary is the
/// point, so the fake has one too
const KNOWN: &[&str] = &["dialogsNeedReload", "closeChats", "updateInterfaces", "messagesDeleted"];

#[derive(Default)]
struct TestNotificationHost {
  registered: RefCell<Vec<(u32, Vec<String>)>>,
  unregistered: RefCell<Vec<u32>>,
  suppressed: RefCell<Vec<(u32, i32, bool)>>,
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

  fn notification_suppress(&self, token: u32, account: i32, on: bool) {
    self.suppressed.borrow_mut().push((token, account, on));
  }
}

/// the jvm table a payload's handles are minted into; every op answers the wire the real host would
#[derive(Default)]
struct TestJvmHost {
  calls: RefCell<Vec<String>>,
}

impl JvmHost for TestJvmHost {
  fn jvm(&self, op: i32, target: i64, name: &str, args: &[String]) -> String {
    self.calls.borrow_mut().push(format!("{op}|{target}|{name}|{}", args.join(",")));
    "Sanswered".to_string()
  }
}

/// the jvm bridge kept alive beside the delegate, so that disposing the fixture releases both
/// engines' roots rather than aborting the runtime free on the ones nobody dropped
struct Jvm {
  host: Rc<TestJvmHost>,
  _state: crate::testing::harness::DisposeOnDrop<JvmState>,
}

impl Deref for Jvm {
  type Target = TestJvmHost;

  fn deref(&self) -> &TestJvmHost {
    &self.host
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<NotificationState>;
type Fixture = (
  Runtime,
  Context,
  Rc<TestNotificationHost>,
  Disposing,
  std::sync::Arc<crate::testing::harness::Logs>,
  Jvm,
);

fn setup(grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestNotificationHost::default());
  let host_dyn: Rc<dyn NotificationHost> = host.clone();
  let grants = TestGrantHost::new(grants).as_host();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let jvm_host = Rc::new(TestJvmHost::default());
  let jvm_host_dyn: Rc<dyn JvmHost> = jvm_host.clone();
  let (state, jvm_state) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let jvm = install_jvm(&ctx, jvm_host_dyn, None, grants.clone(), Lifecycle::new(), log.clone(), None, &inu).unwrap();
    let state =
      install_notifications(&ctx, host_dyn, grants, Lifecycle::new(), log.clone(), Some(jvm.clone()), &inu).unwrap();
    (state, jvm)
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let jvm = Jvm {
    host: jvm_host,
    _state: crate::testing::harness::DisposeOnDrop::new(&ctx, jvm_state, |ctx, state| state.dispose(ctx)),
  };
  (rt, ctx, host, state, logs, jvm)
}

const GRANTED: &[&str] = &["unsafe.notificationCenter", "unsafe.jvm"];

use crate::testing::harness::eval_json;

use crate::testing::harness::catch_json;

fn arm(ctx: &Context) {
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
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
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(&["kv"]);
  assert_eq!(
    catch_json(&ctx, "inu.android.addNotificationCenterDelegate({ closeChats: () => {} })"),
    r#"[true,"not-granted","unsafe.notificationCenter","missing grant: unsafe.notificationCenter"]"#,
  );
  assert!(host.registered.borrow().is_empty());
}

#[test]
fn a_handler_is_called_with_the_account_and_then_the_events_own_arguments() {
  let (rt, ctx, host, state, logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  let token = host.registered.borrow()[0].0;
  state.dispatch(&rt, &ctx, token, "closeChats", 1, &["I-1001".to_string()]);
  state.dispatch(&rt, &ctx, token, "dialogsNeedReload", -1, &["B1".to_string()]);
  // a value the host could not encode is that one argument lost, and it still arrives in place
  state.dispatch(&rt, &ctx, token, "closeChats", 0, &["N".to_string(), "Stext".to_string(), "D4.5".to_string()]);
  assert_eq!(
    eval_json(&ctx, "globalThis.__seen"),
    r#"[["closeChats",[1,-1001]],["dialogsNeedReload",[-1,true]],["closeChats",[0,null,"text",4.5]]]"#,
  );
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

/// what this surface is for: an event's java arguments reach the plugin as the same `JavaObject`
/// `inu.jvm` would hand it, rather than as the nulls a scalars-only payload had to put there.
/// What the handle then *does* is the jvm bridge's own business and is pinned there; a unit test
/// has no vm to call into
#[test]
fn a_non_scalar_argument_arrives_as_a_jvm_handle() {
  let (rt, ctx, host, state, logs, _jvm) = setup(GRANTED);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"globalThis.__shape = null;
               inu.android.addNotificationCenterDelegate({
                 closeChats: (account, dialogId, messages) => {
                   __shape = [account, dialogId, typeof messages, typeof messages.call, typeof messages.getField];
                 },
               });"#,
      )
      .unwrap()
  });
  let token = host.registered.borrow()[0].0;
  state.dispatch(&rt, &ctx, token, "closeChats", 3, &["I-1001".to_string(), "GO77".to_string()]);
  assert_eq!(
    eval_json(&ctx, "globalThis.__shape"),
    r#"[3,-1001,"object","function","function"]"#,
    "a scalar stays a scalar and the java object beside it becomes a handle",
  );
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

/// the payload is java objects, so the delegate reaches as far as `inu.jvm` does and is refused
/// the same way when a plugin asked for only half of that
#[test]
fn the_delegate_is_refused_without_the_jvm_grant() {
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(&["unsafe.notificationCenter"]);
  assert_eq!(
    catch_json(&ctx, "inu.android.addNotificationCenterDelegate({ closeChats: () => {} })"),
    r#"[true,"not-granted","unsafe.jvm","missing grant: unsafe.jvm"]"#,
  );
  assert!(host.registered.borrow().is_empty());
}

/// only the handler the event names runs, and an event this delegate never named is not its
/// business even if the host asks
#[test]
fn only_the_named_handler_runs() {
  let (rt, ctx, host, state, logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  let token = host.registered.borrow()[0].0;
  state.dispatch(&rt, &ctx, token, "messagesDeleted", 0, &[]);
  assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]");
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn the_names_the_plugin_asked_for_are_what_the_host_is_told_to_observe() {
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  let registered = host.registered.borrow().clone();
  assert_eq!(registered.len(), 1);
  assert_eq!(registered[0].1, vec!["dialogsNeedReload".to_string(), "closeChats".to_string()]);
}

/// the host owns the observers, so a disposer that did not tell it would leave one on the app's
/// centre holding this engine for the life of the process
#[test]
fn disposing_unregisters_once_and_stops_the_dispatches() {
  let (rt, ctx, host, state, _logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  let token = host.registered.borrow()[0].0;
  ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
  assert_eq!(*host.unregistered.borrow(), vec![token], "a disposer called twice unregisters once");
  state.dispatch(&rt, &ctx, token, "closeChats", 0, &["I7".to_string()]);
  assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]");
}

/// same rule as every other registry here: a registration mid-dispatch lands next dispatch, and
/// unkeyed ones stack rather than replace
#[test]
fn delegates_stack_and_each_gets_its_own_token() {
  let (rt, ctx, host, state, _logs, _jvm) = setup(GRANTED);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
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
    state.dispatch(&rt, &ctx, token, "closeChats", 0, &[]);
  }
  assert_eq!(eval_json(&ctx, "globalThis.__ran"), r#"["first","second"]"#);
}

#[test]
fn a_throwing_handler_is_a_fault() {
  let (rt, ctx, host, state, logs, _jvm) = setup(GRANTED);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("inu.android.addNotificationCenterDelegate({ closeChats: () => { throw new Error('bus-boom') } })")
      .unwrap()
  });
  let token = host.registered.borrow()[0].0;
  state.dispatch(&rt, &ctx, token, "closeChats", 0, &[]);
  let entry = logs.borrow().iter().find(|l| l.contains("bus-boom")).cloned();
  let entry = entry.expect("expected a diagnostic for the throwing handler");
  assert_eq!(
    crate::classify_log(&entry).0,
    crate::LEVEL_FAULT,
    "a throwing notification handler must disable the plugin",
  );
}

/// Malformed host wires are host errors. Do not call or fault the plugin handler.
#[test]
fn a_payload_that_is_not_a_wire_is_an_ordinary_error() {
  let (rt, ctx, host, state, logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  let token = host.registered.borrow()[0].0;
  state.dispatch(&rt, &ctx, token, "closeChats", 0, &["I7".to_string(), "?nonsense".to_string()]);
  assert_eq!(eval_json(&ctx, "globalThis.__seen"), "[]", "one bad wire drops the whole post");
  let entry = logs.borrow().first().cloned().expect("expected a diagnostic");
  assert_eq!(crate::classify_log(&entry).0, crate::LEVEL_ERROR);
}

/// the vocabulary is the app's, so only the host can refuse a name - and a refused registration
/// leaves nothing behind on either side
#[test]
fn a_host_refusal_is_what_the_plugin_is_thrown() {
  let (_rt, ctx, host, state, _logs, _jvm) = setup(GRANTED);
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
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(GRANTED);
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
  let (rt, ctx, host, state, _logs, _jvm) = setup(GRANTED);
  state.lifecycle.begin_unload();
  let shape: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"globalThis.__ran = 0;
               typeof inu.android.addNotificationCenterDelegate({ closeChats: () => { __ran++ } });"#,
      )
      .unwrap()
  });
  assert_eq!(shape, "function");
  assert!(host.registered.borrow().is_empty());
  state.dispatch(&rt, &ctx, 1, "closeChats", 0, &[]);
  assert_eq!(eval_json(&ctx, "globalThis.__ran"), "0");
}

/// the observers are the host's, and an engine going away is the one teardown no disposer runs
#[test]
fn dispose_releases_the_callbacks_and_tells_the_host_to_stop_observing() {
  let (_rt, ctx, host, state, _logs, _jvm) = setup(GRANTED);
  arm(&ctx);
  state.dispose(&ctx);
  assert!(state.delegates.is_empty());
  // the host is told through its own detach rather than one upcall per token: an engine being
  // torn down cannot answer another one. What this pins is that nothing is left in the engine
  assert!(host.unregistered.borrow().is_empty());
  // rt/ctx drop after this without aborting == the roots were released
}

const ORACLE: &str = include_str!("../../../../test/plugins/notifications-test.js");

/// the bundled oracle is the only test this surface gets on a device, and every assertion in it
/// is a function of what it was handed - so the posts it wants are synthesised here
#[test]
fn the_bundled_notifications_test_plugin_passes() {
  let (rt, ctx, host, state, logs, _jvm) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  ctx.with(|ctx| match ctx.eval::<(), _>(ORACLE) {
    Ok(()) => {}
    Err(rquickjs::Error::Exception) => panic!("{}", format_exception(&ctx)),
    Err(e) => panic!("{e:?}"),
  });
  let token = host.registered.borrow().last().expect("the oracle registered nothing").0;
  state.dispatch(&rt, &ctx, token, "dialogsNeedReload", 0, &["B1".to_string()]);
  state.dispatch(&rt, &ctx, token, "updateInterfaces", -1, &["I512".to_string(), "N".to_string()]);
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "notifications test done", 12);
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

/// a hold, not a switch: the host is told once on each side and the token is the plugin's own
#[test]
fn suppress_holds_until_its_disposer_runs() {
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(&["notifications.suppress"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("globalThis.a = inu.notifications.suppress(); globalThis.b = inu.notifications.suppress();")
      .unwrap()
  });
  assert_eq!(host.suppressed.borrow().len(), 2, "each hold is its own");
  assert!(host.suppressed.borrow().iter().all(|(_, account, on)| *on && *account == ANY_ACCOUNT));

  ctx.with(|ctx| ctx.eval::<(), _>("a(); a(); b();").unwrap());
  let released: Vec<bool> = host.suppressed.borrow().iter().skip(2).map(|(_, _, on)| *on).collect();
  assert_eq!(released, vec![false, false], "disposing twice releases once");
}

#[test]
fn suppress_needs_its_own_grant() {
  let (_rt, ctx, host, _state, _logs, _jvm) = setup(&["unsafe.notificationCenter", "unsafe.jvm"]);
  assert_eq!(
    catch_json(&ctx, "inu.notifications.suppress()"),
    r#"[true,"not-granted","notifications.suppress","missing grant: notifications.suppress"]"#,
  );
  assert!(host.suppressed.borrow().is_empty(), "a refused hold must not reach the host");
}

/// the same hold, named for one account instead of all of them
#[test]
fn an_account_suppresses_only_its_own_notifications() {
  let (_rt, ctx, host, state, _logs, _jvm) = setup(&["notifications.suppress"]);
  let accounts = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let account_host =
      crate::api::telegram::account::tests::TestAccountHost::with(crate::api::telegram::account::tests::TWO_ACCOUNTS);
    let account = crate::api::telegram::account::install_account(
      &ctx,
      account_host,
      TestGrantHost::new(&["notifications.suppress"]).as_host(),
      Lifecycle::new(),
      crate::testing::harness::log_sink(&crate::testing::harness::Logs::new()),
      &inu,
    )
    .unwrap();
    state.install_account_suppress(&ctx, &account).unwrap();
    account
  });
  // the prototype it holds is a root, so it has to go before the runtime is freed
  let accounts = crate::testing::harness::DisposeOnDrop::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
  ctx.with(|ctx| ctx.eval::<(), _>("globalThis.h = inu.account(1).suppressNotifications()").unwrap());
  assert_eq!(host.suppressed.borrow().as_slice(), [(1, 1, true)], "the hold names the account it was taken on");

  ctx.with(|ctx| ctx.eval::<(), _>("h()").unwrap());
  assert_eq!(host.suppressed.borrow()[1], (1, 1, false), "and releases the same one");
  drop(accounts);
}
