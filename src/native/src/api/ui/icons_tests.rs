use super::*;
use crate::runtime::Dispose;
use std::cell::RefCell;

use rquickjs::{Context, Runtime};

const UNKNOWN_NAMES: &[&str] = &["nope", "lightbulb", "Settings", "constructor"];

fn curated_resource(name: &str) -> Option<String> {
  (!UNKNOWN_NAMES.contains(&name)).then(|| format!("msg_{name}"))
}
/// resolves everything except the names/sources a test deliberately withholds
pub(crate) struct TestIconHost {
  missing: Vec<String>,
  asked: RefCell<Vec<(i32, String)>>,
}

impl TestIconHost {
  pub(crate) fn without(missing: &[&str]) -> Rc<TestIconHost> {
    Rc::new(TestIconHost {
      missing: missing.iter().map(|s| (*s).to_string()).collect(),
      asked: RefCell::new(Vec::new()),
    })
  }
}

impl IconHost for TestIconHost {
  fn icon_resolves(&self, kind: i32, value: &str) -> bool {
    self.asked.borrow_mut().push((kind, value.to_string()));
    !self.missing.iter().any(|m| m == value)
  }

  fn common_icon(&self, name: &str) -> Option<String> {
    curated_resource(name)
  }
}

fn setup(missing: &[&str]) -> (Runtime, Context, Rc<TestIconHost>) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = TestIconHost::without(missing);
  let host_dyn: Rc<dyn IconHost> = host.clone();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_icons(&ctx, host_dyn, None, &inu).unwrap();
  });
  (rt, ctx, host)
}

fn thrown_code(ctx: &Context, expr: &str) -> String {
  ctx.with(|ctx| {
    ctx
      .eval::<String, _>(format!(
        "(() => {{ try {{ {expr}; return 'did-not-throw' }} \
             catch (e) {{ return e instanceof inu.PluginError ? e.code : e.constructor.name }} }})()"
      ))
      .unwrap()
  })
}

use crate::testing::harness::eval_string;

#[test]
fn an_svg_is_bounded_and_declaration_free() {
  assert!(check_svg("<svg viewBox='0 0 24 24'><path d='M0 0h24v24H0z'/></svg>").is_ok());
  assert!(check_svg("<?xml version='1.0'?><svg><!-- a note --><path d='M0 0'/></svg>").is_ok());
  assert!(matches!(check_svg("not markup at all"), Err(SvgReject::NotSvg)));
  assert!(matches!(check_svg("<!DOCTYPE svg SYSTEM 'file:///etc/passwd'><svg></svg>"), Err(SvgReject::Markup),));
  assert!(matches!(check_svg("<svg><!ENTITY x '&x;&x;'/></svg>"), Err(SvgReject::Markup),));
  assert!(matches!(
    check_svg(&format!("<svg>{}</svg>", "x".repeat(SVG_LIMIT_BYTES))),
    Err(SvgReject::TooLarge(_)),
  ));
  let head = "<svg>";
  let tail = "</svg>";
  let filler = SVG_LIMIT_BYTES - head.len() - tail.len();
  assert!(check_svg(&format!("{head}{}{tail}", "x".repeat(filler))).is_ok());
}

#[test]
fn a_curated_name_the_app_dropped_is_not_found() {
  let (_rt, ctx, _host) = setup(&["msg_settings"]);
  assert_eq!(thrown_code(&ctx, "inu.icons.common('settings')"), "not-found");
  assert_eq!(eval_string(&ctx, "inu.icons.common('info').__inuIcon"), "rmsg_info");
}

#[test]
fn resource_icon_checks_shape_then_existence() {
  let (_rt, ctx, host) = setup(&["msg_nothing"]);
  assert_eq!(eval_string(&ctx, "inu.android.resourceIcon('msg_settings').__inuIcon"), "rmsg_settings");
  assert_eq!(thrown_code(&ctx, "inu.android.resourceIcon('msg_nothing')"), "not-found");
  assert_eq!(
    thrown_code(&ctx, "inu.android.resourceIcon('org.telegram.messenger:raw/notification')"),
    "invalid-argument",
  );
  let asked: Vec<String> = host.asked.borrow().iter().map(|(_, v)| v.clone()).collect();
  assert_eq!(asked, vec!["msg_settings".to_string(), "msg_nothing".to_string()]);
}

#[test]
fn preset_and_android_raw_animations_mint_the_same_icon_kind() {
  let (_rt, ctx, host) = setup(&["missing_animation"]);
  assert_eq!(eval_string(&ctx, "inu.icons.animation('success').__inuIcon"), "a0done");
  assert_eq!(eval_string(&ctx, "inu.icons.animation('loading').__inuIcon"), "a0timer_3");
  assert_eq!(eval_string(&ctx, "inu.android.rawAnimation('info').__inuIcon"), "a0info");
  assert_eq!(eval_string(&ctx, "inu.android.rawAnimation('info', { loop: true }).__inuIcon"), "a1info");
  assert_eq!(eval_string(&ctx, "inu.android.rawAnimation('info', { loop: 3 }).__inuIcon"), "an3:info");
  assert_eq!(eval_string(&ctx, "inu.android.rawAnimation('info', { static: true }).__inuIcon"), "asinfo");
  assert_eq!(
    thrown_code(&ctx, "inu.android.rawAnimation('info', { loop: true, static: true })"),
    "invalid-argument"
  );
  for loop_value in ["-1", "1.5", "65536"] {
    assert_eq!(
      thrown_code(&ctx, &format!("inu.android.rawAnimation('info', {{ loop: {loop_value} }})")),
      "invalid-argument",
    );
  }
  assert_eq!(thrown_code(&ctx, "inu.icons.animation('unknown')"), "invalid-argument");
  assert_eq!(thrown_code(&ctx, "inu.android.rawAnimation('missing_animation')"), "not-found");
  assert!(host.asked.borrow().iter().all(|(kind, _)| *kind == KIND_RAW_ANIMATION));
}

#[test]
fn custom_emoji_requires_a_positive_int64_string() {
  let (_rt, ctx, _host) = setup(&[]);
  assert_eq!(eval_string(&ctx, "inu.icons.customEmoji('5361751237382052539').__inuIcon"), "e15361751237382052539");
  assert_eq!(
    eval_string(&ctx, "inu.icons.customEmoji('5361751237382052539', { loop: true }).__inuIcon"),
    "e15361751237382052539",
  );
  assert_eq!(
    eval_string(&ctx, "inu.icons.customEmoji('5361751237382052539', { static: true }).__inuIcon"),
    "es5361751237382052539",
  );
  assert_eq!(
    eval_string(&ctx, "inu.icons.customEmoji('5361751237382052539', { loop: false }).__inuIcon"),
    "e05361751237382052539",
  );
  assert_eq!(
    eval_string(&ctx, "inu.icons.customEmoji('5361751237382052539', { loop: 3 }).__inuIcon"),
    "en3:5361751237382052539",
  );
  assert_eq!(thrown_code(&ctx, "inu.icons.customEmoji('5361751237382052539', { loop: 'yes' })"), "TypeError");
  for bad in ["'0'", "'-1'", "'nope'", "42"] {
    assert_ne!(thrown_code(&ctx, &format!("inu.icons.customEmoji({bad})")), "did-not-throw");
  }
}

#[test]
fn sticker_selector_is_explicit_and_bounded() {
  let (_rt, ctx, _host) = setup(&[]);
  assert_eq!(eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', index: 2 }).__inuIcon"), "t1i2\ndogs");
  assert_eq!(
    eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', id: '5361751237382052539' }).__inuIcon"),
    "t1d5361751237382052539\ndogs",
  );
  assert_eq!(eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', emoji: '🐶' }).__inuIcon"), "t1e8J-Qtg\ndogs");
  assert_eq!(
    eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', index: 2, loop: true }).__inuIcon"),
    "t1i2\ndogs"
  );
  assert_eq!(
    eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', index: 2, static: true }).__inuIcon"),
    "tsi2\ndogs"
  );
  assert_eq!(
    eval_string(&ctx, "inu.icons.sticker({ slug: 'dogs', index: 2, loop: 3 }).__inuIcon"),
    "tn3:i2\ndogs"
  );
  for bad in [
    "{ slug: 'dogs' }",
    "{ slug: 'dogs', index: 0, emoji: '🐶' }",
    "{ slug: 'dogs', index: -1 }",
    "{ slug: 'bad/slugs', index: 0 }",
  ] {
    assert_eq!(thrown_code(&ctx, &format!("inu.icons.sticker({bad})")), "invalid-argument");
  }
}

#[test]
fn svg_refuses_before_it_asks_the_host() {
  let (_rt, ctx, host) = setup(&[]);
  assert_eq!(thrown_code(&ctx, "inu.icons.svg('hello')"), "invalid-argument");
  assert_eq!(thrown_code(&ctx, "inu.icons.svg('<!DOCTYPE svg><svg/>')"), "invalid-argument");
  assert_eq!(
    thrown_code(&ctx, &format!("inu.icons.svg('<svg>' + 'x'.repeat({SVG_LIMIT_BYTES}) + '</svg>')")),
    "quota-exceeded",
  );
  assert!(host.asked.borrow().is_empty());
}

#[test]
fn an_svg_the_host_cannot_parse_is_invalid_argument() {
  let source = "<svg><path d='not a path'/></svg>";
  let (_rt, ctx, host) = setup(&[source]);
  assert_eq!(thrown_code(&ctx, &format!("inu.icons.svg({source:?})")), "invalid-argument");
  assert_eq!(host.asked.borrow().len(), 1);
  assert_eq!(host.asked.borrow()[0].0, KIND_SVG);
}

pub(crate) struct SilentUiHost;

impl crate::api::ui::pages::UiHost for SilentUiHost {
  fn ui_open_fragment(&self, _: i64) -> Option<String> {
    None
  }
  fn ui_open_page(&self, _: i64) -> Option<String> {
    None
  }
  fn ui_open_screen(&self, _: &str) -> Option<String> {
    None
  }
  fn ui_register_settings(&self, _: i64) {}
  fn ui_unregister_settings(&self, _: i64) {}
  fn ui_invalidate(&self, _: i64) {}
  fn ui_open_menu(&self, _: i64, _: i64, _: &str, _: &str) -> Option<String> {
    None
  }
}

/// the whole point of the descriptor: what crosses to the host is the spec, inside the row
#[test]
fn a_row_carries_its_icon_spec_into_the_render() {
  let (rt, ctx, _host) = setup(&[]);
  let ui_host: Rc<dyn crate::api::ui::pages::UiHost> = Rc::new(SilentUiHost);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let state = ctx.with(|ctx| {
    crate::api::ui::pages::install_ui(
      &ctx,
      ui_host,
      crate::sandbox::registry::Lifecycle::new(),
      log,
      None,
      &crate::testing::harness::get_api_globals(&ctx),
    )
    .unwrap()
  });
  let state = crate::testing::harness::DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let page_id = ctx.with(|ctx| {
    ctx
      .eval::<f64, _>(
        r#"
          const page = inu.ui.settingsPage({
            title: 'Icons',
            items: () => [
              inu.ui.button({ text: 'Curated', icon: inu.icons.common('settings'), onClick: () => {} }),
              inu.ui.select({ text: 'Native', icon: inu.android.resourceIcon('msg_fave'),
                items: ['a'], selected: 0, onChange: () => {} }),
              inu.ui.button({ text: 'Inline', icon: inu.icons.svg('<svg><path d="M0 0"/></svg>'),
                onClick: () => {} }),
              inu.ui.button({ text: 'Animation', icon: inu.icons.animation('success'), onClick: () => {} }),
              inu.ui.button({ text: 'Emoji', icon: inu.icons.customEmoji('5361751237382052539'), onClick: () => {} }),
              inu.ui.button({ text: 'Sticker', icon: inu.icons.sticker({ slug: 'dogs', index: 2 }), onClick: () => {} }),
              inu.ui.button({ text: 'None', onClick: () => {} }),
            ],
          });
          page.__inuPageId
        "#,
      )
      .unwrap() as i64
  });
  let json = state.render(&rt, &ctx, page_id).expect("render failed");
  assert!(json.contains(r#""text":"Curated","icon":"rmsg_settings""#), "{json}");
  assert!(json.contains(r#""text":"Native","icon":"rmsg_fave""#), "{json}");
  assert!(json.contains(r#""icon":"s<svg><path d=\"M0 0\"/></svg>""#), "{json}");
  assert!(json.contains(r#""text":"Animation","icon":"a0done""#), "{json}");
  assert!(json.contains(r#""text":"Emoji","icon":"e15361751237382052539""#), "{json}");
  assert!(json.contains("\"text\":\"Sticker\",\"icon\":\"t1i2\\ndogs\""), "{json}");
  assert!(!json.contains(r#""text":"None","icon""#), "{json}");
}

#[test]
fn an_element_refuses_an_icon_it_was_not_handed() {
  let (_rt, ctx, _host) = setup(&[]);
  let ui_host: Rc<dyn crate::api::ui::pages::UiHost> = Rc::new(SilentUiHost);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let state = ctx.with(|ctx| {
    crate::api::ui::pages::install_ui(
      &ctx,
      ui_host,
      crate::sandbox::registry::Lifecycle::new(),
      log,
      None,
      &crate::testing::harness::get_api_globals(&ctx),
    )
    .unwrap()
  });
  let _state = crate::testing::harness::DisposeOnDrop::new(&ctx, state, |ctx, state| state.dispose(ctx));
  let make = |icon: &str| format!("inu.ui.button({{ text: 'x', icon: {icon}, onClick: () => {{}} }})");
  assert_eq!(thrown_code(&ctx, &make("{}")), "TypeError");
  assert_eq!(
    thrown_code(&ctx, &make(&format!("{{ __inuIcon: 's<svg>' + 'x'.repeat({SVG_LIMIT_BYTES}) }}"))),
    "invalid-argument",
  );
  assert_eq!(thrown_code(&ctx, &make("{ __inuIcon: 'q' }")), "invalid-argument");
  ctx.with(|ctx| {
    ctx.eval::<Value, _>(make("inu.icons.common('star')")).unwrap();
  });
}

/// on a device the oracle runs against real resources, the only place "every curated name is a drawable
/// this app ships" can be answered
#[test]
fn the_bundled_icons_test_plugin_passes() {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let icon_host = TestIconHost::without(&["inu_no_such_drawable_anywhere", "inu_no_such_animation_anywhere"]);
  let log: crate::Log = std::sync::Arc::new(|_| {});
  let ui = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_icons(&ctx, icon_host, None, &inu).unwrap();
    crate::api::ui::pages::install_ui(
      &ctx,
      Rc::new(SilentUiHost),
      crate::sandbox::registry::Lifecycle::new(),
      log,
      None,
      &inu,
    )
    .unwrap()
  });
  let ui = crate::testing::harness::DisposeOnDrop::new(&ctx, ui, |ctx, state| state.dispose(ctx));

  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, crate::testing::test_plugin!("icons-test.js"));
  drop(ui);

  crate::testing::harness::assert_oracle_exact(&lines, "icons test done", 31);
}
