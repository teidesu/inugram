# Telegram APIs

This page covers the Telegram side of the API: accounts, reading and writing account data, raw
RPC calls, TL objects and formatted text. Hooks that sit in front of the app's own traffic are
in [interception.md](interception.md). Full signatures are in `common.d.ts`.

The APIs will feel familiar if you ever used [mtcute](https://mtcute.dev)

## Accounts

The app can have several logged-in accounts. Everything that reads or acts on Telegram data
goes through an `inu.Account` handle.

| Call | What it gives you |
| --- | --- |
| `inu.account()` | the active account |
| `inu.account(id)` | the account in slot `id` |
| `inu.accounts()` | every logged-in account, in order |
| `inu.onAccountsChanged(cb)` | called when accounts are added, removed or reordered |
| `inu.withCurrentAccount(cb)` | runs `cb` for the current account, and again on every switch |

`withCurrentAccount` is the easiest way to keep per-account state. Return a cleanup function
and it runs when the user switches away:

```ts
inu.withCurrentAccount((account) => {
  console.log('logged in as', account.id)
  return () => {
    console.log('account switched from', account.id)
  }
})
```

`account.id` is a slot index, not a user id. Slots are reused when an account logs out.
Do not keep an `Account` handle around for a long time. Get a fresh one from `inu.account()`, or react to `onAccountsChanged`. In most cases you don't even need to use the `inu.account()` function at all,
since an `account` is passed on most of the high-level handlers

## Peers and marked peer ids

Most calls take an `InputPeerLike`. It can be:

- a marked peer id (like in Bot API or mtcute)
- `'me'` or `'self'`
- a username, with or without the `@`
- a TL object that names a peer: `Peer`, `InputPeer`, `InputUser`, `InputChannel`, `User`, `Chat`

`inu.utils.peers` has utils for converting between the forms.

Few quirks:
- TL objects still carry raw ids: `peer.channel_id` is `123`, not `-1000000000123`.
- The app's Java code (`inu.jvm`, `inu.xposed`, etc) uses a simpler schema, where both chats and channels are simply negated. Use `toSimpleDialogId`

**Secret chats are generally invisible to plugins**

## TL objects

Most of the plugin API surface are `TLObject`-s, which are kinda just JS objects:

```ts
const peer: tl.TypeInputPeer = { _: 'inputPeerUser', user_id: 42, access_hash: '123' }
```

There's a quirk however - TL objects can either be **live** or **detached**.
On the surface they behave largely the same, but they are slightly different under the hood

### Live objects

Live TL objects are basically a `Proxy` backed by a real `TLObject` in the Java heap.

This is a thing for two main reasons:
- In some cases (like `interceptUpdate`), we want JS to be able to directly mutate the live object
- In other cases, it's largely a performance optimization - serializing a large TL object for JS is slow,
  and by having a live object we pretty much pass a "pointer" and lazily read the fields needed

One way to disambiguate them is:

```ts
const isJavaBacked = Symbol.for('inu.tl.handle') in value
```

For live objects, every field read is a JNI crossing, so keep a few things in mind:

- **Enumerating reads everything.** object spreading, `JSON.stringify` and `structuredClone`
  read every field. On a big object, like a history page, that might become slow. Read just the fields you need.
- **Views can be read-only.** See [Reads](#everything-you-read-is-read-only). A simple check:
  ```ts
  const isReadOnly = Object.getOwnPropertyDescriptor(view, '_')?.writable === false
  ```
- **Some views expire.** For example, views passed to interceptors can only be used within that exact microtask.
  Keep that in mind when using async of passing them around, prefer copying the fields you need locally.

### Numbers and 64-bit ids

Java `long` fields cannot always fit in a JS number. The bridge picks per field:

- ids and sizes that Telegram guarantees fit in 53 bits (user, chat and channel ids, file sizes) are `number`
- other `long` fields, such as `access_hash`, are decimal `string`s

Writing a `long` field accepts either form. A number above `Number.MAX_SAFE_INTEGER` is refused.
`bytes` fields are `Uint8Array`, and base64 strings are accepted when writing.

### What plugins never see

To avoid handing sensitive data to plugins, some data is filtered out from the high-level APIs, including:

- Login codes in messages from Telegram's service account.
- `config.autologin_token`.
- Draft text anywhere in a TL object is hidden (unless you hold `account.read(draft)`).
- `updateServiceNotification`.

While highly discouraged, you can use `unsafe.disableApiFiltering` grant to disable this filtering.

## Reads

Account-related reads need `account.read(...)` grants. See type definitions for the exact scopes.

In general, there are two kinds of reads - sync and async. In most cases, the peers you
need are already cached, so you can read them directly.

Few commonly used read methods:
- `getUser(id)` and `getChat(id)`, to get the cached peer
- `getMessages` and `getMessagesCached` to get one or more messages by their IDs
- and a bunch more, see type definitions

### Everything you read is read-only

Most of the TL objects returned by read methods are read-only, since they're backed by a real Java object
inside the app's cache. Use `structuredClone()` to get a plain, mutable copy:

```ts
const me = inu.account().getMe()
if (me !== null) {
  const copy = structuredClone(me)
  copy.first_name = 'someone else'
}
```
### Pagination

Some methods like `getDialogs`  return a `Paged` array: the items plus a `next` cursor, or `null`
on the last page. Pass `next` back as `cursor` to get the following page.

A cursor is opaque and short-lived:

- It only works with the list that made it. A dialogs cursor passed to `getTopics` is rejected.
- Only the 32 newest cursors are kept. An older one throws `invalid-argument`.
- Cursors do not survive a plugin reload.

If you just want every item, use the iterators instead. `iterDialogs`, `iterHistory` and
`iterTopics` page for you. `limit` caps the total, and `batchSize` sets the page size (default
100). Nothing runs until the first `next()`, so argument errors show up there.

```ts
for await (const dialog of inu.account().iterDialogs({ limit: 500 })) {
  console.log(dialog.peer)
}
```

### Asking for fields up front

Some methods (e.g. `getDialogs`/`iterDialogs`) accept a `fields` parameter.
As we already mentioned, reads are **live** TL objects, so reading every field costs a JNI crossing.
To avoid N+1 crossings, you can pass a list of fields which will be passed to JS along with the handle,
skipping the JNI crossing.

## Writes

Account-related reads need `account.read(...)` grants. See type definitions for the exact scopes.

```ts
async function greet(username: string) {
  const account = inu.account()
  const peer = await account.resolvePeer(username)
  await account.sendMessage(peer, md`hello from **inugram**`)
}
```

### Sending messages

`sendMessage`, `sendMedia` and `sendMultiMedia` resolve with the sent `Message`.

By default a send is `optimistic`: the message shows up in the chat at once, as if the user
sent it. Set `optimistic: false` to send in the background.
An optimistic send does not work with `sendAs`, and some replies cannot be shown this way.

### Messages from your own sends skip interceptors

A message your plugin sends does not go through `interceptSendMessage` or `interceptRpc`, in any
plugin. This is on purpose, to avoid accidental infinite loops.

## Messages

Message is the **core** and most commonly used object in the API, so
the plugin API provides a higher-level wrapper `inu.Message` over it.

`inu.Message` wraps a TL `tl.TypeMessage` with getters for the things you usually want,
exposing `raw` as the TL object underneath.

Prefer the getters over reading `raw` yourself. Telegram's message format has many edge cases,
and the getters handle them.

You can also wrap a raw message yourself with `new inu.Message(raw)`.

`account.previewMessage(message)` gives the text the app shows in the chat list, such as
"📷 Photo" or a service message in the user's language.

## Raw RPC

`inu.invokeRpc` (current account) and `account.invokeRpc` can call any API method.

Using this API requires an `invokeRpc` grant, scoped per method: `invokeRpc(messages.getHistory)`.

```ts
const config = await inu.invokeRpc({ _: 'help.getConfig' })
```

- A server error rejects with `inu.RpcError`, which has `code` and `text`.
- As mentioned above, some methods are filtered out, since they can take over the account,
  like `auth.*`. While discouraged, you can use `unsafe.disableApiFiltering` to opt out.
- Your own calls skip every RPC interceptor.

Additionally, there's an `invokeRaw` method that allows sending raw TL bytes and gives raw bytes back.
It needs `unsafe.invokeRaw` and exists primarily for methods newer than the app's layer.

A response from a newer layer can break the connection state (the server might think we're on a higher layer,
and start sending updates the app won't understand), so avoid it if `invokeRpc` works.

### Takeout

`account.initTakeoutSession()` opens a data export session. It has higher rate limits for reading
history. For new sessions, Telegram might ask the user to approve it.
Calls go through `session.invokeRpc`, which needs both `takeout` and the `invokeRpc` scope for the method.

Call `finish()` when you are done.

## Formatted text

Text with formatting is a `TextWithEntities`: a string plus TL `MessageEntity`s. Offsets are
UTF-16 code units, the same as JS string indexes. Anywhere the api takes `InputText`, a plain
string also works.

`inu.utils` has three parsers, based on mtcute's implementations, exposed as tagget template literals:

| Format | Syntax |
| --- | --- |
| `md` | `**bold**`, `__italic__`, `--underline--`, `~~strike~~`, `\|\|spoiler\|\|`, `` `code` ``, fenced pre, `[text](url)`, `> quote` |
| `html` | Telegram's HTML subset. Whitespace collapses like real HTML; use `<br>` for line breaks |
| `thtml` | `html`, but whitespace is kept and the template is dedented first, like the Bot API |

```ts
function formatScore(name: string, score: number) {
  return inu.utils.md`**${name}** scored ${score}`
}
```

The API is prety much the same as mtcute, so just refer to [mtcute docs](https://mtcute.dev/guide/topics/parse-modes.html) for more details, not to repeat all that here.

## Events

There are a few methods that are events you can subscribe to, to do something when something happens.

| Call | Grant |
| --- | --- |
| `inu.onNewMessage(cb)` | `onUpdate(new_message)` |
| `inu.onMessageEdited(cb)` | `onUpdate(edit_message)` |
| `inu.onMessageDeleted(cb)` | `onUpdate(delete_message)` |
| `inu.onUpdate(types, cb)` | `onUpdate(updateNewMessage, ...)`, one scope per constructor |

The message events cover every account and hand you the `Account` the event came from. They see
new messages from both live updates and catch-up after reconnecting. Local and scheduled
messages are not included. Note that this callback sometimes runs before the app has applied the update,
so chat state may not reflect it yet.

`onUpdate` takes raw TL update constructor names. The update is a read-only **live** view.

The two grant forms are separate: `onUpdate(new_message)` does not allow
`onUpdate('updateNewMessage', ...)`, and the other way round.

A bare `onUpdate` grant allows everything.

## Notifications

You can suppress notifications using `inu.notifications.suppress()`
(or per-account `account.suppressNotifications()`), which can be useful e.g. for custom in-app notifications plugins
