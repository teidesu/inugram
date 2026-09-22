use crate::api::lifecycle::AppMode;
use crate::testing::harness::setup_apis as setup;

#[test]
fn unload_callbacks_run_in_order_and_survive_throws() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__ran = [];
            inu.onUnload(() => { globalThis.__ran.push(1); });
            inu.onUnload(() => { throw new Error('bye-boom'); });
            inu.onUnload(() => { globalThis.__ran.push(3); });
            "#,
      )
      .unwrap();
  });

  state.notify_unload(&rt, &ctx);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, "[1,3]");
  assert!(
    logs.borrow().iter().any(|l| l.contains("onUnload callback threw") && l.contains("bye-boom")),
    "expected a logged diagnostic, got: {:?}",
    logs.borrow(),
  );
}

#[test]
fn unload_registrations_stack_and_a_disposer_drops_one() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__ran = [];
            inu.onUnload(() => { __ran.push(1); });
            globalThis.__d = inu.onUnload(() => { __ran.push(2); });
            inu.onUnload(() => { __ran.push(3); });
            __d();
            __d();
            "#,
      )
      .unwrap();
  });
  assert_eq!(state.unload_fns.len(), 2);

  state.notify_unload(&rt, &ctx);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, "[1,3]");
}

#[test]
fn an_unload_callback_disposed_mid_notify_still_runs_and_a_new_one_never_does() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__ran = [];
            inu.onUnload(() => {
                __ran.push('first');
                globalThis.__d2();
                globalThis.__late = typeof inu.onUnload(() => { __ran.push('late'); });
            });
            globalThis.__d2 = inu.onUnload(() => { __ran.push('second'); });
            "#,
      )
      .unwrap();
  });

  state.notify_unload(&rt, &ctx);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, r#"["first","second"]"#);
  let late: String = ctx.with(|ctx| ctx.eval("globalThis.__late").unwrap());
  assert_eq!(late, "function", "registering after unload began returns a no-op disposer");
  assert!(state.unload_fns.is_empty(), "and roots nothing");
}

#[test]
fn a_throwing_lifecycle_callback_faults() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.onAppVisibilityChange(() => { throw new Error('visibility-boom'); });
            inu.onUnload(() => { throw new Error('unload-boom'); });
            "#,
      )
      .unwrap();
  });

  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  state.notify_unload(&rt, &ctx);

  let seen: Vec<(i32, String)> = logs
    .borrow()
    .iter()
    .map(|line| {
      let (level, message) = crate::classify_log(line);
      (level, message.to_string())
    })
    .collect();
  for want in ["visibility-boom", "unload-boom"] {
    let Some((level, message)) = seen.iter().find(|(_, message)| message.contains(want)) else {
      panic!("no diagnostic for '{want}', got: {seen:?}");
    };
    assert_eq!(*level, crate::LEVEL_FAULT, "'{message}' must disable the plugin");
  }
}

#[test]
fn visibility_callbacks_fire_on_transitions_only() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__modes = [];
            inu.onAppVisibilityChange(mode => { __modes.push(mode); });
            "#,
      )
      .unwrap();
  });

  state.app_visibility_changed(&rt, &ctx, AppMode::Foreground);
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  state.app_visibility_changed(&rt, &ctx, AppMode::Foreground);
  let modes: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__modes)").unwrap());
  assert_eq!(modes, r#"["background","foreground"]"#);
}

/// the finer pair rides the same callback and dedups the same way; a pause and a resume around a
/// stretch of being backgrounded arrive in the order the activities did them
#[test]
fn the_finer_pair_is_delivered_alongside_the_coarse_one() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__modes = [];
            inu.onAppVisibilityChange(mode => { __modes.push(mode); });
            "#,
      )
      .unwrap();
  });

  for mode in
    [AppMode::Paused, AppMode::Paused, AppMode::Background, AppMode::Foreground, AppMode::Resumed, AppMode::Resumed]
  {
    state.app_visibility_changed(&rt, &ctx, mode);
  }
  let modes: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__modes)").unwrap());
  assert_eq!(modes, r#"["paused","background","foreground","resumed"]"#);
}

#[test]
fn visibility_without_the_grant_throws_and_registers_nothing() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  let caught: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            let out = 'no-throw';
            try {
                inu.onAppVisibilityChange(() => {});
            } catch (e) {
                out = JSON.stringify([e instanceof inu.PluginError, e.code, e.grant]);
            }
            out;
            "#,
      )
      .unwrap()
  });
  assert_eq!(caught, r#"[true,"not-granted","onAppVisibilityChange"]"#);
  assert!(state.visibility_fns.is_empty());
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
}

#[test]
fn a_throwing_visibility_callback_is_logged_and_the_rest_still_run() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__ran = [];
            inu.onAppVisibilityChange(() => { throw new Error('vis-boom'); });
            globalThis.__d = inu.onAppVisibilityChange(() => { __ran.push('second'); });
            "#,
      )
      .unwrap();
  });

  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, r#"["second"]"#);
  assert!(
    logs
      .borrow()
      .iter()
      .any(|l| l.contains("onAppVisibilityChange callback threw") && l.contains("vis-boom")),
    "got: {:?}",
    logs.borrow(),
  );

  ctx.with(|ctx| ctx.eval::<(), _>("__d(); __d();").unwrap());
  state.app_visibility_changed(&rt, &ctx, AppMode::Foreground);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, r#"["second"]"#, "a disposed registration hears nothing more");
}

#[test]
fn unload_waits_for_returned_promises_without_holding_the_engine() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("inu.onUnload(() => new Promise(resolve => globalThis.finishUnload = resolve));")
      .unwrap()
  });
  state.notify_unload(&rt, &ctx);
  assert!(!state.poll_unload(&rt, &ctx));
  ctx.with(|ctx| ctx.eval::<(), _>("finishUnload()").unwrap());
  assert!(state.poll_unload(&rt, &ctx));
  assert!(!state.lifecycle.is_cleaning_up());
}

#[test]
fn unload_rejections_are_reported_and_do_not_skip_other_handlers() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  ctx.with(|ctx| ctx.eval::<(), _>("globalThis.ran = false; inu.onUnload(async () => { throw Error('async cleanup failed') }); inu.onUnload(async () => { ran = true });").unwrap());
  state.notify_unload(&rt, &ctx);
  assert!(state.poll_unload(&rt, &ctx));
  let ran: bool = ctx.with(|ctx| ctx.eval("ran").unwrap());
  assert!(ran);
  assert!(logs
    .borrow()
    .iter()
    .any(|message| message.contains("onUnload promise rejected") && message.contains("async cleanup failed")));
}

#[test]
fn unload_notification_is_idempotent_and_timeout_ends_cleanup() {
  let (rt, ctx, _host, state, _dialogs, logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("globalThis.count = 0; inu.onUnload(() => { count++; return new Promise(() => {}) });")
      .unwrap()
  });
  state.notify_unload(&rt, &ctx);
  state.notify_unload(&rt, &ctx);
  assert!(!state.poll_unload(&rt, &ctx));
  std::thread::sleep(std::time::Duration::from_millis(2010));
  assert!(state.poll_unload(&rt, &ctx));
  assert!(state.poll_unload(&rt, &ctx));
  let count: i32 = ctx.with(|ctx| ctx.eval("count").unwrap());
  assert_eq!(count, 1);
  assert_eq!(logs.borrow().iter().filter(|message| message.contains("cleanup timed out")).count(), 1);
}

#[test]
fn visibility_events_do_not_reenter_a_plugin_during_cleanup() {
  let (rt, ctx, _host, state, _dialogs, _logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        "globalThis.calls = 0; inu.onAppVisibilityChange(() => calls++); inu.onUnload(() => new Promise(() => {}));",
      )
      .unwrap()
  });
  state.notify_unload(&rt, &ctx);
  state.app_visibility_changed(&rt, &ctx, AppMode::Background);
  let calls: i32 = ctx.with(|ctx| ctx.eval("calls").unwrap());
  assert_eq!(calls, 0);
}
