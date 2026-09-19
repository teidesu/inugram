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
  let rt = Runtime::new().unwrap();
  let ctx = Context::full(&rt).unwrap();
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::error::install_plugin_error(&ctx).unwrap();
    install_utils_with_host(&ctx, Rc::new(TestUtilsHost), &inu).unwrap();
  });
  (rt, ctx)
}

use crate::testing::harness::eval_string as eval;

/// what a caught `inu.PluginError` is worth branching on, or the thrown value's type name
fn code_of(ctx: &Context, code: &str) -> String {
  eval(
    ctx,
    &format!(
      r#"(() => {{
                try {{ {code}; return 'no-throw' }}
                catch (e) {{ return e instanceof inu.PluginError ? e.code : e.constructor.name }}
            }})()"#,
    ),
  )
}

/// the bundled oracle also runs off-device against a recording formatter host
#[test]
fn the_bundled_utils_test_plugin_passes() {
  let (rt, ctx) = setup();
  let lines =
    crate::testing::harness::run_capturing_console(&rt, &ctx, include_str!("../../../../test/plugins/utils-test.js"));
  crate::testing::harness::assert_oracle_exact(&lines, "utils test done", 95);
}

#[test]
fn base64_and_hex_round_trip_real_byte_arrays() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const bytes = new Uint8Array([0, 1, 15, 16, 127, 128, 255]);
        const b64 = inu.utils.toBase64(bytes);
        const hex = inu.utils.toHex(bytes);
        const back = inu.utils.fromBase64(b64);
        JSON.stringify([
            b64,
            hex,
            back instanceof Uint8Array,
            Array.from(back).join(','),
            Array.from(inu.utils.fromHex(hex)).join(','),
            inu.utils.toBase64(new Uint8Array(0)),
            inu.utils.toHex(new Uint8Array(0)),
            Array.from(inu.utils.fromBase64('')).length,
        ]);
        "#,
  );
  assert_eq!(
    out,
    r#"["AAEPEH+A/w==","00010f107f80ff",true,"0,1,15,16,127,128,255","0,1,15,16,127,128,255","","",0]"#,
  );
}

/// the `$inuBytes` wrapper is padded, a good deal of the web is not, and a plugin should not
/// have to know which it was handed
#[test]
fn base64_decodes_padded_and_unpadded_alike() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const of = s => Array.from(inu.utils.fromBase64(s)).join(',');
        JSON.stringify([of('aGk='), of('aGk'), of('YQ=='), of('YQ')]);
        "#,
  );
  assert_eq!(out, r#"["104,105","104,105","97","97"]"#);
}

#[test]
fn a_malformed_codec_argument_is_told_apart_from_a_wrong_type() {
  let (_rt, ctx) = setup();
  // content the codec cannot read is the plugin's argument being wrong...
  assert_eq!(code_of(&ctx, "inu.utils.fromBase64('!!!!')"), "invalid-argument");
  assert_eq!(code_of(&ctx, "inu.utils.fromHex('abc')"), "invalid-argument");
  assert_eq!(code_of(&ctx, "inu.utils.fromHex('zz')"), "invalid-argument");
  // ...while handing a codec something that is not bytes at all is a plain type error, the
  // same call shape `crypto.getRandomValues` already refuses that way
  assert_eq!(code_of(&ctx, "inu.utils.toHex([1, 2, 3])"), "TypeError");
  assert_eq!(code_of(&ctx, "inu.utils.toBase64('hi')"), "TypeError");
}

#[test]
fn hex_is_lowercase_out_and_case_insensitive_in() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const bytes = new Uint8Array([0xde, 0xad, 0xbe, 0xef]);
        JSON.stringify([
            inu.utils.toHex(bytes),
            Array.from(inu.utils.fromHex('DEADBEEF')).join(','),
            Array.from(inu.utils.fromHex('dEaDbEeF')).join(','),
        ]);
        "#,
  );
  assert_eq!(out, r#"["deadbeef","222,173,190,239","222,173,190,239"]"#);
}

#[test]
fn peers_convert_between_the_two_id_schemes() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const { peers } = inu.utils;
        JSON.stringify([
            peers.toDialogId({ _: 'peerUser', user_id: '777000' }),
            peers.toDialogId({ _: 'peerChat', chat_id: '123' }),
            peers.toDialogId({ _: 'peerChannel', channel_id: '456' }),
            peers.toDialogId({ _: 'inputPeerChannelFromMessage', channel_id: '456' }),
            peers.toDialogId({ _: 'user', id: '42' }),
            peers.toDialogId({ _: 'channelForbidden', id: '7' }),
            peers.toBotApiId({ _: 'peerChannel', channel_id: '456' }),
            peers.toBotApiId({ _: 'peerChat', chat_id: '123' }),
            peers.toBotApiId({ _: 'user', id: '42' }),
            peers.fromBotApiId(-1000000000456),
            peers.fromBotApiId('-123'),
            peers.fromBotApiId(42),
        ]);
        "#,
  );
  assert_eq!(out, "[777000,-123,-456,-456,42,-7,-1000000000456,-123,42,-456,-123,42]");
}

/// the round trip is the point of having both: a channel is the only kind the schemes disagree
/// on, and disagreeing about which one it is silently addresses another chat entirely
#[test]
fn a_bot_api_id_round_trips_for_every_kind() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const { peers } = inu.utils;
        const trip = peer => peers.fromBotApiId(peers.toBotApiId(peer)) === peers.toDialogId(peer);
        JSON.stringify([
            trip({ _: 'peerUser', user_id: '777000' }),
            trip({ _: 'peerChat', chat_id: '123' }),
            trip({ _: 'peerChannel', channel_id: '1234567890' }),
            trip({ _: 'channel', id: '2' }),
        ]);
        "#,
  );
  assert_eq!(out, "[true,true,true,true]");
}

#[test]
fn parse_dialog_id_cannot_tell_a_channel_from_a_group_and_says_so() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const { peers } = inu.utils;
        JSON.stringify([
            peers.parseDialogId(777000),
            peers.parseDialogId('-456'),
            peers.parseDialogId(-123),
        ]);
        "#,
  );
  assert_eq!(out, r#"[{"type":"user","id":777000},{"type":"chat","id":456},{"type":"chat","id":123}]"#,);
}

#[test]
fn to_input_peer_reads_the_access_hash_off_the_entity() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const { peers } = inu.utils;
        JSON.stringify([
            peers.toInputPeer({ _: 'user', id: '42', access_hash: '99' }),
            peers.toInputPeer({ _: 'user', id: '1', self: true, access_hash: '5' }),
            peers.toInputPeer({ _: 'user', id: '7' }),
            peers.toInputPeer({ _: 'chat', id: '123' }),
            peers.toInputPeer({ _: 'channel', id: '456', access_hash: '11' }),
        ]);
        "#,
  );
  assert_eq!(
    out,
    r#"[{"_":"inputPeerUser","user_id":"42","access_hash":"99"},{"_":"inputPeerSelf"},{"_":"inputPeerUser","user_id":"7","access_hash":"0"},{"_":"inputPeerChat","chat_id":"123"},{"_":"inputPeerChannel","channel_id":"456","access_hash":"11"}]"#,
  );
}

#[test]
fn a_peer_helper_refuses_what_it_cannot_answer_for() {
  let (_rt, ctx) = setup();
  for call in [
    "inu.utils.peers.toDialogId({ _: 'inputPeerSelf' })",
    "inu.utils.peers.toDialogId({ _: 'inputPeerEmpty' })",
    "inu.utils.peers.toDialogId(null)",
    "inu.utils.peers.toDialogId(42)",
    "inu.utils.peers.toDialogId({ _: 'constructor' })",
    "inu.utils.peers.parseDialogId(0)",
    "inu.utils.peers.parseDialogId('me')",
    "inu.utils.peers.toBotApiId({ _: 'message' })",
    "inu.utils.peers.toInputPeer({ _: 'peerUser', user_id: '1' })",
    "inu.utils.peers.toInputPeer({ _: 'userEmpty', id: '1' })",
  ] {
    assert_eq!(code_of(&ctx, call), "invalid-argument", "{call}");
  }
}

#[test]
fn format_date_dispatches_each_style_to_the_host() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const at = new Date(2024, 4, 12, 19, 4, 30);
        const unix = Math.floor(at.getTime() / 1000);
        JSON.stringify([
            inu.utils.formatDate(unix, 'date'),
            inu.utils.formatDate(unix, 'time'),
            inu.utils.formatDate(unix, 'dateTime'),
            inu.utils.formatDate(unix),
        ]);
        "#,
  );
  assert_eq!(out, r#"["0:1715526270","1:1715526270","2:1715526270","2:1715526270"]"#,);
}

#[test]
fn relative_dates_use_the_dialog_row_formatter() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        inu.utils.formatDate(123, 'relative');
        "#,
  );
  assert_eq!(out, "3:123");
}

#[test]
fn numbers_dispatch_the_plain_and_compact_stock_formatters() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const plain = v => inu.utils.formatNumber(v);
        const short = v => inu.utils.formatNumber(v, { compact: true });
        JSON.stringify([
            plain(0), plain(1234567), plain(-1234567),
            short(999), short(1000000), short(2500000000),
        ]);
        "#,
  );
  assert_eq!(out, r#"["4:0","4:1234567","4:-1234567","5:999","5:1000000","5:2500000000"]"#,);
}

#[test]
fn file_sizes_and_durations_dispatch_to_stock() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const size = inu.utils.formatFileSize;
        const time = inu.utils.formatDuration;
        JSON.stringify([
            size(0), size(512), size(1073741824),
            time(0), time(187), time(3764),
        ]);
        "#,
  );
  assert_eq!(out, r#"["6:0","6:512","6:1073741824","7:0","7:187","7:3764"]"#,);
}

#[test]
fn a_formatter_refuses_what_it_cannot_format() {
  let (_rt, ctx) = setup();
  for call in [
    "inu.utils.formatDate(NaN)",
    "inu.utils.formatDate('yesterday')",
    "inu.utils.formatDate(0, 'fuzzy')",
    "inu.utils.formatNumber('lots')",
    "inu.utils.formatNumber(1.5)",
    "inu.utils.formatFileSize(Infinity)",
    "inu.utils.formatDuration(-1)",
    "inu.utils.formatDuration(2147483648)",
    "inu.utils.formatDuration({})",
  ] {
    assert_eq!(code_of(&ctx, call), "invalid-argument", "{call}");
  }
}

/// the surface is `common.d.ts`'s, and a plugin cannot be allowed to reshape it under another
#[test]
fn the_namespace_is_exactly_what_the_contract_lists_and_is_frozen() {
  let (_rt, ctx) = setup();
  let out = eval(
    &ctx,
    r#"
        const keys = Object.keys(inu.utils).sort().join(',');
        const replace = (target, name) => {
            try { target[name] = () => 'replaced'; return 'no-throw' }
            catch (e) { return e.constructor.name }
        };
        JSON.stringify([
            keys,
            Object.keys(inu.utils.peers).sort().join(','),
            replace(inu.utils, 'toHex'),
            replace(inu.utils.peers, 'toDialogId'),
            replace(inu.utils.md, 'unparse'),
            inu.utils.toHex(new Uint8Array([1])),
            inu.utils.peers.toDialogId({ _: 'peerUser', user_id: '3' }),
        ]);
        "#,
  );
  assert_eq!(
    out,
    r#"["formatDate,formatDuration,formatFileSize,formatNumber,fromBase64,fromHex,html,joinTextWithEntities,md,peers,thtml,toBase64,toHex","fromBotApiId,parseDialogId,toBotApiId,toDialogId,toInputPeer","TypeError","TypeError","TypeError","01",3]"#,
  );
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
    r#"JSON.stringify([
            inu.utils.joinTextWithEntities(['a', 'b']).text,
            inu.utils.joinTextWithEntities([], ', ').text,
            inu.utils.joinTextWithEntities(['', 'a', 'b'], ', ').text,
            inu.utils.joinTextWithEntities(['a', '', 'b'], ', ').text,
        ])"#,
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
    assert_eq!(code_of(&ctx, call), "invalid-argument", "{call}");
  }
}


