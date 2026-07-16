package desu.inugram.helpers.translate

import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.ChatHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LanguageDetector
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.TextSelectionHelper
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.LaunchActivity
import org.telegram.ui.RestrictedLanguagesSelectActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object TranslateHelper {
    private const val ORIGINAL_SEPARATOR = "\n\n--------\n\n"

    // dialog id -> message ids whose body is manually translated
    private val manual = ConcurrentHashMap<Long, MutableSet<Int>>()

    // dialog id -> message id -> translated text with original appended (per-session)
    private val bodies = ConcurrentHashMap<Long, ConcurrentHashMap<Int, TLRPC.TL_textWithEntities>>()

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

    @JvmStatic
    fun installSelectionTranslate(helper: TextSelectionHelper<*>) {
        val account = UserConfig.selectedAccount
        if (!MessagesController.getInstance(account).translateController.isContextTranslateEnabled) return
        helper.setOnTranslate { text, fromLang, toLang, onDismiss ->
            val fragment = LaunchActivity.getLastFragment()
            val context = fragment?.parentActivity ?: LaunchActivity.instance ?: return@setOnTranslate
            TranslateAlert2.showAlert(
                context, fragment, account,
                fromLang, toLang, text, null, false, null, onDismiss,
            )
        }
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

    // entry point for OPTION_TRANSLATE, replicates the stock translate menu cell's trigger
    fun triggerTranslate(
        activity: ChatActivity,
        selected: MessageObject?,
        group: MessageObject.GroupedMessages?,
    ) {
        if (selected == null) return
        val parent = activity.parentActivity ?: return
        val account = activity.currentAccount

        val toLang = TranslateAlert2.getToLanguage()
        val toLangDefault = LocaleController.getInstance().currentLocale.language
        val messageIdToTranslate = intArrayOf(selected.id)

        val inputPeer = if (selected.isPoll || selected.isVoiceTranscriptionOpen || selected.isSponsored ||
            selected.scheduled || activity.chatMode == ChatActivity.MODE_QUICK_REPLIES
        ) {
            null
        } else {
            MessagesController.getInstance(account).getInputPeer(activity.dialogId)
        }
        val noforwards = activity.isPeerNoForwards ||
            selected.messageOwner?.noforwards == true ||
            selected.type == MessageObject.TYPE_PAID_MEDIA

        fun updateTranslateHint() {
            val prefs = MessagesController.getNotificationsSettings(account)
            val key = "dialog_show_translate_count" + activity.dialogId
            val hintCount = prefs.getInt(key, 5)
            if (hintCount > 0) {
                prefs.edit { putInt(key, hintCount - 1) }
                activity.updateTopPanel(true)
            }
        }

        val richMessage = if (selected.type == MessageObject.TYPE_ARTICLE) selected.messageOwner?.rich_message else null
        if (richMessage != null) {
            val fromLang = selected.messageOwner?.originalLanguage
            val toLangValue = if (fromLang != null && fromLang == toLang) toLangDefault else toLang
            val alert = TranslateAlert2.showAlert(
                parent, activity, account, inputPeer, messageIdToTranslate[0],
                fromLang, toLangValue, richMessage, noforwards, null,
            ) { activity.dimBehindView(false) }
            alert?.setDimBehind(false)
            updateTranslateHint()
            return
        }

        val text = selected.getMessageTextToTranslate(group, messageIdToTranslate) ?: return

        fun perform(fromLang: String?) {
            val toLangValue = if (fromLang != null && fromLang == toLang) toLangDefault else toLang
            val srcLang = fromLang?.takeIf { it != "und" } ?: selected.messageOwner?.originalLanguage
            if (srcLang != null && srcLang == toLangValue && !hasTranslatableWebPage(selected)) {
                val langName = TranslateAlert2.languageName(srcLang)?.let(TranslateAlert2::capitalFirst) ?: srcLang.uppercase()
                BulletinFactory.of(activity)
                    .createErrorBulletin(LocaleController.formatString(R.string.InuAlreadyInTargetLanguage, langName))
                    .show()
                return
            }
            if (!startTranslate(activity, selected, group, fromLang ?: "und", toLangValue)) {
                activity.dimBehindView(false)
                val alert = TranslateAlert2.showAlert(
                    parent, activity, account, inputPeer, messageIdToTranslate[0], selected.summarized,
                    fromLang ?: "und", toLangValue, text, selected.messageOwner?.entities,
                    noforwards, null,
                ) { activity.dimBehindView(false) }
                alert.setDimBehind(false)
            }
            updateTranslateHint()
        }

        val originalLanguage = selected.messageOwner?.originalLanguage
        when {
            originalLanguage != null -> perform(originalLanguage)
            InuConfig.TRANSLATE_AUTO_DETECT_LANG.value && LanguageDetector.hasSupport() -> LanguageDetector.detectLanguage(
                text.toString(),
                { perform(it) },
                { perform(null) },
            )

            else -> perform(null)
        }
    }

    @JvmStatic
    fun setupTranslateMenuCell(
        activity: ChatActivity,
        cell: View,
        selected: MessageObject?,
        group: MessageObject.GroupedMessages?,
        waitForLangDetection: AtomicBoolean,
        onLangDetectionDone: AtomicReference<Runnable>,
    ) {
        if (selected == null) return

        if (selected.type == MessageObject.TYPE_ARTICLE && selected.messageOwner?.rich_message != null) {
            cell.visibility = View.VISIBLE
            return
        }

        val toLang = TranslateAlert2.getToLanguage()
        val toLangDefault = LocaleController.getInstance().currentLocale.language
        val respectDnt = InuConfig.TRANSLATE_AUTO_DETECT_LANG.value

        fun shouldShowTranslateRow(fromLang: String): Boolean {
            if (respectDnt && RestrictedLanguagesSelectActivity.getRestrictedLanguages().contains(fromLang)) return false
            return fromLang != toLang || fromLang != toLangDefault || fromLang == TranslateController.UNKNOWN_LANGUAGE
        }

        val originalLanguage = selected.messageOwner?.originalLanguage
        if (originalLanguage != null) {
            cell.visibility = if (shouldShowTranslateRow(originalLanguage)) View.VISIBLE else View.GONE
        } else if (respectDnt) {
            val text = selected.getMessageTextToTranslate(group, intArrayOf(selected.id))
            if (text != null) {
                cell.visibility = View.GONE
                waitForLangDetection.set(true)
                LanguageDetector.detectLanguage(
                    text.toString(),
                    { lang ->
                        if (lang == null || shouldShowTranslateRow(lang)) cell.visibility = View.VISIBLE
                        if (lang != null && selected.messageOwner != null) {
                            selected.messageOwner.originalLanguage = lang // avoid doing that trice
                        }
                        waitForLangDetection.set(false)
                        onLangDetectionDone.getAndSet(null)?.run()
                    },
                    {
                        waitForLangDetection.set(false)
                        onLangDetectionDone.getAndSet(null)?.run()
                    },
                )
                cell.postDelayed({ onLangDetectionDone.getAndSet(null)?.run() }, 250)
            }
        }

        if (hasTranslatableWebPage(selected)) cell.visibility = View.VISIBLE
    }

    private fun startBodyTranslate(
        activity: ChatActivity,
        target: MessageObject,
        owner: TLRPC.Message,
        fromLang: String?,
        toLang: String,
    ) {
        val srcLang = fromLang?.takeIf { it != "und" } ?: owner.originalLanguage
        if (srcLang != null && srcLang == toLang) return
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
            if (isTranscription) {
                owner.translatedVoiceTranscription = text
            } else {
                owner.translatedText = text
                storeMergedBody(target, text)
            }
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

    private fun cloneWebPage(wp: TLRPC.TL_webPage): TLRPC.TL_webPage? =
        InuUtils.cloneTLObject(wp, TLRPC.WebPage::TLdeserialize) as? TLRPC.TL_webPage

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
    fun hasTimeAddition(msg: MessageObject?): Boolean {
        if (msg == null) return false
        return bodyTranslated(msg) || hasTranslatedWebPage(msg)
    }

    @JvmStatic
    fun timeAdditionsHash(msg: MessageObject?): Int {
        if (!hasTimeAddition(msg)) return 0
        return translatedPairs(msg!!).hashCode()
    }

    @JvmStatic
    fun extraTimeWidth(msg: MessageObject?): Int {
        if (!hasTimeAddition(msg)) return 0
        val pairs = translatedPairs(msg!!)
        if (pairs.isEmpty()) return AndroidUtilities.dp(11f)
        return pairs.distinctBy { it.second }.size * (arrowDrawable?.intrinsicWidth ?: 0)
    }

    @JvmStatic
    fun appendTimePrefix(sb: SpannableStringBuilder, msg: MessageObject?) {
        if (!hasTimeAddition(msg)) return
        val pairs = translatedPairs(msg!!)
        if (pairs.isEmpty()) {
            ChatHelper.appendTimeIcon(sb, R.drawable.msg_translate, sizeDp = 11f, translateYDp = 1f)
            sb.append(" ")
            return
        }
        val grouped = linkedMapOf<String, MutableList<String>>()
        for ((from, to) in pairs) grouped.getOrPut(to) { mutableListOf() } += from
        var first = true
        for ((to, froms) in grouped) {
            if (!first) sb.append(", ")
            first = false
            sb.append(froms.joinToString(", ") { label(it) }).append(" ")
            ChatHelper.appendTimeIcon(sb, R.drawable.search_arrow, align = ColoredImageSpan.ALIGN_CENTER)
            sb.append(" ").append(label(to)).append(" ")
        }
    }

    private val arrowDrawable: Drawable? by lazy {
        ContextCompat.getDrawable(
            ApplicationLoader.applicationContext,
            R.drawable.search_arrow,
        )
    }

    private fun label(code: String): String {
        val name = TranslateAlert2.languageName(code) ?: code.uppercase()
        return if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
    }

    @JvmStatic
    fun revert(activity: ChatActivity, msg: MessageObject) {
        manual[msg.dialogId]?.remove(msg.id)
        bodies[msg.dialogId]?.remove(msg.id)
        if (webPages[msg.dialogId]?.remove(msg.id) != null) {
            msg.linkDescription = null
        }
        webPagesLangs[msg.dialogId]?.remove(msg.id)
        NotificationCenter.getInstance(activity.currentAccount)
            .postNotificationName(NotificationCenter.messageTranslated, msg)
    }

    fun resetForDialog(dialogId: Long) {
        manual.remove(dialogId)
        bodies.remove(dialogId)
        webPages.remove(dialogId)
        webPagesLoading.remove(dialogId)
        webPagesLangs.remove(dialogId)
    }

    /** Returns the translated body with original appended when the toggle is on; otherwise [original]. */
    @JvmStatic
    fun viewTranslatedText(
        msg: MessageObject?,
        original: TLRPC.TL_textWithEntities?,
    ): TLRPC.TL_textWithEntities? {
        if (msg == null || original == null) return original
        if (!InuConfig.KEEP_ORIGINAL_AFTER_TRANSLATION.value) return original
        val cached = bodies[msg.dialogId]?.get(msg.id) ?: return original
        // owner.translatedText may have been replaced by a different translation
        // (e.g. stock chat-translate flipping language). drop the stale merge.
        if (original.text == null || !cached.text.startsWith(original.text + ORIGINAL_SEPARATOR)) {
            bodies[msg.dialogId]?.remove(msg.id)
            return original
        }
        return cached
    }

    private fun storeMergedBody(target: MessageObject, translated: TLRPC.TL_textWithEntities) {
        val originalText = target.messageOwner?.message
        if (translated.text.isNullOrEmpty() || originalText.isNullOrEmpty()) {
            bodies[target.dialogId]?.remove(target.id)
            return
        }
        val merged = TLRPC.TL_textWithEntities()
        merged.text = translated.text + ORIGINAL_SEPARATOR + originalText
        val shift = translated.text.length + ORIGINAL_SEPARATOR.length
        val originalEntities = target.messageOwner?.entities
        merged.entities = ArrayList(translated.entities.size + (originalEntities?.size ?: 0))
        merged.entities.addAll(translated.entities)
        originalEntities?.forEach { e ->
            cloneEntity(e)?.also {
                it.offset += shift
                merged.entities.add(it)
            }
        }
        bodies.computeIfAbsent(target.dialogId) { ConcurrentHashMap() }[target.id] = merged
    }

    private fun cloneEntity(e: TLRPC.MessageEntity): TLRPC.MessageEntity? =
        InuUtils.cloneTLObject(e, TLRPC.MessageEntity::TLdeserialize)
}
