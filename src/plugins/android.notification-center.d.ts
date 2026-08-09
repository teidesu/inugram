declare namespace inu {
  namespace android {
    interface NotificationCenterEventsMap {
      didReceiveNewMessages(dialogId: number, messages: Array<JavaObject>, scheduled: boolean, mode: number): void

      updateInterfaces(updateMask: number): void

      dialogsNeedReload(force?: boolean): void

      closeChats(dialogId?: number): void

      closeChatActivity(dialogId: number, includingLast: boolean): void

      closeProfileActivity(dialogId: number, includingLast: boolean): void

      messagesDeleted(messageIds: Array<number>, channelId: number, scheduled: boolean, forAll?: boolean, movedToScheduled?: boolean, movedToScheduledMessageId?: number, sentMessageIds?: Array<number> | null): void

      historyCleared(dialogId: number, maxId: number): void

      messagesRead(inbox: Map<number, number[]> | null, outbox: Map<number, number[]> | null): void

      threadMessagesRead(channelDialogId: number, topMsgId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      monoForumMessagesRead(channelDialogId: number, savedPeerDialogId: number, inboxReadMaxId: number, outboxReadMaxId: number): void

      commentsRead(broadcastChannelId: number, broadcastPostId: number, maxReadId: number): void

      changeRepliesCounter(channelId: number, originalMessageId: number, delta: number): void

      messagesDidLoad(dialogId: number, count: number, messages: Array<JavaObject>, isCache: boolean, firstUnread: number, lastMessageId: number, unreadCount: number, lastDate: number, loadType: number, isEnd: boolean, classGuid: number, loadIndex: number, maxId: number, mentionsCount: number, mode: number): void

      didLoadSponsoredMessages(dialogId: number, messages: TLObject): void

      didLoadSendAsPeers(dialogId: number, peers: TLObject, liveStories: boolean): void

      updateDefaultSendAsPeer(chatId: number, peer: TLObject): void

      messagesDidLoadWithoutProcess(classGuid: number, count: number, isCache: boolean, isEnd: boolean, lastMessageId: number): void

      loadingMessagesFailed(classGuid: number, request: TLObject, error: TLObject): void

      messageReceivedByAck(msgId: number): void

      messageReceivedByServer(oldId: number, newId: number, msg: JavaObject | null, dialogId: number, groupedId: number, existFlags: number, scheduled: boolean): void

      messageReceivedByServer2(oldId: number, newId: number, msg: JavaObject | null, dialogId: number, groupedId: number, existFlags: number, scheduled: boolean): void

      messageSendError(msgId: number): void

      forceImportContactsStart(): void

      contactsDidLoad(): void

      contactsImported(): void

      hasNewContactsToImport(checkType: number, contactHashMap: Map<string, JavaObject>, first: boolean, schedule: boolean): void

      chatDidCreated(chatId: number): void

      chatDidFailCreate(): void

      chatInfoDidLoad(chatFull: TLObject, classGuid: number, byChannelUsers: boolean, fromCache: boolean): void

      chatInfoCantLoad(channelId: number, reason: number): void

      mediaDidLoad(dialogId: number, totalCount: number, objects: Array<JavaObject>, classGuid: number, type: number, topReached: boolean, fromStart: boolean, requestIndex: number): void

      mediaCountDidLoad(dialogId: number, topicId: number, count: number, fromCache: boolean, type: number): void

      mediaCountsDidLoad(dialogId: number, topicId: number, counts: number[]): void

      encryptedChatUpdated(chat: TLObject): void

      messagesReadEncrypted(encryptedChatId: number, maxReadDate: number): void

      encryptedChatCreated(chat: TLObject): void

      dialogPhotosLoaded(): void

      reloadDialogPhotos(): void

      folderBecomeEmpty(folderId: number): void

      removeAllMessagesFromDialog(dialogId: number, fromDifference: boolean, difference: TLObject): void

      notificationsSettingsUpdated(): void

      blockedUsersDidLoad(): void

      openedChatChanged(dialogId: number, topicId: number, closed: boolean): void

      didCreatedNewDeleteTask(dialogId: number, mids: Map<number, Array<number>>): void

      mainUserInfoChanged(): void

      privacyRulesUpdated(): void

      updateMessageMedia(message: TLObject): void

      replaceMessagesObjects(dialogId: number, messageObjects: Array<JavaObject>, updateDialogs?: boolean): void

      didSetPasscode(isPasscodeChange?: boolean): void

      passcodeDismissed(view: JavaObject): void

      twoStepPasswordChanged(currentPasswordHash?: Uint8Array | null, newAlgo?: TLObject, newSecureAlgo?: TLObject, secureRandom?: Uint8Array | null, email?: string | null, hint?: string | null, unusedEmail?: string | null, firstPassword?: string | null): void

      didSetOrRemoveTwoStepPassword(currentPassword?: TLObject | null): void

      didRemoveTwoStepPassword(): void

      replyMessagesDidLoad(dialogId: number, loadedMessages: Array<JavaObject>, replyMessageOwners: Map<number, Map<number, Array<JavaObject>>> | null): void

      didLoadPinnedMessages(dialogId: number, ids: Array<number> | null, pin: boolean, arrayList: Array<JavaObject> | null, replaceObjects: Map<number, JavaObject> | null, maxId: number, totalPinnedCount: number, endReached: boolean): void

      newSessionReceived(): void

      didReceivedWebpages(messages: Array<TLObject>): void

      didReceivedWebpagesInUpdates(webPages: Map<number, TLObject>): void

      stickersDidLoad(type: number, forceUpdateUi: boolean): void

      diceStickersDidLoad(name: string): void

      featuredStickersDidLoad(): void

      featuredEmojiDidLoad(): void

      groupStickersDidLoad(setId: number, set: TLObject): void

      messagesReadContent(dialogId: number, messageIds: Array<number>): void

      botInfoDidLoad(botInfo: TLObject, classGuid: number): void

      userInfoDidLoad(userId: number, userFull: TLObject): void

      pinnedInfoDidLoad(
        peerId: number,
        pinnedMessages: Array<number>,
        pinnedMessagesMap: Map<number, JavaObject>,
        totalPinnedCount: number,
        pinnedEndReached: boolean
      ): void

      botKeyboardDidLoad(keyboard: TLObject, topicKey: number): void

      chatSearchResultsAvailable(
        guid: number,
        messageId: number,
        mask: number,
        dialogId: number,
        index: number,
        count: number,
        jumpToMessage: boolean
      ): void

      hashtagSearchUpdated(
        guid: number,
        count: number,
        endReached: boolean,
        mask: number,
        selectedIndex: number,
        messageId: number
      ): void

      chatSearchResultsLoading(guid: number): void

      musicDidLoad(
        dialogId: number,
        tracksBegin: Array<JavaObject>,
        tracksEnd: Array<JavaObject>
      ): void

      moreMusicDidLoad(addedCount: number): void

      needShowAlert(reason: number, ...extra: any[]): void

      needShowPlayServicesAlert(status: number): void

      didUpdateMessagesViews(
        channelViews: Map<number, Map<number, number>> | null,
        channelForwards: Map<number, Map<number, number>> | null,
        channelReplies: Map<number, Map<number, TLObject>> | null,
        onlySelf: boolean
      ): void

      needReloadRecentDialogsSearch(): void

      peerSettingsDidLoad(dialogId: number): void

      wasUnableToFindCurrentLocation(pendingMessages: Map<string, JavaObject>): void

      reloadHints(): void

      reloadInlineHints(): void

      reloadGuestBotHints(): void

      reloadWebappsHints(): void

      newDraftReceived(dialogId: number): void

      recentDocumentsDidLoad(isGif: boolean, type: number): void

      needAddArchivedStickers(archivedSets: Array<TLObject>): void

      archivedStickersCountDidLoad(type: number): void

      paymentFinished(): void

      channelRightsUpdated(chat: TLObject): void

      openArticle(webPage: TLObject, url: string): void

      articleClosed(): void

      updateMentionsCount(dialogId: number, arg1: number, unreadMentionsCount: number): void

      didUpdatePollResults(pollId: number, poll: TLObject, results: TLObject): void

      chatOnlineCountDidLoad(chatId: number, onlines: number): void

      videoLoadingStateChanged(key: string): void

      newPeopleNearbyAvailable(baseUpdate: TLObject): void

      stopAllHeavyOperations(flags: number): void

      startAllHeavyOperations(flags: number): void

      stopSpoilers(): void

      startSpoilers(): void

      sendingMessagesChanged(): void

      didUpdateReactions(dialogId: number, messageId: number, reactions: TLObject): void

      didUpdateExtendedMedia(dialogId: number, msgId: number, extendedMedia: Array<TLObject>): void

      didVerifyMessagesStickers(messages: Array<JavaObject>): void

      scheduledMessagesUpdated(dialogId: number, count: number, fromStorage: boolean): void

      newSuggestionsAvailable(): void

      didLoadChatInviter(chatId: number, inviterId: number): void

      didLoadChatAdmins(chatId: number): void

      historyImportProgressChanged(dialogId: number, req?: TLObject, error?: TLObject): void

      stickersImportProgressChanged(shortName: string, req?: TLObject, error?: TLObject): void

      stickersImportComplete(stickerSet: TLObject): void

      dialogDeleted(dialogId: number, topicId: number): void

      webViewResultSent(queryId: number): void

      voiceTranscriptionUpdate(messageObject: JavaObject | null, transcriptionId?: number | null, text?: string | null, isPremium?: boolean | null, isFinal?: boolean | null): void

      animatedEmojiDocumentLoaded(messageObject: JavaObject): void

      recentEmojiStatusesUpdate(): void

      updateSearchSettings(): void

      updateTranscriptionLock(): void

      businessMessagesUpdated(): void

      quickRepliesUpdated(): void

      quickRepliesDeleted(messageIds: number[], topicId: number): void

      bookmarkAdded(messageObject: JavaObject): void

      starReactionAnonymousUpdate(dialogId: number, messageId: number, peer: number): void

      businessLinksUpdated(): void

      businessLinkCreated(link: TLObject): void

      needDeleteBusinessLink(link: TLObject): void

      messageTranslated(messageObject: JavaObject, translated: boolean, dialogTranslating?: boolean): void

      messageTranslating(messageObject: JavaObject): void

      dialogIsTranslatable(dialogId: number): void

      dialogTranslate(dialogId: number, translating: boolean): void

      didGenerateFingerprintKeyPair(shouldNotifyCheck: boolean): void

      walletPendingTransactionsChanged(): void

      walletSyncProgressChanged(): void

      httpFileDidLoad(url: string, result: string): void

      httpFileDidFailedLoad(url: string, errorCode: number): void

      didUpdateConnectionState(): void

      fileUploaded(location: string, inputFile: TLObject, inputEncryptedFile: TLObject, key: Uint8Array, iv: Uint8Array, totalFileSize: number): void

      fileUploadFailed(location: string, isEncrypted: boolean): void

      fileUploadProgressChanged(location: string, uploadedSize: number, totalSize: number, isEncrypted: boolean): void

      fileLoadProgressChanged(url: string, downloadedSize: number, totalSize: number): void

      fileLoaded(location: string, finalFile: JavaObject): void

      fileLoadFailed(location: string, reason: number): void

      filePreparingStarted(messageObject: JavaObject, filePath: string, progress: number, lastFrameTimestamp: number): void

      fileNewChunkAvailable(messageObject: JavaObject, filePath: string, availableSize: number, finalSize: number, progress: number, lastFrameTimestamp: number): void

      filePreparingFailed(messageObject: JavaObject, file: string, progress: number, lastFrameTimestamp: number): void

      dialogsUnreadCounterChanged(pushDialogsCount: number): void

      messagePlayingProgressDidChanged(messageId: number, progress: number): void

      messagePlayingDidReset(messageId: number, stopService: boolean): void

      messagePlayingPlayStateChanged(messageId: number): void

      messagePlayingDidStart(messageObject: JavaObject, oldMessageObject: JavaObject | null): void

      messagePlayingDidSeek(messageId: number, progress: number): void

      messagePlayingGoingToStop(messageObject: JavaObject, stopService: boolean): void

      recordProgressChanged(recordingGuid: number, amplitude: number): void

      recordStarted(guid: number, isVideo: boolean): void

      recordStartError(guid: number): void

      recordStopped(guid: number, reason: number): void

      recordPaused(): void

      recordResumed(): void

      screenshotTook(): void

      albumsDidLoad(guid: number, mediaAlbumsSorted: Array<JavaObject>, photoAlbumsSorted: Array<JavaObject>, cameraAlbumId: number): void

      audioDidSent(guid: number, audio: object | null, filePath: string | null, keyframesOrFromDraft?: object, draftLeft?: number, draftRight?: number): void

      audioRecordTooShort(guid: number, isVideo: boolean, duration: number): void

      audioRouteChanged(useFrontSpeaker: boolean): void

      didStartedCall(): void

      groupCallUpdated(chatId: number, callId: number, selfUpdated: boolean, justJoinedId?: number): void

      storyGroupCallUpdated(dialogId: number, call: TLObject): void

      groupCallSpeakingUsersUpdated(chatId: number, callId: number, selfUpdated: boolean): void

      groupCallScreencastStateChanged(): void

      activeGroupCallsUpdated(): void

      applyGroupCallVisibleParticipants(time: number): void

      groupCallTypingsUpdated(): void

      didEndCall(): void

      closeInCallActivity(): void

      groupCallVisibilityChanged(): void

      liveStoryUpdated(callId: number): void

      liveStoryMessageUpdate(callId: number, update: TLObject, isHistory: boolean): void

      appDidLogout(): void

      configLoaded(): void

      needDeleteDialog(dialogId: number, user: TLObject, chat: TLObject, param: boolean): void

      newEmojiSuggestionsAvailable(lang: string): void

      themeUploadedToServer(themeInfo: JavaObject, accent: JavaObject | null): void

      themeUploadError(themeInfo: JavaObject, accent: JavaObject | null): void

      dialogFiltersUpdated(): void

      filterSettingsUpdated(): void

      suggestedFiltersLoaded(): void

      updateBotMenuButton(botId: number, botMenuButton: TLObject): void

      giftsToUserSent(): void

      didStartedMultiGiftsSelector(): void

      boostedChannelByUser(myBoosts: TLObject, boostedSlotsCount: number, uniqueChannelCount: number, boostsStatus: TLObject): void

      boostByChannelCreated(chat: TLObject, isGiveaway: boolean, prepaidGiveaway?: TLObject): void

      didUpdatePremiumGiftStickers(): void

      didUpdateTonGiftStickers(): void

      didUpdatePremiumGiftFieldIcon(): void

      storiesEnabledUpdate(): void

      storiesBlocklistUpdate(): void

      storiesLimitUpdate(): void

      storiesSendAsUpdate(): void

      unconfirmedAuthUpdate(): void

      dialogPhotosUpdate(dialogPhotos: JavaObject): void

      channelRecommendationsLoaded(dialogId: number): void

      savedMessagesDialogsUpdate(): void

      savedReactionTagsUpdate(topicId: number): void

      userIsPremiumBlockedUpadted(): void

      storyAlbumsCollectionsUpdate(dialogId: number, collections: JavaObject): void

      savedMessagesForwarded(newMessagesByIds: Map<number, number>): void

      emojiKeywordsLoaded(): void
      smsJobStatusUpdate(): void

      storyQualityUpdate(): void

      openBoostForUsersDialog(dialogId: number, cell?: JavaObject): void

      groupRestrictionsUnlockedByBoosts(): void

      chatWasBoostedByUser(boostsStatus: TLObject, canApplyBoost: JavaObject, dialogId: number): void

      groupPackUpdated(chatId: number, isEmoji: boolean): void

      timezonesUpdated(): void

      customStickerCreated(isSending?: boolean, stickerSet?: TLObject, document?: TLObject, thumbPath?: string | null, isReplacing?: boolean): void

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
      channelStarsUpdated(): void

      updateAllMessages(chatId: number): void

      starGiftsLoaded(): void

      starUserGiftsLoaded(dialogId: number, list: JavaObject): void

      starUserGiftCollectionsLoaded(dialogId: number, collections: JavaObject): void

      starGiftSoldOut(starGift: TLObject): void
      updateStories(): void

      botDownloadsUpdate(): void

      channelSuggestedBotsUpdate(dialogId: number): void

      channelConnectedBotsUpdate(dialogId: number): void

      adminedChannelsLoaded(): void

      messagesFeeUpdated(userId: number): void

      commonChatsLoaded(dialogId: number, list: JavaObject): void

      appConfigUpdated(): void

      activeAuctionsUpdated(): void

      conferenceEmojiUpdated(): void

      contentSettingsLoaded(): void

      musicListLoaded(list: JavaObject): void

      musicIdsLoaded(): void

      profileMusicUpdated(dialogId: number): void

      updatedChatRanks(chatId: number, userId: number, rank: string): void

      joinedGroup(chatId: number): void

      loadedAiComposeTones(controller: JavaObject): void

      updatedChatbot(): void

      activeAccountChanged(account: number): void

      pushMessagesUpdated(): void

      wallpapersDidLoad(wallPapers: Array<TLObject>): void

      wallpapersNeedReload(slug: string): void

      didReceiveSmsCode(code: string): void

      didReceiveCall(phone: string): void

      emojiLoaded(): void

      invalidateMotionBackground(): void

      closeOtherAppActivities(activity: JavaObject): void

      cameraInitied(): void

      didReplacedPhotoInMemCache(oldKey: string, newKey: string, newLocation: JavaObject): void

      didSetNewTheme(nightTheme: boolean, checkNavigationBarColor?: boolean, forceCheckKeyboardColor?: boolean): void

      themeListUpdated(): void

      didApplyNewTheme(previousTheme: JavaObject, previousAccent: JavaObject | null, deleteOnCancel: boolean): void

      themeAccentListUpdated(): void

      needCheckSystemBarColors(instant?: boolean): void

      needShareTheme(theme: JavaObject, accent: JavaObject | null): void

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

      goingToPreviewTheme(): void

      locationPermissionGranted(fromMediaGeo?: number): void

      locationPermissionDenied(fromMediaGeo?: number): void

      reloadInterface(): void

      suggestedLangpack(): void

      didSetNewWallpapper(): void

      proxySettingsChanged(): void

      proxyCheckDone(proxyInfo: JavaObject): void

      proxyChangedByRotation(): void

      liveLocationsChanged(): void

      newLocationAvailable(): void

      liveLocationsCacheChanged(dialogId: number, account?: number): void

      notificationsCountUpdated(account: number): void

      playerDidStartPlaying(player: JavaObject): void

      closeSearchByActiveAction(): void

      messagePlayingSpeedChanged(): void

      screenStateChanged(): void

      didClearDatabase(): void

      voipServiceCreated(): void

      webRtcMicAmplitudeEvent(amplitude: number): void

      webRtcSpeakerAmplitudeEvent(amplitude: number): void

      showBulletin(type: number, ...payload: any[]): void

      appUpdateAvailable(): void
      appUpdateLoading(): void

      onDatabaseMigration(finished: boolean): void

      onEmojiInteractionsReceived(dialogId: number, action: TLObject): void

      emojiPreviewThemesChanged(): void

      reactionsDidLoad(): void

      attachMenuBotsDidLoad(): void

      chatAvailableReactionsUpdated(chatId: number, unused: number): void
      dialogsUnreadReactionsCounterChanged(): void
      dialogsUnreadPollVotesCounterChanged(): void

      onDatabaseOpened(): void

      onDownloadingFilesChanged(): void

      onActivityResultReceived(requestCode: number, resultCode: number, data: JavaObject | null): void

      onRequestPermissionResultReceived(requestCode: number, permissions: string[], grantResults: number[]): void

      onUserRingtonesUpdated(): void

      currentUserPremiumStatusChanged(): void

      premiumPromoUpdated(): void

      premiumStatusChangedGlobal(): void

      currentUserShowLimitReachedDialog(limitType: number): void

      billingProductDetailsUpdated(): void

      billingConfirmPurchaseError(req: TLObject, error: TLObject): void

      premiumStickersPreviewLoaded(): void

      userEmojiStatusUpdated(user: TLObject): void
      requestPermissions(type: number): void

      permissionsGranted(type: number): void

      activityPermissionsGranted(requestCode: number, permissions: string[], grantResults: number[]): void

      topicsDidLoaded(chatId: number, fromCache: boolean): void

      chatSwitchedForum(chatId: number, forum: boolean, forumTabs: boolean): void

      didUpdateGlobalAutoDeleteTimer(): void

      onDatabaseReset(): void

      wallpaperSettedToUser(): void

      storiesUpdated(): void

      storyDeleted(dialogId: number, storyId: number): void

      storiesListUpdated(storiesList: JavaObject, initial?: boolean): void

      storiesDraftsUpdated(): void

      chatlistFolderUpdate(filterId: number): void

      uploadStoryProgress(path: string, progress: number): void

      uploadStoryEnd(path: string): void

      customTypefacesLoaded(): void

      stealthModeChanged(): void

      onReceivedChannelDifference(channelId: number): void

      storiesReadUpdated(): void

      nearEarEvent(isNear: boolean): void
      translationModelDownloading(lang: string, percent: number): void
      translationModelDownloaded(lang: string, success: boolean): void

      botForumTopicDidCreate(notification: JavaObject): void

      botForumDraftUpdate(notification: JavaObject): void

      botForumDraftDelete(notification: JavaObject): void

      tlSchemeParseException(exception: JavaObject): void
      memoryLeakFoundException(): void

      callTabsVisibleToggled(): void

      contactsPermissionBadgeCheck(): void

      guardBotDecisionResult(notification: JavaObject): void

      webBrowserSettingsUpdate(): void
    }

    type NotificationArg<T> = T extends number | string | boolean | undefined ? T : null

    type NotificationArgs<T extends unknown[]> = { [K in keyof T]: NotificationArg<T[K]> }

    /** @needs-grant unsafe.notificationCenter */
    function addNotificationCenterDelegate(
      handlers: {
        [key in keyof NotificationCenterEventsMap]?: (
          account: number,
          ...args: NotificationArgs<Parameters<NotificationCenterEventsMap[key]>>
        ) => void
      },
    ): Disposer
  }
}
