package desu.inugram.helpers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import desu.inugram.InuConfig
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.ProfileGalleryBlurView
import org.telegram.ui.Components.ProfileGalleryView
import org.telegram.ui.Stories.StoriesController

object ProfileHelper {
    const val ACTION_TOGGLE_HIDE_WALLPAPER = 505
    const val ACTION_TOGGLE_HIDE_THEME = 506
    const val ACTION_DEBUG_CLEAR_CACHE = 599

    private const val GRADIENT_FADE_DARK = 0x80000000.toInt()
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionsBackdropPaint = Paint()
    private var cachedRampGradient: LinearGradient? = null
    private var cachedRampHeight = 0f

    @JvmStatic
    fun useProfilePhotoGradientFade(): Boolean = InuConfig.PROFILE_PHOTO_GRADIENT_FADE.value

    @JvmStatic
    fun reduceMotion(): Boolean = InuConfig.REDUCE_PROFILE_MOTION.value

    @JvmStatic
    fun preferMediaTab(): Boolean = InuConfig.PROFILE_PREFER_MEDIA_TAB.value

    @JvmStatic
    fun applyReduceMotionAlpha(openAnimationInProgress: Boolean, diff: Float, vararg views: View?) {
        if (!reduceMotion() || openAnimationInProgress) return
        val fade = diff.coerceIn(0f, 1f)
        for (v in views) v?.alpha = fade
    }

    @JvmStatic
    fun notifyBlurExpandProgress(pager: ProfileGalleryView?, value: Float) {
        val b = pager?.blurDrawer ?: return
        b.inu_expandProgress = value
        b.invalidate()
    }

    @JvmStatic
    fun effectiveChipExpand(playProfileAnimation: Int, avatarAnimationProgress: Float, currentExpandAnimatorValue: Float): Float = when {
        playProfileAnimation == 2 -> 1f
        avatarAnimationProgress >= 1f || playProfileAnimation == 0 -> currentExpandAnimatorValue.coerceIn(0f, 1f)
        else -> 0f
    }

    @JvmStatic
    fun expandedActionsOffset(playProfileAnimation: Int, avatarAnimationProgress: Float, currentExpandAnimatorValue: Float): Float {
        if (!useProfilePhotoGradientFade()) return 0f
        return AndroidUtilities.dpf2(8f) * effectiveChipExpand(playProfileAnimation, avatarAnimationProgress, currentExpandAnimatorValue)
    }

    @JvmStatic
    fun adjustChipColor(btnColor: Int, whiteColor: Int, expandProgress: Float): Int =
        if (useProfilePhotoGradientFade() && expandProgress > 0f) {
            ColorUtils.blendARGB(btnColor, whiteColor, expandProgress)
        } else btnColor

    @JvmStatic
    fun blendChipBackgroundForExpand(backgroundColor: Int, whiteColor: Int, expandProgress: Float): Int =
        if (useProfilePhotoGradientFade() && expandProgress > 0f) {
            ColorUtils.blendARGB(backgroundColor, whiteColor, expandProgress)
        } else backgroundColor

    @JvmStatic
    fun forceChipShadowForExpand(expandProgress: Float): Boolean =
        useProfilePhotoGradientFade() && expandProgress > 0f

    @JvmStatic
    fun drawProfilePhotoGradientFade(
        canvas: Canvas,
        blurView: ProfileGalleryBlurView,
        width: Float,
        translate: Boolean,
        fraction: Float,
        alpha: Float,
    ): Boolean {
        if (!useProfilePhotoGradientFade()) return false
        val visibility = (1f - fraction).coerceIn(0f, 1f) * alpha.coerceIn(0f, 1f)
        if (visibility <= 0f || width <= 0f) return true

        val openingScale = if (blurView.measuredWidth > 0) width / blurView.measuredWidth else 1f
        val sizePx = blurView.size * openingScale
        val actionPx = blurView.actionSize * openingScale
        val scaledSize = sizePx * (1f - fraction).coerceIn(0f, 1f)

        val photoTop = if (translate) -scaledSize else 0f
        val photoBottom = if (translate) 0f else sizePx
        val actionBottom = if (translate) 0f else sizePx + actionPx

        if (photoBottom > photoTop) {
            val rampEnd = AndroidUtilities.dpf2(56f).coerceAtMost(photoBottom - photoTop)
            fadePaint.shader = getRampGradient(rampEnd)
            fadePaint.alpha = (visibility * 255f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(0f, photoTop)
            canvas.drawRect(0f, 0f, width, rampEnd, fadePaint)
            canvas.restore()
            fadePaint.shader = null

            if (photoTop + rampEnd < photoBottom) {
                fadePaint.color = GRADIENT_FADE_DARK
                fadePaint.alpha = ((GRADIENT_FADE_DARK ushr 24) * visibility).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, photoTop + rampEnd, width, photoBottom, fadePaint)
            }
        }

        if (actionBottom > photoBottom) {
            actionsBackdropPaint.color = Theme.getColor(Theme.key_windowBackgroundGray)
            actionsBackdropPaint.alpha = (visibility * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, photoBottom, width, actionBottom, actionsBackdropPaint)
        }

        return true
    }

    private fun getRampGradient(rampHeight: Float): LinearGradient {
        cachedRampGradient?.let { if (cachedRampHeight == rampHeight) return it }
        return LinearGradient(
            0f, 0f, 0f, rampHeight,
            Color.TRANSPARENT, GRADIENT_FADE_DARK,
            Shader.TileMode.CLAMP,
        ).also {
            cachedRampGradient = it
            cachedRampHeight = rampHeight
        }
    }

    @JvmStatic
    fun shouldShowIdRow(): Boolean {
        return InuConfig.PROFILE_ID_MODE.value != InuConfig.ProfileIdModeItem.OFF
    }

    private fun getRawDialogWallpaper(currentAccount: Int, dialogId: Long): TLRPC.WallPaper? {
        val controller = MessagesController.getInstance(currentAccount)
        return if (dialogId >= 0) controller.getUserFull(dialogId)?.wallpaper
        else controller.getChatFull(-dialogId)?.wallpaper
    }

    private fun hasRawDialogTheme(currentAccount: Int, dialogId: Long): Boolean {
        val controller = MessagesController.getInstance(currentAccount)
        val emoticon = if (dialogId >= 0) controller.getUserFull(dialogId)?.theme_emoticon
        else controller.getChatFull(-dialogId)?.theme_emoticon
        return !emoticon.isNullOrEmpty()
    }

    @JvmStatic
    fun addMenuItems(otherItem: ActionBarMenuItem?, currentAccount: Int, dialogId: Long, resourcesProvider: Theme.ResourcesProvider?) {
        if (otherItem == null) return
        if (!InuConfig.DISABLE_CHAT_BACKGROUNDS.value && getRawDialogWallpaper(currentAccount, dialogId) != null) {
            val hidden = ChatHelper.isRemoveWallpaperSetForDialog(currentAccount, dialogId)
            val label = if (hidden) R.string.InuShowCustomWallpaper else R.string.InuHideCustomWallpaper
            otherItem.addSubItem(
                ACTION_TOGGLE_HIDE_WALLPAPER,
                R.drawable.menu_feature_wallpaper,
                LocaleController.getString(label),
            )
        }
        if (!InuConfig.DISABLE_CHAT_THEMES.value && hasRawDialogTheme(currentAccount, dialogId)) {
            val hidden = ChatHelper.isRemoveThemeSetForDialog(currentAccount, dialogId)
            val label = if (hidden) R.string.InuShowCustomTheme else R.string.InuHideCustomTheme
            otherItem.addSubItem(
                ACTION_TOGGLE_HIDE_THEME,
                R.drawable.msg_theme,
                LocaleController.getString(label),
            )
        }
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            otherItem.addSubItem(
                ACTION_DEBUG_CLEAR_CACHE,
                R.drawable.msg_delete,
                "Debug: clear profile cache",
            )
        }
    }

    @JvmStatic
    fun handleMenuClick(id: Int, currentAccount: Int, dialogId: Long): Boolean {
        when (id) {
            ACTION_TOGGLE_HIDE_WALLPAPER -> ChatHelper.toggleRemoveWallpaper(currentAccount, dialogId)
            ACTION_TOGGLE_HIDE_THEME -> ChatHelper.toggleRemoveTheme(currentAccount, dialogId)
            ACTION_DEBUG_CLEAR_CACHE -> debugClearProfileCache(currentAccount, dialogId)
            else -> return false
        }
        return true
    }

    private fun debugClearProfileCache(currentAccount: Int, dialogId: Long) {
        val mc = MessagesController.getInstance(currentAccount)
        val mcCls = MessagesController::class.java
        val isUser = dialogId > 0
        val keyAbs = if (isUser) dialogId else -dialogId

        runCatching {
            val mapName = if (isUser) "fullUsers" else "fullChats"
            val loadedName = if (isUser) "loadedFullUsers" else "loadedFullChats"

            mcCls.getDeclaredField(mapName).apply { isAccessible = true }.let { f ->
                val map = f.get(mc) as androidx.collection.LongSparseArray<*>
                map.remove(keyAbs)
            }
            mcCls.getDeclaredField(loadedName).apply { isAccessible = true }.let { f ->
                val arr = f.get(mc) as org.telegram.messenger.support.LongSparseLongArray
                arr.delete(keyAbs)
            }
        }.onFailure { it.printStackTrace() }

        runCatching {
            mcCls.getDeclaredField("dialogPhotos").apply { isAccessible = true }.let { f ->
                val map = f.get(mc) as androidx.collection.LongSparseArray<*>
                map.remove(dialogId)
            }
        }.onFailure { it.printStackTrace() }

        runCatching {
            val sc = mc.storiesController
            StoriesController::class.java.getDeclaredField("allStoriesMap").apply { isAccessible = true }.let { f ->
                val map = f.get(sc) as androidx.collection.LongSparseArray<*>
                map.remove(dialogId)
            }
            sc.dialogIdToMaxReadId.delete(dialogId)
        }.onFailure { it.printStackTrace() }

        runCatching {
            org.telegram.ui.Stars.StarsController.getInstance(currentAccount).invalidateProfileGifts(dialogId)
        }.onFailure { it.printStackTrace() }

        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable {
            runCatching {
                val db = storage.database
                val settingsTable = if (isUser) "user_settings" else "chat_settings_v2"
                db.executeFast("DELETE FROM $settingsTable WHERE uid = $keyAbs").stepThis().dispose()
                db.executeFast("DELETE FROM media_v4 WHERE uid = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM media_counts_v2 WHERE uid = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM dialog_photos WHERE uid = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM dialog_photos_count WHERE uid = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM stories WHERE dialog_id = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM stories_counter WHERE dialog_id = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM profile_stories WHERE dialog_id = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM profile_stories_albums WHERE dialog_id = $dialogId").stepThis().dispose()
                db.executeFast("DELETE FROM profile_stories_albums_links WHERE dialog_id = $dialogId").stepThis().dispose()
            }.onFailure { it.printStackTrace() }
        }

        Toast.makeText(ApplicationLoader.applicationContext, "Profile cache cleared — close & reopen profile", Toast.LENGTH_LONG).show()
    }

    @JvmStatic
    fun formatId(userId: Long, chat: TLRPC.Chat?): String {
        val isBotApi = InuConfig.PROFILE_ID_MODE.value == InuConfig.ProfileIdModeItem.BOT_API_ID
        if (userId != 0L) {
            return userId.toString()
        }

        if (chat != null) {
            if (!isBotApi) return chat.id.toString()
            if (ChatObject.isChannel(chat)) return (-1000000000000L - chat.id).toString()
            return (-chat.id).toString()
        }

        return ""
    }

    @JvmStatic
    fun onIdRowClick(
        fragment: BaseFragment,
        clipBackground: Drawable,
        view: View,
        userId: Long,
        chatId: Long,
        accountInstance: AccountInstance,
    ) {
        val messagesController = accountInstance.messagesController;
        val chat = if (chatId != 0L) messagesController.getChat(chatId) else null
        val text = formatId(userId, chat)
        if (text.isEmpty()) return

        ItemOptions.makeOptions(fragment, view)
            .setScrimViewBackground(clipBackground)
            .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                AndroidUtilities.addToClipboard(text)
                if (AndroidUtilities.shouldShowClipboardToast()) {
                    BulletinFactory.of(fragment).createCopyBulletin(
                        LocaleController.getString(R.string.InuProfileIdCopied)
                    ).show()
                }
            }.add(R.drawable.inu_tabler_code, LocaleController.getString(R.string.InuShowJson)) {
                val items = arrayListOf<TLObject>()
                if (userId != 0L) {
                    val user = messagesController.getUser(userId)
                    if (user != null) items.add(user)
                    val userFull = messagesController.getUserFull(userId)
                    if (userFull != null) items.add(userFull)
                    val botInfo = accountInstance.mediaDataController.getBotInfoCached(userId, userId)
                    if (botInfo != null) items.add(botInfo)
                } else {
                    if (chat != null) items.add(chat)
                    val chatFull = messagesController.getChatFull(chatId)
                    if (chatFull != null) items.add(chatFull)
                }
                WebAppHelper.openTlViewer(fragment, items)
            }.let { opts ->
                val regDate = if (userId != 0L) RegDateHelper.getRegDate(userId) else null
                if (regDate != null) {
                    opts.addGap()
                        .addText(LocaleController.formatString(R.string.InuRegDate, regDate), 13)
                }
                opts
            }.show()
    }
}
