// ==UserScript==
// @name         api test
// @author       teidesu
// @version      1.0
// @description  exercises inu.kv / inu.ui.toast / inu.ui.dialog / inu.onUnload
// @grant        kv
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

check('a key that was never set reads null', inu.kv.get('never-written') === null)
const runs = Number(inu.kv.get('runs') ?? '0') + 1
inu.kv.set('runs', String(runs))
check('a value survives the round trip', inu.kv.get('runs') === String(runs), `run #${runs}`)

inu.kv.insertAll({ a: '1', b: '2' })
check(
  'insertAll writes every entry',
  ['a', 'b', 'runs'].every((k) => inu.kv.keys().includes(k)),
  JSON.stringify(inu.kv.keys()),
)
check('getAll answers the values that were written', inu.kv.getAll().a === '1' && inu.kv.getAll().b === '2')
inu.kv.del('a')
check('del removes exactly one key', !inu.kv.keys().includes('a') && inu.kv.keys().includes('b'))

// 1 MB per plugin, and the error carries the two numbers so a plugin can say how far over it is
const full = expectThrows('the kv quota is enforced', () => inu.kv.set('huge', 'x'.repeat(2 * 1024 * 1024)), 'quota-exceeded')
check(
  'a quota error carries usage and quota',
  typeof full?.usage === 'number' && typeof full?.quota === 'number' && full.usage > full.quota,
  full && `${full.usage}/${full.quota}`,
)
check('nothing was written past the quota', !inu.kv.keys().includes('huge'))

check('has answers for a key that is there and one that is not', inu.kv.has('b') && !inu.kv.has('a'))
const used = inu.kv.usage()
inu.kv.set('measured', 'xxxxx')
check('usage grows by what was written', inu.kv.usage() === used + 'measured'.length + 5, `${used} -> ${inu.kv.usage()}`)
inu.kv.del('measured')

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
inu.onUnload(() => unloaded.push(1))
inu.onUnload(() => {
  unloaded.push(2)
  throw new Error('unload boom (should be logged, not fatal)')
})
inu.onUnload(() => {
  unloaded.push(3)
  check('every unload callback runs in order, past a throwing one', unloaded.join(',') === '1,2,3', unloaded.join(','))
})
