use super::*;
use crate::api::telegram::account::tests::TestAccountHost;
use crate::sandbox::grants::CachedGrantHost;
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
type Fixture = (
  Runtime,
  Context,
  Rc<TestScreenHost>,
  Disposing,
  AccountDisposing,
  std::sync::Arc<crate::testing::harness::Logs>,
);

fn setup(grants: &[&str]) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestScreenHost::default());
  *host.screen.borrow_mut() = "N".to_string();
  let host_dyn: Rc<dyn ScreenHost> = host.clone();
  let grants = CachedGrantHost::new(grants);
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let lifecycle = Lifecycle::new();
  let (state, accounts) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let accounts = crate::api::telegram::account::install_account(
      &ctx,
      TestAccountHost::with(TWO_ACCOUNTS),
      grants.clone(),
      lifecycle.clone(),
      log.clone(),
      &inu,
    )
    .unwrap();
    let state = install_screens(&ctx, host_dyn, grants, Some(accounts.clone()), lifecycle, log.clone(), &inu).unwrap();
    (state, accounts)
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let accounts = AccountDisposing::new(&ctx, accounts, |ctx, state| state.dispose(ctx));
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

fn arm(ctx: &Context) {
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
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
fn popping_the_last_screen_hands_over_a_null_screen() {
  let (rt, ctx, _host, state, _accounts, logs) = setup(&["account.read(dialogs)"]);
  arm(&ctx);
  state.dispatch_change(&rt, &ctx, r#"{"action":"pop","screen":null,"previous":{"type":"dialogs","account":0}}"#, "[]");
  assert_eq!(eval_json(&ctx, "globalThis.__seen"), r#"[["pop",null,"dialogs"]]"#);
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn a_plugin_that_never_touches_the_stack_never_materializes_it() {
  let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
  arm(&ctx);
  // a stack json nothing could parse: reaching it at all is the failure this pins
  state.dispatch_change(
    &rt,
    &ctx,
    &format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#),
    "not json at all",
  );
  assert_eq!(eval_json(&ctx, "globalThis.__seen"), r#"[["push","dialogs",null]]"#);
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
  let threw: String = ctx.with(|ctx| {
    ctx
      .eval("(() => { try { __last.stack; return 'no-throw'; } catch (e) { return 'threw'; } })()")
      .unwrap()
  });
  assert_eq!(threw, "threw", "and touching it is where the cost, and the failure, lands");
}

#[test]
fn nothing_is_dispatched_and_nothing_parsed_without_a_registration() {
  let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
  state.dispatch_change(&rt, &ctx, "not json at all", "not json either");
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn registrations_stack_dispose_once_and_a_throw_faults() {
  let (rt, ctx, _host, state, _accounts, logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
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
  state.dispatch_change(&rt, &ctx, &change, "[]");
  assert_eq!(eval_json(&ctx, "globalThis.__ran"), r#"["second","third"]"#);
  let entry = logs.borrow().iter().find(|l| l.contains("nav-boom")).cloned();
  let entry = entry.expect("expected a diagnostic for the throwing callback");
  assert_eq!(
    crate::classify_log(&entry).0,
    crate::LEVEL_FAULT,
    "a throwing navigation callback must disable the plugin",
  );

  ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
  state.dispatch_change(&rt, &ctx, &change, "[]");
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
    ctx
      .eval::<String, _>(
        r#"
          globalThis.__ran = 0;
          typeof inu.ui.onScreenChanged(() => { globalThis.__ran++; });
        "#,
      )
      .unwrap()
  });
  assert_eq!(shape, "function");
  assert!(state.changed_fns.is_empty());
  state.dispatch_change(&rt, &ctx, &format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#), "[]");
  assert_eq!(eval_json(&ctx, "globalThis.__ran"), "0");
}

const ORACLE: &str = crate::testing::test_plugin!("nav-test.js");

/// the navigations the oracle waits for are synthesised here; the count is exact, since a vanished
/// member reads as a refusal in a suite of `expectThrow`s
#[test]
fn the_bundled_nav_test_plugin_passes() {
  let (rt, ctx, host, state, _accounts, logs) = setup(&crate::testing::harness::manifest_grants(ORACLE));
  *host.screen.borrow_mut() = format!("J{DIALOGS}");
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  crate::testing::harness::eval_unit(&ctx, ORACLE);

  const SETTINGS: &str = r#"{"type":"settings","account":0}"#;
  for (top, change, stack) in [
    (
      DIALOGS,
      format!(r#"{{"action":"push","screen":{DIALOGS},"previous":null}}"#),
      format!("[{DIALOGS}]"),
    ),
    (
      CHAT,
      format!(r#"{{"action":"push","screen":{CHAT},"previous":{DIALOGS}}}"#),
      format!("[{DIALOGS},{CHAT}]"),
    ),
    (
      DIALOGS,
      format!(r#"{{"action":"pop","screen":{DIALOGS},"previous":{CHAT}}}"#),
      format!("[{DIALOGS}]"),
    ),
    (
      SETTINGS,
      format!(r#"{{"action":"replace","screen":{SETTINGS},"previous":{DIALOGS}}}"#),
      format!("[{SETTINGS}]"),
    ),
  ] {
    *host.screen.borrow_mut() = format!("J{top}");
    state.dispatch_change(&rt, &ctx, &change, &stack);
  }

  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "nav test done", 38);
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}
