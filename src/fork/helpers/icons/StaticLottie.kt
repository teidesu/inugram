package desu.inugram.helpers.icons

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.WorkerThread
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import kotlin.math.min
import kotlin.math.roundToInt

class StaticLottie private constructor(
    private val frames: IntArray,
    private val drawables: Array<Drawable>,
    val fill: Boolean,
    private val scale: Boolean,
) {
    private val canvas = Canvas()

    @WorkerThread
    fun render(bitmap: Bitmap, frame: Int) {
        bitmap.eraseColor(Color.TRANSPARENT)
        canvas.setBitmap(bitmap)
        val next = frames.indexOfFirst { it > frame }
        val from = if (next > 0) drawables[next - 1] else null
        val to = drawables[if (next < 0) drawables.size - 1 else next]
        if (from == null || from === to) {
            draw(to, 1f, 1f)
        } else {
            val progress = INTERPOLATOR.getInterpolation((frame - frames[next - 1]).toFloat() / (frames[next] - frames[next - 1]))
            draw(from, 1f - progress, if (scale) AndroidUtilities.lerp(1f, MIN_SCALE, progress) else 1f)
            draw(to, progress, if (scale) AndroidUtilities.lerp(MIN_SCALE, 1f, progress) else 1f)
        }
        canvas.setBitmap(null)
    }

    private fun draw(drawable: Drawable, alpha: Float, scale: Float) {
        val width = canvas.width
        val height = canvas.height
        if (fill) {
            drawable.setBounds(0, 0, width, height)
        } else {
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
            val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
            val fit = min(1f, min(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight))
            val drawWidth = (intrinsicWidth * fit).roundToInt()
            val drawHeight = (intrinsicHeight * fit).roundToInt()
            val left = (width - drawWidth) / 2
            val top = (height - drawHeight) / 2
            drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
        }
        drawable.alpha = (alpha * 255).roundToInt()
        val save = canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    private class Compat(val icons: List<Pair<Float, String>>, val triggers: Collection<String> = icons.map { it.second })

    companion object {
        private const val MIN_SCALE = 0.1f
        private val INTERPOLATOR = AccelerateDecelerateInterpolator()
        private val MAIN_TAB_ICONS = listOf(
            "tabs_chats_24", "tabs_chats_active_24", "tabs_contacts_24", "tabs_contact_active_24", "tabs_calls_24", "tabs_calls_active_24",
        )

        private fun swap(from: String, to: String) = Compat(listOf(0f to from, 1f to to))

        private fun swapTab(from: String, to: String) = Compat(listOf(0f to from, 1f to to), MAIN_TAB_ICONS)

        // stock drawables that legacy packs replace in place of these animations, keyed by animation progress.
        // voice_and_video plays 0..30 and 30..60 and stops on either side of the midpoint; its fades are
        // shortened to the 12 frames the other input transitions take
        private val COMPAT = mapOf(
            "tab_chats" to swapTab("tabs_chats_24", "tabs_chats_active_24"),
            "tab_contacts" to swapTab("tabs_contacts_24", "tabs_contact_active_24"),
            "tab_calls" to swapTab("tabs_calls_24", "tabs_calls_active_24"),
            "tab_settings" to swapTab("outline_profile_settings", "filled_profile_settings"),
            "smile_to_sticker" to swap("input_smile", "msg_sticker"),
            "sticker_to_smile" to swap("msg_sticker", "input_smile"),
            "smile_to_keyboard" to swap("input_smile", "input_keyboard"),
            "keyboard_to_smile" to swap("input_keyboard", "input_smile"),
            "smile_to_gif" to swap("input_smile", "msg_gif"),
            "gif_to_smile" to swap("msg_gif", "input_smile"),
            "sticker_to_keyboard" to swap("msg_sticker", "input_keyboard"),
            "keyboard_to_sticker" to swap("input_keyboard", "msg_sticker"),
            "gif_to_keyboard" to swap("msg_gif", "input_keyboard"),
            "keyboard_to_gif" to swap("input_keyboard", "msg_gif"),
            "voice_and_video" to Compat(listOf(0f to "input_video_pressed", 0.2f to "input_mic_pressed", 0.51f to "input_mic_pressed", 0.71f to "input_video_pressed")),
            "inu_voice_to_video" to swap("input_mic", "input_video"),
            "inu_video_to_voice" to swap("input_video", "input_mic"),
        ) + listOf("smiles", "cat", "food", "activities", "travel", "objects", "other", "flags")
            .associate { "msg_emoji_$it" to Compat(listOf(0f to "msg_emoji_$it")) }

        @JvmStatic
        fun create(rawRes: Int, frameCount: Int): StaticLottie? {
            val packs = IconPackHelper.getActivePacks()
            if (packs.isEmpty()) return null
            val resources = ApplicationLoader.applicationContext.resources
            val name = try {
                resources.getResourceEntryName(rawRes)
            } catch (_: Resources.NotFoundException) {
                return null
            }
            val scale = !name.startsWith("tab_")

            val frames = packs.firstNotNullOfOrNull { pack -> pack.getLottieFrames(name)?.let { pack to it } }
            if (frames != null) {
                val (pack, entries) = frames
                val cache = HashMap<String, Drawable?>()
                val loaded = entries.mapNotNull { (frame, path) ->
                    cache.getOrPut(path) { pack.loadLottieFrame(resources, path) }?.let { frame to it }
                }
                if (loaded.isEmpty()) return null
                return StaticLottie(loaded.map { it.first }.toIntArray(), loaded.map { it.second }.toTypedArray(), fill = true, scale)
            }

            val compat = COMPAT[name] ?: return null
            if (compat.triggers.none { icon -> packs.any { icon in it.iconNames } }) return null
            val cache = HashMap<String, Drawable>()
            val drawables = compat.icons.map { (_, icon) ->
                cache.getOrPut(icon) {
                    val id = resources.getIdentifier(icon, "drawable", ApplicationLoader.applicationContext.packageName)
                    packs.firstNotNullOfOrNull { it.loadDrawable(resources, icon, id) }
                        ?: (if (id != 0) resources.getDrawable(id, null)?.mutate() else null)
                        ?: return null
                }
            }
            val thresholds = compat.icons.map { (progress, _) -> (progress * (frameCount - 1)).roundToInt() }
            return StaticLottie(thresholds.toIntArray(), drawables.toTypedArray(), fill = false, scale)
        }
    }
}
