use super::*;
use crate::testing::harness::setup_apis as setup;
use std::rc::Rc;

struct BulletinIconHost;

impl crate::api::ui::icons::IconHost for BulletinIconHost {
  fn icon_resolves(&self, _kind: i32, _value: &str) -> bool {
    true
  }

  fn common_icon(&self, name: &str) -> Option<String> {
    (name == "info").then(|| "msg_info".to_string())
  }
}

#[test]
fn toast_reaches_host_coerced_to_string() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.ui.toast('hello'); inu.ui.toast(42);").unwrap();
  });
  assert_eq!(*host.toasts.borrow(), vec!["hello".to_string(), "42".to_string()]);
}

fn icons(ctx: &rquickjs::Ctx<'_>) {
  crate::api::ui::icons::install_icons(
    ctx,
    Rc::new(BulletinIconHost),
    None,
    &crate::testing::harness::get_api_globals(ctx),
  )
  .unwrap();
}

fn bulletin_options(host: &crate::testing::harness::RecordingHost) -> Vec<String> {
  host.bulletins.borrow().iter().map(|(_, json)| json.clone()).collect()
}

#[test]
fn bulletin_reaches_host_with_ui_and_native_animation_icons() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    icons(&ctx);
    ctx
      .eval::<(), _>(
        "inu.ui.bulletin({ text: 'static', icon: inu.icons.common('info') }); \
         inu.ui.bulletin({ text: 'animated', icon: inu.icons.animation('success') });",
      )
      .unwrap();
  });
  assert_eq!(
    bulletin_options(&host),
    vec![r#"{"text":"static","icon":"rmsg_info"}"#.to_string(), r#"{"text":"animated","icon":"a0done"}"#.to_string()],
  );
}

/// the whole of what a bulletin can be told, in the order it is written out
#[test]
fn bulletin_carries_its_subtitle_avatars_duration_position_and_button() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    icons(&ctx);
    ctx
      .eval::<(), _>(
        "inu.ui.bulletin({ text: 'title', subtitle: 'under it', \
         icon: { type: 'avatars', avatars: [42, -1001], account: 2 }, \
         duration: 'short', position: 'top', button: 'Open' });",
      )
      .unwrap();
  });
  assert_eq!(
    bulletin_options(&host),
    vec![
      r#"{"text":"title","subtitle":"under it","avatars":[42,-1001],"account":2,"duration":1500,"top":true,"button":"Open"}"#
        .to_string(),
    ],
  );
}

#[test]
fn a_bulletin_needs_an_icon_and_checks_what_it_is_given() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    icons(&ctx);
    ctx.eval::<(), _>("globalThis.AVATARS = { type: 'avatars', avatars: [1] }").unwrap();
    for bad in [
      "{ text: 'x' }",
      "{ text: 'x', icon: { type: 'avatars' } }",
      "{ text: 'x', icon: { type: 'avatars', avatars: [] } }",
      "{ text: 'x', icon: { type: 'avatars', avatars: [1, 2, 3, 4] } }",
      "{ text: 'x', icon: { type: 'avatars', avatars: ['me'] } }",
      "{ text: 'x', icon: { type: 'peer', avatars: [1] } }",
      "{ text: 'x', icon: AVATARS, duration: 'forever' }",
      "{ text: 'x', icon: AVATARS, duration: 1 }",
      "{ text: 'x', icon: AVATARS, duration: 999999 }",
      "{ text: 'x', icon: AVATARS, position: 'middle' }",
      "{ text: 'x', icon: AVATARS, button: {} }",
    ] {
      let caught = ctx
        .eval::<String, _>(format!(
          "(() => {{ try {{ inu.ui.bulletin({bad}); return 'no-throw' }} catch (e) {{ return e.name }} }})()"
        ))
        .unwrap();
      assert_ne!(caught, "no-throw", "{bad}");
    }
  });
  assert!(bulletin_options(&host).is_empty(), "nothing refused may reach the host");
}

#[test]
fn bulletin_carries_entities_beside_the_text() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    icons(&ctx);
    ctx
      .eval::<(), _>(
        "inu.ui.bulletin({ text: { text: 'hi', entities: [{ _: 'messageEntityBold', offset: 0, length: 2 }] }, \
         icon: inu.icons.common('info') });",
      )
      .unwrap();
  });
  assert_eq!(
    bulletin_options(&host),
    vec![r#"{"text":"hi","textEntities":[{"_":"messageEntityBold","offset":0,"length":2}],"icon":"rmsg_info"}"#
      .to_string(),],
  );
}

#[test]
fn dialog_input_text_crosses_as_text_plus_entities() {
  let (_rt, ctx, host, _lifecycle, _state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        "inu.ui.dialog({ title: { text: 'T', entities: [{ _: 'messageEntityBold', offset: 0, length: 1 }] }, \
         message: 'plain', positive: 'OK' });",
      )
      .unwrap();
  });
  let dialogs = host.dialogs.borrow();
  assert_eq!(
    dialogs[0].1,
    r#"{"title":"T","titleEntities":[{"_":"messageEntityBold","offset":0,"length":1}],"message":"plain","positive":"OK"}"#,
  );
}

#[test]
fn bulletin_requires_text_and_an_icon() {
  let (_rt, ctx, _host, _lifecycle, _state, _logs) = setup(&[]);
  for source in ["inu.ui.bulletin({ icon: {} })", "inu.ui.bulletin({ text: 'x' })"] {
    assert!(ctx.with(|ctx| ctx.eval::<(), _>(source).is_err()), "accepted {source}");
  }
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

  state.settle(&rt, &ctx, request_id, "Spositive");
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
  assert!(state.pending.is_empty(), "and nothing is left pending");
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
  assert!(state.pending.is_empty());
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

  state.settle(&rt, &ctx, ids[0], "J[2]");
  state.settle(&rt, &ctx, ids[1], "J[0,2]");
  state.settle(&rt, &ctx, ids[2], "N");
  state.settle(&rt, &ctx, ids[3], "J[]");

  let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
  assert_eq!(results, r#"[["single",2],["multi",[0,2]],["dismissed",null],["none",[]]]"#);
  assert!(state.pending.is_empty());

  // a second settle for the same request finds nothing and must not throw
  state.settle(&rt, &ctx, ids[0], "J[1]");
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
  assert!(state.pending.is_empty());
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
  assert_eq!(state.pending.len(), 2);
  state.dispose(&ctx);
  // rt/ctx drop after this without aborting == roots were released
}

#[test]
fn prompt_resolves_with_text_and_null() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__results = [];
            inu.ui.prompt({ title: 'Name?', hint: 'h', value: 'v', selectAll: true })
                .then(r => { globalThis.__results.push(r); });
            inu.ui.prompt({ title: 'Again?' }).then(r => { globalThis.__results.push(r); });
            "#,
      )
      .unwrap();
  });
  let prompts = host.prompts.borrow().clone();
  assert_eq!(prompts.len(), 2);
  assert_eq!(prompts[0].1, r#"{"title":"Name?","hint":"h","value":"v","selectAll":true}"#);

  state.settle(&rt, &ctx, prompts[0].0, "Salice");
  state.settle(&rt, &ctx, prompts[1].0, "N");
  let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
  assert_eq!(results, r#"["alice",null]"#);
  assert!(state.pending.is_empty());
}

/// All modal types share one request table and wire format. Host errors must reject the correct
/// promise, and malformed responses must reject instead of leaving it pending.
#[test]
fn a_modal_answer_that_is_an_error_or_unreadable_rejects() {
  let (rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__results = [];
            const push = tag => [r => globalThis.__results.push([tag, 'ok', r]), e => globalThis.__results.push([tag, e.code ?? e.name])];
            inu.ui.dialog({}).then(...push('dialog'));
            inu.ui.prompt({ title: 't' }).then(...push('prompt'));
            inu.ui.chooser({ items: ['a'] }).then(...push('chooser'));
            "#,
      )
      .unwrap();
  });
  let (dialog, prompt, chooser) = (host.dialogs.borrow()[0].0, host.prompts.borrow()[0].0, host.choosers.borrow()[0].0);
  state.settle(&rt, &ctx, dialog, "Punsupported\n\n\n\nno screen");
  state.settle(&rt, &ctx, prompt, "Zgarbage");
  state.settle(&rt, &ctx, chooser, "J[not json");
  let results: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__results)").unwrap());
  assert_eq!(results, r#"[["dialog","unsupported"],["prompt","Error"],["chooser","SyntaxError"]]"#);
  assert!(state.pending.is_empty());
}

#[test]
fn dispose_with_every_modal_open_releases_roots() {
  let (_rt, ctx, host, _lifecycle, state, _logs) = setup(&[]);
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>("inu.ui.dialog({}); inu.ui.prompt({ title: 'stuck' }); inu.ui.chooser({ items: ['a'] });")
      .unwrap();
  });
  assert_eq!((host.dialogs.borrow().len(), host.prompts.borrow().len(), host.choosers.borrow().len()), (1, 1, 1));
  assert_eq!(state.pending.len(), 3);
  state.dispose(&ctx);
  assert!(state.pending.is_empty());
}
