// ==InuPlugin==
// @name         message test
// @description  asserts inu.Message: every getter against a built raw, and that it stays a lazy read of one
// @grant        invokeRpc(messages.getHistory)
// ==/InuPlugin==

const SERVICE_PEER = { _: 'peerUser', user_id: '777000' }

function message(fields) {
  return new inu.Message(Object.assign({ _: 'message', id: 1, date: 1715540640 }, fields))
}

const incoming = message({
  id: 42,
  message: 'hi',
  peer_id: { _: 'peerUser', user_id: '4242' },
  from_id: { _: 'peerUser', user_id: '4242' },
  views: 90,
  edit_date: 1715540700,
  grouped_id: '13591443077081936',
  via_bot_id: '1234567',
  pinned: true,
})

equals('id', incoming.id, 42)
equals('date', incoming.date, 1715540640)
equals('text', incoming.text, 'hi')
equals('dialogId is derived from peer_id when there is no annotation', incoming.dialogId, 4242)
equals('senderId', incoming.senderId, 4242)
equals('editDate', incoming.editDate, 1715540700)
equals('views', incoming.views, 90)
equals('forwards is null rather than 0 when absent', incoming.forwards, null)
equals('isPinned', incoming.isPinned, true)
equals('out defaults to false', incoming.out, false)
check('groupedId stays an int64 string', incoming.groupedId === '13591443077081936', incoming.groupedId)
check('viaBotId is a number', incoming.viaBotId === 1234567 && typeof incoming.viaBotId === 'number')
equals('isService', incoming.isService, false)
equals('a message with no media has no media type', [incoming.media, incoming.mediaType, incoming.document, incoming.duration], [null, null, null, null])
equals('textWithEntities without entities', incoming.textWithEntities, { text: 'hi' })

const rich = message({ message: 'bold', entities: [{ _: 'messageEntityBold', offset: 0, length: 4 }] })
equals('textWithEntities carries the entities', rich.textWithEntities.entities?.length, 1)

// pass-throughs: a null case alone cannot tell one from `return null`
const forwarded = message({
  fwd_from: { _: 'messageFwdHeader', from_id: { _: 'peerUser', user_id: '5' }, date: 1715540000 },
  reactions: { _: 'messageReactions', results: [{ _: 'reactionCount', count: 3, reaction: { _: 'reactionEmoji', emoticon: '👍' } }] },
})
equals('forwardedFrom is the header itself', [forwarded.forwardedFrom?._, forwarded.forwardedFrom?.date], ['messageFwdHeader', 1715540000])
equals('and it is not what senderId reads', forwarded.senderId, null)
equals('reactions is the block itself', [forwarded.reactions?._, forwarded.reactions?.results?.[0].count], ['messageReactions', 3])
equals('and both are null when absent', [incoming.forwardedFrom, incoming.reactions], [null, null])

const service = message({ _: 'messageService', action: { _: 'messageActionChatCreate', title: 'x', users: [] } })
equals('a service message is one, and has no text', [service.isService, service.text], [true, ''])

// messages loaded from the app's own storage arrive as legacy constructors
equals('a legacy message is still a message', [message({ _: 'message_old7' }).isService, message({ _: 'messageService_old2' }).isService], [false, true])

equals('a channel dialog id is negative', message({ peer_id: { _: 'peerChannel', channel_id: '456' } }).dialogId, -1000000000456)
equals('a basic group dialog id is negative', message({ peer_id: { _: 'peerChat', chat_id: '123' } }).dialogId, -123)
equals(
  'the app\'s stock dialog_id annotation does not override the bot api form',
  message({ peer_id: { _: 'peerChannel', channel_id: '456' }, dialog_id: '-456' }).dialogId,
  -1000000000456,
)

// DialogObject.makeEncryptedDialogId(7)
const ENCRYPTED = '4611686018427387911'
const secret = message({ _: 'message_secret', peer_id: { _: 'peerUser', user_id: '4242' }, dialog_id: ENCRYPTED })
equals('a secret message has no marked peer id', secret.dialogId, null)
check('and its raw annotation is still readable', secret.raw.dialog_id === ENCRYPTED, secret.raw.dialog_id)
const secretService = message({ _: 'messageService', peer_id: { _: 'peerUser', user_id: '4242' }, dialog_id: ENCRYPTED })
equals(
  'a secret service message is secret too, though its constructor does not say so',
  secretService.dialogId,
  null,
)

equals(
  'from_id wins',
  message({ from_id: { _: 'peerUser', user_id: '5' }, peer_id: SERVICE_PEER }).senderId,
  5,
)
// from_id is flags.8?Peer and the server omits it in a 1:1 dialog, e.g. the login code
equals('an incoming 1:1 message falls back to the dialog peer', message({ peer_id: SERVICE_PEER }).senderId, 777000)
equals('an outgoing one does not', message({ peer_id: SERVICE_PEER, out: true }).senderId, null)
equals('a channel post with no author has no sender', message({ peer_id: { _: 'peerChannel', channel_id: '99' }, post: true }).senderId, null)
equals('and neither does a group message with no from_id', message({ peer_id: { _: 'peerChat', chat_id: '3' } }).senderId, null)

function withDocument(...attributes) {
  return message({ media: { _: 'messageMediaDocument', document: { _: 'document', id: '9', attributes } } })
}

equals('a sticker', withDocument({ _: 'documentAttributeSticker' }).mediaType, 'sticker')
equals('a webm sticker is a sticker, not a video', withDocument({ _: 'documentAttributeVideo' }, { _: 'documentAttributeSticker' }).mediaType, 'sticker')
equals('a voice message', withDocument({ _: 'documentAttributeAudio', voice: true }).mediaType, 'voice')
equals('a music track', withDocument({ _: 'documentAttributeAudio' }).mediaType, 'music')
equals('a round video', withDocument({ _: 'documentAttributeVideo', round_message: true }).mediaType, 'roundVideo')
equals('a gif', withDocument({ _: 'documentAttributeVideo' }, { _: 'documentAttributeAnimated' }).mediaType, 'gif')
equals('a video', withDocument({ _: 'documentAttributeVideo' }).mediaType, 'video')
equals('a plain file', withDocument({ _: 'documentAttributeFilename', file_name: 'a.zip' }).mediaType, 'document')
equals('a photo', message({ media: { _: 'messageMediaPhoto' } }).mediaType, 'photo')
equals('a legacy photo', message({ media: { _: 'messageMediaPhoto_old' } }).mediaType, 'photo')
equals('a live location is a location', message({ media: { _: 'messageMediaGeoLive' } }).mediaType, 'location')
equals('a giveaway result is a giveaway', message({ media: { _: 'messageMediaGiveawayResults' } }).mediaType, 'giveaway')
equals('an unrecognised media is "other", not null', message({ media: { _: 'messageMediaDice' } }).mediaType, 'other')
equals('a document with no attributes, or none at all, is a plain document', [withDocument().mediaType, message({ media: { _: 'messageMediaDocument', document: { _: 'documentEmpty' } } }).mediaType], ['document', 'document'])
equals('dialogId reads peer_id rather than the dialog_id annotation', message({ peer_id: { _: 'peerUser', user_id: '4242' }, dialog_id: '0' }).dialogId, 4242)
equals('an old secret message or one with no peer has no marked peer id', [message({ _: 'message_secret_old', peer_id: { _: 'peerUser', user_id: '4242' } }).dialogId, message({}).dialogId], [null, null])
equals('messageMediaEmpty is no media at all', [
  message({ media: { _: 'messageMediaEmpty' } }).media,
  message({ media: { _: 'messageMediaEmpty' } }).mediaType,
], [null, null])

const video = withDocument({ _: 'documentAttributeVideo', duration: 187 })
equals('duration comes off the attribute', video.duration, 187)
check('document is the document', video.document !== null && video.document.id === '9', video.document)
equals('a photo has no document and no duration', [message({ media: { _: 'messageMediaPhoto' } }).document, message({ media: { _: 'messageMediaPhoto' } }).duration], [null, null])
equals('an empty document is no document', message({ media: { _: 'messageMediaDocument', document: { _: 'documentEmpty', id: '0' } } }).document, null)

const inTopic = message({ reply_to: { _: 'messageReplyHeader', forum_topic: true, reply_to_top_id: 12, reply_to_msg_id: 30 } })
equals('topicId prefers the top id', [inTopic.topicId, inTopic.replyToMessageId], [12, 30])
const topicRoot = message({ reply_to: { _: 'messageReplyHeader', forum_topic: true, reply_to_msg_id: 12 } })
// telegram uses one reply header for both: a message in a topic carries the topic root id alone
equals('a message posted in a topic is in it, not replying to it', [topicRoot.topicId, topicRoot.replyToMessageId], [12, null])
const plainReply = message({ reply_to: { _: 'messageReplyHeader', reply_to_msg_id: 30 } })
equals('a reply outside a forum has no topic', [plainReply.topicId, plainReply.replyToMessageId], [null, 30])
equals('and a message with no reply header has neither', [incoming.topicId, incoming.replyToMessageId], [null, null])

/** @type {tl.RawMessage} */
const raw = { _: 'message', id: 1, peer_id: { _: 'peerUser', user_id: 4242 }, date: 1715540640, message: 'original' }
const live = new inu.Message(raw)
const before = live.text
raw.message = 'rewritten'
raw.pinned = true
equals('a getter re-reads raw every time', [before, live.text, live.isPinned], ['original', 'rewritten', true])
check('raw is the object it was handed', live.raw === raw)
check('toJSON hands raw back', live.toJSON() === raw)
equals('so JSON.stringify round-trips as a plain TL message', JSON.parse(JSON.stringify(live)), raw)

let swapped = 'no-throw'
try {
  // @ts-expect-error
  live.raw = { _: 'message', id: 2, message: 'impostor' }
} catch (e) {
  swapped = e.constructor.name
}
check('raw cannot be swapped out from under the wrapper', live.text === 'rewritten', `${swapped}, text = ${live.text}`)

// @ts-expect-error
expectThrow('a wrapper needs something to wrap', 'invalid-argument', () => new inu.Message(null))
// @ts-expect-error
expectThrow('and it has to be an object', 'invalid-argument', () => new inu.Message('a message'))

if (typeof inu.invokeRpc !== 'function') {
  console.log('SKIP the live half: no invokeRpc in this context')
  console.log('message test done')
} else {
  inu.invokeRpc({
    _: 'messages.getHistory',
    peer: { _: 'inputPeerSelf' },
    offset_id: 0,
    offset_date: 0,
    add_offset: 0,
    limit: 20,
    max_id: 0,
    min_id: 0,
    hash: '0',
  }).then(
    (history) => {
      const messages = history !== null && 'messages' in history ? history.messages ?? [] : []
      let wrapped = 0
      for (const raw of messages) {
        if (raw._ === 'messageEmpty') continue
        const m = new inu.Message(raw)
        wrapped += 1
        if (m.raw !== raw) return fail('wrapping a live view', 'raw is not the view')
        // the wrapper is lazy, so it answers what the view (and the takeover filter) says
        if (m.text !== (typeof raw.message === 'string' ? raw.message : '')) {
          return fail('text reads through the view', `${m.text} != ${raw.message}`)
        }
        if (m.id !== raw.id) return fail('id reads through the view', `${m.id} != ${raw.id}`)
        if (m.dialogId === null) return fail('a saved-messages message has a dialog id', String(m.id))
        if (m.senderId === null && !m.isService) return fail('a saved-messages message has a sender', String(m.id))
        if (m.mediaType !== null && m.media === null) return fail('a media type without media', m.mediaType)
      }
      if (wrapped === 0) return console.log('SKIP saved messages is empty')
      pass('every saved message wraps and reads through its view', `${wrapped} message(s)`)
    },
    e => fail('invokeRpc messages.getHistory', e),
  ).then(() => console.log('message test done'))
}
