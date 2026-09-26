// ==InuPlugin==
// @name         jvm test
// @description  asserts inu.jvm: the class namespace scope, what values cross, handles, runnables and what refuses
// @grant        unsafe.jvm
// ==/InuPlugin==

// the grant reaches every class the app can, so nothing below refuses for want of reach

// exact: a vanished member refuses too, so the surface is asserted positively and the count catches the rest
const EXPECTED = 31
const before = ran

check(
  'the jvm members are all declared',
  typeof inu.jvm.cls === 'function' &&
    typeof inu.jvm.runnable === 'function' &&
    typeof inu.jvm.loadDex === 'function' &&
    typeof inu.jvm.defineClass === 'function' &&
    typeof inu.jvm.callSuper === 'function',
)

const ArrayList = inu.jvm.cls('java.util.ArrayList')
const Integer = inu.jvm.cls('java.lang.Integer')
const Long = inu.jvm.cls('java.lang.Long')
check('a class resolves', typeof ArrayList === 'function')

const list = new ArrayList()
check('new gives a java object', typeof list === 'object' && typeof list.call === 'function')

expectThrow('cls takes a name', 'invalid-argument', () => inu.jvm.cls(''))

list.call('add', 1)
list.call('add', 2)
check('a java int reads as a number', list.getField('size') === 2, `${list.getField('size')}`)
check('a java boolean reads as a boolean', list.call('add', 'x') === true)
check('a java String return reads as a string', list.call('toString') === '[1, 2, x]')

const big = Long.getStaticField('MAX_VALUE')
check('a java long reads as a bigint', typeof big === 'bigint' && big === 9223372036854775807n, `${big}`)

const clone = list.call('clone')
check('a java object return is a handle of its own', typeof clone === 'object' && clone !== list)

/** @type {[string, any][]} */
const unconvertible = [
  ['a plain object', {}],
  ['an array', [1, 2]],
  ['a function', () => {}],
  ['a symbol', Symbol('x')],
]

for (const [what, value] of unconvertible) {
  expectThrow(`${what} cannot be handed to java`, 'invalid-argument', () => list.call('add', value))
}

expectThrow('a string past the value bound is refused', 'quota-exceeded', () =>
  list.call('add', 'x'.repeat(1024 * 1024 + 1)),
)

const add = ArrayList.getDeclaredMethod('add(Ljava/lang/Object;)Z')
check('getDeclaredMethod gives something invocable', typeof add.invoke === 'function')
check('an invoked method answers like the shorthand', add.invoke(list, 'x') === true)

const size = ArrayList.getDeclaredField('size')
check('getDeclaredField gives something readable', size.get(list) === 4, `${size.get(list)}`)
size.set(list, 4)
pass('a field can be assigned through its handle')

const sizedCtor = ArrayList.getDeclaredConstructor('(I)V')
check('a declared constructor builds an object', typeof sizedCtor.newInstance(10) === 'object')
check(
  'a constructor and a method do not share a shape',
  Object.keys(Object.getPrototypeOf(sizedCtor)).join() === 'newInstance' &&
    Object.keys(Object.getPrototypeOf(add)).join() === 'invoke',
)
expectThrow('getDeclaredConstructor takes a descriptor', 'invalid-argument', () =>
  ArrayList.getDeclaredConstructor('add'),
)

check('a static field reads', Integer.getStaticField('MAX_VALUE') === 2147483647)
check('a static method answers', Integer.callStatic('valueOf', 1) === 1)

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

const Sized = inu.jvm.defineClass({ superclass: ArrayList, methods: { size: () => 42 } })
const sized = new Sized()
sized.call('add', 'x')
check(
  'callSuper runs the superclass member without dispatching to the js override, which would re-enter',
  inu.jvm.callSuper(Sized, sized, 'size') === 1,
)
const Listish = inu.jvm.defineClass({
  superclass: inu.jvm.cls('java.util.AbstractList'),
  methods: { size: () => 0, get: () => null },
})
expectThrow('callSuper refuses an abstract super member', 'invalid-argument', () =>
  inu.jvm.callSuper(Listish, new Listish(), 'size'),
)
expectThrow('callSuper refuses a receiver of another class', 'invalid-argument', () =>
  inu.jvm.callSuper(Sized, list, 'size'),
)
expectThrow('callSuper refuses a class with no superclass', 'invalid-argument', () =>
  inu.jvm.callSuper(inu.jvm.cls('java.lang.Object'), list, 'hashCode'),
)

let clicks = 0
const onClick = inu.jvm.runnable(() => {
  clicks++
})
check('runnable gives a java object', typeof onClick === 'object' && typeof onClick.call === 'function')
expectThrow('runnable takes a function', 'invalid-argument', () =>
  // @ts-expect-error - the contract says a function, and the engine says so at runtime too
  inu.jvm.runnable('later'),
)
check('the callback has not run yet', clicks === 0, `${clicks}`)

if (ran - before !== EXPECTED) {
  console.error(`FAIL oracle: ${ran - before} load-time assertions ran, expected exactly ${EXPECTED}`)
}

// java runs a callback whenever it likes; the engine is only entered from its own queue
function jvmDone() {
  check('the callback ran once java ran it', clicks === 1, `${clicks}`)
  console.log('jvm test done')
}

globalThis.__jvmDone = jvmDone

// the harness runs the runnable itself; a device hands it to a real `Thread` via a button, since every
// member of the minted object is in the engine's package and refused to the plugin. it also shows a
// callback from another thread lands on the engine's queue
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
            check('a real java call answers', typeof inu.jvm.cls('java.util.Locale').callStatic('getDefault') === 'object')
            expectThrow('the runnable itself cannot be reached into', 'forbidden', () =>
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
