// ==InuPlugin==
// @name         api test
// @author       teidesu
// @version      1.0
// @description  exercises localStorage / inu.ui.toast / inu.ui.dialog / inu.onUnload
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

function expectThrows(label, body, code) {
  try {
    body()
  } catch (e) {
    check(label, e instanceof inu.PluginError && e.code === code, e && e.code)
    return e
  }
  fail(label, `expected ${code}, nothing was thrown`)
  return undefined
}

check('a key that was never set reads null', localStorage.getItem('never-written') === null)
const runs = Number(localStorage.getItem('runs') ?? '0') + 1
localStorage.setItem('runs', String(runs))
check('a value survives the round trip', localStorage.getItem('runs') === String(runs), `run #${runs}`)

localStorage.setItems({ a: '1', b: '2' })
check(
  'setItems writes every entry',
  ['a', 'b', 'runs'].every((k) => Object.keys(localStorage).includes(k)),
  JSON.stringify(Object.keys(localStorage)),
)
check('a stored item reads as a property', localStorage.a === '1' && localStorage['b'] === '2')
localStorage.removeItem('a')
check('removeItem removes exactly one key', !('a' in localStorage) && 'b' in localStorage)

localStorage.c = 3
const assigned = localStorage.getItem('c')
delete localStorage.c
check('assigning and deleting a property stores and removes an item', assigned === '3' && localStorage.getItem('c') === null)

let full
try {
  localStorage.setItem('huge', 'x'.repeat(2 * 1024 * 1024))
} catch (e) {
  full = e
}
check('the 1 MB quota is enforced', full instanceof DOMException && full.name === 'QuotaExceededError', full && full.name)
check('nothing was written past the quota', localStorage.getItem('huge') === null)

const keys = Object.keys(localStorage)
check(
  'length and key() walk the same keys',
  localStorage.length === keys.length && keys.every((k, i) => localStorage.key(i) === k),
  `${localStorage.length} vs ${JSON.stringify(keys)}`,
)

localStorage.setItem('getItem', 'x')
check(
  'an item named like a member leaves the member alone',
  typeof localStorage.getItem === 'function' && localStorage.getItem('getItem') === 'x',
)
localStorage.removeItem('getItem')

inu.ui.toast(`api-test loaded (run #${runs})`)

// the host reads title/message/buttons off a JSON snapshot, and stringify drops the callbacks a
// declarative UIElement hangs off itself, so only `inu.android.nativeView` - a handle id and
// nothing else - survives the crossing, and the rest are refused rather than silently dropped
expectThrows(
  'a declarative element cannot be a dialog body',
  () => inu.ui.dialog({ title: 'api test', body: inu.ui.header('nope') }),
  'unsupported',
)

inu.ui
  .dialog({
    title: 'api test',
    message: 'pick a button',
    positive: 'yes',
    negative: 'no',
    neutral: 'meh',
  })
  .then((result) => {
    check('a dialog settles with the button the user pressed', typeof result === 'string', result)
    inu.ui.toast(`dialog: ${result}`)
    // the last half to report: the dialog only settles once the user has answered it
    console.log('api test done')
  })

const unloaded = []
inu.onUnload(() => { unloaded.push(1) })
inu.onUnload(() => {
  unloaded.push(2)
  throw new Error('unload boom (should be logged, not fatal)')
})
inu.onUnload(() => {
  unloaded.push(3)
  check('every unload callback runs in order, past a throwing one', unloaded.join(',') === '1,2,3', unloaded.join(','))
})
