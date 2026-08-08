use super::*;
use rquickjs::{Context, Runtime};

fn setup() -> (Runtime, Context) {
    let rt = Runtime::new().unwrap();
    let ctx = Context::full(&rt).unwrap();
    ctx.with(|ctx| {
        crate::engine::error::install_plugin_error(&ctx).unwrap();
        let shared = crate::tl::utils::install_utils(&ctx).unwrap();
        install_message(&ctx, &shared).unwrap();
    });
    (rt, ctx)
}

use crate::testing::util::eval_string as eval;

const INCOMING: &str = r#"{
    _: 'message', id: 42, date: 1715540640, message: 'hi',
    peer_id: { _: 'peerUser', user_id: '777000' },
    from_id: { _: 'peerUser', user_id: '777000' },
}"#;

/// the bundled oracle is the only test the js surface gets on a device, and everything but its
/// last section is a pure function of a raw it builds itself, so it runs here too
#[test]
fn the_bundled_message_test_plugin_passes() {
    let (rt, ctx) = setup();
    let lines = crate::testing::util::run_capturing_console(
        &rt,
        &ctx,
        include_str!("../../../res/assets-debug/inu_plugins/message-test.js"),
    );
    // this fixture is `inu.Message` alone, which is the whole point of the surface being pure;
    // the oracle's live half is `rpc.rs`'s to answer
    crate::testing::util::assert_oracle_exact_skipping(
        &lines,
        "message test done",
        63,
        &["SKIP the live half: no invokeRpc in this context"],
    );
}

#[test]
fn the_scalar_getters_read_straight_off_raw() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        &format!(
            r#"
            const m = new inu.Message({INCOMING});
            JSON.stringify([
                m.id, m.date, m.text, m.out, m.isService, m.isSecret, m.isPinned,
                m.editDate, m.views, m.forwards, m.viaBotId, m.groupedId, m.reactions,
                m.media, m.mediaType, m.document, m.duration, m.topicId,
                m.replyToMessageId, m.forwardedFrom,
            ]);
            "#,
        ),
    );
    assert_eq!(
        out,
        r#"[42,1715540640,"hi",false,false,false,false,null,null,null,null,null,null,null,null,null,null,null,null,null]"#,
    );
}

/// the wrapper is a *view* on raw, not a copy of it: a getter read after the object changed
/// must answer with what is there now, or a wrapper over a live `interceptRpc` request would
/// report what an earlier middleware wrote rather than what the request now says
#[test]
fn nothing_is_captured_at_construction() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        &format!(
            r#"
            const raw = {INCOMING};
            const m = new inu.Message(raw);
            const before = m.text;
            raw.message = 'rewritten';
            raw.pinned = true;
            JSON.stringify([before, m.text, m.isPinned, m.raw === raw, m.toJSON() === raw]);
            "#,
        ),
    );
    assert_eq!(out, r#"["hi","rewritten",true,true,true]"#);
}

#[test]
fn text_with_entities_carries_the_entities_only_when_there_are_any() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const bare = new inu.Message({ _: 'message', id: 1, message: 'plain' });
        const rich = new inu.Message({
            _: 'message', id: 2, message: 'bold',
            entities: [{ _: 'messageEntityBold', offset: 0, length: 4 }],
        });
        const service = new inu.Message({ _: 'messageService', id: 3 });
        JSON.stringify([
            bare.textWithEntities, 'entities' in bare.textWithEntities,
            rich.textWithEntities.entities.length, service.text, service.isService,
        ]);
        "#,
    );
    assert_eq!(out, r#"[{"text":"plain"},false,1,"",true]"#);
}

/// the doc's own reason for preferring the annotation: deriving from `peer_id` would answer
/// with the regular dm's id and silently conflate the two chats
#[test]
fn a_secret_chat_message_has_no_dialog_id_at_all() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const peer = { _: 'peerUser', user_id: '4242' };
        // 0x4000000000000000 | 7, what DialogObject.makeEncryptedDialogId(7) produces
        const encrypted = '4611686018427387911';
        const ordinary = new inu.Message({ _: 'message', id: 1, peer_id: peer, dialog_id: '4242' });
        const secret = new inu.Message({ _: 'message_secret', id: 2, peer_id: peer, dialog_id: encrypted });
        const secretService = new inu.Message({ _: 'messageService', id: 3, peer_id: peer, dialog_id: encrypted });
        const offWire = new inu.Message({ _: 'message', id: 4, peer_id: peer, dialog_id: '0' });
        JSON.stringify([
            ordinary.dialogId, ordinary.isSecret,
            secret.dialogId, secret.isSecret,
            secretService.dialogId, secretService.isSecret,
            offWire.dialogId, offWire.isSecret,
        ]);
        "#,
    );
    assert_eq!(out, "[4242,false,null,true,null,true,4242,false]");
}

#[test]
fn a_channel_dialog_id_keeps_its_sign() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const of = peer => new inu.Message({ _: 'message', id: 1, peer_id: peer }).dialogId;
        JSON.stringify([
            of({ _: 'peerChannel', channel_id: '456' }),
            of({ _: 'peerChat', chat_id: '123' }),
            of({ _: 'peerUser', user_id: '7' }),
            of(undefined),
        ]);
        "#,
    );
    assert_eq!(out, "[-456,-123,7,null]");
}

/// the invariant a plugin keying on the sender depends on: `from_id` is optional and a 1:1
/// dialog omits it, so `from_id` alone misses every message read straight off the wire
#[test]
fn the_sender_falls_back_to_the_dialog_peer_only_where_that_is_the_sender() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const of = raw => new inu.Message(Object.assign({ _: 'message', id: 1 }, raw)).senderId;
        const user = { _: 'peerUser', user_id: '777000' };
        const channel = { _: 'peerChannel', channel_id: '99' };
        JSON.stringify([
            of({ from_id: { _: 'peerUser', user_id: '5' }, peer_id: user }),
            of({ peer_id: user }),
            of({ peer_id: user, out: true }),
            of({ peer_id: channel }),
            of({ peer_id: { _: 'peerChat', chat_id: '3' } }),
        ]);
        "#,
    );
    assert_eq!(out, "[5,777000,null,null,null]");
}

#[test]
fn media_type_names_what_stock_spreads_across_a_dozen_predicates() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const doc = (...attributes) => new inu.Message({
            _: 'message', id: 1,
            media: { _: 'messageMediaDocument', document: { _: 'document', id: '9', attributes } },
        });
        const media = value => new inu.Message({ _: 'message', id: 1, media: value }).mediaType;
        JSON.stringify([
            doc({ _: 'documentAttributeSticker' }).mediaType,
            doc({ _: 'documentAttributeVideo' }, { _: 'documentAttributeSticker' }).mediaType,
            doc({ _: 'documentAttributeAudio', voice: true }).mediaType,
            doc({ _: 'documentAttributeAudio' }).mediaType,
            doc({ _: 'documentAttributeVideo', round_message: true }).mediaType,
            doc({ _: 'documentAttributeVideo' }, { _: 'documentAttributeAnimated' }).mediaType,
            doc({ _: 'documentAttributeVideo' }).mediaType,
            doc({ _: 'documentAttributeFilename', file_name: 'a.zip' }).mediaType,
            doc().mediaType,
            media({ _: 'messageMediaPhoto' }),
            media({ _: 'messageMediaPhoto_old' }),
            media({ _: 'messageMediaGeoLive' }),
            media({ _: 'messageMediaGiveawayResults' }),
            media({ _: 'messageMediaDice' }),
            media({ _: 'messageMediaEmpty' }),
            media(undefined),
            media({ _: 'messageMediaDocument', document: { _: 'documentEmpty' } }),
        ]);
        "#,
    );
    assert_eq!(
        out,
        r#"["sticker","sticker","voice","music","roundVideo","gif","video","document","document","photo","photo","location","giveaway","other",null,null,"document"]"#,
    );
}

#[test]
fn the_document_and_its_duration_come_off_the_attributes() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const withDoc = document => new inu.Message({
            _: 'message', id: 1, media: { _: 'messageMediaDocument', document },
        });
        const real = withDoc({ _: 'document', id: '9', attributes: [{ _: 'documentAttributeVideo', duration: 187 }] });
        const empty = withDoc({ _: 'documentEmpty', id: '0' });
        const photo = new inu.Message({ _: 'message', id: 1, media: { _: 'messageMediaPhoto' } });
        JSON.stringify([
            real.document.id, real.duration,
            empty.document, empty.duration,
            photo.document, photo.duration,
        ]);
        "#,
    );
    assert_eq!(out, r#"["9",187,null,null,null,null]"#);
}

/// the second row is the one that pays for itself: a message *in* a topic carries the topic's
/// root id in the same field a reply carries its target in, and reporting that as a reply is a
/// wrong answer rather than a missing one
#[test]
fn a_forum_topic_is_read_off_the_reply_header() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const of = reply_to => {
            const m = new inu.Message({ _: 'message', id: 1, reply_to });
            return [m.topicId, m.replyToMessageId];
        };
        JSON.stringify([
            of({ _: 'messageReplyHeader', forum_topic: true, reply_to_top_id: 12, reply_to_msg_id: 30 }),
            of({ _: 'messageReplyHeader', forum_topic: true, reply_to_msg_id: 12 }),
            of({ _: 'messageReplyHeader', reply_to_msg_id: 30 }),
            of(undefined),
        ]);
        "#,
    );
    assert_eq!(out, "[[12,30],[12,null],[null,30],[null,null]]");
}

#[test]
fn the_int64_getters_keep_their_string_form_and_the_id_ones_do_not() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const m = new inu.Message({
            _: 'message', id: 1, grouped_id: '13591443077081936', via_bot_id: '1234567',
            views: 90, forwards: 3, edit_date: 1715540700, pinned: true, out: true,
        });
        JSON.stringify([
            m.groupedId, typeof m.groupedId, m.viaBotId, typeof m.viaBotId,
            m.views, m.forwards, m.editDate, m.isPinned, m.out,
        ]);
        "#,
    );
    assert_eq!(out, r#"["13591443077081936","string",1234567,"number",90,3,1715540700,true,true]"#,);
}

#[test]
fn raw_is_the_one_thing_the_wrapper_owns_and_it_cannot_be_swapped() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const m = new inu.Message({ _: 'message', id: 1, message: 'original' });
        let swapped = 'no-throw';
        try { m.raw = { _: 'message', id: 2, message: 'impostor' } } catch (e) { swapped = e.constructor.name }
        let refused = 'no-throw';
        try { new inu.Message(null) } catch (e) { refused = `${e.constructor.name}:${e.code}` }
        let refusedString = 'no-throw';
        try { new inu.Message('a message') } catch (e) { refusedString = e.code }
        JSON.stringify([m.text, swapped, refused, refusedString]);
        "#,
    );
    assert_eq!(out, r#"["original","TypeError","PluginError:invalid-argument","invalid-argument"]"#);
}

/// legacy constructors are what a message loaded out of the app's own storage arrives as, and a
/// check written against the modern name has to see them
#[test]
fn legacy_message_constructors_are_still_messages() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        const of = name => {
            const m = new inu.Message({ _: name, id: 1, message: 'hi' });
            return [m.isService, m.isSecret, m.text];
        };
        JSON.stringify([of('message_old7'), of('messageService_old2'), of('message_secret_old'), of('message')]);
        "#,
    );
    assert_eq!(out, r#"[[false,false,"hi"],[true,false,"hi"],[false,true,"hi"],[false,false,"hi"]]"#,);
}

#[test]
fn stringifying_a_message_round_trips_as_a_plain_tl_message() {
    let (_rt, ctx) = setup();
    let out = eval(&ctx, &format!("JSON.stringify(JSON.parse(JSON.stringify(new inu.Message({INCOMING}))))"));
    assert_eq!(
        out,
        r#"{"_":"message","id":42,"date":1715540640,"message":"hi","peer_id":{"_":"peerUser","user_id":"777000"},"from_id":{"_":"peerUser","user_id":"777000"}}"#,
    );
}

/// the composition rule this module exists to keep: the wrapper reads the field, it does not
/// hold a copy of it, so a host that redacts on read redacts what the wrapper answers
#[test]
fn text_is_whatever_raw_is_willing_to_answer() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        // stands in for a filtered live view: the field is a getter the host answers
        const raw = { _: 'message', id: 1, peer_id: { _: 'peerUser', user_id: '777000' } };
        Object.defineProperty(raw, 'message', { get: () => 'code: ******', enumerable: true });
        const m = new inu.Message(raw);
        const first = m.text;
        JSON.stringify([first, m.text, m.textWithEntities.text, /[0-9-]{5,}/.test(m.text)]);
        "#,
    );
    assert_eq!(out, r#"["code: ******","code: ******","code: ******",false]"#);
}

#[test]
fn a_wrapper_is_available_without_any_grant_and_holds_no_host_state() {
    let (_rt, ctx) = setup();
    let out = eval(
        &ctx,
        r#"
        JSON.stringify([
            typeof inu.Message,
            inu.Message.length,
            new inu.Message({ _: 'message', id: 1 }) instanceof inu.Message,
            Object.keys(new inu.Message({ _: 'message', id: 1 })),
        ]);
        "#,
    );
    assert_eq!(out, r#"["function",1,true,["raw"]]"#);
}
