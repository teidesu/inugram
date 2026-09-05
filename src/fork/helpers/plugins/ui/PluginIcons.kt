package desu.inugram.helpers.plugins.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.view.View
import desu.inugram.core.plugins.IconSpec
import desu.inugram.helpers.plugins.QuickJs
import desu.inugram.helpers.plugins.platform.PluginJvm
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.SvgHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.AnimatedEmojiSpan
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.RLottieImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Kotlin side of `inu.icons`/`inu.android.resourceIcon` (rust: `icons.rs`). Native owns every
 * decision; this only answers whether something resolves and turns a spec into a [Drawable].
 *
 * Threading: [iconResolves] arrives on [org.telegram.messenger.Utilities.globalQueue] from the
 * call that mints the icon, and touches no view - a drawable name is a resource-table lookup and
 * an svg is parsed into a bitmap, neither of which needs an `Activity`. [setIcon] is the
 * ui-thread half, called while a row binds, so an icon is resolved against whichever activity is
 * showing it: a configuration change, a theme change or an icon-pack swap re-resolves the same
 * spec through the new resources with nothing to invalidate.
 */
object PluginIcons {
    /** stock's own menu/settings drawables are 24dp */
    private const val ICON_DP = 24f

    private const val RESOURCE_CACHE_SIZE = 128
    private const val SVG_CACHE_SIZE = 16

    private const val KIND_RESOURCE = 0
    private const val KIND_SVG = 1
    private const val KIND_RAW_ANIMATION = 2

    private val resourceIds = lru<String, Int>(RESOURCE_CACHE_SIZE)
    private val rawResourceIds = lru<String, Int>(RESOURCE_CACHE_SIZE)
    private val svgMasks = lru<String, Bitmap>(SVG_CACHE_SIZE)
    private val boundSpecs = WeakHashMap<RLottieImageView, String>()
    private val emojiBindings = WeakHashMap<RLottieImageView, WeakReference<EmojiBinding>>()

    internal data class AnimationSpec(val value: String, val repeatCount: Int?, val isStatic: Boolean)

    private class EmojiBinding(
        private val view: RLottieImageView,
        private val drawable: AnimatedEmojiDrawable,
    ) : AnimatedEmojiSpan.InvalidateHolder, View.OnAttachStateChangeListener {
        private val wrapped = AnimatedEmojiDrawable.WrapSizeDrawable(
            drawable,
            AndroidUtilities.dp(ICON_DP),
            AndroidUtilities.dp(ICON_DP),
        )

        fun attach() {
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) drawable.addView(this)
            invalidate()
        }

        override fun invalidate() {
            if (emojiBindings[view]?.get() !== this) return
            val receiver = drawable.imageReceiver
            val lottie = receiver?.lottieAnimation
            val webm = receiver?.animation
            val animation = lottie ?: webm
            val mediaReady = if (animation == null) {
                receiver?.hasImageLoaded() == true
            } else {
                lottie?.hasBitmap() == true || webm?.hasBitmap() == true
            }
            if (mediaReady && view.drawable !== wrapped) view.setImageDrawable(wrapped)
            view.invalidate()
        }

        override fun onViewAttachedToWindow(view: View) {
            drawable.addView(this)
            invalidate()
        }

        override fun onViewDetachedFromWindow(view: View) {
            drawable.removeView(this)
        }

        fun dispose() {
            view.removeOnAttachStateChangeListener(this)
            drawable.removeView(this)
        }
    }

    fun iconResolves(kind: Int, value: String): Boolean = when (kind) {
        KIND_RESOURCE -> resourceIdOf(value) != 0
        KIND_SVG -> maskOf(value) != null
        KIND_RAW_ANIMATION -> getRawAnimationResourceId(value) != 0
        else -> false
    }

    internal fun resolveImmediateDrawable(context: Context, spec: String?, engine: QuickJs): Drawable? {
        if (spec.isNullOrEmpty()) return null
        val payload = spec.substring(1)
        return when (spec[0]) {
            'r' -> drawableOf(context, payload)
            's' -> maskOf(payload)?.let { BitmapDrawable(context.resources, it) }
            'j' -> payload.toLongOrNull()?.let { PluginJvm.objectAt(engine, it) as? Drawable }
            else -> null
        }
    }

    fun setIcon(view: RLottieImageView, spec: String?, engine: QuickJs, sizeDp: Float = ICON_DP): Boolean {
        clearIcon(view)
        if (spec.isNullOrEmpty()) return false
        boundSpecs[view] = spec
        val animation = if (spec[0] in "aet") parseAnimationSpec(spec) ?: return false else null
        if (spec[0] in "rsj") {
            view.setImageDrawable(resolveImmediateDrawable(view.context, spec, engine) ?: return false)
            return true
        }
        if (animation == null) return false
        if (spec[0] == 'a') {
            val id = getRawAnimationResourceId(animation.value)
            if (id == 0) return false
            val lottie = RLottieDrawable(
                id,
                animation.value,
                AndroidUtilities.dp(sizeDp),
                AndroidUtilities.dp(sizeDp),
                true,
                null,
            ).apply {
                setAllowDecodeSingleFrame(true)
                setAutoRepeat(if (animation.repeatCount == 0) 0 else 1)
                setAutoRepeatCount(animation.repeatCount ?: -1)
            }
            if (animation.isStatic) {
                view.setImageDrawable(lottie)
            } else {
                view.setAutoRepeat(animation.repeatCount == null)
                view.setAnimation(lottie)
                view.playAnimation()
            }
            return true
        }

        val weakView = WeakReference(view)
        if (spec[0] == 'e') {
            val documentId = animation.value.toLongOrNull()?.takeIf { it > 0 } ?: return false
            AnimatedEmojiDrawable.getDocumentFetcher(UserConfig.selectedAccount).fetchDocument(documentId) { document ->
                val target = weakView.get() ?: return@fetchDocument
                if (document != null && boundSpecs[target] == spec) bindDocument(target, spec, document, animation)
            }
            return true
        }

        val (selector, slug) = animation.value.split('\n', limit = 2).takeIf { it.size == 2 } ?: return false
        val input = TLRPC.TL_inputStickerSetShortName().apply { short_name = slug }
        MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSet(input, null, false) { set ->
            val target = weakView.get() ?: return@getStickerSet
            val document = set?.let { findSticker(it, selector) }
            if (document != null && boundSpecs[target] == spec) bindDocument(target, spec, document, animation)
        }
        return true
    }

    fun clearIcon(view: RLottieImageView) {
        boundSpecs.remove(view)
        emojiBindings.remove(view)?.get()?.dispose()
        view.stopAnimation()
        view.clearAnimationDrawable()
    }

    fun setIcon(
        cell: ActionBarMenuSubItem,
        text: CharSequence,
        spec: String?,
        engine: QuickJs,
        fallbackIcon: Int,
    ) {
        cell.setTextAndIcon(text, 0, ColorDrawable(Color.TRANSPARENT))
        if (!setIcon(cell.imageView, spec, engine)) cell.setTextAndIcon(text, fallbackIcon)
    }

    fun addMenuItem(
        options: ItemOptions,
        text: CharSequence,
        spec: String?,
        engine: QuickJs,
        fallbackIcon: Int,
        onClick: Runnable,
    ) {
        options.add(fallbackIcon, text, onClick)
        val cell = options.last ?: return
        setIcon(cell, text, spec, engine, fallbackIcon)
    }

    private fun bindDocument(
        view: RLottieImageView,
        spec: String,
        document: TLRPC.Document,
        animation: AnimationSpec,
    ) {
        if (boundSpecs[view] != spec) return
        emojiBindings.remove(view)?.get()?.dispose()
        val drawable = AnimatedEmojiDrawable(
            if (animation.isStatic) {
                AnimatedEmojiDrawable.STANDARD_LOTTIE_FRAME
            } else {
                AnimatedEmojiDrawable.CACHE_TYPE_AVATAR_CONSTRUCTOR_PREVIEW
            },
            UserConfig.selectedAccount,
            document,
        )
        if (!animation.isStatic) {
            drawable.imageReceiver.apply {
                setAutoRepeat(if (animation.repeatCount == 0) 0 else 1)
                setAutoRepeatCount(
                    if (document.mime_type == "video/webm") animation.repeatCount?.plus(1) ?: 0
                    else animation.repeatCount ?: -1,
                )
            }
        }
        EmojiBinding(view, drawable).also {
            emojiBindings[view] = WeakReference(it)
            it.attach()
        }
    }

    internal fun parseAnimationSpec(spec: String): AnimationSpec? {
        if (spec.isEmpty() || spec[0] !in "aet") return null
        val mode = spec.getOrNull(1) ?: return null
        return when (mode) {
            '0' -> AnimationSpec(spec.substring(2), 0, false)
            '1' -> AnimationSpec(spec.substring(2), null, false)
            's' -> AnimationSpec(spec.substring(2), 0, true)
            'n' -> {
                val delimiter = spec.indexOf(':', 2).takeIf { it > 2 } ?: return null
                val count = spec.substring(2, delimiter).toIntOrNull()
                    ?.takeIf { it in 1..UShort.MAX_VALUE.toInt() }
                    ?: return null
                AnimationSpec(spec.substring(delimiter + 1), count, false)
            }
            else -> null
        }
    }

    fun drawableOf(context: Context, name: String): Drawable? {
        val id = resourceIdOf(name)
        if (id == 0) return null
        // through the context's own Resources rather than Context.getDrawable, which resolves against the base ContextImpl and walks past LaunchActivity's IconsResources override
        return try {
            context.resources.getDrawable(id, context.theme)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    private fun resourceIdOf(name: String): Int {
        if (!IconSpec.isResourceName(name)) return 0
        val context = ApplicationLoader.applicationContext ?: return 0
        return synchronized(resourceIds) {
            resourceIds.getOrPut(name) {
                context.resources.getIdentifier(name, "drawable", context.packageName)
            }
        }
    }

    fun getRawAnimationResourceId(name: String): Int {
        if (!IconSpec.isResourceName(name)) return 0
        val context = ApplicationLoader.applicationContext ?: return 0
        return synchronized(rawResourceIds) {
            rawResourceIds.getOrPut(name) {
                context.resources.getIdentifier(name, "raw", context.packageName)
            }
        }
    }

    private fun findSticker(set: TLRPC.TL_messages_stickerSet, selector: String): TLRPC.Document? = when (selector.firstOrNull()) {
        'i' -> selector.substring(1).toIntOrNull()?.let { set.documents.getOrNull(it) }
        'd' -> selector.substring(1).toLongOrNull()?.let { id -> set.documents.find { it.id == id } }
        'e' -> runCatching {
            String(Base64.decode(selector.substring(1), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull()?.let { emoji ->
            val ids = set.packs.firstOrNull { it.emoticon == emoji }?.documents ?: return@let null
            set.documents.firstOrNull { it.id in ids }
        }
        else -> null
    }

    /**
     * The rasterized icon, tint-ready: every paint is forced opaque white, so a `SRC_IN` colour
     * filter (which is how every cell tints its icon) reproduces it in the row's own colour.
     *
     * The bitmap is bounded by [ICON_DP] whatever the source declares - `SvgHelper` scales the
     * document down to the size it was asked for - and null means the platform's xml reader could
     * not make anything of it.
     */
    private fun maskOf(source: String): Bitmap? = synchronized(svgMasks) {
        if (!IconSpec.isSvgSource(source)) return@synchronized null
        svgMasks[source] ?: run {
            val size = AndroidUtilities.dp(ICON_DP)
            val bitmap = SvgHelper.getBitmap(source, size, size, true) ?: return@run null
            svgMasks[source] = bitmap
            bitmap
        }
    }

    private fun <K, V> lru(capacity: Int): LinkedHashMap<K, V> =
        object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
        }
}
