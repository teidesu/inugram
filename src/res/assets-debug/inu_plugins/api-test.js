// ==UserScript==
// @name         api test
// @author       teidesu
// @namespace    inugram.dev
// @version      1.0
// @description  exercises inu.kv / inu.ui.toast / inu.ui.dialog / inu.onUnload
// @grant        inu.kv
// @plugin-api   1
// @platform     android
// ==/UserScript==

const runs = Number(inu.kv.get('runs') ?? '0') + 1
inu.kv.set('runs', String(runs))
console.log('kv: run #' + runs)

inu.kv.insertAll({ a: '1', b: '2' })
console.log('kv keys =', JSON.stringify(inu.kv.keys()))
console.log('kv getAll =', JSON.stringify(inu.kv.getAll()))
inu.kv.del('a')
console.log('kv after del =', JSON.stringify(inu.kv.keys()))

try {
  inu.kv.set('huge', 'x'.repeat(2 * 1024 * 1024))
  console.error('quota NOT enforced?!')
} catch (e) {
  console.log('kv quota works:', e.message)
}

inu.ui.toast('api-test loaded (run #' + runs + ')')

inu.ui
  .dialog({
    title: 'api test',
    message: 'pick a button',
    positive: 'yes',
    negative: 'no',
    neutral: 'meh',
  })
  .then((result) => {
    console.log('dialog result =', result)
    inu.ui.toast('dialog: ' + result)
  })

inu.onUnload(() => {
  console.log('unload callback #1 ran')
})
inu.onUnload(() => {
  throw new Error('unload boom (should be logged, not fatal)')
})
inu.onUnload(() => {
  console.log('unload callback #3 ran (after the throwing one)')
})
