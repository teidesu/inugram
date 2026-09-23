use super::*;
use rquickjs::{Context, Runtime};
use std::rc::Rc;

struct TestUtilsHost;

impl UtilsHost for TestUtilsHost {
  fn format(&self, op: i32, value: i64) -> String {
    format!("{op}:{value}")
  }
}

fn setup() -> (Runtime, Context) {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_utils_with_host(&ctx, Rc::new(TestUtilsHost), &inu).unwrap();
  });
  (rt, ctx)
}

use crate::testing::harness::eval_string as eval;

fn catch_error_code(ctx: &Context, code: &str) -> String {
  eval(
    ctx,
    &format!(
      r#"
        (() => {{
          try {{ {code}; return 'no-throw' }}
          catch (e) {{ return e instanceof inu.PluginError ? e.code : e.constructor.name }}
        }})()
      "#,
    ),
  )
}

#[test]
fn the_bundled_utils_test_plugin_passes() {
  let (rt, ctx) = setup();
  let lines = crate::testing::harness::run_capturing_console(&rt, &ctx, crate::testing::test_plugin!("utils-test.js"));
  crate::testing::harness::assert_oracle_exact(&lines, "utils test done", 95);
}

#[test]
fn to_input_peer_defaults_a_missing_access_hash_to_zero() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, "JSON.stringify(inu.utils.peers.toInputPeer({ _: 'user', id: '7' }))");
  assert_eq!(out, r#"{"_":"inputPeerUser","user_id":"7","access_hash":"0"}"#);
}

#[test]
fn a_peer_helper_refuses_what_it_cannot_answer_for() {
  let (_rt, ctx) = setup();
  for call in [
    "inu.utils.peers.toDialogId({ _: 'inputPeerEmpty' })",
    "inu.utils.peers.toDialogId(null)",
    "inu.utils.peers.parseDialogId(0)",
    "inu.utils.peers.toBotApiId({ _: 'message' })",
    "inu.utils.peers.toInputPeer({ _: 'userEmpty', id: '1' })",
    "inu.utils.formatDuration(2147483648)",
  ] {
    assert_eq!(catch_error_code(&ctx, call), "invalid-argument", "{call}");
  }
}

#[test]
fn every_formatter_dispatches_its_stock_op() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const { formatDate, formatNumber, formatFileSize, formatDuration } = inu.utils;
      JSON.stringify([
        formatDate(9, 'date'), formatDate(9, 'time'), formatDate(9, 'dateTime'), formatDate(9),
        formatDate(9, 'relative'), formatNumber(-9), formatNumber(9, { compact: true }),
        formatFileSize(9), formatDuration(9),
      ]);
    "#,
  );
  assert_eq!(out, r#"["0:9","1:9","2:9","2:9","3:9","4:-9","5:9","6:9","7:9"]"#);
}

/// a plugin cannot be allowed to reshape the namespace under another
#[test]
fn the_namespace_is_frozen() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const replace = (target, name) => {
        try { target[name] = () => 'replaced'; return 'no-throw' }
        catch (e) { return e.constructor.name }
      };
      JSON.stringify([
        replace(inu.utils, 'toHex'),
        replace(inu.utils.peers, 'toDialogId'),
        replace(inu.utils.md, 'unparse'),
        inu.utils.toHex(new Uint8Array([1])),
        inu.utils.peers.toDialogId({ _: 'peerUser', user_id: '3' }),
      ]);
    "#,
  );
  assert_eq!(out, r#"["TypeError","TypeError","TypeError","01",3]"#);
}

/// mtcute's helper: each part's entities move to where that part landed
#[test]
fn join_text_with_entities_shifts_every_part_into_place() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const bold = (text, offset) => ({ _: 'messageEntityBold', offset, length: text.length });
      JSON.stringify(inu.utils.joinTextWithEntities(
        [
          { text: 'ab', entities: [bold('ab', 0)] },
          'cd',
          { text: 'ef', entities: [bold('f', 1)] },
        ],
        { text: '--', entities: [bold('--', 0)] },
      ));
    "#,
  );
  assert_eq!(
    out,
    r#"{"text":"ab--cd--ef","entities":[{"_":"messageEntityBold","offset":0,"length":2},{"_":"messageEntityBold","offset":2,"length":2},{"_":"messageEntityBold","offset":6,"length":2},{"_":"messageEntityBold","offset":9,"length":1}]}"#,
  );
}

/// the delimiter goes in once something has been written, which is mtcute's own rule
#[test]
fn join_text_with_entities_defaults_to_no_delimiter_and_skips_a_leading_empty_part() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      JSON.stringify([
        inu.utils.joinTextWithEntities(['a', 'b']).text,
        inu.utils.joinTextWithEntities([], ', ').text,
        inu.utils.joinTextWithEntities(['', 'a', 'b'], ', ').text,
        inu.utils.joinTextWithEntities(['a', '', 'b'], ', ').text,
      ])
    "#,
  );
  assert_eq!(out, r#"["ab","","a, b","a, , b"]"#);
}

#[test]
fn join_text_with_entities_refuses_what_is_not_a_text() {
  let (_rt, ctx) = setup();
  for call in [
    "inu.utils.joinTextWithEntities('ab')",
    "inu.utils.joinTextWithEntities([7])",
    "inu.utils.joinTextWithEntities([null])",
    "inu.utils.joinTextWithEntities([{ text: 'a', entities: 7 }])",
    "inu.utils.joinTextWithEntities(['a'], 7)",
  ] {
    assert_eq!(catch_error_code(&ctx, call), "invalid-argument", "{call}");
  }
}

#[test]
fn markdown_parses_a_plain_string_too() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify(inu.utils.md('**bold**'))"#);
  assert_eq!(out, r#"{"text":"bold","entities":[{"_":"messageEntityBold","offset":0,"length":4}]}"#);
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
  let out = eval(
    &ctx,
    r#"
      JSON.stringify(inu.utils.md(
        '```python\ncode\n```\n[u](tg://user?id=42) [e](tg://emoji?id=123123123123123) [h](tg://user?id=7&hash=aabbccddaabbccdd)',
      ).entities)
    "#,
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
fn html_unparse_takes_the_apps_entities_back_to_text() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      const input = { text: 'hello world', entities: [{ _: 'messageEntityBold', offset: 0, length: 5 }] }
      JSON.stringify([inu.utils.html.unparse(input), inu.utils.md.unparse('a*b')])
    "#,
  );
  assert_eq!(out, r#"["<b>hello</b> world","a\\*b"]"#);
}

#[test]
fn unparse_passes_entities_it_does_not_write_through_untouched() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
      inu.utils.md.unparse({
        text: 'hello world',
        entities: [{ _: 'messageEntityHashtag', offset: 0, length: 5 }, { _: 'messageEntityBold', offset: 6, length: 5 }],
      })
    "#,
  );
  assert_eq!(out, "hello **world**");
}

#[test]
fn escape_covers_both_formats() {
  let (_rt, ctx) = setup();
  let out = eval(&ctx, r#"JSON.stringify([inu.utils.md.escape('a*b'), inu.utils.html.escape('"q"', true)])"#);
  assert_eq!(out, r#"["a\\*b","&quot;q&quot;"]"#);
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
    r#"
      (() => {
        const codes = []
        for (const fn of [
          () => inu.utils.md.escape(42),
          () => inu.utils.md.unparse(42),
          () => inu.utils.md.unparse({ text: 'a', entities: 'no' }),
        ]) {
          try { fn(); codes.push('no-throw') }
          catch (e) { codes.push(e instanceof inu.PluginError ? e.code : e.constructor.name) }
        }
        return JSON.stringify(codes)
      })()
    "#,
  );
  assert_eq!(out, r#"["invalid-argument","invalid-argument","invalid-argument"]"#);
}
