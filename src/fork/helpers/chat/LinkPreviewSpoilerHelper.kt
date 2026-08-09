package desu.inugram.helpers.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import androidx.core.graphics.ColorUtils
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.LiteMode
import org.telegram.messenger.MessageObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.spoilers.SpoilerEffect
import org.telegram.ui.Components.spoilers.SpoilerEffect2
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object LinkPreviewSpoilerHelper {
    private class State {
        var spoilered = false
        var revealProgress = 0f
        var revealAnimator: ValueAnimator? = null
        var rebinding = false
        var revealX = 0f
        var revealY = 0f
        var revealMaxRadius = 0f
        var effect2: SpoilerEffect2? = null
        var effect2Index: Int? = null
        var fallback: SpoilerEffect? = null
        val cardRect = RectF()
        var cardRadius = 0f
        var blurBitmap: Bitmap? = null
        var blurCanvas: Canvas? = null
        var blurW = 0
        var blurH = 0
        var blurDirty = true
        var blurSrc: Bitmap? = null
    }

    private const val BLUR_SCALE = 0.2f

    private val states = WeakHashMap<ChatMessageCell, State>()
    private fun stateOf(cell: ChatMessageCell) = states.getOrPut(cell) { State() }

    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()

    /** whether the cover is currently painted over the card — nothing stock draws may show through it. */
    @JvmStatic
    fun isCardCovered(cell: ChatMessageCell): Boolean {
        val state = states[cell] ?: return false
        if (!InuConfig.LINK_PREVIEW_SPOILER.value || !state.spoilered) return false
        if (state.revealAnimator != null) return true
        return state.revealProgress < 1f && cell.messageObject?.isSpoilersRevealed != true
    }

    /**
     * media overlays (play button, duration, menu) outlive the cover: they stay suppressed until the
     * reveal is committed, i.e. until the rebind re-decided autoplay. isSpoilersRevealed stands in for
     * that — both reveal paths rebind right after setting it — and the cover animation may well end
     * first, which would flash a play button that autoplay is about to take away.
     */
    @JvmStatic
    fun shouldHideMediaOverlays(cell: ChatMessageCell): Boolean {
        val state = states[cell] ?: return false
        if (!InuConfig.LINK_PREVIEW_SPOILER.value || !state.spoilered) return false
        return state.revealAnimator != null || cell.messageObject?.isSpoilersRevealed != true
    }

    /**
     * whether the message's preview is spoilered at all, regardless of it having been revealed in the
     * chat — list surfaces keep covering it either way, like stock does with hasMediaSpoilers().
     */
    @JvmStatic
    fun hasLinkPreviewSpoiler(msg: MessageObject?): Boolean {
        if (!InuConfig.LINK_PREVIEW_SPOILER.value || msg == null) return false
        return isLinkCoveredBySpoiler(msg)
    }

    /** message-level gate for stock autoplay decisions, which run before the cell state is bound. */
    @JvmStatic
    fun isMediaCovered(msg: MessageObject?): Boolean {
        if (msg == null || msg.isSpoilersRevealed) return false
        return hasLinkPreviewSpoiler(msg)
    }

    @JvmStatic
    fun onMessageContent(cell: ChatMessageCell, hasLinkPreview: Boolean) {
        val state = stateOf(cell)
        if (state.rebinding) return
        val msg = cell.messageObject
        state.spoilered = hasLinkPreview && hasLinkPreviewSpoiler(msg)
        state.revealAnimator?.cancel()
        state.revealAnimator = null
        if (!state.spoilered) {
            state.revealProgress = 0f
            detachEffect2(cell, state)
            state.blurBitmap?.recycle()
            state.blurBitmap = null
            state.blurCanvas = null
            state.blurSrc = null
            return
        }
        state.revealProgress = if (msg!!.isSpoilersRevealed) 1f else 0f
        if (state.revealProgress == 0f) {
            state.blurDirty = true
            if (cell.isAttachedToWindow) ensureEffect2(cell, state)
        } else {
            detachEffect2(cell, state)
        }
    }

    @JvmStatic
    fun onAttached(cell: ChatMessageCell) {
        val state = states[cell] ?: return
        if (!state.spoilered || state.revealProgress >= 1f) return
        ensureEffect2(cell, state)
    }

    @JvmStatic
    fun onDetached(cell: ChatMessageCell) {
        val state = states[cell] ?: return
        val e = state.effect2 ?: return
        state.effect2Index = e.getAttachIndex(cell)
        e.detach(cell)
    }

    @JvmStatic
    fun setCardBounds(cell: ChatMessageCell, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        val state = states[cell] ?: return
        if (!state.spoilered) return
        state.cardRect.set(left, top, right, bottom)
        state.cardRadius = radius
    }

    @JvmStatic
    fun startCardReveal(cell: ChatMessageCell, x: Float, y: Float) {
        val state = states[cell] ?: return
        if (!state.spoilered || state.revealProgress != 0f || state.revealAnimator != null || state.cardRect.isEmpty) return
        state.revealX = x
        state.revealY = y
        state.revealMaxRadius = maxReachFromPoint(state.cardRect, x, y)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = (state.revealMaxRadius * 0.3f).coerceIn(250f, 550f).toLong()
        animator.interpolator = CubicBezierInterpolator.EASE_BOTH
        animator.addUpdateListener {
            state.revealProgress = it.animatedValue as Float
            cell.invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state.revealAnimator !== animation) return
                state.revealAnimator = null
                state.revealProgress = 1f
                detachEffect2(cell, state)
                cell.invalidate()
            }
        })
        state.revealAnimator = animator
        animator.start()
    }

    /**
     * re-runs the bind so stock re-decides autoplay for a message that is no longer covered.
     * only safe once the text spoiler's ripple is done: the rebind rebuilds the text layout blocks,
     * dropping the SpoilerEffect instances the ripple (and its reveal callback) is attached to.
     */
    private fun rebindForReveal(cell: ChatMessageCell, state: State) {
        val msg = cell.messageObject ?: return
        state.rebinding = true
        try {
            msg.forceUpdate = true
            // the pinned/chat flags are read as fields, like stock does when revealing media spoilers:
            // the getters answer for the pending message while a deferred bind is queued up
            cell.setMessageContent(msg, cell.currentMessagesGroup, cell.pinnedBottom, cell.pinnedTop, cell.firstInChat, cell.lastInChatList)
            msg.forceUpdate = false
            // the buttons were left in their pre-autoplay state (play + download) while the card was
            // covered, and the rebind above animates them away over the now-revealed video — snap instead
            cell.updateButtonState(false, false, true)
            cell.animatingDrawVideoImageButton = 0
            cell.animatingDrawVideoImageButtonProgress = if (cell.drawVideoImageButton) 1f else 0f
        } finally {
            state.rebinding = false
        }
    }

    @JvmStatic
    fun onTextSpoilerRevealed(cell: ChatMessageCell) {
        val state = states[cell] ?: return
        if (!state.spoilered) return
        rebindForReveal(cell, state)
    }

    @JvmStatic
    fun revealFromCardTap(cell: ChatMessageCell, x: Float, y: Float): Boolean {
        val state = states[cell] ?: return false
        if (!isCardCovered(cell) || state.revealProgress > 0f || state.revealAnimator != null) return false
        val msg = cell.messageObject ?: return false
        val trigger = findFirstTextSpoiler(cell, msg)
        if (trigger != null) {
            cell.spoilerPressed = trigger
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, x, y, 0)
            event.offsetLocation(0f, y - cell.getEventY(event))
            val handled = cell.checkSpoilersMotionEvent(event, 0)
            event.recycle()
            if (!handled) cell.spoilerPressed = null
        } else {
            msg.isSpoilersRevealed = true
            startCardReveal(cell, x, y)
            rebindForReveal(cell, state)
            cell.invalidate()
        }
        return true
    }

    private fun findFirstTextSpoiler(cell: ChatMessageCell, msg: MessageObject): SpoilerEffect? {
        cell.explanationLayout?.textLayoutBlocks?.forEach { block ->
            if (block.spoilers.isNotEmpty()) return block.spoilers[0]
        }
        val caption = cell.captionLayout
        if (caption != null) {
            caption.textLayoutBlocks?.forEach { block ->
                if (block.spoilers.isNotEmpty()) return block.spoilers[0]
            }
        } else {
            msg.textLayoutBlocks?.forEach { block ->
                if (block.spoilers.isNotEmpty()) return block.spoilers[0]
            }
        }
        return null
    }

    @JvmStatic
    fun drawOverlay(canvas: Canvas, cell: ChatMessageCell) {
        if (cell.inu_capturingLinkPreviewBlur) return
        if (!isCardCovered(cell)) return
        val state = states[cell] ?: return
        val r = state.cardRect
        if (r.isEmpty) return

        val blur = ensureBlur(cell, state, r)

        canvas.save()
        clipPath.rewind()
        clipPath.addRoundRect(r, state.cardRadius, state.cardRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        clipPath.rewind()
        clipPath.addRect(r.left, r.top, r.left + dpf2(3f), r.bottom, Path.Direction.CW)
        canvas.clipPath(clipPath, Region.Op.DIFFERENCE)
        if (state.revealProgress > 0f) {
            clipPath.rewind()
            clipPath.addCircle(state.revealX, state.revealY, state.revealMaxRadius * state.revealProgress, Path.Direction.CW)
            canvas.clipPath(clipPath, Region.Op.DIFFERENCE)
        }

        if (blur != null) {
            canvas.drawBitmap(blur, null, r, bitmapPaint)
        } else {
            backingPaint.color = Theme.getColor(Theme.key_chat_mediaLoaderPhoto, cell.resourcesProvider)
            backingPaint.alpha = 255
            canvas.drawRect(r, backingPaint)
        }

        val mode = InuConfig.MEDIA_SPOILER_MODE.value
        if (mode == InuConfig.MediaSpoilerModeItem.TELEGRAM) {
            drawParticles(canvas, cell, state, r)
        } else {
            val scrim = ColorUtils.blendARGB(getBubbleColor(cell), Color.BLACK, 0.4f)
            SpoilerHelper.drawMediaSpoilerStyle(canvas, mode, r.left, r.top, r.right, r.bottom, 1f, cell.resourcesProvider, true, scrim)
        }
        canvas.restore()
    }

    private fun getBubbleColor(cell: ChatMessageCell): Int {
        val key = if (cell.messageObject?.isOutOwner == true) Theme.key_chat_outBubble else Theme.key_chat_inBubble
        return Theme.getColor(key, cell.resourcesProvider)
    }

    private fun drawParticles(canvas: Canvas, cell: ChatMessageCell, state: State, r: RectF) {
        val effect2 = state.effect2
        if (effect2 != null && !effect2.destroyed) {
            canvas.save()
            canvas.translate(r.left, r.top)
            val w = r.width()
            val h = r.height()
            val scale = max(1f, max(w / effect2.width, h / effect2.height))
            canvas.scale(scale, scale)
            effect2.draw(canvas, cell, (w / scale).toInt(), (h / scale).toInt(), 1f, cell.drawingToBitmap)
            canvas.restore()
            cell.invalidate()
        } else if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_SPOILER)) {
            val eff = state.fallback ?: SpoilerEffect().also { state.fallback = it }
            eff.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (255 * 0.5f).toInt()))
            eff.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            eff.draw(canvas)
        }
    }

    private fun ensureBlur(cell: ChatMessageCell, state: State, r: RectF): Bitmap? {
        val w = r.width().toInt()
        val h = r.height().toInt()
        if (w <= 0 || h <= 0) return null
        val src = cell.photoImage.bitmap
        val cached = state.blurBitmap
        val imageJustLoaded = state.blurSrc == null && src != null
        if (cached != null && !state.blurDirty && state.blurW == w && state.blurH == h && !imageJustLoaded) {
            return cached
        }

        val bw = max(1, (w * BLUR_SCALE).toInt())
        val bh = max(1, (h * BLUR_SCALE).toInt())
        var bmp = state.blurBitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            state.blurBitmap = bmp
            state.blurCanvas = Canvas(bmp)
        }
        val c = state.blurCanvas!!
        bmp.eraseColor(getBubbleColor(cell) or 0xFF000000.toInt())
        c.save()
        c.scale(bw / w.toFloat(), bh / h.toFloat())
        c.translate(-r.left, -r.top)
        cell.inu_capturingLinkPreviewBlur = true
        try {
            cell.drawLinkPreview(c, 1f)
        } finally {
            cell.inu_capturingLinkPreviewBlur = false
        }
        c.restore()
        Utilities.stackBlurBitmap(bmp, max(4, min(bw, bh) / 6))

        state.blurW = w
        state.blurH = h
        state.blurSrc = src
        state.blurDirty = false
        return bmp
    }

    private fun ensureEffect2(cell: ChatMessageCell, state: State) {
        if (!SpoilerEffect2.supports()) return
        val e = state.effect2
        if (e != null && !e.destroyed) {
            e.attach(cell)
            return
        }
        val created = SpoilerEffect2.getInstance(cell) ?: return
        state.effect2 = created
        state.effect2Index?.let { created.reassignAttach(cell, it) }
    }

    private fun detachEffect2(cell: ChatMessageCell, state: State) {
        val e = state.effect2 ?: return
        e.detach(cell)
        state.effect2 = null
        state.effect2Index = null
    }

    private fun maxReachFromPoint(r: RectF, x: Float, y: Float): Float {
        val dx = max(abs(x - r.left), abs(x - r.right))
        val dy = max(abs(y - r.top), abs(y - r.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    private fun isLinkCoveredBySpoiler(msg: MessageObject): Boolean {
        val media = MessageObject.getMedia(msg.messageOwner)
        val webpage = media?.webpage as? TLRPC.TL_webPage ?: return false
        val entities = msg.messageOwner?.entities ?: return false
        val text = msg.messageOwner?.message ?: return false
        val spoilers = entities.filterIsInstance<TLRPC.TL_messageEntitySpoiler>()
        if (spoilers.isEmpty()) return false
        val targets = setOfNotNull(normalizeUrl(webpage.url), normalizeUrl(webpage.display_url))

        var matchedTarget = false
        var anyUrl = false
        var allCovered = true
        for (e in entities) {
            val url = getEntityUrl(e, text) ?: continue
            anyUrl = true
            val covered = isEntityCovered(e, spoilers)
            if (normalizeUrl(url) in targets) {
                matchedTarget = true
                if (covered) return true
            }
            if (!covered) allCovered = false
        }
        // the preview is often generated from a url that isn't the one in the text (web preview
        // replacements rewrite it, and media/redirect canonicalization changes it server-side),
        // so when nothing matches it, fall back to every link in the message being spoilered
        return !matchedTarget && anyUrl && allCovered
    }

    private fun getEntityUrl(entity: TLRPC.MessageEntity, text: String): String? = when (entity) {
        is TLRPC.TL_messageEntityTextUrl -> entity.url
        is TLRPC.TL_messageEntityUrl -> {
            val end = entity.offset + entity.length
            if (entity.offset < 0 || end > text.length) null else text.substring(entity.offset, end)
        }
        else -> null
    }

    private fun isEntityCovered(entity: TLRPC.MessageEntity, spoilers: List<TLRPC.TL_messageEntitySpoiler>): Boolean {
        val end = entity.offset + entity.length
        return spoilers.any { entity.offset >= it.offset && end <= it.offset + it.length }
    }

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        var u = url.trim().lowercase()
        u = u.removePrefix("https://").removePrefix("http://")
        u = u.removePrefix("www.")
        u = u.removeSuffix("/")
        return u.ifEmpty { null }
    }
}
