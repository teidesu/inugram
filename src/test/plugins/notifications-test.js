// ==InuPlugin==
// @name         notification center test
// @description  asserts inu.android.addNotificationCenterDelegate answers what the typings say
// @grant        unsafe.notificationCenter
// @grant        unsafe.jvm
// ==/InuPlugin==

// the rest reports as the app posts; each check depends only on its post, so opening the app and
// pulling to refresh finishes it

expectThrow('a delegate that is not an object', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate(null))
expectThrow('a delegate with no handlers', 'invalid-argument', () =>
  inu.android.addNotificationCenterDelegate({}))
expectThrow('a handler that is not a function', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate({ closeChats: 7 }))
expectThrow('an event the app does not have', 'invalid-argument', () =>
  // @ts-expect-error
  inu.android.addNotificationCenterDelegate({ notAnEventAtAll: () => {} }))

const heard = []
const stop = inu.android.addNotificationCenterDelegate({
  dialogsNeedReload: (...args) => heard.push(args),
  updateInterfaces: (...args) => heard.push(args),
})
check('registering answers a disposer', typeof stop === 'function')
stop()
stop()
pass('a disposer called twice is a no-op')

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
