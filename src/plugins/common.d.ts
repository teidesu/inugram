/*
Plugin lifecycle follows its grants. Network hooks can run with no activity after a push wakeup.
One JavaScript turn may run 2 seconds of uninterrupted work, or 10 for the top-level evaluation.
Native-backed values have their own budget, 64 MB per plugin; API arrays have at most 65536 elements.

Unknown grant names give no access. Unknown grant scopes reject installation.

Sensitive plugin data is filtered. `TLRPC.deserialize` is outside interception. Takeover RPC methods are refused: `auth.*`, plus `account.`\{`getPasskeys`, `deletePasskey`, `registerPasskey`, `initPasskeyRegistration`, `registerDevice`, `unregisterDevice`, `deleteAccount`, `changePhone`, `getAuthorizations`, `resetAuthorization`, `acceptAuthorization`, `verifyPhone`, `verifyEmail`, `resetPassword`\}.

A plugin runs one JavaScript turn at a time. A callback has a time and memory limit.
*/

declare const console: {
  log(...args: any[]): void
  info(...args: any[]): void
  warn(...args: any[]): void
  error(...args: any[]): void
  debug(...args: any[]): void
}

declare function atob(data: string): string
declare function btoa(data: string): string

declare const performance: {
  now(): number
  readonly timeOrigin: number
}

declare class DOMException extends Error {
  constructor(message?: string, name?: string)

  readonly code: number
}

/** Timers share a per-plugin limit of 512 live timeout/interval registrations. */
declare function setTimeout(callback: () => void, ms?: number): number
declare function clearTimeout(id?: number): void
/** An interval repeats every 4 ms at the fastest and counts against the shared timer limit. */
declare function setInterval(callback: () => void, ms?: number): number
declare function clearInterval(id?: number): void
declare function queueMicrotask(callback: () => void): void

declare class TextEncoder {
  encode(input?: string): Uint8Array
  readonly encoding: 'utf-8'
}
declare class TextDecoder {
  constructor(label?: 'utf-8')
  decode(input?: Uint8Array | ArrayBufferLike): string
  readonly encoding: 'utf-8'
}

declare class URL {
  constructor(url: string, base?: string)
  static canParse(url: string, base?: string): boolean
  static parse(url: string, base?: string): URL | null

  href: string
  protocol: string
  username: string
  password: string
  host: string
  hostname: string
  port: string
  pathname: string
  search: string
  hash: string

  readonly searchParams: URLSearchParams
  readonly origin: string
  toString(): string
  toJSON(): string
}

declare class URLSearchParams {
  constructor(init?: string | string[][] | Record<string, string> | URLSearchParams)
  readonly size: number
  append(name: string, value: string): void

  delete(name: string, value?: string): void
  get(name: string): string | null
  getAll(name: string): string[]
  has(name: string, value?: string): boolean

  set(name: string, value: string): void

  sort(): void
  forEach(callback: (value: string, name: string, parent: URLSearchParams) => void, thisArg?: any): void
  keys(): IterableIterator<string>
  values(): IterableIterator<string>
  entries(): IterableIterator<[string, string]>
  [Symbol.iterator](): IterableIterator<[string, string]>
  toString(): string
}

declare const crypto: {

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

declare function structuredClone<T>(value: T): T

/** One call assembles at most 32 MB; spilled content is **2 GB of spilled content live at once** and **64 spilled blobs held at once**. */
declare class Blob {
  constructor(parts?: (Blob | Uint8Array | ArrayBuffer | string)[], options?: { type?: string })

  readonly size: number

  readonly type: string

  slice(start?: number, end?: number, contentType?: string): Blob

  /** Labels are **truncated at 1024 characters**. */
  bytes(): Promise<Uint8Array>
  /** `arrayBuffer()` are capped at 16 MB**. */
  arrayBuffer(): Promise<ArrayBuffer>

  /** `text()` stops at 8 MB**. */
  text(): Promise<string>

  dispose(): void
}

declare class File extends Blob {
  constructor(parts: (Blob | Uint8Array | ArrayBuffer | string)[], name: string, options?: {
    type?: string

    lastModified?: number
  })

  readonly name: string
  readonly lastModified: number
}

/** Progress reports at most one per 100 ms. */
declare type ProgressCallback = (loaded: number, total: number) => void

declare type HeadersInit = Record<string, string | string[]>

declare interface Response {
  readonly ok: boolean
  readonly status: number
  readonly statusText: string

  readonly url: string
  readonly headers: Record<string, string | string[]>
  text(): Promise<string>
  json(): Promise<any>

  bytes(): Promise<Uint8Array>
  arrayBuffer(): Promise<ArrayBuffer>

  blob(): Promise<Blob>
}

/** @needs-grant fetch. A response has 32 MB in each direction; a plugin may hold at most 256 MB of fetched content, and a chain is refused when longer than 20 hops. */
declare function fetch(url: string, init?: {
  method?: string
  headers?: HeadersInit
  body?: string | Uint8Array | Blob

  redirect?: 'follow' | 'manual' | 'error'

  signal?: AbortSignal

  timeout?: number
}): Promise<Response>

declare const __opaque__: unique symbol

declare interface OpaqueType<Brand> { readonly [__opaque__]: Brand }

declare type TLObject = tl.TypeTlObject
declare type MaybePromise<T> = T | Promise<T>

declare type MismatchedRpcReturns<M extends tl.TypeRpcMethod['_'], All extends tl.TypeRpcMethod['_'] = M>
  = M extends any ? ([tl.RpcCallReturn[All]] extends [tl.RpcCallReturn[M]] ? never : M) : never

declare type SharedRpcReturn<M extends tl.TypeRpcMethod['_']>
  = [MismatchedRpcReturns<M>] extends [never] ? tl.RpcCallReturn[M] : never

declare interface InterceptRpcOptions {
  /** Fail the app's RPC when the middleware returns an invalid TL value. The default logs and skips that middleware. */
  strict?: boolean
}

declare type Disposer = () => void

declare type DialogId = number

declare type PeerLikeObject
  = | tl.TypePeer | tl.TypeInputPeer | tl.TypeInputUser | tl.TypeInputChannel
    | tl.TypeUser | tl.TypeChat

declare type InputPeerLike = DialogId | PeerLikeObject | 'me' | 'self' | (string & {})

declare interface TextWithEntities {
  text: string
  entities?: tl.TypeMessageEntity[]
}

declare type InputText = string | TextWithEntities

declare namespace inu {
  class RpcError extends Error {
    constructor(code: number, text: string)
    code: number
    text: string
  }

  class PluginError extends Error {
    code:
      | 'not-granted'
      | 'forbidden'
      | 'quota-exceeded'
      | 'handle-expired'
      | 'unknown-constructor'
      | 'invalid-argument'
      | 'not-found'
      | 'unsupported'
      | 'timed-out'
      | 'aborted'
      | 'network'
      | 'internal'
      | (string & {})

    grant?: string

    usage?: number
    quota?: number
  }

  function info(): {
    platform: 'android' | (string & {})
    appVersion: string
    appBuild: string
    apiVersion: number
    layer: number
    language: string
    /**
     * The manifest's directives, one array entry per repeated key. `@icon` names the icon shown
     * in the plugins list and accepts no remote urls - only `inu://{name}` where the name comes
     * from the {@link icons.common} set, `tg://emoji?id={documentId}` for a custom emoji, or
     * `tg://addstickers?set={slug}` for a sticker out of a set (`&idx={n}` picks by 0-based
     * position, `&id={documentId}` by document id, neither picks the set's preview sticker).
     * Anything else falls back to the default icon.
     */
    header: Record<string, string[]>
  }

  function onUnload(callback: () => void): Disposer

  /** @needs-grant openUrl */
  function openUrl(url: string): void

  /** @needs-grant onAppVisibilityChange */
  function onAppVisibilityChange(callback: (mode: 'foreground' | 'background') => void): Disposer

  /** 1 MB per-plugin quota. */
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
    /** @needs-grant kv */
    function usage(): number
  }

  namespace clipboard {
    /** @needs-grant clipboard.write */
    function write(text: string): void
    /** @needs-grant clipboard.read */
    function read(): string
  }

  class Message {
    constructor(raw: tl.TypeMessage)

    readonly raw: tl.TypeMessage

    get id(): number

    get dialogId(): DialogId | null

    get senderId(): number | null

    get topicId(): number | null
    get date(): number
    get editDate(): number | null
    get out(): boolean

    get text(): string
    get textWithEntities(): TextWithEntities

    get media(): tl.TypeMessageMedia | null

    get document(): tl.TypeDocument | null

    get mediaType():
      | 'photo' | 'video' | 'roundVideo' | 'voice' | 'music' | 'sticker' | 'gif' | 'document'
      | 'poll' | 'contact' | 'location' | 'venue' | 'story' | 'giveaway' | 'invoice' | 'other'
      | null

    get duration(): number | null

    get groupedId(): string | null

    get replyToMessageId(): number | null
    get forwardedFrom(): tl.TypeMessageFwdHeader | null
    get viaBotId(): number | null
    get isPinned(): boolean
    get views(): number | null
    get forwards(): number | null
    get reactions(): tl.TypeMessageReactions | null
    get isService(): boolean

    get isSecret(): boolean

    toJSON(): tl.TypeMessage
  }

  interface AccountInfo {

    id: number
    userId: number
    isCurrent: boolean
    isPremium: boolean
  }

  /** @needs-grant account.read(self) */
  function accounts(): AccountInfo[]

  /** @needs-grant account.read(self) */
  function onAccountsChanged(callback: (accounts: AccountInfo[]) => void): Disposer

  function withCurrentAccount(callback: (account: Account) => (() => void) | void): Disposer

  function account(id?: number): Account

  /** The cursor table holds **32 cursors at once**. */
  type Cursor<List extends string> = OpaqueType<`Cursor:${List}`> & string

  type Paged<T, List extends string> = T[] & { next: Cursor<List> | null }

  interface Account {

    readonly id: number

    /** @needs-grant account.read(self) */
    readonly userId: number

    isCurrent(): boolean

    /** @needs-grant account.read(self) */
    getMe(): tl.TypeUser | null

    /** @needs-grant account.read(peers) */
    getUser(peer: InputPeerLike): tl.TypeUser | null
    /** @needs-grant account.read(peers) */
    getChat(peer: InputPeerLike): tl.TypeChat | null
    /** @needs-grant account.read(peers) */
    getPeer(peer: InputPeerLike): tl.TypeUser | tl.TypeChat | null
    /** @needs-grant account.read(dialogs) */
    getDialog(peer: InputPeerLike): tl.TypeDialog | null
    /** @needs-grant account.read(messages) */
    getMessage(peer: InputPeerLike, messageId: number): Message | null

    /** @needs-grant account.read(peers) */
    getUsers(peers: InputPeerLike[]): (tl.TypeUser | null)[]
    /** @needs-grant account.read(peers) */
    getChats(peers: InputPeerLike[]): (tl.TypeChat | null)[]
    /** @needs-grant account.read(messages) */
    getMessages(peer: InputPeerLike, messageIds: number[]): (Message | null)[]

    /** @needs-grant account.read(messages) */
    getMessageFile(message: Message | tl.TypeMessage): { path: string, exists: boolean } | null

    /** @needs-grant account.read(messages) */
    downloadMedia(message: Message | tl.TypeMessage, options?: {
      onProgress?: ProgressCallback
    }): Promise<File>

    /** @needs-grant account.read(messages) */
    downloadMediaToFile(message: Message | tl.TypeMessage, options?: {
      onProgress?: ProgressCallback
    }): Promise<{ path: string }>

    /** @needs-grant account.write(send). One staged copy is **one such copy is capped at 256 MB**; the same 256 MB staging cap applies to every write. */
    uploadFile(file: Blob | Uint8Array | { path: string }, options?: {
      fileName?: string
      onProgress?: ProgressCallback
    }): Promise<tl.TypeInputFile>

    /** @needs-grant account.read(peers) */
    getUserFull(peer: InputPeerLike): Promise<tl.TypeUserFull | null>
    /** @needs-grant account.read(peers) */
    getChatFull(peer: InputPeerLike): Promise<tl.TypeChatFull | null>

    /** @needs-grant account.read(dialogs) */
    getDialogs(options?: {
      folderId?: number
      limit?: number
      cursor?: Cursor<'dialogs'>
    }): Promise<Paged<tl.TypeDialog, 'dialogs'>>
    /** @needs-grant account.read(dialogs). `batchSize` defaults to (omitted, **100**, which is telegram's own page). */
    iterDialogs(options?: { folderId?: number, limit?: number, batchSize?: number }): AsyncIterableIterator<tl.TypeDialog>

    /** @needs-grant account.read(history) */
    getHistory(
      peer: InputPeerLike,
      options?: {
        limit?: number
        offsetId?: number
        minId?: number
        maxId?: number

        topicId?: number
      },
    ): Promise<Message[]>
    /** @needs-grant account.read(history) */
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

    /** @needs-grant account.read(dialogs) */
    getTopics(peer: InputPeerLike, options?: {
      limit?: number
      cursor?: Cursor<'topics'>
    }): Promise<Paged<tl.TypeForumTopic, 'topics'>>
    /** @needs-grant account.read(dialogs) */
    iterTopics(peer: InputPeerLike, options?: {
      limit?: number
      batchSize?: number
    }): AsyncIterableIterator<tl.TypeForumTopic>

    /** @needs-grant account.read(peers) */
    resolvePeer(peer: InputPeerLike): Promise<tl.TypeInputPeer>
    /** @needs-grant account.read(peers) */
    resolvePeerCached(peer: InputPeerLike): tl.TypeInputPeer | null
    /** @needs-grant account.read(peers) */
    resolveUser(peer: InputPeerLike): Promise<tl.TypeInputUser>
    /** @needs-grant account.read(peers) */
    resolveChannel(peer: InputPeerLike): Promise<tl.TypeInputChannel>
    /** @needs-grant account.read(peers). Resolves at most **8 in flight**. */
    resolvePeerMany(peers: InputPeerLike[]): Promise<(tl.TypeInputPeer | null)[]>

    /** @needs-grant account.write(send) */
    sendMessage(peer: InputPeerLike, text: InputText, options?: {
      replyToMessageId?: number

      topicId?: number
      silent?: boolean

      scheduleDate?: number
      noWebpage?: boolean

      sendAs?: InputPeerLike

      clearDraft?: boolean
    }): Promise<Message>

    /** @needs-grant account.write(send) */
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

    /** @needs-grant account.write(send) */
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

    /** @needs-grant account.write(edit) */
    editMessage(peer: InputPeerLike, messageId: number, text: InputText, options?: {
      noWebpage?: boolean
    }): Promise<Message>

    /** @needs-grant account.write(delete) */
    deleteMessages(peer: InputPeerLike, messageIds: number[], options?: {

      revoke?: boolean
    }): Promise<void>

    /** @needs-grant account.write(forward) */
    forwardMessages(fromPeer: InputPeerLike, messageIds: number[], toPeer: InputPeerLike, options?: {
      silent?: boolean
      scheduleDate?: number
      topicId?: number

      dropAuthor?: boolean
      dropCaption?: boolean
    }): Promise<Message[]>

    /** @needs-grant account.write(react) */
    setReaction(peer: InputPeerLike, messageId: number, reactions: (string | { customEmojiId: string })[], options?: {
      big?: boolean
    }): Promise<void>

    /** @needs-grant account.write(read) */
    readHistory(peer: InputPeerLike, options?: { maxId?: number, topicId?: number }): Promise<void>

    /** @needs-grant account.write(typing) */
    sendTyping(
      peer: InputPeerLike,
      action?:
        | 'typing'
        | 'cancel'
        | 'recordVideo'
        | 'uploadVideo'
        | 'recordVoice'
        | 'uploadVoice'
        | 'uploadPhoto'
        | 'uploadDocument'
        | 'chooseSticker'
        | 'chooseContact',
      options?: {
        topicId?: number
      }
    ): Promise<void>

    /** @needs-grant account.read(draft) */
    getDraft(peer: InputPeerLike, options?: { topicId?: number }): TextWithEntities | null
    /** @needs-grant account.write(draft) */
    setDraft(peer: InputPeerLike, draft: InputText | null, options?: {
      topicId?: number
      replyToMessageId?: number
    }): Promise<void>

    /** @needs-grant invokeRpc */
    invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>
  }

  namespace utils {
    function toBase64(bytes: Uint8Array): string
    function fromBase64(base64: string): Uint8Array
    function toHex(bytes: Uint8Array): string
    function fromHex(hex: string): Uint8Array

    /** Telegram-localized date text. `unix` must be a safe integer Unix timestamp in seconds. */
    function formatDate(unix: number, style?: 'date' | 'time' | 'dateTime' | 'relative'): string

    /** Telegram-localized integer text. `compact` uses Telegram's million formatter. */
    function formatNumber(value: number, options?: { compact?: boolean }): string

    /** Telegram-localized file size text. `bytes` must be a safe integer. */
    function formatFileSize(bytes: number): string

    /** Telegram's clock-style duration text. `seconds` must be a non-negative signed 32-bit integer. */
    function formatDuration(seconds: number): string

    namespace peers {
      function toDialogId(peer: PeerLikeObject): DialogId
      function parseDialogId(id: DialogId | string): {
        type: 'user' | 'chat'
        id: number
      }

      function toInputPeer(userOrChat: tl.TypeUser | tl.TypeChat): tl.TypeInputPeer
      function toBotApiId(peer: PeerLikeObject): number
      function fromBotApiId(id: DialogId | string): DialogId
    }
  }

  type UIElement = OpaqueType<'UIElement'>
  type UIIcon = OpaqueType<'UIIcon'>

  namespace icons {

    function common(
      name:
        | 'settings' | 'info' | 'search' | 'edit' | 'delete' | 'copy' | 'share' | 'download'
        | 'link' | 'pin' | 'star' | 'mute' | 'unmute' | 'archive' | 'forward' | 'reply'
        | 'user' | 'group' | 'channel' | 'bot' | 'lock' | 'eye' | 'eyeOff' | 'refresh'
        | 'plus' | 'minus' | 'check' | 'close' | 'more' | 'translate' | 'bookmark',
    ): UIIcon

    /** **at most 64 KiB of source**. */
    function svg(source: string): UIIcon
  }
  namespace ui {
    interface UIPage {
      invalidate(): void
      dispose(): void
    }

    function openPage(page: UIPage): void
    function openPage(screen: PageTarget): void

    function toast(text: string): void

    function dialog(options: {
      title?: string
      message?: string
      body?: UIElement
      positive?: string
      negative?: string
      neutral?: string
    }): Promise<'positive' | 'negative' | 'neutral' | 'dismissed'>

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

    interface CurrentScreen {
      type: 'chat' | 'profile' | 'dialogs' | 'settings' | 'other'
      /** @needs-grant account.read(dialogs) */
      dialogId?: DialogId
      /** @needs-grant account.read(dialogs) */
      topicId?: number
      account: Account
    }

    type PageTarget =
      | { type: 'chat', dialogId: DialogId, topicId?: number, account?: number }
      | { type: 'profile', dialogId: DialogId, account?: number }
      | { type: 'dialogs', account?: number }
      | { type: 'settings', account?: number }

    function getCurrentScreen(): CurrentScreen | null

    interface ScreenChange {
      screen: CurrentScreen | null
      previous: CurrentScreen | null
      action: 'push' | 'pop' | 'replace'
      readonly stack: CurrentScreen[]
    }

    function onScreenChanged(callback: (change: ScreenChange) => void): Disposer

    function prompt(options: {
      title: string
      hint?: string
      value?: string
      selectAll?: boolean
    }): Promise<string | null>

    interface UIAnchor {
      openMenu(items: {
        text: string
        checked?: boolean
        danger?: boolean
        onClick: () => void
      }[]): void
    }

    function header(text: string): UIElement

    function check(options: {
      id?: string
      text: string
      subtitle?: string
      checked: boolean
      onChange: (checked: boolean, anchor: UIAnchor) => void
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    function button(options: {
      id?: string
      text: string
      subtitle?: string
      icon?: UIIcon
      value?: string
      danger?: boolean
      onClick: (anchor: UIAnchor) => void
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    function select(options: {
      id?: string
      text: string
      icon?: UIIcon
      items: (string | { text: string, subtitle?: string })[]
      selected: number
      dialog?: boolean
      onChange: (index: number, anchor: UIAnchor) => void
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /** A label may have at most 501 steps**. */
    function slider(options: {
      id?: string
      text?: string
      min: number
      max: number
      step: number
      value: number
      default?: number
      label?: (value: number) => string
      onChange: (value: number, anchor: UIAnchor) => void
    }): UIElement

    function separator(text?: string): UIElement

    function settingsPage(options: {
      title: string
      transient?: boolean
      items: () => UIElement[]
      bottomButton?: {
        text: string
        onClick: (anchor: UIAnchor) => void
      }
      onClose?: () => void
    }): UIPage
  }

  function registerSettings(page: ui.UIPage): Disposer

  /** @needs-grant invokeRpc */
  function invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>

  /** @needs-grant interceptRpc */
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    method: M,
    middleware: (
      request: Extract<tl.TypeRpcMethod, { _: M }>,
      next: (request: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<tl.RpcCallReturn[M] | null>,
      account: Account,
    ) => MaybePromise<tl.RpcCallReturn[M] | null | undefined>,
    options?: InterceptRpcOptions,
  ): Disposer
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    methods: M[],
    middleware: (
      request: Extract<tl.TypeRpcMethod, { _: M }>,
      next: (request: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<SharedRpcReturn<M> | null>,
      account: Account,
    ) => MaybePromise<SharedRpcReturn<M> | null | undefined>,
    options?: InterceptRpcOptions,
  ): Disposer

  /** `TLRPC.deserialize` bypasses interception for `messages.foundStickers`, `messages.foundStickersNotModified`, `users.users`, and `users.usersSlice`. At most 32 rules live at once; parsing is parked on the answer for at most 250ms. */
  function interceptDeserialize(rules: {
    type: string | string[]
    when?: Record<string, string | number | boolean | null>
    set: Record<string, string | number | boolean | null>
  }[]): Disposer
  /** @needs-grant interceptDeserialize */
  function interceptDeserialize(objects: string[], middleware: (object: TLObject) => void): Disposer

  /** @needs-grant onUpdate(new_message) */
  function onNewMessage(callback: (message: Message, account: Account) => void): Disposer
  /** @needs-grant onUpdate(edit_message) */
  function onMessageEdited(callback: (message: Message, account: Account) => void): Disposer
  /** @needs-grant onUpdate(delete_message) */
  function onMessageDeleted(
    callback: (dialogId: DialogId | null, messageIds: number[], account: Account) => void,
  ): Disposer

  /** @needs-grant onUpdate */
  function onUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    callback: (update: Extract<tl.TypeUpdate, { _: U }>, account: Account) => void,
  ): Disposer

  /** @needs-grant interceptUpdate */
  function interceptUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    middleware: (
      update: Extract<tl.TypeUpdate, { _: U }>,
      account: Account,
    ) => MaybePromise<'deliver' | 'drop'>,
  ): Disposer

  interface ActionContext {
    account: Account
  }
  interface ChatActionContext extends ActionContext {
    dialogId: DialogId
    topicId?: number
  }
  type MessageActionSource = 'bubble' | 'selection'
  interface MessageActionContext extends ChatActionContext {
    source: MessageActionSource
    /** Oldest to newest. A bubble expands its album; a selection contains exactly what the user selected. */
    messages: readonly Message[]
  }
  interface MessageEditorActionContext extends ChatActionContext {
    draft: TextWithEntities
    replace: (draft: InputText) => void
    send: (message: InputText) => void
  }

  interface ActionOptions<Ctx, GetterCtx = Ctx> {
    id: string
    text: string | ((ctx: GetterCtx) => string)
    icon?: UIIcon | ((ctx: GetterCtx) => UIIcon)
    visible?: (ctx: Ctx) => boolean
    callback: (ctx: Ctx) => void
  }

  interface MessageActionOptions extends ActionOptions<MessageActionContext, MessageActionContext | null> {
    /** Where the action is available. Defaults to `['bubble']`. */
    placements?: readonly MessageActionSource[]
  }

  /** Renders in 150ms for every plugin's answer; at most 8 rows per menu per plugin. */
  function registerAction(options: ActionOptions<ActionContext>): Disposer

  function registerChatAction(options: ActionOptions<ChatActionContext, ChatActionContext | null>): Disposer

  function registerMessageAction(options: MessageActionOptions): Disposer

  function registerProfileAction(options: ActionOptions<ChatActionContext>): Disposer

  function registerMessageEditorAction(options: ActionOptions<MessageEditorActionContext>): Disposer

  interface OutgoingMessage {
    peer: DialogId
    text: TextWithEntities
    replyToMessageId: number | null
    topicId: number | null
    scheduleDate: number | null
    silent: boolean
    media: tl.TypeInputMedia[]
    readonly isEdit: boolean
    readonly editMessageId: number | null
  }

  /** @needs-grant interceptSendMessage */
  function interceptSendMessage(
    middleware: (message: OutgoingMessage, account: Account) => MaybePromise<'send' | 'drop'>,
  ): Disposer
}
