// ==InuPlugin==
// @name         update intercept test
// @author       teidesu
// @version      1.0
// @description  asserts inu.interceptUpdate narrows to its constructor list, rewrites in place and that a 'drop' verdict is what the host is actually told
// @grant        interceptUpdate(updateNewMessage,updateEditMessage)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

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

check('interceptUpdate exists', typeof inu.interceptUpdate === 'function')
check('the observation form is still its own api', typeof inu.onUpdate === 'function')

const disposer = inu.interceptUpdate('updateNewMessage', () => 'deliver')
check('registering hands back a disposer', typeof disposer === 'function')
disposer()
disposer()
pass('disposing twice is a no-op')

let refused = 'no-throw'
try {
  // @ts-expect-error
  inu.interceptUpdate('updateNewMessage', 'not a callback')
} catch (e) {
  refused = e.constructor.name
}
check('a middleware has to be a function', refused === 'TypeError', refused)

let ungranted = 'no-throw'
try {
  inu.interceptUpdate('updateDeleteMessages', () => 'deliver')
} catch (e) {
  ungranted = e.code
}
check('a constructor outside the grant is refused', ungranted === 'not-granted', ungranted)

// registered and disposed before anything arrived; the counter below re-asserts it never ran
let neverRan = 0
inu.interceptUpdate('updateNewMessage', () => { neverRan += 1; return 'drop' })()

// -- the chain --
//
// the harness pushes three updates: two `updateNewMessage` (the second one asking to be dropped) and
// one `updateEditMessage`, then hands back the verdicts the *host* recorded. every assertion is
// against that list rather than against what this file returned, so a chain that swallowed a verdict
// or answered the wrong one fails here.

const seen = []
// read back off `update` after the assignment, never off the local that produced it, so deleting
// the rewrite below is visible here
const rewritten = []

inu.interceptUpdate(['updateNewMessage', 'updateEditMessage'], ({ update, account }) => {
  const arrived = update.message.message
  seen.push({
    type: update._,
    id: update.message.id,
    text: arrived,
    account: typeof account === 'object' && account !== null && typeof account.id === 'number',
  })
  if (arrived === 'drop me') return 'drop'
  // rewritten in place: what the app applies is this object
  update.message.message = `[${arrived}]`
  rewritten.push(update.message.message)
  return 'deliver'
})

globalThis.__report = (verdicts) => {
  check('the disposed middleware never ran', neverRan === 0, String(neverRan))
  check('every named update reached the middleware', seen.length === 3, String(seen.length))
  check('the account handle comes with it', seen.every(s => s.account))
  check(
    'both constructors reach the wide registration',
    seen.map(s => s.type).join(',') === 'updateNewMessage,updateNewMessage,updateEditMessage',
    seen.map(s => s.type).join(','),
  )
  check('a middleware is handed the update as it arrived', seen[0].text === 'hi', seen[0].text)
  check(
    'a rewrite is what the update reads back as afterwards',
    rewritten.join(',') === '[hi],[edited]',
    rewritten.join(','),
  )

  check('the host got one verdict per dispatch', verdicts.length === 3, JSON.stringify(verdicts))
  check('an ordinary update is delivered', verdicts[0][0] === 1 && verdicts[0][1] === true, JSON.stringify(verdicts[0]))
  check('a dropped update is reported as dropped', verdicts[1][0] === 2 && verdicts[1][1] === false, JSON.stringify(verdicts[1]))
  check('a drop does not spill onto the next update', verdicts[2][1] === true, JSON.stringify(verdicts[2]))

  console.log('update intercept test done')
}
