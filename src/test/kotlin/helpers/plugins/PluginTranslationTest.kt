package desu.inugram.helpers.plugins

import desu.inugram.InuConfig
import desu.inugram.helpers.translate.TranslationProviderHelper
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

class PluginTranslationTest {
    private class Answer(val response: TLObject?, val error: TLRPC.TL_error?)

    private var pluginsWereEnabled = false

    @Before
    fun setUp() {
        resetBridge()
        pluginsWereEnabled = InuConfig.PLUGINS_ENABLED.value
        InuConfig.PLUGINS_ENABLED.value = true
        InuConfig.TRANSLATION_PROVIDER.value = ""
    }

    @After
    fun tearDown() {
        InuConfig.PLUGINS_ENABLED.value = pluginsWereEnabled
        InuConfig.TRANSLATION_PROVIDER.value = ""
    }

    private fun startProvider(translate: String, format: String = "plain"): Plugin {
        val plugin = startEngine("translator")
        plugin.js("inu.registerTranslationProvider({ id: 'p', name: 'Provider', format: '$format', translate: $translate })")
        InuConfig.TRANSLATION_PROVIDER.value = "${plugin.id}:p"
        return plugin
    }

    private fun send(token: Int, vararg texts: TLRPC.TL_textWithEntities): AtomicReference<Answer?> {
        val answer = AtomicReference<Answer?>()
        val request = TLRPC.TL_messages_translateText().apply {
            flags = 2
            text.addAll(texts)
            to_lang = "de"
        }
        val taken = TranslationProviderHelper.maybeTranslate(0, token, request, RequestDelegate { response, error -> answer.set(Answer(response, error)) }, null)
        assertTrue(taken)
        return answer
    }

    private fun text(value: String, vararg entities: TLRPC.MessageEntity) = TLRPC.TL_textWithEntities().apply {
        text = value
        this.entities.addAll(entities)
    }

    @Test
    fun an_html_provider_answers_the_app_request_with_its_formatting_kept_and_learns_the_source_languages() {
        val plugin = startProvider("({ texts, from }) => { globalThis.__from = from; return texts.map(t => t.replace('hello', 'hallo')) }", format = "html")
        val bold = TLRPC.TL_messageEntityBold().apply { offset = 0; length = 5 }
        val english = text("hello world", bold)
        TranslationProviderHelper.setSourceLanguage(english, "en")

        val answer = awaitValue("translated") { send(1, english, text("two")).get() }

        val result = (answer.response as TLRPC.TL_messages_translateResult).result
        assertEquals(listOf("hallo world", "two"), result.map { it.text })
        val entity = result[0].entities.single()
        assertTrue(entity is TLRPC.TL_messageEntityBold)
        assertEquals(0 to 5, entity.offset to entity.length)
        assertEquals("""["en",null]""", plugin.js("JSON.stringify(__from)"))
    }

    @Test
    fun a_cancelled_request_aborts_the_provider_and_is_never_answered() {
        val plugin = startProvider("({ signal }) => { globalThis.__signal = signal; return new Promise(r => { globalThis.__resolve = r }) }")
        val answer = send(2, text("hi"))
        settle()

        assertTrue(TranslationProviderHelper.cancelRequest(0, 2))
        settle()
        assertEquals("true", plugin.js("String(__signal.aborted)"))
        plugin.js("__resolve(['late'])")
        settle()
        Thread.sleep(100)
        assertNull(answer.get())
    }

    @Test
    fun a_provider_past_its_budget_fails_the_request() {
        val plugin = startProvider("({ signal }) => { globalThis.__signal = signal; return new Promise(() => {}) }")
        val answer = send(3, text("hi"))
        settle()

        advanceBy(30_000)

        assertEquals("TRANSLATION_PROVIDER_FAILED", awaitValue("failed") { answer.get() }.error?.text)
        assertEquals("timed-out", plugin.js("__signal.reason.code"))
    }

    @Test
    fun a_picked_provider_that_is_not_running_fails_the_request() {
        InuConfig.TRANSLATION_PROVIDER.value = "gone:p"

        val answer = awaitValue("failed") { send(4, text("hi")).get() }

        assertEquals("TRANSLATION_PROVIDER_FAILED", answer.error?.text)
    }

    @Test
    fun a_disabled_plugin_engine_leaves_the_request_to_telegram() {
        InuConfig.PLUGINS_ENABLED.value = false
        InuConfig.TRANSLATION_PROVIDER.value = "any:p"
        val request = TLRPC.TL_messages_translateText().apply { text.add(text("hi")) }

        assertFalse(TranslationProviderHelper.maybeTranslate(0, 6, request, RequestDelegate { _, _ -> }, null))
    }

    @Test
    fun a_malformed_answer_fails_the_request() {
        startProvider("() => ['one', 'too many']")

        val answer = awaitValue("failed") { send(5, text("hi")).get() }

        assertEquals("TRANSLATION_PROVIDER_FAILED", answer.error?.text)
    }
}
