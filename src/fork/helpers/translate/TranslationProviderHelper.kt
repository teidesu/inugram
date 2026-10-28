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
import org.telegram.messenger.MessageObject
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv

/**
 * Answers translation requests with the provider the user picked instead of Telegram. Requests
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
    private val requestProviders = Collections.synchronizedMap(WeakHashMap<TLObject, String>())

    // #endif

    private class RichTranslationRequest(
        val messages: List<TL_iv.RichMessage>,
        val languages: List<String?>,
        val toLanguage: String,
    ) : TLObject()

    @JvmStatic
    fun setSourceLanguage(text: TLRPC.TL_textWithEntities, language: String?) {
        // #if PLUGINS
        if (language.isNullOrEmpty() || language == TranslateController.UNKNOWN_LANGUAGE) sourceLanguages.remove(text)
        else sourceLanguages[text] = language
        // #endif
    }

    /** [provider] overrides the user's choice for this request, empty for Telegram */
    @JvmStatic
    fun setRequestProvider(request: TLObject, provider: String?) {
        // #if PLUGINS
        if (provider != null) requestProviders[request] = provider
        // #endif
    }

    @JvmStatic
    fun createRichRequest(message: TL_iv.RichMessage, language: String?, toLanguage: String): TLObject =
        RichTranslationRequest(listOf(message), listOf(language), toLanguage)

    @JvmStatic
    fun createRichRequest(messages: List<MessageObject>, toLanguage: String): TLObject =
        RichTranslationRequest(
            messages.map { it.messageOwner.rich_message },
            messages.map { it.messageOwner.originalLanguage },
            toLanguage,
        )

    @JvmStatic
    fun isActive(): Boolean = isActive(null)

    /** [provider] as in [setRequestProvider] */
    @JvmStatic
    fun isActive(provider: String?): Boolean =
        // #if PLUGINS
        InuConfig.PLUGINS_ENABLED.value && (provider ?: InuConfig.TRANSLATION_PROVIDER.value).isNotEmpty()
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
        val provider = requestProviders.remove(request)
        if (!isActive(provider)) {
            if (request !is RichTranslationRequest) return false
            Utilities.stageQueue.postRunnable {
                val error = createError("TRANSLATION_PROVIDER_FAILED")
                onComplete?.run(null, error)
                onCompleteTimestamp?.run(null, error, 0)
            }
            return true
        }
        if (request !is RichTranslationRequest && PluginRpc.isBypassed(request)) return false
        val key = provider ?: InuConfig.TRANSLATION_PROVIDER.value
        val complete = { response: TLObject?, error: TLRPC.TL_error? ->
            Utilities.stageQueue.postRunnable {
                onComplete?.run(response, error)
                onCompleteTimestamp?.run(response, error, 0)
            }
        }
        val texts: List<TLRPC.TL_textWithEntities>
        val from: List<String?>
        val tone: String?
        val toLanguage: String
        val createResponse: (List<TLRPC.TL_textWithEntities>) -> TLObject?
        when (request) {
            is TLRPC.TL_messages_translateText -> {
                if (request.text.isEmpty()) {
                    PluginLog.HOST.w("translation", "a translation by message id cannot reach a translation provider")
                    complete(null, createError("TRANSLATION_PROVIDER_NEEDS_TEXT"))
                    return true
                }
                texts = request.text
                from = request.text.map { sourceLanguages[it] }
                tone = request.tone
                toLanguage = request.to_lang
                createResponse = { TLRPC.TL_messages_translateResult().apply { result = ArrayList(it) } }
            }
            is RichTranslationRequest -> {
                val translator = RichMessageTranslator.create(account, request.messages, request.languages)
                if (translator == null) {
                    complete(null, createError("TRANSLATION_PROVIDER_FAILED"))
                    return true
                }
                if (translator.texts.isEmpty()) {
                    complete(TLRPC.TL_messages_translatedRichMessage().apply { result = translator.messages }, null)
                    return true
                }
                texts = translator.texts
                from = translator.sourceLanguages
                tone = null
                toLanguage = request.toLanguage
                createResponse = { translated ->
                    translator.apply(translated)?.let { messages ->
                        TLRPC.TL_messages_translatedRichMessage().apply { result = messages }
                    }
                }
            }
            else -> return false
        }
        val requestKey = (account.toLong() shl 32) or (token.toLong() and 0xffffffffL)
        val entry = Request()
        requests[requestKey] = entry
        EngineDispatch.scheduler.postRunnable {
            if (requests[requestKey] !== entry) return@postRunnable
            entry.dispatchId = PluginTranslation.translate(key, texts, from, toLanguage, tone) { translated ->
                if (!requests.remove(requestKey, entry)) return@translate
                val response = translated?.let(createResponse)
                if (response != null) complete(response, null) else complete(null, createError("TRANSLATION_PROVIDER_FAILED"))
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
