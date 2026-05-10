package desu.inugram.helpers

import android.os.SystemClock
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import androidx.core.content.edit
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import java.text.BreakIterator
import java.util.concurrent.ThreadLocalRandom

object TypingDraftHelper {
    const val MODE_DISABLED = 0
    const val MODE_EMPTY = 1
    const val MODE_SIZE_ONLY = 2
    const val MODE_FULL = 3

    private const val INTERVAL_MS = 250L

    private val pending = HashMap<String, Runnable>()
    private val lastSentAt = HashMap<String, Long>()
    private val randomIds = HashMap<String, Long>()
    private val salts = HashMap<String, Long>()

    private fun modeKey(account: Int, dialogId: Long) = "live_typing_draft_mode:$account:$dialogId"

    @JvmStatic
    fun getMode(account: Int, dialogId: Long): Int =
        InuConfig.prefs.getInt(modeKey(account, dialogId), MODE_DISABLED)

    @JvmStatic
    fun setMode(account: Int, dialogId: Long, mode: Int) {
        val key = modeKey(account, dialogId)
        InuConfig.prefs.edit {
            if (mode == MODE_DISABLED) remove(key) else putInt(key, mode)
        }
    }

    private fun getOrCreateSalt(key: String): Long =
        salts.getOrPut(key) { ThreadLocalRandom.current().nextLong() }

    @JvmStatic
    fun modeLabelRes(mode: Int): Int = when (mode) {
        MODE_EMPTY -> R.string.InuLiveDraftModeEmpty
        MODE_SIZE_ONLY -> R.string.InuLiveDraftModeSizeOnly
        MODE_FULL -> R.string.InuLiveDraftModeFull
        else -> R.string.InuLiveDraftModeDisabled
    }

    @JvmStatic
    fun addSwipeBackMenuItem(parent: ActionBarMenuItem, account: Int, dialogId: Long, resourcesProvider: Theme.ResourcesProvider?) {
        val ctx = parent.context
        val popup = ActionBarPopupWindow.ActionBarPopupWindowLayout(ctx, 0, resourcesProvider)
        popup.setFitItems(true)

        val swipe = parent.popupLayout?.swipeBack
        if (swipe != null) {
            val back =
                ActionBarMenuItem.addItem(popup, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourcesProvider)
            back.setOnClickListener { swipe.closeForeground() }
        }

        val modes = intArrayOf(MODE_DISABLED, MODE_EMPTY, MODE_SIZE_ONLY, MODE_FULL)
        val current = getMode(account, dialogId)
        val cells = modes.map { mode ->
            val cell = ActionBarMenuItem.addItem(popup, 0, LocaleController.getString(modeLabelRes(mode)), true, resourcesProvider)
            cell.setChecked(mode == current)
            mode to cell
        }
        cells.forEach { (mode, cell) ->
            cell.setOnClickListener {
                setMode(account, dialogId, mode)
                cells.forEach { (m, c) -> c.setChecked(m == mode) }
                parent.toggleSubMenu()
            }
        }

        // footer: separator + caption with clickable link
        val sep = ActionBarPopupWindow.GapView(ctx, resourcesProvider, Theme.key_actionBarDefaultSubmenuSeparator)
        sep.setTag(R.id.fit_width_tag, 1)
        popup.addView(sep, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))

        val footer = LinkSpanDrawable.LinksTextView(ctx, resourcesProvider).apply {
            setTag(R.id.fit_width_tag, 1)
            setPadding(AndroidUtilities.dp(13f), 0, AndroidUtilities.dp(13f), AndroidUtilities.dp(8f))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider))
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider))
            text = AndroidUtilities.replaceSingleTag(
                LocaleController.getString(R.string.InuLiveDraftFooter),
                -1,
                AndroidUtilities.REPLACING_TAG_TYPE_LINK,
                Runnable { Browser.openUrl(ctx, "https://corefork.telegram.org/api/bots/ai#live-message-streaming") },
                resourcesProvider,
            )
        }
        popup.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0, 8, 0, 0))

        val cell = parent.addSwipeBackItem(R.drawable.msg_edit, null, LocaleController.getString(R.string.InuLiveDraftMenu), popup)

        // popupLayout.addView delegates to an inner LinearLayout; the cell's actual parent is that inner layout
        val container = cell.parent as? android.view.ViewGroup ?: return
        var anchorIdx = -1
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === cell) continue
            if (child is ActionBarMenuSubItem && child.openSwipeBackLayout != null) {
                anchorIdx = i
                break
            }
        }
        container.removeView(cell)
        if (anchorIdx >= 0) {
            // stock already adds a coloredGap after autoDelete, so no extra gap here
            container.addView(cell, anchorIdx + 1)
        } else {
            container.addView(cell, 0)
            val gap = ActionBarPopupWindow.GapView(ctx, resourcesProvider, Theme.key_actionBarDefaultSubmenuSeparator)
            gap.setTag(R.id.fit_width_tag, 1)
            container.addView(gap, 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))
        }
    }

    @JvmStatic
    fun forget(account: Int, dialogId: Long, threadMsgId: Long) {
        val key = "$account:$dialogId:$threadMsgId"
        pending.remove(key)?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        lastSentAt.remove(key)
        randomIds.remove(key)
        salts.remove(key)
    }

    @JvmStatic
    fun isEnabledFor(account: Int, dialogId: Long): Boolean {
        // sendMessageTextDraftAction is only valid for user dialogs; encrypted ids are negative so they fall through too
        if (dialogId <= 0L) return false
        if (DialogObject.isEncryptedDialog(dialogId)) return false
        return getMode(account, dialogId) != MODE_DISABLED
    }

    @JvmStatic
    fun sendTextDraft(account: Int, dialogId: Long, threadMsgId: Long, chatActivityEnterView: ChatActivityEnterView?): Boolean {
        if (!isEnabledFor(account, dialogId)) return false
        val text = chatActivityEnterView?.fieldText ?: return false
        val mode = getMode(account, dialogId)

        val key = "$account:$dialogId:$threadMsgId"
        val snapshotText: CharSequence = if (text.isNullOrEmpty()) "" else SpannableString(text)

        val now = SystemClock.uptimeMillis()
        val elapsed = now - (lastSentAt[key] ?: 0L)

        pending.remove(key)?.let { AndroidUtilities.cancelRunOnUIThread(it) }

        if (elapsed >= INTERVAL_MS) {
            lastSentAt[key] = now
            doSend(account, dialogId, threadMsgId, key, mode, snapshotText)
        } else {
            val runnable = Runnable {
                pending.remove(key)
                lastSentAt[key] = SystemClock.uptimeMillis()
                doSend(account, dialogId, threadMsgId, key, mode, snapshotText)
            }
            pending[key] = runnable
            AndroidUtilities.runOnUIThread(runnable, INTERVAL_MS - elapsed)
        }
        return true
    }

    private fun doSend(account: Int, dialogId: Long, threadMsgId: Long, key: String, mode: Int, text: CharSequence) {
        val mc = MessagesController.getInstance(account)
        val selfId = UserConfig.getInstance(account).clientUserId
        if (dialogId == selfId) return

        val user = mc.getUser(dialogId) ?: return
        if (user.id == selfId) return
        val status = user.status
        if (status != null && status.expires != -100 && !mc.onlinePrivacy.containsKey(user.id)) {
            val now = ConnectionsManager.getInstance(account).currentTime
            if (status.expires <= now - 30) return
        }

        val peer = mc.getInputPeer(dialogId) ?: return

        val req = TLRPC.TL_messages_setTyping()
        req.peer = peer
        if (threadMsgId != 0L) {
            req.top_msg_id = threadMsgId.toInt()
            req.flags = req.flags or 1
        }

        if (text.isEmpty()) {
            req.action = TLRPC.TL_sendMessageCancelAction()
        } else {
            val draft = TLRPC.TL_sendMessageTextDraftAction()
            draft.random_id = randomIds.getOrPut(key) { ThreadLocalRandom.current().nextLong() }
            val twe = TLRPC.TL_textWithEntities()
            when (mode) {
                MODE_EMPTY -> {
                    twe.text = ""
                }

                MODE_SIZE_ONLY -> {
                    twe.text = obfuscateBraille(getOrCreateSalt(key), text)
                }

                else -> { // MODE_FULL
                    val mutable: Array<CharSequence> = arrayOf(text)
                    val entities = MediaDataController.getInstance(account).getEntities(mutable, true)
                    twe.text = mutable[0].toString()
                    if (entities != null) twe.entities = entities
                }
            }
            draft.text = twe
            req.action = draft
        }

        ConnectionsManager.getInstance(account).sendRequest(req, null, ConnectionsManager.RequestFlagFailOnServerErrors)
    }

    private fun obfuscateBraille(salt: Long, text: CharSequence): String {
        val s = text.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val sb = StringBuilder(s.length)
        var start = it.first()
        var end = it.next()
        var idx = 0
        while (end != BreakIterator.DONE) {
            val grapheme = s.substring(start, end)
            if (grapheme.any { it == '\n' || it == '\r' }) {
                sb.append(grapheme)
            } else {
                val mixed = splitmix64(salt + idx.toLong())
                sb.append((0x2801 + (mixed and 0xFFL).toInt() % 255).toChar())
                idx++
            }
            start = end
            end = it.next()
        }
        return sb.toString()
    }

    private fun splitmix64(x0: Long): Long {
        var x = x0 + -0x61C8864680B583EBL // 0x9E3779B97F4A7C15
        x = (x xor (x ushr 30)) * -0x40A7B892E9B16D61L // 0xBF58476D1CE4E5B7
        x = (x xor (x ushr 27)) * -0x6B2FB6446CCEEC15L // 0x94D049BB133111EB
        return x xor (x ushr 31)
    }
}
