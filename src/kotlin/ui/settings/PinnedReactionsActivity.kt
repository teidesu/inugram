package desu.inugram.ui.settings


import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.PinnedReactionsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stars.TL_starGiftUnique
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LinkSpanDrawable.LinksTextView
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet
import org.telegram.ui.Components.Reactions.HwEmojis
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.PremiumPreviewFragment
import org.telegram.ui.SelectAnimatedEmojiDialog
import java.util.Collections

class PinnedReactionsActivity : SettingsPageActivity() {

    private val pins = InuConfig.PINNED_REACTIONS.value
    private var selectedIndex = 0

    private var stripView: RecyclerListView? = null
    private var stripAdapter: StripAdapter? = null
    private var picker: SelectAnimatedEmojiDialog? = null
    private var stripDragging = false
    private var animatedStripHeight = -1
    private var stripHeightAnimator: ValueAnimator? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPinnedReactions)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asRippleCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.Enable))
                .setChecked(InuConfig.PINNED_REACTIONS_ENABLED.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPinnedReactionsInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPinnedReactionsHeader)))
        items.add(UItem.asCustom(getOrCreateStrip()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPinnedReactionsLimitInfo)))

        val pickerHeightDp = (AndroidUtilities.displaySize.y / AndroidUtilities.density / 2f).toInt().coerceAtLeast(280)
        items.add(UItem.asCustom(getOrCreatePicker(), pickerHeightDp))
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_COPY_SERVER,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuPinnedReactionsCopyServer)
            )
        )
        if (pins.size > 1) {
            items.add(
                UItem.asButton(
                    BUTTON_REMOVE_ALL,
                    R.drawable.msg_delete,
                    LocaleController.getString(R.string.InuPinnedReactionsRemoveAll)
                ).red()
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.PINNED_REACTIONS_ENABLED.toggle()
                (view as? TextCheckCell)?.apply {
                    setChecked(new)
                    setBackgroundColorAnimated(
                        new,
                        Theme.getColor(if (new) Theme.key_windowBackgroundChecked else Theme.key_windowBackgroundUnchecked),
                    )
                }
                updateEnabledAlpha()
            }

            BUTTON_COPY_SERVER -> {
                val newPins = previewServerPins()
                if (pins.isEmpty()) {
                    applyServerPins(newPins)
                    return
                }
                showDialog(
                    AlertDialog.Builder(context)
                        .setTitle(LocaleController.getString(R.string.InuPinnedReactionsCopyServer))
                        .setMessage(LocaleController.getString(R.string.InuPinnedReactionsCopyServerConfirm))
                        .setView(buildCopyPreview(newPins))
                        .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ -> applyServerPins(newPins) }
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .create()
                )
            }

            BUTTON_REMOVE_ALL -> showDialog(
                AlertDialog.Builder(context)
                    .setTitle(LocaleController.getString(R.string.InuPinnedReactionsRemoveAll))
                    .setMessage(LocaleController.getString(R.string.InuPinnedReactionsRemoveAllConfirm))
                    .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                        pins.clear()
                        selectedIndex = 0
                        InuConfig.PINNED_REACTIONS.save()
                        stripAdapter?.update()
                        listView.adapter.update(true)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()
            )
        }
    }

    private fun stripSpanCount(): Int {
        val availableWidth = AndroidUtilities.displaySize.x - dp(12) * 2
        return (availableWidth / dp(STRIP_CELL_DP)).coerceAtLeast(1)
    }

    private fun computeStripHeight(): Int {
        val rv = stripView ?: return 0
        val count = stripAdapter?.itemCount ?: 0
        val rows = if (count > 0) (count + stripSpanCount() - 1) / stripSpanCount() else 0
        return rows * dp(STRIP_CELL_DP) + rv.paddingTop + rv.paddingBottom
    }

    private fun updatePickerSelection() {
        val picker = picker ?: return
        val set = HashSet<VisibleReaction>()
        pins.getOrNull(selectedIndex)?.let { set.add(it.toVisibleReaction()) }
        picker.setSelectedReactions(set)
        refreshPickerSelectionViews(picker.emojiGridView, set)
        refreshPickerSelectionViews(picker.emojiSearchGridView, set)
    }

    private fun refreshPickerSelectionViews(grid: RecyclerListView?, set: HashSet<VisibleReaction>) {
        val g = grid ?: return
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i) as? SelectAnimatedEmojiDialog.ImageViewEmoji ?: continue
            v.setViewSelected(set.contains(v.reaction), true)
        }
        g.invalidate()
    }

    private fun animateStripHeight() {
        val rv = stripView ?: return
        val target = computeStripHeight()
        if (animatedStripHeight < 0) {
            animatedStripHeight = target
            return
        }
        if (animatedStripHeight == target) return
        stripHeightAnimator?.cancel()
        stripHeightAnimator = ValueAnimator.ofInt(animatedStripHeight, target).apply {
            duration = 180
            addUpdateListener {
                animatedStripHeight = it.animatedValue as Int
                rv.requestLayout()
            }
            start()
        }
    }

    private fun getOrCreateStrip(): View {
        stripView?.let { return it }
        val rv = object : RecyclerListView(context) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val result = super.onTouchEvent(e)
                if (stripDragging) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return result
            }

            override fun setPressed(pressed: Boolean) {}

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (animatedStripHeight < 0) animatedStripHeight = computeStripHeight()
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(animatedStripHeight, MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            layoutManager = GridLayoutManager(context, stripSpanCount())
            stripAdapter = StripAdapter().also { it.items = buildStripItems() }
            adapter = stripAdapter
            setPadding(dp(12), 0, dp(12), dp(8))
            clipToPadding = false
            setSelectorType(9)
            setSelectorDrawableColor(0)
            itemAnimator?.apply {
                addDuration = 140
                removeDuration = 140
                moveDuration = 180
                changeDuration = 140
            }
        }
        ItemTouchHelper(StripDragCallback()).attachToRecyclerView(rv)
        rv.setOnItemClickListener { _, position ->
            val item = stripAdapter?.items?.getOrNull(position) ?: return@setOnItemClickListener
            when (item.type) {
                StripItem.TYPE_PLUS -> {
                    if (pins.size >= PinnedReactionsHelper.MAX_PINS) return@setOnItemClickListener
                    selectedIndex = pins.size
                }

                StripItem.TYPE_REMOVE -> {
                    if (selectedIndex !in pins.indices) return@setOnItemClickListener
                    pins.removeAt(selectedIndex)
                    selectedIndex = selectedIndex.coerceAtMost(pins.size - 1).coerceAtLeast(0)
                    InuConfig.PINNED_REACTIONS.save()
                }

                else -> selectedIndex = position
            }
            stripAdapter?.update()
            listView.adapter.update(true)
        }
        rv.alpha = if (InuConfig.PINNED_REACTIONS_ENABLED.value) 1f else 0.5f
        stripView = rv
        return rv
    }

    private fun getOrCreatePicker(): View {
        picker?.let { return it }
        val view = object : SelectAnimatedEmojiDialog(
            this,
            context,
            false,
            null,
            TYPE_REACTIONS,
            null,
        ) {
            private var firstLayout = true

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                // picker is normally a popup; embedded usage needs a manual onShow to start emoji loads
                if (firstLayout) {
                    firstLayout = false
                    onShow(null)
                }
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
                if (!disallowIntercept) {
                    // inner RecyclerListView clears the flag on every touch event;
                    // re-assert it so the outer list never intercepts mid-gesture
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            override fun onReactionClick(emoji: ImageViewEmoji?, reaction: VisibleReaction) {
                if (!checkEnabled()) return
                if (reaction.documentId != 0L && !UserConfig.getInstance(currentAccount).isPremium) {
                    showPremiumBulletin(null, reaction.documentId)
                    return
                }
                applyPick(
                    if (reaction.documentId != 0L) PinnedReactionsHelper.Pin(null, reaction.documentId)
                    else PinnedReactionsHelper.Pin(reaction.emojicon, 0L)
                )
            }

            override fun onEmojiSelected(
                view: View?,
                documentId: Long?,
                document: TLRPC.Document?,
                gift: TL_starGiftUnique?,
                until: Int?,
            ) {
                if (!checkEnabled()) return
                val docId = documentId ?: document?.id ?: return
                if (!UserConfig.getInstance(currentAccount).isPremium) {
                    showPremiumBulletin(document, docId)
                    return
                }
                applyPick(PinnedReactionsHelper.Pin(null, docId))
            }
        }
        view.setAnimationsEnabled(fragmentBeginToShow)
        view.setDrawBackground(false)
        view.tag = RecyclerListView.TAG_NOT_SECTION
        view.alpha = if (InuConfig.PINNED_REACTIONS_ENABLED.value) 1f else 0.5f
        val reactions =
            MediaDataController.getInstance(currentAccount).reactionsList.map { VisibleReaction.fromEmojicon(it) }
        view.setRecentReactions(reactions)
        view.emojiGridView?.isNestedScrollingEnabled = false
        view.emojiSearchGridView?.isNestedScrollingEnabled = false
        HwEmojis.disableHw()
        picker = view
        updatePickerSelection()
        return view
    }

    private fun checkEnabled(): Boolean {
        if (InuConfig.PINNED_REACTIONS_ENABLED.value) return true
        fragmentView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return false
    }

    private fun showPremiumBulletin(document: TLRPC.Document?, docId: Long) {
        val doc = document ?: AnimatedEmojiDrawable.findDocument(currentAccount, docId) ?: return
        BulletinFactory.of(this).createEmojiBulletin(
            doc,
            AndroidUtilities.replaceTags(LocaleController.getString(R.string.UnlockPremiumEmojiReaction)),
            LocaleController.getString(R.string.PremiumMore),
        ) {
            PremiumFeatureBottomSheet(this, PremiumPreviewFragment.PREMIUM_FEATURE_REACTIONS, false).show()
        }.show()
    }

    private fun applyPick(pin: PinnedReactionsHelper.Pin) {
        val existing = pins.indexOf(pin)
        if (existing == selectedIndex) return

        when {
            existing >= 0 && selectedIndex < pins.size -> Collections.swap(pins, selectedIndex, existing)
            existing >= 0 -> selectedIndex = existing
            selectedIndex >= pins.size -> {
                if (pins.size >= PinnedReactionsHelper.MAX_PINS) return
                pins.add(pin)
                selectedIndex = pins.lastIndex
            }

            else -> pins[selectedIndex] = pin
        }
        InuConfig.PINNED_REACTIONS.save()
        stripAdapter?.update()
        listView.adapter.update(true)
    }

    private fun previewServerPins(): List<PinnedReactionsHelper.Pin> {
        val data = MediaDataController.getInstance(currentAccount)
        val limit = InuConfig.REACTIONS_IN_ROW.value.coerceAtMost(PinnedReactionsHelper.MAX_PINS)
        val seen = HashSet<VisibleReaction>()
        val result = mutableListOf<VisibleReaction>()

        fun add(r: VisibleReaction) {
            if (r.isStar || !seen.add(r)) return
            if (!UserConfig.getInstance(currentAccount).isPremium && r.documentId != 0L) return
            result.add(r)
        }

        for (r in data.topReactions) add(VisibleReaction.fromTL(r))
        for (r in data.recentReactions) add(VisibleReaction.fromTL(r))
        for (r in data.enabledReactionsList) add(VisibleReaction.fromEmojicon(r))

        return result.take(limit).map {
            if (it.documentId != 0L) PinnedReactionsHelper.Pin(null, it.documentId)
            else PinnedReactionsHelper.Pin(it.emojicon, 0L)
        }
    }

    private fun applyServerPins(newPins: List<PinnedReactionsHelper.Pin>) {
        pins.clear()
        pins.addAll(newPins)
        selectedIndex = 0
        InuConfig.PINNED_REACTIONS.save()
        stripAdapter?.update()
        listView.adapter.update(true)
    }

    private fun buildCopyPreview(previewPins: List<PinnedReactionsHelper.Pin>): View {
        val tv = LinksTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourceProvider))
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        NotificationCenter.listenEmojiLoading(tv)

        val sb = SpannableStringBuilder()
        val entities = ArrayList<TLRPC.MessageEntity>()
        for (pin in previewPins) {
            if (sb.isNotEmpty()) sb.append(' ')
            if (pin.docId != 0L) {
                val placeholder = "😀"
                entities.add(TLRPC.TL_messageEntityCustomEmoji().apply {
                    offset = sb.length
                    length = placeholder.length
                    document_id = pin.docId
                })
                sb.append(placeholder)
            } else {
                sb.append(pin.emoji ?: "")
            }
        }
        val replaced = Emoji.replaceEmoji(sb, tv.paint.fontMetricsInt, false)
        tv.text = MessageObject.replaceAnimatedEmoji(replaced, entities, tv.paint.fontMetricsInt)
        return tv
    }

    private fun updateEnabledAlpha() {
        val alpha = if (InuConfig.PINNED_REACTIONS_ENABLED.value) 1f else 0.5f
        stripView?.alpha = alpha
        picker?.alpha = alpha
    }

    private fun buildStripItems(): ArrayList<StripItem> {
        val items = ArrayList<StripItem>()
        for ((i, pin) in pins.withIndex()) {
            items.add(StripItem(StripItem.TYPE_PIN, pin, i == selectedIndex))
        }
        if (pins.size < PinnedReactionsHelper.MAX_PINS) {
            items.add(StripItem(StripItem.TYPE_PLUS, null, pins.size == selectedIndex))
        }
        if (selectedIndex in pins.indices) {
            items.add(StripItem(StripItem.TYPE_REMOVE, null, false))
        }
        return items
    }

    private class StripItem(
        val type: Int,
        val pin: PinnedReactionsHelper.Pin?,
        val selected: Boolean,
    ) : AdapterWithDiffUtils.Item(type, true) {
        companion object {
            const val TYPE_PIN = 0
            const val TYPE_PLUS = 1
            const val TYPE_REMOVE = 2
        }

        override fun equals(other: Any?): Boolean = other is StripItem && type == other.type && pin == other.pin
        override fun hashCode(): Int = type * 31 + (pin?.hashCode() ?: 0)

        override fun contentsEquals(item: AdapterWithDiffUtils.Item): Boolean =
            item is StripItem && this == item && selected == item.selected
    }

    private inner class StripAdapter : AdapterWithDiffUtils() {
        var items = ArrayList<StripItem>()

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return RecyclerListView.Holder(StripCell(context))
        }

        override fun getItemViewType(position: Int): Int = items[position].type

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as StripCell).bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        fun update() {
            val newItems = buildStripItems()
            setItems(items, newItems)
            items = newItems
            animateStripHeight()
            updatePickerSelection()
        }
    }

    private inner class StripCell(context: Context) : View(context) {
        private val imageReceiver = ImageReceiver(this)
        private var emojiDrawable: AnimatedEmojiDrawable? = null
        private var cellType = StripItem.TYPE_PIN
        private var isSelected = false
        private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val plusDrawable =
            Theme.getThemedDrawableByKey(context, R.drawable.msg_add, Theme.key_windowBackgroundWhiteGrayIcon)
        private val removeDrawable =
            Theme.getThemedDrawableByKey(context, R.drawable.msg_close, Theme.key_text_RedRegular)

        init {
            layoutParams = ViewGroup.LayoutParams(dp(STRIP_CELL_DP), dp(STRIP_CELL_DP))
            ScaleStateListAnimator.apply(this)
        }

        fun bind(item: StripItem) {
            cellType = item.type
            isSelected = item.selected
            imageReceiver.clearImage()
            emojiDrawable?.removeView(this)
            emojiDrawable = null

            val pin = item.pin
            if (pin != null) {
                if (pin.docId != 0L) {
                    emojiDrawable = AnimatedEmojiDrawable.make(
                        currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_KEYBOARD, pin.docId,
                    ).also { it.addView(this) }
                } else {
                    val r = MediaDataController.getInstance(currentAccount).reactionsMap[pin.emoji]
                    if (r != null) {
                        val svgThumb = DocumentObject.getSvgThumb(
                            r.activate_animation, Theme.key_windowBackgroundWhiteGrayIcon, 0.2f,
                        )
                        imageReceiver.setImage(
                            ImageLocation.getForDocument(r.select_animation), "60_60_firstframe",
                            null, null, svgThumb, 0, "tgs", r, 0,
                        )
                    }
                }
            }
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            imageReceiver.onAttachedToWindow()
            emojiDrawable?.addView(this)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            imageReceiver.onDetachedFromWindow()
            emojiDrawable?.removeView(this)
        }

        override fun onDraw(canvas: Canvas) {
            if (isSelected) {
                selectorPaint.color = Theme.getColor(Theme.key_listSelector, resourceProvider)
                val pad = dp(2).toFloat()
                val radius = dp(8).toFloat()
                canvas.drawRoundRect(pad, pad, width - pad, height - pad, radius, radius, selectorPaint)
            }
            val icon = when (cellType) {
                StripItem.TYPE_PLUS -> plusDrawable
                StripItem.TYPE_REMOVE -> removeDrawable
                else -> null
            }
            val size = if (icon != null) dp(STRIP_ICON_DP) else dp(STRIP_REACTION_DP)
            val l = (width - size) / 2
            val t = (height - size) / 2
            val drawable = icon ?: emojiDrawable
            if (drawable != null) {
                drawable.setBounds(l, t, l + size, t + size)
                drawable.draw(canvas)
            } else {
                imageReceiver.setImageCoords(l.toFloat(), t.toFloat(), size.toFloat(), size.toFloat())
                imageReceiver.draw(canvas)
            }
        }
    }

    private inner class StripDragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = true
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val pos = vh.adapterPosition
            if (pos !in pins.indices) return 0
            return makeMovementFlags(
                ItemTouchHelper.START or ItemTouchHelper.END or ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            )
        }

        override fun canDropOver(
            rv: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = target.adapterPosition in pins.indices

        override fun onMove(
            rv: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = source.adapterPosition
            val to = target.adapterPosition
            if (from !in pins.indices || to !in pins.indices) return false
            pins.add(to, pins.removeAt(from))
            selectedIndex = when (selectedIndex) {
                from -> to
                in (from + 1)..to -> selectedIndex - 1
                in to..<from -> selectedIndex + 1
                else -> selectedIndex
            }
            stripAdapter?.items?.let { it.add(to, it.removeAt(from)) }
            stripAdapter?.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            stripDragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG
            if (stripDragging) {
                listView?.requestDisallowInterceptTouchEvent(true)
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            stripDragging = false
            InuConfig.PINNED_REACTIONS.save()
            stripAdapter?.update()
        }
    }

    private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

    companion object {
        private const val STRIP_CELL_DP = 44
        private const val STRIP_ICON_DP = 28
        private const val STRIP_REACTION_DP = 36

        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_COPY_SERVER = InuUtils.generateId()
        private val BUTTON_REMOVE_ALL = InuUtils.generateId()
    }
}
