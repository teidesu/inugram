package desu.inugram.helpers

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.TLRPC.MessagesFilter
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import java.lang.ref.WeakReference
import kotlin.math.abs

class SearchFilterHelper(
    private val fragment: ChatActivity,
    searchContainer: FrameLayout,
    private val leftDp: Float,
) {
    init {
        registry[fragment.classGuid] = WeakReference(this)
    }

    var onlyMatches: Boolean = false
        private set


    enum class FilterItem(val labelRes: Int) {
        GIFS(R.string.InuSearchFilterGifs),
        MUSIC(R.string.InuSearchFilterMusic),
        PHOTOS(R.string.InuSearchFilterPhotos),
        FILES(R.string.InuSearchFilterFiles),
        VIDEOS(R.string.InuSearchFilterVideos),
        VOICE(R.string.InuSearchFilterVoice),
        ROUND(R.string.InuSearchFilterRound),
        POLL(R.string.InuSearchFilterPoll),
    }

    private val selection: MutableSet<FilterItem> = mutableSetOf()

    val button: ImageView = ImageView(searchContainer.context).also { b ->
        b.scaleType = ImageView.ScaleType.CENTER
        b.setImageResource(R.drawable.inu_tabler_filter)
        b.colorFilter = PorterDuffColorFilter(
            fragment.getThemedColor(Theme.key_chat_searchPanelIcons),
            PorterDuff.Mode.MULTIPLY,
        )
        val bgCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fragment.getThemedColor(Theme.key_chat_messagePanelBackground))
        }
        b.background = LayerDrawable(
            arrayOf(
                bgCircle,
                Theme.createSelectorDrawable(fragment.getThemedColor(Theme.key_actionBarActionModeDefaultSelector), 1),
            )
        )
        b.contentDescription = LocaleController.getString(R.string.InuSearchFilter)
        b.setOnClickListener { showMenu(b) }
        searchContainer.addView(
            b,
            LayoutHelper.createFrame(44, 44f, Gravity.LEFT or Gravity.TOP, leftDp, 0f, 0f, 0f),
        )
    }

    fun reset() {
        if (onlyMatches) {
            onlyMatches = false
            applyOnlyMatches()
        }
        selection.clear()
    }

    fun rerunSearch() {
        fragment.mediaDataController.searchMessagesInChat(
            fragment.searchingQuery,
            fragment.dialogId,
            fragment.mergeDialogId,
            fragment.classGuid,
            0,
            fragment.threadMessageId,
            fragment.searchingUserMessages,
            fragment.searchingChatMessages,
            fragment.searchingReaction
        )
    }

    fun setCompact(compact: Boolean) {
        val lp = button.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = AndroidUtilities.dp(if (compact) 2f else leftDp)
        button.layoutParams = lp
    }

    fun getFilter(): MessagesFilter = when (selection) {
        setOf(FilterItem.GIFS) -> TLRPC.TL_inputMessagesFilterGif()
        setOf(FilterItem.MUSIC) -> TLRPC.TL_inputMessagesFilterMusic()
        setOf(FilterItem.PHOTOS) -> TLRPC.TL_inputMessagesFilterPhotos()
        setOf(FilterItem.FILES) -> TLRPC.TL_inputMessagesFilterDocument()
        setOf(FilterItem.VIDEOS) -> TLRPC.TL_inputMessagesFilterVideo()
        setOf(FilterItem.VOICE) -> TLRPC.TL_inputMessagesFilterVoice()
        setOf(FilterItem.ROUND) -> TLRPC.TL_inputMessagesFilterRoundVideo()
        setOf(FilterItem.POLL) -> TLRPC.TL_inputMessagesFilterPoll()
        setOf(FilterItem.PHOTOS, FilterItem.VIDEOS) -> TLRPC.TL_inputMessagesFilterPhotoVideo()
        setOf(
            FilterItem.PHOTOS,
            FilterItem.VIDEOS,
            FilterItem.FILES
        ) -> TLRPC.TL_inputMessagesFilterPhotoVideoDocuments()

        setOf(FilterItem.VOICE, FilterItem.ROUND) -> TLRPC.TL_inputMessagesFilterRoundVoice()
        else -> TLRPC.TL_inputMessagesFilterEmpty()
    }

    private fun toggle(item: FilterItem) {
        if (item in selection) {
            val candidate = selection - item
            if (candidate in VALID_COMBOS) {
                selection.remove(item)
            } else {
                selection.clear()
            }
        } else {
            val candidate = selection + item
            if (candidate in VALID_COMBOS) {
                selection.add(item)
            } else {
                selection.clear()
                selection.add(item)
            }
        }
    }

    private fun applyOnlyMatches() {
        val mdc = fragment.mediaDataController
        if (onlyMatches) {
            val found = ArrayList(mdc.foundMessageObjects)
            val sel = mdc.searchPosition
            val targetId = if (sel in 0 until found.size) found[sel].id else 0
            fragment.setFilterMessages(true, true, false)
            mdc.loadReplyMessagesForMessages(found, fragment.dialogId, 0, fragment.threadMessageId, {
                fragment.updateFilteredMessages(true)
                if (targetId != 0) fragment.scrollToMessageId(targetId, 0, true, 0, true, 0)
            }, fragment.classGuid, null)
        } else {
            val middleId = findMiddleVisibleMessageId()
            var idx = -1
            if (middleId != 0) {
                val found = mdc.foundMessageObjects
                for (i in found.indices) {
                    if (found[i].id == middleId) {
                        idx = i
                        break
                    }
                }
            }
            fragment.setFilterMessages(false, true, false)
            if (idx >= 0) mdc.jumpToSearchedMessage(fragment.classGuid, idx)
        }
    }

    private fun findMiddleVisibleMessageId(): Int {
        val list = fragment.chatListView ?: return 0
        val center = list.measuredHeight / 2
        var best = Int.MAX_VALUE
        var id = 0
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child !is ChatMessageCell) continue
            val msg: MessageObject = child.messageObject ?: continue
            if (msg.id == 0) continue
            val d = abs((child.top + child.bottom) / 2 - center)
            if (d < best) {
                best = d
                id = msg.id
            }
        }
        return id
    }

    private fun moveCheckRight(sub: ActionBarMenuSubItem) {
        sub.checkView?.let { sub.removeView(it) }
        sub.makeCheckView(2)
    }

    private fun showMenu(anchor: View) {
        val options = ItemOptions
            .makeOptions(fragment, anchor)
            .setDismissWithButtons(false)
            .setMinWidth(180)
            .setGravity(Gravity.LEFT)

        val subItems = MENU_ORDER.map { item ->
            val sub = options.addChecked()
            moveCheckRight(sub)
            sub.setText(LocaleController.getString(item.labelRes))
            item to sub
        }
        options.addGap()
        val onlyMatchesSub = options.addChecked()
        moveCheckRight(onlyMatchesSub)
        onlyMatchesSub.setText(LocaleController.getString(R.string.InuSearchFilterOnlyMatches))

        val update = Runnable {
            for ((item, sub) in subItems) sub.setChecked(item in selection)
            onlyMatchesSub.setChecked(onlyMatches)
        }
        update.run()

        for ((item, sub) in subItems) {
            sub.setOnClickListener {
                toggle(item)
                update.run()
                rerunSearch()
            }
        }
        onlyMatchesSub.setOnClickListener {
            onlyMatches = !onlyMatches
            applyOnlyMatches()
            options.dismiss()
        }

        options.show()
    }

    companion object {
        // crutch to avoid passing more stuff
        private val registry = mutableMapOf<Int, WeakReference<SearchFilterHelper>>()

        @JvmStatic
        fun filterForGuid(classGuid: Int): MessagesFilter =
            registry[classGuid]?.get()?.getFilter() ?: TLRPC.TL_inputMessagesFilterEmpty()

        @JvmStatic
        fun isOnlyMatchesForGuid(classGuid: Int): Boolean =
            registry[classGuid]?.get()?.onlyMatches == true

        private val MENU_ORDER = listOf(
            FilterItem.PHOTOS,
            FilterItem.VIDEOS,
            FilterItem.FILES,
            FilterItem.VOICE,
            FilterItem.ROUND,
            FilterItem.GIFS,
            FilterItem.MUSIC,
            FilterItem.POLL,
        )

        // every subset representable as a single MessagesFilter
        private val VALID_COMBOS: Set<Set<FilterItem>> = setOf(
            emptySet(),
            setOf(FilterItem.GIFS),
            setOf(FilterItem.MUSIC),
            setOf(FilterItem.PHOTOS),
            setOf(FilterItem.FILES),
            setOf(FilterItem.VIDEOS),
            setOf(FilterItem.VOICE),
            setOf(FilterItem.ROUND),
            setOf(FilterItem.POLL),
            setOf(FilterItem.PHOTOS, FilterItem.VIDEOS),
            setOf(FilterItem.PHOTOS, FilterItem.VIDEOS, FilterItem.FILES),
            setOf(FilterItem.VOICE, FilterItem.ROUND),
        )
    }
}
