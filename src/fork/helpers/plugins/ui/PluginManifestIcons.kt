package desu.inugram.helpers.plugins.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import desu.inugram.core.plugins.CommonIcons
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BackupImageView

/**
 * Loads manifest icons into [BackupImageView]. Remote URLs are unsupported because loading
 * them would expose the user's IP to a manifest-selected host whenever the list renders.
 * Supported forms resolve locally or through Telegram:
 *
 * - `inu://{name}`: a local icon from `inu.icons.common`.
 * - `tg://emoji?id={documentId}`: a custom emoji, resolved as in messages.
 * - `tg://addstickers?set={slug}`: a sticker; `&idx={n}` selects a zero-based index,
 *   `&id={documentId}` selects an ID, and neither selects the set's preview sticker.
 *
 * Other values use [placeholder].
 * TODO: show a warning when `@icon` cannot be resolved.
 */
object PluginManifestIcons {
    private const val FILTER = "56_56"

    /** what every plugin surface draws when the manifest names no icon, or names one we can't resolve */
    fun createPlaceholder(context: Context): Drawable? =
        ResourcesCompat.getDrawable(context.resources, R.drawable.inu_tabler_code, null)?.mutate()?.apply {
            colorFilter = tintOf(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon))
        }

    /**
     * True when the view ended up showing one of *our* glyphs, which is flat and takes [commonTint]
     * - the caller is then free to put it on a badge. Everything else (an emoji, a sticker, the
     * placeholder) brings its own colours and answers false.
     */
    fun bindIcon(
        view: BackupImageView,
        spec: String?,
        placeholder: Drawable?,
        commonTint: Int = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
    ): Boolean {
        view.tag = spec
        view.setAnimatedEmojiDrawable(null)
        // the tint goes on the receiver rather than on the drawable: under a round radius a bitmap
        // is drawn through the receiver's own paint, which is handed the receiver's filter and
        // *nulled* when there is none - so a filter set on the drawable is thrown away
        view.setColorFilter(tintOf(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon)))
        // up front, not only on the `else` below: a sticker set is fetched, and a slug nobody can
        // resolve answers with nothing at all rather than with a failure - so what a spec starts is
        // never proof that it will finish
        view.setImageDrawable(placeholder)
        if (spec.isNullOrBlank()) return false
        var common = false
        val bound = when {
            spec.startsWith("inu://") -> bindCommon(view, spec.removePrefix("inu://"), commonTint).also { common = it }
            spec.startsWith("tg://emoji?") -> bindEmoji(view, Uri.parse(spec))
            spec.startsWith("tg://addstickers?") -> bindSticker(view, spec, Uri.parse(spec))
            else -> false
        }
        return common
    }

    internal fun tintOf(color: Int) = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

    private fun bindCommon(view: BackupImageView, name: String, tint: Int): Boolean {
        val resource = CommonIcons.resolve(name) ?: return false
        val drawable = PluginIcons.drawableOf(view.context, resource)?.mutate() ?: return false
        view.setColorFilter(tintOf(tint))
        view.setImageDrawable(drawable)
        return true
    }

    private fun bindEmoji(view: BackupImageView, uri: Uri): Boolean {
        val documentId = uri.getQueryParameter("id")?.toLongOrNull() ?: return false
        view.setColorFilter(null)
        view.setAnimatedEmojiDrawable(
            AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, documentId),
        )
        return true
    }

    private fun bindSticker(view: BackupImageView, spec: String, uri: Uri): Boolean {
        val slug = uri.getQueryParameter("set")?.takeIf { it.isNotBlank() } ?: return false
        view.setColorFilter(null)
        val documentId = uri.getQueryParameter("id")?.toLongOrNull()
        val index = uri.getQueryParameter("idx")?.toIntOrNull()
        val input = TLRPC.TL_inputStickerSetShortName().apply { short_name = slug }
        val set = MediaDataController.getInstance(UserConfig.selectedAccount)
            .getStickerSet(input, null, false) { fetched ->
                if (view.tag == spec && fetched != null) showSticker(view, fetched, documentId, index)
            }
        if (set != null) showSticker(view, set, documentId, index)
        return true
    }

    private fun showSticker(view: BackupImageView, set: TLRPC.TL_messages_stickerSet, documentId: Long?, index: Int?) {
        val documents = set.documents ?: return
        val sticker = when {
            documentId != null -> documents.find { it?.id == documentId }
            index != null -> documents.getOrNull(index)
            else -> documents.find { it?.id == set.set?.thumb_document_id } ?: documents.firstOrNull()
        } ?: return
        val svgThumb = DocumentObject.getSvgThumb(sticker, Theme.key_windowBackgroundGray, 1.0f)
        val thumb = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
        val location = ImageLocation.getForDocument(thumb, sticker)
        if (MessageObject.isAnimatedStickerDocument(sticker, true) || MessageObject.isVideoSticker(sticker)) {
            view.setImage(ImageLocation.getForDocument(sticker), FILTER, location, null, 0, set)
            if (MessageObject.isTextColorEmoji(sticker)) {
                view.setColorFilter(Theme.getAnimatedEmojiColorFilter(null))
            }
        } else if (location != null && location.imageType == FileLoader.IMAGE_TYPE_LOTTIE) {
            view.setImage(location, FILTER, "tgs", svgThumb, set)
        } else {
            view.setImage(location, FILTER, "webp", svgThumb, set)
        }
    }
}
