package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

class PluginRpcUpdatesTest {
    @Before
    fun setUp() = resetBridge()

    private fun listener(vararg types: String): Plugin {
        val plugin = startPlugin("p", *types.map { "onUpdate($it)" }.toTypedArray())
        assertNull(plugin.onUpdate(*types))
        return plugin
    }

    @Test
    fun an_update_reaches_a_plugin_that_named_its_constructor_read_only_with_its_class_and_scalars() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(createUpdatesBatch(update))
        drain()

        assertEquals("updateNewMessage", plugin.js.updates.single().typeName)
        val wire = plugin.js.updates.single().updateWire
        val id = handleId(wire)
        val tl = plugin.session!!.tl
        assertEquals(
            PluginWire.encodeHandle(vector = false, id = id, readOnly = true, projection = tl.project(id), classId = tl.getClassId(update.javaClass)),
            wire,
        )
    }

    @Test
    fun a_re_fed_batch_does_not_deliver_its_updates_twice() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(createUpdatesBatch(update))
        drain()
        // stock re-feeds a parked batch around the same instances
        deliverUpdates(createUpdatesBatch(update))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun the_difference_catch_up_delivers_its_bare_messages_once_as_the_update_the_server_would_have_sent() {
        val plugin = listener("updateNewMessage", "updateNewChannelMessage")
        val direct = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val inChannel = TLRPC.TL_message().apply { id = 2; peer_id = peerChannel(9L) }.synced()

        deliverDifference(listOf(direct, inChannel))
        drain()
        // the wrapper is synthesised per pass, so dedup has to key on the message
        deliverDifference(listOf(direct, inChannel))
        drain()

        assertEquals(
            listOf("updateNewMessage", "updateNewChannelMessage"),
            plugin.js.updates.map { it.typeName },
        )
    }

    @Test
    fun the_difference_s_other_updates_are_delivered_as_they_are() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        deliverDifference(otherUpdates = listOf(update))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun a_message_the_difference_reports_as_a_hole_is_skipped_as_stock_skips_it() {
        val plugin = listener("updateNewMessage")

        deliverDifference(listOf(TLRPC.TL_messageEmpty().apply { id = 1 }))
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun a_service_notification_is_never_delivered_without_unsafe_disableapifiltering() {
        val plugin = listener("updateServiceNotification")

        deliverUpdates(createUpdatesBatch(TL_update.TL_updateServiceNotification()))
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun a_secret_chat_update_is_never_delivered_whatever_the_plugin_holds() {
        val plugin = startPlugin(
            "p",
            "onUpdate(updateNewEncryptedMessage,updateEncryption,updateEncryptedChatTyping,updateEncryptedMessagesRead)",
            "unsafe.disableApiFiltering",
        )
        val types = arrayOf(
            "updateNewEncryptedMessage",
            "updateEncryption",
            "updateEncryptedChatTyping",
            "updateEncryptedMessagesRead",
        )
        assertNull(plugin.onUpdate(*types))

        deliverUpdates(
            createUpdatesBatch(
                TL_update.TL_updateNewEncryptedMessage().apply {
                    message = TLRPC.TL_encryptedMessage().apply { chat_id = 7; bytes = ByteArray(4) }
                },
                TL_update.TL_updateEncryption(),
                TL_update.TL_updateEncryptedChatTyping(),
                TL_update.TL_updateEncryptedMessagesRead(),
            ),
        )
        drain()

        assertEquals(0, plugin.js.updates.size)

        val listening = listener("updateNewMessage")
        deliverUpdates(createUpdatesBatch(TL_update.TL_updateEncryption(), newMessage(2)))
        drain()
        assertEquals(1, listening.js.updates.size)
        assertEquals("updateNewMessage", listening.js.updates[0].typeName)
    }

    @Test
    fun an_update_carrying_a_draft_hides_it_from_a_plugin_without_the_draft_scope() {
        // an arrival is dispatched once per instance
        val draftUpdate = {
            TL_update.TL_updateDraftMessage().apply {
                peer = peerUser(7L)
                draft = TLRPC.TL_draftMessage().apply { message = "unsent" }.synced()
            }.synced()
        }

        val plugin = listener("updateDraftMessage")
        deliverUpdates(createUpdatesBatch(draftUpdate()))
        drain()
        val wire = plugin.js.updates.single().updateWire
        assertEquals("N", plugin.tl().tlGet(handleId(wire), "draft"))

        val allowed = startPlugin("drafts", "onUpdate(updateDraftMessage)", "account.read(draft)")
        assertNull(allowed.onUpdate("updateDraftMessage"))
        deliverUpdates(createUpdatesBatch(draftUpdate()))
        drain()
        val seen = allowed.js.updates.single().updateWire
        val draftWire = allowed.tl().tlGet(handleId(seen), "draft")
        assertEquals("unsent", decodeString(allowed.tl().tlGet(handleId(draftWire), "message")))
    }

    // the app never reaches this state; only a forged scope can observe the dispatch-time check
    @Test
    fun the_fan_out_re_checks_the_grant_the_registration_was_authorized_under() {
        val plugin = listener("updateNewMessage")
        forgeGrantScope(plugin, "updateNewMessage", "updateUserTyping")

        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun forgeGrantScope(plugin: Plugin, type: String, scope: String) {
        val byType = PluginUpdates::class.java.getDeclaredField("updateListenersByType")
            .apply { isAccessible = true }
            .get(PluginUpdates) as Map<String, List<Any>>
        val listener = byType[type]!!.single { entry ->
            entry.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(entry) === plugin.session
        }
        val scopes = listener.javaClass.getDeclaredField("grantScopes")
            .apply { isAccessible = true }
            .get(listener) as MutableSet<String>
        scopes.clear()
        scopes.add(scope)
    }

    // `updateShortMessage.user_id` is the other party whichever way the message went
    @Test
    fun an_outgoing_short_message_is_rebuilt_with_you_as_its_sender() {
        TestApp.signIn(0, id = 4242L)
        val plugin = listener("updateNewMessage")

        deliverUpdates(
            TLRPC.TL_updateShortMessage().apply {
                id = 5
                user_id = 777000L
                out = true
                message = "Login code: 12345"
            },
        )
        drain()

        val wire = plugin.js.updates.single().updateWire
        val messageWire = plugin.tl().tlGet(handleId(wire), "message")
        val sender = plugin.tl().tlGet(handleId(messageWire), "from_id")
        assertEquals(PluginWire.Value.IntNum(4242), PluginWire.decode(plugin.tl().tlGet(handleId(sender), "user_id")))
        assertEquals(
            "Login code: 12345",
            decodeString(plugin.tl().tlGet(handleId(messageWire), "message")),
            "your own text is not the service account's to redact",
        )
    }

    @Test
    fun registering_for_an_update_type_no_layer_defines_is_refused() {
        val plugin = startPlugin("p", "onUpdate")

        assertPluginError("unknown-constructor", plugin.onUpdate("updateNoSuchThing"))
    }

    @Test
    fun a_registration_needs_its_own_vocabulary_s_grant() {
        assertPluginError("not-granted", startPlugin("a", "onUpdate(updateUserTyping)").onUpdate("updateNewMessage"))
        assertPluginError(
            "not-granted",
            startPlugin("b", "onUpdate(updateNewMessage,updateNewChannelMessage)").onDemuxedEvent(DemuxedEvent.NEW_MESSAGE),
        )
        assertPluginError("not-granted", startPlugin("c", "onUpdate(new_message)").onUpdate("updateNewMessage"))
    }

    private fun demuxed(event: DemuxedEvent, vararg grants: String): Plugin {
        val plugin = startPlugin("p", *grants)
        assertNull(plugin.onDemuxedEvent(event))
        return plugin
    }

    @Test
    fun a_demuxed_registration_is_gated_on_its_event_not_on_the_constructors_behind_it() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()

        assertEquals(listOf("updateNewMessage"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun an_event_only_reaches_the_plugins_whose_demuxed_registration_covers_its_constructor() {
        val deletes = demuxed(DemuxedEvent.DELETE_MESSAGE, "onUpdate")
        val edits = startPlugin("q", "onUpdate")
        assertNull(edits.onDemuxedEvent(DemuxedEvent.EDIT_MESSAGE))

        deliverUpdates(createUpdatesBatch(TL_update.TL_updateDeleteChannelMessages()))
        drain()

        assertEquals(listOf("updateDeleteChannelMessages"), deletes.js.updates.map { it.typeName })
        assertEquals(0, edits.js.updates.size)
    }

    @Test
    fun a_plugin_holding_both_forms_is_dispatched_once_for_one_arrival() {
        val plugin = startPlugin("p", "onUpdate")
        assertNull(plugin.onUpdate("updateNewMessage", callbackId = 1))
        assertNull(plugin.onDemuxedEvent(DemuxedEvent.NEW_MESSAGE, callbackId = 2))

        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    // a reconnect: the live batch lands, then `getDifference` reports the same objects
    @Test
    fun one_arrival_is_delivered_once_across_both_dispatch_sites() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(createUpdatesBatch(update))
        drain()
        deliverDifference(otherUpdates = listOf(update))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun disposing_a_demuxed_registration_stops_its_dispatches() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        plugin.js.listener!!.onUpdateUnregister(1)
        deliverUpdates(createUpdatesBatch(newMessage(1)))
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun a_compressed_short_form_is_rebuilt_into_updatenewmessage_once() {
        val plugin = listener("updateNewMessage")
        val short = TLRPC.TL_updateShortMessage().apply {
            id = 5
            user_id = 42L
            message = "hi"
        }

        deliverUpdates(short)
        drain()
        // each re-feed pass mints a new normalized update, so dedup keys on the short form
        deliverUpdates(short)
        drain()

        assertEquals(listOf("updateNewMessage"), plugin.js.updates.map { it.typeName })
    }
}
