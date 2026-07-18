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
- @plugin-api (version of the plugin api - do we want to support multiple versions?)
@grant-s can be `none` (default) or a comma-separated list of permissions.
permissions are roughly split into "normal" and "dangerous". the latter are apis that can't
be sandboxed properly.
- @platform (android only for now)

permissions are named after the api they grant access to:
- `inu.kv`
- `inu.onAppVisibilityChange`
- `inu.clipboard.read` - dangerous, `inu.clipboard.write` - warning
- `inu.interceptRpc` - dangerous. safer variants:
  - `inu.interceptRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can intercept
- `inu.interceptDeserialize` - same shit
- `inu.invokeRpc` - dangerous. safer variants:
  - `inu.invokeRpc(users.getUsers,channels.getChannels)` - explicit list of methods that the plugin can invoke
- `inu.onUpdate` - dangerous. safer variants:
  - `inu.onUpdate(updateEditChannelMessage,updateEditMessage,updateMessageContent)` - explicit list of updates that are visible to the plugin
- `inu.jvm.cls` - dangerous. safer variants:
  - `inu.jvm.cls(java.lang.Object,java.util.*)` - explicit list of classes or namespaces that the plugin can access (note: `inu.jvm.cls(*)` is still dangerous)
- `fetch` - mildly dangerous. safer variants:
  - `fetch(google.com,bing.com)` - explicit list of domains (+ subdomains) that the plugin can access
- `inu.android.addNotificationCenterDelegate`, same shit
- `inu.fs` - dangerous --- need scoped variant very much
= `window.open` - opens a url in the browser

trying to call a method not defined in the grants will throw an error.

standard globals available:
- window.open() - opens a url in the browser
- console object
- `fetch` API

note: `eval` and `new Function` API is NOT available
*/

declare interface OpaqueType<Brand> { __opaque__: Brand }
/**
 * a TL object as `{ _: 'namespace.method', ...fields }` (e.g. `{ _: 'messages.sendMessage', peer, message }`).
 *
 * `interceptRpc`'s `request` and `next()`'s argument/return value are NOT plain objects — they're
 * live views over the real app-side object. reading/writing a field reflects onto (mutates) that
 * real object directly, there is no snapshot/copy involved. nested TL objects and vectors are
 * views too, minted lazily on first access. live views exist ONLY inside an `interceptRpc`
 * dispatch — everything else (`invokeRpc` results, `onUpdate` payloads, `.toJSON()` output) is
 * plain detached data.
 *
 * caveats:
 * - `long`/int64 fields are strings (JS numbers can't hold full int64 precision), e.g. `peer.userId: "123456789"`
 * - byte-array fields are `Uint8Array` copies (in live views AND in detached snapshots): reading
 *   gives you a snapshot, writing replaces the underlying bytes wholesale (no partial/in-place
 *   mutation). they `JSON.stringify` as `{"$inuBytes": "<base64>"}` wrappers, which round-trip
 *   back into byte fields wherever a `TLObject` is accepted. note that your own `JSON.parse` of
 *   stored snapshot JSON (e.g. from `inu.kv`) does NOT revive wrappers back into `Uint8Array`s —
 *   only values coming over the bridge get that. re-sending the parsed object works as-is; to
 *   *read* the bytes out of one, base64-decode its `$inuBytes` yourself
 * - `flags` bitfields are raw numbers, unmanaged: setting/clearing an optional field does NOT
 *   update `flags` for you, you must fix it up yourself
 * - a live view is only valid for the duration of its `interceptRpc` dispatch — stashing one past
 *   the dispatch settling and then touching a field throws `"TL handle expired"`. use
 *   `obj.toJSON()` to detach a plain, independently-mutable deep copy if you need to keep data
 *   around past the dispatch, or hand it to code outside the bridge (e.g. `postMessage`, or
 *   `inu.kv.set` after `JSON.stringify` — which uses the same snapshot itself)
 * - plain object literals (`{ _: 'messages.sendMessage', peer, message }`) work fine wherever a
 *   `TLObject` is expected (e.g. a middleware's short-circuit return, or `invokeRpc`'s argument) —
 *   only values that *came from* the bridge are live views, nothing requires you to construct one
 */
declare type TLObject = Record<string, any>
declare type MaybePromise<T> = T | Promise<T>

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

  /** info about the current app, plugin engine and the plugin itself. */
  function info(): {
    platform: 'android'
    appVersion: string // e.g. '6.81'
    appBuild: string // e.g. '6822'
    apiVersion: number // always 1 now. the version of the plugin api
    layer: number // TL layer the app is running on
    language: string // e.g. 'en-US'. note that this can also be a custom langpack id.
    header: Record<string, string> // data from the metadata header
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
  function onUnload(callback: () => void): void

  /**
   * called when the app is moved to the background/foreground
   *
   * @needs-grant inu.onAppVisibilityChange
   */
  function onAppVisibilityChange(callback: (mode: 'foreground' | 'background') => void): void

  /**
   * access to a persistent plugin-scoped key-value store. string keys/values only — serialize
   * structured data yourself (`JSON.stringify`; see the `TLObject` doc for the byte-field caveat).
   * `set`/`insertAll` throw once the 1 MB per-plugin quota would be exceeded. survives app
   * restarts and plugin reloads; wiped when the plugin is uninstalled.
   */
  namespace kv {
    /** @needs-grant inu.kv */
    function get(key: string): string | null
    /** @needs-grant inu.kv */
    function set(key: string, value: string): void
    /** @needs-grant inu.kv */
    function del(key: string): void
    /** @needs-grant inu.kv */
    function keys(): string[]
    /** @needs-grant inu.kv */
    function clear(): void
    /** @needs-grant inu.kv */
    function getAll(): Record<string, string>
    /** @needs-grant inu.kv */
    function insertAll(values: Record<string, string>): void
  }

  /** access to the clipboard */
  namespace clipboard {
    /** @needs-grant inu.clipboard.write */
    function write(text: string): void
    /** @needs-grant inu.clipboard.read */
    function read(): string
  }

  type UIElement = OpaqueType<'UIElement'>
  type UIIcon = OpaqueType<'UIIcon'> // todo
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
      positive?: string
      negative?: string
      neutral?: string
    }): Promise<'positive' | 'negative' | 'neutral' | 'dismissed'>

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
   * plugins list. one per plugin (last call wins). nested pages need no registration: build
   * another `settingsPage` and `ui.openPage(...)` it from a button's `onClick`.
   */
  function registerSettings(page: ui.UIPage): void

  /**
   * register an interceptor for all rpc requests of the methods in the array.
   *
   * middlewares for the same method run in registration order, forming a chain. each stage must
   * either call `next(request)` at most once (calling it twice throws) or return its own response.
   * returning `undefined` without having called `next()` is an error; returning `undefined` after
   * `next()` passes that call's response through unchanged. `next()` past the last middleware sends
   * the (possibly rewritten) request for real.
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
   * @needs-grant inu.interceptRpc
   */
  function interceptRpc(
    method: string | string[],
    middleware: (request: TLObject, next: (request: TLObject) => MaybePromise<TLObject | null>) => MaybePromise<TLObject | null | undefined>,
  ): void

  /** @not-implemented */
  // declarative perf-oriented api for trivial cases + more generic api for more complex cases
  function interceptDeserialize(rules: {
    // types of the objects to intercept
    type: string | string[]
    // when to intercept
    when?: Record<string, any>
    // fields to set
    set: Record<string, any>
  }[]): void
  /**
   * register an interceptor for all TL deserialization operation of the types in the array
   * @not-implemented
   */
  function interceptDeserialize(objects: string[], middleware: (request: TLObject) => TLObject): void

  /**
   * invoke an rpc request. bypasses interceptRpc middleware (does not re-trigger it).
   *
   * the resolved value is a plain, detached snapshot of the response (NOT a live view — see
   * `TLObject`'s doc): freely mutable, keepable forever, `JSON.stringify`-able, with the usual
   * plain-data caveats (int64s as strings, bytes as `Uint8Array`).
   *
   * rejects with an `inu.RpcError` when the request fails server-side; resolves to `null` if the
   * app completed the request with neither a response nor an error.
   *
   * runs on the account that was selected when the plugin was loaded — switching accounts
   * mid-session does not retarget it.
   *
   * @needs-grant inu.invokeRpc
   */
  // todo: getAccounts() + an explicit invoke-on-account api instead of the load-time snapshot
  function invokeRpc(params: TLObject): Promise<TLObject | null>
  /**
   * register an update handler. called for every update the granted scope allows, across all
   * arrival paths (incl. difference catch-up). no per-callback filtering — grant scope is the only filter.
   * @needs-grant inu.onUpdate
   */
  function onUpdate(callback: (update: TLObject) => void): void

  // -- actions --
  // very much a draft, actual api is subject to change when i actually implenent the actions

  // ** we really lack methods to get chat info and user info and stuff like that from the app itself (app db etc.)

  /** register a global action (drawer menu, or triple-dot menu) */
  function registerAction(options: {
    id: string
    text: string
    icon?: UIIcon
    callback?: () => void
  }): void

  /** register a chat-level action (in triple-dot menu of the chat) */
  function registerChatAction(options: {
    id: string
    text: string
    icon?: UIIcon
    callback?: (chatId: number) => void // todo: chat object
  }): void

  /** register a message-level action (in the message context menu) */
  function registerMessageAction(options: {
    id: string
    text: string
    icon?: UIIcon
    callback?: (chatId: number, messageId: number) => void // todo: chat object, message object
  }): void

  /** register a message editor action (in the message context menu) */
  function registerMessageEditorAction(options: {
    id: string
    text: string
    icon?: UIIcon
    callback?: (ctx: {
      chatId: number // todo: chat object
      draft: string // todo: text with entities

      replace: (draft: string) => void
      send: (message: string) => void
    }) => void
  }): void

  // todo: intercept message send
  // todo: think about how we would write a plugin that suppreses typing if draft starts with dot

  /** lower-level apis that most plugins should not need to use directly. */
  namespace subtle {}
}
