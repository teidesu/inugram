// ==UserScript==
// @name         events test
// @author       teidesu
// @version      1.0
// @description  asserts inu.onNewMessage/onMessageEdited/onMessageDeleted fire once per arrival and agree with the raw update stream
// @grant        onUpdate(new_message,edit_message,delete_message,updateNewMessage,updateNewChannelMessage,updateEditMessage,updateEditChannelMessage,updateDeleteMessages,updateDeleteChannelMessages)
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

// -- the surface --

check(
  'the three demuxed events exist',
  ['onNewMessage', 'onMessageEdited', 'onMessageDeleted'].every(name => typeof inu[name] === 'function'),
)
check('the raw stream is still its own api', typeof inu.onUpdate === 'function')

const disposer = inu.onNewMessage(() => {})
check('registering hands back a disposer', typeof disposer === 'function')
disposer()
disposer()
pass('disposing twice is a no-op')

let refused = 'no-throw'
try {
  // @ts-expect-error
  inu.onMessageDeleted('not a callback')
} catch (e) {
  refused = e.constructor.name
}
check('a handler has to be a function', refused === 'TypeError', refused)

// registered and disposed before anything could arrive: every check below re-asserts it never ran
let disposedRan = 0
inu.onNewMessage(() => { disposedRan += 1 })()

// -- the streams --
//
// every event is cross-checked against the raw updates it was demuxed out of, which is the part
// that can actually fail: the raw handlers re-derive the ids and the dialog id independently, and
// the counters catch an arrival delivered twice (a re-fed batch, or the difference catch-up
// repeating what the live path already brought in).

const rawSeen = new Map()
const demuxSeen = new Map()
const rawDeletes = new Map()

function bump(counts, key) {
  const seen = (counts.get(key) ?? 0) + 1
  counts.set(key, seen)
  return seen
}

// a TL vector is array-like rather than an Array
function readIds(value) {
  const ids = []
  for (let i = 0; i < value.length; i++) ids.push(Number(value[i]))
  return ids
}

// there is no end to an update stream, so "done" is every one of the three events having been
// cross-checked at least once. it is deliberately not printed at the end of this file: everything
// below arms a handler, and a completion line the assertions cannot precede would say nothing
const verified = new Set()

function verifyOnce(label, key, kind, detail) {
  const raw = rawSeen.get(key) ?? 0
  const demuxed = demuxSeen.get(key) ?? 0
  if (raw !== 1 || demuxed !== 1) fail(label, `${key}: raw x${raw}, demuxed x${demuxed}`)
  else if (detail !== null) fail(label, `${key}: ${detail}`)
  else if (disposedRan !== 0) fail(label, 'a disposed handler fired')
  else {
    pass(label, key)
    verified.add(kind)
    if (verified.size === 3) console.log('events test done')
  }
  rawSeen.delete(key)
  demuxSeen.delete(key)
}

// the comparison runs a microtask later so it does not depend on which of the two handlers the
// dispatch happens to walk first: every handler of one arrival has run by then, and nothing else has
function messageEvent(label, kind) {
  return (m, account) => {
    const key = `${account.id}:${kind}:${m.id}`
    if (bump(demuxSeen, key) > 1) return fail(label, `${key} was delivered more than once`)
    let detail = null
    if (!(m instanceof inu.Message)) detail = 'not an inu.Message'
    else if (m.raw === null || typeof m.raw !== 'object') detail = 'the wrapper has no raw'
    else if (m.id !== Number(m.raw.id)) detail = `id ${m.id} does not read through raw`
    else if (typeof m.text !== 'string') detail = `text is a ${typeof m.text}`
    else if (m.dialogId === null && !m.isSecret) detail = 'no dialog id'
    else if (typeof account.id !== 'number') detail = 'no account'
    queueMicrotask(() => verifyOnce(label, key, kind, detail))
  }
}

inu.onUpdate(['updateNewMessage', 'updateNewChannelMessage'], (update, account) => {
  bump(rawSeen, `${account.id}:new:${update.message.id}`)
})
inu.onUpdate(['updateEditMessage', 'updateEditChannelMessage'], (update, account) => {
  bump(rawSeen, `${account.id}:edited:${update.message.id}`)
})
inu.onUpdate(['updateDeleteMessages', 'updateDeleteChannelMessages'], (update, account) => {
  const ids = readIds(update.messages)
  const key = `${account.id}:deleted:${ids.join(',')}`
  bump(rawSeen, key)
  rawDeletes.set(key, update._ === 'updateDeleteChannelMessages' ? -Number(update.channel_id) : null)
})

inu.onNewMessage(messageEvent('a new message reaches both streams once', 'new'))
inu.onMessageEdited(messageEvent('an edit reaches both streams once', 'edited'))

inu.onMessageDeleted((dialogId, ids, account) => {
  const label = 'a deletion reaches both streams once'
  const key = `${account.id}:deleted:${ids.join(',')}`
  if (bump(demuxSeen, key) > 1) return fail(label, `${key} was delivered more than once`)
  let detail = null
  if (!Array.isArray(ids) || ids.length === 0) detail = 'no message ids'
  else if (!ids.every(Number.isSafeInteger)) detail = `ids are not integers: ${ids}`
  else if (dialogId !== rawDeletes.get(key)) detail = `dialog id ${dialogId} != ${rawDeletes.get(key)}`
  queueMicrotask(() => verifyOnce(label, key, 'deleted', detail))
})

console.log('events test armed')
