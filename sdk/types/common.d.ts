/*
Plugin lifecycle follows its grants. Network hooks can run with no activity after a push wakeup.

**Limits: 2 seconds of uninterrupted work per JavaScript turn, 10 seconds for top-level evaluation (a plugin going over either 3 times is stopped), 64 MB of native-backed values per plugin, 65536 elements per API array.**

Unknown grant names give no access. Unknown grant scopes reject installation.

Sensitive plugin data is filtered. Takeover RPC methods are refused: `auth.*`, plus some `account.*` methods, and `messages.{requestUrlAuth,acceptUrlAuth}`.

A plugin runs one JavaScript turn at a time. A callback has a time and memory limit.
*/

/**
 * Upload/download progress callback
 *
 * **Limits: one progress report per 100 ms.**
 */
declare type ProgressCallback = (loaded: number, total: number) => void

declare const __opaque__: unique symbol
declare interface OpaqueType<Brand> { readonly [__opaque__]: Brand }

/**
 * Raw TL object. May or may not be backed by a real Java `TLObject`
 */
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

/**
 * Releases the registration or resource that returned it. Repeated calls are safe.
 * Remaining resources are disposed on unload.
 *
 * Implements `Disposable`, so it works with `using` and `DisposableStack`.
 */
declare type Disposer = (() => void) & Disposable

declare type PeerLikeObject
  = | tl.TypePeer | tl.TypeInputPeer | tl.TypeInputUser | tl.TypeInputChannel
    | tl.TypeUser | tl.TypeChat

/**
 * A peer, represented by one of:
 * - marked peer id (like in Bot API/mtcute)
 * - `'me'`/`'self'`
 * - a username (optionally with `@`)
 * - a TL object representing a peer.
 *
 * A marked peer id follows the Bot API's scheme:
 * - a user id as-is (e.g. `123456`)
 * - a basic group id, negated (e.g. `-123123`)
 * - a channel or supergroup id, subtracted from `-1000000000000` (e.g. `-1000000123123`)
 *
 * Note that the Android app itself follows a simpler schema,
 * both chats and channels are simply negated. Use {@link toSimpleDialogId} to convert.
 */
declare type InputPeerLike = number | PeerLikeObject | 'me' | 'self' | (string & {})

/** Formatted text representation */
declare interface TextWithEntities {
  text: string
  entities?: tl.TypeMessageEntity[]
}

declare type InputText = string | TextWithEntities

declare namespace inu {
  /** An RPC error, returned by Telegram server */
  class RpcError extends Error {
    constructor(code: number, text: string)
    code: number
    text: string
  }

  /** A plugin error, thrown by plugin APIs. An invalid argument (a wrong type or value) is a plain `TypeError` instead */
  class PluginError extends Error {
    /**
     *  Error code:
     * - `not-granted`: You are trying to use an API you haven't specified a `@grant` for
     * - `forbidden`: You are trying to access a protected resource
     * - `quota-exceeded`: You are trying to use more resources than the quota allows (fs, timers, native memory, actions, etc.)
     * - `handle-expired`: You are trying to read data from an expired/disposed handle.
     * - `unknown-constructor`: You are trying to use an unknown TL constructor
     * - `not-found`: You are trying to access something that doesn't exist
     * - `unsupported`: You are trying to use an unsupported API
     * - `timed-out`: Your operation has timed out
     * - `aborted`: You operation was aborted
     * - `network`: Some network-related issue happened
     * - `internal`: An internal plugin engine error happened, please report this
     */
    code:
      | 'not-granted'
      | 'forbidden'
      | 'quota-exceeded'
      | 'handle-expired'
      | 'unknown-constructor'
      | 'not-found'
      | 'unsupported'
      | 'timed-out'
      | 'aborted'
      | 'network'
      | 'internal'
      | (string & {})

    /** If `code == 'not-granted'`, the grant you're missing */
    grant?: string
    /** If `code == `quota-exceeded'`, the current quota usage */
    usage?: number
    /** If `code == `quota-exceeded'`, the current quota limit */
    quota?: number
  }

  /** Get information about the current environment */
  function info(): {
    /** Platform we're running on (currently only Android) */
    platform: 'android' | (string & {})
    /** Application version (display string) */
    appVersion: string
    /** Application build number */
    appBuild: string
    /** Supported plugin API version */
    apiVersion: number
    /** Current TL layer */
    layer: number
    /** User's preferred language */
    language: string
    /** The manifest header directives, one array entry per repeated key */
    header: Record<string, string[]>
  }

  /**
   * Register a handler that will run when the plugin is unloaded
   *
   * Handlers run in registration order and share one cleanup window.
   * Resources remain available until settlement/timeout. Ordinary callbacks and timers stop first.
   * JVM runnables created during cleanup can run on the UI thread; existing callbacks stay stopped.
   *
   * **Limits: 2 seconds total for async cleanup.**
   */
  function onUnload(callback: () => void | Promise<void>): Disposer

  /**
   * Open a web URL, or an internal deeplink (t.me/telegram.org)
   *
   * @needs-grant openUrl
   */
  function openUrl(url: string): void

  /**
   * Subscribe to app visibility changes.
   *
   * Possible events:
   * - `'foreground'` - the app was moved to the foreground
   * - `'background'` - the app was backgrounded (e.g. user switched to another app)
   * - `'paused'` - the main activity was paused by the system (e.g. permission dialogs/file picker/etc)
   * - `'resumed'` - the main activity was resumed
   *
   * @needs-grant onAppVisibilityChange
   */
  function onAppVisibilityChange(
    callback: (mode: 'foreground' | 'resumed' | 'paused' | 'background') => void,
  ): Disposer

  /** Access to the system clipboard */
  namespace clipboard {
    /**
     * Write something into the clipboard
     *
     * @needs-grant clipboard.write
     */
    function write(text: string): void
    /**
     * Read the clipboard value
     *
     * @needs-grant clipboard.read
     */
    function read(): string
  }

  /** Type of a message's media, see {@link Message.mediaType} */
  type MediaType
    = | 'photo' | 'video' | 'roundVideo' | 'voice' | 'music' | 'sticker' | 'gif' | 'document'
      | 'poll' | 'contact' | 'location' | 'venue' | 'story' | 'giveaway' | 'invoice' | 'other'

  /** A high-level wrapper over a TL Message */
  class Message {
    constructor(raw: tl.TypeMessage)

    /** Raw TL object of the message */
    readonly raw: tl.TypeMessage
    /** Whether the message is a service message */
    get isService(): boolean

    /** Message ID */
    get id(): number
    /** If the message belongs to an album, ID of the album */
    get groupedId(): string | null

    /** ID of the dialog where the message was sent */
    get dialogId(): number | null
    /** ID of the topic where the message belongs */
    get topicId(): number | null
    /** ID of the message sender */
    get senderId(): number | null
    /** Date of the message, as a unix date in seconds */
    get date(): number
    /** Date when the message was edited, as a unix date in seconds */
    get editDate(): number | null
    /** Whether this message is outgoing */
    get out(): boolean
    /** Plain text of the message */
    get text(): string
    /** Formatted text of the message */
    get textWithEntities(): TextWithEntities

    /** Media inside the message, if any */
    get media(): tl.TypeMessageMedia | null
    /** Document inside the message, if any */
    get document(): tl.TypeDocument | null

    /** Type of the media in the message, if any */
    get mediaType(): MediaType | null

    /** For playable media types, duration of the media */
    get duration(): number | null

    /** If the message is a reply to another message, ID of that message */
    get replyToMessageId(): number | null
    /** If the message is a forward, info about that */
    get forwardedFrom(): tl.TypeMessageFwdHeader | null
    /** If the message was sent via an inline bot, ID of that bot */
    get viaBotId(): number | null
    /** Whether the message is pinned */
    get isPinned(): boolean
    /** View count of the message, if available */
    get views(): number | null
    /** Forwards count of the message, if available */
    get forwards(): number | null
    /** Reactions on the message, if any */
    get reactions(): tl.TypeMessageReactions | null

    toJSON(): tl.TypeMessage
  }

  /** A chat folder as shown in the folder tabs. */
  interface ChatFolder {
    /** `0` is the "All chats" tab, which every account has and which cannot be edited. */
    id: number
    title: TextWithEntities
    /** The folder's emoji, or `null` if unset. */
    emoticon: string | null
    /** Telegram's folder colour, `0`-`7` into its own palette - not an ARGB value. `null` when the folder has no colour. */
    colorIndex: number | null
    unreadCount: number
    /** The number of dialogs {@link Account.getDialogsCached} currently returns for this folder. */
    dialogCount: number
    isDefault: boolean
    /** A shared folder, added from an invite link. */
    isChatlist: boolean
    /** The folder's pinned dialogs, in the order they are pinned. */
    pinned: number[]
  }

  /**
   * Get the logged-in accounts, in the order the user sees them in the app
   */
  function accounts(): Account[]

  /**
   * Register on updates for when the account list updates, including when the user reorders it
   */
  function onAccountsChanged(callback: (accounts: Account[]) => void): Disposer

  /**
   * Runs `callback` for the current account, and again on each account switch.
   *
   * When a function/Disposable is returned, it's called whenever the account changes,
   * or the returned `Disposer` is disposed
   */
  function withCurrentAccount(callback: (account: Account) => (() => void) | Disposable | void): Disposer

  /**
   * The account in slot `id` ({@link Account.id}), or the current account when `id` is omitted.
   * Throws `not-found` when no account is logged in there.
   */
  function account(id?: number): Account

  /**
   * An opaque cursor for paginated lists
   *
   * **Limits: 32 live cursors.**
   */
  type Cursor<List extends string> = OpaqueType<`Cursor:${List}`> & string

  /** Wrapper for paginated list return types */
  type Paged<T, List extends string> = T[] & { next: Cursor<List> | null }

  /** Information about an account in the app */
  interface Account {
    /** opaque slot of the account */
    readonly id: number

    /** id of the user this account represents */
    readonly userId: number

    /** whether this is the currently active account */
    isCurrent(): boolean

    /**
     * whether this account has Telegram Premium active
     *
     * @needs-grant account.read(self)
     */
    isPremium(): boolean

    /**
     * Suppresses app notifications for this account until the returned {@link Disposer} runs
     * or the plugin unloads. Suppression remains active while any hold exists.
     * App-wide suppression also applies to this account.
     *
     * @needs-grant notifications.suppress
     */
    suppressNotifications(): Disposer

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
     * Whether the app considers this dialog muted. Combines account defaults, dialog overrides,
     * and topic overrides when a topic is specified. Unknown dialogs are not muted.
     *
     * @needs-grant account.read(dialogs)
     */
    isDialogMuted(peer: InputPeerLike, options?: { topicId?: number }): boolean

    /**
     * The app's message preview: message text, a media label such as "📷 Photo", or a service
     * message in the app's language. Entities describe the preview, not the original message.
     *
     * `hideSpoilers` masks spoilers and removes entities covering masked text.
     * Media labels are unchanged.
     *
     * @needs-grant account.read(messages)
     */
    previewMessage(message: Message | tl.TypeMessage, options?: { hideSpoilers?: boolean }): TextWithEntities

    /**
     * Returns a cached forum topic, or `null` if missing or the peer is not a forum.
     * Never uses the network; use {@link getTopics} to fetch topics.
     *
     * @needs-grant account.read(dialogs)
     */
    getTopicCached(peer: InputPeerLike, topicId: number): tl.TypeForumTopic | null

    /**
     * get one or more users (cached, this method never does a network call, and returns `null` on miss)
     *
     * @needs-grant account.read(peers)
     */
    getUsers(peers: InputPeerLike[]): (tl.TypeUser | null)[]
    /**
     * get one or more chats (cached, this method never does a network call, and returns `null` on miss)
     * @needs-grant account.read(peers)
     */
    getChats(peers: InputPeerLike[]): (tl.TypeChat | null)[]

    /**
     * Reads locally stored messages without using the network. Returns `null` for missing messages.
     * May block on a database read; use {@link getHistory} to read a range instead of looping.
     *
     * @needs-grant account.read(messages)
     * @param peer the dialog, or `0` for the common message box (DMs, legacy groups)
     * @param messageId the message to get
     */
    getMessagesCached(peer: InputPeerLike, messageId: number): Message | null
    getMessagesCached(peer: InputPeerLike, messageIds: number[]): (Message | null)[]

    /**
     * get one or more messages, *always* fetching them from the server
     *
     * @needs-grant account.read(messages)
     * @param peer the dialog, or `0` for the common message box (DMs, legacy groups)
     * @param messageId the message to get
     */
    getMessages(peer: InputPeerLike, messageId: number): Promise<Message | null>
    getMessages(peer: InputPeerLike, messageIds: number[]): Promise<(Message | null)[]>

    /**
     * get an app-owned file representing the attachment of a message
     *
     * @needs-grant account.read(messages)
     *
     * @experimental Subject to breaking changes between releases.
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
     *
     * @experimental Subject to breaking changes between releases.
     */
    downloadMediaToFile(message: Message | tl.TypeMessage, options?: {
      onProgress?: ProgressCallback
    }): Promise<{ path: string }>

    /**
     * `{ path }` names a file the way {@link fs} does, and needs its grant.
     *
     * **Limits: 256 MB per staged copy, for all write operations.**
     *
     * @needs-grant account.write(send)
     */
    uploadFile(file: Blob | Uint8Array | { path: string }, options?: {
      fileName?: string
      onProgress?: ProgressCallback
    }): Promise<tl.TypeInputFile>

    /**
     * Stages a file for an outgoing message's {@link OutgoingMessage.media}, where the app processes
     * and uploads it like a file the user picked. Expires 10 minutes after it was created if no send
     * takes it.
     *
     * `{ path }` names a file the way {@link fs} does, and needs its grant. Keep it available for
     * upload and retries after the send.
     *
     * **Limits: 256 MB per staged copy, for all write operations.**
     *
     * @needs-grant account.write(send)
     *
     * @experimental Subject to breaking changes between releases.
     */
    createLocalMedia(file: Blob | Uint8Array | { path: string }, options?: {
      /** customize the file name */
      fileName?: string
      /** send an image or video as a file rather than recompressing it */
      asDocument?: boolean
    }): Promise<LocalMedia>

    /** @needs-grant account.read(peers) */
    getUserFull(peer: InputPeerLike): Promise<tl.TypeUserFull | null>
    /** @needs-grant account.read(peers) */
    getChatFull(peer: InputPeerLike): Promise<tl.TypeChatFull | null>

    /**
     * `fields` preloads selected fields for faster reads, as in {@link getDialogsCached}.
     *
     * @needs-grant account.read(dialogs)
     */
    getDialogs(options?: {
      folderId?: number
      limit?: number
      fields?: readonly string[]
      cursor?: Cursor<'dialogs'>
    }): Promise<Paged<tl.TypeDialog, 'dialogs'>>
    /**
     * @needs-grant account.read(dialogs). `batchSize` defaults to Telegram's page size of 100.
     * `fields` applies to every page.
     */
    iterDialogs(options?: {
      folderId?: number
      limit?: number
      batchSize?: number
      fields?: readonly string[]
    }): AsyncIterableIterator<tl.TypeDialog>

    /**
     * Returns all cached dialogs in chat-list order: pinned first, then by date.
     * Does not use the network or paginate. Resolves on the next turn; unavailable in synchronous
     * contexts such as an `inu.xposed` phase.
     *
     * `archive` selects the main list (`'exclude'`, the default), the archive (`'only'`), or both
     * (`'keep'`). `chatFolderId` selects a folder from {@link getChatFoldersCached} instead.
     * Specifying both options throws a `TypeError`; folders have their own archive rules.
     *
     * `fields` preloads selected fields for faster reads. Other fields remain readable.
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
     * Returns cached chat folders in tab order. Asynchronous, like {@link getDialogsCached}.
     *
     * @needs-grant account.read(dialogs)
     */
    getChatFoldersCached(): Promise<ChatFolder[]>

    /**
     * Get chat history in a specific dialog,
     * preferring data from the local cache.
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
        topicId?: number

        /** skip the cache and ask the server */
        force?: boolean
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

        /** skip the cache and ask the server */
        force?: boolean
      },
    ): AsyncIterableIterator<Message>

    /**
     * Get topics in a specific forum
     *
     * @needs-grant account.read(dialogs)
     */
    getTopics(peer: InputPeerLike, options?: {
      limit?: number
      cursor?: Cursor<'topics'>
    }): Promise<Paged<tl.TypeForumTopic, 'topics'>>
    /** @needs-grant account.read(dialogs) */
    iterTopics(peer: InputPeerLike, options?: {
      limit?: number
      batchSize?: number
    }): AsyncIterableIterator<tl.TypeForumTopic>

    /**
     * Resolve an `InputPeer` from `InputPeerLike`
     *
     * @needs-grant account.read(peers)
     */
    resolvePeer(peer: InputPeerLike): Promise<tl.TypeInputPeer>
    /**
     * Resolve an `InputPeer` from `InputPeerLike`, without hitting the network, returning `null` on miss
     *
     * @needs-grant account.read(peers)
     */
    resolvePeerCached(peer: InputPeerLike): tl.TypeInputPeer | null
    /**
     * Resolve an `InputUser` from `InputPeerLike`
     *
     * @needs-grant account.read(peers)
     * @throws if the peer is not a user
     */
    resolveUser(peer: InputPeerLike): Promise<tl.TypeInputUser>
    /**
     * Resolve an `InputChannel` from `InputPeerLike`
     *
     * @needs-grant account.read(peers)
     * @throws if the peer is not a channel
     */
    resolveChannel(peer: InputPeerLike): Promise<tl.TypeInputChannel>

    /**
     * **Limits: 8 resolutions in flight.**
     *
     * @needs-grant account.read(peers)
     */
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
       *
       * @experimental Subject to breaking changes between releases.
       */
      optimistic?: boolean
    }): Promise<Message>

    /**
     * send a message containing a media
     *
     * @needs-grant account.write(send)
     * @param peer the dialog
     * @param file the media file. `{ path }` names a file the way {@link fs} does, and needs its grant
     *
     * @experimental Subject to breaking changes between releases.
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
         *
         * @experimental Subject to breaking changes between releases.
         */
        optimistic?: boolean
      }): Promise<Message>

    /**
     * send an album
     *
     * @needs-grant account.write(send)
     * @param peer the dialog
     * @param items the media files
     *
     * @experimental Subject to breaking changes between releases.
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
     * Opens a takeout session with higher rate limits for history reads.
     * Call {@link TakeoutSession.finish} when done; Telegram keeps the session until then.
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

  /**
   * A tagged template that returns text and entities, for example:
   * ```ts
   * md`**hi**`
   * ```.
   *
   * Interpolated values are not parsed: strings are inserted as text, numbers and bigints as
   * digits, and `TextWithEntities` values keep their entities. `boolean`, `NaN` and `Infinity` are omitted
   *
   * Offsets use UTF-16 code units.
   */
  interface TextFormat {
    (strings: TemplateStringsArray, ...values: (InputText | string | number | bigint | boolean | null | undefined)[]): TextWithEntities
    /** Parses a string in this format, such as text received from a server. */
    (text: string): TextWithEntities
    /**
     * Escapes text so parsing returns it unchanged. Interpolated strings are already escaped.
     * `quote` also escapes `"` for HTML attribute values.
     */
    escape(text: string, quote?: boolean): string
    /** Formats text and its entities using this format. */
    unparse(input: InputText): string
  }

  namespace utils {
    /**
     * mtcute's markdown:
     * - `**bold**`
     * - `__italic__`
     * - `--underline--`
     * - `~~strike~~`
     * - `||spoiler||`
     * - `` `code` ``
     * - ```` ```pre ````
     * - `[text](url)`
     * - `> quote`.
     */
    const md: TextFormat
    /** telegram's html subset. Whitespace collapses as it does in real html; `<br>` breaks a line. */
    const html: TextFormat
    /** {@link html}, but whitespace is kept as written and the template is dedented first, like Bot API */
    const thtml: TextFormat

    /**
     * Joins text parts and adjusts their entity offsets:
     *
     * ```ts
     * const board = inu.utils.joinTextWithEntities(
     *   scores.map(entry => md`**${entry.name}**: ${entry.score}`),
     *   '\n',
     * )
     * ```
     *
     * The delimiter is inserted only after nonempty output, so leading empty parts add no delimiter.
     */
    function joinTextWithEntities(parts: InputText[], delim?: InputText): TextWithEntities

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
      /** get the marked peer id of a peer object */
      function getMarkedPeerId(peer: PeerLikeObject): number
      /** parse a marked peer id into its peer type and bare id */
      function parseMarkedPeerId(id: number | string): {
        type: 'user' | 'chat' | 'channel'
        id: number
      }
      /** convert a user or chat object to an input peer */
      function toInputPeer(userOrChat: tl.TypeUser | tl.TypeChat): tl.TypeInputPeer
      /** convert a marked peer id or a peer object to the app's own Java dialog id, which negates channel ids like basic groups */
      function toSimpleDialogId(peer: number | PeerLikeObject): number
    }
  }

  /** An opaque reference to a UI element */
  type UIElement = OpaqueType<'UIElement'>
  /** An opaque reference to an icon used by UI elements */
  type UIIcon = OpaqueType<'UIIcon'>

  namespace icons {
    interface LottieOptions {
      /** `true` loops forever; a number repeats that many times after the initial play. */
      loop?: boolean | number
      /** whether to force render this as a static image (i.e. render just the first frame for animated stickers) */
      static?: boolean
    }

    /** {@link UIIcon} from a built-in common animation */
    function animation(name: 'success' | 'error' | 'info' | 'loading'): UIIcon

    /** {@link UIIcon} from a Telegram custom emoji (`id` is the custom emoji ID as a string) */
    function customEmoji(id: string, options?: LottieOptions): UIIcon

    type StickerOptions = { slug: string } & LottieOptions & (
      | { index: number, emoji?: never, id?: never }
      | { emoji: string, index?: never, id?: never }
      | { id: string, index?: never, emoji?: never }
    )

    /**
     * {@link UIIcon} from a Telegram sticker.
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

    /**
     * {@link UIIcon} with a peer's profile photo (or initials placeholder)
     *
     * @param dialogId Marked peer ID of the peer to show the avatar for
     * @param options
     * @param options.account Acocunt slot to use when loading the avatar
     */
    function avatar(dialogId: number, options?: { account?: number }): UIIcon

    /** {@link UIIcon} from a built-in common icon */
    function common(
      name:
        | 'settings' | 'info' | 'search' | 'edit' | 'delete' | 'copy' | 'share' | 'download'
        | 'link' | 'pin' | 'star' | 'mute' | 'unmute' | 'archive' | 'forward' | 'reply'
        | 'user' | 'group' | 'channel' | 'bot' | 'lock' | 'eye' | 'eyeOff' | 'refresh'
        | 'plus' | 'minus' | 'check' | 'close' | 'more' | 'translate' | 'bookmark',
    ): UIIcon

    /**
     * {@link UIIcon} from a custom SVG.
     *
     * **Limits: 64 KiB of SVG source.**
     */
    function svg(source: string): UIIcon
  }

  /**
   * **Limits: 3 dialogs, prompts, choosers and file pickers open at once, across every plugin; one more
   * rejects with `quota-exceeded`. Bulletins and toasts do not count.**
   */
  namespace ui {
    interface UIPage {
      /** Refresh the page by calling its callback again */
      invalidate(): void
      /** Dispose the page */
      dispose(): void
    }

    /**
     * Target for {@link openPage}
     * - `chat`: open a specific dialog (and optionally topic)
     * - `profile`: open a specific user/channel's profile
     * - `dialogs`: open a list of dialogs
     * - `settings`: open the settings page
     */
    type PageTarget
      = | { type: 'chat', dialogId: number, topicId?: number, account?: number }
        | { type: 'profile', dialogId: number, account?: number }
        | { type: 'dialogs', account?: number }
        | { type: 'settings', account?: number }

    /** open a page defined by the plugin */
    function openPage(page: UIPage): void
    /** open a stock commonly used page */
    function openPage(screen: PageTarget): void

    /** @experimental Subject to breaking changes between releases. */
    interface ChatHistoryMenuItem {
      text: InputText
      icon?: UIIcon
      /** red, like stock's Delete */
      danger?: boolean
      /** draws a checkbox in this state; omitted, the row has none */
      checked?: boolean
      onClick(): void
    }

    /**
     * A single entry in a custom chat history page
     *
     * @experimental Subject to breaking changes between releases.
     */
    interface ChatHistoryEntry {
      /** Message represented by this entry */
      message: tl.TypeMessage
      /**
       * Identity of this entry on the screen
       *
       * Required when the same message appears more than once
       *
       * @default `${peer}:${id}`
       */
      key?: string
      /**
       * Whether the message is "synthetic", i.e. not backed by a real server message.
       *
       * Defaults to the screen's `synthetic` option.
       *
       * Synthetic messages lose some of the menu actions a real message would have
       */
      synthetic?: boolean
    }

    /** @experimental Subject to breaking changes between releases. */
    interface ChatHistoryPage {
      /** Items in the history, newest first, max 200 */
      entries: ChatHistoryEntry[]
      /** Users the messages reference that the app may not have cached yet */
      users?: tl.TypeUser[]
      /** Chats the messages reference that the app may not have cached yet */
      chats?: tl.TypeChat[]
      /**
       * cursor for the next page **in the requested direction**
       *
       * Omit when we have reached the end in this direction
       */
      next?: string
      /**
       * **Only read from the first page**
       *
       * A cursor for the pages newer than it.
       * When omitted, the screen starts at the bottom and never asks for `'newer'`.
       */
      newer?: string
      /**
       * **Only read from the first page**
       *
       * The key of the first unread entry on the page, to show the "Unread messages" line
       */
      firstUnread?: string
      /**
       * The number of "unread" messages, to be shown on the jump-to-bottom button
       *
       * When omitted, the value stays the same
       */
      unreadCount?: number
    }

    /** @experimental Subject to breaking changes between releases. */
    interface ChatHistory {
      /**
       * Add items to the page content
       *
       * Newest first, existing keys are skipped
       */
      append(page: Omit<ChatHistoryPage, 'next' | 'newer' | 'firstUnread'>): void
      /**
       * Replace some of the items on the page
       *
       * Matched by key, unknown keys are skipped
       */
      replace(page: Omit<ChatHistoryPage, 'next' | 'newer' | 'firstUnread'>): void
      /** Remove items from the page by their keys */
      remove(keys: string[]): void
      /**
       * Manually update the number on the jump-to-bottom button
       *
       * Normally this value is updated from `load`-ed pages and `append`/`replace` via `unreadCount`.
       */
      setUnreadCount(count: number): void
      /**
       * Updates the bottom button, or hides it with `null`
       *
       * @throws if the button was not declared
       */
      setButton(button: { text: InputText } | null): void
      /** Close the screen */
      close(): void
      /** A promise that resolves when the user leaves the screen */
      readonly closed: Promise<void>
    }

    /**
     * Open a chat screen, with the data supplied by the pluggin.
     *
     * Supports infinite scrolling in both directions
     *
     * Every item in the dataset gets its own ID, so the same server message can appear more than once
     *
     * @throws `not-found` when `account` is not logged in.
     *
     * @experimental Subject to breaking changes between releases.
     */
    function openChatHistory(options: {
      /** Title of the page */
      title: InputText
      /** Subtitle of the page */
      subtitle?: InputText
      /**
       * Icon (or rather, "avatar") of the page, shown in the header next to the title.
       */
      icon?: UIIcon
      /** Account slot to use for the page */
      account?: number
      /**
       * Default for each entry's `synthetic`.
       *
       * Synthetic items are *not* backed by a real server message, and thus do not have
       * some of the menu actions a real message would have.
       *
       * Synthetic messages still need `peer_id`, `from_id`, `date` and `out`
       * to be drawn correctly.
       *
       * @default false
       */
      synthetic?: boolean
      /**
       * Whether to draw peer avatars and names next to the message,
       * as if it was a group (or a "show authors profile" channel)
       *
       * @default false
       */
      avatars?: boolean
      /**
       * The data provider for the chat page
       *
       * It is called with a cursor (anchor) to load from, and a direction to advance:
       * - `(null, 'older')`: called when the page is opened, "where should we start?" request
       * - `(null, 'newer')`: the newest page, called when the user jumps to the bottom
       * - `(cursor, direction)`: the regular call, requesting a page after a `next` (or the first page's `newer`) cursor.
       *
       * An exception ends that direction.
       */
      load(cursor: string | null, direction: 'older' | 'newer'): MaybePromise<ChatHistoryPage>
      /**
       * Called when a user advances downwards and "reads" a new message,
       * with the `key` of the newest entry the user has on the screen
       */
      onRead?(key: string): void
      /**
       * A button on the bottom of the screen (where a Mute/Join button would reside)
       *
       * Without `text` it starts hidden until `setButton`
       */
      button?: { text?: InputText, onClick(): void }
      /**
       * Customizable overflow menu items, shown as a three-dot dropdown in the header.
       */
      menu?(): MaybePromise<ChatHistoryMenuItem[]>
    }): ChatHistory

    /**
     * Displays a stack of peer avatars in place of a bulletin's icon.
     * Skips peers unknown to the app.
     */
    interface BulletinAvatars {
      type: 'avatars'
      /** 1 to 3 peers */
      avatars: number[]
      /** which account they are looked up in; defaults to the active one */
      account?: number
    }

    /** show a toast */
    function toast(text: string): void
    /**
     * Shows a bulletin (snackbar). Resolves with `'button'` when its button is tapped,
     * `'clicked'` when its body is tapped, or `'dismissed'` when it closes otherwise.
     *
     * You can ignore the promise if you do not need the result.
     */
    function bulletin(options: {
      /** The text, or the title when there is a `subtitle` */
      text: InputText
      /** Subtitle for the bulletin */
      subtitle?: InputText
      icon: UIIcon | BulletinAvatars
      /**
       * `'short'` (1.5s), `'long'` (2.75s, the default), or a duration in milliseconds.
       *
       * **Limits: 500–30000 ms for numeric durations.**
       */
      duration?: 'short' | 'long' | number
      /** Placement of the bulletin */
      position?: 'top' | 'bottom'
      /** label of a trailing button; tapping it resolves with `'button'` */
      button?: string
    }): Promise<'clicked' | 'button' | 'dismissed'>

    /**
     * show a dialog
     *
     * returns a promise that resolves to the result of the dialog once it's dismissed,
     * with either the name of the button pressed, or `'dismissed'` if the dialog was dismissed
     * (e.g. by tapping outside of it, or by pressing the back button).
     */
    function dialog(options: {
      /** title of the dialog */
      title?: InputText
      /** message of the dialog */
      message?: InputText
      /** custom body of the dialog */
      body?: UIElement
      /** text of the positive button, `undefined` to hide */
      positive?: string
      /** text of the negative button, `undefined` to hide */
      negative?: string
      /** text of the neutral button, `undefined` to hide */
      neutral?: string
    }): Promise<'positive' | 'negative' | 'neutral' | 'dismissed'>

    /**
     * show a list of choices; tapping one only changes the selection, and the OK button submits it
     *
     * resolves to the selected index (or a list of indices with `multiple`),
     * or `null` if the chooser was cancelled or dismissed.
     * without `multiple`, OK stays disabled until something is selected
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

    /** Select users, groups or channels. Resolves to marked peer IDs, or null on cancellation. */
    function selectPeers(options: {
      title?: string
      /** Account slot; defaults to the currently selected account. */
      account?: number
      /** Pre-filled marked peer IDs. Peers must be cached in the chosen account. */
      selected?: number[]
      /** Allowed peer types; defaults to all. Groups include basic groups and supergroups. Must not be empty; pre-fill must match. */
      peerType?: ('group' | 'broadcast' | 'user')[]
      /** Positive maximum selection size. Omitted means unlimited. Pre-fill must fit. */
      limit?: number
      /** Allow submitting an empty selection. Defaults to true. */
      allowEmpty?: boolean
    }): Promise<number[] | null>

    /**
     * Info about the currently visible screen
     *
     * @experimental Subject to breaking changes between releases.
     */
    interface CurrentScreen {
      type: 'chat' | 'profile' | 'dialogs' | 'settings' | 'other'
      /** @needs-grant account.read(dialogs) */
      dialogId?: number
      /** @needs-grant account.read(dialogs) */
      topicId?: number
      account: Account
    }

    /**
     * Get the currently visible screen
     *
     * @experimental Subject to breaking changes between releases.
     */
    function getCurrentScreen(): CurrentScreen | null

    /**
     * Info about a navigation event
     *
     * @experimental Subject to breaking changes between releases.
     */
    interface ScreenChange {
      screen: CurrentScreen | null
      previous: CurrentScreen | null
      action: 'push' | 'pop' | 'replace'
      readonly stack: CurrentScreen[]
    }

    /**
     * Subscribe to navigation changes
     *
     * @experimental Subject to breaking changes between releases.
     */
    function onScreenChanged(callback: (change: ScreenChange) => void): Disposer

    /** Prompt a user for some text input */
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
     * @needs-grant fs to name a file
     * @param content the file to save
     * @returns `true` if the user chose to save the file, `false` if they chose not to
     */
    function saveFile(content: Blob | Uint8Array | { path: string }, options?: {
      /** recommended file name */
      fileName?: string
      /** mime type */
      type?: string
    }): Promise<boolean>

    /** Anchor for the menus */
    interface UIAnchor {
      /** Open a contextual menu */
      openMenu(items: {
        /** Text of the item */
        text: string
        /** Whether the item should have a checkmark */
        checked?: boolean
        /** Whether the item should be shown as "dangerous" */
        danger?: boolean
        /** Click handler */
        onClick: () => void
      }[]): void
    }

    /** Header element */
    function header(text: string): UIElement

    /** Check (switch) element */
    function check(options: {
      /** Stable ID of the element */
      id?: string
      /** Label for the switch */
      text: string
      /** Subtitle for the switch */
      subtitle?: string
      /** Icon shown next to the switch */
      icon?: UIIcon
      /** Current switch status */
      checked: boolean
      /** Switch change (click) handler */
      onChange: (checked: boolean, anchor: UIAnchor) => void
      /** Secondary tap (long tap) handler */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /** Button element */
    function button(options: {
      /** Stable ID of the element */
      id?: string
      /** Button label */
      text: InputText
      subtitle?: InputText
      /** Icon shown in the button */
      icon?: UIIcon
      /** "Value" of the button */
      value?: InputText
      /** Whether the button is "dangerous" */
      danger?: boolean
      /** Click handler */
      onClick: (anchor: UIAnchor) => void
      /** Secondary click (long tap) handler */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /** Select element. A button showing a selector on tap */
    function select(options: {
      /** Stable ID of the element */
      id?: string
      /** Label for the button */
      text: InputText
      /** Icon shown in the button */
      icon?: UIIcon
      /** Select items */
      items: (string | { text: string, subtitle?: string })[]
      /** Index of the selected item */
      selected: number
      /**
       * Whether the selector should be a dialog
       *
       * @default `true` if any of `items` has a `subtitle`, otherwise `false`
       */
      dialog?: boolean
      /** Value change handler */
      onChange: (index: number, anchor: UIAnchor) => void
      /** Secondary click (long tap) handler */
      onSecondaryClick?: (anchor: UIAnchor) => void
    }): UIElement

    /**
     * Slider element
     *
     * **Limits: 501 label steps.**
     */
    function slider(options: {
      /** Stable ID of the element */
      id?: string
      /** Header label for the slider */
      text?: string
      /** Minimum value */
      min: number
      /** Maximum value */
      max: number
      /** Step of the slider */
      step: number
      /** Current value of the slider */
      value: number
      /** Default value for the slider */
      default?: number
      /**
       * Value label renderer
       *
       * @default val => String(val)
       */
      label?: (value: number) => string
      /** Value change listener */
      onChange: (value: number, anchor: UIAnchor) => void
    }): UIElement

    /** A separator element, with optional text */
    function separator(text?: InputText): UIElement

    /** Define a declarative settings page */
    function settingsPage(options: {
      /** Page title */
      title: string
      /**
       * Disposes the page when it closes, after {@link onClose} returns.
       * Use for pages created on each open. Reopening a disposed page throws `handle-expired`.
       */
      transient?: boolean
      /**
       * Items rendered by the page
       *
       * Whenever the return value is expected to change, call {@link UIPage.invalidate}
       */
      items: () => UIElement[]
      /** Bottom CTA button on the page */
      bottomButton?: {
        /** Text of the button */
        text: string
        /** Click handler for the CTA */
        onClick: (anchor: UIAnchor) => void
      }
      /** Close handler for the page */
      onClose?: () => void
    }): UIPage
  }

  /** Register a UIPage as a plugin settings page */
  function registerSettings(page: ui.UIPage): Disposer

  /**
   * invoke a raw mtproto rpc method via the currently active account
   *
   * @needs-grant invokeRpc
   */
  function invokeRpc<T extends tl.TypeRpcMethod>(params: T): Promise<tl.RpcCallReturn[T['_']] | null>

  /**
   * invoke a raw mtproto rpc method via the currently active account,
   * given its TL serialization, and return the TL serialization of the server's response
   *
   * ⚠️ this method is primarily intended for **advanced users**, primarily
   * for cases where you want to call a method that is not yet supported by the current
   * app's layer. beware that can cause the app to crash or otherwise misbehave,
   * due to the server "bumping" the layer. in almost all cases, you should use
   * {@link invokeRpc} instead.
   *
   * @needs-grant unsafe.invokeRaw
   */
  function invokeRaw(method: Uint8Array): Promise<Uint8Array | null>

  /** Context of the RPC middleware */
  interface RpcMiddlewareContext<M extends tl.TypeRpcMethod['_']> {
    /** Original request */
    request: Extract<tl.TypeRpcMethod, { _: M }>
    /** Account used for the request */
    account: Account
    /**
     * Abort signal of the request, aborted if the app cancels the request
     */
    readonly signal: AbortSignal
  }

  /** `next()` without a request forwards `context.request`, including any changes made to it */
  type RpcNext<M extends tl.TypeRpcMethod['_'], R> = (request?: Extract<tl.TypeRpcMethod, { _: M }>) => MaybePromise<R | null>

  /**
   * Register an RPC interceptor
   *
   * @needs-grant interceptRpc
   */
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    method: M,
    middleware: (
      context: RpcMiddlewareContext<M>,
      next: RpcNext<M, tl.RpcCallReturn[M]>,
    ) => MaybePromise<tl.RpcCallReturn[M] | null | undefined>,
    options?: InterceptRpcOptions,
  ): Disposer
  function interceptRpc<M extends tl.TypeRpcMethod['_']>(
    methods: M[],
    middleware: (
      context: RpcMiddlewareContext<M>,
      next: RpcNext<M, SharedRpcReturn<M>>,
    ) => MaybePromise<SharedRpcReturn<M> | null | undefined>,
    options?: InterceptRpcOptions,
  ): Disposer

  /**
   * Register a new message handler
   *
   * Receives every `updateNewMessage` and `updateNewChannelMessage` for every account.
   * Runs before the app applies the update, so chat state may not reflect it yet.
   * Local and scheduled messages are not included.
   *
   * @needs-grant onUpdate(new_message)
   */
  function onNewMessage(callback: (message: Message, account: Account) => void): Disposer
  /**
   * Register a message edit handler
   *
   * @needs-grant onUpdate(edit_message)
   */
  function onMessageEdited(callback: (message: Message, account: Account) => void): Disposer
  /**
   * Register a message deletion handler
   *
   * @needs-grant onUpdate(delete_message)
   */
  function onMessageDeleted(
    callback: (dialogId: number | null, messageIds: number[], account: Account) => void,
  ): Disposer

  namespace notifications {
    /**
     * Suppresses all app notifications for all accounts and dismisses existing notifications.
     * Suppression lasts until the returned {@link Disposer} runs or the plugin unloads.
     * Multiple holds can coexist; notifications resume only when all holds are released.
     * See {@link Account.suppressNotifications} to suppress one account's notifications.
     *
     * @needs-grant notifications.suppress
     */
    function suppress(): Disposer
  }

  /**
   * Register a handler for raw TL updates
   *
   * @needs-grant onUpdate
   */
  function onUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    callback: (update: Extract<tl.TypeUpdate, { _: U }>, account: Account) => void,
  ): Disposer

  interface UpdateMiddlewareContext<U extends tl.TypeUpdate['_']> {
    update: Extract<tl.TypeUpdate, { _: U }>
    account: Account
    /**
     * Aborted once the update is delivered without waiting for this stage: its budget ran out or the
     * plugin stopped. `reason` is a {@link PluginError} coded `aborted` or `timed-out`.
     */
    readonly signal: AbortSignal
  }

  /**
   * Register an app-wide update interceptor
   *
   * @needs-grant interceptUpdate
   */
  function interceptUpdate<U extends tl.TypeUpdate['_']>(
    types: U | U[],
    middleware: (context: UpdateMiddlewareContext<U>) => MaybePromise<'deliver' | 'drop'>,
  ): Disposer

  interface ActionContext {
    account: Account
  }
  interface ChatActionContext extends ActionContext {
    dialogId: number
    topicId?: number
  }
  type MessageActionSource = 'bubble' | 'selection'
  interface MessageActionContext extends ChatActionContext {
    source: MessageActionSource
    /** Oldest to newest. A bubble expands its album; a selection contains exactly what the user selected. */
    messages: readonly Message[]
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

  /**
   * Register a global action handler, shown in the drawer or the hamburger menu on the main page
   *
   * **Limits: 150 ms to render plugin actions, 8 rows per menu per plugin.**
   */
  function registerGlobalAction(options: ActionOptions<ActionContext>): Disposer

  /** Register a chat action, shown in the chat hamburger menu */
  function registerChatAction(options: ActionOptions<ChatActionContext, ChatActionContext | null>): Disposer

  /** Register a message action, shown in the message context menu */
  function registerMessageAction(options: MessageActionOptions): Disposer

  /** Register a profile action, shown in the profile hamburger menu */
  function registerProfileAction(options: ActionOptions<ChatActionContext>): Disposer

  /** A request to {@link TranslationProvider} */
  interface TranslationRequest<T> {
    /**
     * The texts the provider was asked to translate.
     *
     * More than one texts can be passed at once,
     * and the response should return the translations in the same order
     */
    readonly texts: readonly T[]
    /**
     * Detected language for each text (in order), such as `en`.
     *
     * `null` when the app does not know it
     */
    readonly from: readonly (string | null)[]
    /** Language code to translate into, such as `en` or `zh-CN` */
    readonly to: string
    /**
     * The tone the user picked when translating their own text before sending it, if not neutral
     * (can be ignored if the provider doesn't support it)
     */
    readonly tone?: 'formal' | 'casual'
    /** Abort signal that should be used for `fetch`-es inside the provider */
    readonly signal: AbortSignal
  }

  type TranslationProvider = {
    /** ID of the provider. Used by the picker, keep it stable across versions */
    id: string
    /** Display name of the provider */
    name: string
  } & (
    | {
      /**
       * Format of the input and output texts
       *
       * - `'plain'` (default): plain strings in and out, formatting is dropped
       * - `'html'`: Telegram's HTML subset with whitespace kept as written (see {@link utils.thtml}), recommended for services that correctly keep markup
       */
      format?: 'plain' | 'html'
      translate(request: TranslationRequest<string>): MaybePromise<string[]>
    }
    | {
      /**
       * Format of the input and output texts
       *
       * - `'entities'`: Telegram's native `TextWithEntities` format, `InputText` out
       */
      format: 'entities'
      translate(request: TranslationRequest<TextWithEntities>): MaybePromise<InputText[]>
    }
  )

  /**
   * Register a translation provider, which can be chosen by the user in Translator settings.
   *
   * When the provider is selected, every translation request will be forwarded to the `translate`
   * method of the provider.
   *
   * **Limits: 30 s per request.**
   */
  function registerTranslationProvider(provider: TranslationProvider): Disposer

  /**
   * Quoted part of a replied-to message
   *
   * @experimental Subject to breaking changes between releases.
   */
  interface ReplyQuote extends TextWithEntities {
    /** Where the quote starts in the replied-to message's text, in UTF-16 code units */
    offset: number | null
  }

  /**
   * What an {@link OutgoingMessage} replies to
   *
   * @experimental Subject to breaking changes between releases.
   */
  interface PendingReply {
    /** ID of the replied-to message */
    messageId: number
    /** Marked peer id of the replied-to message's chat, if it is not the chat the message is sent to */
    peer: number | null
    /** Quoted part of the replied-to message */
    quote: ReplyQuote | null
  }

  /**
   * How forwarded messages are sent:
   * - `normal`: with the original sender
   * - `hide-sender`: as if sent by the user
   * - `hide-caption`: as if sent by the user, with media captions removed
   *
   * @experimental Subject to breaking changes between releases.
   */
  type ForwardMode = 'normal' | 'hide-sender' | 'hide-caption'

  /**
   * Messages forwarded along with an {@link OutgoingMessage}
   *
   * @experimental Subject to breaking changes between releases.
   */
  interface PendingForward {
    /** Marked peer id of the chat the messages are forwarded from */
    peer: number
    /** IDs of the forwarded messages, in the order they are sent */
    messageIds: number[]
    mode: ForwardMode
  }

  /**
   * A message the user is sending, for {@link interceptSendMessage}, editable in-place.
   *
   * It is a snapshot: edits are applied once the middleware returns `'send'`, and one that
   * cannot be applied fails the send then.
   *
   * @experimental Subject to breaking changes between releases.
   */
  interface OutgoingMessage {
    /** Marked peer id of the message's chat */
    peer: number
    /**
     * Formatted text of the message.
     *
     * For forwards, this is the "comment" sent before the forwarded messages. Setting it on a
     * forward sent without one adds a comment
     */
    get text(): TextWithEntities
    set text(value: InputText)
    /** What this message replies to, possibly in another chat or with a quote */
    reply: PendingReply | null
    /**
     * Information about a pending forwarded message(s), as seen in the composer's "forward" panel.
     *
     * When non-null, `text` is the comment that will be sent before the forwarded messages.
     */
    forward: PendingForward | null
    /** If this message is in a topic, ID of the topic */
    topicId: number | null
    /** If this message is scheduled, date of the schedule */
    scheduleDate: number | null
    /** Whether this message is sent as "silent" */
    silent: boolean
    /**
     * Any media attached to the message. Its caption ({@link text}) is on the first item.
     *
     * At the `compose` stage, items are usually {@link LocalMedia} objects,
     * representing the user's picked file.
     */
    media: OutgoingMedia[]
  }

  /**
   * A file the app uploads once the send is decided, see {@link OutgoingMessage.media}
   *
   * @experimental Subject to breaking changes between releases.
   */
  interface LocalMedia {
    readonly _: 'localMedia'
    readonly kind: 'photo' | 'video' | 'gif' | 'music' | 'voice' | 'roundVideo' | 'sticker' | 'document'
    readonly name: string | null
    readonly mimeType: string
    /** Whether the media is hidden behind a spoiler */
    spoiler: boolean
    /**
     * Whether the user edited this video (trimmed, cropped, muted, drew on it, ...) in a way the app
     * applies only once the send is decided, so {@link blob} does not reflect it
     */
    readonly edited: boolean
    /** Width of a photo or video in pixels, as displayed, if known */
    readonly width: number | null
    /** Height of a photo or video in pixels, as displayed, if known */
    readonly height: number | null
    /** Duration of a video or audio in seconds, if known */
    readonly duration: number | null
    /**
     * The file behind this media, as the user picked it, before the app compresses it.
     *
     * Edited photos are returned with the edits rendered.
     * An {@link edited} video is the original and unmodified.
     *
     * @needs-grant account.read(messages)
     * @throws {@link Error} if the file is not available
     */
    blob(): Promise<File>
  }

  /** @experimental Subject to breaking changes between releases. */
  type OutgoingMedia = LocalMedia | tl.TypeInputMedia

  /** @experimental Subject to breaking changes between releases. */
  interface SendMessageContext {
    message: OutgoingMessage
    account: Account
    /**
     * Aborted once this send no longer matters: the user cancelled it, the chain's budget ran out,
     * or the chain was torn down. `reason` is a {@link PluginError} coded `aborted` or `timed-out`.
     */
    readonly signal: AbortSignal
  }

  /** @experimental Subject to breaking changes between releases. */
  type SendMessagePeer = number | 'user' | 'group' | 'broadcast'

  /** @experimental Subject to breaking changes between releases. */
  interface SendMessageFilter {
    /**
     * Regex the message is supposed to match for the hook to fire, or whether it is supposed to have
     * any text (`true`) or none (`false`)
     *
     * A regex is compiled by Android's `java.util.regex.Pattern`; unsupported syntax throws.
     * It runs on the send path with no time limit: avoid nested quantifiers like `(a+)+`.
     */
    text?: RegExp | boolean
    /**
     * Chat(s) the message is supposed to be sent to: marked peer ids, peer types, or both.
     * Groups include basic groups and supergroups
     */
    peer?: SendMessagePeer | SendMessagePeer[]
    /**
     * Whether the message is supposed to have media (`true`) or not (`false`), or the type(s) of
     * media at least one of its items is supposed to be. A document a plugin added by id has no
     * type, so it matches only `true`
     */
    media?: boolean | MediaType | MediaType[]
    /** Whether the message is supposed to carry a forward (`true`) or not (`false`) */
    forward?: boolean
    /**
     * When the hook runs:
     * - `'compose'` (default): when the user taps "send", before the app draws the message or uploads its media
     * - `'uploaded'`: for messages with media, once it is uploaded, right before the message is sent, after every `'compose'` hook
     */
    stage?: 'compose' | 'uploaded'
  }

  /**
   * Register an interceptor for the user-initiated message sends.
   *
   * This overload registers one in the `compose` stage.
   *
   * @needs-grant interceptSendMessage
   *
   * @experimental Subject to breaking changes between releases.
   */
  function interceptSendMessage(
    middleware: (context: SendMessageContext) => MaybePromise<'send' | 'drop'>,
  ): Disposer
  /**
   * Register a hook before the message is sent, with a filter.
   *
   * This overload is recommended over the general one because it avoids unnecessary JS
   * jumps for messages that are not matched.
   *
   * Additionally, this overload allows configuring the stage the hook runs on.
   *
   * @needs-grant interceptSendMessage
   *
   * @experimental Subject to breaking changes between releases.
   */
  function interceptSendMessage(
    filter: SendMessageFilter,
    middleware: (context: SendMessageContext) => MaybePromise<'send' | 'drop'>,
  ): Disposer
}
