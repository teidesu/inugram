/*
Plugin lifecycle follows its grants. Network hooks can run with no activity after a push wakeup.
One JavaScript turn may run 2 seconds of uninterrupted work, or 10 for the top-level evaluation.
Native-backed values have their own budget, 64 MB per plugin; API arrays have at most 65536 elements.

Unknown grant names give no access. Unknown grant scopes reject installation.

Sensitive plugin data is filtered. Takeover RPC methods are refused: `auth.*`, plus `account.`\{`getPasskeys`, `deletePasskey`, `registerPasskey`, `initPasskeyRegistration`, `registerDevice`, `unregisterDevice`, `deleteAccount`, `changePhone`, `getAuthorizations`, `resetAuthorization`, `acceptAuthorization`, `verifyPhone`, `verifyEmail`, `resetPassword`\}.

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

  /**
   * Frees the content now. Dropping the last reference frees it too, whenever the collector gets
   * to it - this is the eager path, and it is also `[Symbol.dispose]`, so `using` works:
   * `using png = await canvas.convertToBlob()`.
   */
  dispose(): void
  [Symbol.dispose](): void
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

  /**
   * Handlers run in registration order; returned promises share a 2-second cleanup window.
   * Resources remain available until settlement/timeout. Ordinary callbacks and timers stop first.
   * JVM runnables created during cleanup can run on the UI thread; existing callbacks stay stopped.
   */
  function onUnload(callback: () => void | Promise<void>): Disposer

  /**
   * Open a web URL, or an internal deeplink (t.me/telegram.org)
   *
   * @needs-grant openUrl
   */
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

  /**
   * A chat folder, as the folder tabs show it. This is app state rather than a TL object - the
   * app keeps its own class for it, with the dialogs it currently resolves to - so it crosses as
   * a plain object rather than a `tl.Type*` handle.
   */
  interface ChatFolder {
    /** `0` is the "All chats" tab, which every account has and which cannot be edited. */
    id: number
    title: TextWithEntities
    /**
     * The folder's emoji, or `null` when it has none. Inugram-only: stock android drops the
     * emoticon the server sends, and the fork keeps it in its own storage.
     */
    emoticon: string | null
    /** Telegram's folder colour, `0`-`7` into its own palette - not an ARGB value. `null` when the folder has no colour. */
    colorIndex: number | null
    unreadCount: number
    /** How many dialogs {@link Account.getDialogsCached} would answer for this folder right now. */
    dialogCount: number
    isDefault: boolean
    /** A shared folder, added from an invite link. */
    isChatlist: boolean
    /** The folder's pinned dialogs, in the order they are pinned. */
    pinned: DialogId[]
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
    /** opaque slot of the account */
    readonly id: number

    /**
     * id of the user this account represents
     *
     * @needs-grant account.read(self)
     */
    readonly userId: number

    /** whether this is the currently active account */
    isCurrent(): boolean

    /**
     * get the user this account represents
     *
     * @needs-grant account.read(self)
     */
    getMe(): tl.TypeUser | null

    /**
     * get a user (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(peers)
     */
    getUser(peer: InputPeerLike): tl.TypeUser | null
    /**
     * get a chat (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(peers)
     */
    getChat(peer: InputPeerLike): tl.TypeChat | null
    /**
     * get a user or a chat (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(peers)
     */
    getPeer(peer: InputPeerLike): tl.TypeUser | tl.TypeChat | null
    /**
     * get a dialog with a specific peer (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(dialogs)
     */
    getDialog(peer: InputPeerLike): tl.TypeDialog | null
    /**
     * get one or more users (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(peers)
     */
    getUsers(peers: InputPeerLike[]): (tl.TypeUser | null)[]
    /**
     * get one or more chats (cached, this method never does a network call, and returns `null` on miss)
     * @needs-grant account.read(peers
     */
    getChats(peers: InputPeerLike[]): (tl.TypeChat | null)[]

    /**
     * get one or more messages, cached. returns `null` on miss
     *
     * @needs-grant account.read(messages)
     * @peer the dialog, or `0` for the common message box (dms, legacy groups)
     * @messageId the message to get
     */
    getMessagesCached(peer: InputPeerLike, messageId: number): Message | null
    getMessagesCached(peer: InputPeerLike, messageIds: number[]): (Message | null)[]

    /**
     * get one or more messages, *always* fetching them from the server
     *
     * @needs-grant account.read(messages)
     * @peer the dialog, or `0` for the common message box (dms, legacy groups)
     * @messageId the message to get
     */
    getMessages(peer: InputPeerLike, messageId: number): Promise<Message | null>
    getMessages(peer: InputPeerLike, messageIds: number[]): Promise<(Message | null)[]>

    /**
     * get an app-owned file representing the attachment of a message
     *
     * @needs-grant account.read(messages)
     */
    getMessageFile(message: Message | tl.TypeMessage): { path: string, exists: boolean } | null

    /**
     * download a message's attachment to a File
     *
     * @needs-grant account.read(messages)
     */
    downloadMedia(message: Message | tl.TypeMessage, options?: {
      /** download progress callback */
      onProgress?: ProgressCallback
    }): Promise<File>

    /**
     * download a message's attachment to a file, and return the path
     *
     * @needs-grant account.read(messages)
     */
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

    /**
     * `fields` names the fields you are going to read, exactly as {@link getDialogsCached} takes
     * them: their values cross with the dialogs rather than one at a time when you touch them.
     *
     * @needs-grant account.read(dialogs)
     */
    getDialogs(options?: {
      folderId?: number
      limit?: number
      fields?: readonly string[]
      cursor?: Cursor<'dialogs'>
    }): Promise<Paged<tl.TypeDialog, 'dialogs'>>
    /** @needs-grant account.read(dialogs). `batchSize` defaults to (omitted, **100**, which is telegram's own page). `fields` is passed to every page. */
    iterDialogs(options?: {
      folderId?: number
      limit?: number
      batchSize?: number
      fields?: readonly string[]
    }): AsyncIterableIterator<tl.TypeDialog>

    /**
     * The dialogs the app already holds in memory, ordered the way the chat list orders them:
     * pinned first, then by date. Never goes to the network and never pages - one call answers
     * the whole list, consistently.
     *
     * Asynchronous all the same: the app owns these lists on its ui thread and rebuilds them in
     * place, so the read hops there rather than walking a list mid-rebuild. It resolves on the
     * next turn, and cannot be called from a synchronous context such as an `inu.xposed` phase.
     *
     * `archive` picks what the answer covers: `'exclude'` (the default) is the main list, `'only'`
     * the archive, `'keep'` both. `chatFolderId` narrows to one folder from
     * {@link getChatFoldersCached} instead; a folder already decides for itself whether it shows
     * archived chats, so naming both `archive` and `chatFolderId` is `invalid-argument`.
     *
     * `fields` names the fields you are going to read, so their values cross with the dialogs
     * themselves rather than one at a time when you touch them. It changes nothing about what a
     * dialog answers - a field you did not name still reads, and so does one this could not carry
     * (an object, a vector, a very long string, or no such field on that constructor). It is worth
     * naming for a list you walk: reading two fields of a few hundred dialogs is a few hundred
     * crossings otherwise.
     *
     * @needs-grant account.read(dialogs)
     */
    getDialogsCached(options?: {
      archive?: 'exclude' | 'only' | 'keep'
      chatFolderId?: number
      limit?: number
      fields?: readonly string[]
    }): Promise<tl.TypeDialog[]>

    /**
     * The account's chat folders, in the order their tabs appear. Reads what
     * {@link getDialogsCached} reads, and is asynchronous for the same reason.
     *
     * @needs-grant account.read(dialogs)
     */
    getChatFoldersCached(): Promise<ChatFolder[]>

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

    /**
     * send a text message
     *
     * @needs-grant account.write(send)
     * @param peer the dialog
     * @param text the message text
     */
    sendMessage(peer: InputPeerLike, text: InputText, options?: {
      /** reply to a message by its id */
      replyToMessageId?: number
      /** send the message to a specific topic */
      topicId?: number
      /** send a message without sound */
      silent?: boolean
      /** instead of sending, schedule the message for a future date (unix timestamp in seconds) */
      scheduleDate?: number
      /** do not generate webpage previews */
      noWebpage?: boolean
      /** "send as" a channel. defaults to the user-preferred sender */
      sendAs?: InputPeerLike

      /**
       * whether to clear the draft after sending
       *
       * @default true
       */
      clearDraft?: boolean

      /**
       * when `true`, the message is shown in the ui right away,
       * similarly to how it would look like if the user had sent it,
       * showing the progress.
       *
       * incompatible with `sendAs` and in some cases `replyToMessageId`
       *
       * @default true
       */
      optimistic?: boolean
    }): Promise<Message>

    /**
     * send a message containing a media
     *
     * @needs-grant account.write(send)
     * @param peer the dialog
     * @param file the media file
     */
    sendMedia(
      peer: InputPeerLike, file: Blob | Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | { path: string },
      options?: {
        /** caption for the media */
        caption?: InputText
        /** reply to a message by its id */
        replyToMessageId?: number
        /** send the message to a specific topic */
        topicId?: number
        /** send a message without sound */
        silent?: boolean
        /** instead of sending, schedule the message for a future date (unix timestamp in seconds) */
        scheduleDate?: number
        /** force send the message as a document, instead of auto-detecting its type by mime */
        asDocument?: boolean
        /** file name for the media */
        fileName?: string
        /** "send as" a channel. defaults to the user-preferred sender */
        sendAs?: InputPeerLike
        /** upload progress callback */
        onProgress?: ProgressCallback

        /**
         * when `true`, the message is shown in the ui right away,
         * similarly to how it would look like if the user had sent it,
         * showing the upload progress.
         *
         * incompatible with `sendAs` and in some cases `replyToMessageId`
         *
         * @default true
         */
        optimistic?: boolean
      }): Promise<Message>

    /**
     * send an album
     *
     * @needs-grant account.write(send)
     * @param peer the dialog
     * @param items the media files
     */
    sendMultiMedia(
      peer: InputPeerLike,
      items: {
        /** the media file */
        file: Blob | Uint8Array | tl.TypeInputFile | tl.TypeInputMedia | { path: string }
        /** caption for the media */
        caption?: InputText
        /** file name for the media */
        fileName?: string
        /** whether to send the media as a document, instead of auto-detecting its type by mime */
        asDocument?: boolean
      }[],
      options?: {
        /** reply to a message by its id */
        replyToMessageId?: number
        /** send the message to a specific topic */
        topicId?: number
        /** send a message without sound */
        silent?: boolean
        /** instead of sending, schedule the message for a future date (unix timestamp in seconds) */
        scheduleDate?: number
        /** "send as" a channel. defaults to the user-preferred sender */
        sendAs?: InputPeerLike
        /** upload progress callback */
        onProgress?: ProgressCallback
      }
    ): Promise<Message[]>

    /**
     * edit a message
     *
     * @needs-grant account.write(edit)
     * @param peer the dialog
     * @param messageId the message to edit
     * @param text the new message text
     */
    editMessage(peer: InputPeerLike, messageId: number, text: InputText, options?: {
      /** do not generate webpage previews */
      noWebpage?: boolean
    }): Promise<Message>

    /**
     * delete one or more messages
     *
     * @needs-grant account.write(delete)
     * @param peer the dialog to delete from
     * @param messageIds the messages to delete
     */
    deleteMessages(peer: InputPeerLike, messageIds: number[], options?: {
      /** "delete for everyone", only applies to legacy groups and dms */
      revoke?: boolean
    }): Promise<void>

    /**
     * forward one or more messages
     *
     * @needs-grant account.write(forward)
     * @param fromPeer the dialog to forward from
     * @param messageIds the messages to forward
     * @param toPeer the dialog to forward to
     */
    forwardMessages(fromPeer: InputPeerLike, messageIds: number[], toPeer: InputPeerLike, options?: {
      /** send a message without sound */
      silent?: boolean
      /** instead of sending, schedule the message for a future date (unix timestamp in seconds) */
      scheduleDate?: number
      /** send the message to a specific topic */
      topicId?: number
      /** whether to send "without author" */
      dropAuthor?: boolean
      /** whether to send "without caption" (implies `dropAuthor`) */
      dropCaption?: boolean
    }): Promise<Message[]>

    /**
     * send or retract a reaction on a message
     *
     * @needs-grant account.write(react)
     * @param peer the dialog
     * @param messageId the message to update reactions for
     * @param reactions the reactions to apply (the full list)
     */
    setReaction(peer: InputPeerLike, messageId: number, reactions: (string | { customEmojiId: string })[], options?: {
      /** whether to send the new reaction as "big" */
      big?: boolean
    }): Promise<void>

    /**
     * mark messages as read
     *
     * @needs-grant account.write(read)
     */
    readHistory(peer: InputPeerLike, options?: {
      /** the last message to mark as read */
      maxId?: number
      /** read the messages in a specific topic */
      topicId?: number
    }): Promise<void>

    /**
     * send a typing status
     *
     * @needs-grant account.write(typing)
     * @param peer the dialog
     * @param action the action to send
     */
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
        /** send the typing status to a specific topic */
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

    /**
     * invoke a raw mtproto rpc method
     *
     * @needs-grant invokeRpc
     */
    invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>

    /**
     * invoke a raw mtproto rpc method, given its TL serialization,
     * and return the TL serialization of the server's response
     *
     * ⚠️ this method is primarily intended for **advanced users**, primarily
     * for cases where you want to call a method that is not yet supported by the current
     * app's layer. beware that can cause the app to crash or otherwise misbehave,
     * due to the server "bumping" the layer. in almost all cases, you should use
     * {@link invokeRpc} instead.
     *
     * @needs-grant unsafe.invokeRaw
     */
    invokeRaw(method: Uint8Array): Promise<Uint8Array | null>

    /**
     * open a takeout session, which increases the rate limits for history reads
     *
     * Telegram keeps the session until {@link TakeoutSession.finish}, so finish the one you
     * opened rather than leaving it behind.
     *
     * @needs-grant takeout
     */
    initTakeoutSession(options?: {
      contacts?: boolean
      messageUsers?: boolean
      messageChats?: boolean
      messageMegagroups?: boolean
      messageChannels?: boolean
      fileMaxSize?: number
    }): Promise<TakeoutSession>
  }

  /**
   * a takeout session
   */
  interface TakeoutSession {
    /** the session's int64 id, as a string */
    readonly id: string

    /**
     * invoke a raw mtproto rpc method, wrapped in `invokeWithTakeout`
     *
     * @needs-grant takeout, plus `invokeRpc` for the method being called
     */
    invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>

    /** closes the session server-side; `success` defaults to `true`. @needs-grant takeout */
    finish(success?: boolean): Promise<boolean>
  }

  namespace utils {
    /** convert an array of bytes to base64 */
    function toBase64(bytes: Uint8Array): string
    /** convert base64 to an array of bytes */
    function fromBase64(base64: string): Uint8Array
    /** convert an array of bytes to hex */
    function toHex(bytes: Uint8Array): string
    /** convert hex to an array of bytes */
    function fromHex(hex: string): Uint8Array

    /** app-localized date text. `unix` must be a safe integer Unix timestamp in seconds. */
    function formatDate(unix: number, style?: 'date' | 'time' | 'dateTime' | 'relative'): string

    /** app-localized integer text. `compact` uses Telegram's million formatter. */
    function formatNumber(value: number, options?: { compact?: boolean }): string

    /** app-localized file size text. `bytes` must be a safe integer. */
    function formatFileSize(bytes: number): string

    /** app's clock-style duration text. `seconds` must be a non-negative signed 32-bit integer. */
    function formatDuration(seconds: number): string

    namespace peers {
      /** convert a peer object to a dialog id */
      function toDialogId(peer: PeerLikeObject): DialogId
      /** parse a dialog id */
      function parseDialogId(id: DialogId | string): {
        type: 'user' | 'chat'
        id: number
      }
      /** convert a user or chat object to an input peer */
      function toInputPeer(userOrChat: tl.TypeUser | tl.TypeChat): tl.TypeInputPeer
      /** convert a peer object to a bot api id */
      function toBotApiId(peer: PeerLikeObject): number
      /** convert a bot api id to a dialog id */
      function fromBotApiId(id: DialogId | string): DialogId
    }
  }

  type UIElement = OpaqueType<'UIElement'>
  type UIIcon = OpaqueType<'UIIcon'>

  namespace icons {
    interface LottieOptions {
      /** `true` loops forever; a number repeats that many times after the initial play. */
      loop?: boolean | number
      /** whether to force render this as a static image (i.e. render just the first frame for animated stickers) */
      static?: boolean
    }

    /** UIIcon from a built-in common animation */
    function animation(name: 'success' | 'error' | 'info' | 'loading'): UIIcon

    /** UIIcon from a Telegram custom emoji (`id` is the custom emoji ID as a string) */
    function customEmoji(id: string, options?: LottieOptions): UIIcon

    type StickerOptions = { slug: string } & LottieOptions & (
      | { index: number, emoji?: never, id?: never }
      | { emoji: string, index?: never, id?: never }
      | { id: string, index?: never, emoji?: never }
    )

    /**
     * UIIcon from a Telegram sticker.
     *
     * `slug` is the slug of a stickerset, and to address a sticker you can use one of:
     * - `index` - zero-based index of a sticker in the set
     * - `emoji` - emoji the sticker represents
     * - `id` - the sticker's id
     *
     * `id` is the most stable way to address a sticker, but none of them are *that* stable
     * because indices might shift, emojis might change, and sticker with the ID might be deleted from the set.
     *
     * for use in interfaces it is highly recommended to use a set you control, or use custom emojis.
     */
    function sticker(options: StickerOptions): UIIcon

    /** UIIcon from a built-in common icon */
    function common(
      name:
        | 'settings' | 'info' | 'search' | 'edit' | 'delete' | 'copy' | 'share' | 'download'
        | 'link' | 'pin' | 'star' | 'mute' | 'unmute' | 'archive' | 'forward' | 'reply'
        | 'user' | 'group' | 'channel' | 'bot' | 'lock' | 'eye' | 'eyeOff' | 'refresh'
        | 'plus' | 'minus' | 'check' | 'close' | 'more' | 'translate' | 'bookmark',
    ): UIIcon

    /** UIIcon from a custom SVG, **at most 64 KiB of source**. */
    function svg(source: string): UIIcon
  }
  namespace ui {
    interface UIPage {
      invalidate(): void
      dispose(): void
    }

    type PageTarget
      = | { type: 'chat', dialogId: DialogId, topicId?: number, account?: number }
        | { type: 'profile', dialogId: DialogId, account?: number }
        | { type: 'dialogs', account?: number }
        | { type: 'settings', account?: number }

    /** open a page defined by the plugin */
    function openPage(page: UIPage): void
    /** open a stock commonly used page */
    function openPage(screen: PageTarget): void

    /** show a toast */
    function toast(text: string): void
    /** show a bulletin (aka snackbar) */
    function bulletin(options: {
      text: string
      icon: UIIcon
    }): void

    /**
     * show a dialog
     *
     * returns a promise that resolves to the result of the dialog once it's dismissed,
     * with either the name of the button pressed, or `'dismissed'` if the dialog was dismissed
     * (e.g. by tapping outside of it, or by pressing the back button).
     */
    function dialog(options: {
      /** title of the dialog */
      title?: string
      /** message of the dialog */
      message?: string
      /** custom body of the dialog */
      body?: UIElement
      /** text of the positive button, `undefined` to hide */
      positive?: string
      /** text of the negative button, `undefined` to hide */
      negative?: string
      /** text of the neutral button, `undefined` to hide */
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

    /** open a file picker, returning the File the user chose (or `null` if they chose nothing) */
    function pickFile(options?: {
      /** mime types to accept */
      accept?: string[]
      /** whether to allow multiple files */
      multiple?: false
    }): Promise<File | null>
    /** open a file picker, returning the File-s the user chose (or `[]` if they chose nothing) */
    function pickFile(options: {
      accept?: string[]
      multiple: true
    }): Promise<File[]>

    /**
     * save a file to the device storage, opening a "save as" dialog
     *
     * @needs-grant fs
     * @param content the file to save
     * @returns `true` if the user chose to save the file, `false` if they chose not to
     */
    function saveFile(content: Blob | Uint8Array | { path: string }, options?: {
      /** recommended file name */
      fileName?: string
      /** mime type */
      type?: string
    }): Promise<boolean>

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
      /**
       * Disposes the page as soon as it closes, after {@link onClose} has returned: for a page
       * built per open, whose definition is of no use once the user navigates back. A page opened
       * more than once must not ask for this - opening a disposed page is `handle-expired`.
       */
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

  /**
   * Send a method this build has no class for, as the bytes of the whole method - its constructor
   * id first, then its arguments - and get the response back the same way, constructor id
   * included. An rpc error still rejects as {@link RpcError}, and a request the app answered with
   * nothing at all resolves `null`.
   *
   * Nothing about the payload is interpreted, so nothing about it is checked either: the api
   * filter can only read the constructor the bytes open with, and refuses a takeover method by
   * that alone - one no layer this build knows is sent as written, which is what this is for.
   * What the bytes mean past the constructor is the plugin's to get right.
   *
   * @needs-grant unsafe.invokeRaw
   */
  function invokeRaw(method: Uint8Array): Promise<Uint8Array | null>

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

    /**
     * Gives this send its media as a file to stage, **on the message the app already drew** rather
     * than as a send of your own: that bubble grows or swaps its media in place, and the app is
     * what uploads the file and draws the progress on it. Works on a text send and on a single
     * media send; an album or an edit throws `unsupported`.
     *
     * The caption is this message's own `text`, so set that rather than passing one here. Await it:
     * it resolves once the file is staged, and rejects if the file cannot be read — so a failure is
     * yours to handle rather than a send that fails afterwards. Return `'send'` once it resolves,
     * since the request you were handed never goes out and `'drop'` discards the media with it.
     *
     * Because it never goes out, there is no response: the `next()` an `interceptRpc` middleware
     * above this one awaits resolves with `null` rather than with `Updates`.
     *
     * The message is re-sent, so it reaches send middleware a second time as a `messages.sendMedia`
     * — carrying a mark that refuses a second `setMedia`, which is what stops this recursing.
     * Replacing the media of a send that already has some costs the upload the app already did.
     *
     * A `{ path }` is uploaded from where it is, after this resolves, so leave the file in place:
     * the app retries a failed send from it too. It is copied instead when its extension disagrees
     * with the name it is sent under.
     */
    setMedia(file: Blob | Uint8Array | { path: string }, options?: {
      fileName?: string
      /** send an image as a file rather than recompressing it into a photo */
      asDocument?: boolean
    }): Promise<void>
  }

  interface SendMessageFilter {
    /** Compiled by Android's `java.util.regex.Pattern`; unsupported syntax rejects registration. */
    text?: RegExp
    isEdit?: boolean
  }

  /**
   * @needs-grant interceptSendMessage
   *
   * A middleware that answers within ~100ms drops a message before it is ever drawn.
   * Longer work shows a pending bubble, which is updated or removed when the chain settles.
   * This applies equally to synchronous and async functions.
   *
   * A `drop` is a verdict, not a response, and the two behave differently in a chain. An
   * `interceptRpc` middleware wrapping this one may catch what its `next()` rejects with and answer
   * something else, which is how a failed request is retried or given a fallback. It cannot do that
   * to a verdict: the send is already being unwound when the verdict is made, so the app acts on it
   * whatever the stages above return.
   */
  function interceptSendMessage(
    middleware: (message: OutgoingMessage, account: Account) => MaybePromise<'send' | 'drop'>,
  ): Disposer
  /**
   * @needs-grant interceptSendMessage. Filters are checked before entering the plugin engine.
   *
   * Same timing as the unfiltered form: a verdict within ~100ms can suppress the bubble;
   * longer work shows a pending bubble until the chain settles.
   */
  function interceptSendMessage(
    filter: SendMessageFilter,
    middleware: (message: OutgoingMessage, account: Account) => MaybePromise<'send' | 'drop'>,
  ): Disposer
}
