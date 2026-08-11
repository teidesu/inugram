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

  state.app_visibility_changed(&rt, &ctx, false);
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

  state.app_visibility_changed(&rt, &ctx, true);
  state.app_visibility_changed(&rt, &ctx, false);
  state.app_visibility_changed(&rt, &ctx, false);
  state.app_visibility_changed(&rt, &ctx, true);
  let modes: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__modes)").unwrap());
  assert_eq!(modes, r#"["background","foreground"]"#);
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
  state.app_visibility_changed(&rt, &ctx, false);
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

  state.app_visibility_changed(&rt, &ctx, false);
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
  state.app_visibility_changed(&rt, &ctx, true);
  let ran: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__ran)").unwrap());
  assert_eq!(ran, r#"["second"]"#, "a disposed registration hears nothing more");
}
