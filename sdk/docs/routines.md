# Routines

A routine is a small function, written in a subset of JS, that runs **on the Java thread that
calls it, without entering the JS engine**.

`@inugram/cli` compiles it to bytecode when you build the plugin,
and the app runs that bytecode directly against Java objects.

> Note: since the CLI compiles routines, you generally can't use them without using the CLI.
> You *can* write the IR yourself, but it is not recommended as it is internal and not stable.

```ts
const isNumeric = inu.jvm.routine((s) => {
  return s !== null && s.matches('[0-9]+')
})
```

Routines need `unsafe.jvm` (and `unsafe.xposed` for hook routines) grants.

## Why routines exist

Long story short - because JS is single-threaded, and because JNI is slow.

Your plugin's JS runs in one engine, owned by one thread, but Java (well, ART) is multi-threaded,
and thus can ask your code to run at any point in time, on any thread.

This is explained better in [engine.md](./engine.md#threads), but what that means for the interop
is that the calling thread needs to borrow your engine. And to do that, it has to wait for
any pending work on the plugin thread to finish.

And the second problem is that JNI is slow. Well, it's not *that* slow, but every crossing
requires us to a) marshal all the data, b) forward everything to the ART (or back), c) unmarshal it to process.
When done a bunch of times in a row, it can become a bottleneck.

And since a routine is compiled to bytecode interpreted by the app directly, it avoids all that JNI overhead.
However, since it's interpreted (and beyond that, interpreted **in ART**) it *is* slower than just running
JS directly for CPU-intensive tasks.

So the routines are very much a tradeoff to make hooking hot paths viable at all.

### When to use one

| Situation | Use |
| --- | --- |
| Hook on a hot method (layout, drawing, message binding, list items) | `inu.xposed.routine` |
| Hook that must apply on every call (privacy, security) | `inu.xposed.routine` |
| Hook where JS is needed, but only for a few calls | a `filter` routine plus JS phases |
| `defineClass` method called often or off the plugin thread | `inu.jvm.routine` body |
| A `Runnable` that must always run | `inu.jvm.routine` |
| Rare callbacks that need plugin APIs (storage, network, UI) | plain JS |

## Routine body

The body *looks* like a plain JS function (arrow or regular), with a few quirks explained below

**All** the values inside the routine are Java values (**not** `JavaObject`!), so think of them as if
they were regular JS values, not handles. See [below](#supported-subset) for more details.

> Currently the CLI doesn't type them at all, so pretty much everything is `any`. This might change in the future.

### Method routines

```ts
inu.jvm.routine(function (a, b) {
  const foo = this.getFoo()
  return foo + a + b
})
```

- `this` is the receiver (Java's `this`, in other words). Using `this` requires a `function` expression,
  due to the way JS works (duh).
- The parameters are the actual call's arguments.
- `return`, well, returns the result.

### Hook routines

```ts
inu.xposed.routine((ctx) => {
  const foo = ctx.thisObject.getFoo()
  return foo + ctx.args[0] + ctx.args[1]
})
```

Conceptually the same thing, but you don't have `this`, and instead have the hook context,
with `thisObject` and `args` members.

Operations with the hook context are compiled to their own instructions

## How it builds

The CLI finds routine calls **syntactically**: `inu.jvm.routine(` or `inu.xposed.routine(`
followed by a function expression or arrow. It compiles the body and replaces the call in the
bundle with the compiled program.

So you need write the call exactly like that, these won't compile:
- `const r = inu.jvm.routine; r(() => {...})`
- `const fn = () => {...}; inu.jvm.routine(fn)`

TypeScript syntax is fine, and the CLI will fail to compile the plugin if you use unsupported constructs.

For example, the snippet above compiles to roughly:

```js
const isNumeric = inu.jvm.routine({
  v: 1,
  source: `function (s) {
    return s !== null && s.matches('[0-9]+')
  }`,
  captures: [],
  slots: 1,
  code: [
    ['arg', [0]],
    ['ne', 0, [null]],
    ['setSlot', 0, 1],
    ['jumpIfFalsy', 1, 6],
    ['call', 0, ['matches'], [['[0-9]+']]],
    ['setSlot', 0, 4],
    ['getSlot', 0],
    ['return', 6]
  ],
  tries: []
})
```

The `source` field records the original body. The app ignores it, but it is preserved so that
users can audit the source code more easily (and verify the bytecode matches using `inu verify`).

## Supported subset

- `const` and `let`, block scoping, inc. shadowing.
- `if`/`else`, `switch` (with fallthrough)
- `while`, `do`/`while`, `for`, `for (const x of y)`, `break`, `continue`, labels.
- `try`/`catch`/`finally`, `throw`.
- `return` (method routines only).
- Member access and calls on Java values: `a.b`, `a[k]`, `a.m(...)`, `a?.b`, `a?.m()`.
- `new C(...)` on a captured class or constructor.
- Arithmetic `+ - * / %`, unary `-`, bitwise `& | ^ ~ << >> >>>`.
- Comparison `=== !== < <= > >=`, `!`, `&&`, `||`, `??`, `?:`, `instanceof`.
- Assignment `=`, compound `+=` etc., `++`, `--`.
- Template literals, array literals (they become `Object[]`), `undefined`.
- `inu.jvm.callSuper(cls, self, name, ...args)`
- `inu.jvm.getSuper(this).name(...args)`.
  - pretty much the same as above, it also compiles to basically `super.name(...args)`,
    but callSuper may not be available in some cases on the captured `cls`

### (Currently) Not supported

- Functions of any kind inside a routine: nested functions, arrows, callbacks
  (`list.forEach(x => ...)`), classes.
- Bare calls: `helper(x)`. Only members can be called: `obj.helper(x)`.
- `==`, `!=` (use `===`), `typeof`, `in`, `delete`, `void`, `**`, unary `+`.
- Object literals, destructuring, spread, regex literals, tagged templates.
- `var`, `for in`, `with`, `arguments`, `super` (use `callSuper`/`getSuper`), `await`, `yield`.
- Async and generator functions
- Globals: `console`, `Math`, `JSON`, `inu.ui`, etc.

### Semantics that differ from JS

Values in a routine are Java values, and members are Java members.

1. **Java members, not JS ones.** For example, `java.lang.String` has `s.length()`, not `s.length`.
  - Arrays have some JS-compatible sugar though: `.length` and `[i]` are valid on Java arrays,
    and `for..of` is valid over arrays/`Iterable`-s.
2. **Numbers.**
  - Arithmetic on two integers is 64-bit and throws on overflow.
  - `/` on integers truncates
  - Вividing by zero throws
  - A floating result that is not finite throws.
  - Bitwise operators work on 32 bits when both sides are `int` or narrower, otherwise 64.
3. **`===` compares values** for numbers, strings and booleans (`1 === 1.0` is true), and
   **identity** for everything else. Use `.equals()` for Java equality.
4. **`+` concatenates** only when one side is a `String`. `CharSequence` is not a `String` and won't concatenate, call `.toString()` first.
5. **`null` and `undefined` are the same value.** Missing arguments read as `null`.
6. **No coercion.** `<` needs two numbers or two strings.
7. **Truthiness is JS-like:** `null`, `false`, `0`, `NaN` and `""` are falsy
8. **Overloads resolve at runtime**, the same way `inu.jvm` calls do. The compiler has no Java type information.
9. **Captures are snapshots** (see below).

## Captures

A routine can use names declared outside it. These are **captures**:

```ts
const LIMIT = 3
const WORDS = ['foo', 'bar']
const countHits = inu.jvm.routine((text) => {
  let hits = 0
  for (let i = 0; i < WORDS.length; i++) {
    if (text.contains(WORDS[i])) hits++
    if (hits >= LIMIT) break
  }
  return `hits: ${hits}`
})
```

- A capture is taken **by value, once**, when the routine is created. Later changes in JS are never seen.
- Supperted types: scalars (`null`, boolean, number, bigint, string), `Uint8Array`, a Java handle,
  or an array of the them.
- An array capture becomes one `Object[]` shared by every run on every thread. If your routine
  writes into it, that is shared mutable state.
- A captured name must be `const`, an import, or a `let`/parameter that is never reassigned.
- A global (anything not declared in the file) cannot be captured.
- Only the bare name is captured. `Foo.BAR` is a live Java static read on the captured `Foo`,
  done on every run. To capture a plain value, bind it first: `const BAR = Foo.getStaticField('BAR')`.

### Typing captures

The typings describe JS handles, so TypeScript does not know about Java members on a captured
class. Two common fixes:

```ts
const UNDERLINE: number = inu.jvm.cls('android.graphics.Paint').getStaticField('UNDERLINE_TEXT_FLAG')
const Log: any = inu.jvm.cls('android.util.Log')

const task = inu.jvm.routine(() => {
  Log.d('inu', `flag is ${UNDERLINE}`)
})
```

Reading a constant once outside is also cheaper than a static read on every run.

## Hook routines

In `inu.xposed.routine`, the context parameter (whatever you name it) compiles to direct
operations:

| You write | Meaning |
| --- | --- |
| `ctx.args[i]` | read argument `i` |
| `ctx.args[i] = v` | replace argument `i` (only available in `before`) |
| `ctx.args.length` | argument count |
| `ctx.thisObject`, `ctx.method` | receiver, hooked method |
| `ctx.returnValue`, `ctx.throwable` | the outcome (only available in `after`) |
| `ctx.setReturnValue(v)`, `ctx.setThrowable(t)` | set the result, same rules as [JS hooks](./xposed.md#phases-and-the-context) |

`ctx.extra` does not exist in routines, use a Java object if you need to pass state between phases.

```ts
const TextView = inu.jvm.cls('android.widget.TextView')
const LABEL = 'Inu'
inu.xposed.hookAllOverloads(TextView, 'getText', {
  after: inu.xposed.routine((ctx) => {
    const text = ctx.returnValue
    if (text !== null && text.toString() === 'Telegram') ctx.setReturnValue(LABEL)
  }),
})
```

## `defineClass` bodies and `super`

A method routine can be a `defineClass` body. `this` is the object (or the class, for a static
method). A routine cannot capture the class it is defining, because the class does not exist
yet when the routine is built. So call `super` with `getSuper`:

```ts
const RedUnderlineSpan = inu.jvm.defineClass({
  superclass: inu.jvm.cls('android.text.style.UnderlineSpan'),
  methods: {
    updateDrawState: {
      params: ['android.text.TextPaint'],
      body: inu.jvm.routine(function (paint) {
        inu.jvm.getSuper(this).updateDrawState(paint)
        paint.setColor(0xFFFF0000 | 0)
      }),
    },
  },
})
```

`getSuper` needs `this`, so the body is a `function`, not an arrow.

- `inu.jvm.getSuper(this).method(...args)` calls the superclass implementation of the class the
  routine is bound to. It only works as a direct call like that, only in method routines, and
  only when the routine runs as a `defineClass` body; anywhere else it throws.
- `inu.jvm.callSuper(SomeClass, obj, 'method', ...args)` works in any routine, with a class you
  captured.

## Loops, time and limits

Loops are allowed, but a routine runs on an app thread, so it is bounded:

- **250 ms per run.** The clock is checked before each Java operation and periodically in loops.
- An abort cannot be caught by your `try`/`catch`, and `finally` does not run for it.
- A Java call already in progress cannot be interrupted. A routine calling something slow is
  only stopped after that call returns.

Size limits: 1024 instructions, 256 slots (`let` variables and temporaries), 256 captures
(4096 values once arrays are flattened), 64 `try` regions, 256 call arguments, 1 MB of captures,
512 live routines per plugin. Going over a compile-time limit is a build error.

## Failure

| Where the routine runs | If it throws |
| --- | --- |
| `defineClass` body | the exception reaches the Java caller |
| xposed `filter` | logged, and the hook runs anyway |
| xposed `before`/`after` | logged; the call continues with changes already made |
| plain `Runnable` | the exception reaches whoever called `run()` |

A `Runnable` routine handed to a `Thread` or `Handler` that throws will crash that thread like any
Java code would. Wrap its body in `try`/`catch` if it can fail.

`try`/`catch` in a routine catches any Java `Throwable`, including `Error`s, and `catch` gets the
original throwable, not a wrapper.

## Verifying a built plugin

Anyone can check that a built `.inu.js` really contains what its source says:

```bash
inu verify dist/my-plugin.inu.js
```

For each compiled routine, `inu verify` takes the recorded `source`, compiles it again with the
same compiler, and compares the result with the bytecode in the file. It also checks that the
number of captured values passed matches.

It verifies the routine **body**. It cannot check what the captured values are at runtime: those
come from the surrounding JS, which you still review as normal code.

## Troubleshooting

| Build error | Fix |
| --- | --- |
| `` `console` is not declared in this file, so it is a global, which a routine cannot capture `` | Routines have no console or other globals. Log from JS, or call `android.util.Log` through a captured class. |
| ``a routine can only call a member, such as `object.method(...)` `` | Inline the helper, or make it a Java method (`defineClass`). |
| `a routine cannot hold another function` | No callbacks inside. Replace `forEach`/`map` with `for of`. |
| `` `x` is assigned after it is declared ... `` | Captures are snapshots. Make it `const`, or pass the value as an argument. |
| ``the hook context is read-only, use `ctx.setReturnValue(...)` `` | Use the setter methods. |
| ``a hook routine cannot return a value, use `ctx.setReturnValue(...)` `` | Hook routines set results through `ctx`. |
| `` `==` is not supported, use `===` `` | There is no coercion; use `===`. |
| `object literals are not supported`, `destructuring is not supported` | Use Java objects and plain variables. |

At runtime:

- `routine: a routine body is compiled by @inugram/cli` means the call was not found at build
  time. Write `inu.jvm.routine(function ...)` literally, and build with the CLI.
- `routine: execution budget exceeded` means a run took over 250 ms.
- A value "does not match" or "does not take these arguments" error comes from overload
  resolution; see [jvm.md](./jvm.md#overloads).

## More examples

Counting with a loop over an `Iterable`:

```ts
const countEmpty = inu.jvm.routine((list) => {
  let n = 0
  for (const s of list) {
    if (s.isEmpty()) n++
  }
  return n
})
```

A hook that clamps an argument and fixes up the object after:

```ts
const View = inu.jvm.cls('android.view.View')
inu.xposed.hookAllOverloads(View, 'setAlpha', {
  before: inu.xposed.routine((ctx) => {
    if (ctx.args[0] < 0.5) ctx.args[0] = 0.5
  }),
  after: inu.xposed.routine((ctx) => {
    try {
      ctx.thisObject.invalidate()
    } catch (e) {
      ctx.setThrowable(e)
    }
  }),
})
```

A `Runnable` that runs on any thread:

```ts
const Log: any = inu.jvm.cls('android.util.Log')
const task = inu.jvm.routine(() => {
  Log.d('inu', 'ran on whatever thread called run()')
})
new (inu.jvm.cls('java.lang.Thread'))(task).call('start')
```
