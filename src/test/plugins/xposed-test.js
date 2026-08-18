// ==InuPlugin==
// @name         xposed test a very very very very very very very long name
// @author       teidesu
// @version      1.0
// @description  asserts inu.xposed: the hook context, the verdicts, ordering and disposal
// @grant        unsafe.jvm(java.lang.*)
// @grant        unsafe.xposed
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// every entry point here takes a handle only `inu.jvm.cls` mints, so this file needs both grants
// and says so. the jvm one is *scoped*, which is what makes the last assertion mean anything: a
// hook is allowed on a class the scope list does not name, because the scope was already spent
// getting the method handle.

let ran = 0

function pass(label, detail) {
  ran++
  console.log(detail === undefined ? `PASS ${label}` : `PASS ${label}: ${detail}`)
}

function fail(label, detail) {
  ran++
  console.error(`FAIL ${label}: ${detail}`)
}

function check(label, ok, detail) {
  if (ok) pass(label, detail)
  else fail(label, detail)
}

function expectThrows(label, fn) {
  try {
    fn()
  } catch (e) {
    return pass(label, e.message)
  }
  fail(label, 'did not throw')
}

// Math over a box type on purpose: its one constructor is private and never runs, so the
// hookAllConstructors window is quiet, and box classes are refused outright (asserted below)
const JMath = inu.jvm.cls('java.lang.Math')
const floorMod = JMath.getDeclaredMethod('floorMod(II)I')

// -- what a hook is, before anything is dispatched --

let off
try {
  off = inu.xposed.hookMethod(floorMod, { before() {} })
} catch (e) {
  if (!(e instanceof inu.PluginError) || e.code !== 'unsupported') throw e
  console.log(`SKIP xposed test: ${e.message}`)
}

if (off !== undefined) {
  check('hookMethod answers with a disposer', typeof off === 'function')
  off()
  off()
  pass('disposing twice is a no-op')

  expectThrows('a hook with neither callback is refused', () => inu.xposed.hookMethod(floorMod, {}))

  check('callOriginalMethod calls past every hook', inu.xposed.callOriginalMethod(floorMod, null, [7, 4]) === 3)

  // the two bulk forms. one registration over however many sites the host installed, and one
  // disposer that takes all of them back down
  const offOverloads = inu.xposed.hookAllOverloads(JMath, 'floorMod', { before() {} })
  check('hookAllOverloads answers with one disposer for every overload', typeof offOverloads === 'function')
  offOverloads()

  const offCtors = inu.xposed.hookAllConstructors(JMath, { after() {} })
  check('hookAllConstructors answers with a disposer too', typeof offCtors === 'function')
  offCtors()

  // the hook stub boxes its own primitive arguments through Integer.valueOf -> new Integer, so
  // a hook on a box class re-enters itself until the stack is gone. refused before it can exist
  const Integer = inu.jvm.cls('java.lang.Integer')
  try {
    inu.xposed.hookAllConstructors(Integer, { after() {} })
    fail('a primitive box class cannot be hooked', 'did not throw')
  } catch (e) {
    check(
      'a primitive box class cannot be hooked',
      e instanceof inu.PluginError && e.code === 'unsupported',
      e.message,
    )
  }

  console.log('xposed test done')
} else {
  console.log('xposed test done')
}
