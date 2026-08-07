package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.tl.TlFilter
import desu.inugram.helpers.plugins.tl.TlHandles
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
    fun `releasing a scope invalidates every handle minted under it, children included`() {
        val handles = TlHandles(UNFILTERED)
        val scope = TlHandles.newScope()
        val root = handles.mintForScope(message(), scope)
        val child = handleId(handles.tlGet(root, "from_id"))

        handles.releaseScope(scope)

        assertPluginError("handle-expired", handles.tlGet(root, "id"))
        assertPluginError("handle-expired", handles.tlGet(child, "user_id"))
    }

    @Test
    fun `a plugin-lifetime handle survives a scope release`() {
        val handles = TlHandles(UNFILTERED)
        val scope = TlHandles.newScope()
        val owned = handles.mintForPlugin(message(), readOnly = true)
        handles.mintForScope(message(), scope)

        handles.releaseScope(scope)

        assertEquals(1L, (PluginWire.decode(handles.tlGet(owned, "id")) as PluginWire.Value.IntNum).value)
    }

    @Test
    fun `a read-only view refuses writes and hands out read-only children`() {
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
    fun `a read-only handle coming back over the bridge is refused as a value`() {
        val handles = TlHandles(UNFILTERED)
        val writable = handles.mintForScope(message(), TlHandles.newScope())
        val appOwned = handles.mintForPlugin(peerUser(9L), readOnly = true)

        // assigning it would re-mint it writable on the next read of that field
        val refusal = handles.tlSet(writable, "from_id", PluginWire.encodeHandle(false, appOwned, readOnly = true))

        assertPluginError("forbidden", refusal)
        assertTrue(handles.isReadOnly(appOwned))
    }

    /**
     * the guard is on the *slot* a rewrite lands in, and `d.peer.user_id = x` and
     * `d.peer = {_: 'peerUser', user_id: x}` land in the same one. checking only the assigned name
     * refuses the first and allows the second, which is the whole of the bypass.
     */
    @Test
    fun `a guarded scope refuses an addressing field carried inside the assigned value`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForDeserialize(target, TlHandles.newScope())

        assertPluginError("forbidden", handles.tlSet(root, "peer_id", PluginWire.encodeJson("""{"_":"peerUser","user_id":"777000"}""")))
        assertPluginError("forbidden", handles.tlSet(root, "from_id", PluginWire.encodeJson("""{"_":"peerUser","user_id":"777000"}""")))
        assertEquals(42L, (target.from_id as TLRPC.TL_peerUser).user_id)
        assertEquals(43L, (target.peer_id as TLRPC.TL_peerUser).user_id)

        // one that describes rather than addresses still goes through: the middleware form exists for structural rewrites
        assertNull(handles.tlSet(root, "message", PluginWire.encodeJson("\"rewritten\"")))
        assertEquals("rewritten", target.message)
    }

    @Test
    fun `a guarded scope refuses an addressing field nested any distance down`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForDeserialize(target, TlHandles.newScope())

        val nested =
            """{"_":"messageMediaDocument","document":{"_":"document","id":"7","access_hash":"9","dc_id":2,"mime_type":"x","size":"1"}}"""
        assertPluginError("forbidden", handles.tlSet(root, "media", PluginWire.encodeJson(nested)))
        assertNull(target.media)
    }

    @Test
    fun `a guarded scope refuses a vector element carrying an addressing field`() {
        val handles = TlHandles(UNFILTERED)
        val target = message().apply { entities.add(TLRPC.TL_messageEntityBold().apply { offset = 0; length = 1 }) }.synced()
        val root = handles.mintForDeserialize(target, TlHandles.newScope())
        val entities = handleId(handles.tlGet(root, "entities"))

        val forged = """{"_":"messageEntityMentionName","offset":0,"length":1,"user_id":"777000"}"""
        assertPluginError("forbidden", handles.tlSet(entities, "0", PluginWire.encodeJson(forged)))
        assertTrue(target.entities[0] is TLRPC.TL_messageEntityBold)

        val plain = """{"_":"messageEntityItalic","offset":0,"length":1}"""
        assertNull(handles.tlSet(entities, "0", PluginWire.encodeJson(plain)))
        assertTrue(target.entities[0] is TLRPC.TL_messageEntityItalic)
    }

    /** a live object carries the addressing fields of wherever it was parsed, so splicing one in is the same rewrite by reference */
    @Test
    fun `a guarded scope refuses a live handle as a value`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForDeserialize(target, TlHandles.newScope())
        val other = handles.mintForPlugin(peerUser(777000L), readOnly = false)

        assertPluginError("forbidden", handles.tlSet(root, "from_id", PluginWire.encodeHandle(false, other, readOnly = false)))
        assertEquals(42L, (target.from_id as TLRPC.TL_peerUser).user_id)
    }

    @Test
    fun `a handle id does not resolve in another plugin's table`() {
        val mine = TlHandles(UNFILTERED)
        val theirs = TlHandles(UNFILTERED)
        val secret = theirs.mintForPlugin(message(), readOnly = true)

        // a plugin can forge the marker symbol, so it can hand any integer back over the bridge
        assertNull(mine.resolveTlObject(secret))
        assertPluginError("handle-expired", mine.tlGet(secret, "message"))
        assertNotNull(theirs.resolveTlObject(secret))
    }

    @Test
    fun `a vector can shrink but never grow past its end`() {
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

    @Test
    fun `an owned response is freed once, when its handle goes away`() {
        val handles = TlHandles(UNFILTERED)
        val response = TLRPC.TL_error()
        response.disableFree = true
        val handle = handles.mintForPlugin(response, readOnly = false, owned = true)

        assertEquals(0, response.inu_freeCount)
        handles.tlRelease(handle)
        assertEquals(1, response.inu_freeCount)

        handles.releaseAll()
        assertEquals(1, response.inu_freeCount, "a released handle must not be freed a second time")
    }

    @Test
    fun `writing a gated field flips only its own bit`() {
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
    fun `flag words are never readable, writable or enumerable`() {
        val handles = TlHandles(UNFILTERED)
        val root = handles.mintForScope(message(), TlHandles.newScope())

        assertEquals(PluginWire.Value.Null, PluginWire.decode(handles.tlGet(root, "flags")))
        assertEquals(0, handles.tlHas(root, "flags"))
        assertTrue(handles.tlOwnKeys(root)!!.split(",").none { it == "flags" || it == "flags2" })
        assertTrue(handles.tlSet(root, "flags", PluginWire.encodeJson("7"))!!.contains("managed by the bridge"))
    }

    @Test
    fun `resolveTlObject hands back the app's own instance, never a copy`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()

        assertSame(target, handles.resolveTlObject(handles.mintForScope(target, TlHandles.newScope())))
    }
}
