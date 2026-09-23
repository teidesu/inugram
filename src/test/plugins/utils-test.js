// ==InuPlugin==
// @name         utils test
// @description  asserts inu.utils: the base64/hex codecs, the formatters, and the peer id arithmetic
// ==/InuPlugin==

const { utils } = inu
const { peers } = utils

const bytes = new Uint8Array([0, 1, 15, 16, 127, 128, 255])
equals('toBase64 is standard padded base64', utils.toBase64(bytes), 'AAEPEH+A/w==')
equals('toHex is lowercase', utils.toHex(bytes), '00010f107f80ff')
equals('fromBase64 round-trips', Array.from(utils.fromBase64(utils.toBase64(bytes))), Array.from(bytes))
equals('fromHex round-trips', Array.from(utils.fromHex(utils.toHex(bytes))), Array.from(bytes))
check('fromBase64 hands back a real Uint8Array', utils.fromBase64('aGk=') instanceof Uint8Array)
equals('fromHex takes uppercase too', Array.from(utils.fromHex('DEADBEEF')), [222, 173, 190, 239])
equals('the empty cases are empty', [utils.toHex(new Uint8Array(0)), utils.fromBase64('').length], ['', 0])
equals('fromBase64 takes unpadded input', Array.from(utils.fromBase64('aGk')), [104, 105])

expectThrow('fromBase64 refuses what is not base64', 'invalid-argument', () => utils.fromBase64('!!!!'))
expectThrow('fromHex refuses an odd digit count', 'invalid-argument', () => utils.fromHex('abc'))
expectThrow('fromHex refuses non-hex digits', 'invalid-argument', () => utils.fromHex('zz'))

// @ts-expect-error
expectThrow('a codec handed something that is not bytes throws a TypeError', TypeError, () => utils.toHex([1, 2, 3]))

// a byte field stringifies as { $inuBytes }; this reads the bytes back out of one
const wrapper = JSON.parse(JSON.stringify({ bytes: { $inuBytes: utils.toBase64(bytes) } }))
equals('a $inuBytes wrapper decodes back', Array.from(utils.fromBase64(wrapper.bytes.$inuBytes)), Array.from(bytes))

// the exact text follows telegram's active language and its translations
const at = new Date(2024, 4, 12, 19, 4, 30)
const unix = Math.floor(at.getTime() / 1000)
check('formatDate date uses Telegram text', utils.formatDate(unix, 'date').length > 0)
check('formatDate time uses Telegram text', utils.formatDate(unix, 'time').length > 0)
check('formatDate dateTime uses Telegram text', utils.formatDate(unix, 'dateTime').length > 0)
check('formatDate defaults to dateTime', utils.formatDate(unix).length > 0)

const now = new Date()
const daysAgo = (days, hour) =>
  Math.floor(new Date(now.getFullYear(), now.getMonth(), now.getDate() - days, hour, 5).getTime() / 1000)
check('a relative date uses dialog-row text', utils.formatDate(daysAgo(0, 9), 'relative').length > 0)

check('formatNumber uses Telegram grouping', utils.formatNumber(1234567).length > 0)
check('formatNumber formats a signed value', utils.formatNumber(-1234567).length > 0)
check('compact uses Telegram abbreviations', utils.formatNumber(1200000, { compact: true }).length > 0)

check('formatFileSize uses Telegram text', utils.formatFileSize(4404019).length > 0)

check('formatDuration uses Telegram text', utils.formatDuration(3764).length > 0)

// @ts-expect-error
expectThrow('formatDate refuses a non-number', 'invalid-argument', () => utils.formatDate('yesterday'))
// @ts-expect-error
expectThrow('formatDate refuses an unknown style', 'invalid-argument', () => utils.formatDate(unix, 'fuzzy'))
// @ts-expect-error
expectThrow('formatNumber refuses a non-number', 'invalid-argument', () => utils.formatNumber('lots'))
expectThrow('formatNumber refuses a fraction', 'invalid-argument', () => utils.formatNumber(1.5))
expectThrow('formatDuration refuses a negative', 'invalid-argument', () => utils.formatDuration(-1))

// every one of these has a Number() and would otherwise format something plausible
/** @type {[string, (value: any) => string][]} */
const formatters = [
  ['formatDate', v => utils.formatDate(v)],
  ['formatNumber', v => utils.formatNumber(v)],
  ['formatFileSize', v => utils.formatFileSize(v)],
  ['formatDuration', v => utils.formatDuration(v)],
]
for (const [what, format] of formatters) {
  for (const bad of [null, undefined, true, '42', [], {}, NaN, Infinity]) {
    expectThrow(`${what} refuses ${JSON.stringify(bad) ?? String(bad)}`, 'invalid-argument', () => format(bad))
  }
}

/** @type {tl.RawChatPhotoEmpty} */
const EMPTY_PHOTO = { _: 'chatPhotoEmpty' }

equals('toDialogId of a user', peers.toDialogId({ _: 'peerUser', user_id: 777000 }), 777000)
equals('toDialogId of a basic group', peers.toDialogId({ _: 'peerChat', chat_id: 123 }), -123)
equals('toDialogId of a channel', peers.toDialogId({ _: 'peerChannel', channel_id: 456 }), -456)
equals('toDialogId of an InputPeer', peers.toDialogId({ _: 'inputPeerChannel', channel_id: 456, access_hash: '1' }), -456)
equals('toDialogId of a User', peers.toDialogId({ _: 'user', id: 42 }), 42)
equals('toDialogId of a Chat', peers.toDialogId({ _: 'channelForbidden', id: 7, access_hash: '1', title: 'x' }), -7)
// @ts-expect-error
equals('toDialogId still reads an id written as a decimal string', peers.toDialogId({ _: 'peerUser', user_id: '777000' }), 777000)

equals('parseDialogId of a user', peers.parseDialogId(777000), { type: 'user', id: 777000 })
equals('parseDialogId of a chat', peers.parseDialogId(-456), { type: 'chat', id: 456 })
equals('parseDialogId takes the string form', peers.parseDialogId('-456'), { type: 'chat', id: 456 })

equals(
  'toInputPeer of a user',
  peers.toInputPeer({ _: 'user', id: 42, access_hash: '99' }),
  { _: 'inputPeerUser', user_id: 42, access_hash: '99' },
)
equals('toInputPeer of yourself', peers.toInputPeer({ _: 'user', id: 1, self: true }), { _: 'inputPeerSelf' })
equals(
  'toInputPeer of a basic group',
  peers.toInputPeer({ _: 'chat', id: 123, title: 'x', photo: EMPTY_PHOTO, participants_count: 1, date: 0, version: 0 }),
  { _: 'inputPeerChat', chat_id: 123 },
)
equals(
  'toInputPeer of a channel',
  peers.toInputPeer({ _: 'channel', id: 456, access_hash: '11', title: 'x', photo: EMPTY_PHOTO, date: 0 }),
  { _: 'inputPeerChannel', channel_id: 456, access_hash: '11' },
)

equals('toBotApiId offsets channels and only channels', [
  peers.toBotApiId({ _: 'peerUser', user_id: 42 }),
  peers.toBotApiId({ _: 'peerChat', chat_id: 123 }),
  peers.toBotApiId({ _: 'peerChannel', channel_id: 456 }),
], [42, -123, -1000000000456])
equals('fromBotApiId undoes it', [
  peers.fromBotApiId(42),
  peers.fromBotApiId(-123),
  peers.fromBotApiId(-1000000000456),
], [42, -123, -456])

/** @type {tl.TypePeer[]} */
const roundTrip = [
  { _: 'peerUser', user_id: 42 },
  { _: 'peerChat', chat_id: 123 },
  { _: 'peerChannel', channel_id: 1234567890 },
]
for (const peer of roundTrip) {
  const trip = peers.fromBotApiId(peers.toBotApiId(peer))
  check(`${peer._} survives the bot api round trip`, trip === peers.toDialogId(peer), `${trip} vs ${peers.toDialogId(peer)}`)
}

expectThrow('toDialogId refuses inputPeerSelf, which names no id', 'invalid-argument', () => peers.toDialogId({ _: 'inputPeerSelf' }))
// @ts-expect-error
expectThrow('toDialogId refuses a plain number', 'invalid-argument', () => peers.toDialogId(42))
expectThrow('parseDialogId refuses a username', 'invalid-argument', () => peers.parseDialogId('me'))
// @ts-expect-error
expectThrow('toInputPeer refuses a Peer, which carries no access_hash', 'invalid-argument', () => peers.toInputPeer({ _: 'peerUser', user_id: 1 }))

// @ts-expect-error
expectThrow('a constructor named "constructor" is not a peer', 'invalid-argument', () => peers.toDialogId({ _: 'constructor' }))

/** @type {tl.TypeMessageEntity[]} */
const BOLD_HI = [{ _: 'messageEntityBold', offset: 0, length: 2 }]

equals('md parses a tagged template', utils.md`**bold**`, {
  text: 'bold',
  entities: [{ _: 'messageEntityBold', offset: 0, length: 4 }],
})
equals('md writes an interpolated string as text', utils.md`${'**x**'}`.text, '**x**')
const inner = utils.md`__b__`
equals('md carries the entities of an interpolated text', utils.md`**a ${inner}**`, {
  text: 'a b',
  entities: [
    { _: 'messageEntityItalic', offset: 2, length: 1 },
    { _: 'messageEntityBold', offset: 0, length: 3 },
  ],
})
equals('html parses tags', utils.html`<b>hi</b>`.entities, BOLD_HI)
equals('html collapses whitespace and thtml keeps it', [utils.html`a  b`.text, utils.thtml`a  b`.text], ['a b', 'a  b'])
equals('md.unparse writes entities back out', utils.md.unparse({ text: 'hi there', entities: BOLD_HI }), '**hi** there')
equals('html.escape escapes markup', utils.html.escape('<a>'), '&lt;a&gt;')
// @ts-expect-error
expectThrow('a format refuses what is not a string', 'invalid-argument', () => utils.md(42))

equals('joinTextWithEntities shifts each part into place', utils.joinTextWithEntities([utils.md`**hi**`, 'x'], ', '), {
  text: 'hi, x',
  entities: BOLD_HI,
})
equals('joinTextWithEntities defaults to no delimiter', utils.joinTextWithEntities(['a', 'b']).text, 'ab')
// @ts-expect-error
expectThrow('joinTextWithEntities refuses a part that is not a text', 'invalid-argument', () => utils.joinTextWithEntities([7]))

console.log('utils test done')
