package desu.inugram.helpers

import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LanguageDetector
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.RestrictedLanguagesSelectActivity
import java.util.concurrent.ConcurrentHashMap

object TranslateHelper {
    // dialog id -> message ids whose body is manually translated
    private val manual = ConcurrentHashMap<Long, MutableSet<Int>>()

    // dialog id -> message id -> translated webpage clone
    private val webPages = ConcurrentHashMap<Long, ConcurrentHashMap<Int, TLRPC.TL_webPage>>()

    // dialog id -> message ids whose webpage is currently being translated
    private val webPagesLoading = ConcurrentHashMap<Long, MutableSet<Int>>()

    // dialog id -> message id -> (detected src lang, target lang) for the webpage
    private val webPagesLangs = ConcurrentHashMap<Long, ConcurrentHashMap<Int, Pair<String?, String>>>()

    @JvmStatic
    fun isWebPageTranslating(msg: MessageObject?): Boolean {
        if (msg == null) return false
        return webPagesLoading[msg.dialogId]?.contains(msg.id) == true
    }

    @JvmStatic
    @JvmOverloads
    fun isManualTranslated(msg: MessageObject?, group: MessageObject.GroupedMessages? = null): Boolean {
        if (msg != null && manual[msg.dialogId]?.contains(msg.id) == true) return true
        val cap = group?.captionMessage ?: return false
        return manual[cap.dialogId]?.contains(cap.id) == true
    }

    @JvmStatic
    @JvmOverloads
    fun isManuallyAffected(msg: MessageObject?, group: MessageObject.GroupedMessages? = null): Boolean =
        isManualTranslated(msg, group) || hasTranslatedWebPage(msg) || hasTranslatedWebPage(group?.captionMessage)

    @JvmStatic
    fun hasTranslatableWebPage(msg: MessageObject?): Boolean {
        if (!InuConfig.IN_PLACE_TRANSLATION.value || !InuConfig.TRANSLATE_WEB_PREVIEWS.value) return false
        val wp = webPageOf(msg) ?: return false
        return !wp.title.isNullOrBlank() || !wp.description.isNullOrBlank() ||
            !wp.site_name.isNullOrBlank() || !wp.author.isNullOrBlank()
    }

    private fun translatedWebPageClone(msg: MessageObject?): TLRPC.TL_webPage? {
        if (msg == null) return null
        return webPages[msg.dialogId]?.get(msg.id)
    }

    private fun hasTranslatedWebPage(msg: MessageObject?): Boolean = translatedWebPageClone(msg) != null

    /** Returns a translated clone if available, otherwise [original]. */
    @JvmStatic
    fun viewWebPage(msg: MessageObject?, original: TLRPC.TL_webPage): TLRPC.TL_webPage =
        translatedWebPageClone(msg) ?: original

    private fun webPageOf(msg: MessageObject?): TLRPC.TL_webPage? {
        val media = msg?.messageOwner?.media as? TLRPC.TL_messageMediaWebPage ?: return null
        return media.webpage as? TLRPC.TL_webPage
    }

    /** @return true if handled in-place; false → caller should fall back to stock TranslateAlert. */
    @JvmStatic
    fun startTranslate(
        activity: ChatActivity,
        selected: MessageObject?,
        group: MessageObject.GroupedMessages?,
        fromLang: String?,
        toLang: String?,
    ): Boolean {
        if (!InuConfig.IN_PLACE_TRANSLATION.value) return false
        if (selected == null || toLang == null) return false
        if (selected.isPoll) return false

        val target = group?.captionMessage?.takeIf { !it.messageOwner?.message.isNullOrEmpty() } ?: selected
        val owner = target.messageOwner ?: return false

        val hasBody = !owner.message.isNullOrEmpty()
        val webPage = if (InuConfig.TRANSLATE_WEB_PREVIEWS.value) webPageOf(target) else null
        val hasWebPage = webPage != null && (
            !webPage.title.isNullOrBlank() || !webPage.description.isNullOrBlank() ||
                !webPage.site_name.isNullOrBlank() || !webPage.author.isNullOrBlank()
            )

        if (!hasBody && !hasWebPage) return false

        activity.dimBehindView(false)
        if (hasBody) startBodyTranslate(activity, target, owner, fromLang, toLang)
        if (hasWebPage) startWebPageTranslate(activity, target, webPage!!, toLang)
        return true
    }

    private fun startBodyTranslate(
        activity: ChatActivity,
        target: MessageObject,
        owner: TLRPC.Message,
        fromLang: String?,
        toLang: String,
    ) {
        val srcLang = fromLang?.takeIf { it != "und" } ?: owner.originalLanguage
        if (srcLang != null) {
            val dnt = RestrictedLanguagesSelectActivity.getRestrictedLanguages()
            if (dnt.contains(srcLang) || srcLang == toLang) return
        }
        val account = activity.currentAccount
        val controller = MessagesController.getInstance(account).translateController
        val dialogId = target.dialogId

        manual.computeIfAbsent(dialogId) { ConcurrentHashMap.newKeySet() }.add(target.id)
        if (fromLang != null && fromLang != "und" && owner.originalLanguage == null) {
            owner.originalLanguage = fromLang
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslating, target)

        controller.pushToTranslate(target, toLang) { isTranscription, id, text, lang ->
            if (id != target.id) return@pushToTranslate
            if (text == null || text.text.isNullOrEmpty()) {
                manual[dialogId]?.remove(target.id)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
                BulletinFactory.of(activity)
                    .createErrorBulletin(LocaleController.getString(R.string.TranslationFailedAlert1))
                    .show()
                return@pushToTranslate
            }
            owner.translatedToLanguage = lang
            if (isTranscription) owner.translatedVoiceTranscription = text
            else owner.translatedText = text
            owner.translatedPoll = null
            MessagesStorage.getInstance(account).updateMessageCustomParams(dialogId, owner)
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
        }
    }

    private fun startWebPageTranslate(
        activity: ChatActivity,
        target: MessageObject,
        webPage: TLRPC.TL_webPage,
        toLang: String,
    ) {
        val account = activity.currentAccount
        markLoading(target, true)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslating, target)

        val sample = listOfNotNull(webPage.title, webPage.description)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        val proceed: (String?) -> Unit = { srcLang ->
            sendWebPageTranslateRequest(activity, target, webPage, toLang, srcLang)
        }
        if (sample.isEmpty() || !LanguageDetector.hasSupport()) {
            proceed(null)
            return
        }
        LanguageDetector.detectLanguage(sample, { src ->
            AndroidUtilities.runOnUIThread {
                val srcLang = src?.split("_")?.firstOrNull()
                val dnt = RestrictedLanguagesSelectActivity.getRestrictedLanguages()
                if (srcLang != null && (dnt.contains(srcLang) || srcLang == toLang)) {
                    markLoading(target, false)
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
                    return@runOnUIThread
                }
                proceed(srcLang)
            }
        }, {
            AndroidUtilities.runOnUIThread { proceed(null) }
        })
    }

    private fun markLoading(target: MessageObject, loading: Boolean) {
        if (loading) {
            webPagesLoading.computeIfAbsent(target.dialogId) { ConcurrentHashMap.newKeySet() }.add(target.id)
        } else {
            webPagesLoading[target.dialogId]?.remove(target.id)
        }
    }

    private fun sendWebPageTranslateRequest(
        activity: ChatActivity,
        target: MessageObject,
        original: TLRPC.TL_webPage,
        toLang: String,
        srcLang: String?,
    ) {
        val parts = mutableListOf<Pair<Char, String>>()
        original.title?.takeIf { it.isNotBlank() }?.let { parts += 't' to it }
        original.description?.takeIf { it.isNotBlank() }?.let { parts += 'd' to it }
        original.site_name?.takeIf { it.isNotBlank() }?.let { parts += 's' to it }
        original.author?.takeIf { it.isNotBlank() }?.let { parts += 'a' to it }
        val account = activity.currentAccount
        if (parts.isEmpty()) {
            markLoading(target, false)
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
            return
        }

        val req = TLRPC.TL_messages_translateText()
        req.flags = req.flags or 2
        req.to_lang = toLang
        for ((_, p) in parts) {
            val twe = TLRPC.TL_textWithEntities()
            twe.text = p
            req.text.add(twe)
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { res, _ ->
            AndroidUtilities.runOnUIThread {
                markLoading(target, false)
                if (res is TLRPC.TL_messages_translateResult && res.result.size >= parts.size) {
                    val clone = cloneWebPage(original)
                    if (clone != null) {
                        for (i in parts.indices) {
                            val text = res.result[i].text ?: continue
                            when (parts[i].first) {
                                't' -> clone.title = text
                                'd' -> clone.description = text
                                's' -> clone.site_name = text
                                'a' -> clone.author = text
                            }
                        }
                        webPages.computeIfAbsent(target.dialogId) { ConcurrentHashMap() }[target.id] = clone
                        webPagesLangs.computeIfAbsent(target.dialogId) { ConcurrentHashMap() }[target.id] = srcLang to toLang
                        target.linkDescription = null
                    }
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, target)
            }
        }
    }

    private fun cloneWebPage(wp: TLRPC.TL_webPage): TLRPC.TL_webPage? {
        return try {
            val buf = NativeByteBuffer(wp.objectSize)
            wp.serializeToStream(buf)
            buf.position(0)
            val constructor = buf.readInt32(false)
            TLRPC.WebPage.TLdeserialize(buf, constructor, false) as? TLRPC.TL_webPage
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun bodyTranslated(msg: MessageObject?): Boolean {
        if (msg == null || !msg.translated) return false
        return msg.messageOwner?.translatedToLanguage != null
    }

    private fun translatedPairs(msg: MessageObject): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val owner = msg.messageOwner
        if (isManualTranslated(msg) && owner != null) {
            val from = owner.originalLanguage
            val to = owner.translatedToLanguage
            if (from != null && from != "und" && to != null) {
                list += from to to
            }
        }
        val wp = webPagesLangs[msg.dialogId]?.get(msg.id)
        if (wp != null) {
            val from = wp.first
            val to = wp.second
            if (from != null && from != "und") {
                list += from to to
            }
        }
        return list.distinct()
    }

    @JvmStatic
    fun extraTimeWidth(msg: MessageObject?): Int {
        if (msg == null) return 0
        if (!bodyTranslated(msg) && !hasTranslatedWebPage(msg)) return 0
        val pairs = translatedPairs(msg)
        if (pairs.isEmpty()) return AndroidUtilities.dp(11f)
        val arrows = pairs.distinctBy { it.second }.size
        return arrows * (arrowDrawable?.intrinsicWidth ?: 0)
    }

    private val arrowDrawable: Drawable? by lazy {
        ContextCompat.getDrawable(
            ApplicationLoader.applicationContext,
            R.drawable.search_arrow,
        )
    }

    @JvmStatic
    fun translatedTimePrefix(msg: MessageObject?, time: CharSequence?): CharSequence? {
        if (time == null || msg == null) return time
        if (!bodyTranslated(msg) && !hasTranslatedWebPage(msg)) return time
        val pairs = translatedPairs(msg)
        val sb = SpannableStringBuilder()
        if (pairs.isEmpty()) {
            sb.append("​")
            sb.setSpan(ColoredImageSpan(R.drawable.msg_translate).apply {
                setSize(AndroidUtilities.dp(11f))
                setTranslateY(AndroidUtilities.dpf2(1f))
            }, sb.length - 1, sb.length, 0)
            sb.append(" ")
        } else {
            val grouped = linkedMapOf<String, MutableList<String>>()
            for ((from, to) in pairs) grouped.getOrPut(to) { mutableListOf() } += from
            var first = true
            for ((to, froms) in grouped) {
                if (!first) sb.append(", ")
                first = false
                sb.append(froms.joinToString(", ") { label(it) }).append(" ")
                sb.append("​")
                sb.setSpan(ColoredImageSpan(R.drawable.search_arrow, ColoredImageSpan.ALIGN_CENTER), sb.length - 1, sb.length, 0)
                sb.append(" ").append(label(to)).append(" ")
            }
        }
        return sb.append(time)
    }

    private fun label(code: String): String {
        val name = TranslateAlert2.languageName(code) ?: code.uppercase()
        return if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
    }

    @JvmStatic
    fun revert(activity: ChatActivity, msg: MessageObject) {
        manual[msg.dialogId]?.remove(msg.id)
        if (webPages[msg.dialogId]?.remove(msg.id) != null) {
            msg.linkDescription = null
        }
        webPagesLangs[msg.dialogId]?.remove(msg.id)
        NotificationCenter.getInstance(activity.currentAccount)
            .postNotificationName(NotificationCenter.messageTranslated, msg)
    }

    fun resetForDialog(dialogId: Long) {
        manual.remove(dialogId)
        webPages.remove(dialogId)
        webPagesLoading.remove(dialogId)
        webPagesLangs.remove(dialogId)
    }
}
