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

const String_ = inu.jvm.cls('java.lang.String')
const length = String_.getDeclaredMethod('length')

// -- what a hook is, before anything is dispatched --

const off = inu.xposed.hookMethod(length, { before() {} })
check('hookMethod answers with a disposer', typeof off === 'function')
off()
off()
pass('disposing twice is a no-op')

expectThrows('a hook with neither callback is refused', () => inu.xposed.hookMethod(length, {}))

// -- the context, and the two verdicts --

globalThis.__xposedLog = []

inu.xposed.hookMethod(length, {
  before(ctx) {
    __xposedLog.push('before-1')
    check('a before hook is handed the method it is on', typeof ctx.method === 'object')
    check('a static-shaped dispatch has no receiver', ctx.thisObject === null)
    check('the arguments are the ones java passed', ctx.args.length === 1 && ctx.args[0] === 7, String(ctx.args))
    // live: what the original is called with is what is left here
    ctx.args[0] = 9
  },
  after(ctx) {
    __xposedLog.push('after-1')
    check('an after hook sees what the original returned', ctx.returnValue === '<original>', String(ctx.returnValue))
    check('nothing was thrown', ctx.throwable === null)
  },
})

// registration order, first to last in both phases - which is only observable with two of them
inu.xposed.hookMethod(length, {
  before() {
    __xposedLog.push('before-2')
  },
  after() {
    __xposedLog.push('after-2')
  },
})

check('callOriginalMethod calls past every hook', inu.xposed.callOriginalMethod(length, null, []) === '<original>')

// the two bulk forms. one registration over however many sites the host installed, and one
// disposer that takes all of them back down
const offOverloads = inu.xposed.hookAllOverloads(String_, 'substring', { before() {} })
check('hookAllOverloads answers with one disposer for every overload', typeof offOverloads === 'function')
offOverloads()

const offCtors = inu.xposed.hookAllConstructors(String_, { after() {} })
check('hookAllConstructors answers with a disposer too', typeof offCtors === 'function')
offCtors()

// the rest is the dispatch, which only the host can start
globalThis.__xposedDone = () => {
  check(
    'both phases ran, in registration order, before all of them then after all of them',
    __xposedLog.join(',') === 'before-1,before-2,after-1,after-2',
    __xposedLog.join(','),
  )
  console.log('xposed test done')
}
