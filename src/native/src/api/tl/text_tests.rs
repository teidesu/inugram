use rquickjs::{Context, Runtime};
use std::rc::Rc;

struct TestUtilsHost;

impl crate::api::tl::utils::UtilsHost for TestUtilsHost {
  fn format(&self, op: i32, value: i64) -> String {
    format!("{op}:{value}")
  }
}

fn setup() -> (Runtime, Context) {
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    crate::api::tl::utils::install_utils_with_host(&ctx, Rc::new(TestUtilsHost), &inu).unwrap();
  });
  (rt, ctx)
}

use crate::testing::harness::eval_string as eval;

#[test]
fn markdown_parses_a_tagged_template() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.md`plain **bold** __italic__`)"#);
  assert_eq!(
    out,
    r#"{"text":"plain bold italic","entities":[{"_":"messageEntityBold","offset":6,"length":4},{"_":"messageEntityItalic","offset":11,"length":6}]}"#
  );
}

#[test]
fn markdown_parses_a_plain_string_too() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.md('**bold**'))"#);
  assert_eq!(out, r#"{"text":"bold","entities":[{"_":"messageEntityBold","offset":0,"length":4}]}"#);
}

#[test]
fn an_interpolated_string_is_text_rather_than_markup() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.md`${'**not bold**'}`)"#);
  assert_eq!(out, r#"{"text":"**not bold**","entities":[]}"#);
}

#[test]
fn an_interpolated_text_brings_its_entities() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const inner = inu.utils.md`__italic__`
      JSON.stringify(inu.utils.md`**bold ${inner}**`)
    "#,
  );
  assert_eq!(
    out,
    r#"{"text":"bold italic","entities":[{"_":"messageEntityItalic","offset":5,"length":6},{"_":"messageEntityBold","offset":0,"length":11}]}"#
  );
}

#[test]
fn numbers_and_bigints_are_written_as_digits_and_falsy_values_are_dropped() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.md`a ${123} b ${10n} c ${null} d ${false}`.text)"#);
  assert_eq!(out, r#""a 123 b 10 c  d""#);
}

#[test]
fn entity_fields_use_the_apps_own_shapes() {
  let (_rt, ctx) = setup();
  // a plain-string call rather than a template, the pre fence being backticks
  let out = eval(
    &ctx,
    r#"JSON.stringify(inu.utils.md(
      '```python\ncode\n```\n[u](tg://user?id=42) [e](tg://emoji?id=123123123123123) [h](tg://user?id=7&hash=aabbccddaabbccdd)',
    ).entities)"#,
  );
  assert_eq!(
    out,
    r#"[{"_":"messageEntityPre","offset":0,"length":4,"language":"python"},{"_":"messageEntityMentionName","offset":5,"length":1,"user_id":42},{"_":"messageEntityCustomEmoji","offset":7,"length":1,"document_id":"123123123123123"},{"_":"inputMessageEntityMentionName","offset":9,"length":1,"user_id":{"_":"inputUser","user_id":7,"access_hash":"-6144092014192636707"}}]"#
  );
}

#[test]
fn html_parses_tags_and_attribute_interpolation() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.html`<b>hi</b> <a href="${'https'}://example.com">link</a>`)"#);
  assert_eq!(
    out,
    r#"{"text":"hi link","entities":[{"_":"messageEntityBold","offset":0,"length":2},{"_":"messageEntityTextUrl","offset":3,"length":4,"url":"https://example.com"}]}"#
  );
}

#[test]
fn html_collapses_whitespace_and_thtml_keeps_it() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify([inu.utils.html`a  b`.text, inu.utils.thtml`a  b`.text])"#);
  assert_eq!(out, r#"["a b","a  b"]"#);
}

#[test]
fn unparse_takes_the_apps_entities_back_to_text() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const input = { text: 'hello world', entities: [{ _: 'messageEntityBold', offset: 0, length: 5 }] }
      JSON.stringify([inu.utils.md.unparse(input), inu.utils.html.unparse(input), inu.utils.md.unparse('a*b')])
    "#,
  );
  assert_eq!(out, r#"["**hello** world","<b>hello</b> world","a\\*b"]"#);
}

#[test]
fn unparse_passes_entities_it_does_not_write_through_untouched() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"inu.utils.md.unparse({
      text: 'hello world',
      entities: [{ _: 'messageEntityHashtag', offset: 0, length: 5 }, { _: 'messageEntityBold', offset: 6, length: 5 }],
    })"#,
  );
  assert_eq!(out, "hello **world**");
}

#[test]
fn escape_covers_both_formats() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"JSON.stringify([inu.utils.md.escape('a*b'), inu.utils.html.escape('<a>'), inu.utils.html.escape('"q"', true)])"#,
  );
  assert_eq!(out, r#"["a\\*b","&lt;a&gt;","&quot;q&quot;"]"#);
}

#[test]
fn a_round_trip_through_unparse_and_parse_keeps_the_entities() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const first = inu.utils.html`<b>bold</b> and <i>italic</i>`
      const again = inu.utils.md(inu.utils.md.unparse(first))
      JSON.stringify([first.text === again.text, JSON.stringify(first.entities) === JSON.stringify(again.entities)])
    "#,
  );
  assert_eq!(out, "[true,true]");
}

#[test]
fn bad_input_refuses_rather_than_guessing() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"(() => {
      const codes = []
      for (const fn of [
        () => inu.utils.md(42),
        () => inu.utils.md.escape(42),
        () => inu.utils.md.unparse(42),
        () => inu.utils.md.unparse({ text: 'a', entities: 'no' }),
      ]) {
        try { fn(); codes.push('no-throw') }
        catch (e) { codes.push(e instanceof inu.PluginError ? e.code : e.constructor.name) }
      }
      return JSON.stringify(codes)
    })()"#,
  );
  assert_eq!(out, r#"["invalid-argument","invalid-argument","invalid-argument","invalid-argument"]"#);
}
