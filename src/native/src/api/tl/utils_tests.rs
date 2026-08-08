use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    ctx.with(|ctx| {
        let inu = crate::testing::harness::inu_namespace(&ctx);
        crate::api::error::install_plugin_error(&ctx, &inu).unwrap();
        install_utils(&ctx, &inu).unwrap();
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

/// the bundled oracle is the only test the js surface gets on a device, so it is also run here
/// - `inu.utils` has no host behind it, and an oracle nothing ever runs is documentation
#[test]
fn the_bundled_utils_test_plugin_passes() {
    let (rt, ctx) = setup();
    let lines = crate::testing::harness::run_capturing_console(
        &rt,
        &ctx,
        include_str!("../../../../res/assets-debug/inu_plugins/utils-test.js"),
    );
    crate::testing::harness::assert_oracle_exact(&lines, "utils test done", 92);
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

/// dates are formatted against the device's own timezone, so the fixture pins the instant by
/// building the expectation the same way rather than by hardcoding a wall clock
#[test]
fn format_date_covers_every_style() {
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
    assert_eq!(out, r#"["12 May 2024","19:04","12 May 2024, 19:04","12 May 2024, 19:04"]"#,);
}

#[test]
fn relative_dates_walk_from_a_time_through_a_weekday_to_a_date() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const now = new Date();
        const daysAgo = (days, hour) => {
            const at = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days, hour, 5);
            return Math.floor(at.getTime() / 1000);
        };
        const relative = unix => inu.utils.formatDate(unix, 'relative');
        const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
        const dayName = days => weekdays[new Date(daysAgo(days, 9) * 1000).getDay()];
        JSON.stringify([
            relative(daysAgo(0, 9)),
            relative(daysAgo(1, 9)) === dayName(1),
            relative(daysAgo(6, 9)) === dayName(6),
            relative(daysAgo(7, 9)).includes(String(new Date(daysAgo(7, 9) * 1000).getFullYear())),
            relative(daysAgo(400, 9)).includes(String(new Date(daysAgo(400, 9) * 1000).getFullYear())),
        ]);
        "#,
    );
    assert_eq!(out, r#"["09:05",true,true,true,true]"#);
}

#[test]
fn numbers_group_and_compact() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const plain = v => inu.utils.formatNumber(v);
        const short = v => inu.utils.formatNumber(v, { compact: true });
        JSON.stringify([
            plain(0), plain(999), plain(1000), plain(1234567), plain(-1234567), plain(1234.5),
            short(0), short(999), short(1000), short(1500), short(1999), short(12345),
            short(1000000), short(1200000), short(-1200000), short(2500000000),
        ]);
        "#,
    );
    assert_eq!(
        out,
        r#"["0","999","1 000","1 234 567","-1 234 567","1 234.5","0","999","1K","1.5K","1.9K","12.3K","1M","1.2M","-1.2M","2.5B"]"#,
    );
}

#[test]
fn file_sizes_and_durations_read_the_way_the_app_writes_them() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const size = inu.utils.formatFileSize;
        const time = inu.utils.formatDuration;
        JSON.stringify([
            size(0), size(512), size(1024), size(4404019), size(1073741824),
            time(0), time(7), time(187), time(3764), time(-5),
        ]);
        "#,
    );
    assert_eq!(out, r#"["0 B","512 B","1.0 KB","4.2 MB","1.0 GB","0:00","0:07","3:07","1:02:44","0:00"]"#,);
}

#[test]
fn a_formatter_refuses_what_it_cannot_format() {
    let (_rt, ctx) = setup();
    for call in [
        "inu.utils.formatDate(NaN)",
        "inu.utils.formatDate('yesterday')",
        "inu.utils.formatDate(0, 'fuzzy')",
        "inu.utils.formatNumber('lots')",
        "inu.utils.formatFileSize(Infinity)",
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
            inu.utils.toHex(new Uint8Array([1])),
            inu.utils.peers.toDialogId({ _: 'peerUser', user_id: '3' }),
        ]);
        "#,
    );
    assert_eq!(
        out,
        r#"["formatDate,formatDuration,formatFileSize,formatNumber,fromBase64,fromHex,peers,toBase64,toHex","fromBotApiId,parseDialogId,toBotApiId,toDialogId,toInputPeer","TypeError","TypeError","01",3]"#,
    );
}
