# Inugram plugin docs

Inugram supports plugins. A plugin is a JavaScript file that extends the app's functionality in some way. Under the hood, plugins run under QuickJS runtime.

To make plugins a bit safer to use, plugins are required to request for permissions ("grants") explicitly
before they can use most of the APIs.

These docs explain the concepts, the reasons behind them and the quirks.
For exact signatures, read the typings in `@inugram/plugin-types`.

## Stability

Plugins in Inugram are **experimental** as a whole,
and can be considered as a *developer preview* more than
anything else. Bugs, crashes, and potentially even exploits are all to be expected.

**Do not** rely on them for mission-critical tasks,
and prefer the pluginless build if you aren't using them

### API stability

APIs marked `@experimental` in the typings may change signatures or documented behavior, or be removed, between releases.
Breaking changes will be called out in release notes, and plugins using these APIs may need updates.

It doesn't mean you shouldn't use them (in fact, we
actively encourage you to do so!), just beware that your
plugin may break with future updates.

Inugram API stability does not freeze Telegram's internals: Java classes/methods can
change with upstream updates, as well as the TL schema.

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
