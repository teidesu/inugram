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

**unsafe** — the sandbox is not a boundary. the grant reaches things the api never named, a scope
list narrows what it starts from without bounding where it gets to, and holding one makes the
plugin's *other* grants descriptive rather than enforced. a plugin with
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
  - `fetch(google.com,bing.com)` - explicit list of domains (+ subdomains) that the plugin can
    access. domains only, case-insensitively — no scheme, no path, no port, no wildcard
- `openUrl` - hands a url to the system browser. in this tier because a url is a *message*: the
  query string is an exfiltration channel that happens to flash a browser at the user.

then the data itself:

- `account.read` - the local user/chat/message cache. scopes:
  - `account.read(self)` - who you are: `getMe`, `getUserFull` on yourself, `Account.userId`,
    `inu.accounts()`, and **naming yourself** (`'me'`/`'self'`) as the peer of any other read
  - `account.read(peers)` - users and chats: `getUser`/`getChat`/`getPeer`/`getUsers`/`getChats`/
    `getUserFull`/`getChatFull`/`resolvePeer*`
  - `account.read(messages)` - `getMessage`/`getMessages`/`getMessageFile`/`downloadMedia`. the
    media members take any `Message`-shaped TL value, one you built included, so this grant is
    "fetch any file a valid file reference names" and not only "files in messages you read";
    getting a usable reference is what still needs the read
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
- `interceptDeserialize` - same, plus everything loaded from the local cache. safer variants:
  - `interceptDeserialize(user,userFull)` - explicit list of constructors the plugin can rewrite
- `invokeRpc` - talks to telegram as this account directly. safer variants:
  - `invokeRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can invoke

-- unsafe --

a scope list here narrows *reach*, never the tier: whatever it says, the capability walks around it
from anything it does name, so what you are reading is still "do you trust the author":

- `unsafe.fs` - `inu.fs` with the scoping removed: absolute paths, the app's whole storage, no cap.
  reaches another plugin's data, the message cache, the sqlite databases, and the media behind a
  `getMessageFile` path. does *not* imply `fs` — it replaces it, so declaring both says nothing
  more than declaring the one.
- `unsafe.jvm` - arbitrary reflection over the whole app. safer variants:
  - `unsafe.jvm(java.util.*,android.widget.TextView)` - explicit list of classes and namespaces,
    checked on every class, value and member that crosses, not only on the way in. it is still this
    tier: naming any of reflection's own classes is the same statement as the bare grant, and
    `loadDex` needs the bare grant whatever else the list says. see the note on `inu.jvm`.
- `unsafe.xposed` - method hooking. every entry point takes a `JavaMethod`/`JavaClass`, so this is
  only useful next to `unsafe.jvm` — but it does **not** imply it. list both. a grant that silently
  turns into two is a grant the user didn't read, which is the one thing this tier can't afford.
- `unsafe.notificationCenter` - the app's whole internal event bus (`inu.android`). here rather
  than in `sensitive` because that bus *is* the app's internal state machine: one registration
  hears every dialog opened, every message received and every file fetched, and a scope list over
  event names would not narrow it into anything a user could reason about. the api filtering below
  does not apply to it and can't: the payloads are arbitrary java objects, not TL — which is also
  why only their scalars cross, see `inu.android.addNotificationCenterDelegate`.
- `unsafe.disableApiFiltering` - turns off the account-takeover filtering described below. legitimate
  uses exist (a plugin that manages your sessions, or surfaces service messages properly), and they
  are indistinguishable from the illegitimate ones, which is why this is where it lives.

trying to call a method not defined in the grants will throw an error. a grant naming a scope the
app doesn't recognise (a misspelt rpc method, `fs(unlimited)`, `fetch(https://a.com)`) is **rejected
at install** rather than silently narrowing to nothing — unlike an unknown grant *name*, whose
vocabulary is open by design, a scope's vocabulary is closed and a typo in one is always a bug. for
`fetch` what's closed is the *shape*: the app has no list of real domains, but a scope carrying a
scheme, a path or a port is one no host can ever match.

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
because the design is settled and moving them later would be breaking. they are deliberately not
deleted: the shape is the part worth agreeing on early, and a member that appears in a later api
level with a different signature than the one people already read is worse than one that says so up
front.

a tagged member is **usually absent from its object entirely**, so reaching it is a plain
`TypeError`, not a `PluginError` you can branch on by code. feature-detect with `typeof` (or
`in`) rather than catching; some tagged members do throw `'unsupported'` instead, so a `try`/`catch`
keyed on `e.code` is the one form that is wrong either way. check `inu.info().apiVersion` if you
need to know, or declare the `@plugin-api` level that ships it.

**account-takeover surfaces are filtered.** a plugin that can read a login code, or mint a login
token, owns the account outright — at which point every other grant on this page is decoration. so
a fixed set of things is removed from what plugins can see and do, regardless of grants, with
`unsafe.disableApiFiltering` the only way off. four rules:

- **login codes are redacted from message text.** every maximal `[0-9-]{5,}` run in a message's
  `message` field is replaced by a run of `*` of exactly the same length, on messages *from the
  service peers*: 777000, and anyone with `UserObject.VERIFY` (489000). only from those senders, so
  an ordinary message that happens to contain a six-digit number is untouched. the same length is
  the point, since entity offsets are utf-16 offsets into that string: `entities` is never adjusted
  and so cannot desync. the shape is deliberately wider than the `[\d\-]{5,8}` stock spoils in the
  ui, which would leave the last three characters of an 11-character run readable. the sender is
  `from_id`, or `fwd_from.from_id` for a forwarded copy, or the dialog peer for an incoming message
  with neither: `from_id` is `flags.8?Peer` and a 1:1 dialog omits it, so keying off it alone would
  miss every login code read straight off the wire. because that verdict is recomputed on each read,
  the fields it reads (`from_id`, `peer_id`, `fwd_from`, `out`) are **sealed**: assigning one throws
  `forbidden` and the peers behind them come back read-only even on a writable view, or clearing the
  sender would be a one-line way to read the code in clear. reading the text yields the redacted
  form, so a plugin that writes it back onto a writable view writes the redaction, not the original,
  and a `toJSON()` snapshot returned as an intercept response is lossy the same way. the message
  itself is still delivered:
  filtering the whole peer meant `getDialogs` showing a chat whose `top_message` resolved to `null`,
  which is a worse api for no more safety.
- **`updateServiceNotification` is not delivered** at all. it carries a login code with no peer
  attached, so there is nothing to redact against. the same constructor also rides inside
  `updates.getDifference` responses, where dropping it would punch a hole in a vector: there its
  `message`, `media` and `entities` are stripped the way `config.autologin_token` is below, so the
  update is visible and its `type`/`inbox_date`/`popup` still read, but the payload is gone.
- **takeover rpc methods are refused**, in `invokeRpc` and `interceptRpc` alike: everything under
  `auth.*`, plus `account.`\{`getPasskeys`, `deletePasskey`, `registerPasskey`,
  `initPasskeyRegistration`, `registerDevice`, `unregisterDevice`, `deleteAccount`, `changePhone`,
  `getAuthorizations`, `resetAuthorization`, `acceptAuthorization`, `verifyPhone`, `verifyEmail`,
  `resetPassword`\}. refused rather than filtered — a scoped grant naming one of these fails at
  install, and calling one throws `forbidden` (see `PluginError`). `auth.exportLoginToken` alone is
  a complete takeover without reading a single message, which is why this list matters more than
  the redaction above.
- **`config.autologin_token` is stripped** from `help.getConfig` responses. it logs into telegram's
  web properties as the user, no code required. a stripped field reads exactly like an optional
  field whose flag bit is clear: `in` says false, `Object.keys()` omits it, reading it yields `null`.
  writes differ, because a write would reopen the read: assigning one is refused the way assigning a
  field the type does not have is, rather than setting it.

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
appear in the plugins list, which the user controls by dragging. it is the position in that list
that decides, never who registered first, so installing a plugin later doesn't condemn it to run
last. within one plugin, registration order applies. so two plugins that both rewrite outgoing
messages compose predictably, and the user gets to decide which one wins. a chain that is already
walking keeps the order it started with: a reorder applies to the next request, not to one in
flight.

**execution model.** all of a plugin's js — top-level, timer callbacks, promise continuations,
every handler here — runs one thing at a time, so a plugin never races itself and needs no locking.
that much is a guarantee. what is *not* one is parallelism: every plugin's js runs on a single
app-wide queue (one thread, shared between plugins and with app work posted there), so a plugin
burning cpu synchronously delays every other plugin's callbacks, not only its own. a plugin merely
*awaiting* something costs nobody anything: the thread is free while a promise is pending, and the
only thing stalled is that plugin's own progress.

which is why synchronous work has a hard ceiling. **one entry into a plugin, meaning one callback
plus the microtasks it queues before returning to the host, may run 2 seconds of uninterrupted
js**, or 10 for the top-level evaluation that compiles the whole file once. past that the engine
stops it from the inside, with an error `try`/`catch` cannot swallow and a looping `finally` cannot
outlive: the rest of that turn is dead, and one line naming the budget goes to the app log. only
the turn dies: timers, registrations and everything else the plugin set up are still there, and
the next callback starts with a full budget. whatever was waiting on the dead turn gets the
failure: an interceptor stage cut down this way answers with it, which fails the app's request
unless a stage above it catches it.

that ceiling is on the turn itself, and a synchronous host call (`inu.kv`, `inu.fs`) is *charged* to
it but cannot itself be cut short: the interrupt is polled on js back-edges, so a turn parked inside
a host call dies on the first instruction after that call returns, not at the deadline. an *async*
stall is a different matter, and nothing preempts it: `await new Promise(() => {})` in an
`onUpdate` handler parks that handler forever, deliberately, because nothing but itself is waiting
on it. the one place something *is* waiting is the interceptors, and that is what the chain budget
below is for.

**memory has a ceiling too, and it is per plugin.** every plugin gets its own engine with its own
heap, capped at 32 MB, so a runaway allocation is refused inside that plugin rather than taken out
of the app's. what your code sees there is not a `PluginError`: building one needs an allocation the
engine has just refused. it is the engine's own failure, `InternalError: out of memory`, or a bare
`null` when the heap was too full to dress even that up, which is the shape a heap grown gradually
usually hits. a `catch` around allocation-heavy work must therefore not assume it caught an `Error`.
the host names it `quota-exceeded` where it reports it, on the plugin's page and in the app log.
unlike the execution ceiling above it *is* catchable, and recovering is real: the refused allocation
never happened, and unwinding out of it frees everything the attempt was holding. the engine itself
is never in danger and no other plugin notices, which is the whole point of a heap per plugin.

**an array you hand a native member is read at most 65536 elements long**, whatever it is - an
argument list, an item list, a rule set, a list of constructor names. past that the call throws
`invalid-argument` and nothing of it happens. this is structural rather than a limit on any one api:
a length is a number a plugin writes, and reading one the runtime cannot serve is not something the
member being called gets a say in.

**bytes that don't live on the js heap are counted separately.** an `OffscreenCanvas`, an
`ImageBitmap` and a `Blob` are a handle in front of native memory the engine cannot see, and quickjs
schedules its collections off js heap growth, so a plugin holding hundreds of MB of those still
looks idle to it. they get their own budget, 64 MB per plugin. one 4096x4096 canvas is exactly 64 MB
of that, which is deliberate: a surface that size is for exporting one image, not for keeping. going
past the budget throws `quota-exceeded` carrying `usage`/`quota`, and *this* one is a `PluginError`
you can catch and back off from, because the allocation never happened. anything bigger belongs in
`inu.fs`, streamed rather than held.

a `Blob` is the one thing here that has somewhere else to put its bytes: content the budget won't
hold spills to the app's cache area instead, and the ceiling it hits there is the spill's own. so a
full native budget is a canvas problem and not a blob one, *unless* the app could not make this
engine a spill directory at all (no room left, storage unavailable), which leaves a blob with
nowhere to go and a full budget refusing it like anything else. that is rare and nothing in the api
announces it, so building a big blob is worth the same `catch` as allocating a big canvas.

**two places are called from the app's own threads: `interceptDeserialize` and `inu.xposed`.**
neither of them runs your code there. both post it to the plugin's thread and park the app's thread
on the answer for a bounded time, so the mutual exclusion above holds for them exactly as it does
for everything else — see `android.xposed.d.ts` for the hook side. deserialization happens on the
app's network and storage threads, it is synchronous by nature (the object is rewritten before the
app looks at it), and it fires per nested object — thousands of times during a cold start. hopping
each of those onto the plugin's own thread and back would cost more than the interception saves. so
the two tiers answer that differently. the **declarative** form never enters an engine at all: a
rule is data, it crosses once when you register it, and the app's deserializing thread evaluates it
natively. the **middleware** form does hop — it is posted to the plugin's own thread and the
deserializing thread is parked on the answer for at most 250ms, past which the object is delivered
as parsed and the walk stops at the next engine boundary. the mutual exclusion above still holds:
the callback runs where every other callback runs, and the deserializing thread simply waits. that
is also why a slow middleware costs a quarter of a second per object and never the object itself.

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

it counts wall time, so awaiting a `fetch` spends it exactly as fast as computing does, and it is
the *only* thing an async stall answers to; a stage that spins instead is cut down earlier, by the
per-entry ceiling above. what it doesn't count is the real request's time on the wire: that is
suspended, so a slow server can never expire a chain. one consequence is worth knowing about: the
budget can therefore run out *after* the request already succeeded, while a stage is still unwinding
the response. the app is then handed what the server actually sent rather than a timeout (calling a
delivered `messages.sendMessage` failed would earn a duplicate the moment the user resends), and the
late stage simply loses its chance to rewrite it.
*/

// the non-`inu` globals the sandbox provides. spelled out here rather than pulled from typescript's
// `dom` lib, because there is no dom — borrowing those types would promise a great deal that isn't
// there. compile plugins with `lib: ["es2022"]` and nothing else.
//
// the engine is quickjs-ng, so everything in es2022 is real, plus `atob`/`btoa`, `queueMicrotask`,
// `performance`, `BigInt`, `Proxy`/`Reflect`, `WeakRef`/`FinalizationRegistry`.
//
// **`Intl` is absent**, which es2022 doesn't cover anyway: `toLocaleDateString`, `toLocaleString`
// and friends exist but ignore their locale argument. `inu.utils.format*` covers the common cases,
// in the engine's own english (see there — it does not read the app's langpack).

/** goes to the app's log. arguments are stringified and joined with a space — no format specifiers */
declare const console: {
  log(...args: any[]): void
  info(...args: any[]): void
  warn(...args: any[]): void
  error(...args: any[]): void
  debug(...args: any[]): void
}

/** binary strings, as on the web. the `Uint8Array` pair is `inu.utils.toBase64`/`fromBase64` */
declare function atob(data: string): string
declare function btoa(data: string): string

/**
 * milliseconds off a monotonic clock. `timeOrigin` is where *this engine* started counting rather
 * than a unix epoch, so it means nothing except subtracted from another reading of the same clock.
 * `Date.now()` is the one that answers what time it is.
 */
declare const performance: {
  now(): number
  readonly timeOrigin: number
}

/**
 * the web error, thrown by the globals whose spec says so: `crypto.getRandomValues` past its
 * ceiling (`QuotaExceededError`), `structuredClone` on a value with an identity (`DataCloneError`),
 * and an `AbortSignal`'s default `reason` (`AbortError`). nothing under `inu.` throws one: that is
 * `PluginError`, which is a separate type on purpose.
 */
declare class DOMException extends Error {
  constructor(message?: string, name?: string)
  /** the legacy numeric code, `0` for a name that has none */
  readonly code: number
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
// against the app rather than against the plugin. while the app is hidden your timers fire at most
// once a second, dropping to once a minute after it has been hidden five minutes. the limit is on
// *you*, not on each timer: ten intervals cost one wake-up per period, not ten, and everything due
// at that wake-up fires together, so backgrounded `setTimeout(f, 10)` and `setTimeout(f, 900)`
// are the same timer.
//
// a suspended interval fires *once* on return to the foreground rather than replaying everything
// it missed — so a timer is not a clock, and a plugin that needs to know how long it was away
// should ask, or do its catch-up work from `inu.onAppVisibilityChange`.
//
// the floor lifts while one of your `interceptRpc` stages is holding a request of the app's own.
// that request has ten seconds to live, a backoff or a debounce written with `setTimeout` is the
// ordinary way to spend some of them, and a request the app is waiting on is not the idle periodic
// work the floor is aimed at. it lifts for the whole wheel, and only until the stage settles.
//
// **in the foreground they are paced by what they cost.** your callbacks run on a thread you share
// with every other plugin and with the app itself, so after a tick taking n ms your next timer
// waits ~9n before it is served (and never less than 4 ms, the same clamp a browser puts on a
// nested `setTimeout`). timers that do their work and stop never notice; the thing this bounds is
// `setTimeout(function f() { setTimeout(f, 0) }, 0)`, which re-arms forever and which no
// per-callback time limit can catch. as with the background floor, the limit is on *you*: it
// delays the whole wheel by one gap rather than each timer by its own, and nothing else the plugin
// does slows down.
//
// **a repeating timer carries a floor of its own**, on top of that pacing:
// an interval repeats every 4 ms at the fastest, the same clamp a browser puts under a repeating
// timer, so `setInterval(f, 0)` is a fast timer rather than a spin on the queue you share. it is
// applied when the interval is armed, so it is the period that is floored and not the first run;
// `setTimeout(f, 0)` is unaffected, having no period to floor.
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
  readonly encoding: 'utf-8'
}
declare class TextDecoder {
  constructor(label?: 'utf-8')
  decode(input?: Uint8Array | ArrayBufferLike): string
  readonly encoding: 'utf-8'
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

/**
 * structured clone of plain data. no transferables — there is nothing to transfer to.
 *
 * cycles are preserved, and `Date`/`RegExp`/`Map`/`Set`/`ArrayBuffer`/typed arrays/`Error` clone as
 * themselves; anything else with an identity rather than a value — a function, a symbol, a promise,
 * a weak collection — throws `DataCloneError`, as on the web.
 *
 * **a TL view throws too.** it is a host object: its fields live behind the bridge, so cloning one
 * would walk the whole graph across it a field at a time (exactly the cost lazy views exist to
 * avoid), a dispatch-scoped one could expire halfway through, and the result would look like a
 * plain object while `toJSON()` already detaches one in a single hop with byte fields revived. so
 * `toJSON()` stays the only way to detach a view, and `structuredClone` refuses instead of
 * pretending.
 *
 * **a `Blob` clones by reference**, as it does on the web: you get a second handle over the same
 * content rather than a copy of it, so cloning a 200 MB download costs nothing and copies nothing.
 * the relation is the one `Blob` already explains — disposing the *original* makes reads on the
 * clone throw `handle-expired`, disposing the clone leaves the original alone. a `File` clones as a
 * `File`.
 */
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
 * handed over and don't lie about that; only reads can fail. **replaced counts as deleted**: the
 * app re-downloads into the same path, and content that no longer matches the `size` this blob
 * promised would be a quieter lie than `handle-expired`.
 *
 * spilling isn't free either, and there are two ceilings, the same shape as every other one here:
 * **2 GB of spilled content live at once**, and **64 spilled blobs held at once** — each is an open
 * file the process cannot get back, and a blob spills at any size once the native budget is full,
 * so the count is usually what you meet first. past either, building a blob throws
 * `quota-exceeded`. both collect before they refuse, so a blob stranded in a reference cycle costs
 * you the quota only until the next sweep, not permanently. so does a
 * spill that can't be written because the device is full, which is the one `quota-exceeded` that
 * carries no `usage`/`quota`: there is no ceiling of yours to name. that's per plugin, and it counts
 * what you're *holding* — a download you finished with and dropped costs nothing. an engine the
 * app couldn't make a spill directory for has none of this: nothing spills, and content the native
 * budget won't hold is refused instead.
 *
 * lifetime is the engine's business too, and works the way it does on the web — quickjs refcounts,
 * so a blob is freed the moment the last reference to it goes, and everything outstanding is freed
 * when the plugin unloads. `dispose()` exists for the two cases where that isn't good enough.
 *
 * handles are per-plugin: a blob is meaningless to any plugin but the one it was handed to, so two
 * plugins can't reach each other's content by guessing.
 *
 * what's missing from the spec version: `stream()`, which needs a `ReadableStream` this sandbox
 * doesn't have; the constructor's `endings` option, which rewrites line endings for a platform
 * this isn't; and parts as any iterable: it has to be an array. what's added is `dispose()`.
 * everything else behaves as the spec says, `Symbol.toStringTag` included. writing one out is
 * `inu.fs.write`, which takes a blob directly — it's the filesystem's business, not the blob's, and
 * putting it here would have meant a method whose grant contradicts the rest of the type's.
 */
declare class Blob {
  /**
   * parts are concatenated, exactly as on the web. no grant — these are bytes you already had.
   *
   * **one call assembles at most 32 MB.** past that it throws `quota-exceeded` *synchronously*,
   * carrying `usage`/`quota`, having read nothing of the part that crossed the line and kept
   * nothing of what it had. this is the one ceiling here that is about time rather than space:
   * joining blobs is host work, copying file to file, and the execution ceiling cannot see host
   * work — it is polled on js instructions, so a single large copy would block every plugin and the
   * app with nothing able to stop it. to join more than that, write the pieces out with `inu.fs`.
   */
  constructor(parts?: (Blob | Uint8Array | ArrayBuffer | string)[], options?: { type?: string })

  readonly size: number
  /**
   * mime type, or `''` when whatever produced this didn't know one.
   *
   * **truncated at 1024 characters**, and so is a `File`'s `name`. a handle costs ~100 bytes of js
   * heap while the host keeps the whole label, and neither the js ceiling nor the native one
   * charges for that copy, so these two are the only strings here a pocketful of handles could
   * turn into arbitrarily much process memory. truncated rather than refused, because both are
   * metadata and no construction should fail over a tail nobody reads.
   */
  readonly type: string

  /**
   * a view over a range. cheap — it doesn't copy, and on a spilled blob it doesn't read.
   *
   * being a view is also the catch, and it's the web's: **a slice retains what it was sliced
   * from**. sniffing a four-byte header off a 200 MB download and keeping the slice keeps all
   * 200 MB alive (in memory, or as a cache file the app can't reclaim) for as long as you hold
   * it. if the slice is what you meant to keep, copy it out (`new Blob([await slice.bytes()])`)
   * and drop the original.
   *
   * `dispose()` follows the same relation: disposing the parent frees the backing, so reads on any
   * slice of it throw `handle-expired` afterwards. disposing a slice never touches the parent.
   */
  slice(start?: number, end?: number, contentType?: string): Blob

  /**
   * materialize into js. this is the copy a blob exists to avoid — call it only if you need it.
   *
   * **`bytes()` and `arrayBuffer()` are capped at 16 MB**, half the js heap, because the copy has to
   * coexist with everything else you're holding. **`text()` stops at 8 MB**: one character past
   * latin-1 makes a js string cost two bytes for every byte you gave it, so text takes half of what
   * a byte read may. past either you get a `quota-exceeded` rejection carrying `usage`/`quota` and
   * *nothing is read*: without the cap the read happens first and then dies as the engine's own
   * out-of-memory, which is neither catchable-looking nor attributable to the line that caused it.
   * slice what you actually need, or write the whole thing out with `inu.fs.write`, which never
   * crosses into js at all.
   *
   * these three *reject* rather than throwing where you called them, as on the web — including for
   * `handle-expired`, so a disposed blob fails in the `await`, not before it. the one failure not
   * promised to arrive that way is the engine failing to allocate the copy at all: that is its own
   * out-of-memory rather than a `PluginError` (see the memory section), and it can land at the
   * call site instead of in the `await`.
   */
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
 * how a transfer reports itself: `loaded` bytes of `total`, `total` being 0 while the size is still
 * unknown.
 *
 * **coalesced on a time interval, not per chunk.** a transfer reports every 32 KB, so a 200 MB
 * download is ~6400 reports, for a pair of numbers whose consumer redraws at display rate at best.
 * you get the first one, then at most one per 100 ms, then the final one, which always arrives
 * even when it lands inside a window that was suppressing the rest. so `loaded` jumps, and the gap
 * between two calls is not the time those bytes took to arrive; if you want a rate, measure it.
 *
 * a transfer that ends *without* getting there — cancelled, failed, the connection dropped — still
 * delivers the last report it managed to make, so the number you are left on is where the transfer
 * really stopped rather than wherever the last window boundary happened to fall.
 */
declare type ProgressCallback = (loaded: number, total: number) => void

/**
 * header values, both directions. a `string[]` means the header repeated — which is why this isn't
 * the spec's `Headers` object: our `fetch` isn't spec-compliant anyway, and a plain record that can
 * hold repeats beats a class that silently joins `Set-Cookie`s with a comma.
 */
declare type HeadersInit = Record<string, string | string[]>

/**
 * what a `fetch` resolves to. there is no global `Response` constructor — this is a shape you are
 * handed, not one you build.
 *
 * **the body is a `Blob` the app is holding**, written out as it arrived and never materialized
 * into js unless you ask. so the reads below are that type's reads, with its ceilings (16 MB for
 * bytes, 8 MB for text) and its `quota-exceeded`, and **the body can be read more than once**
 * — unlike the spec's, which is a stream you consume. **the file lives until the plugin unloads**,
 * and `dispose()` only ends your handle on it: reads then fail `handle-expired`, but the content and
 * the fetched-content budget it holds are not given back before then.
 */
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
 * **every failure arrives as a rejection**, including the ones decided before anything is sent (a
 * missing grant, a url this doesn't speak, a body it can't read). `fetch(...).catch(...)` is the
 * only shape that needs writing. an http *status* is not a failure: a 404 resolves, with `ok` false.
 *
 * when the grant is scoped to domains (`@grant fetch(a.com)`), **every redirect hop is checked
 * against the scope**, not just the url you passed — otherwise an open redirect on an allowed host
 * would launder access to any other. a hop that leaves the scope fails the request with
 * `not-granted` naming the host it tried to reach, and that hop is never sent.
 *
 * **loopback and private ranges are refused** whether the grant is scoped or not: 127/8, ::1,
 * 169.254/16, 10/8, 172.16/12, 192.168/16, and any hostname resolving into them (`forbidden`, and
 * nothing lifts it). `fetch` is meant to be a grant about the internet, and unscoped it would
 * otherwise reach every other app's debug server on the device and the whole of the user's lan —
 * neither of which is what a user reading "arbitrary http" pictures. resolved addresses are checked
 * and **every** answer a name has must be public, not just the one that would be used, so a name
 * with one private record in it doesn't get through on a coin flip. the same check runs per
 * redirect hop, since a host inside an allowed domain can still point at the device.
 *
 * a url whose host isn't what it reads as is refused outright (`invalid-argument`): userinfo
 * (`https://good.com@127.0.0.1/`), a backslash in the authority, whitespace or control characters
 * anywhere in it, and any scheme other than http/https. that holds for a redirect's `Location` too,
 * which is where it matters most: a granted host answering `302 file:///…` is refused rather than
 * turned into a local file read.
 *
 * **bodies are capped at 32 MB in each direction.** a request body past that is `quota-exceeded`
 * (that's `uploadFile`'s job), and so is a response body. on top of that
 * you hold at most 256 MB of fetched content at once, and a body counts against that cap until the
 * plugin unloads, `dispose()` included. a redirect's own body doesn't count: it's dropped as the
 * chain moves past it.
 *
 * headers the transport owns are refused (`invalid-argument`): `host`, `content-length`,
 * `connection`, `transfer-encoding`, and friends. so is a name that isn't a token, a repeat of one
 * you already gave, and a value with a control character in it. the app checks all of that again
 * where it opens the socket, so none of it is a refusal you can talk your way out of.
 *
 * @needs-grant fetch
 */
declare function fetch(url: string, init?: {
  method?: string
  headers?: HeadersInit
  body?: string | Uint8Array | Blob
  /**
   * `follow` (the default) walks the chain, screening each hop; `manual` hands the 3xx back as the
   * response; `error` rejects (`network`) as soon as one arrives. a chain longer than 20 hops is
   * `network` too. as everywhere else, a 303 — and a 301/302 on anything but GET/HEAD — continues
   * as a bodyless GET, so a body is never replayed to a host you didn't name.
   */
  redirect?: 'follow' | 'manual' | 'error'
  /** rejects `aborted` when it fires, and the request is really stopped rather than only ignored */
  signal?: AbortSignal
  /** milliseconds; rejects `timed-out` when it elapses. unset means no client-side limit */
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
 * materialized on access, nested objects and vectors mint views of their own, and a field you never
 * read is never crossed over the bridge. reading two fields off a hundred messages costs two
 * hundred reads, not a hundred object graphs. the cost model to keep in mind is that *enumerating*
 * one (`Object.keys`, spread, `JSON.stringify`) touches every field and so pays for the whole graph
 * at once — targeted access is the cheap path, and `toJSON()` is the honest way to ask for the
 * expensive one.
 *
 * what is and isn't cached follows from that:
 * - the long-lived views (`invokeRpc` results, `onUpdate` payloads, `Account` reads) cache each
 *   field you read, so re-reading one is free and `msg.peer === msg.peer` holds.
 * - `interceptRpc` views are **not** cached, because a middleware later in the chain has to observe
 *   what an earlier one rewrote. every read there is a fresh look at the live object.
 * - **vector elements and `length` are never cached**, in any view. walking a vector is what you do
 *   to find the one element you keep, and caching the walk would retain every element you passed.
 * - **any write invalidates every cached field**, everywhere — not just the field written and not
 *   just the object written through. optional fields share flag bits, and a vector mutation resyncs
 *   its owner's bit, so a write can change which *other* fields are present.
 * - a cached field is a sample, not a subscription: on a read-only view the app may have changed
 *   the object since. `toJSON()` always reads live.
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
 * field, so it isn't done for you — and can't be done by you either. `Object.freeze`,
 * `Object.seal` and `Object.preventExtensions` all throw `unsupported` on a view, read-only or
 * not, because a sealed view can no longer answer honestly about fields it hasn't materialized.
 * `Object.defineProperty` works only as a plain `{ value }` assignment, and obeys the same
 * read-only rule as `=`; asking for an accessor or for `writable`/`enumerable`/`configurable:
 * false` throws `unsupported`.
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
      /**
       * `kv`'s 1 MB, `fs`'s cap, the native-memory budget, a blob spill (its 2 GB ceiling, its limit
       * of 64 spills held at once, or the device being full), one read materializing past its cap,
       * or one construction assembling past 32 MB. carries `usage`/`quota` whenever there is a
       * ceiling to name — **in bytes, except the spill-file ceiling, which counts files** — and the
       * device being full names neither: how much room it has is not this plugin's allowance
       */
      | 'quota-exceeded'
      /**
       * a TL view used past its dispatch, a disposed `Blob`/`ImageBitmap`/`UIPage`, or content
       * that has gone away underneath a live one — the file a `Blob` was over being deleted or
       * replaced
       */
      | 'handle-expired'
      /** a constructor name this app's layer doesn't have */
      | 'unknown-constructor'
      /** malformed peer, bad path, unknown icon name */
      | 'invalid-argument'
      /** the peer/message doesn't resolve, where the api rejects instead of answering `null` */
      | 'not-found'
      /**
       * in the typings, not on this platform or api level: `jvm.defineClass`/`jvm.callSuper`, a
       * `dialog` `body`, an action row's `icon`, `interceptDeserialize`'s middleware form, and the
       * three things `inu.canvas` can't ask the platform's rasterizer for (see `canvas.d.ts`)
       */
      | 'unsupported'
      /** the interceptor chain's budget, or `fetch`'s `timeout` */
      | 'timed-out'
      /** an `AbortSignal` fired, or an interceptor chain was torn down for a non-budget reason */
      | 'aborted'
      /**
       * a `fetch` that never completed: the name didn't resolve, the connection or the tls
       * handshake failed, the peer went away mid-body, or the redirect chain ended the request
       * (`redirect: 'error'`, or too many hops). the request may or may not have reached the server
       */
      | 'network'
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
    /**
     * data from the metadata header, base key lowercased, in declaration order. always an array,
     * one entry per occurrence, because a directive may repeat (`@grant`, `@description:xx`) — a
     * directive that appeared once is a one-element array, not a string.
     */
    header: Record<string, string[]>
  }

  /**
   * called before the plugin is about to be unloaded (manual disable/reload/uninstall, engine
   * toggle). NOT guaranteed to run on process death. callbacks run synchronously in registration
   * order right before engine teardown — async callbacks are not awaited, and promises pending a
   * host round-trip (`invokeRpc`, `ui.dialog`) never settle. a throwing callback doesn't stop the
   * others, but it is a fault like any other callback that throws: your plugin is switched off, and
   * a teardown that always throws is one the user has to clear before the plugin runs again.
   *
   * there's no "load" event because "the script is being ran" is already a load event
   */
  function onUnload(callback: () => void): Disposer

  /**
   * hand a url to the system browser. was `window.open`, which pretended there was a dom behind it.
   *
   * **`http:` and `https:` only** — anything else throws `invalid-argument`, as does a url with
   * userinfo in it (`https://telegram.org@evil.com/`), a backslash in its authority, no host at
   * all, or whitespace/control characters anywhere in it. this is not a spelling rule:
   * every other scheme names an *action* rather than a page, and none of them is what this grant is
   * described as buying. `tg:` is the app's own deeplink surface, `intent:` names an activity and
   * its extras outright, `file:`/`content:` name the storage the sandbox exists to gate.
   *
   * it does **not** go through the app's own link opener, so an autologin domain does not get the
   * account's autologin token appended the way it would for a link the user tapped. what opens is
   * whatever the system has registered for the url, which for a `t.me` link may well be this app.
   *
   * with no ui on screen it does nothing: there is nothing to open into, and this returns `void`,
   * so nothing is reported either.
   *
   * @needs-grant openUrl
   */
  function openUrl(url: string): void

  /**
   * called when the app is moved to the background/foreground. transitions only: you are not
   * told the state you are already in, and a plugin that loads while the app is hidden hears
   * nothing until it comes back.
   *
   * this is where catch-up work goes, because your timers didn't run at their own rate while you
   * were away (see the timers section) and a `foreground` here is the first moment they will
   * again.
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
    /**
     * bytes used of the 1 MB quota, so you can shed entries before a `set` starts throwing.
     * a key and its value both count, and nothing else does
     *
     * @needs-grant kv
     */
    function usage(): number
  }

  /**
   * access to the clipboard. two grants rather than one, and they are two tiers apart: writing
   * clobbers what the user copied, reading hands you whatever they last copied anywhere on the
   * device, which is disproportionately passwords and one-time codes. holding one says nothing
   * about the other.
   */
  namespace clipboard {
    /** @needs-grant clipboard.write */
    function write(text: string): void
    /**
     * the plain text of the clipboard, or `''` for anything that isn't text — an empty clipboard,
     * a clip carrying only a uri or an intent, and android 10+ refusing the read because the app
     * isn't the focused one. a clip is never *coerced* to text: doing that dereferences a
     * `content://` uri through the app's own permissions, which would turn this into "read any
     * provider telegram can reach".
     *
     * @needs-grant clipboard.read
     */
    function read(): string
  }

  /**
   * convenience wrapper over a raw TL message. every getter is a pure function of [raw], and a
   * *lazy* one: nothing is copied at construction and each read goes to [raw] afresh. so wrapping
   * a live `interceptRpc` request sees what the middleware above you rewrote, wrapping a view
   * read-only leaves it read-only, and wrapping a dispatch-scoped view expires with it rather than
   * outliving it holding stale text. wrapping is free; it is the reads that cost.
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
     * so `null` means a secret-chat message, which has no `DialogId` at all (see there) — or a
     * `messageEmpty`, the hole the server reports in place of a message you can't see, which
     * carries no peer to derive one from either. `isSecret` tells the two apart. `raw.dialog_id`
     * still carries the app's own value as a string if you need it.
     */
    get dialogId(): DialogId | null
    /**
     * `null` on channel posts with no visible author, and on an outgoing message the app has not
     * annotated (there the sender is whoever you are, which a pure function of [raw] cannot know).
     *
     * `from_id` is `flags.8?Peer` and the server omits it in a 1:1 dialog, so an incoming message
     * with none falls back to the dialog peer, which is who sent it. it does *not* fall back to
     * `fwd_from.from_id`: that is who wrote a forwarded message originally, not who sent this copy
     * into this chat.
     */
    get senderId(): number | null
    /**
     * forum topic this message belongs to, or `null` outside a forum. 1 is the "General" topic.
     *
     * read off the message's own reply header, which is the only thing [raw] carries about it — so
     * a message sitting in General with nothing to reply to carries no annotation and reads `null`
     * here too. to tell that apart from "not a forum", look the chat up and read `forum` off it.
     */
    get topicId(): number | null
    get date(): number
    get editDate(): number | null
    get out(): boolean
    /** raw `message` field; empty for service messages, and the caption on media messages */
    get text(): string
    get textWithEntities(): TextWithEntities
    /** `messageMediaEmpty` reads as `null`, it being how the wire spells "no media" */
    get media(): tl.TypeMessageMedia | null
    /** `null` unless [media] is a document, and `documentEmpty` counts as none */
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
    /**
     * `null` when this isn't a reply — **including** a message that merely sits in a forum topic.
     * telegram spends the same reply header on both: a message posted into a topic carries the
     * topic's root id and nothing beside it, while a real reply inside one carries what it replied
     * to here and the topic's root in the field [topicId] reads. so "in topic 5" never reads as
     * "replying to message 5".
     */
    get replyToMessageId(): number | null
    get forwardedFrom(): tl.TypeMessageFwdHeader | null
    get viaBotId(): number | null
    get isPinned(): boolean
    get views(): number | null
    get forwards(): number | null
    get reactions(): tl.TypeMessageReactions | null
    get isService(): boolean
    /**
     * true for a secret-chat message, which is either one of stock's own `message_secret*`
     * constructors or anything (a service message, say) whose non-wire `dialog_id` annotation
     * carries the app's encrypted-dialog marker bit. the `layer`/`seq_in`/`seq_out` annotations
     * are *not* what this reads: the app leaves them zero on plenty of secret messages.
     */
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
   * fires whenever the list this returns would differ: login, logout, account switch, and a
   * `isPremium` flipping. it hands you the new list, so there is nothing to call back for.
   *
   * @needs-grant account.read(self)
   */
  function onAccountsChanged(callback: (accounts: AccountInfo[]) => void): Disposer

  /**
   * run per-account setup, and re-run it whenever the selected account changes. the callback may
   * return a teardown function, which runs before the next invocation, when the disposer is called,
   * and once more on unload.
   *
   * with nobody logged in it simply doesn't run — there is no `Account` to hand over — and starts
   * on the first login. a slot re-used by a *different* login counts as a change, so keying
   * anything on `account.id` alone is a bug `userId` doesn't have.
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
   *
   * throws `not-found` when the slot isn't logged in — including the no-argument form, since a
   * plugin does run with the app sitting on the login screen and there is no account to pin to.
   * `withCurrentAccount` is the form that waits instead of throwing.
   */
  function account(id?: number): Account

  /**
   * an opaque paging cursor. hand it back verbatim; don't parse it, its shape is not a contract.
   *
   * branded by which list it came from, so a dialogs cursor can't be handed to `getTopics` — the
   * two encode different things and the mistake is otherwise invisible until it pages wrong. the
   * brand is a compile-time fiction, so the same mistake is refused again at runtime
   * (`invalid-argument`): the token names an entry in a table only this plugin's engine has, and
   * nothing about a page's position is ever in js to tamper with.
   *
   * that table holds **32 cursors at once**, and paging uses the newest, so the oldest is what the
   * bound drops: a plugin holding more half-read lists than that gets `invalid-argument` on the ones
   * it abandoned. page one list at a time, or keep the pages instead of the cursors. the same bound
   * is what ends an `iter*` whose steps are interleaved with that many other paged reads.
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
   * the synchronous getters answer *only* from what the app already has **in memory** — they return
   * `null` on a miss and never fetch or touch disk, which is the whole point of them (if you want
   * the network, that's `invokeRpc`). a miss and a nonexistent peer are indistinguishable, same as
   * in stock, and how much is in memory is the app's business rather than a contract: entities are
   * generously cached, messages are not (see `getMessage`).
   *
   * the `Promise`-returning ones may go to the network, and reject rather than throw whatever went
   * wrong, including a bad argument and a missing grant. they still *name* a peer out of the cache
   * though: asking about one takes an `access_hash`, and `resolvePeer` says why only a username can
   * be looked up. so an id with nothing cached behind it rejects `not-found` here as well, with the
   * same fix, resolve it once first. a `limit` on any of them is one page's worth: telegram takes
   * at most 100 rows at a time, which is what an omitted one asks for and what a larger one is
   * clamped to rather than refused.
   *
   * the handle is pinned, so the account behind it can be logged out while you hold it: every
   * member then throws `not-found` rather than answering as if the slot were merely empty.
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
   * the header. the one exception is `userId`, which *is* the user's identity: it reads behind the
   * same `account.read(self)` that gates `inu.accounts()`, or eight `inu.account(i)` calls would
   * rebuild the list that grant exists to protect. `id` and `isCurrent()` stay free, so the handle
   * is still usable as a scope by a plugin that never asked to know who you are.
   *
   * **which is why naming yourself in a *read* needs `account.read(self)` too**, on top of whatever
   * the read itself asks for: `'me'`/`'self'`, an `inputPeerSelf`, or your own `User` as an
   * `InputPeerLike` in any of the read members. `getUser('me').id` and `getDialog('me').peer` are the
   * same identity `userId` is, so leaving them free would have made that gate decoration. an id you
   * already hold stays behind its read's own scope — knowing *which* peer is you is the part being
   * gated, never the ids.
   *
   * **the writes are gated by `account.write` alone, including the ones that name you.**
   * `sendMessage('me', …)` is Saved Messages, not a lookup of who you are, and a message you sent
   * names you as its sender in whatever the send answers with — that is the server's reply to your
   * own request, on every path, not something the bridge could withhold on one of them without
   * making the two disagree. `OutgoingMessage.peer` resolves an `inputPeerSelf` for the same reason.
   * the gate would not have bought anything either: a plugin that may send can message the author's
   * own bot, so `account.write(send)` is identity disclosure by construction.
   *
   * every message read here has login codes redacted from its text (see the header). the messages
   * themselves are all present, and so is the service chat in `getDialogs` — an earlier design
   * dropped them outright, which meant a dialog whose `top_message` resolved to `null`.
   */
  interface Account {
    /** which slot this handle is pinned to */
    readonly id: number

    /**
     * the user id in this slot. a stable, globally joinable identifier, so unlike the rest of the
     * handle it is gated; reading it without the grant throws `not-granted`.
     *
     * @needs-grant account.read(self)
     */
    readonly userId: number
    /**
     * whether this is the account the user currently has selected. a method rather than a field
     * because the handle is pinned and the answer isn't — it flips under you on a switch. this is
     * the cheap filter for the account-tagged handlers (`inu.onNewMessage` and friends), which fire
     * for every logged-in account.
     */
    isCurrent(): boolean

    /**
     * the logged-in user. the one getter that does not depend on the entity cache, so it answers
     * `null` only when the slot has no login left.
     *
     * @needs-grant account.read(self)
     */
    getMe(): tl.TypeUser | null

    /**
     * `null` when the peer isn't cached *or* turns out not to be a user, which are the same answer
     * everywhere in this group — `getPeer` is the one that doesn't have to guess which it'll be.
     *
     * naming *yourself* works here, and takes `account.read(self)` as well: the `User` it hands
     * back carries your id (see the note on the interface).
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
     * a `Dialog` carries the chat's saved `draft`, which is `account.read(draft)`'s to hand over —
     * so without that scope the field reads as absent, exactly like an optional one whose flag bit
     * is clear. same for a `ForumTopic`'s, and for `updateDraftMessage`'s: the rule is on the field,
     * not on `getDraft`, or the scope would only gate the one api that happens to be named after it.
     *
     * @needs-grant account.read(dialogs)
     */
    getDialog(peer: InputPeerLike): tl.TypeDialog | null
    /**
     * **only what the app is holding in memory**, which for messages is the chat list's own — the
     * message a dialog row shows. everything else lives in sqlite and is `getHistory`'s to fetch,
     * this one never touching disk being the point of it being synchronous. so a `null` here means
     * "not in memory", not "no such message".
     *
     * @needs-grant account.read(messages)
     */
    getMessage(peer: InputPeerLike, messageId: number): Message | null

    /**
     * one bridge crossing for the whole batch; misses come back as `null` in place
     *
     * @needs-grant account.read(peers)
     */
    getUsers(peers: InputPeerLike[]): (tl.TypeUser | null)[]
    /** @needs-grant account.read(peers) */
    getChats(peers: InputPeerLike[]): (tl.TypeChat | null)[]
    /**
     * the same in-memory window `getMessage` reads
     *
     * @needs-grant account.read(messages)
     */
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
      onProgress?: ProgressCallback
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
      onProgress?: ProgressCallback
    }): Promise<{ path: string }>

    /**
     * upload a file and get the `InputFile` back, for the rpc methods that want one (setting a
     * profile photo, a chat avatar, a sticker) rather than going through `sendMedia`.
     *
     * `{ path }` needs `fs` (scoped-relative, or absolute with `unsafe.fs`) for the same reason
     * `sendMedia` does, and the relative form is unwired here the same way and for the same reason.
     * bytes and a `Blob` are exempt: neither can name a file the plugin didn't have, and both are
     * subject to the same 256 MB staging cap `sendMedia` states.
     *
     * `fileName` is what the server records; without one it is the `File`'s own name, or the
     * staged content's.
     *
     * @needs-grant account.write(send)
     */
    uploadFile(file: Blob | Uint8Array | { path: string }, options?: {
      fileName?: string
      onProgress?: ProgressCallback
    }): Promise<tl.TypeInputFile>

    /**
     * bio/`about`, `common_chats_count`, pinned message. fetches when not cached.
     *
     * asking about *yourself* is allowed under `account.read(self)` alone — it's the one peer you
     * already are, so a plugin that shows your own bio doesn't need the whole address book. that
     * means naming yourself as `'me'`/`self`: your own id is a peer like any other and reads behind
     * `peers`, since deciding it was you would mean resolving it first, which is the thing being
     * gated.
     *
     * @needs-grant account.read(peers)
     */
    getUserFull(peer: InputPeerLike): Promise<tl.TypeUserFull | null>
    /**
     * `participants_count` and `available_reactions` live here, not on the bare chat. a basic group
     * and a channel answer with different constructors, and asking about a user rejects
     * `invalid-argument` rather than `not-found`: no amount of resolving turns one into a chat.
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
     * pages for you; stops when the list is exhausted or `limit` is reached.
     *
     * the two counts mean different things and both are optional: `limit` is how many items you
     * want in total (omitted, it pages to the end), `batchSize` how many one page asks for
     * (omitted, **100**, which is telegram's own page). the last page is cut short rather than
     * overshooting `limit`.
     *
     * an `iter*` is exactly the member it pages, called in a loop: same grant, same normalization,
     * same materialization, and the items are the same objects `getDialogs` hands over. **nothing
     * runs until the first `next()`**, so a bad argument, a missing grant and an unresolvable peer
     * all reject *there* rather than throwing where the iterator was made — which is also what a
     * `for await` sees.
     *
     * it holds a `Cursor` between steps, so it inherits that type's bound: interleave 32 other
     * paged reads between two steps and the one it was holding is gone, which ends the iteration
     * with `invalid-argument`. `break` out whenever you like — there is nothing to release.
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
     * pages `getHistory` newest-first, from `offsetId` if you name one. same rules as `iterDialogs`
     * for `limit`/`batchSize` and for when things reject.
     *
     * this one holds no `Cursor`: history pages by message id, so what it carries between steps is
     * the oldest id it has seen. it stops on a page shorter than `batchSize`, and on one whose
     * oldest id is not below the offset it asked from — `offset_id` is exclusive, so a page that
     * repeats itself is a server with nothing left rather than more history.
     *
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
     * forum topics, most-recently-active first. rejects `invalid-argument` if the chat isn't a
     * forum, which is a different failure from `not-found`: forum-ness is read off the cached chat,
     * so it is known before anything is sent and no amount of resolving changes it.
     *
     * @needs-grant account.read(dialogs)
     */
    getTopics(peer: InputPeerLike, options?: {
      limit?: number
      cursor?: Cursor<'topics'>
    }): Promise<Paged<tl.TypeForumTopic, 'topics'>>
    /**
     * pages `getTopics`; same rules as `iterDialogs`, including the `Cursor` bound and the chat
     * having to be a forum, which is decided on the first `next()`.
     *
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
     * the cache answers first, so the common case costs neither a request nor a promise hop. what
     * it cannot answer, **only a username can**: an id with nothing cached behind it has no
     * `access_hash` anywhere on the device, and telegram hands those out attached to an entity
     * rather than on request. so an unknown id rejects `not-found` and an unknown username is
     * looked up — after which the cached half answers for it too.
     *
     * an `InputPeer` you already hold is handed straight back, and needs no grant: nothing is read
     * to produce it. an `InputUser`/`InputChannel` is not one, and resolves as any other peer does.
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
     * narrows to an `InputUser`; rejects `invalid-argument` if the peer turns out not to be a user.
     * that is a different failure from `not-found`, which is the peer not resolving at all — no
     * amount of looking up turns a channel into a user.
     *
     * @needs-grant account.read(peers)
     */
    resolveUser(peer: InputPeerLike): Promise<tl.TypeInputUser>
    /**
     * narrows to an `InputChannel`; rejects `invalid-argument` if the peer turns out not to be a
     * channel — a basic group is not one, and neither are you
     *
     * @needs-grant account.read(peers)
     */
    resolveChannel(peer: InputPeerLike): Promise<tl.TypeInputChannel>
    /**
     * at most **8 in flight**; a peer that is not found comes back as `null` in the position it was
     * asked about instead of failing the batch.
     *
     * this is `resolvePeer` in a scheduler, not a batch request: the cache answers first for each
     * one, so only the misses cost anything, and only a username can be one of those (an uncached id
     * is simply `null`). `null` means "there is no such peer" and nothing else — an element that
     * failed for any other reason rejects the whole call, since a `null` there could not be told
     * apart from an unknown peer. a missing grant and an argument that is not a peer at all fail it
     * too, both decided before anything is sent; an empty list is refused for a missing grant like
     * any other.
     *
     * @needs-grant account.read(peers)
     */
    resolvePeerMany(peers: InputPeerLike[]): Promise<(tl.TypeInputPeer | null)[]>

    // -- writes --
    // these take an `InputPeerLike` and resolve it internally, which is the whole reason they
    // exist: doing it by hand means reading `access_hash` off an entity and assembling an
    // `InputPeer` at every call site.
    //
    // **the request an api here sends does not re-enter the plugin interceptors.** a `sendMessage`
    // here does not run through `interceptSendMessage`, and nothing here runs through
    // `interceptRpc` — same rule `invokeRpc` states for itself. otherwise a plugin that rewrites
    // sends and a plugin that sends would be an infinite loop, and the two-plugin case would be
    // unauditable.
    //
    // the **file transfers** an api here causes are the exception, and are the app's own: an
    // upload's `upload.saveFilePart`/`saveBigFilePart` and a download's `upload.getFile` do reach
    // `interceptRpc`, for both this plugin and every other. they are not attributable to a plugin
    // even in principle: stock runs one transfer per file for every caller waiting on it, so a
    // download you asked for may be the one the ui started. a plugin holding
    // `interceptRpc(upload.saveFilePart)` already sees every byte the user uploads, so nothing here
    // widens what it can reach.
    //
    // **none of them reach secret chats**, which have no `DialogId` to name and are the last place
    // plugin code belongs. naming one rejects `forbidden` — not `not-found`, which is the peer that
    // might resolve later.
    //
    // a peer is resolved from the cache before anything is sent, so an id nothing is cached for
    // rejects `not-found` with no request made, exactly as the async reads do. and `topicId`
    // without a `replyToMessageId` replies to the topic's root message, which is what makes the
    // message land in the topic at all.

    /**
     * @needs-grant account.write(send)
     */
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
     * **the relative `{ path }` form is not wired up**: `inu.fs` has shipped and owns the scoped
     * directory, but this call is never handed it, so a relative path has nothing here to resolve
     * against and throws `unsupported` (after the `fs` grant check, so a plugin without the grant
     * still hears about that first). to send a file out of your own directory, read it and pass the
     * bytes (`inu.fs.read('out.png')`, subject to that call's own 16 MB ceiling). the absolute form
     * works today under `unsafe.fs`.
     *
     * a `Blob` or bytes are copied into the app's cache before they go up, because the uploader
     * takes a file; **one such copy is capped at 256 MB** and rejects `quota-exceeded` past it,
     * without reading a byte. already-uploaded TL values and `{ path }` are not copied at all.
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
      onProgress?: ProgressCallback
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
        onProgress?: ProgressCallback
      }): Promise<Message[]>

    /**
     * @needs-grant account.write(edit)
     */
    editMessage(peer: InputPeerLike, messageId: number, text: InputText, options?: {
      noWebpage?: boolean
    }): Promise<Message>

    /**
     * @needs-grant account.write(delete)
     */
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
     * synchronous, and cache-only like the getters above it: a draft is app state, never fetched.
     * only the text and its entities, which is exactly what `setDraft` takes back; the rest of the
     * app's draft (its reply header, when it was typed) is app state rather than something to show.
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
     * `inu.invokeRpc` is the same call without an account: it targets whichever account the plugin
     * started on, snapshotted at load, so a user switching accounts mid-session cannot silently
     * retarget it. naming the account is the whole point of this form.
     *
     * @needs-grant invokeRpc
     */
    invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>
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
     *
     * the encoders emit padded base64 and lowercase hex; the decoders take either padding and
     * either case, and throw `invalid-argument` on anything they can't read. handing one something
     * that isn't a `Uint8Array` at all is a plain `TypeError`, not a `PluginError`.
     */
    function toBase64(bytes: Uint8Array): string
    function fromBase64(base64: string): Uint8Array
    function toHex(bytes: Uint8Array): string
    function fromHex(hex: string): Uint8Array

    // todo: no hashing at all right now, so a plugin talking to an hmac-signed api has to ship a
    // js sha256 into a jit-less engine. a two-function `sha256`/`hmacSha256` here (the platform
    // already has both) is the fix if that ever comes up — a full webcrypto `subtle` is not.

    // -- formatting --
    // `Intl` is absent and these are what stands in for it: the shapes the app itself uses, so a
    // plugin's ui reads like the screen it's sitting on without shipping a formatting library into
    // a jit-less engine.
    //
    // **they do not read the app's langpack.** the intent was to delegate to the app's own
    // formatter; there is no bridge to it, so what runs is the engine's own arithmetic — english
    // month and weekday names, a 24-hour clock, `.` for the decimal point and a space between
    // groups, whatever `inu.info().language` says. only the *device's timezone* is honoured,
    // `Date` being what these are built on. that is the one place a plugin's ui will not match its
    // surroundings; if it matters, format it yourself off `inu.info().language`.
    //
    // all of them are for display and none of them is for parsing: delegating later would change
    // exactly the output this paragraph is apologising for.
    //
    // anything they cannot format (a non-number, an unknown style) is `invalid-argument`.

    /**
     * `unix` is seconds, matching every date field on a TL object. defaults to `dateTime`.
     * - `date` — `12 May 2024`
     * - `time` — `19:04`
     * - `dateTime` — both, comma-separated
     * - `relative` — what a dialog row shows: a time today, a weekday this week, a date before that
     */
    function formatDate(unix: number, style?: 'date' | 'time' | 'dateTime' | 'relative'): string
    /**
     * grouped (`1 234 567`), or `compact` for the app's short form (`1.2M`). compact truncates
     * rather than rounds, so a count never reads as one it hasn't reached: 1999 is `1.9K`.
     */
    function formatNumber(value: number, options?: { compact?: boolean }): string
    /** `4.2 MB`, in the app's units — binary multiples, one decimal past `1024 B`, `GB` at the top */
    function formatFileSize(bytes: number): string
    /** `3:07`, `1:02:44` — the form the app uses on media */
    function formatDuration(seconds: number): string

    /**
     * pure id arithmetic. these take a `DialogId` (or its decimal-string form, since that's how
     * int64s arrive on a TL snapshot) rather than an `InputPeerLike` — a username isn't a number
     * and there is nothing to compute from one, so accepting it would only defer the failure.
     *
     * anything that doesn't name a peer they can compute from is `invalid-argument`, `inputPeerSelf`
     * and `inputPeerEmpty` included: neither carries an id, and answering `0` for them would be a
     * wrong answer rather than an error.
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
       *
       * your own `User` gives `inputPeerSelf`, and a `userEmpty`/`chatEmpty` placeholder is
       * `invalid-argument`: it carries no hash, so there is nothing to address.
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
   *
   * a `UIIcon` is a *description* of an icon, not a loaded one: it is resolved to a real drawable
   * by whatever draws it, at the moment it draws it. so an icon costs nothing to keep, follows a
   * theme change, an icon-pack change and a rotation without being rebuilt, and cannot go stale.
   * all three are checked when you make one: an icon that resolves to nothing throws here rather
   * than rendering as a hole later.
   *
   * every surface that takes one says so with an `icon` field. a settings-page `check` deliberately
   * has none: the app's switch row has nowhere to put an icon, and a field that silently drops one
   * is worse than not having the field.
   */
  namespace icons {
    /**
     * a small curated set, resolved to whatever the host platform's real asset is, so these
     * follow the app's icon pack and theming. the list grows on demand; unknown names throw
     * `invalid-argument` rather than silently rendering nothing, so a name added in a later api
     * level needs the matching `@plugin-api`.
     *
     * a name in the set that this build ships no asset for throws `not-found`. that is a bug in
     * the app rather than in your plugin, and there is nothing to do about it from here: the
     * bundled `icons test` plugin asks for every one of them on startup so it is caught here
     * rather than in yours.
     */
    function common(
      name:
        | 'settings' | 'info' | 'search' | 'edit' | 'delete' | 'copy' | 'share' | 'download'
        | 'link' | 'pin' | 'star' | 'mute' | 'unmute' | 'archive' | 'forward' | 'reply'
        | 'user' | 'group' | 'channel' | 'bot' | 'lock' | 'eye' | 'eyeOff' | 'refresh'
        | 'plus' | 'minus' | 'check' | 'close' | 'more' | 'translate' | 'bookmark',
    ): UIIcon
    /**
     * inline svg source. scaled down to the icon size the surface uses and tinted to match its
     * context: every paint becomes the row's own colour, so a multi-coloured source arrives
     * monochrome and only its shape survives. write one path, leave `fill` alone.
     *
     * the host parses it once, when you call this, and a source it cannot read is
     * `invalid-argument`, including one carrying no `<svg>` element at all. it is parsed by the
     * platform's own xml reader, so **a document type declaration is refused outright**
     * (`invalid-argument`): a `<!DOCTYPE>` is the one thing in xml that can name an external file
     * or expand to more of itself, and no icon needs one. `<!-- comments -->` are fine.
     *
     * **at most 64 KiB of source**, `quota-exceeded` past that, refused before it is read. parsing
     * is one host call and the per-callback ceiling on synchronous js cannot interrupt one, so the
     * bound is a size rather than a deadline, the same reason `Blob` construction carries one. an
     * icon is a glyph; if you are near this, you are shipping an illustration.
     */
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
       * rendered anymore (an already-open view freezes, and any `UIAnchor` it handed out throws
       * `handle-expired`); calling again is a no-op. needed because pages are never
       * garbage-collected — if you create pages dynamically (e.g. a per-item detail page from a
       * factory function), dispose them or mark them `transient`, or every creation leaks until
       * the plugin unloads.
       *
       * your plugin being unloaded or disabled does this for you, and closes any view of the page
       * that was on screen: a page whose rows no longer do anything is worse than no page.
       */
      dispose(): void
    }

    /**
     * push a page onto the current navigation stack.
     * (will later also accept app fragments via `inu.jvm` — same verb for both.)
     *
     * a no-op when there is no navigation stack to push onto, which is a real state rather than an
     * edge case: a process a push notification woke has no ui at all. it does not throw and does
     * not queue the page for the next time one appears.
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
       * options can't express. on android, `inu.android.nativeView` turns a real View into one,
       * and that is the **only** element a body may be: the options cross as JSON, so a
       * declarative element's callbacks would not survive it, and anything else throws
       * `unsupported` rather than going missing.
       */
      body?: UIElement
      positive?: string
      negative?: string
      neutral?: string
    }): Promise<'positive' | 'negative' | 'neutral' | 'dismissed'>

    /**
     * modal list picker. resolves to the chosen index (or indices when `multiple`), or `null` if
     * dismissed — which is also what you get when there is no UI to show it in. for a picker that
     * lives *in* a settings page rather than over it, use `select`.
     *
     * `items` must not be empty and every `selected` index has to name one of them; both are
     * argued before the dialog is shown, so a bad picker throws where you wrote it rather than
     * resolving to something you didn't mean. a row is text and subtitle only, with no icons: the
     * modal is stock's own list dialog, and the place a row carries an icon is a settings page.
     */
    function chooser(options: {
      title?: string
      items: (string | { text: string, subtitle?: string, danger?: boolean })[]
      selected?: number
      multiple?: false
    }): Promise<number | null>
    function chooser(options: {
      title?: string
      items: (string | { text: string, subtitle?: string, danger?: boolean })[]
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
      /**
       * set for `chat` and `profile`. absent for a secret chat, which has no `DialogId` to name
       * here any more than it does anywhere else — `type` still says a chat is open.
       *
       * @needs-grant account.read(dialogs)
       */
      dialogId?: DialogId
      /** set for `chat` in a forum. @needs-grant account.read(dialogs) */
      topicId?: number
      /** the account being viewed */
      account: Account
    }

    /**
     * what the user is looking at right now. `null` when nothing is on screen (app in background,
     * or too early during startup).
     *
     * a synchronous getter, so it answers from what the app last published or not at all: there is
     * no waiting for a screen to appear. `null` is a real answer and not an error — a process a
     * push notification woke has no ui and never will.
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
     * the anchor is the last argument of every settings-page item callback (`onClick`, `onChange`,
     * `onSecondaryClick`, and the `bottomButton`'s `onClick`), so opening a menu is
     * `(_, row) => row.openMenu([...])`.
     *
     * **an anchor names a row, not a moment.** it stays usable after an `await` — which is exactly
     * where you want it, and where the free function this replaces could not work, since the
     * automatic re-render that follows every callback has already thrown that render's state away
     * by then. which row it names is the element's `id`, or its type plus its text when you didn't
     * give one; a list that reshapes itself between renders is the case `id` exists for.
     *
     * two ways it stops working, and they answer differently. once the page is `dispose()`d the
     * anchor is dead and `openMenu` throws `handle-expired`. while the page lives but its row is
     * not on screen — scrolled out of view, or gone from the latest render — there is nothing to
     * anchor to, so the menu simply doesn't open and none of its `onClick`s ever fires.
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
    // re-renders) and what a `UIAnchor` names. it defaults to the element's type plus its text,
    // which is stable for static-ish lists — set it explicitly only when the list reshapes
    // dynamically (rows get renamed, reordered, or several rows swap texts)

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
      /** leading icon, from `inu.icons` */
      icon?: UIIcon
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
      /** leading icon, from `inu.icons` */
      icon?: UIIcon
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
     *
     * which is also the one limit here: **a slider with a `label` may have at most 501 steps**, and
     * past that it throws `invalid-argument` naming the count it worked out. drop the `label` (the
     * slider then shows bare numbers, which is what the fallback would have been) or coarsen `step`.
     * without a `label` the range is unbounded.
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
   * invoke an rpc request on the connection of the account the plugin started on.
   *
   * the shipped, account-less half of `Account.invokeRpc`; everything that member's doc says holds
   * here. `interceptRpc` is bypassed (so calling this from inside your own middleware doesn't
   * loop), the resolved value is a **mutable** lazy view over a response nobody else holds, a
   * server-side failure rejects with an `inu.RpcError`, and `null` means the app completed the
   * request with neither a response nor an error.
   *
   * a request the app's layer doesn't have throws `unknown-constructor`, and one on the takeover
   * list throws `forbidden` (see the header). the account is fixed for the plugin's life; picking
   * one is what the `Account` member is for.
   *
   * @needs-grant invokeRpc
   */
  function invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>

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
   * every live stage is abandoned and the request usually fails with an `inu.RpcError` saying the
   * interceptor timed out; a late `next()` from an abandoned stage throws. the budget is wall time
   * over the whole chain, and separate from the per-callback ceiling on synchronous js described in
   * the header: a stage that spins rather than awaits is stopped by that one first, and fails the
   * request with the interrupt. do slow work outside the chain and
   * cache the answer, and note that a plugin low in the list only gets whatever the ones above it
   * left unspent.
   *
   * concretely, on expiry: the app's request and every parked stage's `next()` fail with an
   * `inu.RpcError` whose `code` is `-1000` and whose `text` is `INTERCEPTOR_TIMEOUT`, and a `next()`
   * called afterwards throws an `inu.PluginError` with code `'timed-out'`. the exception is a chain
   * that expires after the real request already answered: there the app gets that answer instead of
   * the timeout (see the header), while the stages are torn down as usual. time spent waiting on the
   * real request does not count against the budget.
   *
   * a chain torn down for any *other* reason rejects the parked `next()`s with `-1000` and
   * `INTERCEPTOR_ABANDONED`, and a `next()` called afterwards throws `'aborted'` rather than
   * `'timed-out'`. the codes are worth branching on, since one means "you were too slow" and the
   * other means "something else ended this". stopping, reloading or uninstalling a plugin drops its
   * own stage and every stage below it; the stage *above* sees its `next()` reject with `-1000` and
   * the text `plugin '<name>' was stopped`, and when the stopped plugin held the first stage that is
   * what the app's request fails with, including in the window where the request had already gone
   * out, so the server may well have executed it.
   *
   * a `next()` called after its own stage already settled throws `'invalid-argument'`, and calling
   * it twice throws a `TypeError`.
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
   * literal instead is also fine and replaces the request wholesale, but it must still be the
   * method you intercepted: swapping `_` throws `forbidden` at runtime, as it would turn one
   * `interceptRpc` scope into a send primitive for everything else.
   *
   * **don't read `request` back after `next()` resolves.** it is the app's own object, and the app
   * releases what it serialized the moment the request goes out, so a field backed by a byte buffer
   * (`upload.saveFilePart`'s `bytes`, say) reads back as `null` afterwards. read what you need on
   * the way down, before you hand it on.
   *
   * a request the app cancels while your chain is still walking **is cancelled**: the chain
   * collapses where it stands, every stage still parked in `await next()` is abandoned, and nothing
   * is sent. the app is not answered (a cancelled request has no delegate to answer), but its
   * `onCancelled` still runs, from this side while nothing has gone out yet and from the network
   * layer once the passthrough has handed the request over. so a cancel does stop your middleware,
   * and an `await` after `next()` resolving is not somewhere you can assume you will be resumed.
   *
   * `next()` rejects with an `inu.RpcError` when the request fails server-side; rethrowing it (or
   * throwing your own) fails the intercepted request with that error — see `RpcError`'s doc.
   * `next()` resolves to `null` when the app completed the request with neither a response nor an
   * error (some cancellation paths do this); returning that `null` (or returning `undefined` after
   * awaiting it) passes the empty completion through unchanged.
   *
   * the takeover methods listed in the header can't be intercepted: a grant that *names* one fails
   * at install, and with an unscoped grant the registration itself throws `forbidden`, since an
   * unscoped grant satisfies every scope check and so cannot be caught at install. either way the
   * app's own calls to them never reach a chain. login-code redaction applies to
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
   * **four constructors are the exception, and nothing here reaches them**: `users.users`,
   * `users.usersSlice`, `messages.foundStickers` and `messages.foundStickersNotModified` are parsed
   * through a second helper (`TLRPC.deserialize`) in generated app code that the hook does not sit
   * on and that the fork does not patch. a rule naming one of them registers and then never fires,
   * which is the direction that is safe to be wrong in: it does nothing rather than something else.
   * the objects *inside* those four are parsed the ordinary way, so a rule on `user` still reaches
   * every user in a `users.users`.
   *
   * the declarative form exists because this runs on the hot path — thousands of objects during a
   * cold start. rules are matched natively with no js crossing at all; the middleware form pays a
   * js call per object and should be a last resort.
   *
   * see the execution-model note in the header for where the middleware form actually runs: on the
   * app's own deserializing thread, with the synchronous apis unavailable inside it.
   *
   * **what a rule changes is written to disk, and uninstalling the plugin does not undo it.** the
   * app re-serializes its own live objects back into the local database, so the first save after a
   * rule fires puts the rewrite in the `users`/`chats`/`messages`/`dialogs` rows — and for a row the
   * server never re-sends, it stays there. nothing short of clearing the app's local database (or
   * logging out and back in) takes it out again. this is the one api here whose mistakes a restart
   * does not clear, so what a rule may rewrite is narrower than what it can name:
   *
   * - **`id`, anything ending in `_id`, `access_hash`, `dc_id` and `file_reference` cannot be
   *   `set`** (`forbidden`). those address the object rather than describe it, a wrong one is
   *   perfectly well-formed so the app never notices, and a refetch writes to the row the wrong id
   *   names instead of repairing it. `unsafe.disableApiFiltering` does not lift this: it is not a
   *   filter.
   * - **flag words (`flags`, `flags2`) cannot be named at all** (`forbidden`). the bridge owns them
   *   here as everywhere else — set the optional field and its bit follows.
   * - **secret-chat constructors are not rule targets** (`forbidden`), and their ids are stripped
   *   out of the families that carry them, so a rule on `message` reaches every message except a
   *   secret one. same rule the `Account` reads and `onUpdate` keep.
   * - **the takeover surface is not a rule target** either: `auth.*`, and the constructors whose
   *   fields the api filter hides. lifted by `unsafe.disableApiFiltering`, like the rest of it.
   * - **a `when` may only name a field you could read anyway.** matching on a value is reading it —
   *   a rule that fires on a guess tells you the guess was right — so a field whose *value* the
   *   filter redacts for you is `forbidden` in a `when`, though it is fine in a `set`. a field the
   *   filter hides outright reads as absent here exactly as it does everywhere else
   *   (`invalid-argument`, in a `when` and in a `set` alike): a `forbidden` would confirm it exists,
   *   which is the thing being withheld.
   * - naming an rpc method is `invalid-argument`: the app serializes those and never parses one, so
   *   the rule could only ever do nothing.
   *
   * a value has to fit the field it lands in — a boolean for a boolean, a whole number for an int,
   * a string (or a number) for a `long` — and a field that holds a nested object, a vector or a byte
   * string can hold no constant at all, so it is `invalid-argument`. `null` clears a **string** the
   * wire marks optional, and nothing else: on a field the wire marks required it is refused, because
   * the app writes what a rule leaves behind back into its own database and stock does not guard a
   * shape the server cannot send; on an optional number or boolean write `0`/`false`, which clears
   * the bit the same way. clearing a nested object, a vector or a byte string is a structural
   * rewrite rather than a constant, so there is no way to ask for one.
   *
   * **at most 32 rules live at once**, counted across every registration this plugin holds; past
   * that the whole call is `quota-exceeded` and nothing of it registers. a rule set registers whole
   * or not at all, so a refusal anywhere in it leaves nothing behind.
   *
   * @needs-grant interceptDeserialize
   */
  function interceptDeserialize(rules: {
    /**
     * constructor names to match. every one of them is checked against the grant's scope list, so
     * `interceptDeserialize(user)` accepts a rule on `user` and refuses one that also names
     * `message`.
     */
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
   * **rewrite in place; the return value is ignored.** the app keeps the object it parsed, so there
   * is nothing to substitute it with, and the same `DeserializeGuards` the rule form states apply:
   * `id`, any `*_id`, `access_hash`, `dc_id` and `file_reference` are `forbidden` to assign, and
   * what you can read is what the api filter lets you read anywhere else.
   *
   * the guard is on the slot the rewrite lands in, not on the name you wrote, so it holds **at any
   * depth of the value** too: `m.peer_id = { _: 'peerUser', user_id: '7' }` is the same rewrite as
   * `m.peer_id.user_id = '7'` and is `forbidden` the same way, and so is an object carrying one
   * several levels down, and so is a vector element. for the same reason **another live TL object
   * cannot be assigned here at all** (`forbidden`): it carries the addressing fields of wherever it
   * was parsed, so splicing one in is that rewrite by reference. build the replacement as a plain
   * object — structural rewrites are what this form is for, it is only the addressing that is out.
   *
   * **the app is blocked while your middleware runs**, on its own network or storage thread, and it
   * gives up after 250ms — past that the object is delivered as parsed. so this must be synchronous
   * and cheap: an `await` in here settles long after the object has been handed on, and the view is
   * dead by then. it is also skipped entirely for an object parsed by your own code (an
   * `unsafe.jvm` call that reaches a stock parser), because waiting there would be waiting on
   * yourself.
   *
   * each constructor named counts as one rule against the 32 the rule form counts, for the same
   * reason: it is one more id every parsed object is tested against.
   *
   * @needs-grant interceptDeserialize
   */
  function interceptDeserialize(objects: string[], middleware: (object: TLObject) => void): Disposer

  // -- events --
  // all of these fire across every logged-in account and hand you the `Account` the event arrived
  // on, rather than living *on* an account: a handler is a plugin-wide thing, and hanging one off a
  // pinned handle meant registering N times and re-registering on every login. filter with
  // `account.isCurrent()` or `account.userId` if you only care about one.
  //
  // the demuxed ones exist for the same reason `interceptSendMessage` does: one callback covers
  // what the raw stream spreads across `updateNewMessage`/`updateNewChannelMessage` and the
  // difference catch-up path. writing that fan-out by hand is most of the boilerplate in a
  // typical plugin. (the compressed `updateShortMessage`/`updateShortChatMessage` wire forms are
  // rebuilt into `updateNewMessage` before dispatch, and `updateShortSentMessage` - a send ack
  // with no message body - is not delivered at all, so `onUpdate` only ever sees real `Update`
  // constructors.)
  //
  // login codes are redacted from message text before any of these fire, and
  // `updateServiceNotification` is not delivered at all (see the header). the messages themselves
  // still arrive, so a plugin that watches the service chat sees it happen, just not the code.
  //
  // a demuxed event is a *narrowing* of `onUpdate` and nothing else: same registry, same disposer
  // rules, same read-only payload, same once-per-arrival guarantee. so registering both forms over
  // one constructor gets you each callback once per message, not one of them twice.
  //
  // **the two halves of the `onUpdate` grant vocabulary do not imply each other.**
  // `onUpdate(new_message)` buys `onNewMessage` and no raw stream at all;
  // `onUpdate(updateNewMessage)` buys the raw stream and not `onNewMessage`. name both if you want
  // both. (an unscoped `onUpdate` covers everything, as unscoped grants always do.) they are
  // separate because they are separate powers to hand over: the demuxed events are a message
  // arriving, the constructor list is whatever the app's update pipeline is carrying that turn.

  /**
   * a message arrived in a dialog, on any account — `updateNewMessage` and
   * `updateNewChannelMessage`, plus the compressed `updateShort*` forms once they've been rebuilt,
   * plus everything a difference catch-up recovered after the app was offline. your own outgoing
   * messages arrive here too (`message.out`).
   *
   * deliberately *not* included, none of them being a message arriving in a dialog: scheduled
   * messages (not sent yet), quick replies (a template), business/ephemeral messages (another
   * inbox), and secret chats, which plugin code never reaches at all.
   *
   * @needs-grant onUpdate(new_message)
   */
  function onNewMessage(callback: (message: Message, account: Account) => void): Disposer
  /**
   * a message was edited — `updateEditMessage`/`updateEditChannelMessage`. `message` is the whole
   * new message, not a diff; `message.editDate` is when.
   *
   * @needs-grant onUpdate(edit_message)
   */
  function onMessageEdited(callback: (message: Message, account: Account) => void): Disposer
  /**
   * ids only — the messages are gone by the time this fires, so there is nothing to hand over.
   *
   * **`dialogId` is `null` for a deletion outside a channel**, which is most of them: a 1:1 or
   * basic-group `updateDeleteMessages` carries message ids and no peer whatsoever. ids are unique
   * per account in that space, so the app resolves the dialog by looking them up in its own message
   * database — an async query this dispatch has nothing to wait on, and one that answers nothing at
   * all for a message the app never stored. a channel deletion names its channel and always has a
   * `dialogId`.
   *
   * @needs-grant onUpdate(delete_message)
   */
  function onMessageDeleted(
    callback: (dialogId: DialogId | null, messageIds: number[], account: Account) => void,
  ): Disposer

  /**
   * the raw update stream. `account` says which account it arrived on.
   *
   * called for every update of the named types, on every logged-in account, **once each**. the app
   * parks a batch whose pts it can't apply yet and re-feeds it to the same dispatch point once the
   * hole closes, sometimes repacked around the very same update objects; that is bookkeeping, not a
   * second arrival, and it is filtered out before you see it. so an `onUpdate` handler can count.
   *
   * that holds **across every arrival path**, and there are three: the live update stream, the
   * compressed `updateShort*` forms, and the difference catch-up the app runs after a reconnect or
   * a missed pts (`updates.getDifference`/`getChannelDifference`), whose bare messages are handed
   * over as the `updateNewMessage`/`updateNewChannelMessage` the server would have sent had the
   * client been online. a reconnect that re-reports what the live path already delivered is one
   * arrival, not two. the one thing none of them carries is a secret chat, which never reaches
   * plugin code at all.
   *
   * **the constructor list is required**, and narrows `update` to it the way `interceptRpc` does.
   * there used to be an unscoped form; it was a bridge crossing per update to discard almost all
   * of them in the callback's first line, on a path that delivers hundreds at once during
   * difference catch-up. name the constructors and the filtering happens natively, before anything
   * is materialized. it's also the only form that can narrow the type, so the scoped one was
   * already what you wanted. the grant still bounds what you may name.
   *
   * `update` is **read-only**, and the reason is ownership rather than timing: it is a view onto
   * the app's own object, the same instance the app's update pipeline works from and the same one
   * every other plugin listening for that constructor is handed. a write would not "fail to change
   * what the app does", it would reach into state the app is mid-way through consuming, and into
   * another plugin's argument. to actually change one, that's `interceptUpdate`.
   *
   * on timing: you are called after the app has *seen* that batch, and usually after it has applied
   * it, in which case the fields it backfills (`dialog_id`, the peer omitted in a 1:1 dialog, ...)
   * are populated. the exception is a batch parked behind a pts/seq hole: the app queues it to apply
   * once the gap closes, and you are handed it on the pass that queued it, so those fields may still
   * be empty. you are not called again when it is finally applied. treat backfilled fields as a
   * convenience, never as a guarantee, and don't read any of this as a barrier for anything.
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
   * **the constructor list is required**, as it is for `onUpdate`. this runs before the app has
   * processed anything, on a path that sees hundreds of updates at once during difference catch-up,
   * so the filtering has to happen natively rather than as the first line of your callback. there
   * is no legitimate "intercept everything" case; register the constructors you actually rewrite.
   *
   * dropping is a blunt instrument: the app never learns the update happened, but the *server*
   * believes it was delivered, so dropping something that carries a pts/seq advance desyncs the
   * client until the next full catch-up. prefer rewriting. it is also why **every failure delivers**
   * — a middleware that throws, rejects or returns anything other than the two verdicts is a bug
   * that switches the plugin off, and the update goes through untouched. losing the user's messages
   * because a plugin has a typo in it is not a trade worth making.
   *
   * `update` is writable all the way down, and that includes `pts`/`pts_count`. nothing stops you,
   * because they are ordinary fields on the app's own object, but they are the app's synchronisation
   * state and not payload: a value the app cannot line up against what it already has makes it park
   * the update and run a catch-up, which arrives, is intercepted, and is rewritten again. a plugin
   * that rewrites pts on every update it sees is an unbounded `getDifference` loop, not a desync
   * that heals. rewrite what the update *says*, leave the numbers it arrived with alone.
   *
   * **the chain has 2 seconds**, shared across plugins — a tenth of what a send gets, because
   * updates arrive in bursts and the whole burst is waiting behind you. one budget covers the whole
   * *arriving batch*, not each update in it: the app hands its updates over in batches and is
   * blocked on the batch, so a per-update budget would let one arrival hold the stream for its size
   * times the budget. when it runs out, everything not yet decided is delivered (never dropped) and
   * the batches queued behind it start moving.
   *
   * that queue is per account and is the other thing to know: while a batch is being rewritten, the
   * ones arriving behind it wait, whether or not anything claims them. the app applies updates in
   * arrival order and a plugin must not be able to reorder them.
   *
   * two arrival paths differ from `onUpdate`'s. the difference catch-up
   * (`updates.getDifference`) is **observation only** — it is applied by its own code path, not
   * through the one this hooks — so a plugin that must rewrite everything cannot rely on this alone.
   * and the compressed `updateShortMessage`/`updateShortChatMessage` forms are handed over as the
   * `updateNewMessage` they normalize to, exactly as for `onUpdate`; if you rewrite one, the app is
   * handed the `updates` batch the server would have sent instead of the compressed form, since the
   * compressed form has nowhere to carry your rewrite.
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
  //
  // a menu is built on the app's ui thread and your code cannot run there, so a row is drawn one
  // hop behind: the app reserves as many rows as you registered, asks every plugin for its labels,
  // and fills them in. what that costs you is a bound — a menu that is opened by the same gesture
  // that builds it (the message menu) waits ~150ms for every plugin's `text`/`visible` together and
  // then draws without the ones that did not answer. so keep them cheap and synchronous; anything
  // that has to await belongs in `callback`.
  //
  // `visible`/`text` throwing drops that one row and is logged, not a fault: a predicate that fails
  // on one chat must not switch off every other feature you provide. `callback` throwing is a fault
  // and switches your plugin off, like any other callback: the user asked for that row.
  //
  // rows come out in the order plugins are listed in settings, and each plugin's own rows in the
  // order it registered them. at most 8 rows per menu per plugin; past that `register*Action`
  // throws `quota-exceeded`, naming the row that lost. re-registering an `id` you already hold is a
  // *replacement* and never counts against that, so a full menu can still be updated. the new row
  // keeps the position the old one had.
  //
  // a row is tied to the engine that drew it. disable, uninstall or reload a plugin while one of
  // its rows is on screen and that row does nothing when tapped, rather than reaching whatever
  // replaced it.
  //
  // `icon` is accepted by the type but not by the runtime yet: passing one throws `unsupported`.

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
    /** not drawn yet: passing one throws `unsupported` */
    icon?: UIIcon | ((ctx: Ctx) => UIIcon)
    /** omit to always show. evaluated on every menu build */
    visible?: (ctx: Ctx) => boolean
    callback: (ctx: Ctx) => void
  }

  /** register a global action (a row at the bottom of the drawer menu) */
  function registerAction(options: ActionOptions<ActionContext>): Disposer

  /** register a chat-level action (in the triple-dot menu of the chat) */
  function registerChatAction(options: ActionOptions<ChatActionContext>): Disposer

  /** register a message-level action (in the message context menu) */
  function registerMessageAction(options: ActionOptions<MessageActionContext>): Disposer

  /** register a profile-level action (in the triple-dot menu of a profile) */
  function registerProfileAction(options: ActionOptions<ChatActionContext>): Disposer

  /**
   * register a message editor action (in the menu behind a long press on the send button)
   *
   * this menu is built by the gesture that opens it, so the sheet waits for your `text`/`visible`
   * to come back before it is shown, and gives up on them after ~150ms - a slow render loses its
   * rows for that one long press rather than delaying the sheet.
   *
   * `ctx.draft` is what is in the composer at the moment of the long press, and **reading it needs
   * `account.read(draft)`** — it is the same app state `getDraft` hands over, so it costs the same
   * scope, and without the grant reading it throws `not-granted`. `replace` writes back into it and
   * `send` writes back and then sends, both through the composer's own path, so what goes out is
   * what the send button would have sent; neither is gated on the read scope, so a row that only
   * sends something needs no grant at all. both are no-ops once the sheet is gone.
   */
  function registerMessageEditorAction(options: ActionOptions<MessageEditorActionContext>): Disposer

  /**
   * an outgoing message, normalized. the point of this shape is that one callback covers what the
   * raw layer spreads across `messages.sendMessage`/`sendMedia`/`sendMultiMedia`/`editMessage`
   * and their scheduled variants — writing that fan-out by hand is what makes the equivalent
   * `interceptRpc` approach miserable.
   *
   * mutate in place to change what gets sent. every member is read straight off the request, so
   * what you write is what goes out — and a member the request has no room for throws `unsupported`
   * rather than silently doing nothing: `silent`, `replyToMessageId` and `topicId` on an edit, and
   * any `media` assignment that would change the *number* of attachments.
   *
   * that last one is the one limit worth stating up front. attaching media to a text send, or
   * stripping it from a media send, means sending a different rpc method than the one the app is
   * already awaiting a response for, which is refused for the same reason `interceptRpc`'s `next()`
   * refuses it. replacing one attachment with another is fine. to actually change the shape of what
   * is sent, return `'drop'` and send your own with `account.sendMessage`/`sendMedia` — those don't
   * re-enter the interceptors, so it does not loop.
   */
  interface OutgoingMessage {
    /**
     * retargeting resolves the new peer through the account's own cache, so it needs
     * `account.read(peers)` on top of this api's grant, and throws `not-found` for a peer the app
     * has never seen. reading it needs nothing.
     */
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
   * it is the same chain, in fact: this is a narrowing of `interceptRpc` over the four send
   * methods, which is where the budget, the cancel handling and the "your own sends don't re-enter"
   * rule come from. so a plugin holding both forms over `messages.sendMessage` sees them
   * interleaved in plugin-list order like any two stages. the *grants* stay apart in both
   * directions, as they do for the demuxed events: `interceptSendMessage` does not buy
   * `interceptRpc(messages.sendMessage)`, and holding that one does not buy this.
   *
   * returning anything other than the two verdicts is treated as a throw — it drops the send and
   * switches the plugin off. that is the fail-safe direction here, the opposite of `interceptUpdate`:
   * a send the user can see failed and retry is recoverable, one silently sent by a broken
   * middleware is not.
   *
   * @needs-grant interceptSendMessage
   */
  function interceptSendMessage(
    middleware: (message: OutgoingMessage, account: Account) => MaybePromise<'send' | 'drop'>,
  ): Disposer

  // todo: think about how we would write a plugin that suppreses typing if draft starts with dot
  // (that's a different hook — typing notifications aren't sends)
}
