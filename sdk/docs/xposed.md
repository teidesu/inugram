# Xposed-style method hooking

`inu.xposed` provides Xposed-style method hooking, letting you run custom code before and/or after
the original, change its arguments, and replace its result.

It is the most powerful and the most dangerous API a plugin has.

It needs both `unsafe.xposed` and `unsafe.jvm`.

Hooking works in-process on a stock, non-rooted device. Some devices or Android versions may not
support it. Then every hook call throws `unsupported`, and your plugin should carry on without it.

## Hooking a method

```ts
const Activity = inu.jvm.cls('android.app.Activity')
const onResume = Activity.getDeclaredMethod('onResume')
const off = inu.xposed.hookMethod(onResume, {
  after(ctx) {
    console.log('resumed', ctx.thisObject?.call('getLocalClassName'))
  },
})
```

- `hookMethod(method, hook)` hooks one specific method or constructor handle.
- `hookAllOverloads(cls, name, hook)` hooks every overload the class declares with that name
- `hookAllConstructors(cls, hook)` hooks every declared constructor.

All of the above return a disposer to un-hook the method

Classes that back primitive boxing (`java.lang.Integer` and friends) cannot be hooked, because
the hooking subsystem itself uses them, and it breaks stuff.

## Phases and the context

A hook can have a `before` phase, an `after` phase, or both. Each gets a context for the current call:

| Member | Description |
| --- | --- |
| `thisObject` | the receiver, `null` for a static method |
| `method` | the hooked method |
| `args` | the arguments, editable in `before` only |
| `returnValue`, `throwable` | the outcome, available in `after` only |
| `setReturnValue(v)` | In `before`: skip the original and return `v`. In `after`: replace the result |
| `setThrowable(t)` | In `before`: skip the original and throw `t`. In `after`: replace the outcome with an error |
| `extra` | any extra JS values you want to carry from `before` to `after` of the same call |

```ts
const JMath = inu.jvm.cls('java.lang.Math')
inu.xposed.hookMethod(JMath.getDeclaredMethod('floorMod(II)I'), {
  before(ctx) {
    ctx.args[1] = 7
    ctx.extra = Date.now()
  },
  after(ctx) {
    if (ctx.returnValue === 0) ctx.setReturnValue(1)
  },
})
```

Quirks:

- Use `setReturnValue` and `setThrowable`, **not** `ctx.returnValue = x`.
- Changed arguments are converted to the parameter types with the [value rules](./jvm.md#values-crossing-the-bridge).
- A result you set must fit the return type.
- Values in `ctx` are read lazily and only while the call is running. Read what you need inside the
  phase. Keeping `ctx` and reading from it later throws `handle-expired`.
- If your callback throws, the error is logged and the call continues as if the phase did
  nothing. A hook never crashes app code by accident. `setThrowable` is how you throw on purpose.

## Several hooks, several plugins

Many plugins can hook the same method. The app keeps one real hook and chains them.

`before` phases run in registration order, `after` phases in reverse order.

`inu.xposed.callOriginalMethod(method, thisObject, args)` can be used to calls the original,
skipping *any* hooks.

## Threads

A hooked method runs on whatever thread called it: the UI thread, a network thread, a
background worker. Your JS phases run **on that thread**, synchronously, while the app waits.

To run JS, the thread has to borrow your plugin's engine, which only one thread can use at a
time (see [engine.md](./engine.md) and [jvm.md](./jvm.md#callbacks-into-js)). The hooked thread
waits for it. Your plugin thread hands the engine over whenever it is inside a Java call made
through `inu.jvm` or `callOriginalMethod`, so the wait is at most one JS turn of it. The 250 ms
phase budget starts once the hook has the engine, and Java calls the hook makes count toward it.

A JS phase is still skipped when:

- another app thread holds your engine for over 2 s (slow Java/JS call)
- the hooked thread is inside another of your callbacks already, for example a runnable called
  a hooked method (a call your plugin-thread JS makes through `inu.jvm` runs the hook nested);
- the call happened inside one of your own hook phases (the recursion rule above);
- the plugin is stopping/stopped

`before` and `after` are entered separately, so `after` can be skipped even though `before` ran.

What this means for you:

- **Prefer a routine hook for security or privacy features.** While a JS phase only skips in the edge
  cases above, it is still possible for it to be skipped, unlike routine hooks that are **always** ran
- Keep all synchronous work in your plugin short. A long `for` loop on the plugin thread stalls everything else
- Every JS phase costs a thread handoff. On a hot method (layout, drawing, message binding, anything called per list item), this adds up fast.

## Routine hooks

A second option is using [routines](./routines.md).

In short, routines are a JS subset, compiled into an IR executed fully in Java. They never
enter the JS engine, so they don't need to wait for it to become free.

```ts
const Window = inu.jvm.cls('android.view.Window')
const FLAG_SECURE = 0x2000
inu.xposed.hookAllOverloads(Window, 'setFlags', {
  before: inu.xposed.routine((ctx) => {
    ctx.args[0] = ctx.args[0] & ~FLAG_SECURE
    ctx.args[1] = ctx.args[1] & ~FLAG_SECURE
  }),
})
```

## Filters: routine gate, JS body

Sometimes you actually do need a JS hook, but only in some very specific cases.
You can then combine the best of both worlds - a routine filter + a JS hook:

```ts
const TextView = inu.jvm.cls('android.widget.TextView')
// ❌ don't
const off = inu.xposed.hookAllOverloads(TextView, 'setText', {
  before(ctx) {
    if (ctx.args[0] !== 'secret') return
    ctx.args[0] = '[hidden]'
  },
})
// ✅ do
const off = inu.xposed.hookAllOverloads(TextView, 'setText', {
  filter: inu.jvm.routine((text) => {
    return text !== null && text.toString().contains('secret')
  }),
  before(ctx) {
    ctx.args[0] = '[hidden]'
  },
})
```

- `this` in the filter is the receiver, and its parameters are the call's arguments.
- If the filter throws, the hook runs anyway

## Other helpers

- `inu.xposed.allocateInstance(cls)` creates an object without running any constructor.
- `inu.xposed.disableProfileSaver()` turns off the ART profile saver for the process. Only
  use this if you notice hooks not running on an ART-optimized method. It is rarely needed.

