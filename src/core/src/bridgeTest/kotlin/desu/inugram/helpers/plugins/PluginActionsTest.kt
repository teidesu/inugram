package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.ui.PluginActions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

class PluginActionsTest {
    @Before
    fun setUp() = resetBridge()

    private fun Plugin.answers(vararg rows: Pair<Int, String>) {
        js.onRenderActions = { _, _ ->
            rows.joinToString(",", "[", "]") { (token, text) -> """{"token":$token,"text":"$text"}""" }
        }
    }

    private fun rendered(kind: Int, surface: PluginActions.Surface): List<String> {
        val out = ArrayList<String>()
        PluginActions.render(kind, surface) { rows -> out.addAll(rows.map { it.text }) }
        drain()
        return out
    }

    private val chat = PluginActions.Surface.chat(0, 4242L, null)

    @Test
    fun rowsFollowThePluginListRatherThanTheOrderRegistrationsArrived() {
        val second = startPlugin("second")
        val first = startPlugin("first")
        PluginActions.register(second, second.js, PluginActions.KIND_CHAT, 1, "b")
        PluginActions.register(first, first.js, PluginActions.KIND_CHAT, 1, "a")
        second.answers(1 to "second")
        first.answers(1 to "first")

        assertEquals(listOf("second", "first"), rendered(PluginActions.KIND_CHAT, chat))

        PluginManager.installed = listOf(first, second)
        assertEquals(listOf("first", "second"), rendered(PluginActions.KIND_CHAT, chat))
    }

    @Test
    fun aSecretChatIsNeverRenderedAndNoEngineIsEvenAsked() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 1, "a")
        plugin.answers(1 to "row")

        val secret = PluginActions.Surface.chat(0, DialogObjectIds.ENCRYPTED, null)
        assertEquals(emptyList(), rendered(PluginActions.KIND_CHAT, secret))
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun aRowWhoseEngineIsNoLongerListedDoesNothingWhenTapped() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 7, "a")
        plugin.answers(7 to "row")

        val rows = ArrayList<desu.inugram.core.plugins.ActionRow<QuickJs>>()
        PluginActions.render(PluginActions.KIND_CHAT, chat) { rows.addAll(it) }
        drain()
        assertEquals(1, rows.size)

        PluginManager.installed = emptyList()
        PluginActions.dispatch(rows[0], chat)
        drain()
        assertEquals(0, plugin.js.actionDispatches.size)

        PluginManager.installed = listOf(plugin)
        PluginActions.dispatch(rows[0], chat)
        drain()
        assertEquals(1, plugin.js.actionDispatches.size)
        assertEquals(7, plugin.js.actionDispatches[0].token)
        assertEquals(PluginActions.KIND_CHAT, plugin.js.actionDispatches[0].kind)
    }

    @Test
    fun theSurfaceCrossesUnchangedAndAMessageActionCarriesEveryIdOfTheBubble() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin, plugin.js, PluginActions.KIND_MESSAGE, 1, "a")
        plugin.answers(1 to "row")

        val surface = PluginActions.Surface.message(0, 4242L, 99L, listOf(11, 12, 13))
        rendered(PluginActions.KIND_MESSAGE, surface)

        assertEquals(1, plugin.js.actionRenders.size)
        assertEquals(surface.json, plugin.js.actionRenders[0].second)
        val json = JSONObject(surface.json)
        assertEquals(4242L, json.getLong("dialogId"))
        assertEquals(99L, json.getLong("topicId"))
        assertEquals("[11,12,13]", json.getJSONArray("messageIds").toString())
    }

    @Test
    fun anEngineThatCouldNotAnswerContributesNothingAndTheRestStillDraw() {
        val broken = startPlugin("broken")
        val fine = startPlugin("fine")
        PluginActions.register(broken, broken.js, PluginActions.KIND_CHAT, 1, "a")
        PluginActions.register(fine, fine.js, PluginActions.KIND_CHAT, 1, "b")
        broken.js.onRenderActions = { _, _ -> null }
        fine.answers(1 to "fine")

        assertEquals(listOf("fine"), rendered(PluginActions.KIND_CHAT, chat))
    }

    @Test
    fun theRowCountIsAnswerableWithoutEnteringAnyEngineAndFollowsRegistrations() {
        val plugin = startPlugin("p")
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_CHAT))

        assertNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 1, "a"))
        assertNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 2, "b"))
        assertEquals(2, PluginActions.rowCount(PluginActions.KIND_CHAT))
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_MESSAGE))
        assertTrue(plugin.js.actionRenders.isEmpty())

        PluginActions.unregister(plugin.js, PluginActions.KIND_CHAT, 1)
        assertEquals(1, PluginActions.rowCount(PluginActions.KIND_CHAT))

        PluginActions.detach(plugin.js)
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    /**
     * both numbers come out of `common.d.ts` rather than out of the code they check: a ceiling
     * pinned to the constant the app ships is pinned to a copy of itself, and stays green with the
     * constant raised to its maximum.
     */
    @Test
    fun theCapAndTheRenderBudgetAreTheNumbersTheContractStates() {
        assertEquals(statedNumber(contract(), "{}ms for every plugin's"), PluginActions.RENDER_BUDGET_MS)
        val cap = statedNumber(contract(), "at most {} rows per menu per plugin").toInt()
        val plugin = startPlugin("p")
        for (token in 1..cap) {
            assertNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, token, "row$token"))
        }
        assertNotNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, cap + 1, "over"))
        assertEquals(cap, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    @Test
    fun theNinthRowIsRefusedButUpdatingOneOfTheEightIsNot() {
        val plugin = startPlugin("p")
        for (token in 1..8) {
            assertNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, token, "row$token"))
        }
        assertNotNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 9, "row9"))
        assertEquals(8, PluginActions.rowCount(PluginActions.KIND_CHAT))

        // the engine allocates the replacement's token first and retires the displaced one after,
        // which is what a count-only cap would refuse
        assertNull(PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 10, "row1"))
        PluginActions.unregister(plugin.js, PluginActions.KIND_CHAT, 1)
        assertEquals(8, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    @Test
    fun aDetachedEngineIsNeitherCountedNorAskedNorDispatchedTo() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin, plugin.js, PluginActions.KIND_CHAT, 1, "a")
        plugin.answers(1 to "row")

        PluginActions.detach(plugin.js)
        assertEquals(emptyList(), rendered(PluginActions.KIND_CHAT, chat))
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun anEditorOpNamingAComposerThatIsGoneIsRefusedRatherThanDroppedSilently() {
        val refusal = PluginActions.editorOp(PluginActions.EDITOR_REPLACE, 404L, """{"text":"hi"}""")
        assertEquals("the composer this action came from is gone", refusal)
    }
}

private object DialogObjectIds {
    const val ENCRYPTED = 0x4000000000000000L or 7L
}
