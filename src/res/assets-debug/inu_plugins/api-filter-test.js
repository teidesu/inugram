// ==InuPlugin==
// @name         api filter test
// @author       teidesu
// @version      1.0
// @description  asserts config.autologin_token is stripped, redaction evidence is sealed, and service login codes are redacted
// @grant        invokeRpc(help.getConfig)
// @grant        invokeRpc(messages.getHistory)
// @grant        onUpdate(updateNewMessage)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

const CODE_RE = /[0-9-]{5,}/
const SERVICE_IDS = ['777000', '489000']

function pass(label, detail) {
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  console.error(`FAIL ${label}: ${detail}`)
}

// every read-side assertion below is satisfied by a build with the filter switched off entirely,
// so each half also asserts a refusal that only a live filter produces
// three halves, each asynchronous, and "done" is what says every one of them reported. a marker
// at the end of the file would print before a single response had arrived
let halvesLeft = 3
function halfDone() {
  if (--halvesLeft === 0) console.log('api filter test done')
}

function refuses(write) {
  try {
    write()
  } catch (e) {
    return e instanceof inu.PluginError && e.code === 'forbidden'
  }
  return false
}

// a *stripped* field is the other refusal, and common.d.ts says which: "assigning one is refused
// the way assigning a field the type does not have is", so an ordinary error rather than the
// `forbidden` PluginError a sealed field earns
function refusesAsAbsent(write) {
  try {
    write()
  } catch (e) {
    return !(e instanceof inu.PluginError)
  }
  return false
}

inu.invokeRpc({ _: 'help.getConfig' }).then(
  (config) => {
    const label = 'config.autologin_token is stripped'
    if (config === null) return fail(label, 'the app completed the request with no response')
    if ('autologin_token' in config) return fail(label, '`in` says the field is there')
    if (Object.keys(config).includes('autologin_token')) return fail(label, 'Object.keys lists it')
    if (config.autologin_token !== null) return fail(label, `reads back ${config.autologin_token}`)
    // `toJSON` is on every view the bridge hands over, but the generated tl typings don't carry it
    if ('autologin_token' in /** @type {any} */ (config).toJSON()) return fail(label, 'the toJSON() snapshot carries it')
    // the reads above all hold on a server that simply sent no token, so this is the half that
    // distinguishes "stripped" from "was never there"
    if (!refusesAsAbsent(() => { config.autologin_token = 'x' })) {
      return fail(label, 'assigning it was not refused, so nothing is filtering')
    }
    pass(label, `${Object.keys(config).length} other field(s) still readable`)
    halfDone()
  },
  (e) => fail('invokeRpc help.getConfig', e),
)

inu.invokeRpc({
  _: 'messages.getHistory',
  peer: { _: 'inputPeerSelf' },
  offset_id: 0,
  offset_date: 0,
  add_offset: 0,
  limit: 1,
  max_id: 0,
  min_id: 0,
  hash: '0',
}).then(
  (history) => {
    const label = 'the fields redaction is keyed on are sealed'
    const messages = history !== null && 'messages' in history ? history.messages ?? [] : []
    const message = messages[0]
    if (message === undefined) {
      console.log(`SKIP ${label}: saved messages is empty`)
      return halfDone()
    }
    // an invokeRpc response is writable, so a refusal here is the filter and nothing else
    for (const field of ['from_id', 'peer_id', 'fwd_from', 'out']) {
      if (!refuses(() => { message[field] = null })) return fail(label, `${field} accepted a write`)
    }
    const peer = message.peer_id
    if (peer !== null && typeof peer === 'object' && !refuses(() => { peer.user_id = '0' })) {
      return fail(label, 'the peer behind peer_id came back writable')
    }
    pass(label)
    halfDone()
  },
  (e) => fail('invokeRpc messages.getHistory', e),
)

function findServiceSender(message) {
  // mirrors ApiFilter.isServiceMessage, including fwd_from and the out rule: an oracle keyed on a
  // narrower predicate than the filter's is blind to exactly the messages the filter misses
  const isService = (p) =>
    // long fields cross as strings
    p !== null && typeof p === 'object' && p._ === 'peerUser' && SERVICE_IDS.includes(String(p.user_id))
  if (isService(message.from_id)) return true
  if (message.fwd_from !== null && typeof message.fwd_from === 'object' && isService(message.fwd_from.from_id)) return true
  return !message.out && isService(message.peer_id)
}

let seen = 0
inu.onUpdate('updateNewMessage', (update) => {
  const message = update.message
  if (message === null || typeof message !== 'object') return
  if (!findServiceSender(message)) return
  const text = message.message
  if (typeof text !== 'string') return

  seen += 1
  const label = `service message #${seen} carries no code-shaped run`
  const hit = CODE_RE.exec(text)
  if (hit === null) pass(label)
  else fail(label, `matched '${hit[0]}' at offset ${hit.index}`)
  if (seen === 1) halfDone()
})

console.log('api-filter-test armed; the redaction half reports once a service message arrives')
