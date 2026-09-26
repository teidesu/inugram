# Java reflection (`inu.jvm`)

In some advanced cases, a plugin might need to reach the app's Java objects at runtime.

For those cases, `inu.jvm` exists, exposing the engine's Java reflection API.

Since this is a low-level and dangerous API, it needs the `unsafe.jvm` grant.
Avoid using it if safer alternatives exist.

## Classes, objects and members

```ts
const ArrayList = inu.jvm.cls('java.util.ArrayList')
const list = new ArrayList()
list.call('add', 'hello')
const size: number = list.call('size')

const Integer = inu.jvm.cls('java.lang.Integer')
const max: number = Integer.getStaticField('MAX_VALUE')
const parsed: number = Integer.callStatic('parseInt', '42')
```

- `inu.jvm.cls(name)` expects a FQN, as seen by the ART. Use jadx if unsure.
  Nested classes use `$`: `org.telegram.tgnet.TLRPC$TL_messageEntityBold`.
- `inu.jvm.cls` merely resolves a class name, it does not run any static initializers.
- A class handle is callable with `new`. It also has a few accessors to get methods, fields, etc.
- Currently most of the `inu.jvm` APIs are untyped, so take extra care when using them.

## Handles

Every Java object you get back is a **handle**: a JS object backed by a Java object,
for as long as it is not GC-ed

- Each call that returns an object gives you a new handle, even for the same Java object. Do not
  compare handles with `===`. Ask Java instead: `a.call('equals', b)`, or `System.identityHashCode`.
- A handle is only released with GC
- All handles die when the plugin unloads
- Handles do not survive a reload. Store data, not handles.

## Values crossing the bridge

Scalars and strings cross as values. Everything else crosses as a handle.

| JS | Java |
| --- | --- |
| `null`, `undefined` | `null` |
| `boolean` | `boolean` / `Boolean` |
| integral `number` (including `2.0`) | any integer or float type it fits exactly |
| fractional `number` | `double`, or `float` if in range |
| `bigint` | any integer type it fits (must fit a `long`) |
| `string` | `String`, or `char` / `Character` if exactly one UTF-16 unit |
| `Uint8Array` | `byte[]` (copied) |
| handle | the object, if it is an instance of the parameter type |

Going back:

| Java | JS |
| --- | --- |
| `byte`, `short`, `int`, `float`, `double` and their boxes | `number` |
| `long` / `Long` | `number` when within 2^53, otherwise `bigint` |
| `char` / `Character`, `String` | `string` |
| `byte[]` | `Uint8Array` (copied) |
| anything else | a handle |

Rules to keep in mind:

- A number is never truncated. Passing `300` where a `byte` is expected does not match that
  parameter, and passing `1.5` to an `int` does not either.
- Plain objects, JS arrays, functions and symbols cannot be passed. They throw
  `invalid-argument`. Build a Java collection, or use `inu.android.bundle` for a `Bundle`.
- A string or `byte[]` over 1 MB is refused with `quota-exceeded`, in either direction.
- Because `long` comes back as a `number` when small, check with `typeof x === 'bigint'` only
  if the value can really be large, or normalize with `BigInt(x)`.

## Overloads

Normally a call picks an overload from the **runtime** values you pass.
There is no static type information.

The overload is resolved using the number of passed arguments, and their types.
If zero or multiple overloads match, the call throws `invalid-argument` and lists a few descriptors.

To use an exact overload, use the Dalvik descriptor:

```ts
const StringBuilder = inu.jvm.cls('java.lang.StringBuilder')
const sb = new StringBuilder()
sb.call('append(C)Ljava/lang/StringBuilder;', 'a')

const ArrayList = inu.jvm.cls('java.util.ArrayList')
const sized = ArrayList.getDeclaredConstructor('(I)V').newInstance(16)
const add = ArrayList.getDeclaredMethod('add(Ljava/lang/Object;)Z')
add.invoke(sized, 'x')
```

- `getDeclaredMethod(name)` works without a descriptor only when the name is not overloaded.
- `getDeclaredConstructor` always takes a descriptor, such as `(I)V`.
- A pinned member still checks your arguments. It throws `invalid-argument` if they do not fit.
- Lookups are cached per class, so repeated calls are cheap.

## Errors

Two kinds of errors reach your code:

- A bridge refusal is an `inu.PluginError` with a `code`:
  - `not-found` (no such class or member)
  - `invalid-argument` (arguments do not fit, ambiguous overload)
  - `forbidden` (trying to access plugin engine internals)
  - `handle-expired`
  - `quota-exceeded`.
- An exception thrown by the Java code is a plain `Error`. Its message starts with the Java
  exception class, for example `java.lang.NumberFormatException: ...`.

```ts
const Integer = inu.jvm.cls('java.lang.Integer')
try {
  Integer.callStatic('parseInt', 'nope')
} catch (e) {
  if (e instanceof inu.PluginError) console.log('bridge refused:', e.code)
  else console.log('java threw:', (e as Error).message)
}
```

## TL objects

As discussed in [telegram.md](./telegram.md), the app's TL objects are often backed by real Java objects.

To convert a JS-land `TLObject` into a `JavaObject` reference, use `inu.jvm.fromTl`:

```ts
const bold = inu.jvm.fromTl({ _: 'messageEntityBold', offset: 0, length: 4 })
bold.setField('length', 5)
```

It works for both live and detached `TLObject`-s, creating a new `TLObject` for the latter.

The opposite operation also exists, letting you convert a `JavaObject` reference into a readable `TLObject`:

```ts
const view = inu.jvm.toTl(bold)
```

## Callbacks into JS

Java code often wants a callback: a `Runnable`, a listener, an overridden method.

The API provides two ways to declare one:

- **JS callbacks** (`inu.jvm.runnable`, and JS bodies in `defineClass`) run your JS function in QuickJS.
- **Routines** (`inu.jvm.routine`) run a compiled program without entering JS. See [routines.md](./routines.md).

A JS callback runs on **whatever thread Java calls it on**.
To run JS there, the thread has to borrow your plugin's engine (see [engine.md](./engine.md)).
Since only one thread can hold it at a time, there are a few quirks:

- If another thread holds the engine, the caller waits. The plugin thread holds it only while
  its JS runs: during any Java call it makes through `inu.jvm`, it hands the engine over. So the
  wait is at most one JS turn of the plugin thread (2 s at worst, usually far less).
- If another app thread holds the engine for over 2 s (its own callback is stuck in a slow Java
  call), the callback is skipped (a runnable) or fails with `IllegalStateException` (a `defineClass` body).
- A Java call your plugin-thread JS makes can call back into your callbacks on that thread. They
  run nested, like a normal function call.
- A callback that re-enters its own plugin from inside another callback on the same thread is refused
- After the plugin unloads, runnables do nothing, `void` bodies do nothing, and other bodies throw `IllegalStateException`.
- Promise continuations and timers you start in a callback run later, on the plugin thread.
- Many APIs only work on the plugin thread and throw inside a callback

### `inu.jvm.runnable`

Wraps a JS function (and its closure) in a `java.lang.Runnable`

```ts
const Handler = inu.jvm.cls('android.os.Handler')
const Looper = inu.jvm.cls('android.os.Looper')
const handler = new Handler(Looper.callStatic('getMainLooper'))
handler.call('post', inu.jvm.runnable(() => {
  inu.ui.toast('hello from the main thread')
}))
```

A runnable only skips when another app thread sits on your engine for over 2 s, or the plugin is
stopping. For hot paths, and for anything that must hold even then, use a routine.

## Defining classes

`inu.jvm.defineClass` generates a real Java class at runtime. Use it to implement an interface,
subclass something, or pass Java a listener.

```ts
const OnClickListener = inu.jvm.cls('android.view.View$OnClickListener')
const Listener = inu.jvm.defineClass({
  interfaces: [OnClickListener],
  methods: {
    onClick: (self, view) => {
      inu.ui.toast('clicked')
    },
  },
})
const listener = new Listener()
```

A fuller example with a field, a typed override, `super` and a constructor:

```ts
const ArrayList = inu.jvm.cls('java.util.ArrayList')
const Counting = inu.jvm.defineClass({
  superclass: ArrayList,
  fields: { adds: 'int' },
  methods: {
    add: {
      params: ['java.lang.Object'],
      returns: 'boolean',
      body: (self, item) => {
        self.setField('adds', self.getField('adds') + 1)
        return inu.jvm.callSuper(Counting, self, 'add', item)
      },
    },
  },
  constructors: [
    { params: ['int'], super: [{ arg: 0 }] },
  ],
})
new Counting(8).add(1)
```

How it works:

- Types are written like Java (`int`, `float[]`, `java.lang.String`) or as JVM descriptors
- If you omit `params` / `returns`, the signature is inferred from inherited method
  - If there is none, the method is `()void`
  - You can also pass an array of specs to define overloads, each will need explicit `params`
- A body is a JS function or a routine. JS bodies receive `self` and the arguments.
  A JS body may return an array of scalars and handles, which Java gets as an `Object[]`
- The return value is converted to the declared return type by the rules above
- A thrown JS error becomes an `IllegalStateException` in the Java caller.
- Due to Dalvik limitations, we need to generate `super()` in the constructor as one of the first instructions.
  Thus, it is a separate fixed list or a function that computes the arguments, ran before the constructor function.
- JS bodies follow all the callback rules above. For bodies called often or from other threads prefer using a routine.

### Class names and unloading

ART cannot unload a class. A defined class lives until the app process dies, even after your
plugin unloads (its bodies stop running JS; see above).

So by default every class gets a random name, available as `DefinedClass.name`. Each reload
defines a fresh class and there is no clash. If you pass an explicit name, the first definition
wins until the app restarts, and later reloads see the old class.

Limits: 128 classes per engine, 256 fields and 256 methods per class, 64 interfaces, 64
parameters, 1 MB of definition. Nested calls into bodies are limited to 64 levels sharing a
250 ms budget.

## Loading DEX

> ‼️ Avoid this API. DEX files are hard to audit and are usually not needed.
> If you find yourself needing to load a custom DEX, please reach out!

`inu.jvm.loadDex(pathOrBytes)` loads a DEX file (up to 8 MB). Classes in it become reachable
through `inu.jvm.cls`.

## Related `inu.android` helpers

- `inu.android.getCurrentActivity()`, `inu.android.getCurrentFragment()`: the visible screen, or `null`.
- `inu.android.bundle(values)`: build a `Bundle` from a JS object.
- `inu.android.nativeView(view)`: convert a custom `View` into a `UIElement`
- `inu.android.drawableIcon(drawable)`: convert a `Drawable` into a `UIIcon`
- `inu.ui.openPage(fragment)`: open a custom `Fragment` via a `JavaObject`
