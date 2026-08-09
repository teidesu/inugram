use super::*;
use crate::testing::harness::setup_apis as setup;

#[test]
fn toast_reaches_host_coerced_to_string() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.ui.toast('hello'); inu.ui.toast(42);").unwrap();
  });
  assert_eq!(*host.toasts.borrow(), vec!["hello".to_string(), "42".to_string()]);
}

#[test]
fn dialog_resolves_with_user_action() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__result = null;
            inu.ui.dialog({ title: 'T', positive: 'OK' }).then(r => { globalThis.__result = r; });
            "#,
      )
      .unwrap();
  });
  let dialogs = host.dialogs.borrow();
  assert_eq!(dialogs.len(), 1);
  assert_eq!(dialogs[0].1, r#"{"title":"T","positive":"OK"}"#);
  let request_id = dialogs[0].0;
  drop(dialogs);

  resolve_dialog(&rt, &ctx, &state, request_id, "positive");
  let result: String = ctx.with(|ctx| ctx.eval("globalThis.__result").unwrap());
  assert_eq!(result, "positive");
}

/// the host reads title/message/buttons and nothing else, and `JSON.stringify` drops the
/// callbacks a `UIElement` hangs off itself, so a body has to be refused rather than dropped
#[test]
fn dialog_body_is_refused_rather_than_silently_dropped() {
  let (_rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  let code: String = ctx.with(|ctx| {
    ctx
      .eval(
        r#"(() => {
                   try {
                       inu.ui.dialog({ title: 'T', body: { __inuUi: 'button' } });
                       return 'did not throw';
                   } catch (e) {
                       return `${e instanceof inu.PluginError}:${e.code}`;
                   }
               })()"#,
      )
      .unwrap()
  });
  assert_eq!(code, "true:unsupported");
  assert!(host.dialogs.borrow().is_empty(), "nothing is shown for a refused dialog");
  assert!(state.pending_dialogs.borrow().is_empty(), "and nothing is left pending");
}

#[test]
fn dialog_host_error_rejects() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  *host.fail_dialog.borrow_mut() = Some("no ui".to_string());
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__err = null;
            inu.ui.dialog({}).catch(e => { globalThis.__err = e.message; });
            "#,
      )
      .unwrap();
  });
  pump_jobs(&rt, &ctx, &|_| {});
  let err: String = ctx.with(|ctx| ctx.eval("globalThis.__err").unwrap());
  assert_eq!(err, "no ui");
  assert!(state.pending_dialogs.borrow().is_empty());
}

#[test]
fn dialog_non_object_options_throws() {
  let (_rt, ctx, _host, _lifecycle, _state, _logs) = setup(&[]);
  let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.ui.dialog('nope')").is_err());
  assert!(threw);
}

#[test]
fn chooser_serializes_one_shape_for_both_modes() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.ui.chooser({ title: 'Pick', items: ['a', { text: 'b', subtitle: 'bee' }, { text: 'c', danger: true }], selected: 2 });
            inu.ui.chooser({ items: ['a', 'b'], selected: [1, 0], multiple: true });
            "#,
      )
      .unwrap();
  });
  let choosers = host.choosers.borrow();
  assert_eq!(choosers.len(), 2);
  assert_eq!(
    choosers[0].1,
    r#"{"title":"Pick","multiple":false,"items":[{"text":"a","danger":false},{"text":"b","subtitle":"bee","danger":false},{"text":"c","danger":true}],"selected":[2]}"#,
  );
  assert_eq!(
    choosers[1].1,
    r#"{"multiple":true,"items":[{"text":"a","danger":false},{"text":"b","danger":false}],"selected":[1,0]}"#,
  );
}

#[test]
fn chooser_resolves_an_index_a_list_or_null_by_mode() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__results = [];
            const push = tag => r => { globalThis.__results.push([tag, r]); };
            inu.ui.chooser({ items: ['a', 'b', 'c'] }).then(push('single'));
            inu.ui.chooser({ items: ['a', 'b', 'c'], multiple: true }).then(push('multi'));
            inu.ui.chooser({ items: ['a'] }).then(push('dismissed'));
            inu.ui.chooser({ items: ['a', 'b'], multiple: true }).then(push('none'));
            "#,
      )
      .unwrap();
  });
  let ids: Vec<i64> = host.choosers.borrow().iter().map(|(id, _)| *id).collect();
  assert_eq!(ids.len(), 4);

  resolve_chooser(&rt, &ctx, &state, ids[0], Some("2"));
  resolve_chooser(&rt, &ctx, &state, ids[1], Some("0,2"));
  resolve_chooser(&rt, &ctx, &state, ids[2], None);
  resolve_chooser(&rt, &ctx, &state, ids[3], Some(""));

  let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
  assert_eq!(results, r#"[["single",2],["multi",[0,2]],["dismissed",null],["none",[]]]"#);
  assert!(state.pending_choosers.borrow().is_empty());

  // a second settle for the same request finds nothing and must not throw
  resolve_chooser(&rt, &ctx, &state, ids[0], Some("1"));
  let unchanged: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results.length)").unwrap());
  assert_eq!(unchanged, "4");
}

#[test]
fn chooser_validates_its_options_eagerly() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  let errors: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const out = [];
            const tryIt = f => { try { f(); out.push('ok'); } catch (e) { out.push(e.message); } };
            tryIt(() => inu.ui.chooser({ items: [] }));
            tryIt(() => inu.ui.chooser({ items: ['a'], selected: 1 }));
            tryIt(() => inu.ui.chooser({ items: ['a'], selected: -1 }));
            tryIt(() => inu.ui.chooser({ items: ['a', 'b'], selected: [0], multiple: false }));
            tryIt(() => inu.ui.chooser({ items: ['a', 'b'], selected: 0, multiple: true }));
            tryIt(() => inu.ui.chooser({ items: [42] }));
            tryIt(() => inu.ui.chooser({ items: [{ subtitle: 'no text' }] }));
            tryIt(() => inu.ui.chooser({ items: 'a' }));
            JSON.stringify(out);
            "#,
      )
      .unwrap()
  });
  assert_eq!(
    errors,
    r#"["chooser: 'items' must not be empty","chooser: 'selected' out of range","chooser: 'selected' out of range","chooser: 'selected' must be a single index unless 'multiple' is set","chooser: 'selected' must be an array of indices when 'multiple' is set","chooser: items must be strings or { text, subtitle?, danger? } objects","chooser item: 'text' must be a string","chooser: 'items' must be an array"]"#,
  );
  assert!(host.choosers.borrow().is_empty(), "nothing invalid may reach the host");
}

#[test]
fn chooser_host_error_rejects() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  *host.fail_chooser.borrow_mut() = Some("no ui".to_string());
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__err = null;
            inu.ui.chooser({ items: ['a'] }).catch(e => { globalThis.__err = e.message; });
            "#,
      )
      .unwrap();
  });
  pump_jobs(&rt, &ctx, &|_| {});
  let err: String = ctx.with(|ctx| ctx.eval("globalThis.__err").unwrap());
  assert_eq!(err, "no ui");
  assert!(state.pending_choosers.borrow().is_empty());
}

#[test]
fn dispose_releases_pending_dialog_and_unload_roots() {
  let (_rt, ctx, _host, _lifecycle, state, _logs) = setup(&["onAppVisibilityChange"]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.onUnload(() => {});
            inu.onAppVisibilityChange(() => {});
            inu.ui.dialog({ title: 'stuck' });
            inu.ui.chooser({ items: ['stuck'] });
            "#,
      )
      .unwrap();
  });
  assert_eq!(state.pending_dialogs.borrow().len(), 1);
  assert_eq!(state.pending_choosers.borrow().len(), 1);
  dispose(&ctx, &state);
  // rt/ctx drop after this without aborting == roots were released
}
