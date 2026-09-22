declare namespace inu {
  namespace android {
    /**
     * Arguments as plugins receive them: booleans, numbers, strings and `byte[]` (as `Uint8Array`)
     * pass through, and every other object, TL objects and collections included, is a {@link JavaObject}.
     * Notes name the Java type behind each `JavaObject`. A random 64-bit id past `Number.MAX_SAFE_INTEGER`
     * arrives as a `bigint`.
     */
    interface NotificationCenterEventsMap {
      /** `messages`: `ArrayList<MessageObject>` */
      didReceiveNewMessages(dialogId: number, messages: JavaObject, scheduled: boolean, mode: number): void

      updateInterfaces(updateMask: number): void

      /** Global or per-account. */
      dialogsNeedReload(force?: boolean): void

      closeChats(dialogId?: number): void

      closeChatActivity(dialogId: number, includingLast: boolean): void

      closeProfileActivity(dialogId: number, includingLast: boolean): void

      /** `messageIds`: `ArrayList<Integer>`, `sentMessageIds`: `ArrayList<Integer>` */
      messagesDeleted(messageIds: JavaObject, channelId: number, scheduled: boolean, sent?: boolean, movedToScheduled?: boolean, movedToScheduledMessageId?: number, sentMessageIds?: JavaObject | null): void

      historyCleared(dialogId: number, maxId: number): void

      /** `inbox`: `LongSparseIntArray`, `outbox`: `LongSparseIntArray` */
      messagesRead(inbox: JavaObject | null, outbox: JavaObject | null): void

      threadMessagesRead(channelDialogId: number, topMsgId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      monoForumMessagesRead(channelDialogId: number, savedPeerDialogId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      commentsRead(broadcastChannelId: number, broadcastPostId: number, maxReadId: number): void

      changeRepliesCounter(channelId: number, originalMessageId: number, delta: number): void

      /** `messages`: `ArrayList<MessageObject>` */
      messagesDidLoad(dialogId: number, count: number, messages: JavaObject, isCache: boolean, firstUnread: number, lastMessageId: number, unreadCount: number, lastDate: number, loadType: number, isEnd: boolean, classGuid: number, loadIndex: number, maxId: number, mentionsCount: number, mode: number): void

      /** `messages`: `ArrayList<MessageObject>` */
      didLoadSponsoredMessages(dialogId: number, messages: JavaObject): void

      /** `peers`: `TLRPC.TL_channels_sendAsPeers` */
      didLoadSendAsPeers(dialogId: number, peers: JavaObject, liveStories: boolean): void

      /** `peer`: `TLRPC.Peer` */
      updateDefaultSendAsPeer(chatId: number, peer: JavaObject): void

      messagesDidLoadWithoutProcess(classGuid: number, count: number, isCache: boolean, isEnd: boolean, lastMessageId: number): void

      /** `request`: `TLObject`, `error`: `TLRPC.TL_error` */
      loadingMessagesFailed(classGuid: number, request: JavaObject, error: JavaObject): void

      messageReceivedByAck(msgId: number): void

      /** `message`: `TLRPC.Message` */
      messageReceivedByServer(oldId: number, newId: number, message: JavaObject | null, dialogId: number, groupedId: number | bigint, existFlags: number, scheduled: boolean): void

      /** `message`: `TLRPC.Message` */
      messageReceivedByServer2(oldId: number, newId: number, message: JavaObject | null, dialogId: number, groupedId: number | bigint, existFlags: number, scheduled: boolean): void

      messageSendError(messageId: number): void

      forceImportContactsStart(): void

      contactsDidLoad(): void

      contactsImported(): void

      /** `contactHashMap`: `HashMap<String, ContactsController.Contact>` */
      hasNewContactsToImport(checkType: number, contactHashMap: JavaObject, first: boolean, schedule: boolean): void

      chatDidCreated(chatId: number): void

      chatDidFailCreate(): void

      /** `chatFull`: `TLRPC.ChatFull` */
      chatInfoDidLoad(chatFull: JavaObject | null, classGuid: number, byChannelUsers: boolean, fromCache: boolean): void

      chatInfoCantLoad(channelId: number, reason: number): void

      /** `messages`: `ArrayList<MessageObject>` */
      mediaDidLoad(dialogId: number, totalCount: number, messages: JavaObject, classGuid: number, type: number, topReached: boolean, fromStart: boolean, requestIndex: number): void

      mediaCountDidLoad(dialogId: number, topicId: number, count: number, fromCache: boolean, type: number): void

      /** `counts`: `int[]` */
      mediaCountsDidLoad(dialogId: number, topicId: number, counts: JavaObject): void

      /** `chat`: `TLRPC.EncryptedChat` */
      encryptedChatUpdated(chat: JavaObject): void

      messagesReadEncrypted(encryptedChatId: number, maxReadDate: number): void

      /** `chat`: `TLRPC.EncryptedChat` */
      encryptedChatCreated(chat: JavaObject): void

      /** Not posted by the app at the moment. */
      dialogPhotosLoaded(): void

      reloadDialogPhotos(): void

      folderBecomeEmpty(folderId: number): void

      /** `difference`: `TLRPC.TL_updates_channelDifferenceTooLong` */
      removeAllMessagesFromDialog(dialogId: number, fromDifference: boolean, difference: JavaObject | null): void

      notificationsSettingsUpdated(): void

      blockedUsersDidLoad(): void

      openedChatChanged(dialogId: number, topicId: number, closed: boolean): void

      /** `mids`: `SparseArray<ArrayList<Integer>>` */
      didCreatedNewDeleteTask(dialogId: number, mids: JavaObject): void

      mainUserInfoChanged(): void

      privacyRulesUpdated(): void

      /** `message`: `TLRPC.Message` */
      updateMessageMedia(message: JavaObject): void

      /** `messageObjects`: `ArrayList<MessageObject>` */
      replaceMessagesObjects(dialogId: number, messageObjects: JavaObject, updateDialogs?: boolean): void

      /** Global. */
      didSetPasscode(isPasscodeChange?: boolean): void

      /** Global. `view`: `PasscodeView` */
      passcodeDismissed(view: JavaObject): void

      /** `newAlgo`: `TLRPC.PasswordKdfAlgo`, `newSecureAlgo`: `TLRPC.SecurePasswordKdfAlgo` */
      twoStepPasswordChanged(currentPasswordHash?: Uint8Array | null, newAlgo?: JavaObject | null, newSecureAlgo?: JavaObject, secureRandom?: Uint8Array, email?: string | null, hint?: string | null, unconfirmedEmail?: string | null, firstPassword?: string | null): void

      /** `currentPassword`: `TL_account.Password` */
      didSetOrRemoveTwoStepPassword(currentPassword?: JavaObject | null): void

      didRemoveTwoStepPassword(): void

      /** `messageObjects`: `ArrayList<MessageObject>`, `replyMessageOwners`: `LongSparseArray<SparseArray<ArrayList<MessageObject>>>` */
      replyMessagesDidLoad(dialogId: number, messageObjects: JavaObject, replyMessageOwners: JavaObject | null): void

      /** `ids`: `ArrayList<Integer>`, `messageObjects`: `ArrayList<MessageObject>`, `messages`: `HashMap<Integer, MessageObject>` */
      didLoadPinnedMessages(dialogId: number, ids: JavaObject | null, pin: boolean, messageObjects: JavaObject | null, messages: JavaObject | null, maxId: number, totalPinnedCount: number, endReached: boolean): void

      /** Not posted by the app at the moment. */
      newSessionReceived(): void

      /** `messages`: `ArrayList<TLRPC.Message>` */
      didReceivedWebpages(messages: JavaObject): void

      /** `webPages`: `LongSparseArray<TLRPC.WebPage>` */
      didReceivedWebpagesInUpdates(webPages: JavaObject): void

      stickersDidLoad(type: number, forceUpdateUi: boolean): void

      diceStickersDidLoad(name: string): void

      featuredStickersDidLoad(): void

      featuredEmojiDidLoad(): void

      /** `set`: `TLRPC.TL_messages_stickerSet` */
      groupStickersDidLoad(setId: number | bigint, set: JavaObject): void

      /** `messageIds`: `ArrayList<Integer>` */
      messagesReadContent(dialogId: number, messageIds: JavaObject): void

      /** `botInfo`: `TL_bots.BotInfo` */
      botInfoDidLoad(botInfo: JavaObject, classGuid: number): void

      /** `userFull`: `TLRPC.UserFull` */
      userInfoDidLoad(userId: number, userFull: JavaObject | null): void

      /** `pinnedMessages`: `ArrayList<Integer>`, `pinnedMessagesMap`: `HashMap<Integer, MessageObject>` */
      pinnedInfoDidLoad(dialogId: number, pinnedMessages: JavaObject, pinnedMessagesMap: JavaObject, totalPinnedCount: number, pinnedEndReached: boolean): void

      /** `keyboard`: `TLRPC.Message`, `topicKey`: `MessagesStorage.TopicKey` */
      botKeyboardDidLoad(keyboard: JavaObject | null, topicKey: JavaObject): void

      chatSearchResultsAvailable(guid: number, messageId: number, mask: number, dialogId: number, index: number, count: number, jumpToMessage: boolean): void

      hashtagSearchUpdated(guid: number, count: number, endReached: boolean, mask: number, selectedIndex: number, messageId: number): void

      chatSearchResultsLoading(guid: number): void

      /** `tracksBegin`: `ArrayList<MessageObject>`, `tracksEnd`: `ArrayList<MessageObject>` */
      musicDidLoad(dialogId: number, tracksBegin: JavaObject, tracksEnd: JavaObject): void

      moreMusicDidLoad(addedCount: number): void

      /** Global or per-account. `arg1`: `TLRPC.TL_help_termsOfService` */
      needShowAlert(reason: number, arg1?: string | JavaObject, arg2?: string): void

      needShowPlayServicesAlert(status: number): void

      /** `channelViews`: `LongSparseArray<SparseIntArray>`, `channelForwards`: `LongSparseArray<SparseIntArray>`, `channelReplies`: `LongSparseArray<SparseArray<TLRPC.MessageReplies>>` */
      didUpdateMessagesViews(channelViews: JavaObject | null, channelForwards: JavaObject | null, channelReplies: JavaObject | null, onlySelf: boolean): void

      needReloadRecentDialogsSearch(): void

      peerSettingsDidLoad(dialogId: number): void

      /** `pendingMessages`: `HashMap<String, MessageObject>` */
      wasUnableToFindCurrentLocation(pendingMessages: JavaObject): void

      reloadHints(): void

      reloadInlineHints(): void

      reloadGuestBotHints(): void

      reloadWebappsHints(): void

      newDraftReceived(dialogId: number): void

      recentDocumentsDidLoad(isGif: boolean, type: number): void

      /** `sets`: `ArrayList<TLRPC.StickerSetCovered>` */
      needAddArchivedStickers(sets: JavaObject): void

      archivedStickersCountDidLoad(type: number): void

      paymentFinished(): void

      /** `chat`: `TLRPC.Chat` */
      channelRightsUpdated(chat: JavaObject): void

      /** `webPage`: `TLRPC.WebPage` */
      openArticle(webPage: JavaObject, url: string): void

      articleClosed(): void

      updateMentionsCount(dialogId: number, topicId: number, unreadMentionsCount: number): void

      /** `poll`: `TLRPC.Poll`, `results`: `TLRPC.PollResults` */
      didUpdatePollResults(pollId: number | bigint, poll: JavaObject | null, results: JavaObject): void

      chatOnlineCountDidLoad(chatId: number, onlines: number): void

      videoLoadingStateChanged(key: string): void

      /** `update`: `TLRPC.Update` */
      newPeopleNearbyAvailable(update: JavaObject): void

      /** Global or per-account. */
      stopAllHeavyOperations(flags: number): void

      /** Global. */
      startAllHeavyOperations(flags: number): void

      /** Global. */
      stopSpoilers(): void

      /** Global. */
      startSpoilers(): void

      sendingMessagesChanged(): void

      /** `reactions`: `TLRPC.TL_messageReactions` */
      didUpdateReactions(dialogId: number, messageId: number, reactions: JavaObject): void

      /** `extendedMedia`: `ArrayList<TLRPC.MessageExtendedMedia>` */
      didUpdateExtendedMedia(dialogId: number, msgId: number, extendedMedia: JavaObject): void

      /** `messages`: `ArrayList<TLRPC.Message>` */
      didVerifyMessagesStickers(messages: JavaObject): void

      scheduledMessagesUpdated(dialogId: number, count: number, fromStorage: boolean): void

      newSuggestionsAvailable(): void

      didLoadChatInviter(chatId: number, inviterId: number): void

      didLoadChatAdmins(chatId: number): void

      /** `req`: `TLRPC.TL_messages_initHistoryImport | TLRPC.TL_messages_startHistoryImport | TLRPC.TL_messages_checkHistoryImportPeer`, `error`: `TLRPC.TL_error` */
      historyImportProgressChanged(dialogIdOrShortName: number | string, req?: JavaObject, error?: JavaObject): void

      /** `req`: `TLRPC.TL_stickers_createStickerSet`, `error`: `TLRPC.TL_error` */
      stickersImportProgressChanged(shortName: string, req?: JavaObject, error?: JavaObject): void

      /** `stickerSet`: `TLObject` */
      stickersImportComplete(stickerSet: JavaObject): void

      dialogDeleted(dialogId: number, topicId: number): void

      webViewResultSent(queryId: number | bigint): void

      /** `messageObject`: `MessageObject` */
      voiceTranscriptionUpdate(messageObject: JavaObject | null, transcriptionId?: number | bigint | null, text?: string | null, isPremium?: boolean | null, isFinal?: boolean | null): void

      /** `messageObject`: `MessageObject` */
      animatedEmojiDocumentLoaded(messageObject: JavaObject): void

      recentEmojiStatusesUpdate(): void

      updateSearchSettings(): void

      updateTranscriptionLock(): void

      /** Not posted by the app at the moment. */
      businessMessagesUpdated(): void

      quickRepliesUpdated(): void

      /** `messageIds`: `ArrayList<Integer>` */
      quickRepliesDeleted(messageIds: JavaObject, topicId: number): void

      /** `messageObject`: `MessageObject` */
      bookmarkAdded(messageObject: JavaObject): void

      starReactionAnonymousUpdate(dialogId: number, messageId: number, peer: number): void

      businessLinksUpdated(): void

      /** `link`: `TL_account.TL_businessChatLink` */
      businessLinkCreated(link: JavaObject): void

      /** `link`: `TL_account.TL_businessChatLink` */
      needDeleteBusinessLink(link: JavaObject): void

      /** `messageObject`: `MessageObject` */
      messageTranslated(messageObject: JavaObject, translated?: boolean, dialogTranslating?: boolean): void

      /** `messageObject`: `MessageObject` */
      messageTranslating(messageObject: JavaObject): void

      dialogIsTranslatable(dialogId: number): void

      dialogTranslate(dialogId: number, translating: boolean): void

      /** Global. */
      didGenerateFingerprintKeyPair(notifyCheckFingerprint: boolean): void

      /** Not posted by the app at the moment. */
      walletPendingTransactionsChanged(): void

      /** Not posted by the app at the moment. */
      walletSyncProgressChanged(): void

      httpFileDidLoad(url: string, path: string): void

      httpFileDidFailedLoad(url: string, reason: number): void

      didUpdateConnectionState(): void

      /** `inputFile`: `TLRPC.InputFile`, `inputEncryptedFile`: `TLRPC.InputEncryptedFile` */
      fileUploaded(location: string, inputFile: JavaObject | null, inputEncryptedFile: JavaObject | null, key: Uint8Array | null, iv: Uint8Array | null, totalFileSize: number): void

      fileUploadFailed(location: string, isEncrypted: boolean): void

      fileUploadProgressChanged(location: string, uploadedSize: number, totalSize: number, isEncrypted: boolean): void

      fileLoadProgressChanged(location: string, loadedSize: number, totalSize: number): void

      /** `finalFile`: `java.io.File` */
      fileLoaded(location: string, finalFile: JavaObject): void

      fileLoadFailed(location: string, reason: number): void

      /** `messageObject`: `MessageObject` */
      filePreparingStarted(messageObject: JavaObject, filePath: string, progress: number, lastFrameTimestamp: number): void

      /** `messageObject`: `MessageObject` */
      fileNewChunkAvailable(messageObject: JavaObject, filePath: string, availableSize: number, finalSize: number, progress: number, lastFrameTimestamp: number): void

      /** `messageObject`: `MessageObject` */
      filePreparingFailed(messageObject: JavaObject, filePath: string, progress: number, lastFrameTimestamp: number): void

      dialogsUnreadCounterChanged(pushDialogsCount: number): void

      messagePlayingProgressDidChanged(messageId: number, progress: number): void

      messagePlayingDidReset(messageId: number, stopService: boolean): void

      messagePlayingPlayStateChanged(messageId: number): void

      /** `messageObject`: `MessageObject`, `oldMessageObject`: `MessageObject` */
      messagePlayingDidStart(messageObject: JavaObject, oldMessageObject: JavaObject | null): void

      messagePlayingDidSeek(messageId: number, progress: number): void

      /** `messageObject`: `MessageObject` */
      messagePlayingGoingToStop(messageObject: JavaObject, stopService: boolean): void

      recordProgressChanged(recordingGuid: number, amplitude: number): void

      recordStarted(guid: number, isVoice: boolean): void

      recordStartError(guid: number): void

      recordStopped(guid: number, reason: number): void

      recordPaused(): void

      recordResumed(): void

      screenshotTook(): void

      /** Global. `mediaAlbumsSorted`: `ArrayList<MediaController.AlbumEntry>`, `photoAlbumsSorted`: `ArrayList<MediaController.AlbumEntry>` */
      albumsDidLoad(guid: number, mediaAlbumsSorted: JavaObject, photoAlbumsSorted: JavaObject, cameraAlbumId: number | null): void

      /** `audio`: `TLRPC.TL_document | VideoEditedInfo`, `keyframesOrFromDraft`: `ArrayList<Bitmap>` */
      audioDidSent(guid: number, audio: JavaObject | null, filePath: string | null, keyframesOrFromDraft?: boolean | JavaObject, draftLeft?: number, draftRight?: number): void

      audioRecordTooShort(guid: number, isVideo: boolean, duration: number): void

      audioRouteChanged(useFrontSpeaker: boolean): void

      /** Global. */
      didStartedCall(): void

      groupCallUpdated(chatId: number, callId: number | bigint, selfUpdated: boolean, justJoinedId?: number): void

      /** `call`: `TLRPC.GroupCall` */
      storyGroupCallUpdated(dialogId: number, call: JavaObject): void

      groupCallSpeakingUsersUpdated(chatId: number, callId: number | bigint, selfUpdated: boolean): void

      groupCallScreencastStateChanged(): void

      activeGroupCallsUpdated(): void

      applyGroupCallVisibleParticipants(time: number): void

      groupCallTypingsUpdated(): void

      /** Global. */
      didEndCall(): void

      /** Global. */
      closeInCallActivity(): void

      /** Global. */
      groupCallVisibilityChanged(): void

      liveStoryUpdated(callId: number | bigint): void

      /** `update`: `TL_update.TL_updateGroupCallMessage | TL_update.TL_updateDeleteGroupCallMessages` */
      liveStoryMessageUpdate(callId: number | bigint, update: JavaObject, isHistory: boolean): void

      appDidLogout(): void

      configLoaded(): void

      /** `user`: `TLRPC.User`, `chat`: `TLRPC.Chat` */
      needDeleteDialog(dialogId: number, user: JavaObject | null, chat: JavaObject | null, revoke: boolean): void

      newEmojiSuggestionsAvailable(lang: string): void

      /** `themeInfo`: `Theme.ThemeInfo`, `accent`: `Theme.ThemeAccent` */
      themeUploadedToServer(themeInfo: JavaObject, accent: JavaObject | null): void

      /** `themeInfo`: `Theme.ThemeInfo`, `accent`: `Theme.ThemeAccent` */
      themeUploadError(themeInfo: JavaObject, accent: JavaObject | null): void

      dialogFiltersUpdated(): void

      filterSettingsUpdated(): void

      suggestedFiltersLoaded(): void

      /** `button`: `TL_bots.BotMenuButton` */
      updateBotMenuButton(botId: number, button: JavaObject): void

      giftsToUserSent(): void

      didStartedMultiGiftsSelector(): void

      /** `myBoosts`: `TL_stories.TL_premium_myBoosts`, `boostsStatus`: `TL_stories.TL_premium_boostsStatus` */
      boostedChannelByUser(myBoosts: JavaObject, boostedSlotsCount: number, uniqueChannelCount: number, boostsStatus: JavaObject | null): void

      /** `chat`: `TLRPC.Chat`, `prepaidGiveaway`: `TL_stories.PrepaidGiveaway` */
      boostByChannelCreated(chat: JavaObject, isGiveaway: boolean, prepaidGiveaway?: JavaObject): void

      didUpdatePremiumGiftStickers(): void

      didUpdateTonGiftStickers(): void

      didUpdatePremiumGiftFieldIcon(): void

      storiesEnabledUpdate(): void

      storiesBlocklistUpdate(): void

      storiesLimitUpdate(): void

      storiesSendAsUpdate(): void

      unconfirmedAuthUpdate(): void

      /** `dialogPhotos`: `MessagesController.DialogPhotos` */
      dialogPhotosUpdate(dialogPhotos: JavaObject): void

      channelRecommendationsLoaded(dialogId: number): void

      savedMessagesDialogsUpdate(): void

      savedReactionTagsUpdate(topicId: number): void

      userIsPremiumBlockedUpadted(): void

      /** `collections`: `StoriesController.StoriesCollections` */
      storyAlbumsCollectionsUpdate(dialogId: number, collections: JavaObject): void

      /** `newMessagesByIds`: `android.util.SparseLongArray` */
      savedMessagesForwarded(newMessagesByIds: JavaObject): void

      emojiKeywordsLoaded(): void

      /** Not posted by the app at the moment. */
      smsJobStatusUpdate(): void

      storyQualityUpdate(): void

      /** `cell`: `ChatMessageCell` */
      openBoostForUsersDialog(dialogId: number, cell?: JavaObject): void

      groupRestrictionsUnlockedByBoosts(): void

      /** `boostsStatus`: `TL_stories.TL_premium_boostsStatus`, `canApplyBoost`: `ChannelBoostsController.CanApplyBoost` */
      chatWasBoostedByUser(boostsStatus: JavaObject, canApplyBoost: JavaObject, dialogId: number): void

      groupPackUpdated(chatId: number, isEmoji: boolean): void

      timezonesUpdated(): void

      /** `stickerSet`: `TLObject`, `document`: `TLRPC.Document` */
      customStickerCreated(isSending?: boolean, stickerSet?: JavaObject, document?: JavaObject, thumbPath?: string | null, isReplacing?: boolean): void

      premiumFloodWaitReceived(): void

      availableEffectsUpdate(): void

      starOptionsLoaded(): void

      starGiftOptionsLoaded(): void

      starGiveawayOptionsLoaded(): void

      starBalanceUpdated(): void

      starTransactionsLoaded(): void

      starSubscriptionsLoaded(): void

      factCheckLoaded(): void

      botStarsUpdated(dialogId: number): void

      botStarsTransactionsLoaded(dialogId: number): void

      /** Not posted by the app at the moment. */
      channelStarsUpdated(): void

      updateAllMessages(chatId: number): void

      starGiftsLoaded(): void

      /** `list`: `StarsController.GiftsList` */
      starUserGiftsLoaded(dialogId: number, list: JavaObject | null): void

      /** `collections`: `StarsController.GiftsCollections` */
      starUserGiftCollectionsLoaded(dialogId: number, collections: JavaObject): void

      /** `starGift`: `TL_stars.StarGift` */
      starGiftSoldOut(starGift: JavaObject): void

      /** Not posted by the app at the moment. */
      updateStories(): void

      botDownloadsUpdate(): void

      channelSuggestedBotsUpdate(dialogId: number): void

      channelConnectedBotsUpdate(dialogId: number): void

      adminedChannelsLoaded(): void

      messagesFeeUpdated(userId: number): void

      /** `list`: `MessagesController.CommonChatsList` */
      commonChatsLoaded(dialogId: number, list: JavaObject): void

      appConfigUpdated(): void

      activeAuctionsUpdated(): void

      conferenceEmojiUpdated(): void

      contentSettingsLoaded(): void

      /** `list`: `MessagesController.SavedMusicList` */
      musicListLoaded(list: JavaObject): void

      musicIdsLoaded(): void

      profileMusicUpdated(dialogId: number): void

      updatedChatRanks(chatId: number, userId: number, rank: string | null): void

      joinedGroup(chatId: number): void

      /** `controller`: `AiTonesController` */
      loadedAiComposeTones(controller: JavaObject): void

      updatedChatbot(): void

      /** Global. */
      activeAccountChanged(account: number): void

      /** Global. */
      pushMessagesUpdated(): void

      /** Global. `wallPapers`: `ArrayList<TLRPC.WallPaper>` */
      wallpapersDidLoad(wallPapers: JavaObject): void

      /** Global. */
      wallpapersNeedReload(slug: string): void

      /** Global. */
      didReceiveSmsCode(code: string): void

      /** Global. */
      didReceiveCall(phone: string | null): void

      /** Global. */
      emojiLoaded(): void

      /** Global. */
      invalidateMotionBackground(): void

      /** Global. `activity`: `Activity` */
      closeOtherAppActivities(activity: JavaObject): void

      /** Global. */
      cameraInitied(): void

      /** Global. `newLocation`: `ImageLocation` */
      didReplacedPhotoInMemCache(oldKey: string, newKey: string, newLocation: JavaObject | null): void

      /** Global. */
      didSetNewTheme(nightTheme: boolean, checkNavigationBarColor?: boolean, forceCheckKeyboardColor?: boolean): void

      /** Global. */
      themeListUpdated(): void

      /** Global. `previousTheme`: `Theme.ThemeInfo`, `previousAccent`: `Theme.ThemeAccent` */
      didApplyNewTheme(previousTheme: JavaObject, previousAccent: JavaObject | null, deleteOnCancel: boolean): void

      /** Global. */
      themeAccentListUpdated(): void

      /** Global. */
      needCheckSystemBarColors(checkNavigationBar?: boolean): void

      /** Global. `theme`: `Theme.ThemeInfo`, `accent`: `Theme.ThemeAccent` */
      needShareTheme(theme: JavaObject, accent: JavaObject | null): void

      /** Global. `theme`: `Theme.ThemeInfo`, `pos`: `int[]`, `toDark`: `SparseIntArray` when loading a remote theme, `animatingView`: `RLottieImageView`, `rippleAbove`: `TextCell`, `then`: `Runnable` */
      needSetDayNightTheme(theme: JavaObject, nightTheme: boolean, pos: JavaObject | null, accentId: number, toDark?: boolean | JavaObject, animatingView?: JavaObject | null, rippleAbove?: JavaObject | null, then?: JavaObject | null, colorNotDark?: boolean): void

      /** Global. */
      goingToPreviewTheme(): void

      /** Global. */
      locationPermissionGranted(fromMediaGeo?: number): void

      /** Global. */
      locationPermissionDenied(fromMediaGeo?: number): void

      /** Global. */
      reloadInterface(): void

      /** Global. */
      suggestedLangpack(): void

      /** Global. */
      didSetNewWallpapper(): void

      /** Global. */
      proxySettingsChanged(): void

      /** Global. `proxyInfo`: `SharedConfig.ProxyInfo` */
      proxyCheckDone(proxyInfo: JavaObject): void

      /** Global. */
      proxyChangedByRotation(): void

      /** Global. */
      liveLocationsChanged(): void

      /** Global. */
      newLocationAvailable(): void

      /** Global or per-account. */
      liveLocationsCacheChanged(dialogId: number, account?: number): void

      /** Global. */
      notificationsCountUpdated(account: number): void

      /** Global. `player`: `VideoPlayer` */
      playerDidStartPlaying(player: JavaObject): void

      /** Global. */
      closeSearchByActiveAction(): void

      /** Global. */
      messagePlayingSpeedChanged(): void

      /** Global. */
      screenStateChanged(): void

      didClearDatabase(): void

      voipServiceCreated(): void

      /** Global. */
      webRtcMicAmplitudeEvent(amplitude: number): void

      /** Global. */
      webRtcSpeakerAmplitudeEvent(amplitude: number): void

      /** Global. `payload`: `TLRPC.Document | LauncherIconController.LauncherIcon` */
      showBulletin(type: number, payload: string | number | JavaObject, extra?: number | string): void

      /** Global. */
      appUpdateAvailable(available?: boolean): void

      /** Global. */
      appUpdateLoading(): void

      onDatabaseMigration(started: boolean): void

      /** `action`: `TLRPC.SendMessageAction` */
      onEmojiInteractionsReceived(dialogId: number, action: JavaObject): void

      /** Global. */
      emojiPreviewThemesChanged(): void

      /** Global. */
      reactionsDidLoad(): void

      attachMenuBotsDidLoad(): void

      chatAvailableReactionsUpdated(chatId: number, topicId: number): void

      /** `newUnreadMessages`: `ArrayList<Integer>` */
      dialogsUnreadReactionsCounterChanged(dialogId: number, topicId: number, count: number, newUnreadMessages: JavaObject | null): void

      /** `newUnreadMessages`: `ArrayList<Integer>` */
      dialogsUnreadPollVotesCounterChanged(dialogId: number, topicId: number, count: number, newUnreadMessages: JavaObject | null): void

      onDatabaseOpened(): void

      onDownloadingFilesChanged(): void

      /** Global. `data`: `android.content.Intent` */
      onActivityResultReceived(requestCode: number, resultCode: number, data: JavaObject | null): void

      /** Global. `permissions`: `String[]`, `grantResults`: `int[]` */
      onRequestPermissionResultReceived(requestCode: number, permissions: JavaObject, grantResults: JavaObject): void

      onUserRingtonesUpdated(): void

      currentUserPremiumStatusChanged(): void

      premiumPromoUpdated(): void

      /** Global. */
      premiumStatusChangedGlobal(): void

      currentUserShowLimitReachedDialog(limitType: number): void

      /** Global. */
      billingProductDetailsUpdated(): void

      /** Global. `req`: `TLRPC.TL_payments_assignPlayMarketTransaction`, `error`: `TLRPC.TL_error` */
      billingConfirmPurchaseError(req: JavaObject, error: JavaObject): void

      premiumStickersPreviewLoaded(): void

      /** `user`: `TLRPC.User` */
      userEmojiStatusUpdated(user: JavaObject): void

      /** Not posted by the app at the moment. */
      requestPermissions(): void

      /** Global. */
      permissionsGranted(type: number): void

      /** Global. `permissions`: `String[]`, `grantResults`: `int[]` */
      activityPermissionsGranted(requestCode: number, permissions: JavaObject, grantResults: JavaObject): void

      topicsDidLoaded(chatId: number, fromCache: boolean): void

      chatSwitchedForum(chatId: number, forum: boolean, forumTabs: boolean): void

      didUpdateGlobalAutoDeleteTimer(): void

      onDatabaseReset(): void

      wallpaperSettedToUser(): void

      storiesUpdated(): void

      storyDeleted(dialogId: number, storyId: number): void

      /** `storiesList`: `StoriesController.StoriesList` */
      storiesListUpdated(storiesList: JavaObject, success?: boolean): void

      storiesDraftsUpdated(): void

      chatlistFolderUpdate(filterId: number): void

      uploadStoryProgress(path: string, progress: number): void

      /** Global. */
      uploadStoryEnd(path: string | null): void

      /** Global. */
      customTypefacesLoaded(): void

      stealthModeChanged(): void

      onReceivedChannelDifference(channelId: number): void

      storiesReadUpdated(): void

      /** Global. */
      nearEarEvent(isNear: boolean): void

      /** Not posted by the app at the moment. */
      translationModelDownloading(): void

      /** Not posted by the app at the moment. */
      translationModelDownloaded(): void

      /** `notification`: `BotForumHelper.BotForumTopicCreateNotification` */
      botForumTopicDidCreate(notification: JavaObject): void

      /** `notification`: `BotForumHelper.BotForumTextDraftUpdateNotification` */
      botForumDraftUpdate(notification: JavaObject): void

      /** `notification`: `BotForumHelper.BotForumTextDraftDeleteNotification` */
      botForumDraftDelete(notification: JavaObject): void

      /** Global. `exception`: `TLParseException` */
      tlSchemeParseException(exception: JavaObject): void

      /** Not posted by the app at the moment. */
      memoryLeakFoundException(): void

      callTabsVisibleToggled(): void

      contactsPermissionBadgeCheck(): void

      /** `notification`: `BotGuardHelper.GuardBotDecisionResultNotification` */
      guardBotDecisionResult(notification: JavaObject): void

      webBrowserSettingsUpdate(): void

      /** Not posted by the app at the moment. */
      communityPendingRequestsUpdate(): void

      communitySwitchedCollapsed(chatId: number, collapsed: boolean): void
    }

    /**
     * Add a listener to the android app's raw `NotificationCenter` events.
     *
     * `account` is the account slot the event was posted for, or `-1` for events the app posts
     * globally (marked "Global" in {@link NotificationCenterEventsMap}).
     *
     * @needs-grant unsafe.notificationCenter
     * @needs-grant unsafe.jvm
     */
    function addNotificationCenterDelegate(
      handlers: {
        [key in keyof NotificationCenterEventsMap]?: (
          account: number,
          ...args: Parameters<NotificationCenterEventsMap[key]>
        ) => void
      },
    ): Disposer
  }
}
