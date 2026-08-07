package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlCtorIds
import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tg.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.UserConfig
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
    fun `an update in a batch reaches a plugin that named its constructor`() {
        val plugin = listener("updateNewMessage")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
        assertEquals("updateNewMessage", plugin.js.updates[0].typeName)
        assertTrue(handleOf(plugin.js.updates[0].updateWire).readOnly, "an app-owned object is never writable")
    }

    @Test
    fun `a plugin that named another constructor gets nothing`() {
        val plugin = listener("updateUserTyping")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun `a re-fed batch does not deliver its updates twice`() {
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
    fun `the difference catch-up delivers its bare messages as the update the server would have sent`() {
        val plugin = listener("updateNewMessage", "updateNewChannelMessage")
        val direct = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val inChannel = TLRPC.TL_message().apply { id = 2; peer_id = peerChannel(9L) }.synced()

        PluginRpc.onDifference(listOf(direct, inChannel), emptyList(), 0)
        drain()

        assertEquals(
            listOf("updateNewMessage", "updateNewChannelMessage"),
            plugin.js.updates.map { it.typeName },
        )
    }

    @Test
    fun `the difference's other_updates are delivered as they are`() {
        val plugin = listener("updateNewMessage")
        val update = newMessage(1)

        PluginRpc.onDifference(emptyList(), listOf(update), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun `a message the difference reports as a hole is skipped, as stock skips it`() {
        val plugin = listener("updateNewMessage")

        PluginRpc.onDifference(listOf(TLRPC.TL_messageEmpty().apply { id = 1 }), emptyList(), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    @Test
    fun `the two dispatch sites cannot deliver one arrival twice`() {
        val plugin = listener("updateNewMessage")
        val message = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()

        PluginRpc.onDifference(listOf(message), emptyList(), 0)
        drain()
        // the wrapper is one we synthesised, so identity dedup has to key on the message itself
        PluginRpc.onDifference(listOf(message), emptyList(), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun `a service notification is never delivered without unsafe disableApiFiltering`() {
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
    fun `a secret chat update is never delivered, whatever the plugin holds`() {
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
    fun `an update carrying a draft hides it from a plugin without the draft scope`() {
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
    fun `the fan-out re-checks the grant the registration was authorized under`() {
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
    fun `an outgoing short message is rebuilt with you as its sender`() {
        UserConfig.getInstance(0).clientUserId = 4242L
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
    fun `registering for an update type no layer defines is refused`() {
        val plugin = startPlugin("p", "onUpdate")

        assertPluginError("unknown-constructor", plugin.onUpdate("updateNoSuchThing"))
    }

    @Test
    fun `registering for an ungranted update type is refused`() {
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
    fun `a demuxed registration is gated on its event, not on the constructors behind it`() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(listOf("updateNewMessage"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun `the constructor grant does not buy the demuxed event`() {
        val plugin = startPlugin("p", "onUpdate(updateNewMessage,updateNewChannelMessage)")

        assertPluginError("not-granted", plugin.onDemuxedEvent(DemuxedEvent.NEW_MESSAGE))
    }

    @Test
    fun `the demuxed grant does not buy the raw stream`() {
        val plugin = startPlugin("p", "onUpdate(new_message)")

        assertPluginError("not-granted", plugin.onUpdate("updateNewMessage"))
    }

    @Test
    fun `an unscoped onUpdate grant covers a demuxed registration`() {
        val plugin = demuxed(DemuxedEvent.DELETE_MESSAGE, "onUpdate")

        deliverUpdates(batchOf(TL_update.TL_updateDeleteMessages()), 0)
        drain()

        assertEquals(listOf("updateDeleteMessages"), plugin.js.updates.map { it.typeName })
    }

    @Test
    fun `an event only reaches the plugins whose demuxed registration covers its constructor`() {
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
    fun `a plugin holding both forms is dispatched once for one arrival`() {
        val plugin = startPlugin("p", "onUpdate")
        assertNull(plugin.onUpdate("updateNewMessage", callbackId = 1))
        assertNull(plugin.onDemuxedEvent(DemuxedEvent.NEW_MESSAGE, callbackId = 2))

        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun `a demuxed registration sees a live arrival and its re-fed batch once`() {
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
    fun `a demuxed registration sees the difference catch-up too, and not twice`() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val direct = TLRPC.TL_message().apply { id = 1; peer_id = peerUser(7L) }.synced()
        val inChannel = TLRPC.TL_message().apply { id = 2; peer_id = peerChannel(9L) }.synced()

        PluginRpc.onDifference(listOf(direct, inChannel), emptyList(), 0)
        drain()
        PluginRpc.onDifference(listOf(direct, inChannel), emptyList(), 0)
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
    fun `a demuxed registration sees one arrival once across both dispatch sites`() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")
        val update = newMessage(1)

        deliverUpdates(batchOf(update), 0)
        drain()
        PluginRpc.onDifference(emptyList(), listOf(update), 0)
        drain()

        assertEquals(1, plugin.js.updates.size)
    }

    @Test
    fun `the compressed short form reaches a demuxed registration as updateNewMessage`() {
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
    fun `disposing a demuxed registration stops its dispatches`() {
        val plugin = demuxed(DemuxedEvent.NEW_MESSAGE, "onUpdate(new_message)")

        plugin.js.rpcListener!!.onUpdateUnregister(1)
        deliverUpdates(batchOf(newMessage(1)), 0)
        drain()

        assertEquals(0, plugin.js.updates.size)
    }

    /** every constructor a demuxed event registers for has to be one some layer actually defines */
    @Test
    fun `the demuxed events name real update constructors`() {
        for (event in DemuxedEvent.entries) {
            for (type in event.types) {
                assertTrue(type in TlCtorIds.updateNames, "$type is not an update constructor")
            }
        }
    }

    @Test
    fun `the compressed short forms are rebuilt into updateNewMessage`() {
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
