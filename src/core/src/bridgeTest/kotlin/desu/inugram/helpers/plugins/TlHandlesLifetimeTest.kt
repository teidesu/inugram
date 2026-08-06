package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.TlWire
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

        assertEquals(1L, (TlWire.decode(handles.tlGet(owned, "id")) as TlWire.Value.IntNum).value)
    }

    @Test
    fun `a read-only view refuses writes and hands out read-only children`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()
        val root = handles.mintForPlugin(target, readOnly = true)

        assertPluginError("forbidden", handles.tlSet(root, "message", TlWire.encodeJson("\"x\"")))
        assertEquals("hi", target.message)

        val child = handleOf(handles.tlGet(root, "from_id"))
        assertTrue(child.readOnly)
        assertPluginError("forbidden", handles.tlSet(child.id, "user_id", TlWire.encodeJson("1")))
    }

    @Test
    fun `a read-only handle coming back over the bridge is refused as a value`() {
        val handles = TlHandles(UNFILTERED)
        val writable = handles.mintForScope(message(), TlHandles.newScope())
        val appOwned = handles.mintForPlugin(peerUser(9L), readOnly = true)

        // assigning it would re-mint it writable on the next read of that field
        val refusal = handles.tlSet(writable, "from_id", TlWire.encodeHandle(false, appOwned, readOnly = true))

        assertPluginError("forbidden", refusal)
        assertTrue(handles.isReadOnly(appOwned))
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

        assertEquals("vector length can only shrink (2 -> 5 not allowed)", handles.tlSet(vector, "length", TlWire.encodeJson("5")))
        assertNull(handles.tlSet(vector, "length", TlWire.encodeJson("1")))
        assertEquals(1, request.id.size)

        // index == size is the push
        assertNull(handles.tlSet(vector, "1", TlWire.encodeJson("""{"_":"inputUserSelf"}""")))
        assertEquals(2, request.id.size)
        assertEquals("vector index out of range: 5", handles.tlSet(vector, "5", TlWire.encodeJson("""{"_":"inputUserSelf"}""")))
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

        assertNull(handles.tlSet(root, "post_author", TlWire.encodeJson("\"nick\"")))
        assertTrue(target.flags != before)
        assertEquals("nick", stringOf(handles.tlGet(root, "post_author")))

        assertNull(handles.tlSet(root, "post_author", TlWire.encodeNull()))
        assertEquals(before, target.flags, "clearing it must leave every other bit exactly as it was")
        assertEquals(TlWire.Value.Null, TlWire.decode(handles.tlGet(root, "post_author")))
    }

    @Test
    fun `flag words are never readable, writable or enumerable`() {
        val handles = TlHandles(UNFILTERED)
        val root = handles.mintForScope(message(), TlHandles.newScope())

        assertEquals(TlWire.Value.Null, TlWire.decode(handles.tlGet(root, "flags")))
        assertEquals(0, handles.tlHas(root, "flags"))
        assertTrue(handles.tlOwnKeys(root)!!.split(",").none { it == "flags" || it == "flags2" })
        assertTrue(handles.tlSet(root, "flags", TlWire.encodeJson("7"))!!.contains("managed by the bridge"))
    }

    @Test
    fun `resolveTlObject hands back the app's own instance, never a copy`() {
        val handles = TlHandles(UNFILTERED)
        val target = message()

        assertSame(target, handles.resolveTlObject(handles.mintForScope(target, TlHandles.newScope())))
    }
}
