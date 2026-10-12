package desu.inugram.helpers.search

import android.os.SystemClock
import android.util.LruCache
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble

class SearchMediaScan internal constructor(
    private val account: Int,
    @JvmField val guid: Int,
    private val query: String,
    @JvmField val dialogId: Long,
    private val mergeDialogId: Long,
    private val threadId: Long,
    private val sender: TLRPC.InputPeer,
    private val senderId: Long,
    private val filter: TLRPC.MessagesFilter,
    private val types: Set<SearchFilterHelper.FilterItem>,
    private val reaction: ReactionsLayoutInBubble.VisibleReaction?,
    private val jumpToMessage: Boolean,
    private val cache: PageCache,
) {
    internal class PageCache {
        data class Key(val peerId: Long, val offsetId: Int, val filter: Class<out TLRPC.MessagesFilter>?)

        // Sender-only pages use a null filter and can be reused across media selections.
        // Each page contains at most 100 raw messages; eviction only causes a refetch.
        val pages = LruCache<Key, TLRPC.messages_Messages>(20)
        var nextRequestAt = 0L
    }

    var loading = true
        private set
    private var finished = false

    val hasMore: Boolean
        get() = !finished && !cancelled

    private val controller = MediaDataController.getInstance(account)
    private val connections = ConnectionsManager.getInstance(account)
    private var peerId = dialogId
    private var offsetId = 0
    private var filterBySender = false
    private var requestId = 0
    private var pending: Runnable? = null
    private var cancelled = false
    private var jumped = false
    private var nextMatchIndex = -1
    private var targetMatches = 20

    fun start() {
        controller.inu_publishSearchScan(this, arrayListOf(), false)
        startPeer()
    }

    fun cancel() {
        cancelled = true
        loading = false
        pending?.let(AndroidUtilities::cancelRunOnUIThread)
        pending = null
        if (requestId != 0) {
            connections.cancelRequest(requestId, true)
            requestId = 0
        }
    }

    fun loadMore() {
        if (loading || !hasMore) return
        targetMatches = controller.foundMessageObjects.size + 20
        loading = true
        controller.inu_publishSearchScan(this, arrayListOf(), false)
        schedule { send(filterBySender, ::acceptPage) }
    }

    fun navigate(direction: Int) {
        if (direction != 1 && direction != 2) return
        val index = controller.searchPosition + if (direction == 1) 1 else -1
        if (index in controller.foundMessageObjects.indices) {
            nextMatchIndex = -1
            controller.jumpToSearchedMessage(guid, index)
        } else if (direction == 1 && hasMore) {
            nextMatchIndex = controller.foundMessageObjects.size
            loadMore()
        }
    }

    private fun schedule(delay: Long = 0, action: () -> Unit) {
        val runnable = Runnable {
            pending = null
            if (!cancelled) action()
        }
        pending = runnable
        AndroidUtilities.runOnUIThread(runnable, delay)
    }

    private fun startPeer() {
        offsetId = 0
        filterBySender = false
        if (peerId > 0) {
            // Sender search in private chats is handled locally.
            send(false, ::acceptPage)
            return
        }
        send(true) { bySender ->
            schedule {
                send(false) { byMedia ->
                    val senderCount = if (bySender is TLRPC.TL_messages_messages) bySender.messages.size else bySender.count
                    val mediaCount = if (byMedia is TLRPC.TL_messages_messages) byMedia.messages.size else byMedia.count
                    filterBySender = senderCount < mediaCount
                    if (filterBySender) {
                        acceptPage(bySender)
                    } else {
                        acceptPage(byMedia)
                    }
                }
            }
        }
    }

    private fun send(bySender: Boolean, onResult: (TLRPC.messages_Messages) -> Unit) {
        val key = PageCache.Key(peerId, offsetId, if (bySender) null else filter.javaClass)
        val cached = cache.pages.get(key)
        if (cached != null) {
            schedule { onResult(cached) }
            return
        }
        val delay = cache.nextRequestAt - SystemClock.elapsedRealtime()
        if (delay > 0) {
            schedule(delay) { send(bySender, onResult) }
            return
        }
        val req = TLRPC.TL_messages_search().apply {
            peer = MessagesController.getInstance(account).getInputPeer(peerId)
            q = query
            limit = 100
            offset_id = offsetId
            filter = if (bySender) TLRPC.TL_inputMessagesFilterEmpty() else this@SearchMediaScan.filter
            if (bySender) {
                from_id = sender
                flags = flags or 1
            }
            if (threadId != 0L) {
                if (peerId == UserConfig.getInstance(account).clientUserId || MessagesStorage.getInstance(account).isMonoForum(peerId)) {
                    saved_peer_id = MessagesController.getInstance(account).getInputPeer(threadId)
                    flags = flags or 4
                } else {
                    top_msg_id = threadId.toInt()
                    flags = flags or 2
                }
            }
            if (reaction != null) {
                saved_reaction.add(reaction.toTLReaction())
                flags = flags or 8
            }
        }
        cache.nextRequestAt = SystemClock.elapsedRealtime() + 500
        requestId = connections.sendRequest(req, { response, error ->
            AndroidUtilities.runOnUIThread {
                if (cancelled) return@runOnUIThread
                requestId = 0
                cache.nextRequestAt = SystemClock.elapsedRealtime() + 500
                if (error != null) {
                    val waitSeconds = InuUtils.parseFloodWaitSeconds(error.text.orEmpty())
                    if (waitSeconds != null) {
                        val waitMillis = waitSeconds * 1000L
                        cache.nextRequestAt = SystemClock.elapsedRealtime() + waitMillis
                        schedule(waitMillis) {
                            send(bySender, onResult)
                        }
                    } else {
                        loading = false
                        finished = true
                        controller.inu_publishSearchScan(this, arrayListOf(), false)
                    }
                    return@runOnUIThread
                }
                val page = response as TLRPC.messages_Messages
                cache.pages.put(key, page)
                onResult(page)
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors)
    }

    private fun acceptPage(response: TLRPC.messages_Messages) {
        val messages = response.messages
        if (messages.isEmpty()) {
            if (peerId == dialogId && mergeDialogId != 0L) {
                peerId = mergeDialogId
                schedule { startPeer() }
            } else {
                loading = false
                finished = true
            }
            controller.inu_publishSearchScan(this, arrayListOf(), false)
            return
        }
        val nextOffset = messages.last().id
        if (offsetId != 0 && nextOffset >= offsetId) {
            loading = false
            finished = true
            controller.inu_publishSearchScan(this, arrayListOf(), false)
            return
        }
        MessagesStorage.getInstance(account).putUsersAndChats(response.users, response.chats, true, true)
        MessagesController.getInstance(account).putUsers(response.users, false)
        MessagesController.getInstance(account).putChats(response.chats, false)
        val matches = ArrayList<MessageObject>()
        val trimmedQuery = query.trim()
        val highlightWords = !trimmedQuery.startsWith("#") && !trimmedQuery.startsWith("$")
        for (message in messages) {
            if (offsetId != 0 && message.id >= offsetId) continue
            if (message is TLRPC.TL_messageEmpty || message.action is TLRPC.TL_messageActionHistoryClear) continue
            if (message is TLRPC.TL_messageService) continue
            if (MessageObject.getFromChatId(message) != senderId) continue
            val media = MessageObject.getMedia(message)
            if (media is TLRPC.TL_messageMediaWebPage) continue
            // Rich messages can contain photos or videos without ordinary media.
            if (message.rich_message == null && (media == null || media is TLRPC.TL_messageMediaEmpty)) continue
            val obj = MessageObject(account, message, false, true)
            val type = when {
                obj.isGif -> SearchFilterHelper.FilterItem.GIFS
                obj.isRoundVideo -> SearchFilterHelper.FilterItem.ROUND
                obj.isVoice -> SearchFilterHelper.FilterItem.VOICE
                obj.isMusic -> SearchFilterHelper.FilterItem.MUSIC
                obj.isVideo -> SearchFilterHelper.FilterItem.VIDEOS
                obj.isPhoto -> SearchFilterHelper.FilterItem.PHOTOS
                obj.isPoll -> SearchFilterHelper.FilterItem.POLL
                obj.isDocument -> SearchFilterHelper.FilterItem.FILES
                else -> null
            }
            if (type !in types) continue
            if (obj.hasValidGroupId()) obj.isPrimaryGroupMessage = true
            obj.setQuery(query, highlightWords)
            matches.add(obj)
        }
        offsetId = nextOffset
        if (matches.isNotEmpty() && (peerId == UserConfig.getInstance(account).clientUserId || SearchFilterHelper.isOnlyMatchesForGuid(guid))) {
            SearchFilterHelper.loadRepliesForGuid(account, matches, peerId, threadId, {
                publishPage(matches)
            }, guid)
            return
        }
        publishPage(matches)
    }

    private fun publishPage(matches: ArrayList<MessageObject>) {
        if (cancelled) return
        val wasLoading = loading
        loading = controller.foundMessageObjects.size + matches.size < targetMatches
        val jump = jumpToMessage && !jumped && nextMatchIndex < 0 && matches.isNotEmpty()
        if (matches.isNotEmpty()) jumped = true
        if (matches.isNotEmpty() || loading != wasLoading) {
            controller.inu_publishSearchScan(this, matches, jump)
        }
        if (nextMatchIndex in controller.foundMessageObjects.indices) {
            val index = nextMatchIndex
            nextMatchIndex = -1
            controller.jumpToSearchedMessage(guid, index)
        }
        if (loading) schedule { send(filterBySender, ::acceptPage) }
    }
}
