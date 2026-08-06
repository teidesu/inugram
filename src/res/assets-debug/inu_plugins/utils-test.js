// ==UserScript==
// @name         utils test
// @author       teidesu
// @version      1.0
// @description  asserts inu.utils: the base64/hex codecs, the formatters, and the peer id arithmetic
// @plugin-api   1
// @platform     android
// ==/UserScript==

function pass(label, detail) {
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function equals(label, actual, expected) {
  const same = JSON.stringify(actual) === JSON.stringify(expected)
  check(label, same, same ? JSON.stringify(actual) : `${JSON.stringify(actual)} != ${JSON.stringify(expected)}`)
}

function expectThrow(label, code, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  check(label, error instanceof inu.PluginError && error.code === code, `${error.name}: ${error.code}`)
}

const { utils } = inu
const { peers } = utils

// -- codecs --

const bytes = new Uint8Array([0, 1, 15, 16, 127, 128, 255])
equals('toBase64 is standard padded base64', utils.toBase64(bytes), 'AAEPEH+A/w==')
equals('toHex is lowercase', utils.toHex(bytes), '00010f107f80ff')
equals('fromBase64 round-trips', Array.from(utils.fromBase64(utils.toBase64(bytes))), Array.from(bytes))
equals('fromHex round-trips', Array.from(utils.fromHex(utils.toHex(bytes))), Array.from(bytes))
check('fromBase64 hands back a real Uint8Array', utils.fromBase64('aGk=') instanceof Uint8Array)
equals('fromHex takes uppercase too', Array.from(utils.fromHex('DEADBEEF')), [222, 173, 190, 239])
equals('the empty cases are empty', [utils.toHex(new Uint8Array(0)), utils.fromBase64('').length], ['', 0])
// unpadded is what a great deal of the web hands out
equals('fromBase64 takes unpadded input', Array.from(utils.fromBase64('aGk')), [104, 105])

expectThrow('fromBase64 refuses what is not base64', 'invalid-argument', () => utils.fromBase64('!!!!'))
expectThrow('fromHex refuses an odd digit count', 'invalid-argument', () => utils.fromHex('abc'))
expectThrow('fromHex refuses non-hex digits', 'invalid-argument', () => utils.fromHex('zz'))

let wrongType = 'no-throw'
try {
  // @ts-expect-error
  utils.toHex([1, 2, 3])
} catch (e) {
  wrongType = e.constructor.name
}
check('a codec handed something that is not bytes throws a TypeError', wrongType === 'TypeError', wrongType)

// the pair that closes the loop the TLObject doc describes: a byte field stringifies as
// { $inuBytes }, and this is what reads the bytes back out of one
const wrapper = JSON.parse(JSON.stringify({ bytes: { $inuBytes: utils.toBase64(bytes) } }))
equals('a $inuBytes wrapper decodes back', Array.from(utils.fromBase64(wrapper.bytes.$inuBytes)), Array.from(bytes))

// -- formatting --

// built through Date so the expectation follows the device's own timezone, the way the output does
const at = new Date(2024, 4, 12, 19, 4, 30)
const unix = Math.floor(at.getTime() / 1000)
equals('formatDate date', utils.formatDate(unix, 'date'), '12 May 2024')
equals('formatDate time', utils.formatDate(unix, 'time'), '19:04')
equals('formatDate dateTime', utils.formatDate(unix, 'dateTime'), '12 May 2024, 19:04')
equals('formatDate defaults to dateTime', utils.formatDate(unix), '12 May 2024, 19:04')

const now = new Date()
const daysAgo = (days, hour) =>
  Math.floor(new Date(now.getFullYear(), now.getMonth(), now.getDate() - days, hour, 5).getTime() / 1000)
const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
equals('a relative time today is a clock', utils.formatDate(daysAgo(0, 9), 'relative'), '09:05')
equals(
  'a relative time this week is a weekday',
  utils.formatDate(daysAgo(2, 9), 'relative'),
  weekdays[new Date(daysAgo(2, 9) * 1000).getDay()],
)
equals('a relative time older than a week is a date', utils.formatDate(daysAgo(30, 9), 'relative'), utils.formatDate(daysAgo(30, 9), 'date'))

equals('formatNumber groups', utils.formatNumber(1234567), '1 234 567')
equals('formatNumber keeps a fraction', utils.formatNumber(1234.5), '1 234.5')
equals('formatNumber keeps the sign', utils.formatNumber(-1234567), '-1 234 567')
equals('formatNumber leaves small numbers alone', utils.formatNumber(999), '999')
equals('compact truncates rather than rounds', utils.formatNumber(1999, { compact: true }), '1.9K')
equals('compact drops a zero tenth', utils.formatNumber(1000000, { compact: true }), '1M')
equals('compact reads 1.2M', utils.formatNumber(1200000, { compact: true }), '1.2M')
equals('compact reaches billions', utils.formatNumber(2500000000, { compact: true }), '2.5B')

equals('formatFileSize bytes', utils.formatFileSize(512), '512 B')
equals('formatFileSize 4.2 MB', utils.formatFileSize(4404019), '4.2 MB')
equals('formatFileSize the next unit up', utils.formatFileSize(1073741824), '1.0 GB')

equals('formatDuration under an hour', utils.formatDuration(187), '3:07')
equals('formatDuration over an hour', utils.formatDuration(3764), '1:02:44')
equals('formatDuration zero', utils.formatDuration(0), '0:00')

// @ts-expect-error
expectThrow('formatDate refuses a non-number', 'invalid-argument', () => utils.formatDate('yesterday'))
// @ts-expect-error
expectThrow('formatDate refuses an unknown style', 'invalid-argument', () => utils.formatDate(unix, 'fuzzy'))
// @ts-expect-error
expectThrow('formatNumber refuses a non-number', 'invalid-argument', () => utils.formatNumber('lots'))

// a number and nothing coercible to one: every one of these has a Number() and would otherwise
// format something plausible for an argument the caller never meant
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

// -- peers --

/** @type {tl.RawChatPhotoEmpty} */
const EMPTY_PHOTO = { _: 'chatPhotoEmpty' }

equals('toDialogId of a user', peers.toDialogId({ _: 'peerUser', user_id: '777000' }), 777000)
equals('toDialogId of a basic group', peers.toDialogId({ _: 'peerChat', chat_id: '123' }), -123)
equals('toDialogId of a channel', peers.toDialogId({ _: 'peerChannel', channel_id: '456' }), -456)
equals('toDialogId of an InputPeer', peers.toDialogId({ _: 'inputPeerChannel', channel_id: '456', access_hash: '1' }), -456)
equals('toDialogId of a User', peers.toDialogId({ _: 'user', id: '42' }), 42)
equals('toDialogId of a Chat', peers.toDialogId({ _: 'channelForbidden', id: '7', access_hash: '1', title: 'x' }), -7)

equals('parseDialogId of a user', peers.parseDialogId(777000), { type: 'user', id: 777000 })
equals('parseDialogId of a chat', peers.parseDialogId(-456), { type: 'chat', id: 456 })
equals('parseDialogId takes the string form', peers.parseDialogId('-456'), { type: 'chat', id: 456 })

equals(
  'toInputPeer of a user',
  peers.toInputPeer({ _: 'user', id: '42', access_hash: '99' }),
  { _: 'inputPeerUser', user_id: '42', access_hash: '99' },
)
equals('toInputPeer of yourself', peers.toInputPeer({ _: 'user', id: '1', self: true }), { _: 'inputPeerSelf' })
equals(
  'toInputPeer of a basic group',
  peers.toInputPeer({ _: 'chat', id: '123', title: 'x', photo: EMPTY_PHOTO, participants_count: 1, date: 0, version: 0 }),
  { _: 'inputPeerChat', chat_id: '123' },
)
equals(
  'toInputPeer of a channel',
  peers.toInputPeer({ _: 'channel', id: '456', access_hash: '11', title: 'x', photo: EMPTY_PHOTO, date: 0 }),
  { _: 'inputPeerChannel', channel_id: '456', access_hash: '11' },
)

equals('toBotApiId offsets channels and only channels', [
  peers.toBotApiId({ _: 'peerUser', user_id: '42' }),
  peers.toBotApiId({ _: 'peerChat', chat_id: '123' }),
  peers.toBotApiId({ _: 'peerChannel', channel_id: '456' }),
], [42, -123, -1000000000456])
equals('fromBotApiId undoes it', [
  peers.fromBotApiId(42),
  peers.fromBotApiId(-123),
  peers.fromBotApiId(-1000000000456),
], [42, -123, -456])

/** @type {tl.TypePeer[]} */
const roundTrip = [
  { _: 'peerUser', user_id: '42' },
  { _: 'peerChat', chat_id: '123' },
  { _: 'peerChannel', channel_id: '1234567890' },
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
expectThrow('toInputPeer refuses a Peer, which carries no access_hash', 'invalid-argument', () => peers.toInputPeer({ _: 'peerUser', user_id: '1' }))

// a lookup keyed on a TL name must not answer for Object.prototype's own members
// @ts-expect-error
expectThrow('a constructor named "constructor" is not a peer', 'invalid-argument', () => peers.toDialogId({ _: 'constructor' }))

console.log('utils test done')
