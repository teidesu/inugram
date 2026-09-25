// ==InuPlugin==
// @name         events test
// @description  asserts inu.onNewMessage/onMessageEdited/onMessageDeleted fire once per arrival and agree with the raw update stream
// @grant        onUpdate(new_message,edit_message,delete_message,updateNewMessage,updateNewChannelMessage,updateEditMessage,updateEditChannelMessage,updateDeleteMessages,updateDeleteChannelMessages)
// ==/InuPlugin==

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

let disposedRan = 0
inu.onNewMessage(() => { disposedRan += 1 })()

// each event is cross-checked against the raw updates it was demuxed from. the counters catch a
// double delivery (a re-fed batch, or catch-up repeating the live path)
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

// an update stream has no end: "done" is every event cross-checked at least once
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

// compared a microtask later, so the result does not depend on handler order
function messageEvent(label, kind) {
  return (m, account) => {
    const key = `${account.id}:${kind}:${m.id}`
    if (bump(demuxSeen, key) > 1) return fail(label, `${key} was delivered more than once`)
    let detail = null
    if (!(m instanceof inu.Message)) detail = 'not an inu.Message'
    else if (m.raw === null || typeof m.raw !== 'object') detail = 'the wrapper has no raw'
    else if (m.id !== Number(m.raw.id)) detail = `id ${m.id} does not read through raw`
    else if (typeof m.text !== 'string') detail = `text is a ${typeof m.text}`
    else if (m.dialogId === null) detail = 'no dialog id'
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
  rawDeletes.set(key, update._ === 'updateDeleteChannelMessages' ? -1000000000000 - Number(update.channel_id) : null)
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
