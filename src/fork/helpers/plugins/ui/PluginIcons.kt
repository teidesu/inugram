package desu.inugram.helpers.plugins.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import desu.inugram.core.plugins.IconSpec
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SvgHelper

/**
 * Kotlin side of `inu.icons`/`inu.android.resourceIcon` (rust: `icons.rs`). Native owns every
 * decision; this only answers whether something resolves and turns a spec into a [Drawable].
 *
 * Threading: [iconResolves] arrives on [org.telegram.messenger.Utilities.globalQueue] from the
 * call that mints the icon, and touches no view - a drawable name is a resource-table lookup and
 * an svg is parsed into a bitmap, neither of which needs an `Activity`. [resolveDrawable] is the
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

    private val resourceIds = lru<String, Int>(RESOURCE_CACHE_SIZE)
    private val svgMasks = lru<String, Bitmap>(SVG_CACHE_SIZE)

    fun iconResolves(kind: Int, value: String): Boolean = when (kind) {
        KIND_RESOURCE -> resourceIdOf(value) != 0
        KIND_SVG -> maskOf(value) != null
        else -> false
    }

    fun resolveDrawable(context: Context, spec: String?): Drawable? {
        if (spec.isNullOrEmpty()) return null
        val payload = spec.substring(1)
        return when (spec[0]) {
            'r' -> drawableOf(context, payload)
            's' -> maskOf(payload)?.let { BitmapDrawable(context.resources, it) }
            else -> null
        }
    }

    private fun drawableOf(context: Context, name: String): Drawable? {
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
