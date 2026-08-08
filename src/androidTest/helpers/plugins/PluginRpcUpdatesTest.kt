package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tg.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

/** the `onUpdate` fan-out, both dispatch sites: `processUpdates` and the difference catch-up. */
class PluginRpcUpdatesTest {
    @Before
    fun setUp() = resetBridge()

    private fun listener(vararg types: String): Plugin {
        val plugin = startPlugin("p", *types.map { "onUpdate($it)" }.toTypedArray())
        assertNull(plugin.onUpdate(*types))
        return plugin
    }

    private fun newMessage(id: Int, peer: TLRPC.Peer = peerUser(7L)): TL_update.TL_updateNewMessage =
        TL_update.TL_updateNewMessage().apply {
            message = TLRPC.TL_message().apply {
                this.id = id
                peer_id = peer
                message = "m$id"
            }.synced()
        }

    private fun batchOf(vararg updates: TLRPC.Update): TLRPC.TL_updates =
        TLRPC.TL_updates().apply { this.updates = ArrayList(updates.toList()) }

    @Test
    fun an_update_in_a_batch_reaches_a_plugin_that_named_its_constructor() {
        val plugin = listener("updateNewMessage")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
        assertEquals("updateNewMessage", plugin.js.updates[0].typeName)
        assertTrue(handleOf(plugin.js.updates[0].updateWire).readOnly, "an app-owned object is never writable")
    }

    @Test
    fun a_plugin_that_named_another_constructor_gets_nothing() {
        val plugin = listener("updateUserTyping")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun a_re_fed_batch_does_not_deliver_its_updates_twice() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        deliverUpdates(batchOf(update), 0)
        drain()
        // stock parks a batch whose pts does not line up and re-feeds it around the *same* instances
        deliverUpdates(batchOf(update), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun the_difference_catch_up_delivers_its_bare_messages_as_the_update_the_server_would_have_sent() {
        val plugin = listener("updateNewMessage", "updateNewChannelMessage")
        val direct = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val inChannel = TLRPC.TL_message().apply { id = 2; peer_id = peerChannel(9L) }.synced()

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
    fun the_two_dispatch_sites_cannot_deliver_one_arrival_twice() {
        val plugin = listener("updateNewMessage")
        val message = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()

        deliverDifference(listOf(message))
        drain()
        // the wrapper is one we synthesised, so identity dedup has to key on the message itself
        deliverDifference(listOf(message))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun a_service_notification_is_never_delivered_without_unsafe_disableapifiltering() {
        val plugin = listener("updateServiceNotification")

        deliverUpdates(batchOf(TL_update.TL_updateServiceNotification()), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    /**
     * `common.d.ts` on `onUpdate`: "the one thing none of them carries is a secret chat, which never
     * reaches plugin code at all". unlike the takeover rules this one has no bypass, so the plugin
     * here holds the grant that lifts those.
     */
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
            batchOf(
                TL_update.TL_updateNewEncryptedMessage().apply {
                    message = TLRPC.TL_encryptedMessage().apply { chat_id = 7; bytes = ByteArray(4) }
                },
                TL_update.TL_updateEncryption(),
                TL_update.TL_updateEncryptedChatTyping(),
                TL_update.TL_updateEncryptedMessagesRead(),
            ),
            0,
        )
        drain()

        assertEquals(0, plugin.js.updates.size)
        // the same batch's ordinary update still arrives, so this is a filter and not a dead fan-out
        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()
        assertEquals(0, plugin.js.updates.size, "and that plugin named no ordinary constructor")

        val listening = listener("updateNewMessage")
        deliverUpdates(batchOf(TL_update.TL_updateEncryption(), newMessage(2)), 0)
        drain()
        assertEquals(1, listening.js.updates.size)
        assertEquals("updateNewMessage", listening.js.updates[0].typeName)
    }

    /** the same rule one materialization away: it is on the field, not on `Account.getDraft` */
    @Test
    fun an_update_carrying_a_draft_hides_it_from_a_plugin_without_the_draft_scope() {
        // one instance per delivery: an arrival is dispatched once, by object identity
        val draftUpdate = {
            TL_update.TL_updateDraftMessage().apply {
                peer = peerUser(7L)
                draft = TLRPC.TL_draftMessage().apply { message = "unsent" }.synced()
            }.synced()
        }

        val plugin = listener("updateDraftMessage")
        deliverUpdates(batchOf(draftUpdate()), 0)
        drain()
        val wire = plugin.js.updates.single().updateWire
        assertEquals("N", plugin.tl().tlGet(handleId(wire), "draft"))

        val allowed = startPlugin("drafts", "onUpdate(updateDraftMessage)", "account.read(draft)")
        assertNull(allowed.onUpdate("updateDraftMessage"))
        deliverUpdates(batchOf(draftUpdate()), 0)
        drain()
        val seen = allowed.js.updates.single().updateWire
        val draftWire = allowed.tl().tlGet(handleId(seen), "draft")
        assertEquals("unsent", stringOf(allowed.tl().tlGet(handleId(draftWire), "message")))
    }

    /**
     * the fan-out asks again, over the scopes that actually authorized this plugin for this
     * constructor. Nothing in the app reaches the state below - a registration that got past
     * [PluginRpc.registerUpdates] holds a scope its plugin has - which is exactly why the second
     * gate has no other way to be observed, and why it is worth having: it is what makes the
     * dispatch depend on the permissions rather than on a list built from them once.
     */
    @Test
    fun the_fan_out_re_checks_the_grant_the_registration_was_authorized_under() {
        val plugin = listener("updateNewMessage")
        forgeGrantScope(plugin, "updateNewMessage", "updateUserTyping")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    /**
     * rewrites the scope a published [PluginRpc.UpdateListener] remembers. Reflection for the same
     * reason [resetBridge] uses it: the bridge must not grow a seam the app has no use for.
     */
    @Suppress("UNCHECKED_CAST")
    private fun forgeGrantScope(plugin: Plugin, type: String, scope: String) {
        val byType = PluginRpc::class.java.getDeclaredField("updateListenersByType")
            .apply { isAccessible = true }
            .get(PluginRpc) as Map<String, List<Any>>
        val listener = byType[type]!!.single { entry ->
            entry.javaClass.getDeclaredField("plugin").apply { isAccessible = true }.get(entry) === plugin
        }
        val scopes = listener.javaClass.getDeclaredField("grantScopes")
            .apply { isAccessible = true }
            .get(listener) as MutableSet<String>
        scopes.clear()
        scopes.add(scope)
    }

    /**
     * `updateShortMessage` carries the *other* party's id in `user_id` whichever way the message
     * went, so a message you sent to the service account would otherwise be rebuilt with 777000 as
     * its sender: every plugin would read the service account as the author of your own text, and
     * the redaction keyed on that sender would star out what you typed.
     */
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
            0,
        )
        drain()

        val wire = plugin.js.updates.single().updateWire
        val messageWire = plugin.tl().tlGet(handleId(wire), "message")
        val sender = plugin.tl().tlGet(handleId(messageWire), "from_id")
        // a TL long crosses as a string, js numbers not reaching that far
        assertEquals("4242", stringOf(plugin.tl().tlGet(handleId(sender), "user_id")))
        assertEquals(
            "Login code: 12345",
            stringOf(plugin.tl().tlGet(handleId(messageWire), "message")),
            "your own text is not the service account's to redact",
        )
    }

    @Test
    fun registering_for_an_update_type_no_layer_defines_is_refused() {
        val plugin = startPlugin("p", "onUpdate")

        assertPluginError("unknown-constructor", plugin.onUpdate("updateNoSuchThing"))
    }

    @Test
    fun registering_for_an_ungranted_update_type_is_refused() {
        val plugin = startPlugin("p", "onUpdate(updateUserTyping)")

        assertPluginError("not-granted", plugin.onUpdate("updateNewMessage"))
    }

    //
    // they are registrations on this very stream, so what has to be proved host-side is that the
    // grant vocabulary is separate and that the dedup covers them: the narrowing itself is the
    // engine's (`rpc.rs`, `events.js`).

    private fun demuxed(event: DemuxedEvent, vararg grants: String): Plugin {
        val plugin = startPlugin("p", *grants)
        assertNull(plugin.onDemuxedEvent(event))
        return plugin
    }

    @Test
    fun a_demuxed_registration_is_gated_on_its_event_not_on_the_constructors_behind_it() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(listOf("updateNewMessage"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun the_constructor_grant_does_not_buy_the_demuxed_event() {
        val plugin = startPlugin("p", "onUpdate(updateNewMessage,updateNewChannelMessage)")

        assertPluginError("not-granted", plugin.onDemuxedEvent(DemuxedEvent.NEW_MESSAGE))
    }

    @Test
    fun the_demuxed_grant_does_not_buy_the_raw_stream() {
        val plugin = startPlugin("p", "onUpdate(new_message)")

        assertPluginError("not-granted", plugin.onUpdate("updateNewMessage"))
    }

    @Test
    fun an_unscoped_onupdate_grant_covers_a_demuxed_registration() {
        val plugin = demuxed(DemuxedEvent.DELETE_MESSAGE, "onUpdate")

        deliverUpdates(batchOf(TL_update.TL_updateDeleteMessages()), 0)
        drain()

        assertEquals(listOf("updateDeleteMessages"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun an_event_only_reaches_the_plugins_whose_demuxed_registration_covers_its_constructor() {
        val deletes = demuxed(DemuxedEvent.DELETE_MESSAGE, "onUpdate")
        val edits = startPlugin("q", "onUpdate")
        assertNull(edits.onDemuxedEvent(DemuxedEvent.EDIT_MESSAGE))

        deliverUpdates(batchOf(TL_update.TL_updateDeleteChannelMessages()), 0)
        drain()

        assertEquals(listOf("updateDeleteChannelMessages"), deletes.js.updates.map { it.typeName })
        assertEquals(0, edits.js.updates.size)
    }

    /**
     * the dispatch that matters most: one plugin holding both forms is handed one materialized
     * update, and its engine fans out to both handlers from it - two dispatches would mint two
     * handles over the app's own object and deliver the demuxed event twice.
     */
    @Test
    fun a_plugin_holding_both_forms_is_dispatched_once_for_one_arrival() {
        val plugin = startPlugin("p", "onUpdate")
        assertNull(plugin.onUpdate("updateNewMessage", callbackId = 1))
        assertNull(plugin.onDemuxedEvent(DemuxedEvent.NEW_MESSAGE, callbackId = 2))

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun a_demuxed_registration_sees_a_live_arrival_and_its_re_fed_batch_once() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val update = newMessage(1)

        deliverUpdates(batchOf(update), 0)
        drain()
        deliverUpdates(batchOf(update), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    /** the path a plugin that was running while the app was offline gets its messages on */
    @Test
    fun a_demuxed_registration_sees_the_difference_catch_up_too_and_not_twice() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val direct = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val inChannel = TLRPC.TL_message().apply { id = 2; peer_id = peerChannel(9L) }.synced()

        deliverDifference(listOf(direct, inChannel))
        drain()
        deliverDifference(listOf(direct, inChannel))
        drain()

        assertEquals(
            listOf("updateNewMessage", "updateNewChannelMessage"),
            plugin.js.updates.map { it.typeName },
        )
    }

    /**
     * the same arrival reaching both dispatch sites, which is what a reconnect does: the live batch
     * lands, then `getDifference` reports the very same objects
     */
    @Test
    fun a_demuxed_registration_sees_one_arrival_once_across_both_dispatch_sites() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val update = newMessage(1)

        deliverUpdates(batchOf(update), 0)
        drain()
        deliverDifference(otherUpdates = listOf(update))
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun the_compressed_short_form_reaches_a_demuxed_registration_as_updatenewmessage() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val short = TLRPC.TL_updateShortMessage().apply {
            id = 5
            user_id = 42L
            message = "hi"
        }

        deliverUpdates(short, 0)
        drain()
        // stock re-feeds a parked batch around the same `Updates`, which mints a *different*
        // normalized update every pass - the dedup has to key on what it was unpacked from
        deliverUpdates(short, 0)
        drain()

        assertEquals(listOf("updateNewMessage"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun disposing_a_demuxed_registration_stops_its_dispatches() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        plugin.js.listener!!.onUpdateUnregister(1)
        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    /** every constructor a demuxed event registers for has to be one some layer actually defines */
    @Test
    fun the_demuxed_events_name_real_update_constructors() {
        for (event in DemuxedEvent.entries) {
            for (type in event.types) {
                assertTrue(type in TlCtorIds.updateNames, "$type is not an update constructor")
            }
        }
    }

    @Test
    fun the_compressed_short_forms_are_rebuilt_into_updatenewmessage() {
        val plugin = listener("updateNewMessage")
        val short = TLRPC.TL_updateShortMessage().apply {
            id = 5
            user_id = 42L
            message = "hi"
        }

        deliverUpdates(short, 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
        assertEquals("updateNewMessage", plugin.js.updates[0].typeName)
    }
}
