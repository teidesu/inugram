declare namespace inu {
  /**
   * api to access commonly used android app internals
   *
   * **note**: this api is NOT stable, does not follow the overall versioning.
   * and docs/typings below might also be out of date.
   * use at your own risk.
   *
   * ids here are plain `number`s like everywhere else in this api — java hands them over as
   * `long`, but a dialog id fits a js number, so there's no reason for this corner to speak a
   * different dialect. anything genuinely too wide for a number arrives as a `JavaObject`.
   */
  namespace android {
    interface NotificationCenterEventsMap {
      /**
       * Posted when new messages arrive for a dialog (all paths incl. difference catch-up).
       * Single call site in MessagesController.processNewDifferenceParams.
       * @param dialogId target dialog id
       * @param messages mutable; observers may filter/modify; ArrayList<JavaObject>
       * @param scheduled true if these are scheduled messages
       * @param mode ChatActivity mode constant (MODE_DEFAULT=0, MODE_SCHEDULED, MODE_QUICK_REPLIES, etc.)
       */
      didReceiveNewMessages(dialogId: number, messages: Array<JavaObject>, scheduled: boolean, mode: number): void

      /**
       * Posted to signal UI refresh for peers/dialogs. arg is a bitmask of what changed.
       * @param updateMask bitmask; one of MessagesController.UPDATE_MASK_* constants (NAME, AVATAR, STATUS, CHAT, ALL, EMOJI_STATUS, READ_DIALOG_MESSAGE, USER_PRINT, etc.); 0 means generic refresh
       */
      updateInterfaces(updateMask: number): void

      /**
       * Posted to signal that the dialogs list needs to be reloaded from storage/server.
       * Most call sites post no args; some (MessagesController) post a single boolean.
       * @param force optional; true to force reload even if not strictly needed; absent in most call sites
       */
      dialogsNeedReload(force?: boolean): void

      /**
       * Posted to close chat screens. Without args closes all; with a dialogId only closes that dialog.
       * @param dialogId optional; if present, only the ChatActivity matching this id is closed; negative for channels (ChatEditActivity passes -chatId)
       */
      closeChats(dialogId?: number): void

      /**
       * Posted to close a specific ChatActivity (not profile). Closes all stack instances matching dialogId except last,
       * or all including last when includingLast=true.
       * @param dialogId dialog id to close
       * @param includingLast if true, also close if this is the topmost fragment
       */
      closeChatActivity(dialogId: number, includingLast: boolean): void

      /**
       * Posted to close a specific ProfileActivity for a dialog.
       * @param dialogId dialog id to close
       * @param includingLast if true, also close if this is the topmost fragment
       */
      closeProfileActivity(dialogId: number, includingLast: boolean): void

      /**
       * Posted when messages are deleted. Arg counts vary across call sites (3 to 7 args).
       * Short form (3 args): from MessagesStorage — no scheduled/movedToScheduled info.
       * Full form (7 args): from MessagesController.deleteMessages — includes all flags.
       * @param messageIds ArrayList<number> of deleted message ids (mutable, passed in-place)
       * @param channelId long; channel id (negative for channels, 0 for user/group chats)
       * @param scheduled boolean; true if these were scheduled messages
       * @param forAll boolean (only in 7-arg form); true if deleted for all users; false in 3-arg form implicitly
       * @param movedToScheduled boolean (only in 7-arg form); true if moved to scheduled
       * @param movedToScheduledMessageId int (only in 7-arg form); id of the message moved to scheduled
       * @param sentMessageIds ArrayList<number>|null (only in 7-arg form); ids from sent scheduled messages that were deleted
       *
       * Overloads:
       *   3-arg: (messageIds: Array<number>, channelId: number, scheduled: boolean)
       *   7-arg: (messageIds: Array<number>, channelId: number, scheduled: boolean, forAll: boolean, movedToScheduled: boolean, movedToScheduledMessageId: number, sentMessageIds: Array<number>|null)
       */
      messagesDeleted(messageIds: Array<number>, channelId: number, scheduled: boolean, forAll?: boolean, movedToScheduled?: boolean, movedToScheduledMessageId?: number, sentMessageIds?: Array<number> | null): void

      /**
       * Posted when a dialog's history is cleared (delete history operation).
       * @param dialogId long; the dialog whose history was cleared (negative for channels)
       * @param maxId int; the max message id that was cleared up to
       */
      historyCleared(dialogId: number, maxId: number): void

      /**
       * Posted when inbox/outbox read state is updated for dialogs.
       * Maps dialogId → max read message id. Either arg can be null.
       * @param inbox LongMap<number, number>|null; dialogId → max inbox read message id
       * @param outbox LongMap<number, number>|null; dialogId → max outbox read message id
       */
      messagesRead(inbox: Map<number, number[]> | null, outbox: Map<number, number[]> | null): void

      /**
       * Posted when a comment thread's read state is updated (inbox or outbox).
       * Corresponds to TL_updateReadChannelDiscussionInbox/Outbox.
       * @param channelDialogId long; negative channel id (−channel_id)
       * @param topMsgId int; the top/root message id of the thread
       * @param inboxReadMaxId int; new max inbox read id; 0 if this is an outbox update
       * @param outboxReadMaxId int; new max outbox read id; 0 if this is an inbox update
       */
      threadMessagesRead(channelDialogId: number, topMsgId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      /**
       * Posted when a monoforum subthread's read state is updated (inbox or outbox).
       * Corresponds to TL_updateReadMonoForumInbox/Outbox.
       * @param channelDialogId long; negative channel id (−channel_id)
       * @param savedPeerDialogId long; dialog id of the saved peer (subthread)
       * @param inboxReadMaxId int; new max inbox read id; 0 if this is an outbox update
       * @param outboxReadMaxId int; new max outbox read id; 0 if this is an inbox update
       */
      monoForumMessagesRead(channelDialogId: number, savedPeerDialogId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      /**
       * Posted when comments on a broadcast post are read.
       * Corresponds to TL_updateReadChannelDiscussionInbox when broadcast_id is set,
       * or posted by ChatActivity when the user reads a comments thread.
       * @param broadcastChannelId long; id of the broadcast channel
       * @param broadcastPostId int; message id of the original broadcast post
       * @param maxReadId int; new max read comment message id
       */
      commentsRead(broadcastChannelId: number, broadcastPostId: number, maxReadId: number): void

      /**
       * Posted to adjust the replies counter on a broadcast post (e.g. when comments are deleted).
       * @param channelId long; id of the channel owning the original post
       * @param originalMessageId int; message id of the post whose counter changes
       * @param delta int; signed delta to add to replies count (negative when deleting)
       */
      changeRepliesCounter(channelId: number, originalMessageId: number, delta: number): void

      /**
       * Posted when a batch of messages finishes loading (after reply preloading is done).
       * 16 args total — args[10]=classGuid used for routing, args[11]=loadIndex.
       * HashtagSearchController posts with mode=ChatActivity.MODE_SEARCH and fewer meaningful args (count=actual size).
       * @param dialogId long; the dialog the messages belong to
       * @param count int; number of messages loaded
       * @param messages ArrayList<JavaObject>; the loaded messages (mutable)
       * @param isCache boolean; true if loaded from local cache
       * @param firstUnread int; message id of the first unread message (0 if none)
       * @param lastMessageId int; id of the last delivered message
       * @param unreadCount int; unread count for the dialog
       * @param lastDate int; date of the last message
       * @param loadType int; load direction/type (0=first load, 1=up, 2=down, 3=around, etc.)
       * @param isEnd boolean; true if no more messages in that direction
       * @param classGuid int; routing guid — observers ignore if not matching their own guid
       * @param loadIndex int; monotonic load sequence number; negative means "do not dequeue"
       * @param maxId int; the pivot message id used for the request
       * @param mentionsCount int; unread mentions count
       * @param mode int; ChatActivity mode constant
       */
      messagesDidLoad(dialogId: number, count: number, messages: Array<JavaObject>, isCache: boolean, firstUnread: number, lastMessageId: number, unreadCount: number, lastDate: number, loadType: number, isEnd: boolean, classGuid: number, loadIndex: number, maxId: number, mentionsCount: number, mode: number): void

      /**
       * Posted when sponsored messages finish loading for a dialog.
       * @param dialogId long; the dialog the sponsored messages belong to
       * @param messages TLRPC.TL_messages_sponsoredMessages; the loaded sponsored messages result
       */
      didLoadSponsoredMessages(dialogId: number, messages: TLObject): void

      /**
       * Posted when the list of "send as" peers finishes loading for a dialog.
       * @param dialogId long; the dialog the peers belong to
       * @param peers TLRPC.TL_channels_sendAsPeers; the loaded peers result
       * @param liveStories boolean; whether live stories peers are included
       */
      didLoadSendAsPeers(dialogId: number, peers: TLObject, liveStories: boolean): void

      /**
       * Posted when the default "send as" peer for a chat is changed.
       * @param chatId long; the chat whose default send-as changed
       * @param peer TLRPC.Peer; the new default send-as peer
       */
      updateDefaultSendAsPeer(chatId: number, peer: TLObject): void

      /**
       * Posted when messages finish loading but do NOT need UI processing (e.g. encrypted dialogs,
       * or when needProcess=false). Used by internal loaders to signal completion without full delivery.
       * @param classGuid int; routing guid
       * @param count int; number of raw messages loaded
       * @param isCache boolean; true if from local cache
       * @param isEnd boolean; true if no more messages in that direction
       * @param lastMessageId int; id of the last message
       */
      messagesDidLoadWithoutProcess(classGuid: number, count: number, isCache: boolean, isEnd: boolean, lastMessageId: number): void

      /**
       * Posted when loading messages from the server fails.
       * @param classGuid int; routing guid
       * @param request TLObject; the original request object that failed
       * @param error TLRPC.TL_error|null; the error returned by the server; may be null
       */
      loadingMessagesFailed(classGuid: number, request: TLObject, error: TLObject): void

      /**
       * Posted when a pending outgoing message is acknowledged by the server (MTProto ack, not yet assigned a server id).
       * @param msgId int; the temporary (local) message id that was acked
       */
      messageReceivedByAck(msgId: number): void

      /**
       * Posted when a pending outgoing message is confirmed by the server and assigned a real server id.
       * Fired BEFORE the message object is saved to storage (messageReceivedByServer2 fires after).
       * msg can be null when only the id mapping is known (e.g. scheduled message finalization in MessagesController).
       * @param oldId int; the temporary (local) message id
       * @param newId int; the real server-assigned message id
       * @param msg JavaObject|null; the confirmed message object; null in some MessagesController paths
       * @param dialogId long; dialog the message belongs to
       * @param groupedId long; media group id if part of an album; 0 otherwise
       * @param existFlags int; bitmask of which fields already existed (used for dedup); -1 when unknown
       * @param scheduled boolean; true if this is a scheduled message
       */
      messageReceivedByServer(oldId: number, newId: number, msg: JavaObject | null, dialogId: number, groupedId: number, existFlags: number, scheduled: boolean): void

      /**
       * Posted after messageReceivedByServer once the message has been fully saved to storage.
       * Same argument signature as messageReceivedByServer.
       * @param oldId int; the temporary (local) message id
       * @param newId int; the real server-assigned message id
       * @param msg JavaObject|null; the confirmed message object; null in some MessagesController paths
       * @param dialogId long; dialog the message belongs to
       * @param groupedId long; media group id if part of an album; 0 otherwise
       * @param existFlags int; bitmask of which fields already existed; -1 when unknown
       * @param scheduled boolean; true if this is a scheduled message
       */
      messageReceivedByServer2(oldId: number, newId: number, msg: JavaObject | null, dialogId: number, groupedId: number, existFlags: number, scheduled: boolean): void

      /**
       * Posted when a pending outgoing message permanently fails to send.
       * @param msgId int; the temporary (local) message id that failed
       */
      messageSendError(msgId: number): void

      /**
       * Posted to trigger a contacts import prompt in the UI (no args).
       * Fired from DialogsActivity when a background import check detects new contacts.
       */
      forceImportContactsStart(): void

      /**
       * Posted when the local contacts list finishes loading or updating (no args).
       * Fired many times from ContactsController after sync, import, or name-change operations.
       */
      contactsDidLoad(): void

      /**
       * Posted when contacts have been successfully imported to Telegram (no args).
       * Fired from ContactsController after a successful importContacts or deleteContacts round-trip.
       */
      contactsImported(): void

      /**
       * Posted when new phone-book contacts are detected that have not yet been imported.
       * @param checkType int; type of check that found new contacts
       * @param contactHashMap HashMap<String, ContactsController.Contact>; the new contacts keyed by phone number
       * @param first boolean; true if this is the first-ever import prompt
       * @param schedule boolean; whether to schedule the import for a later time
       */
      hasNewContactsToImport(checkType: number, contactHashMap: Map<string, JavaObject>, first: boolean, schedule: boolean): void

      /**
       * Posted when a new group/channel chat is successfully created.
       * @param chatId long; the id of the newly created chat
       */
      chatDidCreated(chatId: number): void

      /**
       * Posted when creating a group/channel chat fails (no args).
       * Observers re-enable their UI and show an error.
       */
      chatDidFailCreate(): void

      /**
       * Posted when full chat/channel info loaded.
       * @param chatFull loaded chat full object
       * @param classGuid int request class guid (0 from most callers)
       * @param byChannelUsers boolean true if triggered by channel users load
       * @param fromCache boolean true if data came from local cache
       */
      chatInfoDidLoad(chatFull: TLObject, classGuid: number, byChannelUsers: boolean, fromCache: boolean): void

      /**
       * Posted when full chat/channel info failed to load.
       * @param channelId long channel id
       * @param reason int 0=generic, 1=banned, 2=private
       */
      chatInfoCantLoad(channelId: number, reason: number): void

      /**
       * Posted when a page of shared media loaded.
       * @param dialogId long dialog id
       * @param totalCount int total item count from server
       * @param objects ArrayList<JavaObject> loaded messages (mutable, observers may iterate only)
       * @param classGuid int request class guid
       * @param type int media type constant
       * @param topReached boolean true if top of history reached
       * @param fromStart boolean true if loaded from a non-zero min_id (i.e. not from newest end)
       * @param requestIndex int request sequence index
       */
      mediaDidLoad(dialogId: number, totalCount: number, objects: Array<JavaObject>, classGuid: number, type: number, topReached: boolean, fromStart: boolean, requestIndex: number): void

      /**
       * Posted when a single media type count loaded.
       * @param dialogId long dialog id
       * @param topicId long topic id (0 if none)
       * @param count int media count (0 when fromCache and server returned -1)
       * @param fromCache boolean true if count came from local cache
       * @param type int media type constant
       */
      mediaCountDidLoad(dialogId: number, topicId: number, count: number, fromCache: boolean, type: number): void

      /**
       * Posted when all media type counts loaded at once.
       * @param dialogId long dialog id
       * @param topicId long topic id (0 if none)
       * @param counts int[] per-type counts array (indexed by media type constant)
       */
      mediaCountsDidLoad(dialogId: number, topicId: number, counts: number[]): void

      /**
       * Posted when an encrypted chat object is updated (key exchange steps, state changes).
       * @param chat TLRPC.EncryptedChat updated encrypted chat
       */
      encryptedChatUpdated(chat: TLObject): void

      /**
       * Posted when encrypted messages are marked as read up to a date.
       * @param encryptedChatId int encrypted chat id (key from Map<number, number>)
       * @param maxReadDate int max read date (seconds)
       */
      messagesReadEncrypted(encryptedChatId: number, maxReadDate: number): void

      /**
       * Posted when a new encrypted chat is successfully created.
       * @param chat TLRPC.EncryptedChat the newly created encrypted chat
       */
      encryptedChatCreated(chat: TLObject): void

      /**
       * Posted to signal that dialog avatar photos should be reloaded/refreshed.
       * Never posted — only used as a legacy event id still referenced by GroupCallActivity animation gate.
       * Observers registered in ProfileGalleryView never fire via this path (dialogPhotosUpdate is used instead).
       * // never posted
       */
      dialogPhotosLoaded(): void

      /**
       * Posted when dialog avatar photos need to be reloaded (privacy change, photo deleted/set, etc.).
       * No arguments.
       */
      reloadDialogPhotos(): void

      /**
       * Posted when a folder becomes empty (all dialogs removed).
       * @param folderId int the folder id that became empty
       */
      folderBecomeEmpty(folderId: number): void

      /**
       * Posted when all messages in a dialog are deleted (history cleared or channel difference too long).
       * @param dialogId long dialog id whose messages were cleared
       * @param fromDifference boolean true if triggered by channel difference, false if by explicit history clear
       * @param difference TLRPC.TL_updates_channelDifferenceTooLong|null non-null only when fromDifference=true
       */
      removeAllMessagesFromDialog(dialogId: number, fromDifference: boolean, difference: TLObject): void

      /**
       * Posted when notification settings are changed (global or per-dialog).
       * No arguments.
       */
      notificationsSettingsUpdated(): void

      /**
       * Posted when the blocked users list is loaded or changed.
       * No arguments.
       */
      blockedUsersDidLoad(): void

      /**
       * Posted when the currently opened chat changes (tablet two-pane layout tracking).
       * @param dialogId long dialog id that was opened or closed
       * @param topicId long topic id (0 if none)
       * @param closed boolean true if the chat was closed, false if opened
       */
      openedChatChanged(dialogId: number, topicId: number, closed: boolean): void

      /**
       * Posted when a new scheduled self-destruct delete task is created.
       * @param dialogId long dialog id the task belongs to
       * @param mids Map<number, ArrayList<number>> map of destroyTime -> list of message ids
       */
      didCreatedNewDeleteTask(dialogId: number, mids: Map<number, Array<number>>): void

      /**
       * Posted when the current user's own profile info changes (name, photo, bio, etc.).
       * No arguments.
       */
      mainUserInfoChanged(): void

      /**
       * Posted when privacy rules are updated.
       * No arguments.
       */
      privacyRulesUpdated(): void

      /**
       * Posted when a message's media attachment is updated (e.g. upload completed, media replaced).
       * @param message TLRPC.Message the message whose media was updated (mutated in-place by sender before posting)
       */
      updateMessageMedia(message: TLObject): void

      /**
       * Posted when JavaObject wrappers for existing messages are replaced (e.g. after edit or send confirmation).
       * Most call sites pass 2 args; one call site (MessagesController diff processing) passes an optional 3rd boolean.
       * @param dialogId long dialog id
       * @param messageObjects ArrayList<JavaObject> replacement message objects
       * @param updateDialogs boolean? (optional, only some call sites) whether to also update dialogs list
       */
      replaceMessagesObjects(dialogId: number, messageObjects: Array<JavaObject>, updateDialogs?: boolean): void

      /**
       * Posted when passcode is set, changed, or screen-capture setting toggled.
       * Some call sites pass no args; one passes false to indicate a non-passcode change (screen capture toggle).
       * @param isPasscodeChange boolean? (optional) false when triggered by screen-capture toggle, absent when passcode set/changed
       */
      didSetPasscode(isPasscodeChange?: boolean): void

      /**
       * Posted when the passcode lock screen is dismissed.
       * @param view View the PasscodeView that was dismissed
       */
      passcodeDismissed(view: JavaObject): void

      /**
       * Posted when 2-step verification password is changed.
       * Some call sites post with no args (password removed/reset path); most post full param set.
       * @param currentPasswordHash byte[]|null new password hash, null on reset
       * @param newAlgo TLRPC.PasswordKdfAlgo|null new algo
       * @param newSecureAlgo TLRPC.SecurePasswordKdfAlgo|null new secure algo
       * @param secureRandom byte[]|null secure random bytes
       * @param email String|null recovery email
       * @param hint String|null password hint
       * @param unusedEmail String|null (arg[6]) always null in observed call sites
       * @param firstPassword String|null plaintext password used to set (for auto-fill)
       * All args optional — some call sites post with zero args.
       */
      twoStepPasswordChanged(currentPasswordHash?: Uint8Array | null, newAlgo?: TLObject, newSecureAlgo?: TLObject, secureRandom?: Uint8Array | null, email?: string | null, hint?: string | null, unusedEmail?: string | null, firstPassword?: string | null): void

      /**
       * Posted when 2-step password is set or removed.
       * Sometimes posted with no args (password removed); sometimes with currentPassword.
       * @param currentPassword TL_account.TL_password|null (optional) current password info after the change, null if removed
       */
      didSetOrRemoveTwoStepPassword(currentPassword?: TLObject | null): void

      /**
       * Posted when 2-step password is removed (in addition to didSetOrRemoveTwoStepPassword).
       * No arguments.
       */
      didRemoveTwoStepPassword(): void

      /**
       * Posted when reply-to messages are loaded for rendering quoted replies.
       * @param dialogId long dialog id
       * @param loadedMessages ArrayList<JavaObject> the loaded reply message objects
       * @param replyMessageOwners LongMap<number, Map<number, ArrayList<JavaObject>>>|null owner map for updating reply refs; null in some call sites
       */
      replyMessagesDidLoad(dialogId: number, loadedMessages: Array<JavaObject>, replyMessageOwners: Map<number, Map<number, Array<JavaObject>>> | null): void

      /**
       * Posted when pinned messages for a dialog are loaded.
       * Two shapes: "ids loaded" (ids non-null, replaceObjects = HashMap) and "objects loaded" (ids null, arrayList non-null).
       * @param dialogId long dialog id
       * @param ids ArrayList<number>|null list of pinned message ids (null in the "objects already known" path)
       * @param pin boolean true = add/update pins, false = unpin
       * @param arrayList ArrayList<JavaObject>|null loaded pinned message objects (null when only updating id list)
       * @param replaceObjects HashMap<number,JavaObject>|null replacement map (non-null when ids non-null and data from server)
       * @param maxId int max message id boundary for pagination (0 when loading from top)
       * @param totalPinnedCount int total pinned message count (-1 when unknown)
       * @param endReached boolean true if no more pinned messages to load
       */
      didLoadPinnedMessages(dialogId: number, ids: Array<number> | null, pin: boolean, arrayList: Array<JavaObject> | null, replaceObjects: Map<number, JavaObject> | null, maxId: number, totalPinnedCount: number, endReached: boolean): void

      /**
       * Posted when a new login session is detected on the account. No arguments.
       * // never posted (only registered as observer in SessionsActivity; no postNotificationName call site found)
       */
      newSessionReceived(): void

      /**
       * Posted when webpage previews are resolved and ready to replace pending media in messages.
       * @param messages ArrayList<TLRPC.Message> messages whose media.webpage has been populated
       */
      didReceivedWebpages(messages: Array<TLObject>): void

      /**
       * Posted when webpage previews arrive via update diff.
       * @param webPages map from webpage ID → updated TLRPC.WebPage; never null at call site (guarded)
       */
      didReceivedWebpagesInUpdates(webPages: Map<number, TLObject>): void

      /**
       * Posted when a sticker set type finishes loading or reordering.
       * @param type MediaDataController sticker type constant (int; e.g. TYPE_IMAGE, TYPE_MASK, etc.)
       * @param forceUpdateUi whether the UI should force-refresh (true at most call sites; false when silently syncing from storage)
       */
      stickersDidLoad(type: number, forceUpdateUi: boolean): void

      /**
       * Posted when a dice/animated sticker set finishes loading.
       * @param name emoji string identifying the dice set (e.g. "🎲")
       */
      diceStickersDidLoad(name: string): void

      /**
       * Posted when the featured (trending) sticker sets list finishes loading or refreshing.
       * No args.
       */
      featuredStickersDidLoad(): void

      /**
       * Posted when the featured (trending) emoji sets list finishes loading or refreshing.
       * No args.
       */
      featuredEmojiDidLoad(): void

      /**
       * Posted when a group/channel's custom sticker set finishes loading.
       * @param setId long ID of the StickerSet
       * @param set   loaded TLRPC.TL_messages_stickerSet (may be updated cached copy)
       */
      groupStickersDidLoad(setId: number, set: TLObject): void

      /**
       * Posted when message content (media) has been marked as read (opened).
       * @param dialogId long dialog ID
       * @param messageIds ArrayList<number> of message IDs whose content was read; mutable, do not retain reference
       */
      messagesReadContent(dialogId: number, messageIds: Array<number>): void

      /**
       * Posted when bot info for a user/dialog finishes loading.
       * @param botInfo  TL_bots.BotInfo loaded object
       * @param classGuid int request-group GUID; 0 when posted from a direct one-shot load
       */
      botInfoDidLoad(botInfo: TLObject, classGuid: number): void

      /**
       * Posted when full user info (UserFull) finishes loading or is updated.
       * @param userId  long — the user/dialog ID the full info belongs to
       * @param userFull TLRPC.UserFull loaded object
       */
      userInfoDidLoad(userId: number, userFull: TLObject): void

      /**
       * Posted when pinned messages for a chat/user finish loading.
       * @param peerId           long — negative for channels/chats, positive for users
       * @param pinnedMessages   ArrayList<number> of pinned message IDs (ordered)
       * @param pinnedMessagesMap HashMap<number, JavaObject> id → JavaObject cache
       * @param totalPinnedCount int total count of pinned messages
       * @param pinnedEndReached boolean whether all pinned messages have been loaded
       */
      pinnedInfoDidLoad(
        peerId: number,
        pinnedMessages: Array<number>,
        pinnedMessagesMap: Map<number, JavaObject>,
        totalPinnedCount: number,
        pinnedEndReached: boolean
      ): void

      /**
       * Posted when the bot keyboard (reply markup) for a topic finishes loading or is cleared.
       * @param keyboard TLRPC.Message|null — null when no keyboard exists for this topic
       * @param topicKey long topic key (dialog + thread ID)
       */
      botKeyboardDidLoad(keyboard: TLObject, topicKey: number): void

      /**
       * Posted when in-chat search navigates to a result or clears.
       * @param guid          int search session identifier
       * @param messageId     int ID of the current result message (0 when no result)
       * @param mask          int bitmask of navigation capability flags (1=canGoNext, 2=canGoPrev)
       * @param dialogId      long dialog the result lives in (0 when no result)
       * @param index         int zero-based index of this result within results
       * @param count         int total result count (0 when no result)
       * @param jumpToMessage boolean whether to jump/scroll to message or just update counter
       */
      chatSearchResultsAvailable(
        guid: number,
        messageId: number,
        mask: number,
        dialogId: number,
        index: number,
        count: number,
        jumpToMessage: boolean
      ): void

      /**
       * Posted when hashtag search results are updated (new batch loaded or index changed).
       * @param guid          int search session identifier
       * @param count         int total result count
       * @param endReached    boolean whether the end of results has been reached
       * @param mask          int navigation bitmask (1=canGoNext, 2=canGoPrev)
       * @param selectedIndex int current result index within the list
       * @param messageId     int ID of the message at selectedIndex (0 if not navigated yet)
       */
      hashtagSearchUpdated(
        guid: number,
        count: number,
        endReached: boolean,
        mask: number,
        selectedIndex: number,
        messageId: number
      ): void

      /**
       * Posted when in-chat search starts loading a new batch (spinner state).
       * @param guid int search session identifier
       */
      chatSearchResultsLoading(guid: number): void

      /**
       * Posted when local music tracks for a dialog finish loading from the database.
       * @param dialogId       long dialog ID the tracks belong to
       * @param tracksBegin    ArrayList<JavaObject> tracks before current (older)
       * @param tracksEnd      ArrayList<JavaObject> tracks after current (newer)
       */
      musicDidLoad(
        dialogId: number,
        tracksBegin: Array<JavaObject>,
        tracksEnd: Array<JavaObject>
      ): void

      /**
       * Posted when additional tracks are appended to the active playlist during playback.
       * @param addedCount int number of tracks added to the playlist
       */
      moreMusicDidLoad(addedCount: number): void

      /**
       * Posted to show a system alert to the user. Arg count varies by reason:
       *   reason=0,1,5  → (number reason)                            — spam/restriction alerts, no extra args
       *   reason=2      → (number reason, String message, String type) — server-sent message with type tag
       *   reason=3      → (number reason)                            — proxy-related alert (global NC)
       *   reason=4      → (number reason, TLRPC.TL_help_termsOfService) — ToS update
       *   reason=6      → (number reason, String errorText)          — VoIP/group-call error; consumed only in VoIP/group call paths
       * @param reason    int discriminator
       * @param extra     optional; type depends on reason (see above)
       */
      needShowAlert(reason: number, ...extra: any[]): void

      /**
       * Posted when Google Play Services availability check returns a non-OK status.
       * @param status int ConnectionResult status code from LocationController
       */
      needShowPlayServicesAlert(status: number): void

      /**
       * Posted when message view/forward/reply counts are updated from the server.
       * @param channelViews    LongMap<number, Map<number, number>>|null  dialogId → (messageId → viewCount)
       * @param channelForwards LongMap<number, Map<number, number>>|null  dialogId → (messageId → forwardCount)
       * @param channelReplies  LongMap<number, Map<number, TLRPC.MessageReplies>>|null  dialogId → (messageId → replies)
       * @param onlySelf        boolean true when only updating counts for the local client's own sends
       */
      didUpdateMessagesViews(
        channelViews: Map<number, Map<number, number>> | null,
        channelForwards: Map<number, Map<number, number>> | null,
        channelReplies: Map<number, Map<number, TLObject>> | null,
        onlySelf: boolean
      ): void

      /**
       * Posted when recent dialogs search cache should be invalidated and reloaded.
       * No args.
       */
      needReloadRecentDialogsSearch(): void

      /**
       * Posted when peer settings (PeerSettings/privacy info) finish loading for a dialog.
       * @param dialogId long peer/dialog ID
       */
      peerSettingsDidLoad(dialogId: number): void

      /**
       * Posted when a location-sharing send attempt fails because the current location cannot be obtained.
       * @param pendingMessages HashMap<String, JavaObject> copy of messages waiting for location (key = random ID string); mutable copy, safe to retain
       */
      wasUnableToFindCurrentLocation(pendingMessages: Map<string, JavaObject>): void

      /**
       * Posted when top peer (people/bots/inline) hints should be reloaded.
       * No args.
       */
      reloadHints(): void

      /**
       * Posted when inline bot hints should be reloaded.
       * No args.
       */
      reloadInlineHints(): void

      /**
       * Posted when guest bot hints should be reloaded.
       * No args.
       */
      reloadGuestBotHints(): void

      /**
       * Posted when web app (attachment menu) bot hints should be reloaded.
       * No args.
       */
      reloadWebappsHints(): void

      /**
       * Posted when a new or updated draft is received/saved for a dialog.
       * @param dialogId long dialog ID whose draft changed
       */
      newDraftReceived(dialogId: number): void

      /**
       * Posted when recent stickers/GIFs list finishes loading from cache or network.
       * @param isGif boolean true for GIF recents, false for sticker recents
       * @param type  int MediaDataController sticker/GIF type constant (e.g. TYPE_IMAGE, TYPE_FAVE, TYPE_MASK)
       */
      recentDocumentsDidLoad(isGif: boolean, type: number): void

      /**
       * Posted when installing a sticker set results in previously installed sets being archived.
       * @param archivedSets ArrayList<TLRPC.StickerSetCovered> sets that were moved to the archive
       */
      needAddArchivedStickers(archivedSets: Array<TLObject>): void

      /**
       * Fired after archived stickers count is loaded/refreshed for a given sticker type.
       * @param type int — sticker set type (TYPE_IMAGE=0, TYPE_MASK=1, TYPE_EMOJIPACKS=2)
       */
      archivedStickersCountDidLoad(type: number): void

      /**
       * Fired when a payment flow completes successfully.
       * No arguments.
       */
      paymentFinished(): void

      /**
       * Fired when channel admin rights are updated for a chat.
       * @param chat TLRPC.Chat — the chat whose rights changed (mutable object, may be mutated by observers)
       */
      channelRightsUpdated(chat: TLObject): void

      /**
       * Fired to trigger opening an IV article (instant view).
       * @param webPage TLRPC.WebPage — the webpage object to display
       * @param url String — the specific URL/anchor within the article to open
       */
      openArticle(webPage: TLObject, url: string): void

      /**
       * Fired when the ArticleViewer is dismissed/closed.
       * No arguments.
       */
      articleClosed(): void

      /**
       * Fired when the unread mentions count of a dialog is updated.
       * @param dialogId long — dialog id
       * @param arg1 long — always 0L at current call sites (reserved/padding)
       * @param unreadMentionsCount int — new unread mentions count
       */
      updateMentionsCount(dialogId: number, arg1: number, unreadMentionsCount: number): void

      /**
       * Fired when a poll's results are updated.
       * @param pollId long — poll id
       * @param poll TLRPC.Poll — updated poll object
       * @param results TLRPC.PollResults — updated poll results
       */
      didUpdatePollResults(pollId: number, poll: TLObject, results: TLObject): void

      /**
       * Fired when online member count for a chat is loaded.
       * @param chatId long — chat id (used as key)
       * @param onlines int — number of online members
       */
      chatOnlineCountDidLoad(chatId: number, onlines: number): void

      /**
       * Fired when video preloading/loading state changes for a file.
       * @param key String — file key identifying the video
       */
      videoLoadingStateChanged(key: string): void

      /**
       * Fired when new "people nearby" data is available.
       * @param baseUpdate TLRPC.Updates — the raw update containing nearby peers
       */
      newPeopleNearbyAvailable(baseUpdate: TLObject): void

      /**
       * Signals that heavy UI operations (animations, emoji, etc.) should stop.
       * Uses bitmask flags — callers pass their own bit so they can pair stop/start without conflicts.
       * Posted on global NotificationCenter.
       * @param flags int — bitmask of the operation layers being paused (e.g. 1, 2, 4, 8, 16, 512, 4096)
       */
      stopAllHeavyOperations(flags: number): void

      /**
       * Signals that heavy UI operations may resume. Paired with stopAllHeavyOperations.
       * Posted on global NotificationCenter.
       * @param flags int — bitmask of the operation layers being resumed (same bit as the stop call)
       */
      startAllHeavyOperations(flags: number): void

      /**
       * Signals that spoiler animations/reveals should stop (e.g. chat goes to background).
       * No arguments. Posted on global NotificationCenter.
       */
      stopSpoilers(): void

      /**
       * Signals that spoiler animations/reveals should start (e.g. chat comes to foreground).
       * No arguments. Posted on global NotificationCenter.
       */
      startSpoilers(): void

      /**
       * Fired when the set of messages currently being sent changes (enqueued, progressed, completed, failed).
       * No arguments.
       */
      sendingMessagesChanged(): void

      /**
       * Fired when message reactions are updated for a specific message.
       * @param dialogId long — dialog id containing the message
       * @param messageId int — id of the message whose reactions changed
       * @param reactions TLRPC.ReactionCount (or TLRPC.MessageReactions) — updated reactions object (mutable, observers read it)
       */
      didUpdateReactions(dialogId: number, messageId: number, reactions: TLObject): void

      /**
       * Fired when extended media (e.g. invoice media) is updated for a message.
       * @param dialogId long — dialog id
       * @param msgId int — message id
       * @param extendedMedia ArrayList<TLRPC.MessageExtendedMedia> — updated extended media list (mutable)
       */
      didUpdateExtendedMedia(dialogId: number, msgId: number, extendedMedia: Array<TLObject>): void

      /**
       * Fired after sticker verification for a set of messages completes.
       * @param messages ArrayList<JavaObject> — messages whose stickers were verified (mutable list)
       */
      didVerifyMessagesStickers(messages: Array<JavaObject>): void

      /**
       * Fired when scheduled messages for a dialog are loaded or updated.
       * @param dialogId long — dialog id
       * @param count int — number of scheduled messages
       * @param fromStorage boolean — true if count came from local DB, false if from server response
       */
      scheduledMessagesUpdated(dialogId: number, count: number, fromStorage: boolean): void

      /**
       * Fired when the list of account suggestions (e.g. phone/birthday) changes.
       * No arguments.
       */
      newSuggestionsAvailable(): void

      /**
       * Fired when the inviter of a chat participant is loaded.
       * @param chatId long — chat id
       * @param inviterId long — user id of the inviter
       */
      didLoadChatInviter(chatId: number, inviterId: number): void

      /**
       * Fired when the admin list for a chat is loaded.
       * @param chatId long — chat id
       */
      didLoadChatAdmins(chatId: number): void

      /**
       * Fired as history import progresses or fails.
       * Arg count varies:
       *   - 1 arg: progress update only (check SendMessagesHelper.ImportingHistory for state)
       *   - 3 args: error/completion — dismiss UI
       * @param dialogId long — dialog id being imported into (or String shortName for sticker path variant)
       * @param [req] TLRPC.TL_messages_initHistoryImport? — request object (only on error/finish)
       * @param [error] TLRPC.TL_error? — error object, null on success (only on error/finish)
       */
      historyImportProgressChanged(dialogId: number, req?: TLObject, error?: TLObject): void

      /**
       * Fired as sticker pack import progresses or fails.
       * Arg count varies:
       *   - 1 arg: progress update (check SendMessagesHelper.ImportingStickers for state)
       *   - 3 args: error/completion — dismiss UI
       * @param shortName String — sticker pack short name
       * @param [req] TLObject? — request object (only on error/finish)
       * @param [error] TLRPC.TL_error? — error object, null on success (only on error/finish)
       */
      stickersImportProgressChanged(shortName: string, req?: TLObject, error?: TLObject): void

      /**
       * Fired when a sticker pack import completes successfully and has observers.
       * @param stickerSet TLRPC.TL_messages_stickerSet — the freshly imported sticker set
       */
      stickersImportComplete(stickerSet: TLObject): void

      /**
       * Fired when a dialog (or forum topic) is deleted.
       * @param dialogId long — dialog id (negative for channels; for topics: -chatId)
       * @param topicId int — topic id within the channel, 0 for regular dialogs
       */
      dialogDeleted(dialogId: number, topicId: number): void

      /**
       * Fired when a web app inline query result is sent.
       * @param queryId long — the query_id of the sent web view result
       */
      webViewResultSent(queryId: number): void

      /**
       * Fired when a voice message transcription is updated.
       * Arg count varies:
       *   - 1 arg: simple state refresh (e.g. error/cancel, fetch messageObject from args[0])
       *   - 5 args: full update with transcription data
       * @param messageObject JavaObject? — the voice message being transcribed (nullable in MessagesController update path)
       * @param [transcriptionId] Long? — transcription id (null on simple refresh)
       * @param [text] String? — transcription text so far (null on simple refresh)
       * @param [isPremium] Boolean? — whether transcription requires premium (null on simple refresh)
       * @param [isFinal] Boolean? — whether transcription is complete (null on simple refresh or on cancel)
       */
      voiceTranscriptionUpdate(messageObject: JavaObject | null, transcriptionId?: number | null, text?: string | null, isPremium?: boolean | null, isFinal?: boolean | null): void

      /**
       * Fired when an animated emoji document finishes loading for a JavaObject.
       * @param messageObject JavaObject — the message that triggered the emoji load
       */
      animatedEmojiDocumentLoaded(messageObject: JavaObject): void

      /**
       * Fired when the recent emoji statuses list is updated.
       */
      recentEmojiStatusesUpdate(): void

      /**
       * Fired when search-language settings change (e.g. translation language toggled).
       */
      updateSearchSettings(): void

      /**
       * Fired when the audio transcription cooldown lock state changes (e.g. after
       * a transcription completes or the cooldown timer fires).
       */
      updateTranscriptionLock(): void

      /**
       * // never posted
       * Declared in NotificationCenter but no postNotificationName call site found.
       * Inferred from declaration only.
       */
      businessMessagesUpdated(): void

      /**
       * Fired whenever the quick-replies list is mutated (loaded, added, edited,
       * reordered, deleted, etc.). No args — observers re-read the controller state.
       */
      quickRepliesUpdated(): void

      /**
       * Fired when one or more quick-reply messages are deleted.
       * @param messageIds ArrayList of deleted message IDs (number); mutable list owned by caller
       * @param topicId    quick-reply topic ID (long / Long)
       */
      quickRepliesDeleted(messageIds: number[], topicId: number): void

      /**
       * Fired when a web-page bookmark is saved from the article viewer.
       * @param messageObject JavaObject wrapping the bookmarked URL
       */
      bookmarkAdded(messageObject: JavaObject): void

      /**
       * Fired when the user changes the anonymity setting for a paid star-reaction on
       * a specific message (optimistic, before server confirms).
       * @param dialogId dialog ID (long)
       * @param messageId message ID (int)
       * @param peer selected privacy peer ID (long); 0 = default, UserObject.ANONYMOUS = anonymous
       */
      starReactionAnonymousUpdate(dialogId: number, messageId: number, peer: number): void

      /**
       * Fired whenever the business-chat-links list changes (created, deleted, edited,
       * reloaded). No args — observers re-read BusinessLinksController.
       */
      businessLinksUpdated(): void

      /**
       * Fired after a new business chat link is successfully created on the server.
       * @param link TL_account.TL_businessChatLink — the newly created link object
       */
      businessLinkCreated(link: TLObject): void

      /**
       * Fired from ChatActivity when the user confirms deletion of a business link via
       * the in-chat link toolbar, requesting BusinessLinksController to perform the deletion.
       * @param link TL_account.TL_businessChatLink to delete
       */
      needDeleteBusinessLink(link: TLObject): void

      /**
       * Fired when a message's translation state changes (translated, un-translated,
       * or summarized).
       * Arg count varies:
       *   2 args: messageObject translated (success path, single-message translate)
       *   3 args: messageObject translated dialogTranslating (when toggling dialog-level translate off)
       * @param messageObject  JavaObject whose translation changed
       * @param translated     boolean — true = translation applied, false = cleared
       * @param dialogTranslating boolean (optional, 3-arg form only) — whether dialog-level translation is still active
       */
      messageTranslated(messageObject: JavaObject, translated: boolean, dialogTranslating?: boolean): void

      /**
       * Fired when translation of a message is in progress (request sent, awaiting result).
       * @param messageObject JavaObject being translated
       */
      messageTranslating(messageObject: JavaObject): void

      /**
       * Fired when the "is this dialog translatable?" state is determined for a dialog.
       * @param dialogId dialog ID (long / Long)
       */
      dialogIsTranslatable(dialogId: number): void

      /**
       * Fired when dialog-level translation is toggled on or off for a specific dialog.
       * @param dialogId    dialog ID (long / Long)
       * @param translating boolean — true = translation enabled, false = disabled
       */
      dialogTranslate(dialogId: number, translating: boolean): void

      /**
       * Global event. Fired after the biometric (fingerprint) key pair is generated.
       * @param shouldNotifyCheck boolean — whether a fingerprint-check notification should follow
       */
      didGenerateFingerprintKeyPair(shouldNotifyCheck: boolean): void

      /**
       * // never posted
       * Declared in NotificationCenter; no posting call site found in Java or native.
       */
      walletPendingTransactionsChanged(): void

      /**
       * // never posted
       * Declared in NotificationCenter; no posting call site found in Java or native.
       */
      walletSyncProgressChanged(): void

      /**
       * Fired after an HTTP file download completes successfully.
       * @param url    original HTTP URL (String)
       * @param result local file path of the downloaded file (String)
       */
      httpFileDidLoad(url: string, result: string): void

      /**
       * Fired when an HTTP file download fails (after retries exhausted).
       * @param url       original HTTP URL (String)
       * @param errorCode int — 0 in all observed call sites
       */
      httpFileDidFailedLoad(url: string, errorCode: number): void

      /**
       * Fired whenever the network connection state changes (connecting, connected,
       * waiting, etc.).
       */
      didUpdateConnectionState(): void

      /**
       * Fired when a file upload completes successfully.
       * @param location           local file path / attach key (String)
       * @param inputFile          TLRPC.InputFile for unencrypted uploads (nullable)
       * @param inputEncryptedFile TLRPC.InputEncryptedFile for secret-chat uploads (nullable)
       * @param key                encryption key bytes (byte[], nullable)
       * @param iv                 encryption IV bytes (byte[], nullable)
       * @param totalFileSize      total size in bytes (long / Long)
       */
      fileUploaded(location: string, inputFile: TLObject, inputEncryptedFile: TLObject, key: Uint8Array, iv: Uint8Array, totalFileSize: number): void

      /**
       * Fired when a file upload fails.
       * @param location    local file path / attach key (String)
       * @param isEncrypted boolean — whether this was a secret-chat upload
       */
      fileUploadFailed(location: string, isEncrypted: boolean): void

      /**
       * Fired periodically as a file upload progresses.
       * In SendMessagesHelper the sentinel values -1L/-1L/false are used to signal
       * "progress unknown / retry" without updating the visible progress bar.
       * @param location    local file path / attach key (String)
       * @param uploadedSize bytes uploaded so far (long); -1 = unknown
       * @param totalSize    total file size in bytes (long); -1 = unknown
       * @param isEncrypted  boolean — secret-chat upload
       */
      fileUploadProgressChanged(location: string, uploadedSize: number, totalSize: number, isEncrypted: boolean): void

      /**
       * Fired periodically as a file download progresses (both blob cache and
       * direct-file-loader paths).
       * @param url          cache key / URL / file location (String)
       * @param downloadedSize bytes downloaded so far (long)
       * @param totalSize    total expected size in bytes (long)
       */
      fileLoadProgressChanged(url: string, downloadedSize: number, totalSize: number): void

      /**
       * Fired when a file download completes.
       * @param location  cache key / attach file name (String)
       * @param finalFile the downloaded `java.io.File` on disk
       */
      fileLoaded(location: string, finalFile: JavaObject): void

      /**
       * Fired when a file download fails or is cancelled.
       * @param location  cache key / attach file name (String)
       * @param reason    int — 1 = soft failure (from cache queue), 2 = cancelled; 0 not observed but possible
       */
      fileLoadFailed(location: string, reason: number): void

      /**
       * Fired once when a video-conversion job writes its first chunk to disk, signalling
       * that the output file path is valid and upload can begin.
       * @param messageObject    JavaObject being converted (JavaObject)
       * @param filePath         output file path (String)
       * @param progress         conversion progress 0..1 (Float)
       * @param lastFrameTimestamp timestamp of the last encoded frame (long / Long)
       */
      filePreparingStarted(messageObject: JavaObject, filePath: string, progress: number, lastFrameTimestamp: number): void

      /**
       * Fired on each new chunk written during video conversion, allowing incremental
       * upload of the still-encoding file.
       * @param messageObject    JavaObject being converted (JavaObject)
       * @param filePath         output file path (String)
       * @param availableSize    bytes written and available for upload so far (long)
       * @param finalSize        total expected file size when done; 0 if not yet final (long)
       * @param progress         conversion progress 0..1 (Float)
       * @param lastFrameTimestamp timestamp of the last encoded frame (long / Long)
       */
      fileNewChunkAvailable(messageObject: JavaObject, filePath: string, availableSize: number, finalSize: number, progress: number, lastFrameTimestamp: number): void

      /**
       * Posted when video conversion/preparation fails before sending.
       * @param messageObject The message being prepared
       * @param file Path to the (partial) output file
       * @param progress Conversion progress at failure (float)
       * @param lastFrameTimestamp Timestamp of last successfully encoded frame (long)
       */
      filePreparingFailed(messageObject: JavaObject, file: string, progress: number, lastFrameTimestamp: number): void

      /**
       * Posted when the push notification unread counter changes.
       * @param pushDialogsCount New unread dialogs count (int)
       */
      dialogsUnreadCounterChanged(pushDialogsCount: number): void

      /**
       * Posted periodically during audio/voice message playback with updated progress.
       * @param messageId ID of the currently playing message (int, from JavaObject.getId())
       * @param progress Current playback progress 0.0–1.0 (float); 0 when stopping/resetting
       */
      messagePlayingProgressDidChanged(messageId: number, progress: number): void

      /**
       * Posted when playback is fully stopped and the player is reset.
       * @param messageId ID of the message that was playing (int)
       * @param stopService Whether to stop the background playback service (boolean)
       */
      messagePlayingDidReset(messageId: number, stopService: boolean): void

      /**
       * Posted when the play/pause state of the current message changes.
       * @param messageId ID of the affected message (int); 0 when no message is playing
       */
      messagePlayingPlayStateChanged(messageId: number): void

      /**
       * Posted when a new audio/voice message starts playing.
       * @param messageObject The message now playing (JavaObject)
       * @param oldMessageObject The previously playing message, may be null (JavaObject | null)
       */
      messagePlayingDidStart(messageObject: JavaObject, oldMessageObject: JavaObject | null): void

      /**
       * Posted after a seek operation completes.
       * @param messageId ID of the message being seeked (int)
       * @param progress New playback position 0.0–1.0 (float)
       */
      messagePlayingDidSeek(messageId: number, progress: number): void

      /**
       * Posted just before playback stops, giving observers a chance to react before state is cleared.
       * @param messageObject The message about to stop (JavaObject)
       * @param stopService Whether the playback service will be stopped (boolean)
       */
      messagePlayingGoingToStop(messageObject: JavaObject, stopService: boolean): void

      /**
       * Posted on UI thread during audio recording with updated amplitude.
       * Posted by both MediaController (voice) and InstantCameraView (round video).
       * @param recordingGuid Unique ID of the current recording session (int)
       * @param amplitude Current microphone amplitude (float)
       */
      recordProgressChanged(recordingGuid: number, amplitude: number): void

      /**
       * Posted when audio/video recording successfully starts.
       * @param guid Unique ID of the recording session (int)
       * @param isVideo true for round video (InstantCameraView), false for voice (MediaController) (boolean)
       */
      recordStarted(guid: number, isVideo: boolean): void

      /**
       * Posted when recording fails to start (permission denied, device error, etc.).
       * @param guid Unique ID of the failed recording session (int)
       */
      recordStartError(guid: number): void

      /**
       * Posted when recording stops. Reason encodes how/why recording ended.
       * Reason values (int): 0 = cancel by gesture, 1 = sent (audio), 2 = sent (video/normal stop),
       * 4 = cancel by time limit, 5 = video sent via state, 6 = stop without gesture.
       * @param guid Unique ID of the recording session (int)
       * @param reason Stop reason code (int)
       */
      recordStopped(guid: number, reason: number): void

      /**
       * Posted when voice recording is paused (lock-mode pause).
       * No args beyond the event ID.
       */
      recordPaused(): void

      /**
       * Posted when a paused voice/video recording is resumed.
       * No args beyond the event ID.
       */
      recordResumed(): void

      /**
       * Posted when the OS screenshot detector fires while a secret chat is visible.
       * No args.
       */
      screenshotTook(): void

      /**
       * Posted (on global NotificationCenter) after device gallery albums finish loading.
       * @param guid Request GUID to match against the caller's classGuid (int)
       * @param mediaAlbumsSorted All-media albums sorted (ArrayList<MediaController.AlbumEntry>)
       * @param photoAlbumsSorted Photo-only albums sorted (ArrayList<MediaController.AlbumEntry>)
       * @param cameraAlbumId ID of the camera roll album (long)
       */
      albumsDidLoad(guid: number, mediaAlbumsSorted: Array<JavaObject>, photoAlbumsSorted: Array<JavaObject>, cameraAlbumId: number): void

      /**
       * Posted when a voice or round-video recording is ready to send.
       * Arg count varies by source and media type:
       *   Voice (pause-send path, 7 args): guid, TL_document, filePath, fromDraft=true, draftLeft, draftRight
       *   Voice (normal stop, 3 args):     guid, TL_document | null, filePath | null
       *   Round video (4 args):            guid, VideoEditedInfo, filePath, ArrayList<Bitmap> keyframeThumbs
       * Handlers check args.length and instanceof to distinguish.
       * @param guid Recording session GUID (int)
       * @param audio TL_document for voice or VideoEditedInfo for round video; null when cancelled (Object | null)
       * @param filePath Absolute path to the recorded file; null when cancelled (String | null)
       * @param [keyframesOrFromDraft] ArrayList<Bitmap> keyframes (round video) or boolean fromDraft (voice draft) (Object?) — optional
       * @param [draftLeft] Draft waveform left trim (float) — voice draft only, optional
       * @param [draftRight] Draft waveform right trim (float) — voice draft only, optional
       */
      audioDidSent(guid: number, audio: object | null, filePath: string | null, keyframesOrFromDraft?: object, draftLeft?: number, draftRight?: number): void

      /**
       * Posted when a recording is too short to send.
       * @param guid Recording session GUID (int)
       * @param isVideo true if round-video, false if voice (boolean)
       * @param duration Recorded duration in milliseconds (int)
       */
      audioRecordTooShort(guid: number, isVideo: boolean, duration: number): void

      /**
       * Posted when the audio output route changes (earpiece ↔ speaker) during voice message playback.
       * @param useFrontSpeaker true = earpiece/front speaker, false = main speaker (boolean)
       */
      audioRouteChanged(useFrontSpeaker: boolean): void

      /**
       * Posted on global NotificationCenter when a VoIP or group call UI becomes active.
       * No args.
       */
      didStartedCall(): void

      /**
       * Posted when a group call's state is updated (participants, schedule, call object, etc.).
       * Normally 4 args; one call site passes a 5th arg (justJoinedId) when a participant just joined.
       * @param chatId Chat ID owning the call; 0 for channel/conference calls (long)
       * @param callId The group call ID (long)
       * @param selfUpdated true if the local participant's own state changed (boolean)
       * @param [justJoinedId] Peer ID of the participant that just joined, 0 if none (long?) — optional
       */
      groupCallUpdated(chatId: number, callId: number, selfUpdated: boolean, justJoinedId?: number): void

      /**
       * Posted when a story's associated live/group call is updated.
       * @param dialogId Dialog ID of the story owner (long)
       * @param call Updated group call object (TLRPC.GroupCall)
       */
      storyGroupCallUpdated(dialogId: number, call: TLObject): void

      /**
       * Posted when the set of actively speaking participants in a group call changes.
       * @param chatId Chat ID owning the call (long)
       * @param callId The group call ID (long)
       * @param selfUpdated true if the local user's speaking state changed (boolean)
       */
      groupCallSpeakingUsersUpdated(chatId: number, callId: number, selfUpdated: boolean): void

      /**
       * Posted when the local screencast state in a group call changes (started or stopped).
       * No args.
       */
      groupCallScreencastStateChanged(): void

      /**
       * Posted when the map of active group calls (chats with live calls) changes.
       * No args.
       */
      activeGroupCallsUpdated(): void

      /**
       * Posted to trigger a batch update of which participants are visible on screen,
       * used to batch participant load requests.
       * @param time Current elapsed realtime in ms (long, from SystemClock.elapsedRealtime())
       */
      applyGroupCallVisibleParticipants(time: number): void

      /**
       * Posted when the list of users "typing" in a group call (raise-hand / active speakers indicator) changes.
       * No args.
       */
      groupCallTypingsUpdated(): void

      /**
       * Posted on global NotificationCenter when a VoIP call ends.
       * No args.
       */
      didEndCall(): void

      /**
       * Posted on global NotificationCenter to signal the in-call UI activity should close.
       * No args.
       */
      closeInCallActivity(): void

      /**
       * Fired when the group call / live story / RTMP pip overlay visibility changes.
       * No args — observers re-query visibility from GroupCallPip/LiveStoryPipOverlay state.
       */
      groupCallVisibilityChanged(): void

      /**
       * Fired when a live story call state changes (empty stream, participant joined/left, call ended, recording started/stopped).
       * @param callId — long; the group call id the update pertains to
       */
      liveStoryUpdated(callId: number): void

      /**
       * Fired when a group call message arrives or is deleted during a live story.
       * arg count varies: 3 always.
       * @param callId    — long; the group call id
       * @param update    — TL_update.TL_updateGroupCallMessage | TL_update.TL_updateDeleteGroupCallMessages; the raw update
       * @param isHistory — boolean; true when replaying buffered history on join, false for live updates
       */
      liveStoryMessageUpdate(callId: number, update: TLObject, isHistory: boolean): void

      /**
       * Fired after the current account is fully logged out and its data cleared.
       * No args.
       */
      appDidLogout(): void

      /**
       * Fired when the server config (getDifference / getConfig response) has been applied.
       * No args.
       */
      configLoaded(): void

      /**
       * Fired to request deletion of a dialog from the dialogs list (typically after leaving/deleting from profile or chat screen).
       * @param dialogId — long; target dialog id (negative for chats)
       * @param user     — TLRPC.User | null; non-null for user dialogs, null for group/channel
       * @param chat     — TLRPC.Chat | null; non-null for group/channel dialogs, null for user
       * @param param    — Boolean; revoke flag for non-bot users, blockBot flag for bots
       */
      needDeleteDialog(dialogId: number, user: TLObject, chat: TLObject, param: boolean): void

      /**
       * Fired when emoji keyword suggestions for a language have been refreshed in the local DB.
       * @param lang — String; language code whose suggestions were updated
       */
      newEmojiSuggestionsAvailable(lang: string): void

      /**
       * Fired when a theme or accent has been successfully uploaded to the server.
       * @param themeInfo — Theme.ThemeInfo; the theme that was uploaded
       * @param accent    — Theme.ThemeAccent | null; the accent being uploaded, or null if uploading the base theme
       */
      themeUploadedToServer(themeInfo: JavaObject, accent: JavaObject | null): void

      /**
       * Fired when a theme/accent upload to the server fails.
       * @param themeInfo — Theme.ThemeInfo; the theme that failed to upload
       * @param accent    — Theme.ThemeAccent | null; the accent that failed, or null if uploading base theme
       */
      themeUploadError(themeInfo: JavaObject, accent: JavaObject | null): void

      /**
       * Fired when the folder/filter list changes (reorder, add, delete, server sync, or local edit).
       * No args — observers re-read MessagesController.dialogFilters.
       */
      dialogFiltersUpdated(): void

      /**
       * Fired when filter-level settings within an existing folder change (e.g. include/exclude peers).
       * No args.
       */
      filterSettingsUpdated(): void

      /**
       * Fired when the list of suggested dialog filters from the server is loaded.
       * No args — observers read MessagesController.suggestedFilters.
       */
      suggestedFiltersLoaded(): void

      /**
       * Fired when a bot menu button update is received from the server.
       * @param botId         — long; the bot's user id
       * @param botMenuButton — TL_bots.BotMenuButton; the new menu button (may be TL_botMenuButton or TL_botMenuButtonDefault)
       */
      updateBotMenuButton(botId: number, botMenuButton: TLObject): void

      /**
       * Fired after a gift has been successfully sent to a user.
       * No args.
       */
      giftsToUserSent(): void

      /**
       * Fired to signal that the multi-gift selector flow should be opened.
       * No args.
       */
      didStartedMultiGiftsSelector(): void

      /**
       * Fired after the user successfully boosts a channel by reassigning existing boosts.
       * @param myBoosts             — TL_stories.TL_premium_myBoosts; the resulting boost state
       * @param boostedSlotsCount    — int; number of slots applied
       * @param uniqueChannelCount   — int; number of distinct channels that lost a boost
       * @param boostsStatus         — TL_stories.TL_premium_boostsStatus; updated boost status for the target channel
       */
      boostedChannelByUser(myBoosts: TLObject, boostedSlotsCount: number, uniqueChannelCount: number, boostsStatus: TLObject): void

      /**
       * Fired after a giveaway or regular boost has been created for a channel.
       * arg count varies: 2 (regular boost) or 3 (prepaid giveaway).
       * @param chat             — TLRPC.Chat; the channel being boosted
       * @param isGiveaway       — boolean; true if this was a giveaway
       * @param prepaidGiveaway  — TL_stories.PrepaidGiveaway | undefined; only present when isGiveaway=true and launched from a prepaid giveaway
       */
      boostByChannelCreated(chat: TLObject, isGiveaway: boolean, prepaidGiveaway?: TLObject): void

      /**
       * Fired when the premium gift sticker set has been loaded/refreshed.
       * No args — observers re-read MediaDataController state.
       */
      didUpdatePremiumGiftStickers(): void

      /**
       * Fired when the TON gift sticker set has been loaded/refreshed.
       * No args.
       */
      didUpdateTonGiftStickers(): void

      /**
       * Fired when the premium gift field icon (small animated icon shown in the text field) is updated.
       * No args.
       */
      didUpdatePremiumGiftFieldIcon(): void

      /**
       * Fired when the Stories enabled/disabled state changes for the account or globally.
       * No args — observers re-read StoriesController / MessagesController state.
       */
      storiesEnabledUpdate(): void

      /**
       * Fired when the stories blocklist (hidden story senders) changes.
       * No args — observers re-read StoriesController.blocklist.
       */
      storiesBlocklistUpdate(): void

      /**
       * Fired when the account's daily story posting limit or remaining count changes.
       * No args.
       */
      storiesLimitUpdate(): void

      /**
       * Fired when the "send as" peer selection for stories changes.
       * No args — observers re-read StoriesController sendAsPeers state.
       */
      storiesSendAsUpdate(): void

      /**
       * Fired whenever the list of unconfirmed login authorizations changes (loaded, accepted, or rejected).
       * No args — observers query UnconfirmedAuthController directly.
       */
      unconfirmedAuthUpdate(): void

      /**
       * Fired when a dialog's avatar/photo list changes (photo loaded, added, or removed).
       * @param dialogPhotos — MessagesController.DialogPhotos; the mutable photo list object for the dialog (mutated in-place by the controller)
       */
      dialogPhotosUpdate(dialogPhotos: JavaObject): void

      /**
       * Fired when channel recommendations for a specific dialog have been loaded or refreshed.
       * @param dialogId — long; the dialog id whose recommendations changed (negative = channel)
       */
      channelRecommendationsLoaded(dialogId: number): void

      /**
       * Fired when the list of saved-messages sub-dialogs changes.
       * No args — observers re-read SavedMessagesController state.
       */
      savedMessagesDialogsUpdate(): void

      /**
       * Fired when saved-message reaction tags change for a topic.
       * @param topicId — long; the saved-messages topic id affected; 0 means all topics
       */
      savedReactionTagsUpdate(topicId: number): void

      /**
       * Posted when the premium-blocked status of one or more contacts changes
       * (after loading userFull or updating contact-blocked state in storage).
       */
      userIsPremiumBlockedUpadted(): void

      /**
       * Posted when story album collections for a dialog are loaded or updated.
       * @param dialogId - dialog whose albums changed
       * @param collections - the live StoriesCollections object (mutable; observers should not hold long-term references)
       */
      storyAlbumsCollectionsUpdate(dialogId: number, collections: JavaObject): void

      /**
       * Posted on UI thread after messages are forwarded to Saved Messages and server confirms new IDs.
       * @param newMessagesByIds - map from new message id -> random_id used during send (mutable Map<number, number>)
       */
      savedMessagesForwarded(newMessagesByIds: Map<number, number>): void

      /**
       * Posted when emoji keywords finish loading (any language or locale).
       * No args.
       */
      emojiKeywordsLoaded(): void

      // never posted; infer from declaration only
      smsJobStatusUpdate(): void

      /**
       * Posted when story quality setting changes (loaded from server or toggled).
       * No args.
       */
      storyQualityUpdate(): void

      /**
       * Posted to open the Boost for Users / boost channel dialog.
       * Second arg is optional: only present when triggered from a boost-counter cell tap.
       * @param dialogId - target dialog id (long, may be negative for channels)
       * @param cell - (optional) the ChatMessageCell that was tapped
       */
      openBoostForUsersDialog(dialogId: number, cell?: JavaObject): void

      /**
       * Posted on UI thread when group restrictions are unlocked via boosts
       * (boost count reached the required threshold).
       * No args.
       */
      groupRestrictionsUnlockedByBoosts(): void

      /**
       * Posted on UI thread after the current user successfully boosts a channel.
       * @param boostsStatus - current boost status returned by the server
       * @param canApplyBoost - copy of the CanApplyBoost state at boost time
       * @param dialogId - the boosted dialog id
       */
      chatWasBoostedByUser(boostsStatus: TLObject, canApplyBoost: JavaObject, dialogId: number): void

      /**
       * Posted after the group sticker/emoji pack is updated server-side.
       * @param chatId - the chat id whose pack changed (info.id, positive)
       * @param isEmoji - true if the updated pack is an emoji pack, false for sticker pack
       */
      groupPackUpdated(chatId: number, isEmoji: boolean): void

      /**
       * Posted when timezone list is loaded or refreshed from the server.
       * No args.
       */
      timezonesUpdated(): void

      /**
       * Posted when a custom sticker creation/upload attempt completes.
       * Arg count varies by path (0–5 args):
       *   - 0 args: sent via customHandler path (upload handed off externally)
       *   - 1 arg (Boolean true): sticker sent directly to a dialog (not added to set)
       *   - 2 args (Boolean false, null-or-absent): addToFavorite or sendToDialog path — no set returned
       *   - 5 args (Boolean false, TL_messages_stickerSet, TLRPC.Document, String|null, Boolean): full upload
       *     success; 5th arg = isReplacing (true = replaced existing sticker, false = added/created)
       * @param isSending - false = sticker added to pack; true = sticker sent as message
       * @param stickerSet - (optional) the resulting sticker set
       * @param document - (optional) the uploaded sticker document
       * @param thumbPath - (optional, nullable) local path to the thumbnail
       * @param isReplacing - (optional) whether this replaced an existing sticker in the set
       */
      customStickerCreated(isSending?: boolean, stickerSet?: TLObject, document?: TLObject, thumbPath?: string | null, isReplacing?: boolean): void

      /**
       * Posted when a premium flood-wait error is received from the server.
       * No args.
       */
      premiumFloodWaitReceived(): void

      /**
       * Posted when the available message effects list is loaded or updated.
       * No args.
       */
      availableEffectsUpdate(): void

      /**
       * Posted when star purchase options (prices) finish loading.
       * No args.
       */
      starOptionsLoaded(): void

      /**
       * Posted when star gift purchase options finish loading.
       * No args.
       */
      starGiftOptionsLoaded(): void

      /**
       * Posted when star giveaway options finish loading.
       * No args.
       */
      starGiveawayOptionsLoaded(): void

      /**
       * Posted when the user's star balance changes (loaded, updated, or a pending debit clears).
       * No args; observers call StarsController.getInstance to read the new value.
       */
      starBalanceUpdated(): void

      /**
       * Posted when a page of star transactions finishes loading.
       * No args; observers read from the StarsController transaction lists directly.
       */
      starTransactionsLoaded(): void

      /**
       * Posted when star subscriptions finish loading (initial load or next page).
       * No args.
       */
      starSubscriptionsLoaded(): void

      /**
       * Posted when fact-check data for one or more messages is fetched and callbacks are resolved.
       * No args.
       */
      factCheckLoaded(): void

      /**
       * Posted when bot stars revenue stats are loaded or refreshed (stars or TON variant).
       * @param dialogId - the bot/channel dialog id whose stats were updated
       */
      botStarsUpdated(dialogId: number): void

      /**
       * Posted when bot stars transactions finish loading for a dialog.
       * @param dialogId - the dialog id whose transactions were loaded
       */
      botStarsTransactionsLoaded(dialogId: number): void

      // never posted; infer from declaration only
      channelStarsUpdated(): void

      /**
       * Posted to trigger a full redraw of all messages in a chat (e.g. after topic slowmode change).
       * @param chatId - negative chat id (pass as -chatId from the call site)
       */
      updateAllMessages(chatId: number): void

      /**
       * Posted when the catalog of purchasable star gifts finishes loading or refreshing.
       * No args.
       */
      starGiftsLoaded(): void

      /**
       * Posted when a user's received star gifts list loads or changes.
       * @param dialogId - owner dialog id
       * @param list - the live GiftsList object for the given dialog (mutable)
       */
      starUserGiftsLoaded(dialogId: number, list: JavaObject): void

      /**
       * Posted when the collections metadata for a user's star gifts loads or changes.
       * @param dialogId - owner dialog id
       * @param collections - the live GiftsCollections object (mutable)
       */
      starUserGiftCollectionsLoaded(dialogId: number, collections: JavaObject): void

      /**
       * Posted when a star gift's availability drops to zero (sold out).
       * @param starGift - the StarGift object whose availability_remains was set to 0
       */
      starGiftSoldOut(starGift: TLObject): void

      /**
       * Never posted anywhere in the codebase; only defined in NotificationCenter.java.
       * Handlers not found. Likely dead / reserved.
       */
      // never posted
      updateStories(): void

      /**
       * Fired when the bot downloads list changes.
       * Posted from BotDownloads.java with no extra args.
       */
      botDownloadsUpdate(): void

      /**
       * Fired when suggested bots for a channel are updated.
       * @param dialogId - long, channel dialog id
       */
      channelSuggestedBotsUpdate(dialogId: number): void

      /**
       * Fired when connected bots for a channel are updated.
       * @param dialogId - long, channel dialog id
       */
      channelConnectedBotsUpdate(dialogId: number): void

      /**
       * Fired when the list of admined channels has been loaded.
       * Posted from BotStarsController.java with no extra args.
       */
      adminedChannelsLoaded(): void

      /**
       * Fired when the paid message fee for a user is updated.
       * @param userId - long, target user/dialog id
       */
      messagesFeeUpdated(userId: number): void

      /**
       * Fired when a common chats list for a dialog is loaded.
       * @param dialogId - long, dialog id whose common chats were loaded
       * @param list - MessagesController.CommonChatsList, the loaded list (mutable, observers read from it)
       */
      commonChatsLoaded(dialogId: number, list: JavaObject): void

      /**
       * Fired when the app config is updated.
       * Posted from MessagesController.java with no extra args.
       */
      appConfigUpdated(): void

      /**
       * Fired when the active gift auctions list is updated.
       * Posted from GiftAuctionController.java with no extra args.
       */
      activeAuctionsUpdated(): void

      /**
       * Fired when the emoji set for a conference call changes.
       * Posted from ConferenceCall.java with no extra args.
       */
      conferenceEmojiUpdated(): void

      /**
       * Fired when content settings (sensitive content policy etc.) are loaded.
       * Posted from MessagesController.java with no extra args.
       */
      contentSettingsLoaded(): void

      /**
       * Fired when a music list (SavedMusicList or MessagesController.SavedMusicList) finishes loading.
       * @param list - MessagesController.SavedMusicList, the loaded list (mutable, observers read its .list)
       * Note: AudioPlayerAlert also posts this with its own SavedMusicList instance.
       */
      musicListLoaded(list: JavaObject): void

      /**
       * Fired when music message ids (track ordering) are loaded for the profile playlist.
       * Posted from MessagesController.java with no extra args.
       */
      musicIdsLoaded(): void

      /**
       * Fired when profile music (bio music track) for a dialog is updated.
       * @param dialogId - long, dialog whose profile music changed
       */
      profileMusicUpdated(dialogId: number): void

      /**
       * Fired when a chat member rank is updated.
       * @param chatId - long, chat id (negated channel id from MessagesStorage; plain chat id from MessagesController)
       * @param userId - long, user id whose rank changed
       * @param rank - String, new rank string (may be empty)
       */
      updatedChatRanks(chatId: number, userId: number, rank: string): void

      /**
       * Fired when the current user joins a group (non-channel megagroup).
       * @param chatId - long, chat id of the joined group
       */
      joinedGroup(chatId: number): void

      /**
       * Fired when AI compose tones finish loading.
       * @param controller - AiTonesController, the controller that finished loading (immutable data)
       */
      loadedAiComposeTones(controller: JavaObject): void

      /**
       * Fired when the business chatbot configuration is updated.
       * Posted from BusinessChatbotController.java with no extra args.
       */
      updatedChatbot(): void

      /**
       * Fired on global NC when the active Telegram account is switched.
       * @param account - int, the newly active account index
       */
      activeAccountChanged(account: number): void

      /**
       * Fired on global NC when the push notification message list changes.
       * Posted from NotificationsController.java with no extra args.
       */
      pushMessagesUpdated(): void

      /**
       * Fired on global NC when wallpapers are loaded from local DB.
       * @param wallPapers - ArrayList<TLRPC.WallPaper>, the loaded wallpaper list (mutable)
       */
      wallpapersDidLoad(wallPapers: Array<TLObject>): void

      /**
       * Fired on global NC when a specific wallpaper needs to be reloaded from network.
       * @param slug - String, the wallpaper slug that needs reloading
       */
      wallpapersNeedReload(slug: string): void

      /**
       * Fired on global NC when an SMS verification code is received.
       * @param code - String, the extracted SMS code
       */
      didReceiveSmsCode(code: string): void

      /**
       * Fired on global NC when an incoming call is received (used for login call-flash auth).
       * @param phone - String, the calling phone number (stripped to digits)
       */
      didReceiveCall(phone: string): void

      /**
       * Fired on global NC when emoji/sticker sprite sheets finish loading.
       * Posted with no extra args. Also fired after code highlighting emoji load.
       */
      emojiLoaded(): void

      /**
       * Fired on global NC when a motion background drawable needs to invalidate all its observers.
       * Posted from MotionBackgroundDrawable with no extra args.
       */
      invalidateMotionBackground(): void

      /**
       * Fired on global NC when a new app activity (LaunchActivity/BubbleActivity/ExternalActionActivity)
       * becomes active; other activity instances should finish themselves.
       * @param activity - Activity (LaunchActivity | BubbleActivity | ExternalActionActivity), the new foreground activity
       */
      closeOtherAppActivities(activity: JavaObject): void

      /**
       * Fired on global NC when the camera hardware finishes initializing.
       * Posted from CameraController with no extra args.
       */
      cameraInitied(): void

      /**
       * Fired on global NC when a cached image in the memory cache is replaced with a new key/location.
       * @param oldKey - String, the old cache key (may include @filter suffix)
       * @param newKey - String, the new cache key (may include @filter suffix)
       * @param newLocation - ImageLocation, the new image location object
       */
      didReplacedPhotoInMemCache(oldKey: string, newKey: string, newLocation: JavaObject): void

      /**
       * Fired after a new theme has been applied globally.
       * @param nightTheme whether this is the night theme being set
       * @param checkNavigationBarColor whether to update the nav bar color (optional, defaults to true)
       * @param forceCheckKeyboardColor force-refresh keyboard bar color (optional, only from ChatActivity)
       * Arg count varies: 1–3. Third arg only posted from ChatActivity when toggling in-chat theme.
       */
      didSetNewTheme(nightTheme: boolean, checkNavigationBarColor?: boolean, forceCheckKeyboardColor?: boolean): void

      /**
       * Fired when the list of available themes has changed (added/deleted/imported).
       * No args.
       */
      themeListUpdated(): void

      /**
       * Fired when the user explicitly confirms a theme preview and applies it.
       * @param previousTheme the theme that was active before the preview (ThemeInfo)
       * @param previousAccent the accent that was active before (ThemeAccent, may be null)
       * @param deleteOnCancel whether the applied theme file should be deleted on undo (boolean)
       */
      didApplyNewTheme(previousTheme: JavaObject, previousAccent: JavaObject | null, deleteOnCancel: boolean): void

      /**
       * Fired when the list of theme accents has changed.
       * No args.
       */
      themeAccentListUpdated(): void

      /**
       * Fired to request that status/nav bar colors be re-evaluated for the current screen.
       * @param instant apply immediately without animation (optional boolean, omitted = false)
       * Arg count varies: 0–1.
       */
      needCheckSystemBarColors(instant?: boolean): void

      /**
       * Fired to trigger sharing a theme or accent.
       * @param theme the theme to share (ThemeInfo)
       * @param accent the accent to share (ThemeAccent, may be null when sharing plain theme)
       */
      needShareTheme(theme: JavaObject, accent: JavaObject | null): void

      /**
       * Fired to trigger a day/night theme switch, optionally with an animated transition.
       * Arg count varies: 4–10. Core args:
       * @param theme target ThemeInfo
       * @param nightTheme boolean — whether this is the night theme
       * @param pos int[] click position for ripple animation, or null for instant switch
       * @param accentId int — accent id, or -1
       * Additional optional args (positional, may be absent):
       *   [4] toDark (boolean), [5] animatingView (RLottieImageView | null), [6] rippleAbove (View | null),
       *   [7] then (Runnable | null), [8] colorNotDark (boolean)
       * Also posted with a 6th arg (fallbackKeys: String[]) from Theme.ThemeInfo.
       */
      needSetDayNightTheme(
        theme: JavaObject,
        nightTheme: boolean,
        pos: number[] | null,
        accentId: number,
        toDark?: boolean,
        animatingView?: JavaObject | null,
        rippleAbove?: JavaObject | null,
        then?: JavaObject | null,
        colorNotDark?: boolean
      ): void

      /**
       * Fired just before the app enters theme preview mode.
       * No args.
       */
      goingToPreviewTheme(): void

      /**
       * Fired when location permission is granted by the user.
       * @param fromMediaGeo optional int=1 when the grant came from the media geolocation request code; absent for normal geolocation
       * Arg count: 0 or 1.
       */
      locationPermissionGranted(fromMediaGeo?: number): void

      /**
       * Fired when location permission is denied by the user.
       * @param fromMediaGeo optional int=1 when denial came from the media geolocation request code; absent for normal geolocation
       * Arg count: 0 or 1.
       */
      locationPermissionDenied(fromMediaGeo?: number): void

      /**
       * Fired when the UI locale/language has changed and all screens should reload strings.
       * No args.
       */
      reloadInterface(): void

      /**
       * Fired when the server sends a suggested language pack for the user.
       * No args.
       */
      suggestedLangpack(): void

      /**
       * Fired when the chat wallpaper has been changed.
       * No args.
       */
      didSetNewWallpapper(): void

      /**
       * Fired when proxy settings have changed (added, deleted, toggled).
       * No args.
       */
      proxySettingsChanged(): void

      /**
       * Fired when a proxy connectivity check finishes.
       * @param proxyInfo the proxy whose check completed (SharedConfig.ProxyInfo)
       */
      proxyCheckDone(proxyInfo: JavaObject): void

      /**
       * Fired when the active proxy has been rotated automatically by ProxyRotationController.
       * No args.
       */
      proxyChangedByRotation(): void

      /**
       * Fired when the set of active live location shares changes (started, stopped, updated).
       * No args.
       */
      liveLocationsChanged(): void

      /**
       * Fired when a new device location fix is available from the location service.
       * No args.
       */
      newLocationAvailable(): void

      /**
       * Fired when the live-location cache for a specific dialog has been refreshed.
       * @param dialogId dialog id whose cache changed (long)
       * @param account account index (int) — only present when posted from LocationController; absent when posted from LocationActivity
       * Arg count: 1 or 2.
       */
      liveLocationsCacheChanged(dialogId: number, account?: number): void

      /**
       * Fired when the unread notifications count badge needs to be refreshed.
       * @param account account index that triggered the update (int)
       */
      notificationsCountUpdated(account: number): void

      /**
       * Fired when a VideoPlayer begins playback, so other players can pause.
       * @param player the VideoPlayer that started (VideoPlayer)
       */
      playerDidStartPlaying(player: JavaObject): void

      /**
       * Fired to close any active search UI because a navigation action has taken focus.
       * No args.
       */
      closeSearchByActiveAction(): void

      /**
       * Fired when the voice/audio message playback speed has been changed.
       * No args.
       */
      messagePlayingSpeedChanged(): void

      /**
       * Fired when the screen turns on or off (ACTION_SCREEN_ON / ACTION_SCREEN_OFF).
       * No args.
       */
      screenStateChanged(): void

      /**
       * Fired after the local message database has been fully cleared.
       * No args.
       */
      didClearDatabase(): void

      /**
       * Fired when a VoIP call service instance is created and ready.
       * No args. Posted on the UI thread; account-scoped.
       */
      voipServiceCreated(): void

      /**
       * Fired periodically with the current microphone amplitude level during a call.
       * @param amplitude current mic amplitude of one participant (float, 0.0–1.0)
       */
      webRtcMicAmplitudeEvent(amplitude: number): void

      /**
       * Fired periodically with the current speaker/remote amplitude level during a call.
       * @param amplitude max speaker amplitude across participants (float)
       */
      webRtcSpeakerAmplitudeEvent(amplitude: number): void

      /**
       * Fired to display a bulletin (toast-like overlay) in the foreground fragment.
       * @param type bulletin type constant (int) — one of Bulletin.TYPE_* constants:
       *   TYPE_ERROR: args[1] = String (error text)
       *   TYPE_SUCCESS: args[1] = String (success text)
       *   TYPE_ERROR_SUBTITLE: args[1] = String (title), args[2] = String (subtitle)
       *   TYPE_STICKER: args[1] = TLRPC.Document (sticker), args[2] = int (StickerSetBulletinLayout type)
       *   TYPE_APP_ICON: args[1] = LauncherIconController.LauncherIcon
       *   TYPE_NAME_CHANGED: args[1] = long (peerId)
       *   TYPE_BIO_CHANGED: args[1] = long (peerId)
       * Arg count varies by type (2–3).
       */
      showBulletin(type: number, ...payload: any[]): void

      /**
       * Fired when an app update APK is available and ready to prompt.
       * Posted from SettingsActivity, FileRefController (on ref refresh), LaunchActivity (on version check), ProfileActivity.
       */
      appUpdateAvailable(): void

      /**
       * Never posted anywhere in the codebase; only observed by MainTabsActivity.
       * Likely intended for download-progress tracking of an update file.
       */
      // never posted
      appUpdateLoading(): void

      /**
       * Signals a database migration step.
       * @param finished boolean — true when migration is fully complete, false when starting
       */
      onDatabaseMigration(finished: boolean): void

      /**
       * Fired when an emoji interaction typing action is received from another user.
       * @param dialogId long — positive = user_id, negative = chat_id (already negated at call site)
       * @param action TLRPC.TL_sendMessageEmojiInteraction — the interaction payload
       */
      onEmojiInteractionsReceived(dialogId: number, action: TLObject): void

      /**
       * Fired when the set of emoji preview themes (status/reaction previews) changes.
       * Posted from MediaDataController on theme list update.
       */
      emojiPreviewThemesChanged(): void

      /**
       * Fired when the available reactions list finishes loading.
       * Posted from MediaDataController.
       */
      reactionsDidLoad(): void

      /**
       * Fired when the attach-menu bots list is loaded or refreshed.
       * Posted from MediaDataController, BotWebViewSheet, BotWebViewAttachedSheet.
       */
      attachMenuBotsDidLoad(): void

      /**
       * Fired when the set of available reactions for a specific chat is updated.
       * @param chatId long — chat identifier (positive)
       * @param unused long — always 0L at all call sites (reserved/padding)
       */
      chatAvailableReactionsUpdated(chatId: number, unused: number): void

      /**
       * Never posted anywhere in the codebase; declared in NotificationCenter at line 330.
       * Inferred from name: would signal a change to the unread reactions badge counter for dialogs.
       */
      // never posted
      dialogsUnreadReactionsCounterChanged(): void

      /**
       * Never posted anywhere in the codebase; declared in NotificationCenter at line 331.
       * Inferred from name: would signal a change to the unread poll-votes badge counter for dialogs.
       */
      // never posted
      dialogsUnreadPollVotesCounterChanged(): void

      /**
       * Fired when the local database has been opened and is ready for use.
       * Posted from MessagesStorage after database initialization.
       */
      onDatabaseOpened(): void

      /**
       * Fired when the set of actively downloading files changes (added, removed, or state changed).
       * Posted from FileLoader and DownloadController on queue mutations.
       */
      onDownloadingFilesChanged(): void

      /**
       * Fired from LaunchActivity.onActivityResult to broadcast the raw Android result globally.
       * @param requestCode int — activity request code
       * @param resultCode int — Android result code (RESULT_OK etc.)
       * @param data Intent | null — result intent, may be null
       */
      onActivityResultReceived(requestCode: number, resultCode: number, data: JavaObject | null): void

      /**
       * Fired from LaunchActivity.onRequestPermissionsResult to broadcast the raw Android result globally.
       * @param requestCode int — permission request code
       * @param permissions String[] — array of requested permission strings
       * @param grantResults int[] — array of grant results per permission
       */
      onRequestPermissionResultReceived(requestCode: number, permissions: string[], grantResults: number[]): void

      /**
       * Fired when the user's custom ringtone list is loaded or mutated.
       * Posted from RingtoneDataStore and NotificationsSoundActivity.
       */
      onUserRingtonesUpdated(): void

      /**
       * Fired when the current account's premium status changes.
       * Posted from UserConfig when the isPremium flag flips.
       */
      currentUserPremiumStatusChanged(): void

      /**
       * Fired when premium promo data is refreshed (MediaDataController load, BirthdayController, privacy saves).
       */
      premiumPromoUpdated(): void

      /**
       * Fired globally (getGlobalInstance) when any account's premium status changes.
       * Posted from UserConfig alongside currentUserPremiumStatusChanged.
       */
      premiumStatusChangedGlobal(): void

      /**
       * Requests the UI to display a "limit reached" bottom sheet for the given limit type.
       * @param limitType int — one of LimitReachedBottomSheet.TYPE_* constants (e.g. TYPE_LARGE_FILE)
       */
      currentUserShowLimitReachedDialog(limitType: number): void

      /**
       * Fired when Google Play Billing product details are fetched or refreshed.
       * Posted from MessagesController and BillingController.
       */
      billingProductDetailsUpdated(): void

      /**
       * Fired when assigning a Play Market purchase on the server fails.
       * @param req TLRPC.TL_payments_assignPlayMarketTransaction — the failed request object
       * @param error TLRPC.TL_error — server error response
       */
      billingConfirmPurchaseError(req: TLObject, error: TLObject): void

      /**
       * Fired when the premium sticker preview set finishes loading.
       * Posted from MediaDataController.
       */
      premiumStickersPreviewLoaded(): void

      /**
       * Fired when a user's emoji status is updated (via typing update or explicit set).
       * @param user TLRPC.User — the user whose status changed (mutable, do not hold long-term)
       */
      userEmojiStatusUpdated(user: TLObject): void

      /**
       * Never posted anywhere in the codebase; only observed by LaunchActivity.
       * Intended to request a runtime permission from the activity.
       * Handler reads args[0] as int permission type (e.g. BLUETOOTH_CONNECT_TYPE).
       */
      // never posted (observed only; callers expected to post with type int)
      requestPermissions(type: number): void

      /**
       * Fired after a tracked permission request is granted (requestedPermissions map hit).
       * @param type int — internal permission type constant (keyed via requestedPermissions SparseArray)
       */
      permissionsGranted(type: number): void

      /**
       * Fired unconditionally from onRequestPermissionsResult with the full Android result.
       * @param requestCode int — permission request code
       * @param permissions String[] — requested permissions
       * @param grantResults int[] — grant results
       */
      activityPermissionsGranted(requestCode: number, permissions: string[], grantResults: number[]): void

      /**
       * Fired when a forum's topic list is loaded from server or cache.
       * @param chatId long — chat identifier (positive); negated for saved-dialogs case at one call site
       * @param fromCache boolean — true if loaded from local cache/storage, false if from server
       */
      topicsDidLoaded(chatId: number, fromCache: boolean): void

      /**
       * Fired when a chat's forum mode or forum-tabs mode is toggled.
       * @param chatId long — chat identifier
       * @param forum boolean — whether forum mode is now enabled
       * @param forumTabs boolean — whether forum tabs are now enabled (chat.forum_tabs)
       */
      chatSwitchedForum(chatId: number, forum: boolean, forumTabs: boolean): void

      /**
       * Fired when the account-wide global auto-delete timer setting changes.
       * Posted from UserConfig and AutoDeleteMessagesActivity.
       */
      didUpdateGlobalAutoDeleteTimer(): void

      /**
       * Posted from MessagesStorage when the local database is fully reset/wiped.
       * No args.
       */
      onDatabaseReset(): void

      /**
       * Posted from ChatThemeController when a wallpaper has been applied to a user.
       * No args.
       */
      wallpaperSettedToUser(): void

      /**
       * Posted from StoriesController / LaunchActivity / SelfStoryViewsPage / ViewsForPeerStoriesRequester
       * whenever the stories list state changes (loaded, reordered, updated, etc.).
       * No args.
       */
      storiesUpdated(): void

      /**
       * Posted from StoriesController when a story is deleted.
       * @param dialogId - long, peer whose story was deleted
       * @param storyId  - int, id of the deleted story
       */
      storyDeleted(dialogId: number, storyId: number): void

      /**
       * Posted from StoriesController.StoriesList when the paginated story list changes.
       * Arg count varies: usually 1 arg, occasionally 2.
       * @param storiesList - StoriesController.StoriesList, the list that changed
       * @param initial     - Boolean (optional), false when this is not the initial load; omitted in most cases
       */
      storiesListUpdated(storiesList: JavaObject, initial?: boolean): void

      /**
       * Posted from DraftsController when story drafts are added, updated, or deleted.
       * No args.
       */
      storiesDraftsUpdated(): void

      /**
       * Posted from MessagesController when a shared chatlist folder is updated.
       * @param filterId - int, the folder/filter id that changed
       */
      chatlistFolderUpdate(filterId: number): void

      /**
       * Posted from StoriesController during story upload, reporting progress.
       * @param path     - String, local file path of the story being uploaded
       * @param progress - float, upload progress 0..1
       */
      uploadStoryProgress(path: string, progress: number): void

      /**
       * Posted from StoriesController (global instance) when a story upload finishes.
       * @param path - String, local file path of the uploaded story
       */
      uploadStoryEnd(path: string): void

      /**
       * Posted from PaintTypeface (global instance) when custom typefaces finish loading.
       * No args.
       */
      customTypefacesLoaded(): void

      /**
       * Posted from StoriesController when stealth mode state changes.
       * No args.
       */
      stealthModeChanged(): void

      /**
       * Posted from MessagesController when channel difference processing completes.
       * @param channelId - long, the channel whose difference was received
       */
      onReceivedChannelDifference(channelId: number): void

      /**
       * Posted from StoriesController when story read state is updated (mark-as-read).
       * No args.
       */
      storiesReadUpdated(): void

      /**
       * Posted from VoIPService (global instance) when proximity sensor state changes
       * (ear detection during calls).
       * @param isNear - boolean, true if device is near ear
       */
      nearEarEvent(isNear: boolean): void

      /**
       * Never posted (all post call sites are commented out).
       * Inferred from commented-out handler: would carry lang + percent.
       * @param lang    - String, language code being downloaded
       * @param percent - float, download progress 0..1
       */
      // never posted (commented out)
      translationModelDownloading(lang: string, percent: number): void

      /**
       * Never posted (all post call sites are commented out).
       * Inferred from commented-out handler: would carry lang + success flag.
       * @param lang    - String, language code that finished downloading
       * @param success - boolean, whether download succeeded
       */
      // never posted (commented out)
      translationModelDownloaded(lang: string, success: boolean): void

      /**
       * Posted from BotForumHelper when a bot-created forum topic is confirmed server-side.
       * @param notification - BotForumHelper.BotForumTopicCreateNotification, contains dialogId + topicId
       */
      botForumTopicDidCreate(notification: JavaObject): void

      /**
       * Posted from BotForumHelper when a bot-typed draft message is created or updated.
       * @param notification - BotForumHelper.BotForumTextDraftUpdateNotification,
       *                       contains userId, topicId, messageObject, isNew
       */
      botForumDraftUpdate(notification: JavaObject): void

      /**
       * Posted from BotForumHelper when a bot draft message expires (timeout).
       * @param notification - BotForumHelper.BotForumTextDraftDeleteNotification,
       *                       contains userId, topicId, localMessageId
       */
      botForumDraftDelete(notification: JavaObject): void

      /**
       * Posted from TLParseException (global instance, DEBUG_VERSION only) when an unknown
       * TL constructor is encountered during deserialization.
       * @param exception - TLParseException, the parse error
       */
      tlSchemeParseException(exception: JavaObject): void

      /**
       * Never posted (the only call site is commented out).
       * Would be posted from LeakDetector on memory leak detection.
       * Inferred from commented-out code: would carry class + count.
       */
      // never posted (commented out)
      memoryLeakFoundException(): void

      /**
       * Posted from MainTabsActivity / CallLogActivity (fork) when call tab visibility is toggled.
       * No args.
       */
      callTabsVisibleToggled(): void

      /**
       * Posted from ContactsActivity (fork) to trigger a contacts permission badge recheck.
       * No args.
       */
      contactsPermissionBadgeCheck(): void

      /**
       * Posted from BotGuardHelper when the guard bot completes a join decision.
       * @param notification - BotGuardHelper.GuardBotDecisionResultNotification,
       *                       contains dialogId, guardBotId, queryId, result (TLRPC.JoinChatBotResult)
       */
      guardBotDecisionResult(notification: JavaObject): void

      /**
       * Posted from MessagesController when web browser settings are received or updated.
       * No args.
       */
      webBrowserSettingsUpdate(): void
    }

    /**
     * add a notification center delegate
     *
     * object keys are event names, values are callback functions
     *
     * @needs-grant android.addNotificationCenterDelegate
     */
    function addNotificationCenterDelegate(
      handlers: {
        [key in keyof NotificationCenterEventsMap]?: (...args: Parameters<NotificationCenterEventsMap[key]>) => void
      },
    ): Disposer
  }
}
