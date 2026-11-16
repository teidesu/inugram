package desu.inugram.helpers.chat

import android.net.Uri
import android.os.Bundle
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.LaunchActivity

object CrossAccountForwardHelper {
    private const val MAX_FORWARD_IDS = 100
    private const val HISTORY_SLACK = 20
    private const val FIND_ATTEMPTS = 10
    private const val FIND_RETRY_DELAY_MS = 500L

    private class Job(
        val source: Int,
        val relay: Int,
        val targets: List<MessagesStorage.TopicKey>,
        val prices: Map<Long, Long>?,
        val hideAuthor: Boolean,
        val hideCaption: Boolean,
        val notify: Boolean,
        val scheduleDate: Int,
        val scheduleRepeatPeriod: Int,
    ) {
        val sourceUserId = UserConfig.getInstance(source).clientUserId
        val relayUserId = UserConfig.getInstance(relay).clientUserId
        val relayName: String = UserObject.getFirstName(UserConfig.getInstance(relay).currentUser)
        var viewMessageId = 0

        fun fail(reason: String?) {
            AndroidUtilities.runOnUIThread {
                val text = LocaleController.formatString(R.string.InuCrossAccountForwardFailed, relayName, reason ?: LocaleController.getString(R.string.UnknownError))
                BulletinFactory.global().createErrorBulletin(text).show()
            }
        }
    }

    @JvmStatic
    fun forward(
        source: Int,
        relay: Int,
        messages: List<MessageObject>,
        targets: List<MessagesStorage.TopicKey>,
        prices: Map<Long, Long>?,
        comment: CharSequence?,
        hideAuthor: Boolean,
        hideCaption: Boolean,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
    ) {
        val job = Job(source, relay, ArrayList(targets), prices, hideAuthor, hideCaption, notify, scheduleDate, scheduleRepeatPeriod)
        val chunks = messages
            .filter { it.id > 0 && !it.needDrawBluredPreview() }
            .groupBy { it.dialogId }
            .values
            .flatMap { it.chunked(MAX_FORWARD_IDS) }
        ensureUser(job, source, relay) {
            ensureUser(job, relay, source) {
                if (comment != null) sendComment(job, comment.toString())
                relayChunks(job, chunks, 0)
            }
        }
    }

    private fun ensureUser(job: Job, viewer: Int, target: Int, then: () -> Unit) {
        val known = MessagesController.getInstance(viewer).getUser(UserConfig.getInstance(target).clientUserId)
        if (known != null && !known.min) return then()
        ConnectionsManager.getInstance(target).sendRequest(TLRPC.TL_contacts_exportContactToken()) { res, err ->
            val url = (res as? TLRPC.TL_exportedContactToken)?.url ?: return@sendRequest job.fail(err?.text)
            val uri = Uri.parse(url)
            val req = TLRPC.TL_contacts_importContactToken()
            req.token = if (uri.scheme == "tg") uri.getQueryParameter("token") else uri.lastPathSegment
            ConnectionsManager.getInstance(viewer).sendRequest(req) { user, err2 ->
                if (user !is TLRPC.User) return@sendRequest job.fail(err2?.text)
                AndroidUtilities.runOnUIThread {
                    MessagesController.getInstance(viewer).putUser(user, false)
                    MessagesStorage.getInstance(viewer).putUsersAndChats(arrayListOf(user), null, true, true)
                    then()
                }
            }
        }
    }

    private fun sendComment(job: Job, comment: String) {
        for (target in job.targets) {
            val params = SendMessagesHelper.SendMessageParams.of(
                comment, target.dialogId, null, null, null, true, null, null, null,
                job.notify, job.scheduleDate, job.scheduleRepeatPeriod, null, false,
            )
            params.payStars = job.prices?.get(target.dialogId) ?: 0
            SendMessagesHelper.getInstance(job.relay).sendMessage(params)
        }
    }

    private fun relayChunks(job: Job, chunks: List<List<MessageObject>>, index: Int) {
        if (index == chunks.size) {
            val text = LocaleController.formatString(R.string.InuCrossAccountForwardDone, job.relayName)
            if (job.targets.size == 1) {
                BulletinFactory.global().createSimpleBulletin(R.raw.forward, text, LocaleController.getString(R.string.ViewAction)) {
                    openTarget(job)
                }.show()
            } else {
                BulletinFactory.global().createSimpleBulletin(R.raw.forward, text).show()
            }
            return
        }
        val controller = MessagesController.getInstance(job.source)
        val chunk = chunks[index]
        val req = TLRPC.TL_messages_forwardMessages()
        req.from_peer = controller.getInputPeer(chunk[0].dialogId)
        req.to_peer = controller.getInputPeer(job.relayUserId)
        chunk.forEach {
            req.id.add(it.id)
            req.random_id.add(Utilities.random.nextLong())
        }
        req.silent = true
        req.drop_author = job.hideAuthor
        req.drop_media_captions = job.hideCaption
        ConnectionsManager.getInstance(job.source).sendRequest(req) { res, err ->
            if (res !is TLRPC.Updates) return@sendRequest job.fail(err?.text)
            val relayed = res.updates.filterIsInstance<TL_update.TL_updateNewMessage>().map { it.message }.sortedBy { it.id }
            controller.processUpdates(res, false)
            AndroidUtilities.runOnUIThread {
                if (relayed.isEmpty()) return@runOnUIThread job.fail(null)
                findRelayed(job, relayed, 0) { relayIds ->
                    forwardToTargets(job, relayIds) { ok ->
                        deleteRelayed(job, relayed)
                        if (ok) relayChunks(job, chunks, index + 1)
                    }
                }
            }
        }
    }

    // ids of a private chat message differ between its two sides, so the relay copies are matched by content
    private fun findRelayed(job: Job, relayed: List<TLRPC.Message>, attempt: Int, then: (List<Int>) -> Unit) {
        val controller = MessagesController.getInstance(job.relay)
        val req = TLRPC.TL_messages_getHistory()
        req.peer = controller.getInputPeer(job.sourceUserId)
        req.limit = relayed.size + HISTORY_SLACK
        ConnectionsManager.getInstance(job.relay).sendRequest(req) { res, err ->
            AndroidUtilities.runOnUIThread {
                if (res !is TLRPC.messages_Messages) {
                    deleteRelayed(job, relayed)
                    return@runOnUIThread job.fail(err?.text)
                }
                val ids = matchRelayed(relayed, res.messages.filter { !it.out }.sortedBy { it.id })
                if (ids != null) {
                    controller.putUsers(res.users, false)
                    controller.putChats(res.chats, false)
                    then(ids)
                } else if (attempt + 1 < FIND_ATTEMPTS) {
                    AndroidUtilities.runOnUIThread({ findRelayed(job, relayed, attempt + 1, then) }, FIND_RETRY_DELAY_MS)
                } else {
                    deleteRelayed(job, relayed)
                    job.fail(LocaleController.getString(R.string.InuCrossAccountForwardNotDelivered))
                }
            }
        }
    }

    private fun matchRelayed(relayed: List<TLRPC.Message>, incoming: List<TLRPC.Message>): List<Int>? {
        val ids = IntArray(relayed.size)
        var end = incoming.size
        for (i in relayed.indices.reversed()) {
            val sent = relayed[i]
            end = (end - 1 downTo 0).firstOrNull { incoming[it].date == sent.date && incoming[it].message == sent.message } ?: return null
            ids[i] = incoming[end].id
        }
        return ids.asList()
    }

    private fun forwardToTargets(job: Job, relayIds: List<Int>, then: (Boolean) -> Unit) {
        val controller = MessagesController.getInstance(job.relay)
        var pending = job.targets.size
        var ok = true
        for (target in job.targets) {
            val req = TLRPC.TL_messages_forwardMessages()
            req.from_peer = controller.getInputPeer(job.sourceUserId)
            req.to_peer = controller.getInputPeer(target.dialogId)
            relayIds.forEach {
                req.id.add(it)
                req.random_id.add(Utilities.random.nextLong())
            }
            req.silent = !job.notify || MessagesController.getNotificationsSettings(job.relay).getBoolean("silent_${target.dialogId}", false)
            req.drop_author = job.hideAuthor
            req.drop_media_captions = job.hideCaption
            if (target.topicId != 0L && controller.getChat(-target.dialogId)?.forum == true) {
                req.top_msg_id = target.topicId.toInt()
                req.flags = req.flags or 512
            }
            if (job.scheduleDate != 0) {
                req.schedule_date = job.scheduleDate
                req.flags = req.flags or 1024
                if (job.scheduleRepeatPeriod != 0) {
                    req.schedule_repeat_period = job.scheduleRepeatPeriod
                    req.flags = req.flags or TLObject.FLAG_24
                }
            }
            val price = job.prices?.get(target.dialogId) ?: 0
            if (price > 0) {
                req.allow_paid_stars = relayIds.size * price
                req.flags = req.flags or 2097152
            }
            ConnectionsManager.getInstance(job.relay).sendRequest(req) { res, err ->
                val forwardedId = (res as? TLRPC.Updates)?.updates?.mapNotNull {
                    when (it) {
                        is TL_update.TL_updateNewMessage -> it.message.id
                        is TL_update.TL_updateNewChannelMessage -> it.message.id
                        is TL_update.TL_updateNewScheduledMessage -> it.message.id
                        else -> null
                    }
                }?.minOrNull()
                if (res is TLRPC.Updates) controller.processUpdates(res, false)
                AndroidUtilities.runOnUIThread {
                    if (res !is TLRPC.Updates) {
                        ok = false
                        job.fail(err?.text)
                    } else if (job.viewMessageId == 0 && forwardedId != null) {
                        job.viewMessageId = forwardedId
                    }
                    if (--pending == 0) then(ok)
                }
            }
        }
    }

    private fun openTarget(job: Job) {
        val launchActivity = LaunchActivity.instance ?: return
        val target = job.targets[0]
        val args = Bundle()
        if (DialogObject.isUserDialog(target.dialogId)) {
            args.putLong("user_id", target.dialogId)
        } else {
            args.putLong("chat_id", -target.dialogId)
        }
        if (job.scheduleDate != 0) {
            args.putInt("chatMode", ChatActivity.MODE_SCHEDULED)
        } else if (job.viewMessageId != 0) {
            args.putInt("message_id", job.viewMessageId)
        }
        launchActivity.switchToAccount(job.relay, true)
        val parent = LaunchActivity.getSafeLastFragment() ?: return
        if (!MessagesController.getInstance(job.relay).checkCanOpenChat(args, parent)) return
        val chatActivity = ChatActivity(args)
        if (target.topicId != 0L && MessagesController.getInstance(job.relay).getChat(-target.dialogId)?.forum == true) {
            ForumUtilities.applyTopic(chatActivity, target)
        }
        parent.presentFragment(chatActivity)
    }

    private fun deleteRelayed(job: Job, relayed: List<TLRPC.Message>) {
        MessagesController.getInstance(job.source).deleteMessages(
            ArrayList(relayed.map { it.id }), null, null, job.relayUserId, 0, true, 0,
        )
    }
}
