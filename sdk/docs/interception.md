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
| `interceptSendMessage` | 60 s per send | the message is sent as-is |
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

`interceptSendMessage` runs when the user sends or edits a message. It covers text messages,
single media, albums and edits. It hands you an `OutgoingMessage` with the fields you usually
care about, and you answer `'send'` or `'drop'`.

```ts
inu.interceptSendMessage({ text: /^\/shrug\b/ }, ({ message }) => {
  const rest = message.text.text.replace(/^\/shrug\s*/, '')
  message.text = { text: `${rest} ¯\\_(ツ)_/¯`.trim(), entities: [] }
  return 'send'
})
```

### Filter first

Use the filter form when you can. The filter runs inside the app, so messages that do not match
never reach JS:

- `text` is a `RegExp`. It is compiled by Java's regex engine, so syntax Java does not support
  throws when you register.
- `isEdit: true` matches only edits, `false` only new messages. Leave it out for both.

For an album, the filter reads the caption of the first item.

### What you can change

| Field | Notes |
| --- | --- |
| `text` | a string or `TextWithEntities`. For media it is the caption; for an album, the first item's caption |
| `peer` | a marked peer id. The new peer must be cached, or it throws `not-found` |
| `replyToMessageId`, `topicId` | not available on edits |
| `scheduleDate`, `silent` | `silent` not available on edits |
| `media` | replace the media array with a raw `InputMedia` |
| `setMedia(file)` | replaces the media of a text or single-media send, with the upload shown in the bubble |

### Verdicts

THe interceptor can return one of:

- `'send'` - send the message normally, including any applied edits.
- `'drop'` - cancel sending the message

If you plan to `drop`, it is recommended to return it ASAP (within 100ms of invocation),
so that the "sending" bubble is never actually shown.

### Mixing with interceptRpc

`interceptSendMessage` runs in the same chain as `interceptRpc` middleware for the send methods.
An outer `interceptRpc` stage can catch a failed `next()` and retry or answer, but it cannot undo
a drop.

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
