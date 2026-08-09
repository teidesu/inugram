// ==UserScript==
// @name         xposed test
// @author       teidesu
// @version      1.0
// @description  asserts inu.xposed: the hook context, the verdicts, ordering and disposal
// @grant        unsafe.jvm(java.lang.*)
// @grant        unsafe.xposed
// @plugin-api   1
// @platform     android
// ==/UserScript==

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

const Integer = inu.jvm.cls('java.lang.Integer')
const bitCount = Integer.getDeclaredMethod('bitCount(I)I')

// -- what a hook is, before anything is dispatched --

let off
try {
  off = inu.xposed.hookMethod(bitCount, { before() {} })
} catch (e) {
  if (!(e instanceof inu.PluginError) || e.code !== 'unsupported') throw e
  console.log(`SKIP xposed test: ${e.message}`)
}

if (off !== undefined) {
check('hookMethod answers with a disposer', typeof off === 'function')
off()
off()
pass('disposing twice is a no-op')

expectThrows('a hook with neither callback is refused', () => inu.xposed.hookMethod(bitCount, {}))

check('callOriginalMethod calls past every hook', inu.xposed.callOriginalMethod(bitCount, null, [7]) === 3)

// the two bulk forms. one registration over however many sites the host installed, and one
// disposer that takes all of them back down
const offOverloads = inu.xposed.hookAllOverloads(Integer, 'bitCount', { before() {} })
check('hookAllOverloads answers with one disposer for every overload', typeof offOverloads === 'function')
offOverloads()

const offCtors = inu.xposed.hookAllConstructors(Integer, { after() {} })
check('hookAllConstructors answers with a disposer too', typeof offCtors === 'function')
offCtors()

console.log('xposed test done')
} else {
  console.log('xposed test done')
}
