package desu.inugram.helpers.plugins.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
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
 * Remote urls are unsupported: loading one would leak the user's IP to a manifest-chosen host.
 * - `inu://{name}`: `inu.icons.common`
 * - `tg://emoji?id={documentId}`: a custom emoji
 * - `tg://addstickers?set={slug}`: `&idx={n}` zero-based, `&id={documentId}`, or the set's preview
 * TODO: show a warning when `@icon` cannot be resolved.
 */
object PluginManifestIcons {
    private const val FILTER = "56_56"

    fun createPlaceholder(context: Context): Drawable? =
        ResourcesCompat.getDrawable(context.resources, R.drawable.inu_tabler_code, null)?.mutate()?.apply {
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN)
        }

    /** true when showing one of our flat glyphs tinted with [commonTint], so the caller may badge it */
    fun bindIcon(
        view: BackupImageView,
        spec: String?,
        placeholder: Drawable?,
        commonTint: Int = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
    ): Boolean {
        view.tag = spec
        view.setAnimatedEmojiDrawable(null)
        // a round-radius bitmap draws through the receiver's paint, which nulls a filter set on the drawable
        view.setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN))
        // an unresolvable sticker slug answers with nothing rather than a failure, so show the placeholder up front
        view.setImageDrawable(placeholder)
        if (spec.isNullOrBlank()) return false
        when {
            spec.startsWith("inu://") -> return bindCommon(view, spec.removePrefix("inu://"), commonTint)
            spec.startsWith("tg://emoji?") -> bindEmoji(view, Uri.parse(spec))
            spec.startsWith("tg://addstickers?") -> bindSticker(view, spec, Uri.parse(spec))
        }
        return false
    }

    private fun bindCommon(view: BackupImageView, name: String, tint: Int): Boolean {
        val drawable = PluginIcons.loadCommonDrawable(view.context, name)?.mutate() ?: return false
        view.setColorFilter(PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN))
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
