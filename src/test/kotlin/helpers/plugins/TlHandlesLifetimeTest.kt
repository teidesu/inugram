package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.TLRPC

/** handle lifetime, mode inheritance and per-plugin isolation - the rules `tl_proxy.rs` relies on. */
class TlHandlesLifetimeTest {
    /** nothing here is about what a plugin may see, so every view is built with both rules off */
    private val UNFILTERED = TlFilter.Policy(takeover = false, drafts = true)

    @Before
    fun setUp() = resetBridge()

    private fun message(): TLRPC.TL_message = TLRPC.TL_message().apply {
        id = 1
        message = "hi"
        from_id = peerUser(42L)
        peer_id = peerUser(43L)
    }.synced()

    @Test
    fun off_thread_release_waits_for_the_handle_table_queue() {
        val handles = TlHandles(UNFILTERED)
        val root = handles.mintForPlugin(message(), readOnly = true)
        val caller = Thread { handles.tlRelease(root) }
        caller.start()
        caller.join(5000)
        assertTrue(!caller.isAlive)
        assertEquals(1L, (PluginWire.decode(handles.tlGet(root, "id")) as PluginWire.Value.IntNum).value)
        drain()
        assertPluginError("handle-expired", handles.tlGet(root, "id"))
    }

    @Test
    fun releasing_a_scope_invalidates_every_handle_minted_under_it_children_included() {
        val handles = TlHandles(UNFILTERED)
        val scope = TlHandles.newScope()
        val root = handles.mintForScope(message(), scope)
        val child = handleId(handles.tlGet(root, "from_id"))

        handles.releaseScope(scope)

        assertPluginError("handle-expired", handles.tlGet(root, "id"))
        assertPluginError("handle-expired", handles.tlGet(child, "user_id"))
    }

    @Test
    fun a_plugin_lifetime_handle_survives_a_scope_release() {
        val handles = TlHandles(UNFILTERED)
        val scope = TlHandles.newScope()
        val owned = handles.mintForPlugin(message(), readOnly = true)
        handles.mintForScope(message(), scope)

        handles.releaseScope(scope)

        assertEquals(1L, (PluginWire.decode(handles.tlGet(owned, "id")) as PluginWire.Value.IntNum).value)
    }

    @Test
    fun a_read_only_view_refuses_writes_and_hands_out_read_only_children() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForPlugin(target, readOnly = true)

        assertPluginError("forbidden", handles.tlSet(root, "message", PluginWire.encodeJson("\"x\"")))
        assertEquals("hi", target.message)

        val child = handleOf(handles.tlGet(root, "from_id"))
        assertTrue(child.readOnly)
        assertPluginError("forbidden", handles.tlSet(child.id, "user_id", PluginWire.encodeJson("1")))
    }

    @Test
    fun a_read_only_handle_coming_back_over_the_bridge_is_refused_as_a_value() {
        val handles = TlHandles(UNFILTERED)
        val writable = handles.mintForScope(message(), TlHandles.newScope())
        val appOwned = handles.mintForPlugin(peerUser(9L), readOnly = true)

        // assigning it would re-mint it writable on the next read of that field
        val refusal = handles.tlSet(writable, "from_id", PluginWire.encodeHandle(false, appOwned, readOnly = true))

        assertPluginError("forbidden", refusal)
        assertTrue(handles.isReadOnly(appOwned))
    }

    @Test
    fun a_handle_id_does_not_resolve_in_another_plugin_s_table() {
        val mine = TlHandles(UNFILTERED)
        val theirs = TlHandles(UNFILTERED)
        val secret = theirs.mintForPlugin(message(), readOnly = true)

        // a plugin can forge the marker symbol, so it can hand any integer back over the bridge
        assertNull(mine.resolveTlObject(secret))
        assertPluginError("handle-expired", mine.tlGet(secret, "message"))
        assertNotNull(theirs.resolveTlObject(secret))
    }

    @Test
    fun a_vector_can_shrink_but_never_grow_past_its_end() {
        val handles = TlHandles(UNFILTERED)
        val request = TLRPC.TL_users_getUsers().apply {
            id = arrayListOf(TLRPC.TL_inputUserSelf(), TLRPC.TL_inputUserSelf())
        }
        val root = handles.mintForScope(request, TlHandles.newScope())
        val vector = handleId(handles.tlGet(root, "id"))

        assertEquals("vector length can only shrink (2 -> 5 not allowed)", handles.tlSet(vector, "length", PluginWire.encodeJson("5")))
        assertNull(handles.tlSet(vector, "length", PluginWire.encodeJson("1")))
        assertEquals(1, request.id.size)

        // index == size is the push
        assertNull(handles.tlSet(vector, "1", PluginWire.encodeJson("""{"_":"inputUserSelf"}""")))
        assertEquals(2, request.id.size)
        assertEquals("vector index out of range: 5", handles.tlSet(vector, "5", PluginWire.encodeJson("""{"_":"inputUserSelf"}""")))
    }

    /** counts what the real [org.telegram.tgnet.TLObject] does not: how often it was freed */
    private class CountingError : TLRPC.TL_error() {
        var freeCount = 0

        override fun freeResources() {
            freeCount++
            super.freeResources()
        }
    }

    @Test
    fun an_owned_response_is_freed_once_when_its_handle_goes_away() {
        val handles = TlHandles(UNFILTERED)
        val response = CountingError()
        response.disableFree = true
        val handle = handles.mintForPlugin(response, readOnly = false, owned = true)

        assertEquals(0, response.freeCount)
        handles.tlRelease(handle)
        assertEquals(1, response.freeCount)

        handles.releaseAll()
        assertEquals(1, response.freeCount, "a released handle must not be freed a second time")
    }

    @Test
    fun writing_a_gated_field_flips_only_its_own_bit() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForScope(target, TlHandles.newScope())
        val before = target.flags

        assertNull(handles.tlSet(root, "post_author", PluginWire.encodeJson("\"nick\"")))
        assertTrue(target.flags != before)
        assertEquals("nick", stringOf(handles.tlGet(root, "post_author")))

        assertNull(handles.tlSet(root, "post_author", PluginWire.encodeNull()))
        assertEquals(before, target.flags, "clearing it must leave every other bit exactly as it was")
        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(root, "post_author")))
    }

    @Test
    fun flag_words_are_never_readable_writable_or_enumerable() {
        val handles = TlHandles(UNFILTERED)
        val root = handles.mintForScope(message(), TlHandles.newScope())

        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(root, "flags")))
        assertEquals(0, handles.tlHas(root, "flags"))
        assertTrue(handles.tlOwnKeys(root)!!.split(",").none { it == "flags" || it == "flags2" })
        assertTrue(handles.tlSet(root, "flags", PluginWire.encodeJson("7"))!!.contains("managed by the bridge"))
    }

    @Test
    fun resolveTlObject_hands_back_the_app_s_own_instance_never_a_copy() {
        val handles = TlHandles(UNFILTERED)
        val target = message()

        assertSame(target, handles.resolveTlObject(handles.mintForScope(target, TlHandles.newScope())))
    }

    /**
     * The table is reached from `globalQueue` and from whichever thread a JVM runnable or an Xposed
     * phase entered on, so minting, reading and releasing a scope all have to overlap safely.
     */
    @Test
    fun the_table_holds_up_under_minting_and_releasing_from_several_threads() {
        val handles = TlHandles(UNFILTERED)
        val minted = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
        val failures = CopyOnWriteArrayList<Throwable>()
        val rounds = 400
        val readers = (1..4).map { worker ->
            Thread({
                try {
                    repeat(rounds) { round ->
                        val target = TLRPC.TL_message().apply {
                            id = round
                            message = "m$worker"
                            peer_id = peerUser(worker.toLong())
                        }.synced()
                        val handle = handles.mintForPlugin(target, readOnly = false)
                        assertTrue(minted.add(handle), "handle $handle was minted twice")
                        assertEquals(PluginWire.encodeInt(round.toLong()), handles.tlGet(handle, "id"))
                        assertEquals(PluginWire.encodeString("m$worker"), handles.tlGet(handle, "message"))
                        assertEquals("message", JSONObject(handles.project(handle)).getString("_"))
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }, "tl-reader-$worker")
        }
        val scopes = Thread({
            try {
                repeat(rounds) {
                    val scope = TlHandles.newScope()
                    val handle = handles.mintForScope(message(), scope)
                    assertTrue(minted.add(handle), "handle $handle was minted twice")
                    handles.releaseScope(scope)
                    assertEquals(PluginWire.encodeExpired(), handles.tlGet(handle, "id"))
                }
            } catch (e: Throwable) {
                failures.add(e)
            }
        }, "tl-scopes")
        val workers = readers + scopes
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        workers.forEach { assertTrue(!it.isAlive, "${it.name} did not finish") }
        assertTrue(failures.isEmpty(), failures.joinToString())
        assertEquals(rounds * 5, minted.size)
    }
}
