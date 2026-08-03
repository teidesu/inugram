/*
a plugin is a single js file with a userscript-style metadata header:

// ==UserScript==
// @name         My awesome plugin
// @author       teidesu
// @namespace    http://example.com
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
- @run-at (when do we run?)
- @inject-into
- @downloadURL (do we want auto-updates?) also should probably support t.me message links

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

permissions are roughly split into "normal" and "dangerous". the latter are apis that can't
be sandboxed properly.

- `kv`
- `account.read` - dangerous (the local user/chat/message cache). scopes:
  - `account.read(self)` - the logged-in user only: `getMe`, `getUserFull` on yourself
  - `account.read(peers)` - users and chats: `getUser`/`getChat`/`getPeer`/`getUsers`/`getChats`/
    `getUserFull`/`getChatFull`/`resolvePeer*`
  - `account.read(messages)` - `getMessage`/`getMessages`/`getMessageFile`/`downloadMedia`
  - `account.read(dialogs)` - `getDialog`/`getDialogs`/`iterDialogs`/`getTopics`/`iterTopics`
  - `account.read(history)` - `getHistory`/`iterHistory`
  - `account.read(draft)` - `getDraft`
- `account.write` - dangerous (acts as the user, indistinguishably from the user). scopes:
  - `account.write(send)` - `sendMessage`/`sendMedia`/`sendMultiMedia`/`uploadFile`
  - `account.write(edit)` - `editMessage`
  - `account.write(delete)` - `deleteMessages`
  - `account.write(forward)` - `forwardMessages`
  - `account.write(react)` - `setReaction`
  - `account.write(read)` - `readHistory`
  - `account.write(typing)` - `sendTyping`
  - `account.write(draft)` - `setDraft`
- `interceptSendMessage` - dangerous (sees and can rewrite or drop every outgoing message)
- `onAppVisibilityChange`
- `clipboard.read` - dangerous, `clipboard.write` - warning
- `interceptRpc` - dangerous. safer variants:
  - `interceptRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can intercept
- `interceptDeserialize` - same shit
- `invokeRpc` - dangerous. safer variants:
  - `invokeRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can invoke
- `onUpdate` - dangerous. the scope list mixes the demuxed event names with raw TL constructors:
  - `onUpdate(new_message,edit_message,delete_message)` - only the `onNewMessage`/`onMessageEdited`/
    `onMessageDeleted` conveniences, without the raw stream behind them
  - `onUpdate(updateEditChannelMessage,updateEditMessage,updateMessageContent)` - explicit list of
    updates that are visible to the plugin, which also bounds what `inu.onUpdate` itself delivers
- `jvm` - **game over**. arbitrary reflection over the whole app; there is no meaningful
  narrower version of it (see the note on `inu.jvm`), so it isn't offered with a scope list.
- `xposed` - same, plus method hooking. implies `jvm`, since every `inu.xposed` call takes a
  `JavaMethod`/`JavaClass` and `inu.jvm.cls` is the only thing that mints one.
- `fetch` - mildly dangerous. safer variants:
  - `fetch(google.com,bing.com)` - explicit list of domains (+ subdomains) that the plugin can access
- `android.addNotificationCenterDelegate`, same shit
- `fs` - the plugin's own private directory, capped at 50 MB (see `inu.fs`). safer than it sounds.
  - `fs(200mb)` - a bigger cap. shown to the user as a number, so ask for what you need.
  - `fs(full)` - dangerous: the app's whole storage, absolute paths, no cap.
- `openUrl` - hands a url to the system browser

trying to call a method not defined in the grants will throw an error.

todo: grants are all-or-nothing at install right now — the user can't deny an individual one — so a
plugin can assume everything it declared is live, and `typeof inu.x` answers the only question left
("does this app have it at all"). the day grants become deniable that stops being true and this
needs an `inu.grants()`/`inu.hasGrant()` to feature-detect against, since try/catch around every
call is not an api.

note: `eval` and `new Function` API is NOT available

**ordering between plugins.** anything that chains or fans out — `interceptRpc` middleware,
`interceptSendMessage`, `onUpdate` handlers — runs in the order the plugins appear in the plugins
list, which the user controls by dragging. within one plugin, registration order applies. so two
plugins that both rewrite outgoing messages compose predictably, and the user gets to decide which
one wins.

**execution model.** every plugin gets its own queue, and all of its js — top-level, timer
callbacks, promise continuations, every handler here — runs on that one queue, one thing at a time.
so a plugin never races itself and needs no locking, and the sync calls in this api (`inu.kv`,
`inu.fs`, the cache getters on `Account`) block only the plugin that made them. a plugin that wedges
its queue wedges nothing else — with the one exception of the interceptors, which the app is waiting
on; see the deadline documented on `interceptRpc`.
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
// the plugin that armed it. callbacks run on the plugin's own queue like everything else (see the
// execution-model note in the header), so a slow one delays that plugin and nothing else.

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
}

/**
 * a deliberately small slice of the web api — no `Request`/`Blob`/`FormData`, no streaming, and
 * bodies are strings or bytes.
 *
 * when the grant is scoped to domains (`@grant fetch(a.com)`), **every redirect hop is checked
 * against the scope**, not just the url you passed — otherwise an open redirect on an allowed host
 * would launder access to any other. a hop that leaves the scope fails the request.
 *
 * @needs-grant fetch
 */
declare function fetch(url: string, init?: {
  method?: string
  headers?: HeadersInit
  body?: string | Uint8Array
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
 * `interceptRpc`'s `request` and `next()`'s argument/return value are NOT plain objects — they're
 * live views over the real app-side object. reading/writing a field reflects onto (mutates) that
 * real object directly, there is no snapshot/copy involved. nested TL objects and vectors are
 * views too, minted lazily on first access. live views exist ONLY inside an `interceptRpc`
 * dispatch — everything else (`invokeRpc` results, `onUpdate` payloads, `.toJSON()` output) is
 * plain detached data.
 *
 * caveats:
 * - `long`/int64 fields are strings (JS numbers can't hold full int64 precision), e.g. `peer.userId: "123456789"`.
 *   this is the *only* place ids are strings — a `DialogId` is always a `number`
 * - byte-array fields are `Uint8Array` copies (in live views AND in detached snapshots): reading
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
 * - a live view is only valid for the duration of its `interceptRpc` dispatch — stashing one past
 *   the dispatch settling and then touching a field throws `"TL handle expired"`. use
 *   `obj.toJSON()` to detach a plain, independently-mutable deep copy if you need to keep data
 *   around past the dispatch, or hand it to code outside the bridge (e.g. `postMessage`, or
 *   `inu.kv.set` after `JSON.stringify` — which uses the same snapshot itself)
 * - `toJSON()` is the *only* way to detach one. a live view is a host object, so `structuredClone`
 *   throws on it, and `JSON.parse(JSON.stringify(view))` gets you the snapshot's shape but with the
 *   byte fields left as `{"$inuBytes": ...}` wrappers rather than `Uint8Array`s (see above)
 * - plain object literals (`{ _: 'messages.sendMessage', peer, message }`) work fine wherever a
 *   `TLObject` is expected (e.g. a middleware's short-circuit return, or `invokeRpc`'s argument) —
 *   only values that *came from* the bridge are live views, nothing requires you to construct one
 */
declare type TLObject = tl.TypeTlObject
declare type MaybePromise<T> = T | Promise<T>

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

/**
 * an id in *input* position. also accepts the decimal-string form, because int64 fields on TL
 * snapshots are strings — so `getUser(someUser.id)` works without a manual `Number()`. only inputs
 * are widened like this; anything handed back to you is a plain `DialogId`.
 */
declare type InputDialogId = DialogId | string

/** the TL shapes that name a peer on their own */
declare type PeerLikeObject
  = | tl.TypePeer | tl.TypeInputPeer | tl.TypeInputUser | tl.TypeInputChannel
    | tl.TypeUser | tl.TypeChat

/**
 * anything that can name a peer:
 * - a `DialogId`
 * - a username (with or without a leading `@`)
 * - `'me'`/`'self'`
 * - a TL `Peer`, or an already-built `InputPeer`/`InputUser`/`InputChannel` (passed through untouched)
 */
declare type InputPeerLike = InputDialogId | PeerLikeObject | 'me' | 'self' | (string & {})

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

  // todo: everything that isn't an rpc failure currently throws a bare `Error`, so the only way to
  // tell "handle expired" from "kv quota exceeded" from "not granted" is to match on the message
  // text, which is a terrible contract. wants an `inu.PluginError` carrying a stable `code`
  // ('handle-expired' | 'quota-exceeded' | 'not-granted' | 'unknown-constructor' | ...).

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
   * **grants are per method, not per handle.** holding an `Account` costs nothing — `inu.account()`
   * needs no grant at all — and each group below declares its own, so a plugin that only sends
   * doesn't have to ask to read your message cache, and vice versa. the scope names are listed in
   * the header.
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
     * takes a *bare* user id, not a `DialogId` — see `getPeer` if you have a dialog id
     *
     * @needs-grant account.read(peers)
     */
    getUser(id: InputDialogId): tl.TypeUser | null
    /**
     * takes a *bare* chat/channel id, not a `DialogId`
     *
     * @needs-grant account.read(peers)
     */
    getChat(id: InputDialogId): tl.TypeChat | null
    /**
     * dispatches on the id's sign so you don't hand-roll the user-vs-chat branch
     *
     * @needs-grant account.read(peers)
     */
    getPeer(id: InputDialogId): tl.TypeUser | tl.TypeChat | null
    /**
     * secret chats and folder rows are not included — they have no `DialogId`
     *
     * @needs-grant account.read(dialogs)
     */
    getDialog(id: InputDialogId): tl.TypeDialog | null
    /** @needs-grant account.read(messages) */
    getMessage(dialogId: InputDialogId, messageId: number): Message | null

    /**
     * one bridge crossing for the whole batch; misses come back as `null` in place
     *
     * @needs-grant account.read(peers)
     */
    getUsers(ids: InputDialogId[]): (tl.TypeUser | null)[]
    /** @needs-grant account.read(peers) */
    getChats(ids: InputDialogId[]): (tl.TypeChat | null)[]
    /** @needs-grant account.read(messages) */
    getMessages(dialogId: InputDialogId, messageIds: number[]): (Message | null)[]

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
     * `fs(full)`; `downloadMedia` is the way to get at the bytes without that.
     *
     * @needs-grant account.read(messages)
     */
    getMessageFile(message: Message | tl.TypeMessage): { path: string, exists: boolean } | null

    /**
     * download a message's media, resolving once it's on disk. a no-op returning immediately if it
     * already is. rejects if the message has no media.
     *
     * three destinations, and the choice is really about which fs grant you're willing to hold:
     * - default (`to: 'app'`) lands in the app's own media directory, where the app would have put
     *   it anyway. cheapest, but the path is outside the plugin's directory, so reading it needs
     *   `fs(full)`.
     * - `to: 'temp'` copies it to an `inu.fs.createTempFile()` path instead: readable with plain
     *   scoped `fs`, doesn't count against the plugin's quota, and cleans itself up on unload. this
     *   is the one you want for "download it, look at it (or send it somewhere), forget it".
     * - `bytes: true` skips the filesystem entirely and hands you the content, so a plugin that
     *   only wants to inspect a file needs no fs grant at all. composes with either destination.
     *
     * @needs-grant account.read(messages)
     */
    downloadMedia(message: Message | tl.TypeMessage, options?: {
      to?: 'app' | 'temp'
      bytes?: false
      onProgress?: (loaded: number, total: number) => void
    }): Promise<{ path: string }>
    downloadMedia(message: Message | tl.TypeMessage, options: {
      to?: 'app' | 'temp'
      bytes: true
      onProgress?: (loaded: number, total: number) => void
    }): Promise<{ path: string, bytes: Uint8Array }>
    /** the `bytes: someVariable` case, which neither literal overload above can match */
    downloadMedia(message: Message | tl.TypeMessage, options: {
      to?: 'app' | 'temp'
      bytes?: boolean
      onProgress?: (loaded: number, total: number) => void
    }): Promise<{ path: string, bytes?: Uint8Array }>

    /**
     * upload a file and get the `InputFile` back, for the rpc methods that want one (setting a
     * profile photo, a chat avatar, a sticker) rather than going through `sendMedia`.
     *
     * a path needs `fs` — scoped-relative, or absolute with `fs(full)` — for the same reason
     * `sendMedia` does. bytes are exempt: they can't name a file the plugin didn't have.
     *
     * @needs-grant account.write(send)
     */
    uploadFile(file: Uint8Array | string, options?: {
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
    getUserFull(id: InputDialogId): Promise<tl.TypeUserFull | null>
    /**
     * `participants_count` and `available_reactions` live here, not on the bare chat
     *
     * @needs-grant account.read(peers)
     */
    getChatFull(id: InputDialogId): Promise<tl.TypeChatFull | null>

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
     * @needs-grant account.read(dialogs)
     */
    iterDialogs(options?: { folderId?: number, limit?: number, batchSize?: number }): AsyncIterableIterator<tl.TypeDialog>

    /**
     * newest-first. `offsetId` continues from a known message id
     *
     * @needs-grant account.read(history)
     */
    getHistory(
      dialogId: InputDialogId,
      options?: {
        limit?: number
        offsetId?: number
        minId?: number
        maxId?: number
        /** restrict to one forum topic */
        topicId?: number
      },
    ): Promise<Message[]>
    /** @needs-grant account.read(history) */
    iterHistory(
      dialogId: InputDialogId,
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
    getTopics(dialogId: InputDialogId, options?: {
      limit?: number
      cursor?: Cursor<'topics'>
    }): Promise<Paged<tl.TypeForumTopic, 'topics'>>
    /** @needs-grant account.read(dialogs) */
    iterTopics(dialogId: InputDialogId, options?: {
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
     * `file` is raw bytes, an already-uploaded TL `InputFile`/document, or a filesystem **path** —
     * a path always, never a url, even though telegram itself can send one (build an
     * `inputMediaDocumentExternal` for that). by default the type is inferred from the content and
     * the app sends it the way it would if you'd picked it in the ui; `asDocument` forces the
     * uncompressed path.
     *
     * a **path additionally needs `fs`**, even though it's this call doing the reading — a relative
     * one resolves inside the plugin's own directory, and an absolute one needs `fs(full)`. without
     * that rule `account.write(send)` alone would be enough to read any file the app can reach and
     * post it to a chat: an exfiltration primitive the grant doesn't look like it confers. bytes
     * are exempt, since they can't name a file the plugin didn't already have.
     *
     * to send something you drew, `canvas.toFile` it to an `inu.fs.createTempFile()` path and pass
     * that — it avoids dragging the encoded image through js memory the way `toBytes` does, and the
     * file cleans itself up afterwards.
     *
     * @needs-grant account.write(send)
     */
    sendMedia(peer: InputPeerLike, file: Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | string, options?: {
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
      file: Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | string
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
     * the resolved value is a plain, detached snapshot of the response (NOT a live view — see
     * `TLObject`'s doc): freely mutable, keepable forever, `JSON.stringify`-able, with the usual
     * plain-data caveats (int64s as strings, bytes as `Uint8Array`).
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

    /** pure id arithmetic */
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
      function parseDialogId(id: InputDialogId): {
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
      function fromBotApiId(id: InputDialogId): DialogId
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
     * what the user is looking at right now. `null` when nothing is on screen (app in background,
     * or too early during startup).
     *
     * this is the portable slice of it — `inu.android.getCurrentFragment` hands you the real
     * fragment when you need to go further.
     *
     * `type` is free. `dialogId`/`topicId` need `account.read(dialogs)` and are simply absent
     * without it — polling *which chat the user is reading* is the same information the message
     * cache holds, and it shouldn't be cheaper to get just because it came from the ui.
     */
    function getCurrentScreen(): {
      type: 'chat' | 'profile' | 'dialogs' | 'settings' | 'other'
      /** set for `chat` and `profile`. @needs-grant account.read(dialogs) */
      dialogId?: DialogId
      /** set for `chat` in a forum. @needs-grant account.read(dialogs) */
      topicId?: number
      /** the account being viewed */
      account: Account
    } | null

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
     * show an anchored options menu (with scrim) over the row whose callback is currently
     * running. only valid *synchronously* inside a settings-page item callback (onClick /
     * onChange / onSecondaryClick) — that item's row is the anchor; calling it anywhere else
     * (including after an `await`) throws. a menu item's onClick can't open another menu.
     */
    function openMenu(items: {
      text: string
      /**
       * show a checkmark next to the item. specifying this on ANY item (even as `false`) makes
       * the whole menu radio-style: every row reserves the checkmark column so texts align
       */
      checked?: boolean
      danger?: boolean
      onClick: () => void
    }[]): void

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
      onChange: (checked: boolean) => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: () => void
    }): UIElement

    /** tappable text row */
    function button(options: {
      id?: string
      text: string
      subtitle?: string
      /** value shown on the right side */
      value?: string
      danger?: boolean
      onClick: () => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: () => void
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
      onChange: (index: number) => void
      /** long tap (may map to e.g. right click on other platforms) */
      onSecondaryClick?: () => void
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
      onChange: (value: number) => void
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
        onClick: () => void
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
   * **a middleware has 10 seconds.** the app is blocked on it — an interceptor that awaits a `fetch`
   * against a blackholed host would otherwise hang the user's send forever, with nothing on screen
   * to explain it. past the deadline the stage is abandoned and the request fails with an
   * `inu.RpcError` saying the interceptor timed out; a late `next()` from the abandoned stage
   * throws. do slow work outside the chain and cache the answer.
   *
   * passing an **array of several methods widens both sides to the union** — `request` is any of
   * their request types and the return is any of their response types, so nothing stops you
   * returning method A's response while handling method B. typescript can't correlate the two per
   * element; register once per method where that matters.
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
   * @needs-grant interceptRpc
   */
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    method: M | M[],
    middleware: (
      request: Extract<tl.TypeRpcMethod, { _: M }>,
      next: (request: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<tl.RpcCallReturn[M] | null>,
      account: Account,
    ) => MaybePromise<tl.RpcCallReturn[M] | null | undefined>,
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
   * @not-implemented
   */
  function interceptDeserialize(rules: {
    /** constructor names to match */
    type: string | string[]
    /** only rewrite objects whose fields all match these values */
    when?: Record<string, any>
    /** fields to overwrite */
    set: Record<string, any>
  }[]): Disposer
  /**
   * the general form, for rewrites the rules can't express. pays a js crossing per matched object.
   * @not-implemented
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
   * called for every update the granted scope allows, across all arrival paths (incl. difference
   * catch-up).
   *
   * the scoped form filters by constructor and narrows `update` to it, the same way `interceptRpc`
   * does — and it's what you want by default, since a handler that switches on `update._` is paying
   * a bridge crossing per update to discard almost all of them. the unscoped form still only sees
   * what the *grant* allows.
   *
   * @needs-grant onUpdate
   */
  function onUpdate(callback: (update: tl.TypeUpdate, account: Account) => void): Disposer
  function onUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    callback: (update: Extract<tl.TypeUpdate, { _: U }>, account: Account) => void,
  ): Disposer

  // -- actions --
  // very much a draft, actual api is subject to change when i actually implenent the actions
  //
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
   * plugin-list order): call `next()` to pass the message along after mutating it, or simply return
   * without calling it to drop the send entirely. throwing also drops it, and surfaces to the user
   * as a failed send.
   *
   * `next()` takes nothing, because the message is mutated in place — there is no second object to
   * hand it. it also resolves to nothing: the sent message isn't plumbed back through the chain
   * (todo, if a plugin ever needs it).
   *
   * **secret chats never reach here.** an e2e message is not intercepted, not shown to plugins, and
   * not rewritable by them — the guarantee the chat makes is the whole product, and a plugin
   * sitting between the user and the encryption would quietly void it.
   *
   * sends made *by plugins* (`Account.sendMessage` and friends) don't reach here either, so a
   * middleware can send without re-entering itself.
   *
   * **a middleware has 10 seconds**, same as `interceptRpc` and for the same reason — the user's
   * send is blocked on it. past the deadline the send fails and the user is told the interceptor
   * timed out.
   *
   * @needs-grant interceptSendMessage
   */
  function interceptSendMessage(
    middleware: (
      message: OutgoingMessage,
      next: () => MaybePromise<void>,
      account: Account,
    ) => MaybePromise<void>,
  ): Disposer

  // todo: think about how we would write a plugin that suppreses typing if draft starts with dot
  // (that's a different hook — typing notifications aren't sends)
}
