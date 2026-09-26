# The engine

This page explains some lower-level details of the plugin's engine
and lifecycle.

## One engine per plugin

Each plugin runs in its own QuickJS engine, with its own globals. Plugins share nothing.

Your bundle runs as a classic script in strict mode, not as a module. This means:

- There is (currently) no top-level `await`. Wrap async start-up work in an async function
- There is no `import` at runtime. Use CLI to bundle everything into one file

The engine is recent QuickJS, so modern syntax works, including `using` declarations and
`DisposableStack`.

## Threads

All plugin engines share one dedicated thread. Your top-level code, timers, event callbacks,
promise continuations and API responses all run there, one JavaScript turn at a time. It is
never the Android UI thread, so a slow plugin does not freeze the app. It does delay every other
plugin, though, so keep each turn short.

APIs that need the UI thread or the network hop there themselves and hand the result back to
your thread. That is why most of them return promises.

There are exceptions though. A JVM callback or an Xposed hook can (and probably will) run on
whatever thread the app calls it from. See [jvm.md](jvm.md), [xposed.md](xposed.md) and
[routines.md](routines.md) for how that works.

You can think of this as Python's GIL - the engine can only run one thing at a time.

## Lifecycle

### Start

Most of the plugins will only start when the app is opened. However,
in some cases the plugni needs to be started early, e.g. to process a push notification.
If your plugin requests one of these grants, the app will run it before any UI is shown:

- `interceptRpc`
- `interceptUpdate`
- `interceptSendMessage`
- `onUpdate`
- `unsafe.notificationCenter`

The app waits up to 500 ms for these to register before it processes updates. That wait counts
against every push notification, so do cheap work at the top level and register your interceptors
first.

Top-level evaluation has 10 seconds. If it throws, the plugin fails to load and is disabled.

### Stop

A plugin stops when the user disables, reloads, updates or removes it, and when the engine is
turned off.

When the plugin is stopped, the plugin engine automatically releases all its listeners,
handlers, resources and disposers.

Note that in some cases you might need to release resources yourself, e.g.
when heavily relying on `inu.jvm` reflection, in which case you can use `inu.onUnload` to
register a cleanup handler:

```ts
inu.onUnload(() => {
  // release resources here
})
```

### Disposers

Every API that registers something returns a `Disposer`: call it to undo the registration.
Calling it twice is safe. It is also a `Disposable`, so it works with `using` and
`DisposableStack`:

```ts
const subscriptions = new DisposableStack()
subscriptions.use(inu.onNewMessage(message => console.log(message.text)))
subscriptions.use(inu.onAppVisibilityChange(mode => console.log(mode)))

inu.registerSettings(inu.ui.settingsPage({
  title: 'Listener',
  items: () => [
    inu.ui.button({ text: 'Stop listening', onClick: () => subscriptions.dispose() }),
  ],
}))
```

### Reloading

When a plugin is reloaded (e.g. updated, restarted or re-enabled), only the code re-runs.
Its storage and data is preserved, including:
- `localStorage`
- the scoped `inu.fs` directory
- files `inu.jvm` staged, such as loaded dex

Uninstalling, however, wipes everything the install owned.

## Errors

Sometimes a plugin can crash. Crashed plugins are automatically disabled, and the user
sees an error in the plugins page, as well as a "plugin crashed" notice.

A plugin can crash in a few ways:
- Top-level code throws
- A callback the engine calls throws and nothing catches it
- An unhandled promise rejection
- A turn runs out of time (see below).

To avoid unhandled rejection crashes, attach `.catch` to every promise you do not `await`:

```ts
void inu.account().sendMessage('me', 'hi').catch((error) => {
  console.warn('send failed', error)
})
```

The error text goes to the plugin's log, visible via `inu dev`

### `inu.PluginError`

The error most `inu.*` APIs throw is `inu.PluginError`. Most of the time, they are unrecoverable.

| Code | Meaning |
| --- | --- |
| `not-granted` | The plugin is missing a grant |
| `forbidden` | The resource is protected (e.g. takeover protection) |
| `quota-exceeded` | You hit a quota limit (storage/memory/etc). `usage` and `quota` are set where they apply |
| `handle-expired` | You are trying to access an expired handle (a disposed blob, a stale cursor, a dead TL view) |
| `unknown-constructor` | You are trying to use an unknown TL constructor |
| `invalid-argument` | Wrong type or value |
| `not-found` | Something you're trying to access does not exist |
| `unsupported` | This feature is not available in this app build or in this situation (this normally shouldnt happen) |
| `timed-out`, `aborted`, `network` | Network issues |
| `internal` | An engine bug. Please report it |

### `inu.RpcError`

When an RPC call fails, an `inu.RpcError` is thrown.
It contains the server's `code` and `text` for a failed Telegram API call.

```ts
try {
  await inu.invokeRpc({ _: 'help.getConfig' })
} catch (error) {
  if (error instanceof inu.RpcError) {
    console.warn(`server said ${error.code} ${error.text}`)
  } else {
    throw error
  }
}
```

### `DOMException`

To match the Web APIs, some of the web-shaped APIs throw `DOMException`s.

This includes `localStorage`, `fetch`, `inu.canvas` and some other APIs.

Most of the time, those are also unrecoverable, so there isn't much point in catching them.

## Limits

| Limit | Value | When you hit it |
| --- | --- | --- |
| One turn (callback, timer, promise job) | 2 s of uninterrupted work | The plugin gets disabled |
| Top-level evaluation | 10 s | The plugin gets disabled |
| JavaScript heap | 32 MB | The allocation throws. Uncaught, that stops the plugin like any error |
| Native memory (blobs, canvas, buffers) | 64 MB | `PluginError` `quota-exceeded`, and nothing is allocated |
| Elements in one array passed to an API | 65536 | `invalid-argument` |
| Active timers | 512 | `quota-exceeded` |
| Log lines | 200 per 10 s | Further lines are dropped, with one warning |

The time limit is intentionally unrecoverable. The limit resets each time control returns to the
engine, so long work is fine if you split it with `await` or timers.

Deep recursion throws a normal, catchable `RangeError: Maximum call stack size exceeded`.

Per-API limits are listed in the typings as **Limits:** notes.

## Web-like globals

The engine provides a small, web-shaped standard library. Most of it behaves as you expect.
The differences:

| Global | Difference from browsers |
| --- | --- |
| `setTimeout`, `setInterval` | 4 ms minimum interval. While the app is in the background, timers fire at most once a second, and after 5 minutes at most once a minute |
| `fetch` | Needs the `fetch` grant. No `Request`, `FormData` or streaming bodies. Has a `timeout` option. **Bypasses the app's proxy.** See [io.md](io.md) |
| `localStorage` | Synchronous, private, 1 MB. Has an extra `setItems` for atomic batches. See [io.md](io.md) |
| `Blob`, `File` | Can be backed by a file on disk. Have `dispose()` to free memory early |
| `TextEncoder`, `TextDecoder` | UTF-8 only |
| `crypto` | Only `getRandomValues` and `randomUUID` |
| `console` | Formats like Node's `util.inspect`. `%s %d %i %f %j %o %O %c` work |

There is no `window`, `document`, `navigator`, `process`, `require` or `Buffer`. Use `globalThis`.

