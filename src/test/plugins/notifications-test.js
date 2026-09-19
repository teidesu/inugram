// ==InuPlugin==
// @name         notification center test
// @author       teidesu
// @version      1.0
// @description  asserts inu.android.addNotificationCenterDelegate answers what the typings say
// @grant        unsafe.notificationCenter
// @grant        unsafe.jvm
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// the load-time half runs on its own; the rest reports as the app posts, which is the only way an
// event bus can be exercised at all. every check in it is a function of the one post it was handed,
// never of what the app happens to be doing, so opening the app and pulling to refresh finishes it.

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

function expectThrows(label, code, fn) {
  try {
    const value = fn()
    fail(label, `did not throw, returned ${JSON.stringify(value)}`)
  } catch (e) {
    if (!(e instanceof inu.PluginError)) fail(label, `not an inu.PluginError (${e})`)
    else if (e.code !== code) fail(label, `code = ${e.code}, want ${code}`)
    else pass(label, e.message)
  }
}

// -- what a delegate has to be --

expectThrows('a delegate that is not an object', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate(null))
expectThrows('a delegate with no handlers', 'invalid-argument', () =>
  inu.android.addNotificationCenterDelegate({}))
expectThrows('a handler that is not a function', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate({ closeChats: 7 }))
// the vocabulary is the app's own, so a typo is refused rather than silently never firing
expectThrows('an event the app does not have', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate({ notAnEventAtAll: () => {} }))

// -- the disposer, before anything can have been posted --

const heard = []
const stop = inu.android.addNotificationCenterDelegate({
  dialogsNeedReload: (...args) => heard.push(args),
  updateInterfaces: (...args) => heard.push(args),
})
check('registering answers a disposer', typeof stop === 'function')
stop()
stop()
pass('a disposer called twice is a no-op')

// -- what a handler is handed --

const WANTED = 2
let seen = 0

/**
 * a `long` is a js number here like everywhere else in this api; anything the app hands over that
 * is not a scalar arrives as the same `JavaObject` `inu.jvm` would answer with
 */
function isScalarOrHandle(value) {
  if (value === null || typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean') return true
  return typeof value === 'object' && typeof value.call === 'function' && typeof value.getField === 'function'
}

function observe(name, args) {
  seen++
  const where = `${name}#${seen} ${JSON.stringify(args)}`
  check('the account slot comes first', typeof args[0] === 'number', where)
  check('and every argument after it is a scalar, null, or a jvm handle', args.slice(1).every(isScalarOrHandle), where)
  check('a disposed delegate hears nothing', heard.length === 0, JSON.stringify(heard))
  if (seen === WANTED) console.log('notifications test done')
}

inu.android.addNotificationCenterDelegate({
  dialogsNeedReload: (...args) => observe('dialogsNeedReload', args),
  updateInterfaces: (...args) => observe('updateInterfaces', args),
})

console.log(`notifications-test armed; use the app until ${WANTED} events land`)
