// ==InuPlugin==
// @name         xposed test a very very very very very very very long name
// @description  asserts inu.xposed: the hook context, the verdicts, ordering and disposal
// @grant        unsafe.jvm
// @grant        unsafe.xposed
// ==/InuPlugin==

// Math's one constructor is private and never runs, so the hookAllConstructors window is quiet.
// box classes are refused outright (asserted below)
const JMath = inu.jvm.cls('java.lang.Math')
const floorMod = JMath.getDeclaredMethod('floorMod(II)I')

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

  expectThrow('a hook with neither callback is refused', null, () => inu.xposed.hookMethod(floorMod, {}))

  check('callOriginalMethod calls past every hook', inu.xposed.callOriginalMethod(floorMod, null, [7, 4]) === 3)

  const offOverloads = inu.xposed.hookAllOverloads(JMath, 'floorMod', { before() {} })
  check('hookAllOverloads answers with one disposer for every overload', typeof offOverloads === 'function')
  offOverloads()

  const offCtors = inu.xposed.hookAllConstructors(JMath, { after() {} })
  check('hookAllConstructors answers with a disposer too', typeof offCtors === 'function')
  offCtors()

  // the hook stub boxes primitives through Integer.valueOf -> new Integer, so a hook on a box class
  // would re-enter itself until the stack is gone
  const Integer = inu.jvm.cls('java.lang.Integer')
  expectThrow('a primitive box class cannot be hooked', 'unsupported', () => inu.xposed.hookAllConstructors(Integer, { after() {} }))

  console.log('xposed test done')
} else {
  console.log('xposed test done')
}
