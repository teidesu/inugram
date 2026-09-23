use crate::api::lifecycle::AppMode;
use crate::testing::harness::setup_apis as setup;
use crate::testing::harness::{catch_json, eval_json, eval_unit as eval};

#[test]
fn unload_callbacks_run_in_order_and_survive_throws() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      inu.onUnload(() => { globalThis.__ran.push(1); });
      inu.onUnload(() => { throw new Error('bye-boom'); });
      inu.onUnload(() => { globalThis.__ran.push(3); });
    "#,
  );

  state.notify_unload(&rt, &ctx);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,3]");
  let entry = logs.borrow().iter().find(|l| l.contains("bye-boom")).cloned().expect("no diagnostic for the throw");
  assert_eq!(crate::classify_log(&entry).0, crate::LEVEL_FAULT, "a throwing onUnload must disable the plugin");
}

#[test]
fn unload_registrations_stack_and_a_disposer_drops_one() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      inu.onUnload(() => { __ran.push(1); });
      globalThis.__d = inu.onUnload(() => { __ran.push(2); });
      inu.onUnload(() => { __ran.push(3); });
      __d();
      __d();
    "#,
  );
  assert_eq!(state.unload_fns.len(), 2);

  state.notify_unload(&rt, &ctx);
  assert_eq!(eval_json(&ctx, "__ran"), "[1,3]");
}

#[test]
fn an_unload_callback_disposed_mid_notify_still_runs_and_a_new_one_never_does() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      inu.onUnload(() => {
        __ran.push('first');
        globalThis.__d2();
        globalThis.__late = typeof inu.onUnload(() => { __ran.push('late'); });
      });
      globalThis.__d2 = inu.onUnload(() => { __ran.push('second'); });
    "#,
  );

  state.notify_unload(&rt, &ctx);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["first","second"]"#);
  assert_eq!(
    eval_json(&ctx, "__late"),
    r#""function""#,
    "registering after unload began returns a no-op disposer"
  );
  assert!(state.unload_fns.is_empty(), "and roots nothing");
}

/// the finer pair rides the same callback and dedups the same way; a pause and a resume around a
/// stretch of being backgrounded arrive in the order the activities did them
#[test]
fn visibility_fires_on_transitions_only_for_both_pairs() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&["onAppVisibilityChange"]);
  eval(&ctx, "globalThis.__modes = []; inu.onAppVisibilityChange(mode => { __modes.push(mode); });");

  for mode in [
    AppMode::Foreground,
    AppMode::Paused,
    AppMode::Paused,
    AppMode::Background,
    AppMode::Background,
    AppMode::Foreground,
    AppMode::Resumed,
    AppMode::Resumed,
  ] {
    state.app_visibility_changed(&rt, &ctx, mode);
  }
  assert_eq!(eval_json(&ctx, "__modes"), r#"["paused","background","foreground","resumed"]"#);
}

#[test]
fn visibility_without_the_grant_throws_and_registers_nothing() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  assert_eq!(
    catch_json(&ctx, "inu.onAppVisibilityChange(() => {})"),
    r#"[true,"not-granted","onAppVisibilityChange","missing grant: onAppVisibilityChange"]"#
  );
  assert!(state.visibility_fns.is_empty());
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
}

#[test]
fn a_throwing_visibility_callback_is_logged_and_the_rest_still_run() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&["onAppVisibilityChange"]);
  eval(
    &ctx,
    r#"
      globalThis.__ran = [];
      inu.onAppVisibilityChange(() => { throw new Error('vis-boom'); });
      globalThis.__d = inu.onAppVisibilityChange(() => { __ran.push('second'); });
    "#,
  );

  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["second"]"#);
  let entry = logs.borrow().iter().find(|l| l.contains("vis-boom")).cloned().expect("no diagnostic for the throw");
  assert_eq!(
    crate::classify_log(&entry).0,
    crate::LEVEL_FAULT,
    "a throwing visibility callback must disable the plugin"
  );

  eval(&ctx, "__d(); __d();");
  state.app_visibility_changed(&rt, &ctx, AppMode::Foreground);
  assert_eq!(eval_json(&ctx, "__ran"), r#"["second"]"#, "a disposed registration hears nothing more");
}

#[test]
fn unload_waits_for_returned_promises_without_holding_the_engine() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  eval(&ctx, "inu.onUnload(() => new Promise(resolve => globalThis.finishUnload = resolve));");
  state.notify_unload(&rt, &ctx);
  assert!(!state.poll_unload(&rt, &ctx));
  eval(&ctx, "finishUnload()");
  assert!(state.poll_unload(&rt, &ctx));
  assert!(!state.lifecycle.is_cleaning_up());
}

#[test]
fn unload_rejections_are_reported_and_do_not_skip_other_handlers() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  eval(
    &ctx,
    "globalThis.ran = false; inu.onUnload(async () => { throw Error('async cleanup failed') }); inu.onUnload(async () => { ran = true });",
  );
  state.notify_unload(&rt, &ctx);
  assert!(state.poll_unload(&rt, &ctx));
  assert_eq!(eval_json(&ctx, "ran"), "true");
  assert!(logs
    .borrow()
    .iter()
    .any(|message| message.contains("onUnload promise rejected") && message.contains("async cleanup failed")));
}

#[test]
fn unload_notification_is_idempotent_and_timeout_ends_cleanup() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  eval(&ctx, "globalThis.count = 0; inu.onUnload(() => { count++; return new Promise(() => {}) });");
  state.notify_unload(&rt, &ctx);
  state.notify_unload(&rt, &ctx);
  assert!(!state.poll_unload(&rt, &ctx));
  std::thread::sleep(std::time::Duration::from_millis(2010));
  assert!(state.poll_unload(&rt, &ctx));
  assert!(state.poll_unload(&rt, &ctx));
  assert_eq!(eval_json(&ctx, "count"), "1");
  assert_eq!(logs.borrow().iter().filter(|message| message.contains("cleanup timed out")).count(), 1);
}

#[test]
fn visibility_events_do_not_reenter_a_plugin_during_cleanup() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&["onAppVisibilityChange"]);
  eval(
    &ctx,
    "globalThis.calls = 0; inu.onAppVisibilityChange(() => calls++); inu.onUnload(() => new Promise(() => {}));",
  );
  state.notify_unload(&rt, &ctx);
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  assert_eq!(eval_json(&ctx, "calls"), "0");
}
