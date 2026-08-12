// ==InuPlugin==
// @name         jvm test
// @author       teidesu
// @version      1.0
// @description  asserts inu.jvm: the class namespace scope, what values cross, handles, runnables and what refuses
// @grant        unsafe.jvm(java.lang.*,java.util.*)
// @plugin-api   1
// @platform     android
// ==/InuPlugin==

// the grant is deliberately *scoped*, which is the only way to see the boundary at all: a plugin
// holding the bare token reaches everything and every refusal below would read as a passing test of
// nothing. `java.util.*` and `java.lang.*` are what this file touches; `android.*` is what it does
// not, and every refusal it asserts is one of those two facts.

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

function expectPluginError(label, code, grant, fn) {
  let error
  try {
    fn()
  } catch (e) {
    error = e
  }
  if (error === undefined) return fail(label, 'did not throw')
  if (!(error instanceof inu.PluginError)) return fail(label, `${error.name}: ${error.message}`)
  if (error.code !== code) return fail(label, `code = ${error.code}, want ${code}`)
  if (grant !== null && error.grant !== grant) return fail(label, `grant = ${error.grant}, want ${grant}`)
  pass(label, `${error.code} ${error.message}`)
}

// exact, not a floor: most of what follows is a refusal, and a member that stopped existing refuses
// too - so the surface is asserted positively first and the count is what catches the rest
const EXPECTED = 30
const before = ran

check(
  'the jvm members are all declared',
  typeof inu.jvm.cls === 'function' &&
    typeof inu.jvm.runnable === 'function' &&
    typeof inu.jvm.loadDex === 'function' &&
    typeof inu.jvm.defineClass === 'function' &&
    typeof inu.jvm.callSuper === 'function',
)

// -- the entry point --

const ArrayList = inu.jvm.cls('java.util.ArrayList')
const Integer = inu.jvm.cls('java.lang.Integer')
const Long = inu.jvm.cls('java.lang.Long')
check('a class inside the scope list resolves', typeof ArrayList === 'function')

// a class is callable because `new cls(...)` is how the contract builds one
const list = new ArrayList()
check('new gives a java object', typeof list === 'object' && typeof list.call === 'function')

expectPluginError(
  'a class outside the scope list is refused',
  'not-granted',
  'unsafe.jvm(android.app.Activity)',
  () => inu.jvm.cls('android.app.Activity'),
)
expectPluginError(
  'a namespace the scope list only prefixes is refused',
  'not-granted',
  'unsafe.jvm(java.utility.Fake)',
  () => inu.jvm.cls('java.utility.Fake'),
)
expectPluginError('cls takes a name', 'invalid-argument', null, () => inu.jvm.cls(''))

// -- values on their way out --

list.call('add', 1)
list.call('add', 2)
check('a java int reads as a number', list.getField('size') === 2, `${list.getField('size')}`)
check('a java boolean reads as a boolean', list.call('add', 'x') === true)
check('a java String return reads as a string', list.call('toString') === '[1, 2, x]')

// a java long is 64 bits wide; the alternative to a bigint here is a number that is quietly not
// the one java holds
const big = Long.getStaticField('MAX_VALUE')
check('a java long reads as a bigint', typeof big === 'bigint' && big === 9223372036854775807n, `${big}`)

const clone = list.call('clone')
check('a java object return is a handle of its own', typeof clone === 'object' && clone !== list)

// the host is what knows the runtime class of what it is about to hand over, so this is the same
// scope list refusing on the far side of the bridge
expectPluginError(
  'a value whose class is outside the scope list is refused',
  'not-granted',
  null,
  () => inu.jvm.cls('java.lang.System').getStaticField('out'),
)

// -- values on their way in --

/** @type {[string, any][]} */
const unconvertible = [
  ['a plain object', {}],
  ['an array', [1, 2]],
  ['a function', () => {}],
  ['a symbol', Symbol('x')],
]

for (const [what, value] of unconvertible) {
  expectPluginError(`${what} cannot be handed to java`, 'invalid-argument', null, () => list.call('add', value))
}

expectPluginError('a string past the value bound is refused', 'quota-exceeded', null, () =>
  list.call('add', 'x'.repeat(1024 * 1024 + 1)),
)

// -- the pinned forms --

const add = ArrayList.getDeclaredMethod('add(Ljava/lang/Object;)Z')
check('getDeclaredMethod gives something invocable', typeof add.invoke === 'function')
check('an invoked method answers like the shorthand', add.invoke(list, 'x') === true)

const size = ArrayList.getDeclaredField('size')
check('getDeclaredField gives something readable', size.get(list) === 4, `${size.get(list)}`)
size.set(list, 4)
pass('a field can be assigned through its handle')

check('a static field reads', Integer.getStaticField('MAX_VALUE') === 2147483647)
check('a static method answers', Integer.callStatic('valueOf', 1) === 1)

// -- what a java throw looks like --

let thrown
try {
  Integer.callStatic('parseInt', 'NaN')
} catch (e) {
  thrown = e
}
check(
  'a java exception is a plain Error, not a PluginError',
  thrown instanceof Error && !(thrown instanceof inu.PluginError) && thrown.message.includes('NumberFormatException'),
  `${thrown}`,
)

// -- what refuses whatever the scope list says --

expectPluginError('loadDex needs the whole grant', 'not-granted', 'unsafe.jvm(*)', () =>
  inu.jvm.loadDex('/data/local/tmp/patch.dex'),
)
expectPluginError('defineClass is not implemented', 'unsupported', null, () =>
  inu.jvm.defineClass('my/plugin/Span', {}),
)
expectPluginError('callSuper is not implemented', 'unsupported', null, () =>
  inu.jvm.callSuper(list, 'toString'),
)

// -- callbacks --

let clicks = 0
const onClick = inu.jvm.runnable(() => {
  clicks++
})
check('runnable gives a java object', typeof onClick === 'object' && typeof onClick.call === 'function')
expectPluginError('runnable takes a function', 'invalid-argument', null, () =>
  // @ts-expect-error - the contract says a function, and the engine says so at runtime too
  inu.jvm.runnable('later'),
)
check('the callback has not run yet', clicks === 0, `${clicks}`)

if (ran - before !== EXPECTED) {
  console.error(`FAIL oracle: ${ran - before} load-time assertions ran, expected exactly ${EXPECTED}`)
}

// -- the half something else has to drive --

// a callback never runs inside the call that handed the object over: java runs it whenever it
// likes, and the engine is only ever entered from its own queue
function jvmDone() {
  check('the callback ran once java ran it', clicks === 1, `${clicks}`)
  console.log('jvm test done')
}

globalThis.__jvmDone = jvmDone

// a device reaches the same function through a button; the harness runs the runnable itself.
// java is what runs a runnable, and the plugin cannot: every member of the object this api mints
// lives in the engine's own package and is refused. So the button hands it to a real `Thread` and
// waits, which is also the only way to see that a callback arriving from another thread lands on
// the engine's queue rather than in it
if (typeof inu.ui?.settingsPage === 'function') {
  inu.registerSettings(
    inu.ui.settingsPage({
      title: 'jvm test',
      items: () => [
        inu.ui.separator('hands the runnable to a java thread and checks it came back'),
        inu.ui.button({
          text: 'Run it',
          onClick: () => {
            const Thread = inu.jvm.cls('java.lang.Thread')
            check('a real java call answers', typeof Thread.callStatic('currentThread') === 'object')
            expectPluginError('the runnable itself cannot be reached into', 'forbidden', null, () =>
              onClick.call('run'),
            )
            new Thread(onClick).call('start')
            setTimeout(jvmDone, 100)
          },
        }),
      ],
    }),
  )
}
