# Interception

Interceptors allow plugins to modify the data the app sends or receives, enabling various fun stuff.

Currently there are three interceptor methods:

- `inu.interceptRpc`: intercept RPC requests, allowing to rewrite them or modify/replace responses
- `inu.interceptSendMessage`: intercept messages sent by the user before they're actually sent
- `inu.interceptUpdate`: intercept updates sent by the server before they're applied by the app

Avoid using interceptors to listen for events, as they slightly slow down the app.

## Quirks to keep in mind

- **The app waits for you.** While your middleware runs, the app waits for your response.
  Avoid heavy computations, keep middleware short, and register for the narrowest set of methods/types you need.

- **Your own traffic skips interceptors.** Requests from `invokeRpc` and account write methods skip
  **all** interceptors (not just yours)
- **Plugins run in the user's order.** When several plugins intercept the same thing, they run in
  the order the user arranged plugins in the app.
- **Views expire.** The request, response or update you are handed is a view over the app's **live** object,
  and is only valid during the call. Once it is over, reading it throws `handle-expired`. Copy the
  fields you need before you return.
- **Each call has a time budget.** Only time spent in JS counts, not time waiting for the server.

**Limits**:

| Interceptor | Budget | When it runs out |
| --- | --- | --- |
| `interceptRpc` | 10 s per request | the app gets the real response if it was already sent, otherwise a timeout error |
| `interceptSendMessage` | 60 s per send | the send fails and nothing is sent |
| `interceptUpdate` | 2 s per batch of updates | updates are delivered as-is |

In addition to the object being intercepter, you also get a `context.signal` AbortSignal,
which you can use to determine if the continuing the middleware is still worthwhile.

## interceptRpc

`interceptRpc` is a middleware, inspired by Koa and alike. If you've used mtcute's network middleware,
this is pretty much it. You get the request and a `next` function, which continues the middleware chain.

```ts
inu.interceptRpc('messages.getHistory', async (context, next) => {
  context.request.limit = Math.min(context.request.limit, 20)
  const response = await next()
  if (response !== null && response._ !== 'messages.messagesNotModified') {
    console.log('got', response.messages.length, 'messages')
  }
  return response
})
```

The grant is scoped per method: `interceptRpc(messages.getHistory)`.
API filtering still applies to middlewares

### What you can do

- **Rewrite the request.** `context.request` is writable. Change fields and call `next()`. You can
  also pass `next` a custom request (but it must be the same method).
- **Change the response.** Await `next()`, change the result or build a new one, and return it.
- **Answer without the server.** Return a response without calling `next()`. The request is
  never sent.
- **Fail the call.** Return or throw an `inu.RpcError`. The app sees it as a server error.

### What you return

| You return | The app gets |
| --- | --- |
| a TL object | that object as the response |
| an `inu.RpcError` (returned or thrown) | that error |
| `undefined`, after calling `next()` | whatever `next()` resolved with |
| `undefined`, without calling `next()` | an error. Returning nothing is a bug, not consent |
| `null` | an empty response. Most app code does not expect one; fail with an `RpcError` instead |
| a thrown non-`RpcError` | an error, and the throw is logged |

`next()` can only be called once. Calling it twice throws.

If you return something that is not a valid TL object, the default is to log it and skip your
middleware, as if you had not been there. Pass `{ strict: true }` to fail the app's call instead.
Use strict mode when a bad response from you would be worse than no response.

### Several methods at once

Pass an array of methods to share one middleware between them. The return type is then limited
to what all of them return. For methods with different response types, register them one by one.

## interceptSendMessage

`interceptSendMessage`, its outgoing-message/media types, and `account.createLocalMedia` are [experimental](README.md#api-stability).

`interceptSendMessage` runs when the user sends a message: text, files and albums, stickers, gifs,
locations, contacts and forwards. It hands you an `OutgoingMessage` with the fields you usually care
about, and you answer `'send'` or `'drop'`.

```ts
inu.interceptSendMessage({ text: /^\/shrug\b/ }, ({ message }) => {
  const rest = message.text.text.replace(/^\/shrug\s*/, '')
  message.text = { text: `${rest} ¯\\_(ツ)_/¯`.trim(), entities: [] }
  return 'send'
})
```

It does not see edits, polls, games, invoices, stories, quick replies, scheduled messages sent now,
secret chats, or messages plugins send.

### Filter first

Use the filter form when you can. The filter runs inside the app, so messages that do not match
never reach JS:

- `text` is a `RegExp`, or `true`/`false` for a send with or without any text. A regex is
  compiled by Java's regex engine, so syntax Java does not support throws when you register.
  It runs on the app's send path with no time limit, so keep it cheap: a nested quantifier
  like `(a+)+` can backtrack for seconds and freeze every send. Registering one logs a warning.
  If matching fails, the message goes to your middleware as if it matched.
- `peer` is a marked peer id or a peer type (`'user'`, `'group'`, `'broadcast'`), or an array of
  them: the hook only sees sends to those chats.
- `media` is `true`/`false` for a send with or without media, or one or more `MediaType`s (as
  `Message.mediaType` names them), matched when any item is of one of them. A document a plugin
  added by id has no type, so it matches only `true`.
- `forward` picks sends with (`true`) or without (`false`) a forward.
- `stage` picks when the hook runs, see [Stages](#stages).

Every field given must match. For an album, the filter reads its caption. A forward without a
comment has no text, so a `text` filter never matches it. Each stage's filter reads the message as
the stages before it left it.

### Stages

By default a hook runs at the `compose` stage: as soon as the user sends, before the app draws the
message, processes its files or uploads anything. The app sends nothing until every compose stage
has answered, and later sends to the same chat wait behind it, so they still arrive in order. A text
message held longer than 100 ms is drawn as sending in the meantime, along with the texts sent after
it, and turns into whatever the stages made of it, or goes away if they dropped it. If the user
cancels it while it waits, it is not sent, whatever the stages answer.

`{ stage: 'uploaded' }` runs for messages with media, once it is uploaded, right before the message
goes out, after every compose stage. The message is already drawn by then.

### What you can change

`message` is a plain snapshot of the send. Edit it in place or assign to it; your edits are applied
when you return `'send'`. An edit that cannot be applied fails the send, after your middleware has
returned.

At the compose stage:

| Field | Notes |
| --- | --- |
| `text` | a string or `TextWithEntities`. For media it is the caption |
| `peer` | a marked peer id. The chat must be one the account knows, or the send fails with `not-found` |
| `reply` | a `PendingReply`: message id, the chat it is in when that is another chat, and a quote |
| `forward` | a `PendingForward`: source chat, message ids and mode. Set it to `null` to send no forward. Setting `text` or `media` on a forward sent alone adds a comment, which goes first |
| `topicId` | the topic the message goes to |
| `scheduleDate`, `silent` | |
| `media` | a file on the device is a `LocalMedia`: reorder, cut, or add to them with `account.createLocalMedia(file)`. `media.blob()` reads the picked file, and `createLocalMedia(await media.blob(), { fileName })` re-wraps it without copying, for example under another name. A LocalMedia also carries `width`, `height` and `duration` when the file tells them. An edited photo's blob has its edits; an `edited` video's is the original, which the app trims, crops and filters only after the send is decided. Media already on the server, such as a sticker or a location, is its `InputMedia`, which you can replace with any other or put in an album with LocalMedia, up to 10 items. With no media left, the text goes alone |

The app sends what you left as if the user had sent it. LocalMedia is drawn and uploaded like a
file the user picked; other `InputMedia` is shown once the server has it.

At the uploaded stage only `text`, `media` and `silent` may change. `media` holds the uploaded `InputMedia`:
reorder, cut, or add to it, and a single file can become an album. With no media left, the text
goes alone. A `LocalMedia` is refused. In a chat that charges per message, the number of items
cannot change.

### Verdicts

The interceptor can return one of:

- `'send'` - send the message, with your edits.
- `'drop'` - cancel sending the message.

A throw, a rejection or running out of budget fails the send.

### Mixing with interceptRpc

`interceptSendMessage` runs before any `interceptRpc` middleware sees the request the app makes for
the message.

## interceptUpdate

`interceptUpdate` sees updates before the app applies them. The update is writable, you can change it in
place and answer `'deliver'`, or answer `'drop'` to hide it from the app.

```ts
inu.interceptUpdate('updateNewMessage', ({ update }) => {
  const message = update.message
  if (message._ === 'message' && message.message.includes('spoiler')) return 'drop'
  return 'deliver'
})
```

The grant is scoped per update constructor: `interceptUpdate(updateNewMessage)`. An unknown
constructor throws when you register.

### How updates flow

- Updates reach interceptors one at a time, in the order they arrived. Each passes through every
  interceptor in plugin order. Once one plugin drops an update, later plugins do not see it.
- The account's whole update stream waits while an interceptor runs. A slow interceptor delays
  every update behind it, not just the one it is looking at.
- Updates from catch-up after a reconnect go through interceptors too.
- Compact updates the server sends for short messages arrive as the full update they stand for,
  such as `updateNewMessage`.
- `onUpdate`, `onNewMessage` and the other events see an update after interception. They see
  your changes, and they never see dropped updates.
- A dropped update is not re-fetched later. Telegram's sequence numbers are still accounted for,
  so the app does not notice a gap.

### Failures deliver

Anything other than `'deliver'` or `'drop'`, a throw, a rejection or running out of budget all
deliver the update and log the problem.

## Performance

- Register for exact methods and update types. A registration costs nothing for traffic it does not match.
- Use the `interceptSendMessage` filter so most messages never enter JS.
- Read fields by name. Enumerating a view, spreading it or cloning it reads every field.
- Do not await slow work, such as network calls, in `interceptUpdate`. It holds the whole
  stream. Deliver first and do the work in an `onUpdate` handler instead.
