# Grants

A grant is a permission a plugin declares in its manifest. The user sees the list before
installing, and the plugin gets exactly that. There is no runtime permission prompt: a plugin
cannot ask for more later, only ship an update that asks for more, which the user must approve.

Each API's typings name the grant it needs with a `@needs-grant` JSDOC tag. Everything without that
tag, such as `localStorage`, timers or `inu.account()` itself, needs no grant.

## Syntax

```ts
grants: [
  'onUpdate(new_message, edit_message)',
  'account.read(peers, messages)',
  'fetch(api.example.com)',
  'fs(200mb)',
  'clipboard.write',
]
```

A grant is a name, optionally followed by scopes in parentheses, separated by commas.

The same name may appear more than once; the scopes add up. A grant without scopes covers every scope.

## Scope kinds

The catalogue (`grants.json`) says which kind of scope each grant takes:

| Kind | Grants | A scope is | Matching |
| --- | --- | --- | --- |
| none | `clipboard.*`, `openUrl`, `interceptSendMessage`, `takeout`, `unsafe.*`, ... | n/a | |
| list | `account.read`, `account.write` | pre-defined list of scopes | exact |
| domain | `fetch` | a host name | the host or any subdomain of it, case-insensitive |
| size | `fs` | a quota such as `200mb` (`kb`, `mb`, `gb`) | the default is 50 MB |
| RPC method | `invokeRpc`, `interceptRpc` | a TL method name such as `messages.getHistory` | exact, one scope per method |
| update type | `onUpdate`, `interceptUpdate` | a TL update constructor such as `updateNewMessage` | exact |

`onUpdate` also accepts `new_message`, `edit_message` and `delete_message`, which map to
higher-level `inu.onNewMessage`, `inu.onMessageEdited` and `inu.onMessageDeleted` respectively

## How checks work

- **Checks fail closed.** A call your grants do not cover throws or rejects with
  `inu.PluginError` code `not-granted`. `error.grant` holds the exact token to add to your
  manifest.
- **Some namespaces only exist with their grant.** Without `fs` or `unsafe.fs`, `inu.fs` is
  `undefined`. The same holds for `inu.jvm` without `unsafe.jvm`, and `inu.xposed` without
  `unsafe.xposed`. Feature-test before you use them.
- **`unsafe.xposed` also needs `unsafe.jvm`.** Hooks work in terms of JVM handles, so without
  `unsafe.jvm` the `inu.xposed` namespace is not installed at all.
- **An unknown grant name is ignored.** The app installs the plugin and the name gives no access.
  This keeps a plugin written for a newer app installable on an older one. `inu check` warns
  about it, because a typo silently costs you the API.
- **An unknown or malformed scope is refused.** A known grant with a bad scope, such as
  `account.read(peer)` or `fetch(foo)`, blocks installation. A typo there would otherwise narrow
  the grant to nothing. `inu check` reports the same errors the app would.

## Takeover protection

To prevent account takeover, some of the APIs are protected by a "takeover filter".
You can read more about it in [telegram.md](telegram.md).

`unsafe.disableApiFiltering` lifts the takeover filter. It is marked dangerous, and it exists for
plugins whose whole purpose is account management.

## The `unsafe.*` grants

Some grants are "unsafe", because granting them is a potential security risk, as they bypas any
JS sandboxing:

| Grant | Gives |
| --- | --- |
| `unsafe.jvm` | access to `inu.jvm`, i.e. [Java reflection](jvm.md) |
| `unsafe.xposed` | access to `inu.xposed`, i.e. [xposed-style hooking](xposed.md), requires `unsafe.jvm` |
| `unsafe.fs` | unscoped `inu.fs` file system access, with no quota |
| `unsafe.invokeRaw` | access to `inu.invokeRaw` |
| `unsafe.notificationCenter` | access to Android app's NotificationCenter, requires `unsafe.jvm` |
| `unsafe.disableApiFiltering` | bypass the takeover filter |
