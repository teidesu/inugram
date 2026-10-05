package desu.inugram.helpers.translate

import desu.inugram.InuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.EngineDispatch
import desu.inugram.helpers.plugins.PluginLog
import desu.inugram.helpers.plugins.telegram.PluginRpc
import desu.inugram.helpers.plugins.telegram.PluginTranslation
// #endif
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * Answers `messages.translateText` with the provider the user picked instead of Telegram. Requests
 * keep their [org.telegram.tgnet.ConnectionsManager] token, so stock cancels them as it does any other.
 */
object TranslationProviderHelper {
    // #if PLUGINS
    private class Request {
        /** scheduler only */
        var dispatchId = 0L
    }

    private val requests = ConcurrentHashMap<Long, Request>()
    private val sourceLanguages = Collections.synchronizedMap(WeakHashMap<TLRPC.TL_textWithEntities, String>())

    // #endif

    @JvmStatic
    fun setSourceLanguage(text: TLRPC.TL_textWithEntities, language: String?) {
        // #if PLUGINS
        if (language.isNullOrEmpty() || language == TranslateController.UNKNOWN_LANGUAGE) sourceLanguages.remove(text)
        else sourceLanguages[text] = language
        // #endif
    }

    @JvmStatic
    fun isActive(): Boolean =
        // #if PLUGINS
        InuConfig.PLUGINS_ENABLED.value && InuConfig.TRANSLATION_PROVIDER.value.isNotEmpty()
        // #else
        false
        // #endif

    /** Premium's chat translation, which a provider of the user's own does not need */
    @JvmStatic
    fun canTranslateChats(account: Int): Boolean = isActive() || UserConfig.getInstance(account).isPremium

    /** stageQueue, from `ConnectionsManager.sendRequestInternal` */
    @JvmStatic
    fun maybeTranslate(
        account: Int,
        token: Int,
        request: TLObject,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
    ): Boolean {
        // #if PLUGINS
        if (!isActive() || request !is TLRPC.TL_messages_translateText || PluginRpc.isBypassed(request)) return false
        val key = InuConfig.TRANSLATION_PROVIDER.value
        val complete = { response: TLObject?, error: TLRPC.TL_error? ->
            Utilities.stageQueue.postRunnable {
                onComplete?.run(response, error)
                onCompleteTimestamp?.run(response, error, 0)
            }
        }
        if (request.text.isEmpty()) {
            PluginLog.HOST.w("translation", "a translation by message id cannot reach a translation provider")
            complete(null, createError("TRANSLATION_PROVIDER_NEEDS_TEXT"))
            return true
        }
        val requestKey = (account.toLong() shl 32) or (token.toLong() and 0xffffffffL)
        val from = request.text.map { sourceLanguages[it] }
        val entry = Request()
        requests[requestKey] = entry
        EngineDispatch.scheduler.postRunnable {
            if (requests[requestKey] !== entry) return@postRunnable
            entry.dispatchId = PluginTranslation.translate(key, request.text, from, request.to_lang, request.tone) { texts ->
                if (!requests.remove(requestKey, entry)) return@translate
                if (texts != null) complete(TLRPC.TL_messages_translateResult().apply { result = texts }, null)
                else complete(null, createError("TRANSLATION_PROVIDER_FAILED"))
            }
        }
        return true
        // #else
        return false
        // #endif
    }

    /** stageQueue, from `ConnectionsManager.cancelRequest`. False for a token this did not answer */
    @JvmStatic
    fun cancelRequest(account: Int, token: Int): Boolean {
        // #if PLUGINS
        val entry = requests.remove((account.toLong() shl 32) or (token.toLong() and 0xffffffffL)) ?: return false
        EngineDispatch.scheduler.postRunnable { PluginTranslation.cancel(entry.dispatchId) }
        return true
        // #else
        return false
        // #endif
    }

    private fun createError(text: String) = TLRPC.TL_error().apply {
        code = 400
        this.text = text
    }
}
