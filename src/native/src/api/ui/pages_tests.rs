use super::*;
use rquickjs::Context;

#[derive(Default)]
struct TestUiHost {
  opened_pages: RefCell<Vec<i64>>,
  opened_fragments: RefCell<Vec<i64>>,
  opened_screens: RefCell<Vec<String>>,
  registered: RefCell<Vec<i64>>,
  unregistered: RefCell<Vec<i64>>,
  invalidated: RefCell<Vec<i64>>,
  menus: RefCell<Vec<Menu>>,
}

struct Menu {
  id: i64,
  page_id: i64,
  anchor: String,
  items_json: String,
}

impl UiHost for TestUiHost {
  fn ui_open_page(&self, page_id: i64) -> Option<String> {
    self.opened_pages.borrow_mut().push(page_id);
    None
  }
  fn ui_open_fragment(&self, handle: i64) -> Option<String> {
    self.opened_fragments.borrow_mut().push(handle);
    None
  }
  fn ui_open_screen(&self, options_json: &str) -> Option<String> {
    self.opened_screens.borrow_mut().push(options_json.to_string());
    None
  }
  fn ui_register_settings(&self, page_id: i64) {
    self.registered.borrow_mut().push(page_id);
  }
  fn ui_unregister_settings(&self, page_id: i64) {
    self.unregistered.borrow_mut().push(page_id);
  }
  fn ui_invalidate(&self, page_id: i64) {
    self.invalidated.borrow_mut().push(page_id);
  }
  fn ui_open_menu(&self, menu_id: i64, page_id: i64, anchor_key: &str, items_json: &str) -> Option<String> {
    self.menus.borrow_mut().push(Menu {
      id: menu_id,
      page_id,
      anchor: anchor_key.to_string(),
      items_json: items_json.to_string(),
    });
    None
  }
}

/// disposes on drop, so a failing assertion is one failed test rather than an abort in
/// `JS_FreeRuntime` that takes the whole suite's reporting with it
type Disposing = crate::testing::harness::DisposeOnDrop<UiState>;

type Fixture = (Runtime, Context, Rc<TestUiHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

fn setup() -> Fixture {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestUiHost::default());
  let host_dyn: Rc<dyn UiHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    install_ui(&ctx, host_dyn, Lifecycle::new(), log, None, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

/// registers a page exercising every element type; returns its page id
fn build_full_page(ctx: &Context, host: &Rc<TestUiHost>) -> i64 {
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__state = { on: false, sel: 0, speed: 1, log: [] };
            const s = globalThis.__state;
            const page = inu.ui.settingsPage({
                title: 'Test page',
                items: () => [
                    inu.ui.header('General'),
                    inu.ui.check({ text: 'Toggle', subtitle: 'sub', checked: s.on, onChange: v => { s.on = v; } }),
                    inu.ui.button({ text: 'Do it', value: 'now', danger: true, onClick: () => { s.log.push('click'); },
                        onSecondaryClick: () => { s.log.push('long'); } }),
                    inu.ui.select({ text: 'Mode', items: ['a', { text: 'b', subtitle: 'bee' }], selected: s.sel,
                        dialog: true, onChange: i => { s.sel = i; } }),
                    inu.ui.slider({ text: 'Speed', min: 0, max: 2, step: 1, value: s.speed, default: 1,
                        label: v => v + 'x', onChange: v => { s.speed = v; } }),
                    inu.ui.separator('the end'),
                ],
                bottomButton: { text: 'Save', onClick: () => { s.log.push('save'); } },
                onClose: () => { s.log.push('close'); },
            });
            globalThis.__disposeSettings = inu.registerSettings(page);
            globalThis.__page = page;
            "#,
      )
      .unwrap();
  });
  *host.registered.borrow().last().unwrap()
}

#[test]
fn full_page_render_serializes_every_element() {
  let (rt, ctx, host, state, logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  let json = state.render(&rt, &ctx, page_id).expect("render failed");
  assert_eq!(
    json,
    r#"{"title":"Test page","items":[{"type":"header","key":"t:header:General#1","text":"General"},{"type":"check","key":"t:check:Toggle#1","text":"Toggle","subtitle":"sub","checked":false,"onChange":1},{"type":"button","key":"t:button:Do it#1","text":"Do it","value":"now","danger":true,"onClick":2,"onSecondaryClick":3},{"type":"select","key":"t:select:Mode#1","text":"Mode","items":[{"text":"a"},{"text":"b","subtitle":"bee"}],"selected":0,"dialog":true,"onChange":4},{"type":"slider","key":"t:slider:Speed#1","text":"Speed","min":0,"max":2,"step":1,"value":1,"default":1,"labels":["0x","1x","2x"],"onChange":5},{"type":"separator","key":"t:separator:the end#1","text":"the end"}],"bottomButton":{"key":"b","text":"Save","onClick":6}}"#,
  );
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn input_text_renders_entities_beside_the_plain_text() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            const bold = (offset, length) => [{ _: 'messageEntityBold', offset, length }];
            const page = inu.ui.settingsPage({
                title: 'Test page',
                items: () => [
                    inu.ui.button({ text: { text: 'Do it', entities: bold(0, 2) },
                        subtitle: { text: 'sub', entities: bold(1, 1) }, value: 'now', onClick: () => {} }),
                    inu.ui.select({ text: { text: 'Mode', entities: bold(0, 4) }, items: ['a'], selected: 0,
                        onChange: () => {} }),
                    inu.ui.separator({ text: 'the end', entities: bold(4, 3) }),
                ],
            });
            globalThis.__disposeSettings = inu.registerSettings(page);
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  let json = state.render(&rt, &ctx, page_id).expect("render failed");
  assert_eq!(
    json,
    r#"{"title":"Test page","items":[{"type":"button","key":"t:button:Do it#1","text":"Do it","subtitle":"sub","value":"now","textEntities":[{"_":"messageEntityBold","offset":0,"length":2}],"subtitleEntities":[{"_":"messageEntityBold","offset":1,"length":1}],"danger":false,"onClick":1},{"type":"select","key":"t:select:Mode#1","text":"Mode","textEntities":[{"_":"messageEntityBold","offset":0,"length":4}],"items":[{"text":"a"}],"selected":0,"dialog":false,"onChange":2},{"type":"separator","key":"t:separator:the end#1","text":"the end","textEntities":[{"_":"messageEntityBold","offset":4,"length":3}]}]}"#,
  );
  assert!(!json.contains("valueEntities"), "a plain string carries no entities: {json}");
}

/// a check row is a plain `TextView` and renders no spans, so its text stays a string rather than
/// silently dropping the entities of an `InputText`
#[test]
fn check_refuses_input_text() {
  let (_rt, ctx, _host, _state, _logs) = setup();
  let message: String = ctx.with(|ctx| {
    ctx
      .eval(
        r#"(() => {
                   try {
                       inu.ui.check({ text: { text: 'Toggle', entities: [] }, checked: false, onChange: () => {} });
                       return 'did not throw';
                   } catch (e) { return e.message; }
               })()"#,
      )
      .unwrap()
  });
  assert_eq!(message, "check: 'text' must be a string");
}

#[test]
fn events_update_state_and_rerender_uses_fresh_slots() {
  let (rt, ctx, host, state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  state.render(&rt, &ctx, page_id).unwrap();

  state.dispatch_event(&rt, &ctx, page_id, 1, "true");
  state.dispatch_event(&rt, &ctx, page_id, 4, "1");
  state.dispatch_event(&rt, &ctx, page_id, 2, "");
  state.dispatch_event(&rt, &ctx, page_id, 6, "");

  let snapshot: String = ctx.with(|ctx| ctx.eval("JSON.stringify([__state.on, __state.sel, __state.log])").unwrap());
  assert_eq!(snapshot, r#"[true,1,["click","save"]]"#);

  let json = state.render(&rt, &ctx, page_id).unwrap();
  assert!(json.contains(r#""checked":true"#));
  assert!(json.contains(r#""selected":1"#));
  assert!(json.contains(r#""onChange":7"#), "slots must not restart: {json}");

  // slot from the first render is gone now - firing it must be a silent no-op
  state.dispatch_event(&rt, &ctx, page_id, 1, "false");
  let unchanged: bool = ctx.with(|ctx| ctx.eval("__state.on === true").unwrap());
  assert!(unchanged);
}

#[test]
fn the_anchor_opens_a_menu_over_its_own_row_and_a_click_dispatches() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__picked = null;
            const page = inu.ui.settingsPage({
                title: 'menu test',
                items: () => [
                    inu.ui.button({ text: 'first', onClick: () => {} }),
                    inu.ui.button({ id: 'menu-row', text: 'row', onClick: (anchor) => {
                        anchor.openMenu([
                            { text: 'one', onClick: () => { globalThis.__picked = 'one'; } },
                            { text: 'two', checked: true, danger: true, onClick: () => { globalThis.__picked = 'two'; } },
                        ]);
                    } }),
                ],
            });
            inu.registerSettings(page);
            "#,
      )
      .unwrap();
  });

  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  state.dispatch_event(&rt, &ctx, page_id, 2, "");

  let menus = host.menus.borrow();
  assert_eq!(menus.len(), 1);
  assert_eq!(menus[0].page_id, page_id);
  assert_eq!(menus[0].anchor, "i:menu-row#1", "the menu must name the row it was opened from");
  assert_eq!(
    menus[0].items_json,
    r#"[{"text":"one","danger":false},{"text":"two","checked":true,"danger":true}]"#,
  );
  let menu_id = menus[0].id;
  drop(menus);

  state.dispatch_menu_click(&rt, &ctx, menu_id, 1);
  let picked: String = ctx.with(|ctx| ctx.eval("globalThis.__picked").unwrap());
  assert_eq!(picked, "two");
  assert!(state.menus.borrow().is_empty());
}

#[test]
fn menu_dismissed_without_click_releases_callbacks() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__picked = null;
            const page = inu.ui.settingsPage({
                title: 't',
                items: () => [inu.ui.button({ text: 'row', onClick: (anchor) => {
                    anchor.openMenu([{ text: 'x', onClick: () => { globalThis.__picked = 'x'; } }]);
                } })],
            });
            inu.registerSettings(page);
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  state.dispatch_event(&rt, &ctx, page_id, 1, "");
  let menu_id = host.menus.borrow()[0].id;

  state.dispatch_menu_click(&rt, &ctx, menu_id, -1);
  let picked_is_null: bool = ctx.with(|ctx| ctx.eval("globalThis.__picked === null").unwrap());
  assert!(picked_is_null);
  assert!(state.menus.borrow().is_empty());
}

/// the whole reason the anchor is a value: the auto-invalidate that follows every callback has
/// already reallocated the page's slots by the time an awaited continuation resumes
#[test]
fn an_anchor_outlives_the_render_that_minted_it() {
  let (rt, ctx, host, state, logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__anchor = null;
            inu.registerSettings(inu.ui.settingsPage({
                title: 't',
                items: () => [
                    inu.ui.check({ id: 'row', text: 'toggle', checked: false, onChange: (v, anchor) => {
                        globalThis.__anchor = anchor;
                    } }),
                ],
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  state.dispatch_event(&rt, &ctx, page_id, 1, "true");
  // the host re-renders after every event, which drops slot 1 and mints slot 2
  let json = state.render(&rt, &ctx, page_id).unwrap();
  assert!(json.contains(r#""onChange":2"#), "the re-render must reallocate slots: {json}");

  ctx.with(|ctx| {
    ctx.eval::<(), _>("globalThis.__anchor.openMenu([{ text: 'late', onClick: () => {} }]);").unwrap();
  });
  let menus = host.menus.borrow();
  assert_eq!(menus.len(), 1);
  assert_eq!(menus[0].anchor, "i:row#1");
  assert!(logs.borrow().is_empty(), "unexpected logs: {:?}", logs.borrow());
}

#[test]
fn an_anchor_whose_page_was_disposed_is_handle_expired() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__anchor = null;
            globalThis.__page = inu.ui.settingsPage({
                title: 't',
                items: () => [inu.ui.button({ text: 'row', onClick: (anchor) => { globalThis.__anchor = anchor; } })],
            });
            inu.registerSettings(globalThis.__page);
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  state.dispatch_event(&rt, &ctx, page_id, 1, "");
  assert_eq!(host.menus.borrow().len(), 0);

  let outcome: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            globalThis.__page.dispose();
            let out = 'no-throw';
            try {
                globalThis.__anchor.openMenu([{ text: 'late', onClick: () => {} }]);
            } catch (e) { out = `${e instanceof inu.PluginError}:${e.code}`; }
            out;
            "#,
      )
      .unwrap()
  });
  assert_eq!(outcome, "true:handle-expired");
  assert!(host.menus.borrow().is_empty(), "a disposed page must not reach the host");
}

/// a menu item's own `onClick` gets no anchor, so a menu cannot open another menu
#[test]
fn a_menu_item_callback_gets_no_anchor() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__args = null;
            inu.registerSettings(inu.ui.settingsPage({
                title: 't',
                items: () => [inu.ui.button({ text: 'row', onClick: (anchor) => {
                    anchor.openMenu([{ text: 'x', onClick: (...args) => { globalThis.__args = args.length; } }]);
                } })],
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  state.dispatch_event(&rt, &ctx, page_id, 1, "");
  let menu_id = host.menus.borrow()[0].id;
  state.dispatch_menu_click(&rt, &ctx, menu_id, 0);

  let argc: i32 = ctx.with(|ctx| ctx.eval("globalThis.__args").unwrap());
  assert_eq!(argc, 0);
}

/// every callback position the contract declares an anchor for gets one, at the right index
#[test]
fn every_item_callback_is_handed_an_anchor() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__seen = {};
            const note = (what) => (...args) => {
                const anchor = args[args.length - 1];
                globalThis.__seen[what] = typeof anchor?.openMenu === 'function' ? args.length : 'missing';
            };
            inu.registerSettings(inu.ui.settingsPage({
                title: 't',
                items: () => [
                    inu.ui.check({ text: 'c', checked: false, onChange: note('check'), onSecondaryClick: note('checkLong') }),
                    inu.ui.button({ text: 'b', onClick: note('button'), onSecondaryClick: note('buttonLong') }),
                    inu.ui.select({ text: 's', items: ['a', 'b'], selected: 0, onChange: note('select'), onSecondaryClick: note('selectLong') }),
                    inu.ui.slider({ text: 'l', min: 0, max: 2, step: 1, value: 0, onChange: note('slider') }),
                ],
                bottomButton: { text: 'go', onClick: note('bottom') },
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();
  // slots in render order: check/checkLong, button/buttonLong, select/selectLong, slider, bottom
  for (slot, arg) in [(1, "true"), (2, ""), (3, ""), (4, ""), (5, "1"), (6, ""), (7, "1"), (8, "")] {
    state.dispatch_event(&rt, &ctx, page_id, slot, arg);
  }
  let seen: String = ctx.with(|ctx| ctx.eval("JSON.stringify(globalThis.__seen)").unwrap());
  assert_eq!(
    seen,
    r#"{"check":2,"checkLong":1,"button":1,"buttonLong":1,"select":2,"selectLong":1,"slider":2,"bottom":1}"#,
  );
}

#[test]
fn a_row_key_is_stable_across_renders_and_unique_within_one() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__extra = false;
            inu.registerSettings(inu.ui.settingsPage({
                title: 't',
                items: () => [
                    ...(globalThis.__extra ? [inu.ui.header('Extra')] : []),
                    inu.ui.separator(),
                    inu.ui.separator(),
                    inu.ui.button({ id: 'act', text: 'whatever this render calls it', onClick: () => {} }),
                ],
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  let first = state.render(&rt, &ctx, page_id).unwrap();
  assert!(first.contains(r#""key":"t:separator:#1""#), "{first}");
  assert!(first.contains(r#""key":"t:separator:#2""#), "two textless separators must not collide: {first}");
  assert!(first.contains(r#""key":"i:act#1""#), "{first}");

  // a row appearing above it, and its own text changing, must not move the explicit key
  ctx.with(|ctx| {
    ctx.eval::<(), _>("globalThis.__extra = true;").unwrap();
  });
  let second = state.render(&rt, &ctx, page_id).unwrap();
  assert!(second.contains(r#""key":"i:act#1""#), "an explicit id is the row's identity: {second}");
  assert!(second.contains(r#""key":"t:header:Extra#1""#), "{second}");
}

#[test]
fn rendering_a_page_the_engine_no_longer_has_is_an_error_not_a_fault() {
  let (rt, ctx, host, state, logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  state.render(&rt, &ctx, page_id).unwrap();
  ctx.with(|ctx| ctx.eval::<(), _>("globalThis.__page.dispose();").unwrap());
  logs.borrow_mut().clear();

  // the host still has the page on screen and asks for one more render: that is the app
  // naming a page the engine dropped, not the plugin throwing
  assert!(state.render(&rt, &ctx, page_id).is_none());
  let entry = logs.borrow().first().cloned().expect("expected a logged diagnostic");
  assert_eq!(
    crate::classify_log(&entry).0,
    crate::LEVEL_ERROR,
    "disposing your own open page must not disable the plugin: {entry}",
  );
}

#[test]
fn open_page_and_invalidate_reach_host() {
  let (rt, ctx, host, _state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.ui.openPage(globalThis.__page); globalThis.__page.invalidate();").unwrap();
  });
  assert_eq!(*host.opened_pages.borrow(), vec![page_id]);
  assert_eq!(*host.invalidated.borrow(), vec![page_id]);

  let threw = ctx.with(|ctx| ctx.eval::<(), _>("inu.ui.openPage({})").is_err());
  assert!(threw);
  let _ = (&rt, page_id);
}

#[test]
fn open_screen_validates_and_reaches_host() {
  let (_rt, ctx, host, _state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
          inu.ui.openPage({ type: 'chat', dialogId: -1001, topicId: 42, account: 2 })
          inu.ui.openPage({ type: 'profile', dialogId: 7, account: 2 })
          inu.ui.openPage({ type: 'dialogs', account: 2 })
          inu.ui.openPage({ type: 'settings' })
        "#,
      )
      .unwrap();
  });
  assert_eq!(
    *host.opened_screens.borrow(),
    vec![
      r#"{"type":"chat","accountId":2,"dialogId":-1001,"topicId":42}"#,
      r#"{"type":"profile","accountId":2,"dialogId":7}"#,
      r#"{"type":"dialogs","accountId":2}"#,
      r#"{"type":"settings"}"#,
    ]
  );

  for source in [
    "inu.ui.openPage({ type: 'other' })",
    "inu.ui.openPage({ type: 'chat', dialogId: 0 })",
    "inu.ui.openPage({ type: 'profile', dialogId: 1, topicId: 2 })",
    "inu.ui.openPage({ type: 'profile', dialogId: 1, account: {} })",
  ] {
    assert!(ctx.with(|ctx| ctx.eval::<(), _>(source).is_err()), "accepted {source}");
  }
}

/// the android members that take a real object take it as an `inu.jvm` handle and nothing
/// else: what crosses is the id, so the grant that let the handle be minted is still the
/// only thing that decided anything
#[test]
fn a_java_object_reaches_open_page_native_view_and_drawable_icon() {
  struct IconHost;

  impl crate::api::ui::icons::IconHost for IconHost {
    fn icon_resolves(&self, _kind: i32, _value: &str) -> bool {
      true
    }

    fn common_icon(&self, _name: &str) -> Option<String> {
      None
    }
  }

  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestUiHost::default());
  let host_dyn: Rc<dyn UiHost> = host.clone();
  let log = crate::testing::harness::log_sink(&crate::testing::harness::Logs::new());
  let jvm_host = crate::api::platform::jvm::tests::testing::OracleJvmHost::new();
  let grants = crate::sandbox::grants::TestGrantHost::new(&["unsafe.jvm"]);
  let (state, jvm) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    let jvm = crate::api::platform::jvm::install_jvm(
      &ctx,
      jvm_host.as_host(),
      None,
      grants.as_host(),
      Lifecycle::new(),
      log.clone(),
      None,
      &inu,
    )
    .unwrap();
    ctx.globals().set("__obj", jvm.wire_to_value(&ctx, "GO900").unwrap()).unwrap();
    crate::api::ui::icons::install_icons(&ctx, Rc::new(IconHost), Some(jvm.clone()), &inu).unwrap();
    let ui = install_ui(&ctx, host_dyn, Lifecycle::new(), log, Some(jvm.clone()), &inu).unwrap();
    (ui, jvm)
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let _jvm = crate::testing::harness::DisposeOnDrop::new(&ctx, jvm, |ctx, state| state.dispose(ctx));

  ctx.with(|ctx| {
    ctx.eval::<(), _>("inu.ui.openPage(globalThis.__obj);").unwrap();
  });
  assert_eq!(host.opened_fragments.borrow().len(), 1, "a java object never reached the fragment path");
  assert!(host.opened_pages.borrow().is_empty(), "and it must not be taken for a settings page");

  let element: String = ctx.with(|ctx| ctx.eval("JSON.stringify(inu.android.nativeView(globalThis.__obj))").unwrap());
  assert!(element.contains(r#""__inuUi":"native""#), "{element}");
  assert!(element.contains(r#""handle":"#), "{element}");

  let page_id: i64 = ctx.with(|ctx| {
    ctx
      .eval(
        r#"
            globalThis.__iconPage = inu.ui.settingsPage({
                title: 'Icon',
                items: () => [inu.ui.button({
                    text: 'Icon',
                    icon: inu.android.drawableIcon(globalThis.__obj),
                    onClick: () => {},
                })],
            });
            globalThis.__iconPage.__inuPageId
            "#,
      )
      .unwrap()
  });
  let rendered = state.render(&rt, &ctx, page_id).unwrap();
  assert!(rendered.contains(r#""icon":"j"#), "{rendered}");
  assert_eq!(state.pages.borrow()[&page_id].retained_icon_values.borrow().len(), 1);

  // a number is not a handle: the id lives in a WeakMap keyed on the object, so writing one
  // down is not a way to name something the plugin was never handed
  let threw: String = ctx.with(|ctx| {
    ctx
      .eval(
        r#"(() => { try { inu.android.nativeView(2); return 'no-throw' } catch (e) { return e.constructor.name } })()"#,
      )
      .unwrap()
  });
  assert_eq!(threw, "TypeError");
  drop(state);
}

#[test]
fn page_closed_fires_on_close_and_page_stays_reopenable() {
  let (rt, ctx, host, state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  state.render(&rt, &ctx, page_id).unwrap();

  state.close_page(&rt, &ctx, page_id);
  let closed: bool = ctx.with(|ctx| ctx.eval("__state.log.includes('close')").unwrap());
  assert!(closed);

  // render callbacks were released, but the page can be rendered again
  assert!(state.render(&rt, &ctx, page_id).is_some());
}

#[test]
fn manual_dispose_releases_page_and_is_idempotent() {
  let (rt, ctx, host, state, logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  state.render(&rt, &ctx, page_id).unwrap();

  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__page.dispose();
            globalThis.__page.dispose();
            globalThis.__openErr = null;
            try { inu.ui.openPage(globalThis.__page); } catch (e) { globalThis.__openErr = `${e instanceof inu.PluginError}:${e.code}`; }
            globalThis.__typeErr = null;
            try { inu.ui.openPage({}); } catch (e) { globalThis.__typeErr = e.constructor.name; }
            "#,
      )
      .unwrap();
  });
  assert!(state.pages.borrow().is_empty());
  let open_err: String = ctx.with(|ctx| ctx.eval("globalThis.__openErr").unwrap());
  assert_eq!(open_err, "true:handle-expired");
  // something that is not a page at all stays a plain TypeError: no handle ever existed
  let type_err: String = ctx.with(|ctx| ctx.eval("globalThis.__typeErr").unwrap());
  assert_eq!(type_err, "TypeError");
  assert!(state.render(&rt, &ctx, page_id).is_none());
  logs.borrow_mut().clear();
  // rt/ctx drop after this without aborting == roots were released
  let _ = &rt;
}

#[test]
fn transient_page_auto_disposes_on_close_after_on_close_fires() {
  let (rt, ctx, host, state, _logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__closed = 0;
            globalThis.__page = inu.ui.settingsPage({
                title: 't',
                transient: true,
                items: () => [inu.ui.button({ text: 'r', onClick: () => {} })],
                onClose: () => { globalThis.__closed++; },
            });
            inu.registerSettings(globalThis.__page);
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).unwrap();

  state.close_page(&rt, &ctx, page_id);
  let closed: i32 = ctx.with(|ctx| ctx.eval("globalThis.__closed").unwrap());
  assert_eq!(closed, 1);
  assert!(state.pages.borrow().is_empty());

  // a second close for the same id must be a silent no-op
  state.close_page(&rt, &ctx, page_id);
  let closed: i32 = ctx.with(|ctx| ctx.eval("globalThis.__closed").unwrap());
  assert_eq!(closed, 1);

  // and reopening the one page a plugin kept says why it is gone, that being the easy mistake
  let refused: String = ctx.with(|ctx| {
    ctx
      .eval(
        r#"
            (() => {
                try { inu.ui.openPage(globalThis.__page); return 'opened'; }
                catch (e) { return String(e.message ?? e); }
            })()
            "#,
      )
      .unwrap()
  });
  assert!(refused.contains("declared transient"), "{refused}");
}

#[test]
fn throwing_items_fn_logs_and_returns_none() {
  let (rt, ctx, host, state, logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.registerSettings(inu.ui.settingsPage({
                title: 'broken',
                items: () => { throw new Error('render-boom'); },
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  assert!(state.render(&rt, &ctx, page_id).is_none());
  let entry = logs.borrow().first().cloned().expect("expected a logged diagnostic");
  let (level, message) = crate::classify_log(&entry);
  assert_eq!(level, crate::LEVEL_FAULT, "a page whose render throws must disable the plugin");
  assert!(message.contains("render failed") && message.contains("render-boom"), "got: {message}");
}

#[test]
fn a_throwing_page_callback_faults_where_a_stale_host_id_does_not() {
  let (rt, ctx, host, state, logs) = setup();
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            inu.registerSettings(inu.ui.settingsPage({
                title: 'broken',
                items: () => [inu.ui.button({ text: 'x', onClick: () => { throw new Error('click-boom'); } })],
                onClose: () => { throw new Error('close-boom'); },
            }));
            "#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page_id).expect("render failed");

  state.dispatch_event(&rt, &ctx, page_id, 1, "");
  state.close_page(&rt, &ctx, page_id);

  let seen: Vec<(i32, String)> = logs
    .borrow()
    .iter()
    .map(|line| {
      let (level, message) = crate::classify_log(line);
      (level, message.to_string())
    })
    .collect();
  for want in ["click-boom", "close-boom"] {
    let Some((level, message)) = seen.iter().find(|(_, message)| message.contains(want)) else {
      panic!("no diagnostic for '{want}', got: {seen:?}");
    };
    assert_eq!(*level, crate::LEVEL_FAULT, "'{message}' must disable the plugin");
  }

  // the host naming a menu the engine has already settled is a host bug; no plugin code ran
  logs.borrow_mut().clear();
  state.dispatch_menu_click(&rt, &ctx, 4242, 0);
  let entry = logs.borrow().first().cloned().expect("expected a logged diagnostic");
  assert_eq!(crate::classify_log(&entry).0, crate::LEVEL_ERROR, "got: {entry:?}");
}

#[test]
fn element_creation_validates_options_eagerly() {
  let (_rt, ctx, _host, _state, _logs) = setup();
  let errors: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const out = [];
            const tryIt = f => { try { f(); out.push('ok'); } catch (e) { out.push(e.message); } };
            tryIt(() => inu.ui.check({ text: 'x' }));
            tryIt(() => inu.ui.select({ text: 'x', items: [], selected: 0, onChange: () => {} }));
            tryIt(() => inu.ui.select({ text: 'x', items: ['a'], selected: 5, onChange: () => {} }));
            tryIt(() => inu.ui.slider({ min: 0, max: 10, step: 0, value: 1, onChange: () => {} }));
            JSON.stringify(out);
            "#,
      )
      .unwrap()
  });
  assert_eq!(
    errors,
    r#"["check: 'checked' must be a boolean","select: 'items' must not be empty","select: 'selected' out of range","slider: 'step' must be > 0"]"#,
  );
}

/// degrading to bare numbers loses whatever unit the label carried, on a range the author's own
/// test values never reach - so it is refused, and refused again where a hand-built element is
/// read rather than only where `inu.ui.slider` mints one
#[test]
fn a_slider_label_past_the_step_cap_is_refused_at_both_ends() {
  let (rt, ctx, host, state, _logs) = setup();
  let minted: String = ctx.with(|ctx| {
    ctx
      .eval(
        r#"(() => {
                   const opts = { min: 0, max: 2000, step: 1, value: 0, onChange: () => {} };
                   inu.ui.slider(opts);
                   try {
                       inu.ui.slider({ ...opts, label: v => v + ' MB' });
                       return 'did not throw';
                   } catch (e) {
                       return `${e instanceof inu.PluginError}:${e.code}:${e.message.includes('2001 steps')}`;
                   }
               })()"#,
      )
      .unwrap()
  });
  assert_eq!(minted, "true:invalid-argument:true", "a label past the cap is refused");

  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"inu.registerSettings(inu.ui.settingsPage({ title: 'p', items: () => [{
                   __inuUi: 'slider', min: 0, max: 2000, step: 1, value: 0,
                   label: v => String(v), onChange: () => {},
               }] }))"#,
      )
      .unwrap();
  });
  let page_id = *host.registered.borrow().last().unwrap();
  assert!(state.render(&rt, &ctx, page_id).is_none(), "a forged element is refused too");
}

#[test]
fn slider_label_cap_is_enforced() {
  let stated = MAX_SLIDER_LABELS;

  let (_rt, ctx, _host, _state, _logs) = setup();
  let out: String = ctx.with(|ctx| {
    ctx
      .eval(format!(
        r#"(() => {{
                   const mk = (max) => {{
                       try {{
                           inu.ui.slider({{ min: 0, max, step: 1, value: 0, label: String, onChange: () => {{}} }});
                           return 'ok';
                       }} catch (e) {{ return e.code }}
                   }};
                   return [mk({}), mk({})].join('|');
               }})()"#,
        stated - 1,
        stated,
      ))
      .unwrap()
  });
  assert_eq!(out, "ok|invalid-argument", "the cap counts the values a label is called for");
}

#[test]
fn a_second_register_settings_throws_until_the_first_is_disposed() {
  let (_rt, ctx, host, _state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  let err: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            let out = 'no-throw';
            try { inu.registerSettings(globalThis.__page); } catch (e) { out = e.message; }
            out;
            "#,
      )
      .unwrap()
  });
  assert!(err.contains("already registered"), "got: {err}");
  assert_eq!(*host.registered.borrow(), vec![page_id]);

  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        "globalThis.__disposeSettings(); globalThis.__disposeSettings(); inu.registerSettings(globalThis.__page);",
      )
      .unwrap();
  });
  assert_eq!(*host.unregistered.borrow(), vec![page_id], "a disposer called twice unregisters once");
  assert_eq!(*host.registered.borrow(), vec![page_id, page_id]);
}

#[test]
fn disposing_a_registered_page_unregisters_it() {
  let (_rt, ctx, host, state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  ctx.with(|ctx| ctx.eval::<(), _>("globalThis.__page.dispose();").unwrap());
  assert_eq!(*host.unregistered.borrow(), vec![page_id]);
  assert!(state.settings.is_empty());

  // the registration is gone, so its disposer must not unregister a second time
  ctx.with(|ctx| ctx.eval::<(), _>("globalThis.__disposeSettings();").unwrap());
  assert_eq!(*host.unregistered.borrow(), vec![page_id]);
}

#[test]
fn register_settings_after_unload_began_is_a_no_op() {
  let (_rt, ctx, host, state, _logs) = setup();
  state.lifecycle.begin_unload();
  let shape: String = ctx.with(|ctx| {
    ctx
      .eval::<String, _>(
        r#"
            const page = inu.ui.settingsPage({ title: 't', items: () => [] });
            typeof inu.registerSettings(page);
            "#,
      )
      .unwrap()
  });
  assert_eq!(shape, "function");
  assert!(host.registered.borrow().is_empty());
  assert!(state.settings.is_empty());
}

/// `inu.kv` has a host, a grant and an oracle of its own; the three lines of it this plugin
/// reads at load are stood in for so the ui half can run with no host but [`TestUiHost`]
const KV_SHIM: &str = r#"
    inu.kv = {
        store: new Map(),
        get(k) { return inu.kv.store.has(k) ? inu.kv.store.get(k) : null },
        set(k, v) { inu.kv.store.set(k, String(v)) },
        clear() { inu.kv.store.clear() },
    };
"#;

/// the bundled oracle is the only test the js surface gets on a device, so its load-time half -
/// everything decidable without a screen - is run here too, with an **exact** count: a member
/// that vanished reads as a refusal in a suite written out of `expectThrow`, and only the count
/// tells those apart
#[test]
fn the_bundled_ui_test_plugin_passes() {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  let host = Rc::new(TestUiHost::default());
  let host_dyn: Rc<dyn UiHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  // `inu.ui` is one object: the dialogs install it and the pages install into it, as an engine does
  let (dialogs, state) = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    let modal_host = Rc::new(crate::testing::harness::RecordingHost::default());
    let dialogs = crate::api::ui::dialogs::install_dialogs(&ctx, modal_host, None, log.clone(), &inu).unwrap();
    (dialogs, install_ui(&ctx, host_dyn, Lifecycle::new(), log, None, &inu).unwrap())
  });
  let _state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let _dialogs = crate::testing::harness::DisposeOnDrop::new(&ctx, dialogs, |ctx, state| state.dispose(ctx));
  let source = format!("{KV_SHIM}\n{}", include_str!("../../../../test/plugins/ui-test.js"),);
  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, &source);

  crate::testing::harness::assert_oracle_exact(&lines, "ui test done", 17);
  assert_eq!(host.registered.borrow().len(), 1, "the plugin must have left one settings page");
}

#[test]
fn dispose_with_open_everything_releases_roots() {
  let (rt, ctx, host, state, _logs) = setup();
  let page_id = build_full_page(&ctx, &host);
  state.render(&rt, &ctx, page_id).unwrap();
  // leave a menu open too
  ctx.with(|ctx| {
    ctx
      .eval::<(), _>(
        r#"
            globalThis.__disposeSettings();
            inu.registerSettings(inu.ui.settingsPage({ title: 'm', items: () => [
                inu.ui.button({ text: 'r', onClick: (anchor) => anchor.openMenu([{ text: 'x', onClick: () => {} }]) }),
            ]}));
            "#,
      )
      .unwrap();
  });
  let page2 = *host.registered.borrow().last().unwrap();
  state.render(&rt, &ctx, page2).unwrap();
  state.dispatch_event(&rt, &ctx, page2, 1, "");
  assert_eq!(state.menus.borrow().len(), 1);

  state.dispose(&ctx);
  // rt/ctx drop after this without aborting == roots were released
}
