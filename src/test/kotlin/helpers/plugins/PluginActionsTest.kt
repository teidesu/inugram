package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.ui.ActionKey
import desu.inugram.helpers.plugins.ui.ActionRow
import desu.inugram.helpers.plugins.ui.ActionSurface
import desu.inugram.helpers.plugins.ui.MessageActionSource
import desu.inugram.helpers.plugins.ui.PluginActions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

class PluginActionsTest {
    @Before
    fun setUp() = resetBridge()

    private fun Plugin.answers(vararg rows: Pair<Int, String>) {
        js.onRenderActions = { _, _ ->
            rows.joinToString(",", "[", "]") { (token, text) -> """{"token":$token,"text":"$text"}""" }
        }
    }

    private fun rendered(kind: Int, surface: ActionSurface): List<String> {
        val out = ArrayList<String>()
        PluginActions.render(kind, surface) { rows -> out.addAll(rows.map { it.text }) }
        settle()
        return out
    }

    private val chat = ActionSurface.chat(0, 4242L, null)

    @Test
    fun rowsFollowThePluginListRatherThanTheOrderRegistrationsArrived() {
        val second = startPlugin("second")
        val first = startPlugin("first")
        PluginActions.register(second.session!!, PluginActions.KIND_CHAT, 1, "b")
        PluginActions.register(first.session!!, PluginActions.KIND_CHAT, 1, "a")
        second.answers(1 to "second")
        first.answers(1 to "first")

        assertEquals(listOf("second", "first"), rendered(PluginActions.KIND_CHAT, chat))

        setInstalledPlugins(listOf(first, second))
        assertEquals(listOf("first", "second"), rendered(PluginActions.KIND_CHAT, chat))
    }

    @Test
    fun aSecretChatIsNeverRenderedAndNoEngineIsEvenAsked() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 1, "a")
        plugin.answers(1 to "row")

        val secret = ActionSurface.chat(0, DialogObjectIds.ENCRYPTED, null)
        assertEquals(emptyList(), rendered(PluginActions.KIND_CHAT, secret))
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun static_rows_are_returned_without_entering_the_engine() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_CHAT,
            1,
            "a",
            text = "Static",
            icon = "rmsg_pin",
            dynamicFields = 0,
        )

        assertEquals(listOf("Static"), rendered(PluginActions.KIND_CHAT, chat))
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun cached_and_dynamic_rows_are_merged_in_registration_order() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_CHAT,
            1,
            "static",
            text = "Static",
            dynamicFields = 0,
        )
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_CHAT,
            2,
            "dynamic",
            icon = "rmsg_pin",
            dynamicFields = PluginActions.DYNAMIC_TEXT,
        )
        plugin.answers(2 to "Dynamic")

        assertEquals(listOf("Static", "Dynamic"), rendered(PluginActions.KIND_CHAT, chat))
        assertEquals(1, plugin.js.actionRenders.size)
    }

    @Test
    fun settings_ignore_a_dynamic_visibility_getter() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_CHAT,
            1,
            "a",
            text = "Static",
            dynamicFields = PluginActions.DYNAMIC_VISIBLE,
        )

        val rows = ArrayList<ActionRow>()
        PluginActions.renderSettings(PluginActions.KIND_CHAT) { rows.addAll(it) }
        settle()

        assertEquals(listOf("Static"), rows.map { it.text })
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun main_rows_follow_the_configured_order_and_append_unknown_rows() {
        val a = ActionKey("p", PluginActions.KIND_MESSAGE, "a")
        val b = ActionKey("p", PluginActions.KIND_MESSAGE, "b")
        val c = ActionKey("p", PluginActions.KIND_MESSAGE, "c")
        PluginActions.resetSettings(PluginActions.KIND_MESSAGE)
        try {
            PluginActions.setMainOrder(
                PluginActions.KIND_MESSAGE,
                listOf(PluginActions.pluginOrderKey(c), PluginActions.pluginOrderKey(a)),
            )
            assertEquals(listOf(c, a, b), PluginActions.orderMainKeys(PluginActions.KIND_MESSAGE, listOf(a, b, c)))
        } finally {
            PluginActions.resetSettings(PluginActions.KIND_MESSAGE)
        }
    }

    @Test
    fun aRowWhoseEngineIsNoLongerListedDoesNothingWhenTapped() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 7, "a")
        plugin.answers(7 to "row")

        val rows = ArrayList<ActionRow>()
        PluginActions.render(PluginActions.KIND_CHAT, chat) { rows.addAll(it) }
        settle()
        assertEquals(1, rows.size)

        setInstalledPlugins(emptyList())
        PluginActions.dispatch(rows[0], chat)
        settle()
        assertEquals(0, plugin.js.actionDispatches.size)

        setInstalledPlugins(listOf(plugin))
        PluginActions.dispatch(rows[0], chat)
        settle()
        assertEquals(1, plugin.js.actionDispatches.size)
        assertEquals(7, plugin.js.actionDispatches[0].token)
        assertEquals(PluginActions.KIND_CHAT, plugin.js.actionDispatches[0].kind)
    }

    @Test
    fun message_surface_carries_filtered_raw_messages_for_native_wrapping() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_MESSAGE,
            1,
            "a",
            PluginActions.MESSAGE_PLACEMENT_SELECTION,
        )
        plugin.answers(1 to "row")

        val surface = ActionSurface.message(
            0,
            4242L,
            99L,
            MessageActionSource.SELECTION,
            listOf(
                TLRPC.TL_message().apply {
                    id = 11
                    date = 1
                    message = "one"
                    dialog_id = 4242L
                    grouped_id = 7L
                }.synced(),
                TLRPC.TL_message().apply {
                    id = 12
                    date = 2
                    message = "two"
                    dialog_id = 4242L
                    grouped_id = 7L
                }.synced(),
                TLRPC.TL_message().apply {
                    id = 13
                    date = 3
                    message = "three"
                    dialog_id = -99L
                }.synced(),
            ),
        )
        rendered(PluginActions.KIND_MESSAGE, surface)

        assertEquals(1, plugin.js.actionRenders.size)
        val json = JSONObject(plugin.js.actionRenders[0].second)
        assertEquals(4242L, json.getLong("dialogId"))
        assertEquals(99L, json.getLong("topicId"))
        assertEquals("selection", json.getString("source"))
        val messages = json.getJSONArray("messages")
        assertEquals(listOf(11, 12, 13), (0 until messages.length()).map { messages.getJSONObject(it).getInt("id") })
        assertEquals("7", messages.getJSONObject(0).getString("grouped_id"))
        assertEquals("-99", messages.getJSONObject(2).getString("dialog_id"))
    }

    @Test
    fun anEngineThatCouldNotAnswerContributesNothingAndTheRestStillDraw() {
        val broken = startPlugin("broken")
        val fine = startPlugin("fine")
        PluginActions.register(broken.session!!, PluginActions.KIND_CHAT, 1, "a")
        PluginActions.register(fine.session!!, PluginActions.KIND_CHAT, 1, "b")
        broken.js.onRenderActions = { _, _ -> null }
        fine.answers(1 to "fine")

        assertEquals(listOf("fine"), rendered(PluginActions.KIND_CHAT, chat))
    }

    @Test
    fun theRowCountIsAnswerableWithoutEnteringAnyEngineAndFollowsRegistrations() {
        val plugin = startPlugin("p")
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_CHAT))

        assertNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 1, "a"))
        assertNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 2, "b"))
        assertEquals(2, PluginActions.rowCount(PluginActions.KIND_CHAT))
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_MESSAGE))
        assertTrue(plugin.js.actionRenders.isEmpty())

        PluginActions.unregister(plugin.js, PluginActions.KIND_CHAT, 1)
        assertEquals(1, PluginActions.rowCount(PluginActions.KIND_CHAT))

        PluginActions.detach(plugin.js)
        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    @Test
    fun message_placements_have_independent_counts() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_MESSAGE,
            1,
            "selection",
            PluginActions.MESSAGE_PLACEMENT_SELECTION,
        )

        assertEquals(0, PluginActions.rowCount(PluginActions.KIND_MESSAGE))
        assertEquals(
            1,
            PluginActions.rowCount(PluginActions.KIND_MESSAGE, PluginActions.MESSAGE_PLACEMENT_SELECTION),
        )
    }

    @Test
    fun message_settings_include_selection_only_rows() {
        val plugin = startPlugin("p")
        PluginActions.register(
            plugin.session!!,
            PluginActions.KIND_MESSAGE,
            1,
            "selection",
            PluginActions.MESSAGE_PLACEMENT_SELECTION,
        )
        plugin.answers(1 to "selection")

        val rows = ArrayList<ActionRow>()
        PluginActions.renderSettings(PluginActions.KIND_MESSAGE) { rows.addAll(it) }
        settle()

        assertEquals(listOf("selection"), rows.map { it.text })
    }

    @Test
    fun the_action_row_cap_is_enforced() {
        val cap = 8
        val plugin = startPlugin("p")
        for (token in 1..cap) {
            assertNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, token, "row$token"))
        }
        assertNotNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, cap + 1, "over"))
        assertEquals(cap, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    @Test
    fun theNinthRowIsRefusedButUpdatingOneOfTheEightIsNot() {
        val plugin = startPlugin("p")
        for (token in 1..8) {
            assertNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, token, "row$token"))
        }
        assertNotNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 9, "row9"))
        assertEquals(8, PluginActions.rowCount(PluginActions.KIND_CHAT))

        // the engine allocates the replacement's token first and retires the displaced one after,
        // which is what a count-only cap would refuse
        assertNull(PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 10, "row1"))
        PluginActions.unregister(plugin.js, PluginActions.KIND_CHAT, 1)
        assertEquals(8, PluginActions.rowCount(PluginActions.KIND_CHAT))
    }

    @Test
    fun aDetachedEngineIsNeitherCountedNorAskedNorDispatchedTo() {
        val plugin = startPlugin("p")
        PluginActions.register(plugin.session!!, PluginActions.KIND_CHAT, 1, "a")
        plugin.answers(1 to "row")

        PluginActions.detach(plugin.js)
        assertEquals(emptyList(), rendered(PluginActions.KIND_CHAT, chat))
        assertEquals(0, plugin.js.actionRenders.size)
    }

    @Test
    fun anEditorOpNamingAComposerThatIsGoneIsRefusedRatherThanDroppedSilently() {
        val refusal = PluginActions.editorOp(PluginActions.EDITOR_REPLACE, 404L, """{"text":"hi"}""")
        assertPluginError("handle-expired", refusal)
    }
}

private object DialogObjectIds {
    const val ENCRYPTED = 0x4000000000000000L or 7L
}
