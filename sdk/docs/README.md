# Inugram plugin docs

Inugram supports plugins. A plugin is a JavaScript file that extends the app's functionality in some way. Under the hood, plugins run under QuickJS runtime.

To make plugins a bit safer to use, plugins are required to request for permissions ("grants") explicitly
before they can use most of the APIs.

These docs explain the concepts, the reasons behind them and the quirks.
For exact signatures, read the typings in `@inugram/plugin-types`.

## TOC

- [getting-started.md](getting-started.md): how to get up and running with the plugins SDK
- [telegram.md](telegram.md): how to actually use the Telegram-related APIs
- [interception.md](interception.md): intercepting RPCs, updates and outgoing messages
- [grants.md](grants.md): detailed explanation of permissions
- [ui.md](ui.md): declarative UI and actions
- [canvas.md](canvas.md): `inu.canvas`, as an alternative to PIL
- [engine.md](engine.md): runtime environment of the plugins
- [io.md](io.md): `fetch`, files, blobs, `localStorage`, the clipboard
- [jvm.md](jvm.md): unsafe low-level JVM reflection access via `inu.jvm`
- [xposed.md](xposed.md): unsafe low-level xposed-style hooks via `inu.xposed`
- [routines.md](routines.md): "routines", a small JS subset, compiled into an IR and executed fully in Java
