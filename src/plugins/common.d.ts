/*
a plugin is a single js file with a userscript-style metadata header:

// ==UserScript==
// @name         My awesome plugin
// @author       teidesu
// @version      1.0
// @description         This script rocks.
// @description:zh-CN   这个脚本很棒！
// @icon https://my.cdn.com/icon.png
// @grant        none
// @plugin-api   1
// @platform   android
// ==/UserScript==

directives im unsure about:
- @require, @resource (you should just bundle them)
- @inject-into
- @downloadURL (do we want auto-updates?) also should probably support t.me message links

there is no `@run-at`. **when a plugin runs is derived from its grants**, because the grants
already answer the question the directive would have asked. a plugin holding any of `interceptRpc`,
`interceptDeserialize`, `interceptUpdate`, `interceptSendMessage` or `onUpdate` has to be live
before the app touches the network or its cache, so it runs at process start. everything else runs
when the ui first comes up, and doesn't pay for a wakeup it has nothing to do with.

if you're in the first group, **your top-level code can run with no ui on screen and no activity in
existence** — a push notification waking the process is the common case, and there the app fetches
updates, posts a notification and goes back to sleep without ever creating a screen. so top-level is
for registering things, which is all any of the `intercept*`/`on*`/`register*` calls do. anything
that needs a warm app (reading an account, pushing a page) belongs in a callback, not at top level:
`inu.withCurrentAccount` for the account-shaped cases, an action's callback for the ui-shaped ones.

there is no `@namespace`. userscripts need one because a name is all they have to tell two scripts
apart; here a plugin's identity is assigned at install and is not anything the file says, so `@name`
is a label and nothing hangs off it. rename freely — your `inu.kv` and `inu.fs` data follow the
install, not the name — and two plugins may share a name without either being able to reach the
other's storage.

@plugin-api is a MINIMUM, read like android's minSdkVersion: "this plugin needs api level >= N".
the app refuses to load a plugin declaring a level above its own (`inu.info().apiVersion`) and says
so in the plugins list, rather than letting it fail at some arbitrary call site later. a level is
never broken once shipped — the api only grows — so declaring the lowest level you actually need is
always the right move. you shouldn't have to work that out by hand: the sdk cli knows which level
each member of this surface arrived in, and stamps the right @plugin-api into the header at build
time. @platform works the same way: a plugin naming a platform this app isn't refuses to load.

that contract is also what makes everything else here safe to extend, so two rules follow from it:
- **unknown @grant tokens are ignored**, never a load failure. an older app simply doesn't install
  the api the grant would have unlocked, and the plugin should feature-detect (or declare the
  @plugin-api level that introduced it, and let the loader do the talking). an ignored grant confers
  nothing, so this can't be used to sneak capabilities past an old app.
- **unknown TL constructors throw.** naming a constructor this app's layer doesn't have (see
  `inu.info().layer`) fails the call — it is not silently dropped.

@grant-s can be `none` (default) or a comma-separated list of permissions. a grant is named after
the api it unlocks, minus the `inu.` prefix every api here shares — `account.read`, not
`inu.account.read`. several take a parenthesised scope list that narrows them; the bare form means
all of it.

every grant sits in exactly one of three tiers. the tier is a property of the *mechanism*, not of
how scary the api sounds, and it's the only thing a user should have to understand:

**safe** — scoped and enforced, and nothing personal is reachable through it even if the plugin is
malicious. the worst a safe-only plugin can do is be bad at its job. these need no warning and
shouldn't get one; a list that cries wolf about `kv` is a list nobody reads by the time it reaches
`unsafe.jvm`.

**sensitive** — scoped and enforced exactly as advertised: the plugin reaches what the grant names
and nothing else, and the scope list means what it says. what it names is the problem. these are
the grants whose *correct* behaviour is access to your data, so nothing is broken when a plugin
holding `account.read(messages)` reads your messages — that is the feature. no bug or bypass is
implied anywhere in this tier; the sandbox is intact and the plugin still walks out with something
worth stealing. that's what makes these the ones to read one at a time.

**unsafe** — the sandbox is not a boundary. the grant reaches things the api never named, no scope
could narrow it (a scope gates an entry point that the capability walks straight around), and
holding one makes the plugin's *other* grants descriptive rather than enforced. a plugin with
`unsafe.jvm` and no `account.read` still reads your messages, and there is no mechanism by which it
couldn't — so for these, the manifest stops being evidence of anything and the only real question
is whether you trust the author.

that last tier is why the prefix is in the name rather than only in this document: it's what you
skim a manifest for. it's also why unscoped filesystem access is its own grant instead of an
argument to `fs` — a scope list is for narrowing a capability, and reading it as the place where a
capability is *removed* means the most consequential thing in a header would sit mid-line, looking
like the size next to it.

-- safe --

- `kv` - the plugin's own key-value store, 1 MB
- `fs` - the plugin's own private directory, capped at 50 MB (see `inu.fs`). safer than it sounds:
  scoped, quota'd, wiped on uninstall, and it can't name a path outside itself.
  - `fs(200mb)` - a bigger cap. shown to the user as a number, so ask for what you need.
  - note that plenty of plugins that look like they need this don't: handing content *around*
    inside this api (draw it, download it, fetch it, send it, decode it) goes through `Blob`, which
    needs no `fs` at all. ask for it when you mean to *keep* something, not to move it.
- `onAppVisibilityChange` - foreground/background transitions
- `clipboard.write` - can clobber what the user copied, which is obnoxious rather than dangerous

-- sensitive --

egress first, because these are what turn every grant below them into a leak rather than a local
misfeature. a plugin holding neither can read plenty and tell no one:

- `fetch` - arbitrary http. safer variants:
  - `fetch(google.com,bing.com)` - explicit list of domains (+ subdomains) that the plugin can access
- `openUrl` - hands a url to the system browser. in this tier because a url is a *message*: the
  query string is an exfiltration channel that happens to flash a browser at the user.

then the data itself:

- `account.read` - the local user/chat/message cache. scopes:
  - `account.read(self)` - the logged-in user only: `getMe`, `getUserFull` on yourself
  - `account.read(peers)` - users and chats: `getUser`/`getChat`/`getPeer`/`getUsers`/`getChats`/
    `getUserFull`/`getChatFull`/`resolvePeer*`
  - `account.read(messages)` - `getMessage`/`getMessages`/`getMessageFile`/`downloadMedia`
  - `account.read(dialogs)` - `getDialog`/`getDialogs`/`iterDialogs`/`getTopics`/`iterTopics`
  - `account.read(history)` - `getHistory`/`iterHistory`
  - `account.read(draft)` - `getDraft`
- `account.write` - acts as the user, indistinguishably from the user. scopes:
  - `account.write(send)` - `sendMessage`/`sendMedia`/`sendMultiMedia`/`uploadFile`
  - `account.write(edit)` - `editMessage`
  - `account.write(delete)` - `deleteMessages`
  - `account.write(forward)` - `forwardMessages`
  - `account.write(react)` - `setReaction`
  - `account.write(read)` - `readHistory`
  - `account.write(typing)` - `sendTyping`
  - `account.write(draft)` - `setDraft`
- `clipboard.read` - whatever the user last copied, which is disproportionately passwords
- `interceptSendMessage` - sees and can rewrite or drop every outgoing message
- `onUpdate` - the incoming stream, read-only. the scope list mixes the demuxed event names with raw
  TL constructors:
  - `onUpdate(new_message,edit_message,delete_message)` - only the `onNewMessage`/`onMessageEdited`/
    `onMessageDeleted` conveniences, without the raw stream behind them
  - `onUpdate(updateEditChannelMessage,updateEditMessage,updateMessageContent)` - explicit list of
    updates that are visible to the plugin, which also bounds what `inu.onUpdate` itself delivers
- `interceptUpdate` - rewrites or drops incoming updates before the app sees them. separate from
  `onUpdate` because reading the stream and changing it are different powers, and most plugins that
  want the first have no business with the second. safer variants:
  - `interceptUpdate(updateNewMessage,updateEditMessage)` - explicit list of constructors
- `interceptRpc` - every request the app makes, and the ability to rewrite it. safer variants:
  - `interceptRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can intercept
- `interceptDeserialize` - same, plus everything loaded from the local cache
- `invokeRpc` - talks to telegram as this account directly. safer variants:
  - `invokeRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can invoke

-- unsafe --

none of these take a scope list, because for these there is nothing a scope could hold onto:

- `unsafe.fs` - `inu.fs` with the scoping removed: absolute paths, the app's whole storage, no cap.
  reaches another plugin's data, the message cache, the sqlite databases, and the media behind a
  `getMessageFile` path. does *not* imply `fs` — it replaces it, so declaring both says nothing
  more than declaring the one.
- `unsafe.jvm` - arbitrary reflection over the whole app. a scope could only gate the *entry point*,
  and one `JavaObject` walks to everything from there — see the note on `inu.jvm`.
- `unsafe.xposed` - method hooking. every entry point takes a `JavaMethod`/`JavaClass`, so this is
  only useful next to `unsafe.jvm` — but it does **not** imply it. list both. a grant that silently
  turns into two is a grant the user didn't read, which is the one thing this tier can't afford.
- `unsafe.notificationCenter` - the app's whole internal event bus (`inu.android`). here rather
  than in `sensitive` because every payload it hands over is a `JavaObject`, and one of those walks
  the heap exactly as `unsafe.jvm` does. the api filtering below does not apply to it and can't:
  the payloads are arbitrary java objects, not TL.
- `unsafe.disableApiFiltering` - turns off the account-takeover filtering described below. legitimate
  uses exist (a plugin that manages your sessions, or surfaces service messages properly), and they
  are indistinguishable from the illegitimate ones, which is why this is where it lives.

trying to call a method not defined in the grants will throw an error. a grant naming a scope the
app doesn't recognise (`fetch(gogle.com)`, a misspelt rpc method) is **rejected at install** rather
than silently narrowing to nothing — unlike an unknown grant *name*, whose vocabulary is open by
design, a scope's vocabulary is closed and a typo in one is always a bug.

**grants are all-or-nothing at install, by decision rather than by omission.** the user approves the
whole list or doesn't install, and the installer shows it grouped by the three tiers above — which
is the point of the tiers being a property of the mechanism: the grouping is the explanation, and a
list sorted by how alarming each line reads would be neither honest nor stable.

so a plugin can assume everything it declared is live, and `typeof inu.x` answers the only question
left ("does this app have it at all"). if grants ever become individually deniable that stops being
true, and this needs an `inu.grants()`/`inu.hasGrant()` to feature-detect against — try/catch around
every call is not an api. don't write plugins that assume it's coming.

note: `eval` and `new Function` API is NOT available

**`@not-implemented` means designed, not shipped.** members carrying that tag are in these typings
because the design is settled and moving them later would be breaking, but calling one right now
throws `PluginError` with code `'unsupported'`. they are deliberately not deleted: the shape is the
part worth agreeing on early, and a member that appears in a later api level with a different
signature than the one people already read is worse than one that says so up front. check
`inu.info().apiVersion` if you need to know, or declare the `@plugin-api` level that ships it.

**account-takeover surfaces are filtered.** a plugin that can read a login code, or mint a login
token, owns the account outright — at which point every other grant on this page is decoration. so
a fixed set of things is removed from what plugins can see and do, regardless of grants, with
`unsafe.disableApiFiltering` the only way off. four rules:

- **login codes are redacted from message text.** code-shaped runs (the same `[\d\-]{5,8}` shape
  stock spoils in the ui) are stripped from `text`/`textWithEntities` on messages *from the service
  peers* — 777000, and anyone with `UserObject.VERIFY` (489000). only from those senders, so an
  ordinary message that happens to contain a six-digit number is untouched. the message itself is
  still delivered: filtering the whole peer meant `getDialogs` showing a chat whose `top_message`
  resolved to `null`, which is a worse api for no more safety.
- **`updateServiceNotification` is not delivered** at all. it carries a login code with no peer
  attached, so there is nothing to redact against.
- **takeover rpc methods are refused**, in `invokeRpc` and `interceptRpc` alike: everything under
  `auth.*`, plus `account.`\{`getPasskeys`, `deletePasskey`, `registerPasskey`,
  `initPasskeyRegistration`, `registerDevice`, `unregisterDevice`, `deleteAccount`, `changePhone`,
  `getAuthorizations`, `resetAuthorization`, `acceptAuthorization`, `verifyPhone`, `verifyEmail`,
  `resetPassword`\}. refused rather than filtered — a scoped grant naming one of these fails at
  install, and calling one throws `forbidden` (see `PluginError`). `auth.exportLoginToken` alone is
  a complete takeover without reading a single message, which is why this list matters more than
  the redaction above.
- **`config.autologin_token` is stripped** from `help.getConfig` responses. it logs into telegram's
  web properties as the user, no code required.

**where the filter lives, and what that buys.** all of it is enforced at the single point where a
TL object is materialized for js, not per api method. that matters because there is no per-method
version that works: `messages.getMessages` takes ids from the whole personal-dialog space and will
hand back service messages nobody asked for, and updates arrive by half a dozen constructors. one
chokepoint covers every rpc, update, cache load and intercept, including ones added later.

it also means `interceptRpc`'s live views need no special case: the proxy simply declines to
materialize what's filtered, and the app's own object is never touched, so the app still receives
everything intact.

the filter is on *this* api, not on the app, so it holds across the safe and sensitive tiers and
nowhere else. `unsafe.jvm`/`unsafe.xposed` reach the same data through reflection and the sqlite
cache, and no amount of filtering here can change that — it's the definition of the tier, not a gap
in it.

**ordering between plugins.** anything that chains or fans out — `interceptRpc` middleware,
`interceptSendMessage`, `interceptUpdate`, `onUpdate` handlers — runs in the order the plugins
appear in the plugins list, which the user controls by dragging. within one plugin, registration order applies. so two
plugins that both rewrite outgoing messages compose predictably, and the user gets to decide which
one wins.

**execution model.** all of a plugin's js — top-level, timer callbacks, promise continuations,
every handler here — runs one thing at a time, so a plugin never races itself and needs no locking.
that's the guarantee; the queue it usually runs on is just how it's kept. plugins run concurrently
with *each other*, so a plugin that wedges itself wedges nothing else — with the one exception of
the interceptors, which the app is waiting on; see the deadline documented on `interceptRpc`.

**the one place that runs elsewhere is `interceptDeserialize`.** deserialization happens on the
app's network and storage threads, it is synchronous by nature (the object is rewritten before the
app looks at it), and it fires per nested object — thousands of times during a cold start. hopping
each of those onto the plugin's own thread and back would cost more than the interception saves, so
a deserialize callback runs *on the thread that's deserializing*, holding that plugin's lock. the
mutual exclusion above still holds, only the thread it holds on is different.

that has one visible consequence: **the synchronous apis are unavailable inside a deserialize
callback** and throw `forbidden` there. `inu.kv`, `inu.fs` and the cache getters on `Account` all
reach back into app subsystems, and calling one from inside the subsystem currently deserializing is
a lock-order inversion — a deadlock rather than a slow path. read what you need before registering,
or use the declarative form, which never enters js at all.

**the interceptor deadline is per chain, not per plugin.** one budget is shared by every middleware
registered for a given request, spent in order — so ten plugins with interceptors cost the user's
send at most one deadline, not ten. a stage that runs past what's left is abandoned there. which
stage a plugin gets to run in therefore depends on how slow the ones above it are, and a plugin
that wants a guaranteed slice shouldn't be doing slow work in the chain at all. the budget is 10
seconds for `interceptRpc` and `interceptSendMessage`, and 2 for `interceptUpdate`, which runs on a
path that delivers hundreds of updates in a burst.
*/

// the non-`inu` globals the sandbox provides. spelled out here rather than pulled from typescript's
// `dom` lib, because there is no dom — borrowing those types would promise a great deal that isn't
// there. compile plugins with `lib: ["es2022"]` and nothing else.
//
// the engine is quickjs-ng, so everything in es2022 is real, plus `atob`/`btoa`, `queueMicrotask`,
// `performance`, `BigInt`, `Proxy`/`Reflect`, `WeakRef`/`FinalizationRegistry`.
//
// **`Intl` is absent**, which es2022 doesn't cover anyway: `toLocaleDateString`, `toLocaleString`
// and friends exist but ignore their locale argument. `inu.utils.format*` covers the common cases
// by delegating to the app's own formatter, which also makes plugin ui match the app it's in.

/** goes to the app's log. arguments are stringified and joined with a space — no format specifiers */
declare const console: {
  log(...args: any[]): void
  info(...args: any[]): void
  warn(...args: any[]): void
  error(...args: any[]): void
  debug(...args: any[]): void
}

// -- timers --
// web signatures, because bundled library code expects them by name. every timer is owned by the
// plugin: all pending ones are cancelled when it unloads, so a stray `setInterval` can't outlive
// the plugin that armed it. callbacks are subject to the same mutual exclusion as the rest of the
// plugin's js (see the execution-model note in the header), so a slow one delays that plugin and
// nothing else.
//
// **timers are throttled while the app is in the background**, the way a browser throttles a
// hidden tab, because n plugins ticking once a second is a battery complaint the user will file
// against the app rather than against the plugin. a suspended interval fires *once* on return to
// the foreground rather than replaying everything it missed — so a timer is not a clock, and a
// plugin that needs to know how long it was away should ask, or do its catch-up work from
// `inu.onAppVisibilityChange`.
//
// this applies to timers only. events, interceptors and handlers keep firing in the background:
// a message arriving while the app is backgrounded still reaches `onNewMessage`.

declare function setTimeout(callback: () => void, ms?: number): number
declare function clearTimeout(id?: number): void
declare function setInterval(callback: () => void, ms?: number): number
declare function clearInterval(id?: number): void
declare function queueMicrotask(callback: () => void): void

/** utf-8 only — the encoding argument the web version takes has nowhere to go here */
declare class TextEncoder {
  encode(input?: string): Uint8Array
}
declare class TextDecoder {
  constructor(label?: 'utf-8')
  decode(input?: Uint8Array | ArrayBuffer): string
}

declare const crypto: {
  /** fills with cryptographically strong random bytes and returns it */
  getRandomValues: <T extends Uint8Array>(array: T) => T
  randomUUID: () => string
}

declare class AbortController {
  readonly signal: AbortSignal
  abort(reason?: any): void
}
declare interface AbortSignal {
  readonly aborted: boolean
  readonly reason: any
  addEventListener(type: 'abort', listener: () => void): void
  removeEventListener(type: 'abort', listener: () => void): void
}

/** structured clone of plain data. no transferables — there is nothing to transfer to */
declare function structuredClone<T>(value: T): T

/**
 * content the app is holding for you — an encoded canvas, a downloaded file, a fetched body. the
 * web `Blob`, and one of the few types here worth borrowing wholesale, because the concept lands
 * unchanged: immutable content of a known size and type, which you can pass around, slice and hand
 * to things without ever looking at the bytes.
 *
 * it's how you avoid the filesystem. a path is a *name*, and a name can be forged — which is why
 * `sendMedia` on a path needs `fs`, or `account.write(send)` alone would let a plugin post any file
 * the app can read. a blob can't be forged: you can only pass back one you were given (or one you
 * built from bytes you already had), so **every api here takes a blob without an `fs` grant**.
 * drawing an image and sending it needs `account.write(send)` and nothing else.
 *
 * where the content lives is the engine's business: media the app already downloaded is a view over
 * that file and costs nothing to hand you, content with no file behind it yet (an encoded canvas, a
 * fetched body) stays in memory while it's small and spills to the app's cache area when it isn't.
 * that's what keeps a 200 MB video from being a 200 MB allocation. don't rely on any of it.
 *
 * a blob over a file the *app* owns is live, exactly as a web `File` is: if the user deletes the
 * message it came from, reads start throwing. `size` and `type` are answered from the moment it was
 * handed over and don't lie about that; only reads can fail.
 *
 * lifetime is the engine's business too, and works the way it does on the web — quickjs refcounts,
 * so a blob is freed the moment the last reference to it goes, and everything outstanding is freed
 * when the plugin unloads. `dispose()` exists for the two cases where that isn't good enough.
 *
 * handles are per-plugin: a blob is meaningless to any plugin but the one it was handed to, so two
 * plugins can't reach each other's content by guessing.
 *
 * what's missing from the spec version: `stream()`, which needs a `ReadableStream` this sandbox
 * doesn't have. everything else behaves as the spec says. writing one out is `inu.fs.write`, which
 * takes a blob directly — it's the filesystem's business, not the blob's, and putting it here would
 * have meant a method whose grant contradicts the rest of the type's.
 */
declare class Blob {
  /** parts are concatenated, exactly as on the web. no grant — these are bytes you already had */
  constructor(parts?: (Blob | Uint8Array | ArrayBuffer | string)[], options?: { type?: string })

  readonly size: number
  /** mime type, or `''` when whatever produced this didn't know one */
  readonly type: string

  /** a view over a range. cheap — it doesn't copy, and on a spilled blob it doesn't read */
  slice(start?: number, end?: number, contentType?: string): Blob

  /** materialize into js. this is the copy a blob exists to avoid — call it only if you need it */
  bytes(): Promise<Uint8Array>
  arrayBuffer(): Promise<ArrayBuffer>
  /** utf-8, like the spec. a blob is not required to hold text; you get replacement chars if not */
  text(): Promise<string>

  /**
   * free it now rather than when the last reference goes. not spec: the web `Blob` has no
   * lifetime to manage, and neither does this one most of the time — dropping it on the floor is
   * fine and is what you should normally do.
   *
   * it's here for the two cases where refcounting doesn't answer promptly: a blob caught in a
   * reference cycle (collected by mark-sweep, which is scheduled off js allocation and so has no
   * idea what you're holding), and a long-lived plugin churning through big ones, where "eventually"
   * is measured against how fast you're making them. if you're making blobs in a loop, dispose them
   * in that loop.
   *
   * idempotent. using a disposed blob throws `handle-expired` — which is the other reason to call
   * it: it turns a use-after-free bug into an exception at the point of use.
   */
  dispose(): void
}

/**
 * a `Blob` that knows what it was called. the web type, minus the constructor's `endings` option,
 * and `lastModified` is whatever the source could answer (a downloaded file's mtime, the message
 * date) rather than a guarantee.
 *
 * this is what the media apis hand back, so a name survives the round trip:
 * `sendMedia(peer, await downloadMedia(msg))` keeps the original filename without a `fileName`
 * option at the call site. `name` is a *name*, never a path — it can't name a file on disk, so it
 * doesn't reopen the question `Blob` exists to close.
 */
declare class File extends Blob {
  constructor(parts: (Blob | Uint8Array | ArrayBuffer | string)[], name: string, options?: {
    type?: string
    /** unix millis */
    lastModified?: number
  })

  readonly name: string
  readonly lastModified: number
}

/**
 * header values, both directions. a `string[]` means the header repeated — which is why this isn't
 * the spec's `Headers` object: our `fetch` isn't spec-compliant anyway, and a plain record that can
 * hold repeats beats a class that silently joins `Set-Cookie`s with a comma.
 */
declare type HeadersInit = Record<string, string | string[]>

declare interface Response {
  readonly ok: boolean
  readonly status: number
  readonly statusText: string
  /** the FINAL url, after any redirects */
  readonly url: string
  readonly headers: Record<string, string | string[]>
  text(): Promise<string>
  json(): Promise<any>
  /** the whole body. `arrayBuffer()` is the same thing for people typing from muscle memory */
  bytes(): Promise<Uint8Array>
  arrayBuffer(): Promise<ArrayBuffer>
  /**
   * the body as a `Blob`, keeping it on the app's side — and the reason to prefer this over
   * `bytes()`: fetching an image and sending it needs neither the content in js nor an `fs` grant
   * to park it in.
   */
  blob(): Promise<Blob>
}

/**
 * a deliberately small slice of the web api — no `Request`/`FormData`, no streaming, and bodies are
 * strings, bytes or a `Blob`.
 *
 * when the grant is scoped to domains (`@grant fetch(a.com)`), **every redirect hop is checked
 * against the scope**, not just the url you passed — otherwise an open redirect on an allowed host
 * would launder access to any other. a hop that leaves the scope fails the request.
 *
 * **loopback and private ranges are refused** whether the grant is scoped or not: 127/8, ::1,
 * 169.254/16, 10/8, 172.16/12, 192.168/16, and any hostname resolving into them. `fetch` is meant
 * to be a grant about the internet, and unscoped it would otherwise reach every other app's debug
 * server on the device and the whole of the user's lan — neither of which is what a user reading
 * "arbitrary http" pictures. resolved addresses are checked, not just the literal, so a hostname
 * pointing at 127.0.0.1 doesn't get through either.
 *
 * @needs-grant fetch
 */
declare function fetch(url: string, init?: {
  method?: string
  headers?: HeadersInit
  body?: string | Uint8Array | Blob
  redirect?: 'follow' | 'manual' | 'error'
  signal?: AbortSignal
  /** milliseconds; rejects when it elapses. unset means no client-side limit */
  timeout?: number
}): Promise<Response>

declare const __opaque__: unique symbol
/** nominal brand. the symbol is engine-private, so these can't be forged by shape */
declare interface OpaqueType<Brand> { readonly [__opaque__]: Brand }
/**
 * a TL object as `{ _: 'namespace.method', ...fields }` (e.g. `{ _: 'messages.sendMessage', peer, message }`).
 *
 * this is the union of *every* constructor the app knows. most places that take or return TL hand
 * you something narrower (`tl.TypeUpdate`, `tl.TypeMessageMedia`, ...) — reach for this only where
 * the type genuinely isn't known ahead of time.
 *
 * **nothing here is eagerly copied.** every TL object you're handed is a lazy view: fields are
 * materialized on first access and cached, nested objects and vectors mint views of their own, and
 * a field you never read is never crossed over the bridge. reading two fields off a hundred
 * messages costs two hundred reads, not a hundred object graphs. the cost model to keep in mind is
 * that *enumerating* one (`Object.keys`, spread, `JSON.stringify`) touches every field and so pays
 * for the whole graph at once — targeted access is the cheap path, and `toJSON()` is the honest way
 * to ask for the expensive one.
 *
 * what differs between them is **who owns the object underneath**, which decides whether writes
 * mean anything:
 *
 * - `interceptRpc`'s `request` and `next()`'s argument/return value are views over the app's *live*
 *   object, and writing a field mutates it directly — no snapshot, no copy, no re-serialization.
 *   that's the point of the api: rewriting the request in place is how you intercept it.
 * - `invokeRpc` results are views over a response that exists only for your call. nobody else holds
 *   it, so they're freely mutable, and mutating one affects nothing but your own copy.
 * - **everything read off an `Account` is read-only, and so are `onUpdate` payloads.** those are
 *   views over objects the *app* owns — its user cache, its message cache — where a write would
 *   either silently vanish or quietly corrupt app state, and neither is a defensible api. assigning
 *   to one throws `forbidden`. to edit, take a copy with `toJSON()`, which is a plain mutable
 *   object you own outright. to *change* an update rather than observe it, that's `interceptUpdate`.
 *
 * a view is read-only in the sense that assignment throws, not in the sense that it's frozen:
 * `Object.isFrozen` says false and `Object.freeze` would defeat the laziness by materializing every
 * field, so it isn't done for you.
 *
 * caveats:
 * - `long`/int64 fields are strings (JS numbers can't hold full int64 precision), e.g. `peer.userId: "123456789"`.
 *   this is the *only* place ids are strings — a `DialogId` is always a `number`
 * - byte-array fields are `Uint8Array` copies (in views AND in detached snapshots): reading
 *   gives you a snapshot, writing replaces the underlying bytes wholesale (no partial/in-place
 *   mutation). they `JSON.stringify` as `{"$inuBytes": "<base64>"}` wrappers, which round-trip
 *   back into byte fields wherever a `TLObject` is accepted. note that your own `JSON.parse` of
 *   stored snapshot JSON (e.g. from `inu.kv`) does NOT revive wrappers back into `Uint8Array`s —
 *   only values coming over the bridge get that. re-sending the parsed object works as-is; to
 *   read* the bytes out of one, `inu.utils.fromBase64` its `$inuBytes`
 * - `flags`/`flags2` are not exposed at all, and assigning to one throws. the bridge owns them:
 *   assigning an optional field sets its bit and assigning `null` (or `0`, `''`, `[]` — all of
 *   which mean "absent" on the wire) clears it. reads work the same way round, so a field whose bit
 *   is clear is simply not there, rather than showing up as the `0` its java slot happens to hold.
 *   a few optional fields share one bit, which is the schema's own doing; the bit is set when any
 *   of them is
 * - naming a constructor this app's layer doesn't know throws. the typings are generated from one
 *   app version (their header says which layer), so if you build against a newer sdk than the app
 *   you're running on, gate with `inu.info().layer` — or just declare the `@plugin-api` level that
 *   shipped it and let the loader refuse to start you
 * - **how long a view lives depends on who owns it.** the `interceptRpc` ones are valid only for
 *   the duration of their dispatch, because the object underneath is the app's and is released
 *   when the dispatch settles: stash one and touching a field later throws `handle-expired`.
 *   everything else (`Account` reads, `onUpdate` payloads, `invokeRpc` results) lives as long as
 *   you hold it — the view keeps its object alive, and drops it when the view is collected. so
 *   keeping a message around is fine; keeping ten thousand of them holds ten thousand alive, and
 *   `toJSON()` is how you keep the data without the object
 * - `toJSON()` is the *only* way to detach one. a view is a host object, so `structuredClone`
 *   throws on it, and `JSON.parse(JSON.stringify(view))` gets you the snapshot's shape but with the
 *   byte fields left as `{"$inuBytes": ...}` wrappers rather than `Uint8Array`s (see above)
 * - plain object literals (`{ _: 'messages.sendMessage', peer, message }`) work fine wherever a
 *   `TLObject` is expected (e.g. a middleware's short-circuit return, or `invokeRpc`'s argument) —
 *   only values that *came from* the bridge are views, nothing requires you to construct one
 */
declare type TLObject = tl.TypeTlObject
declare type MaybePromise<T> = T | Promise<T>

/**
 * the members of `M` whose response type isn't the whole union's, i.e. the ones that disagree.
 *
 * `All` defaults to `M` and is captured before the conditional distributes, so each member gets
 * compared against the full union rather than against itself. every member's response is a subset
 * of the union's by construction, so containment the other way round is the whole test.
 *
 * note this can't be done by collapsing `RpcCallReturn[M]` with a union-to-intersection trick:
 * nearly every TL response is *itself* a union (`messages.Messages` is four constructors), so
 * "is this a single type" answers no for one method just as readily as for two.
 */
declare type MismatchedRpcReturns<M extends tl.TypeRpcMethod['_'], All extends tl.TypeRpcMethod['_'] = M>
  = M extends any ? ([tl.RpcCallReturn[All]] extends [tl.RpcCallReturn[M]] ? never : M) : never

/**
 * the response type shared by every rpc method in `M`, or `never` when they don't share one.
 *
 * that's what lets `interceptRpc`'s array form stay type-safe: without it the return position
 * widens to the union of every method's response, and handling method B while returning method A's
 * response typechecks.
 */
declare type SharedRpcReturn<M extends tl.TypeRpcMethod['_']>
  = [MismatchedRpcReturns<M>] extends [never] ? tl.RpcCallReturn[M] : never

/**
 * undoes a registration. idempotent and always safe to call — including twice, and including after
 * the plugin has started unloading.
 *
 * every `register*`/`on*`/`intercept*`/`hook*` returns one, which is what makes it legal to register
 * from somewhere other than top-level plugin scope. the rules, so that's a contract rather than an
 * accident:
 *
 * - **registering mid-dispatch is fine, and takes effect from the *next* dispatch.** each dispatch
 *   snapshots its handler list up front, so an `onUpdate` handler registered while handling an
 *   update won't see that same update, and a middleware added during an `interceptRpc` chain
 *   doesn't join the chain already being walked. disposing mid-dispatch is symmetric: the in-flight
 *   run still completes.
 * - **registrations that carry an `id` are keyed by it** — re-registering the same id replaces the
 *   previous one instead of stacking. so a plugin that rebuilds its menu items on some event stays
 *   idempotent no matter how often it fires.
 * - **unkeyed registrations stack.** `onUpdate`, `interceptRpc`, `interceptSendMessage` and friends
 *   append every time, so calling one *from inside its own callback* grows the handler list on
 *   every dispatch until the engine dies. that's the one genuinely dangerous shape here; hold the
 *   disposer, or key off something you control.
 * - **registering after unload has begun is a no-op**, and returns a disposer that does nothing.
 */
declare type Disposer = () => void

/**
 * a dialog's identity, encoded as follows:
 * - for dialogs with users, `user.id`
 * - for chats and channels, `-chat.id`
 *
 * always a `number` everywhere in this api. raw TL objects are the one exception — their int64
 * fields are strings, because that's the only lossless way to hand a js program an int64.
 */
declare type DialogId = number

/** the TL shapes that name a peer on their own */
declare type PeerLikeObject
  = | tl.TypePeer | tl.TypeInputPeer | tl.TypeInputUser | tl.TypeInputChannel
    | tl.TypeUser | tl.TypeChat

/**
 * anything that can name a peer, and the only thing any of these apis take in input position:
 * - a `DialogId`
 * - the decimal-string form of one, because int64 fields on TL snapshots are strings — so
 *   `getUser(someUser.id)` works without a manual `Number()`
 * - a username (with or without a leading `@`)
 * - `'me'`/`'self'`
 * - a TL `Peer`, or an already-built `InputPeer`/`InputUser`/`InputChannel` (passed through untouched)
 *
 * there is deliberately no narrower "id only" input type. one existed, accepted a bare `string` for
 * the int64 case, and therefore typechecked every username handed to a method documented as taking
 * an id — a type that disagreed with its own doc comment and only said so at runtime. anything that
 * can name a peer resolves a peer.
 *
 * outputs are never widened like this: what you get back is always a plain `DialogId`.
 */
declare type InputPeerLike = DialogId | PeerLikeObject | 'me' | 'self' | (string & {})

/** formatted text */
declare interface TextWithEntities {
  text: string
  entities?: tl.TypeMessageEntity[]
}

/** a bare string is accepted as unformatted text anywhere formatted text is expected */
declare type InputText = string | TextWithEntities

// todo: markdown/html. plan is to bundle @mtcute/markdown-parser + @mtcute/html-parser into the
// prelude and re-export their typings rather than write our own — both already emit plain TL
// entities and neither touches the DOM. one integration snag left to settle: both mint `Long`s for
// `documentId`/`accessHash`, which have to be normalized to our int64-as-string convention at the
// seam. (the html one's base64 trie decode needs `atob` at module load, which quickjs-ng has.)
// also, mtcute has camelCase TL field names, and we want snake_case for ours.

declare namespace inu {
  /**
   * a failed rpc request, as rejected by `invokeRpc()` and `interceptRpc`'s `next()`: `code`/`text`
   * mirror the server's TL error (e.g. `420`/`'FLOOD_WAIT_3'`; `-1000` marks app-synthesized
   * errors, like a middleware throwing a plain `Error`). middleware may also throw (or return) an
   * `RpcError` itself to fail the intercepted request with that exact code/text — anything else
   * thrown reaches the app as `-1000` with the thrown message.
   */
  class RpcError extends Error {
    constructor(code: number, text: string)
    code: number
    text: string
  }

  /**
   * everything this api throws that isn't an rpc failure. `RpcError` stays separate because it
   * mirrors telegram's own taxonomy and a plugin usually wants to branch on the server's text.
   *
   * always match on `code`, never on `message` — the text is for humans and will change.
   *
   * the distinction worth knowing is `not-granted` vs `forbidden`: the first means "add this to
   * your header and it works", the second means "no grant will ever make this work".
   */
  class PluginError extends Error {
    code:
      /** grant missing, or its scope list doesn't cover this target */
      | 'not-granted'
      /** blocked by policy: a filtered rpc method, a secret chat. see the header */
      | 'forbidden'
      /** `kv`'s 1 MB, or `fs`'s cap. carries `usage`/`quota` */
      | 'quota-exceeded'
      /** a TL view used past its dispatch, a disposed `Blob`/`ImageBitmap`/`UIPage` */
      | 'handle-expired'
      /** a constructor name this app's layer doesn't have */
      | 'unknown-constructor'
      /** malformed peer, bad path, unknown icon name */
      | 'invalid-argument'
      /** the peer/message doesn't resolve, where the api rejects instead of answering `null` */
      | 'not-found'
      /** in the typings, not on this platform or api level. `inu.canvas` throws this today */
      | 'unsupported'
      /** the interceptor chain's budget, or `fetch`'s `timeout` */
      | 'timed-out'
      /** an `AbortSignal` fired */
      | 'aborted'
      /** a bug in the host. worth reporting */
      | 'internal'
      /** widened on purpose: new codes are not a breaking change, so switch with a default */
      | (string & {})

    /** `not-granted` only: the grant token that would have allowed this call */
    grant?: string
    /** `quota-exceeded` only, in bytes */
    usage?: number
    quota?: number
  }

  /** info about the current app, plugin engine and the plugin itself. */
  function info(): {
    /** widened on purpose: other platforms are coming, and narrowing later would be breaking */
    platform: 'android' | (string & {})
    appVersion: string // e.g. '6.81'
    appBuild: string // e.g. '6822'
    /** the highest `@plugin-api` level this app implements. see the header note on versioning */
    apiVersion: number
    layer: number // TL layer the app is running on
    language: string // e.g. 'en-US'. note that this can also be a custom langpack id.
    /** data from the metadata header. a directive may repeat (`@grant`, `@description:xx`) */
    header: Record<string, string[]>
  }

  /**
   * called before the plugin is about to be unloaded (manual disable/reload/uninstall, engine
   * toggle). NOT guaranteed to run on process death. callbacks run synchronously in registration
   * order right before engine teardown — async callbacks are not awaited, and promises pending a
   * host round-trip (`invokeRpc`, `ui.dialog`) never settle. a throwing callback is logged and
   * doesn't stop the others.
   *
   * there's no "load" event because "the script is being ran" is already a load event
   */
  function onUnload(callback: () => void): Disposer

  /**
   * hand a url to the system browser. was `window.open`, which pretended there was a dom behind it.
   *
   * @needs-grant openUrl
   */
  function openUrl(url: string): void

  /**
   * called when the app is moved to the background/foreground
   *
   * @needs-grant onAppVisibilityChange
   */
  function onAppVisibilityChange(callback: (mode: 'foreground' | 'background') => void): Disposer

  /**
   * access to a persistent plugin-scoped key-value store. string keys/values only — serialize
   * structured data yourself (`JSON.stringify`; see the `TLObject` doc for the byte-field caveat).
   * `set`/`insertAll` throw once the 1 MB per-plugin quota would be exceeded. survives app
   * restarts and plugin reloads; wiped when the plugin is uninstalled.
   *
   * for anything bigger than the quota, that's `inu.fs` — its scoped mode is wiped on uninstall too.
   */
  namespace kv {
    /** @needs-grant kv */
    function get(key: string): string | null
    /** @needs-grant kv */
    function has(key: string): boolean
    /** @needs-grant kv */
    function set(key: string, value: string): void
    /** @needs-grant kv */
    function del(key: string): void
    /** @needs-grant kv */
    function keys(): string[]
    /** @needs-grant kv */
    function clear(): void
    /** @needs-grant kv */
    function getAll(): Record<string, string>
    /** @needs-grant kv */
    function insertAll(values: Record<string, string>): void
    /** bytes used of the 1 MB quota, so you can shed entries before a `set` starts throwing */
    function usage(): number
  }

  /** access to the clipboard */
  namespace clipboard {
    /** @needs-grant clipboard.write */
    function write(text: string): void
    /** @needs-grant clipboard.read */
    function read(): string
  }

  /**
   * convenience wrapper over a raw TL message. every getter is a pure function of [raw].
   */
  class Message {
    constructor(raw: tl.TypeMessage)
    /** the underlying TL message, stock's `MessageObject.messageOwner` */
    readonly raw: tl.TypeMessage

    get id(): number
    /**
     * prefers stock's non-wire `dialog_id` annotation over re-deriving from `peer_id`, which
     * matters for secret chats: deriving would yield the id of the *regular* dm with the same
     * person, silently conflating the two.
     *
     * a message you built yourself from a wire object (`new inu.Message(rpcResponse.messages[0])`,
     * something parsed back out of `inu.kv`) carries no annotation — there it falls back to
     * `peer_id`, which is correct precisely because a secret-chat message never arrives in that
     * shape.
     *
     * so `null` means one thing: this is a secret-chat message, which has no `DialogId` at all
     * (see there). `isSecret` says the same. `raw.dialog_id` still carries the app's own value as
     * a string if you need it.
     */
    get dialogId(): DialogId | null
    /** null on channel posts with no visible author */
    get senderId(): number | null
    /** forum topic this message belongs to, or `null` outside a forum. 1 is the "General" topic */
    get topicId(): number | null
    get date(): number
    get editDate(): number | null
    get out(): boolean
    /** raw `message` field; empty for service messages, and the caption on media messages */
    get text(): string
    get textWithEntities(): TextWithEntities
    get media(): tl.TypeMessageMedia | null
    get document(): tl.TypeDocument | null
    /**
     * discriminant over what stock spreads across `isVoice()`/`isMusic()`/`isSticker()`/...
     *
     * new values get added as telegram adds media types, so treat this as open: switch with a
     * default branch rather than exhaustively. `'other'` is what an unrecognised media maps to.
     */
    get mediaType():
      | 'photo' | 'video' | 'roundVideo' | 'voice' | 'music' | 'sticker' | 'gif' | 'document'
      | 'poll' | 'contact' | 'location' | 'venue' | 'story' | 'giveaway' | 'invoice' | 'other'
      | null
    /** seconds, for the media types that carry a duration attribute */
    get duration(): number | null
    /** album grouping key, int64 as a string */
    get groupedId(): string | null
    get replyToMessageId(): number | null
    get forwardedFrom(): tl.TypeMessageFwdHeader | null
    get viaBotId(): number | null
    get isPinned(): boolean
    get views(): number | null
    get forwards(): number | null
    get reactions(): tl.TypeMessageReactions | null
    get isService(): boolean
    /** secret-chat messages carry stock's non-wire `layer`/`seq_in`/`seq_out` annotations */
    get isSecret(): boolean

    /** returns [raw], so `JSON.stringify(message)` round-trips as a plain TL message */
    toJSON(): tl.TypeMessage
  }

  /** one logged-in account slot */
  interface AccountInfo {
    /**
     * slot index the app uses; stable while the account stays logged in, but **reused** once it
     * logs out. key anything durable (kv entries, caches) by [userId] instead, or a later login
     * into the freed slot inherits the previous account's state.
     */
    id: number
    userId: number
    isCurrent: boolean
    isPremium: boolean
  }

  /**
   * every logged-in account. behind a grant because a user id is the user's identity — a plugin
   * that only draws stickers has no business fingerprinting who's holding the phone.
   *
   * @needs-grant account.read(self)
   */
  function accounts(): AccountInfo[]

  /**
   * fires on login, logout and account switch
   *
   * @needs-grant account.read(self)
   */
  function onAccountsChanged(callback: (accounts: AccountInfo[]) => void): Disposer

  /**
   * run per-account setup, and re-run it whenever the selected account changes. the callback may
   * return a teardown function, which runs before the next invocation and once more on unload.
   *
   * this is the reactive counterpart to `account()`: use it to register things that belong to one
   * account (update handlers, caches keyed by peer), and plain `account()` for one-off reads
   * inside a callback. account-agnostic setup — settings pages, menu actions — belongs outside it,
   * or it'll get torn down and rebuilt on every switch for no reason.
   */
  function withCurrentAccount(callback: (account: Account) => (() => void) | void): Disposer

  /**
   * a handle to one account, pinned: it always denotes the same account for its whole life, even
   * across a switch. `id` omitted means "whichever is selected at the moment of this call", which
   * is resolved once, right here — it does not follow later switches. `withCurrentAccount` is how
   * you follow those.
   *
   * accounts are a scope rather than a trailing `accountId` on twenty methods because a scope
   * composes: you can hand an `Account` to a helper function, and it can't be forgotten at one
   * call site the way an optional argument can.
   */
  function account(id?: number): Account

  /**
   * an opaque paging cursor. hand it back verbatim; don't parse it, its shape is not a contract.
   *
   * branded by which list it came from, so a dialogs cursor can't be handed to `getTopics` — the
   * two encode different things and the mistake is otherwise invisible until it pages wrong.
   */
  type Cursor<List extends string> = OpaqueType<`Cursor:${List}`> & string

  /**
   * a page: the items themselves, with `next` riding along. an array rather than `{ items, cursor }`
   * so the common case (`for (const d of await acc.getDialogs())`, `.map`, `.filter`) doesn't pay
   * for a wrapper it never unwraps. `next` is `null` once the list is exhausted.
   */
  type Paged<T, List extends string> = T[] & { next: Cursor<List> | null }

  /**
   * a handle to one account: everything you can read from and do to it.
   *
   * the synchronous getters answer *only* from what the app already has — they return `null` on a
   * miss and never fetch, which is the whole point of them (if you want the network, that's
   * `invokeRpc`). a miss and a nonexistent peer are indistinguishable, same as in stock.
   *
   * the `Promise`-returning ones may go to the network.
   *
   * **everything read here is read-only** (see `TLObject`'s doc). these are lazy views over the
   * app's own caches, so reading two fields off a page of history costs two fields rather than a
   * page of object graphs — and assigning to one throws, because the object underneath belongs to
   * the app and a write would either vanish or corrupt its state. `toJSON()` gives you a plain
   * mutable copy when you want to edit or keep one past the entity's life in the cache.
   *
   * **grants are per method, not per handle.** holding an `Account` costs nothing — `inu.account()`
   * needs no grant at all — and each group below declares its own, so a plugin that only sends
   * doesn't have to ask to read your message cache, and vice versa. the scope names are listed in
   * the header.
   *
   * every message read here has login codes redacted from its text (see the header). the messages
   * themselves are all present, and so is the service chat in `getDialogs` — an earlier design
   * dropped them outright, which meant a dialog whose `top_message` resolved to `null`.
   */
  interface Account {
    /** which slot this handle is pinned to */
    readonly id: number
    readonly userId: number
    /**
     * whether this is the account the user currently has selected. a method rather than a field
     * because the handle is pinned and the answer isn't — it flips under you on a switch. this is
     * the cheap filter for the account-tagged handlers (`inu.onNewMessage` and friends), which fire
     * for every logged-in account.
     */
    isCurrent(): boolean

    /**
     * the logged-in user
     *
     * @needs-grant account.read(self)
     */
    getMe(): tl.TypeUser | null

    /**
     * `null` when the peer isn't cached *or* turns out not to be a user, which are the same answer
     * everywhere in this group — `getPeer` is the one that doesn't have to guess which it'll be
     *
     * @needs-grant account.read(peers)
     */
    getUser(peer: InputPeerLike): tl.TypeUser | null
    /** @needs-grant account.read(peers) */
    getChat(peer: InputPeerLike): tl.TypeChat | null
    /**
     * either kind, so you don't hand-roll the user-vs-chat branch on a `DialogId`'s sign
     *
     * @needs-grant account.read(peers)
     */
    getPeer(peer: InputPeerLike): tl.TypeUser | tl.TypeChat | null
    /**
     * secret chats and folder rows are not included — they have no `DialogId`
     *
     * @needs-grant account.read(dialogs)
     */
    getDialog(peer: InputPeerLike): tl.TypeDialog | null
    /** @needs-grant account.read(messages) */
    getMessage(peer: InputPeerLike, messageId: number): Message | null

    /**
     * one bridge crossing for the whole batch; misses come back as `null` in place
     *
     * @needs-grant account.read(peers)
     */
    getUsers(peers: InputPeerLike[]): (tl.TypeUser | null)[]
    /** @needs-grant account.read(peers) */
    getChats(peers: InputPeerLike[]): (tl.TypeChat | null)[]
    /** @needs-grant account.read(messages) */
    getMessages(peer: InputPeerLike, messageIds: number[]): (Message | null)[]

    /**
     * where a message's media lives on disk. not on `Message`, so that wrapper can stay a pure
     * function of its raw object, and not on `inu.fs`, because the answer is account-dependent:
     * the directory and filename are derived statically, but a per-account file-path database gets
     * the final say and is what tracks files the user moved or saved elsewhere.
     *
     * `null` means the message has no media at all. a result with `exists: false` means it has
     * media that hasn't been downloaded — `path` is where it *would* land, which is what you want
     * if you're about to fetch it, and `downloadMedia` is how you make it exist.
     *
     * the path is absolute and outside the plugin's own directory, so reading it needs
     * `unsafe.fs`; `downloadMedia` is the way to get at the bytes without that.
     *
     * @needs-grant account.read(messages)
     */
    getMessageFile(message: Message | tl.TypeMessage): { path: string, exists: boolean } | null

    /**
     * download a message's media. a no-op resolving immediately if it's already downloaded.
     * rejects if the message has no media.
     *
     * hands back a `File`, so the common shape — download it, look at it or send it on, forget it
     * — needs no fs grant at all, and leaves nothing behind to clean up. the name rides along, so
     * `sendMedia(peer, await downloadMedia(msg))` keeps the original filename.
     *
     * @needs-grant account.read(messages)
     */
    downloadMedia(message: Message | tl.TypeMessage, options?: {
      onProgress?: (loaded: number, total: number) => void
    }): Promise<File>

    /**
     * the same download, put in the app's own media directory where picking it in the ui would
     * have, answering with the path. this is the one to use when the point *is* the file on disk:
     * the user asked to save it, or something outside the plugin will open it.
     *
     * a separate function rather than an option on `downloadMedia`, because the option decided the
     * return type and every dynamic call site paid for that with a cast.
     *
     * reading the result back needs `unsafe.fs` — the path is outside the plugin's directory. if
     * you were only going to read it, you wanted `downloadMedia`.
     *
     * @needs-grant account.read(messages)
     */
    downloadMediaToFile(message: Message | tl.TypeMessage, options?: {
      onProgress?: (loaded: number, total: number) => void
    }): Promise<{ path: string }>

    /**
     * upload a file and get the `InputFile` back, for the rpc methods that want one (setting a
     * profile photo, a chat avatar, a sticker) rather than going through `sendMedia`.
     *
     * `{ path }` needs `fs` — scoped-relative, or absolute with `unsafe.fs` — for the same reason
     * `sendMedia` does. bytes and a `Blob` are exempt: neither can name a file the plugin didn't
     * have.
     *
     * @needs-grant account.write(send)
     */
    uploadFile(file: Blob | Uint8Array | { path: string }, options?: {
      fileName?: string
      onProgress?: (loaded: number, total: number) => void
    }): Promise<tl.TypeInputFile>

    /**
     * bio/`about`, `common_chats_count`, pinned message. fetches when not cached.
     *
     * asking about *yourself* is allowed under `account.read(self)` alone — it's the one peer you
     * already are, so a plugin that shows your own bio doesn't need the whole address book.
     *
     * @needs-grant account.read(peers)
     */
    getUserFull(peer: InputPeerLike): Promise<tl.TypeUserFull | null>
    /**
     * `participants_count` and `available_reactions` live here, not on the bare chat
     *
     * @needs-grant account.read(peers)
     */
    getChatFull(peer: InputPeerLike): Promise<tl.TypeChatFull | null>

    /**
     * the chat list, most-recent-first. `folderId` 0 is the main list, 1 the archive.
     *
     * paging is a cursor rather than a numeric offset because telegram's dialog paging isn't an
     * index — it's an (offset_date, offset_id, offset_peer) triple, and pretending otherwise would
     * either lie or quietly re-page from the top. pass the previous page's `next` back verbatim to
     * continue. (history pages by `offsetId` instead, because there the offset genuinely *is* a
     * message id you can name yourself.)
     *
     * async because paging past what's cached goes to the network — the point-lookup `getDialog`
     * stays the synchronous cache-only one.
     *
     * @needs-grant account.read(dialogs)
     */
    getDialogs(options?: {
      folderId?: number
      limit?: number
      cursor?: Cursor<'dialogs'>
    }): Promise<Paged<tl.TypeDialog, 'dialogs'>>
    /**
     * pages for you; stops when the list is exhausted or `limit` is reached
     *
     * @not-implemented
     * @needs-grant account.read(dialogs)
     */
    iterDialogs(options?: { folderId?: number, limit?: number, batchSize?: number }): AsyncIterableIterator<tl.TypeDialog>

    /**
     * newest-first. `offsetId` continues from a known message id
     *
     * @needs-grant account.read(history)
     */
    getHistory(
      peer: InputPeerLike,
      options?: {
        limit?: number
        offsetId?: number
        minId?: number
        maxId?: number
        /** restrict to one forum topic */
        topicId?: number
      },
    ): Promise<Message[]>
    /**
     * @not-implemented
     * @needs-grant account.read(history)
     */
    iterHistory(
      peer: InputPeerLike,
      options?: {
        limit?: number
        offsetId?: number
        minId?: number
        maxId?: number
        batchSize?: number
        topicId?: number
      },
    ): AsyncIterableIterator<Message>

    /**
     * forum topics, most-recently-active first. rejects if the chat isn't a forum
     *
     * @needs-grant account.read(dialogs)
     */
    getTopics(peer: InputPeerLike, options?: {
      limit?: number
      cursor?: Cursor<'topics'>
    }): Promise<Paged<tl.TypeForumTopic, 'topics'>>
    /**
     * @not-implemented
     * @needs-grant account.read(dialogs)
     */
    iterTopics(peer: InputPeerLike, options?: {
      limit?: number
      batchSize?: number
    }): AsyncIterableIterator<tl.TypeForumTopic>

    /**
     * resolve a peer to an `InputPeer` for use with `invokeRpc`. rejects rather than resolving to
     * `null` — a null `InputPeer` is useless and would only defer the failure somewhere worse.
     *
     * @needs-grant account.read(peers)
     */
    resolvePeer(peer: InputPeerLike): Promise<tl.TypeInputPeer>
    /**
     * the cache-only, synchronous half. a dedicated narrow bridge call that returns just the
     * `InputPeer` — it does not serialize a whole user/chat the way `getUser` would, so this is
     * the cheap way to address many peers. `null` when the peer isn't cached (resolve it once
     * with `resolvePeer`, then this starts answering).
     *
     * @needs-grant account.read(peers)
     */
    resolvePeerCached(peer: InputPeerLike): tl.TypeInputPeer | null
    /**
     * narrows to an `InputUser`; rejects if the peer turns out not to be a user
     *
     * @needs-grant account.read(peers)
     */
    resolveUser(peer: InputPeerLike): Promise<tl.TypeInputUser>
    /**
     * narrows to an `InputChannel`; rejects if the peer turns out not to be a channel
     *
     * @needs-grant account.read(peers)
     */
    resolveChannel(peer: InputPeerLike): Promise<tl.TypeInputChannel>
    /**
     * at most 8 in flight; peers that can't be resolved come back as `null` instead of failing the batch
     *
     * @not-implemented
     * @needs-grant account.read(peers)
     */
    resolvePeerMany(peers: InputPeerLike[]): Promise<(tl.TypeInputPeer | null)[]>

    // -- writes --
    // these take an `InputPeerLike` and resolve it internally, which is the whole reason they
    // exist: doing it by hand means reading `access_hash` off an entity and assembling an
    // `InputPeer` at every call site.
    //
    // **none of them re-enter the plugin interceptors.** a `sendMessage` here does not run through
    // `interceptSendMessage`, and nothing here runs through `interceptRpc` — same rule `invokeRpc`
    // states for itself. otherwise a plugin that rewrites sends and a plugin that sends would be an
    // infinite loop, and the two-plugin case would be unauditable.
    //
    // **none of them reach secret chats**, which have no `DialogId` to name and are the last place
    // plugin code belongs.

    /** @needs-grant account.write(send) */
    sendMessage(peer: InputPeerLike, text: InputText, options?: {
      replyToMessageId?: number
      /** post into a forum topic */
      topicId?: number
      silent?: boolean
      /** unix seconds; sends later instead of now */
      scheduleDate?: number
      noWebpage?: boolean
      /** post as a channel/anonymous admin rather than yourself */
      sendAs?: InputPeerLike
      /** clear the chat's draft on success, the way the ui does */
      clearDraft?: boolean
    }): Promise<Message>

    /**
     * `file` is a `Blob`, raw bytes, an already-uploaded TL `InputFile`/document, or `{ path }` —
     * a path always, never a url, even though telegram itself can send one (build an
     * `inputMediaDocumentExternal` for that). by default the type is inferred from the content and
     * the app sends it the way it would if you'd picked it in the ui; `asDocument` forces the
     * uncompressed path.
     *
     * a **`{ path }` additionally needs `fs`**, even though it's this call doing the reading — a
     * relative one resolves inside the plugin's own directory, and an absolute one needs
     * `unsafe.fs`. without that rule `account.write(send)` alone would be enough to read any file
     * the app can reach and post it to a chat: an exfiltration primitive the grant doesn't look
     * like it confers. it's wrapped rather than a bare `string` so that the one member of this
     * union carrying a grant requirement says so at the call site.
     *
     * a `Blob` is the one to reach for: it needs no `fs` (it can't name a file the plugin wasn't
     * handed), and it never drags the content through js memory. to send something you drew, that's
     * `canvas.convertToBlob()` straight into here.
     *
     * @needs-grant account.write(send)
     */
    sendMedia(peer: InputPeerLike, file: Blob | Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | { path: string }, options?: {
      caption?: InputText
      replyToMessageId?: number
      topicId?: number
      silent?: boolean
      scheduleDate?: number
      asDocument?: boolean
      fileName?: string
      sendAs?: InputPeerLike
      onProgress?: (loaded: number, total: number) => void
    }): Promise<Message>

    /**
     * an album — one grouped bubble rather than N separate messages, which is a different rpc and
     * not something `sendMedia` in a loop can produce.
     *
     * each item takes the same `file` shapes `sendMedia` does, with the same path rules. a caption
     * on the first item is the album's caption; telegram renders later ones inconsistently, so put
     * it first or leave them off. `onProgress` reports across the whole batch.
     *
     * @needs-grant account.write(send)
     */
    sendMultiMedia(peer: InputPeerLike, items: {
      file: Blob | Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | { path: string }
      caption?: InputText
      fileName?: string
      asDocument?: boolean
    }[], options?: {
        replyToMessageId?: number
        topicId?: number
        silent?: boolean
        scheduleDate?: number
        sendAs?: InputPeerLike
        onProgress?: (loaded: number, total: number) => void
      }): Promise<Message[]>

    /** @needs-grant account.write(edit) */
    editMessage(peer: InputPeerLike, messageId: number, text: InputText, options?: {
      noWebpage?: boolean
    }): Promise<Message>

    /** @needs-grant account.write(delete) */
    deleteMessages(peer: InputPeerLike, messageIds: number[], options?: {
      /** delete for everyone rather than just locally */
      revoke?: boolean
    }): Promise<void>

    /**
     * resolves to the *new* messages, in `toPeer`
     *
     * @needs-grant account.write(forward)
     */
    forwardMessages(fromPeer: InputPeerLike, messageIds: number[], toPeer: InputPeerLike, options?: {
      silent?: boolean
      scheduleDate?: number
      topicId?: number
      /** strip the "forwarded from" header */
      dropAuthor?: boolean
      dropCaption?: boolean
    }): Promise<Message[]>

    /**
     * set your reactions on a message. pass `[]` to clear. a plain string is an emoji; use
     * `{ customEmojiId }` for a premium one.
     *
     * @needs-grant account.write(react)
     */
    setReaction(peer: InputPeerLike, messageId: number, reactions: (string | { customEmojiId: string })[], options?: {
      big?: boolean
    }): Promise<void>

    /**
     * mark read up to and including [maxId], or the whole dialog when omitted
     *
     * @needs-grant account.write(read)
     */
    readHistory(peer: InputPeerLike, options?: { maxId?: number, topicId?: number }): Promise<void>

    /**
     * show a typing/recording indicator. it expires on its own after a few seconds, so repeat it
     * while the action continues; `'cancel'` clears it early.
     *
     * @needs-grant account.write(typing)
     */
    sendTyping(peer: InputPeerLike, action?:
      | 'typing' | 'cancel' | 'recordVideo' | 'uploadVideo' | 'recordVoice' | 'uploadVoice'
      | 'uploadPhoto' | 'uploadDocument' | 'chooseSticker' | 'chooseContact', options?: {
        topicId?: number
      }): Promise<void>

    /**
     * the saved draft for a chat, as the input field would show it. `null` when there is none.
     *
     * a read, despite sitting next to the writes — it has its own scope on the read side.
     *
     * @needs-grant account.read(draft)
     */
    getDraft(peer: InputPeerLike, options?: { topicId?: number }): TextWithEntities | null
    /**
     * pass `null` to clear. this is what the input field will show next time the chat is opened
     *
     * @needs-grant account.write(draft)
     */
    setDraft(peer: InputPeerLike, draft: InputText | null, options?: {
      topicId?: number
      replyToMessageId?: number
    }): Promise<void>

    /**
     * invoke an rpc request on this account's connection.
     *
     * bypasses `interceptRpc` middleware — it does not re-trigger it, so a plugin can call from
     * inside its own interceptor without looping.
     *
     * the resolved value is a lazy view over the response, and one of the two **mutable** kinds
     * (see `TLObject`'s doc): the response exists only for this call, so nobody else can see what
     * you do to it. keepable for as long as you hold it, `JSON.stringify`-able, with the usual
     * caveats (int64s as strings, bytes as `Uint8Array`). `toJSON()` if you want it detached.
     *
     * rejects with an `inu.RpcError` when the request fails server-side; resolves to `null` if the
     * app completed the request with neither a response nor an error.
     *
     * @needs-grant invokeRpc
     */
    invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']]>
  }

  /**
   * helpers with no host state behind them — pure functions, no bridge calls, no account needed.
   * they live here rather than as globals because none of them has a web equivalent to be
   * compatible with (the ones that do — `TextEncoder`, `crypto`, timers — keep their web names).
   */
  namespace utils {
    /**
     * `atob`/`btoa` exist, but they speak binary strings, which is a footgun everywhere else in
     * this api hands you `Uint8Array`. these are the pair that doesn't need a conversion dance.
     */
    function toBase64(bytes: Uint8Array): string
    function fromBase64(base64: string): Uint8Array
    function toHex(bytes: Uint8Array): string
    function fromHex(hex: string): Uint8Array

    // todo: no hashing at all right now, so a plugin talking to an hmac-signed api has to ship a
    // js sha256 into a jit-less engine. a two-function `sha256`/`hmacSha256` here (the platform
    // already has both) is the fix if that ever comes up — a full webcrypto `subtle` is not.

    // -- formatting --
    // `Intl` is absent, and these delegate to the app's own formatter rather than reimplementing
    // it — so a plugin's ui reads the same as the screen it's sitting on (24h vs am/pm, the user's
    // langpack, the app's own "yesterday"/"1.2K" conventions) without asking about any of that.
    // they follow `inu.info().language`, including custom langpacks.

    /**
     * `unix` is seconds, matching every date field on a TL object.
     * - `date` — `12 May 2024`
     * - `time` — `19:04`
     * - `dateTime` — both
     * - `relative` — what a dialog row shows: a time today, a weekday this week, a date before that
     */
    function formatDate(unix: number, style?: 'date' | 'time' | 'dateTime' | 'relative'): string
    /** grouped (`1 234 567`), or `compact` for the app's short form (`1.2M`) */
    function formatNumber(value: number, options?: { compact?: boolean }): string
    /** `4.2 MB`, in the app's units */
    function formatFileSize(bytes: number): string
    /** `3:07`, `1:02:44` — the form the app uses on media */
    function formatDuration(seconds: number): string

    /**
     * pure id arithmetic. these take a `DialogId` (or its decimal-string form, since that's how
     * int64s arrive on a TL snapshot) rather than an `InputPeerLike` — a username isn't a number
     * and there is nothing to compute from one, so accepting it would only defer the failure.
     */
    namespace peers {
      /** accepts a TL `Peer`, `InputPeer`, `User` or `Chat` */
      function toDialogId(peer: PeerLikeObject): DialogId
      /**
       * `'chat'` covers basic groups, channels, supergroups and communities alike: the app's scheme
       * negates all of them without an offset, and their ids share one server-side space, so which
       * kind it is genuinely cannot be recovered from the number. (the bot api scheme *can*, since it
       * offsets channels — one of the few things it buys.) to tell them apart, look the peer up and
       * read `megagroup`/`broadcast` off it.
       */
      function parseDialogId(id: DialogId | string): {
        type: 'user' | 'chat'
        /** the bare, always-positive id */
        id: number
      }
      /**
       * build an `InputPeer` from a user/chat you already hold — `access_hash` is right there on it,
       * so this costs nothing. use it instead of `resolvePeerCached` when you've already read the entity.
       */
      function toInputPeer(userOrChat: tl.TypeUser | tl.TypeChat): tl.TypeInputPeer
      /**
       * to the bot api / tdlib scheme, for handing an id to an external service that speaks it.
       *
       * takes the peer itself, not its id, for the reason in `parseDialogId`: only a channel gets the
       * `-1e12` offset, and a bare negative id doesn't say whether it is one. pass the `User`/`Chat`
       * you read (or a TL `Peer`) and it reads the kind off that.
       */
      function toBotApiId(peer: PeerLikeObject): number
      /** the reverse is unambiguous — the offset itself marks channels — so an id is enough here */
      function fromBotApiId(id: DialogId | string): DialogId
    }
  }

  type UIElement = OpaqueType<'UIElement'>
  type UIIcon = OpaqueType<'UIIcon'>

  /**
   * three ways to name an icon, in descending order of how well they travel:
   * `common` is portable and themed for you, `inu.android.resourceIcon` is native but pins you to
   * one platform, `svg` always works but you own the visual consistency.
   */
  namespace icons {
    /**
     * a small curated set, resolved to whatever the host platform's real asset is — so these
     * follow the app's icon pack and theming. the list grows on demand; unknown names throw
     * rather than silently rendering nothing, so a name added in a later api level needs the
     * matching `@plugin-api`.
     */
    function common(
      name:
        | 'settings' | 'info' | 'search' | 'edit' | 'delete' | 'copy' | 'share' | 'download'
        | 'link' | 'pin' | 'star' | 'mute' | 'unmute' | 'archive' | 'forward' | 'reply'
        | 'user' | 'group' | 'channel' | 'bot' | 'lock' | 'eye' | 'eyeOff' | 'refresh'
        | 'plus' | 'minus' | 'check' | 'close' | 'more' | 'translate' | 'bookmark',
    ): UIIcon
    /** inline svg source. scaled and tinted to match its context */
    function svg(source: string): UIIcon
  }
  namespace ui {
    /**
     * a plugin-defined settings page. rendering is declarative: `items()` re-runs on every
     * (re)render and the previous item list is diffed away — elements are cheap one-shot
     * descriptors, not live views. state belongs to the plugin (memory or `inu.kv`); the page
     * auto-invalidates after any item callback returns, so `invalidate()` is only needed when
     * state changes *asynchronously* (e.g. after an awaited `invokeRpc`).
     */
    interface UIPage {
      /** re-run `items()` and re-render (no-op while the page isn't open) */
      invalidate(): void
      /**
       * free the page and everything it captured. after this the page can't be opened or
       * rendered anymore (an already-open view freezes); calling again is a no-op. needed
       * because pages are never garbage-collected — if you create pages dynamically (e.g. a
       * per-item detail page from a factory function), dispose them or mark them `transient`,
       * or every creation leaks until the plugin unloads.
       */
      dispose(): void
    }

    /**
     * push a page onto the current navigation stack.
     * (will later also accept app fragments via `inu.jvm` — same verb for both.)
     */
    function openPage(page: UIPage): void

    /** show a toast */
    function toast(text: string): void

    /**
     * show a dialog. the promise resolves to the user's action: the button they tapped, or
     * `'dismissed'` for back-press/outside-tap (also when no UI is available to show it at all).
     * button options are label strings; omitted buttons aren't shown.
     */
    function dialog(options: {
      title?: string
      message?: string
      /**
       * arbitrary content in place of `message` — this is the escape hatch for dialogs the plain
       * options can't express. on android, `inu.android.nativeView` turns a real View into one.
       */
      body?: UIElement
      positive?: string
      negative?: string
      neutral?: string
    }): Promise<'positive' | 'negative' | 'neutral' | 'dismissed'>

    /**
     * modal list picker. resolves to the chosen index (or indices when `multiple`), or `null` if
     * dismissed. for a picker that lives *in* a settings page rather than over it, use `select`.
     */
    function chooser(options: {
      title?: string
      items: (string | { text: string, subtitle?: string, icon?: UIIcon, danger?: boolean })[]
      selected?: number
      multiple?: false
    }): Promise<number | null>
    function chooser(options: {
      title?: string
      items: (string | { text: string, subtitle?: string, icon?: UIIcon, danger?: boolean })[]
      selected?: number[]
      multiple: true
    }): Promise<number[] | null>

    /**
     * a screen the user can be looking at. the portable slice of it — `inu.android`'s
     * `getCurrentFragment` hands you the real fragment when you need to go further.
     *
     * `type` is free. `dialogId`/`topicId` need `account.read(dialogs)` and are simply absent
     * without it — *which chat the user is reading* is the same information the message cache
     * holds, and it shouldn't be cheaper to get just because it came from the ui. absent isn't
     * ambiguous: `type` already says whether there was one to give.
     */
    interface CurrentScreen {
      type: 'chat' | 'profile' | 'dialogs' | 'settings' | 'other'
      /** set for `chat` and `profile`. @needs-grant account.read(dialogs) */
      dialogId?: DialogId
      /** set for `chat` in a forum. @needs-grant account.read(dialogs) */
      topicId?: number
      /** the account being viewed */
      account: Account
    }

    /**
     * what the user is looking at right now. `null` when nothing is on screen (app in background,
     * or too early during startup).
     */
    function getCurrentScreen(): CurrentScreen | null

    interface ScreenChange {
      /** the screen now on top, and the last entry of `stack`. `null` once the stack is empty */
      screen: CurrentScreen | null
      /**
       * what was on top before. a snapshot, not a live handle — on a `pop` or `replace` the screen
       * it describes is already gone, and it is *not* in `stack` anymore. `null` for the first
       * navigation of the app's life.
       */
      previous: CurrentScreen | null
      /**
       * how the top changed. this is the part `screen`/`previous` can't tell you: opening someone's
       * profile from a chat and closing a chat to reveal a profile underneath both arrive as
       * chat -> profile, and they are `push` and `pop` respectively.
       *
       * `replace` is a push that dropped what it landed on (stock's `removeLast`), so like `pop` it
       * loses `previous` from the stack, and unlike `pop` it doesn't get any shallower.
       */
      action: 'push' | 'pop' | 'replace'
      /**
       * the whole navigation stack, bottom first, with `screen` as its last entry. answers what a
       * screen was opened *from* — a profile below a chat means they got there through it.
       *
       * computed when you touch it, so ignoring it costs nothing.
       */
      readonly stack: CurrentScreen[]
    }

    /**
     * fires when the user navigates, i.e. whenever the top of the navigation stack changes. the
     * reactive counterpart to `getCurrentScreen`, and the reason you don't have to poll it.
     *
     * this is about navigation only. going to the background doesn't change the stack and doesn't
     * fire this, even though `getCurrentScreen` starts answering `null` there — that's
     * `onAppVisibilityChange`'s job. `screen` is `null` here only when the stack itself empties.
     *
     * only fires on an actual change. a rebuild that ends up on the same screen doesn't, so every
     * call is one navigation.
     *
     * the same grant rule as `getCurrentScreen` applies to the fields, and the event itself needs
     * none: that the user opened *a chat* is not worth a permission, and *which* one is already
     * behind `account.read(dialogs)` wherever it appears.
     */
    function onScreenChanged(callback: (change: ScreenChange) => void): Disposer

    /**
     * show a single-line text-input dialog. resolves to the submitted text, or `null` on
     * cancel/dismiss. validation is your job — re-prompt if the value doesn't fit.
     */
    function prompt(options: {
      title: string
      hint?: string
      /** pre-filled text */
      value?: string
      /** pre-select the pre-filled text */
      selectAll?: boolean
    }): Promise<string | null>

    /**
     * an anchored options menu (with scrim), opened over the row that handed you the anchor.
     *
     * the anchor is the second argument to every settings-page item callback (`onClick`,
     * `onChange`, `onSecondaryClick`), so opening a menu is `(_, row) => row.openMenu([...])`.
     * it used to be a free function that read the currently-running callback off the host and threw
     * anywhere else — including after an `await`, which is exactly where you'd want it. passing the
     * anchor makes "which row is this over" a value rather than a piece of dynamic context, and
     * makes it survive an await.
     *
     * a menu item's own `onClick` gets no anchor, so a menu can't open another menu.
     */
    interface UIAnchor {
      openMenu(items: {
        text: string
        /**
         * show a checkmark next to the item. specifying this on ANY item (even as `false`) makes
         * the whole menu radio-style: every row reserves the checkmark column so texts align
         */
        checked?: boolean
        danger?: boolean
        onClick: () => void
      }[]): void
    }

    // -- settings page elements --
    // declarative one-shot descriptors; only meaningful inside a `settingsPage`'s `items()`.
    // `id` is the row's stable identity for list diffing (animations, preserving row state across
    // re-renders). it defaults to a hash of the element's type + text, which is stable for
    // static-ish lists — set it explicitly only when the list reshapes dynamically (rows get
    // renamed, reordered, or several rows swap texts)

    function header(text: string): UIElement

    /** switch row */
    function check(options: {
      id?: string
      text: string
      subtitle?: string
      checked: boolean
      onChange: (checked: boolean, anchor: UIAnchor) => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /** tappable text row */
    function button(options: {
      id?: string
      text: string
      subtitle?: string
      /** value shown on the right side */
      value?: string
      danger?: boolean
      onClick: (anchor: UIAnchor) => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /**
     * text row showing the selected option's text as its value; tap opens an anchored options
     * menu, or a radio-list alert when `dialog` is set (the only mode that shows `subtitle`s)
     */
    function select(options: {
      id?: string
      text: string
      items: (string | { text: string, subtitle?: string })[]
      selected: number
      dialog?: boolean
      onChange: (index: number, anchor: UIAnchor) => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /**
     * `label` renders the value for display. it runs at *render* time, once per step (which is
     * why `step` is required — the label strip is precomputed, live dragging never calls JS);
     * `onChange` fires once, on release.
     */
    function slider(options: {
      id?: string
      text?: string
      min: number
      max: number
      step: number
      value: number
      /** double-tap-to-reset target */
      default?: number
      label?: (value: number) => string
      onChange: (value: number, anchor: UIAnchor) => void
    }): UIElement

    /** section divider; with text = gray explanatory footer for the section above */
    function separator(text?: string): UIElement

    function settingsPage(options: {
      title: string
      /**
       * auto-`dispose()` the page when its last open view closes (after `onClose` fires).
       * for throwaway pages built on the fly; don't set on pages you keep a reference to and
       * reopen — reopening a disposed page throws.
       */
      transient?: boolean
      /** called on every (re)render; return the full item list */
      items: () => UIElement[]
      /** sticky button pinned below the list */
      bottomButton?: {
        text: string
        onClick: (anchor: UIAnchor) => void
      }
      onClose?: () => void
    }): UIPage
  }

  /**
   * register the plugin's settings page — adds a settings button to the plugin's row in the
   * plugins list. one per plugin — calling it twice throws rather than silently deciding which
   * page wins. nested pages need no registration: build another `settingsPage` and
   * `ui.openPage(...)` it from a button's `onClick`.
   *
   * to swap the page later, dispose the first registration and register again.
   */
  function registerSettings(page: ui.UIPage): Disposer

  /**
   * register an interceptor for all rpc requests of the methods in the array.
   *
   * the method name is checked against the app's TL schema, and `request`/the response type are
   * both pinned to it — returning the wrong constructor for the method you intercepted is a
   * compile error, not a runtime surprise in app code that expected something else.
   *
   * middlewares for the same method run in registration order (across plugins: plugin-list order,
   * which the user controls), forming a chain. each stage must either call `next(request)` at most
   * once (calling it twice throws) or return its own response. returning `undefined` without having
   * called `next()` is an error; returning `undefined` after `next()` passes that call's response
   * through unchanged. `next()` past the last middleware sends the (possibly rewritten) request for
   * real.
   *
   * `account` is whose connection the request is on. this fires for every logged-in account, so a
   * plugin that only cares about one has to check (`account.isCurrent()` is the usual filter).
   *
   * **the chain has 10 seconds**, shared by every middleware registered for the request rather than
   * 10s each, so a user's send costs at most that no matter how many plugins are installed. the app
   * is blocked on it — an interceptor that awaits a `fetch` against a blackholed host would
   * otherwise hang the user's send forever, with nothing on screen to explain it. past the deadline
   * the stage is abandoned and the request fails with an `inu.RpcError` saying the interceptor
   * timed out; a late `next()` from the abandoned stage throws. do slow work outside the chain and
   * cache the answer, and note that a plugin low in the list only gets whatever the ones above it
   * left unspent.
   *
   * passing an **array of several methods is allowed only when they share one response type**,
   * which is the common case that motivates the form (the `channels.*`/`messages.*` pairs that both
   * answer `messages.Messages`, say). `request` widens to the union of their request types, and the
   * response stays the single type they agree on. when they *don't* agree the shared type resolves
   * to `never`, so returning anything but `null` is a compile error — which is the intended
   * rejection, though it lands on your return value rather than on the array. register once per
   * method there.
   *
   * `request`, `next()`'s argument and return value are live views over the real request/response
   * object (see `TLObject`'s doc) — mutating `request` in place and calling `next(request)` mutates
   * and re-sends the *same* underlying object, no re-serialization. passing `next()` a plain object
   * literal instead is also fine and replaces the request wholesale.
   *
   * `next()` rejects with an `inu.RpcError` when the request fails server-side; rethrowing it (or
   * throwing your own) fails the intercepted request with that error — see `RpcError`'s doc.
   * `next()` resolves to `null` when the app completed the request with neither a response nor an
   * error (some cancellation paths do this); returning that `null` (or returning `undefined` after
   * awaiting it) passes the empty completion through unchanged.
   *
   * the takeover methods listed in the header can't be intercepted: naming one here fails at
   * install, and the app's own calls to them never reach a chain. login-code redaction applies to
   * whatever you do see, on the way in — the app's own object is untouched, so it still receives
   * everything intact.
   *
   * @needs-grant interceptRpc
   */
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    method: M,
    middleware: (
      request: Extract<tl.TypeRpcMethod, { _: M }>,
      next: (request: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<tl.RpcCallReturn[M] | null>,
      account: Account,
    ) => MaybePromise<tl.RpcCallReturn[M] | null | undefined>,
  ): Disposer
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    methods: M[],
    middleware: (
      request: Extract<tl.TypeRpcMethod, { _: M }>,
      next: (request: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<SharedRpcReturn<M> | null>,
      account: Account,
    ) => MaybePromise<SharedRpcReturn<M> | null | undefined>,
  ): Disposer

  /**
   * intercept TL *deserialization* — every object the app parses, whichever direction it came
   * from. that includes the local sqlite cache, which never goes near an rpc response, so this
   * reaches things `interceptRpc` structurally cannot: a `user` row loaded at startup, a cached
   * `message`, a stored `chatFull`.
   *
   * the declarative form exists because this runs on the hot path — thousands of objects during a
   * cold start. rules are matched natively with no js crossing at all; the middleware form pays a
   * js call per object and should be a last resort.
   *
   * see the execution-model note in the header for where the middleware form actually runs: on the
   * app's own deserializing thread, with the synchronous apis unavailable inside it.
   *
   * @not-implemented
   * @needs-grant interceptDeserialize
   */
  function interceptDeserialize(rules: {
    /** constructor names to match */
    type: string | string[]
    /**
     * only rewrite objects where every one of these fields **equals** the given value.
     *
     * exact equality and nothing else — there are no operators, no `$`-prefixed matchers, no
     * nested paths. a comparison DSL here would be a parser and an evaluator on the hot path, in
     * service of rules that the middleware form already expresses; the declarative form earns its
     * place by being the one that never enters js, and it keeps that only by staying trivial.
     */
    when?: Record<string, string | number | boolean | null>
    /** fields to overwrite. constants, for the same reason */
    set: Record<string, string | number | boolean | null>
  }[]): Disposer
  /**
   * the general form, for rewrites the rules can't express. pays a js crossing per matched object.
   *
   * @not-implemented
   * @needs-grant interceptDeserialize
   */
  function interceptDeserialize(objects: string[], middleware: (object: TLObject) => TLObject): Disposer

  // -- events --
  // all of these fire across every logged-in account and hand you the `Account` the event arrived
  // on, rather than living *on* an account: a handler is a plugin-wide thing, and hanging one off a
  // pinned handle meant registering N times and re-registering on every login. filter with
  // `account.isCurrent()` or `account.userId` if you only care about one.
  //
  // the demuxed ones exist for the same reason `interceptSendMessage` does: one callback covers
  // what the raw stream spreads across `updateNewMessage`/`updateNewChannelMessage`/
  // `updateShortMessage`/`updateShortChatMessage` and the difference catch-up path. writing that
  // fan-out by hand is most of the boilerplate in a typical plugin.
  //
  // login codes are redacted from message text before any of these fire, and
  // `updateServiceNotification` is not delivered at all (see the header). the messages themselves
  // still arrive, so a plugin that watches the service chat sees it happen, just not the code.

  /** @needs-grant onUpdate(new_message) */
  function onNewMessage(callback: (message: Message, account: Account) => void): Disposer
  /** @needs-grant onUpdate(edit_message) */
  function onMessageEdited(callback: (message: Message, account: Account) => void): Disposer
  /**
   * ids only — the messages are gone by the time this fires, so there is nothing to hand over
   *
   * @needs-grant onUpdate(delete_message)
   */
  function onMessageDeleted(callback: (dialogId: DialogId, messageIds: number[], account: Account) => void): Disposer

  /**
   * the raw update stream. `account` says which account it arrived on.
   *
   * called for every update of the named types, across all arrival paths (incl. difference
   * catch-up).
   *
   * **the constructor list is required**, and narrows `update` to it the way `interceptRpc` does.
   * there used to be an unscoped form; it was a bridge crossing per update to discard almost all
   * of them in the callback's first line, on a path that delivers hundreds at once during
   * difference catch-up. name the constructors and the filtering happens natively, before anything
   * is materialized. it's also the only form that can narrow the type, so the scoped one was
   * already what you wanted. the grant still bounds what you may name.
   *
   * `update` is **read-only**: this is the observation api, and the app has already acted on the
   * update by the time you see it, so a write here would change nothing and read as though it had.
   * to actually change one, that's `interceptUpdate`.
   *
   * @needs-grant onUpdate
   */
  function onUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    callback: (update: Extract<tl.TypeUpdate, { _: U }>, account: Account) => void,
  ): Disposer

  /**
   * rewrite or drop incoming updates before the app processes them. the counterpart to `onUpdate`
   * the way `interceptSendMessage` is to nothing at all: observation and mutation are separate
   * verbs, so an `onUpdate` handler can't change the world by accident and this one can't be
   * mistaken for a listener.
   *
   * mutate `update` in place, then return `'deliver'` to pass it on or `'drop'` to make it as
   * though it never arrived. chains across plugins in plugin-list order, like the other
   * interceptors; a `'drop'` ends the chain.
   *
   * **the constructor list is required**, unlike `onUpdate`'s optional one. this runs before the
   * app has processed anything, on a path that sees hundreds of updates at once during difference
   * catch-up, so the filtering has to happen natively rather than as the first line of your
   * callback. there is no legitimate "intercept everything" case; register the constructors you
   * actually rewrite.
   *
   * dropping is a blunt instrument: the app never learns the update happened, but the *server*
   * believes it was delivered, so dropping something that carries a pts/seq advance desyncs the
   * client until the next full catch-up. prefer rewriting.
   *
   * **the chain has 2 seconds**, shared across plugins — a tenth of what a send gets, because
   * updates arrive in bursts and the whole burst is waiting behind you.
   *
   * @needs-grant interceptUpdate
   */
  function interceptUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    middleware: (
      update: Extract<tl.TypeUpdate, { _: U }>,
      account: Account,
    ) => MaybePromise<'deliver' | 'drop'>,
  ): Disposer

  // -- actions --
  // callbacks hand over ids rather than entities, and you widen them yourself off the ctx's account
  // (`ctx.account.getPeer(ctx.dialogId)`, `.getMessages(...)`). that keeps the menu surface from
  // paying to serialize a chat and a message on every single menu build, when most actions only
  // care about the ids.
  //
  // `text`/`icon` may be functions of the ctx, and `visible` decides whether the row appears at
  // all — both evaluated per menu build. use `visible`: an item that is always present is an item
  // the user will resent once they have ten plugins installed.
  //
  // none of these fire in secret chats. partly falls out of secret chats having no `DialogId`, but
  // it's the behaviour we'd want anyway — an e2e chat is the last place to be running plugin code.

  /** what every action callback gets. `account` is the one the screen belongs to */
  interface ActionContext {
    account: Account
  }
  interface ChatActionContext extends ActionContext {
    dialogId: DialogId
    /** set when the chat is a forum and a topic is open */
    topicId?: number
  }
  interface MessageActionContext extends ChatActionContext {
    /**
     * the tapped bubble's messages. one entry normally; an album is a single bubble, so all of its
     * message ids arrive together rather than the action firing once per item.
     */
    messageIds: number[]
  }
  interface MessageEditorActionContext extends ChatActionContext {
    draft: TextWithEntities
    replace: (draft: InputText) => void
    send: (message: InputText) => void
  }

  /** the parts every `register*Action` shares */
  interface ActionOptions<Ctx> {
    id: string
    text: string | ((ctx: Ctx) => string)
    icon?: UIIcon | ((ctx: Ctx) => UIIcon)
    /** omit to always show. evaluated on every menu build */
    visible?: (ctx: Ctx) => boolean
    callback: (ctx: Ctx) => void
  }

  /** register a global action (drawer menu, or triple-dot menu) */
  function registerAction(options: ActionOptions<ActionContext>): Disposer

  /** register a chat-level action (in triple-dot menu of the chat) */
  function registerChatAction(options: ActionOptions<ChatActionContext>): Disposer

  /** register a message-level action (in the message context menu) */
  function registerMessageAction(options: ActionOptions<MessageActionContext>): Disposer

  /** register a profile-level action (in triple-dot menu of a profile) */
  function registerProfileAction(options: ActionOptions<ChatActionContext>): Disposer

  /** register a message editor action (in the message context menu) */
  function registerMessageEditorAction(options: ActionOptions<MessageEditorActionContext>): Disposer

  /**
   * an outgoing message, normalized. the point of this shape is that one callback covers what the
   * raw layer spreads across `messages.sendMessage`/`sendMedia`/`sendMultiMedia`/`editMessage`
   * and their scheduled variants — writing that fan-out by hand is what makes the equivalent
   * `interceptRpc` approach miserable.
   *
   * mutate in place to change what gets sent, including `peer` (to retarget the send) and `media`
   * (to attach, replace or strip attachments).
   */
  interface OutgoingMessage {
    peer: DialogId
    text: TextWithEntities
    replyToMessageId: number | null
    topicId: number | null
    /** unix seconds, or null for send-now */
    scheduleDate: number | null
    silent: boolean
    /** empty for a plain text message; more than one entry means an album */
    media: tl.TypeInputMedia[]
    /** true when this is an edit rather than a new send; `editMessageId` says which message */
    readonly isEdit: boolean
    readonly editMessageId: number | null
  }

  /**
   * intercept outgoing messages. chains in registration order like `interceptRpc` (across plugins:
   * plugin-list order): mutate `message` in place, then return `'send'` to pass it along or
   * `'drop'` to cancel the send entirely. throwing also drops it, and surfaces to the user as a
   * failed send.
   *
   * there is no `next()`, unlike `interceptRpc`. there it earns its keep by handing back the
   * response to inspect; here it would take nothing and resolve to nothing, so it carried no
   * information and existed only as a thing to forget — and forgetting it silently ate the user's
   * message. a verdict makes the choice total: under `strict`, a path that returns nothing is a
   * compile error rather than a dropped send.
   *
   * **secret chats never reach here.** an e2e message is not intercepted, not shown to plugins, and
   * not rewritable by them — the guarantee the chat makes is the whole product, and a plugin
   * sitting between the user and the encryption would quietly void it.
   *
   * sends made *by plugins* (`Account.sendMessage` and friends) don't reach here either, so a
   * middleware can send without re-entering itself.
   *
   * **the chain has 10 seconds**, same as `interceptRpc` and for the same reason — the user's send
   * is blocked on it. one budget shared by every middleware, not 10s each. past the deadline the
   * send fails and the user is told the interceptor timed out.
   *
   * @needs-grant interceptSendMessage
   */
  function interceptSendMessage(
    middleware: (message: OutgoingMessage, account: Account) => MaybePromise<'send' | 'drop'>,
  ): Disposer

  // todo: think about how we would write a plugin that suppreses typing if draft starts with dot
  // (that's a different hook — typing notifications aren't sends)
}
