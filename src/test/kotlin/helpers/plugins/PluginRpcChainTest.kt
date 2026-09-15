package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginWire
import desu.inugram.helpers.plugins.telegram.PluginRpc
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * The `interceptRpc` chain end to end: the app's `sendRequestInternal` on one side, a scripted
 * middleware on the other, and the real queues (virtual, single-threaded) in between.
 *
 * Everything here is a rule from AGENTS.md's "Plugin engine invariants" that no other target can
 * reach, because it needs a request instance, a chain and a queue at once.
 */
class PluginRpcChainTest {
    /**
     * counts what the real [TLObject] does not: how often it was freed. The base `freeResources` is
     * empty and the `disableFree` check lives in whichever subclass owns a `NativeByteBuffer`, so
     * the shape below is that subclass's.
     *
     * The name is load-bearing: `TlNames.classNameToTlName` reads the *class*, so a counter called
     * anything else would enter a chain registered for a different method.
     */
    @Suppress("ClassName")
    class TL_users_getUsers : TLRPC.TL_users_getUsers() {
        var freeCount = 0

        override fun freeResources() {
            if (disableFree) return
            freeCount++
            super.freeResources()
        }
    }

    @Suppress("ClassName")
    class TL_userFull : TLRPC.TL_userFull() {
        var freeCount = 0

        override fun freeResources() {
            if (disableFree) return
            freeCount++
            super.freeResources()
        }
    }

    private class AppRequest {
        val request = TL_users_getUsers()
        var response: TLObject? = null
        var error: TLRPC.TL_error? = null
        var answered = false
    }

    @Before
    fun setUp() = resetBridge()

    private fun send(app: AppRequest, token: Int = 11, account: Int = 0): Boolean =
        PluginRpc.maybeIntercept(
            connections(account),
            app.request,
            RequestDelegate { response, error ->
                app.answered = true
                app.response = response
                app.error = error
            },
            null, null, null,
            0, 0, 0, false, token, account,
        )

    /** a plugin whose middleware immediately forwards with `next()` and returns what it gets */
    private fun passthroughPlugin(name: String = "p"): Plugin {
        val plugin = startPlugin(name, "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        plugin.js.onDispatchRpc = { plugin.next(it.dispatchId, it.requestWire) }
        plugin.js.onCompleteNext = { plugin.complete(it.dispatchId, it.resultWire) }
        return plugin
    }

    @Test
    fun detaching_an_old_session_keeps_the_replacement_registrations() {
        val plugin = passthroughPlugin()
        val old = plugin.session!!
        old.stopDispatching()
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        attachBridge(plugin.session!!)
        assertNull(plugin.interceptRpc("users.getUsers"))
        PluginRpc.detach(old)
        assertTrue(send(AppRequest()))
        drain()
        assertEquals(1, plugin.js.dispatches.size)
        assertTrue((old.engine as RecordingQuickJs).dispatches.isEmpty())
    }

    @Test
    fun a_queued_chain_cannot_dispatch_old_ids_into_a_reloaded_engine() {
        val plugin = passthroughPlugin()
        val old = plugin.session!!
        val app = AppRequest()
        assertTrue(send(app))
        old.stopDispatching()
        PluginRpc.detach(plugin.session!!)
        old.tl.releaseAll()
        plugin.session = PluginSession(plugin, RecordingQuickJs())
        attachBridge(plugin.session!!)
        assertNull(plugin.interceptRpc("users.getUsers"))
        drain()
        assertTrue(plugin.js.dispatches.isEmpty())
        assertTrue((old.engine as RecordingQuickJs).dispatches.isEmpty())
        assertSame(app.request, connections().lastSent()!!.request)
    }

    @Test
    fun a_future_chain_stage_keeps_its_original_session() {
        val first = passthroughPlugin("first")
        first.js.onDispatchRpc = null
        val second = passthroughPlugin("second")
        val app = AppRequest()
        assertTrue(send(app))
        drain()
        val old = second.session!!
        old.stopDispatching()
        PluginRpc.detach(second.session!!)
        old.tl.releaseAll()
        second.session = PluginSession(second, RecordingQuickJs())
        attachBridge(second.session!!)
        assertNull(second.interceptRpc("users.getUsers"))
        val pending = first.js.dispatches.single()
        assertNull(first.next(pending.dispatchId, pending.requestWire))
        drain()
        assertTrue(second.js.dispatches.isEmpty())
        assertTrue((old.engine as RecordingQuickJs).dispatches.isEmpty())
        assertSame(app.request, connections().lastSent()!!.request)
    }

    @Test
    fun a_chain_forwards_the_request_for_real_and_answers_the_app_with_what_came_back() {
        passthroughPlugin()
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        val sent = connections().lastSent()!!
        assertSame(app.request, sent.request)
        assertFalse(app.answered, "the app must not be answered before the real request is")

        val response = TLRPC.TL_error().apply { code = 0; text = "ok" }
        sent.answer(response, null, 1234L)
        drain()

        assertTrue(app.answered)
        assertSame(response, app.response)
    }

    @Test
    fun stock_s_connection_not_inited_retry_does_not_start_a_second_chain() {
        val plugin = passthroughPlugin()
        val app = AppRequest()

        assertTrue(send(app))
        drain()
        assertEquals(1, plugin.js.dispatches.size)

        // stock re-enters sendRequestInternal with the very instance it was handed, a fresh token,
        // and without invoking the delegate
        connections().inu_retryNotInited(connections().lastSent()!!, 99)
        drain()

        assertEquals(1, plugin.js.dispatches.size, "the middleware must not run twice for one flight")
        assertEquals(2, connections().sent.size, "and the retry must still reach the wire")

        // once the delegate answers, no further send can follow and the lease ends
        connections().lastSent()!!.answer(null, null, 0L)
        drain()
        assertTrue(app.answered)

        assertTrue(
            PluginRpc.maybeIntercept(
                connections(), app.request, null, null, null, null, 0, 0, 0, false, 100, 0,
            ),
            "a genuinely new send of the same instance is a new chain",
        )
    }

    @Test
    fun a_cancel_that_beats_the_send_runs_the_caller_s_oncancelled_and_stops_the_request() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        plugin.interceptRpc("users.getUsers")
        // a middleware that parks: the chain never reaches the passthrough
        plugin.js.onDispatchRpc = {}
        val app = AppRequest()

        assertTrue(send(app, token = 11))
        drain()

        var cancelled = false
        PluginRpc.onRequestCancelled(0, 11, true) { cancelled = true }
        drain()

        assertTrue(cancelled, "stock hangs onCancelled off callbacks only the passthrough creates")
        assertEquals(0, connections().sent.size, "a cancelled chain must not put its request on the wire")
        assertFalse(app.answered, "stock drops a cancelled request's delegate too")
        assertEquals("R-1000:INTERCEPTOR_CANCELLED", plugin.js.abandons.single().reasonWire)
    }

    @Test
    fun a_cancel_that_loses_the_race_is_re_issued_once_the_token_is_real() {
        passthroughPlugin()
        val app = AppRequest()

        assertTrue(send(app, token = 11))
        drain()
        assertEquals(1, connections().sent.size)

        PluginRpc.onRequestCancelled(0, 11, true, null)
        drain()

        assertEquals(1, connections().cancels.size)
        assertEquals(11, connections().cancels[0].token)
        assertTrue(connections().cancels[0].notifyServer)
    }

    @Test
    fun cancelling_by_guid_reaches_a_chain_that_has_not_passed_through_yet() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        plugin.interceptRpc("users.getUsers")
        plugin.js.onDispatchRpc = {}
        val app = AppRequest()

        PluginRpc.onRequestBoundToGuid(0, 11, 4242)
        assertTrue(send(app, token = 11))
        drain()

        PluginRpc.onRequestsCancelledForGuid(0, 4242)
        drain()

        assertEquals("R-1000:INTERCEPTOR_CANCELLED", plugin.js.abandons.single().reasonWire)
        assertEquals(0, connections().sent.size)
    }

    @Test
    fun a_stalled_middleware_fails_the_app_when_the_budget_runs_out() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        plugin.interceptRpc("users.getUsers")
        plugin.js.onDispatchRpc = {}
        val app = AppRequest()

        assertTrue(send(app))
        drain()
        assertFalse(app.answered)

        advanceBy(10_001)

        assertTrue(app.answered)
        assertEquals(-1000, app.error!!.code)
        assertEquals("INTERCEPTOR_TIMEOUT", app.error!!.text)
        assertEquals(0, connections().sent.size, "an expired chain must not fall through to the real send")
    }

    @Test
    fun the_budget_is_suspended_while_the_real_request_is_in_flight() {
        passthroughPlugin()
        val app = AppRequest()

        assertTrue(send(app))
        drain()
        val sent = connections().lastSent()!!

        // a slow server is not charged to the plugins
        advanceBy(30_000)
        assertFalse(app.answered)

        val response = TLRPC.TL_error()
        sent.answer(response, null, 5L)
        drain()

        assertTrue(app.answered)
        assertSame(response, app.response)
    }

    @Test
    fun the_request_is_freed_once_by_the_chain_and_never_under_a_live_view() {
        passthroughPlugin()
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        // sendRequestInternal frees the request the moment it has serialized it; the chain owns it
        assertTrue(app.request.disableFree, "the send must not gut a view a stage still holds")
        assertEquals(0, app.request.freeCount)

        connections().lastSent()!!.answer(null, null, 0L)
        drain()

        assertEquals(1, app.request.freeCount, "and the chain must perform that free exactly once")
    }

    @Test
    fun a_short_circuiting_chain_still_frees_the_request_it_took_over() {
        // the app's request is serialized-and-freed by stock on the send it never reaches, so the
        // chain owns that free whether or not it passed the request on
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        plugin.js.onDispatchRpc = { plugin.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"boolTrue"}""")) }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertTrue(app.answered)
        assertEquals(0, connections().sent.size, "a short circuit never reaches the wire")
        assertEquals(1, app.request.freeCount, "the request it took over is still its to free")
    }

    @Test
    fun a_bad_response_skips_a_non_strict_middleware() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        plugin.js.onDispatchRpc = {
            plugin.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"users.users","users":1}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        val sent = connections().lastSent()!!
        assertFalse(app.answered)
        val response = TLRPC.TL_boolTrue()
        sent.answer(response, null, 7L)
        drain()

        assertSame(response, app.response)
        assertNull(app.error)
    }

    @Test
    fun a_bad_response_fails_a_strict_middleware() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers", strict = true))
        plugin.js.onDispatchRpc = {
            plugin.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"users.users","users":1}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertTrue(app.answered)
        assertNull(app.response)
        assertEquals(-1000, app.error?.code)
        assertTrue(app.error?.text?.startsWith("bad middleware response:") == true)
        assertEquals(0, connections().sent.size)
    }

    @Test
    fun a_bad_response_after_next_uses_the_downstream_result_without_sending_twice() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        plugin.js.onDispatchRpc = {
            plugin.next(it.dispatchId, it.requestWire)
            plugin.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"users.users","users":1}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertEquals(1, connections().sent.size)
        assertFalse(app.answered)
        val response = TLRPC.TL_boolTrue()
        connections().lastSent()!!.answer(response, null, 8L)
        drain()

        assertSame(response, app.response)
        assertNull(app.error)
        assertEquals(1, connections().sent.size)
    }

    @Test
    fun a_takeover_method_is_refused_even_under_an_unscoped_grant() {
        // an unscoped grant satisfies every scope check, so this refusal is the only thing between
        // `@grant interceptRpc` and auth.exportLoginToken
        val plugin = startPlugin("p", "interceptRpc", "invokeRpc")
        assertPluginError("forbidden", plugin.interceptRpc("auth.exportLoginToken"))
        assertPluginError("forbidden", plugin.js.listener!!.onInvokeRpc(1L, QuickJs.ANY_ACCOUNT, PluginWire.encodeJson("""{"_":"auth.exportLoginToken"}""")))
        // the account.* half of the list is not a prefix, so it is refused by name or not at all
        assertPluginError("forbidden", plugin.interceptRpc("account.deleteAccount"))
        assertPluginError("forbidden", plugin.js.listener!!.onInvokeRpc(2L, QuickJs.ANY_ACCOUNT, PluginWire.encodeJson("""{"_":"account.resetAuthorization"}""")))
    }

    /**
     * the account-less form sends on the slot the plugin started on and the `Account` form on the
     * one it names, which is the whole difference between them. The slot is not the host's to
     * trust: `invokeRpc` lives on a prototype every handle shares, so a plugin can call it through
     * any object carrying an `id`.
     */
    @Test
    fun invokerpc_sends_on_the_slot_it_was_given() {
        for (slot in 0..1) TestApp.signIn(slot, id = 100L + slot)
        val plugin = startPlugin("p", "invokeRpc(users.getUsers)")
        val request = PluginWire.encodeJson("""{"_":"users.getUsers"}""")

        assertNull(plugin.js.listener!!.onInvokeRpc(1L, QuickJs.ANY_ACCOUNT, request))
        assertEquals(1, connections(0).sent.size)
        assertEquals(0, connections(1).sent.size)

        assertNull(plugin.js.listener!!.onInvokeRpc(2L, 1, request))
        assertEquals(1, connections(0).sent.size)
        assertEquals(1, connections(1).sent.size, "the named account is the one it went out on")

        assertPluginError("invalid-argument", plugin.js.listener!!.onInvokeRpc(3L, 7, request))
        assertPluginError("invalid-argument", plugin.js.listener!!.onInvokeRpc(4L, 9999, request))
        assertPluginError("invalid-argument", plugin.js.listener!!.onInvokeRpc(5L, -2, request))
    }

    /**
     * a request handle the plugin was handed read-only (an `onUpdate` payload, anything off an
     * `Account`) coming back in as the request: accepting it would re-mint the app's own object
     * writable on the next read of any of its fields.
     */
    @Test
    fun a_read_only_handle_is_refused_wherever_a_request_is_decoded() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)", "invokeRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        val appOwned = tlTableOf(plugin)!!.mintForPlugin(TLRPC.TL_users_getUsers(), readOnly = true)
        val readOnlyWire = PluginWire.encodeHandle(vector = false, id = appOwned, readOnly = true)

        var refusal: String? = null
        plugin.js.onDispatchRpc = { refusal = plugin.next(it.dispatchId, readOnlyWire) }
        assertTrue(send(AppRequest()))
        drain()

        assertPluginError("forbidden", refusal)
        assertPluginError("forbidden", plugin.js.listener!!.onInvokeRpc(1L, QuickJs.ANY_ACCOUNT, readOnlyWire))
        assertEquals(0, connections().sent.size)
    }

    @Test
    fun a_chain_that_collapses_before_it_advances_keeps_the_request_off_the_wire() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        var dispatchId = 0L
        plugin.js.onDispatchRpc = { dispatchId = it.dispatchId }
        val app = AppRequest()
        assertTrue(send(app))
        drain()

        // next() only queues the advance; the plugin stops before that runnable gets to run, so the
        // identity guard on it is the only thing between a dead chain and a real send.
        // (the later window, a collapse after the send is already queued on stageQueue, needs two
        // real threads and is not reachable from this single-threaded harness.)
        plugin.next(dispatchId, PluginWire.encodeJson("""{"_":"users.getUsers"}"""))
        detachPlugin(plugin)
        drain()

        assertEquals(0, connections().sent.size, "a collapsed chain must not still reach the server")
    }

    @Test
    fun a_stage_that_settles_without_awaiting_next_does_not_leave_its_sub_chain_walking() {
        val first = startPlugin("first", "interceptRpc(users.getUsers)")
        val second = passthroughPlugin("second")
        assertNull(first.interceptRpc("users.getUsers"))
        // calls next() and settles anyway, so the stage below it is live when this one answers
        first.js.onDispatchRpc = {
            first.next(it.dispatchId, it.requestWire)
            first.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"boolTrue"}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertTrue(app.answered)
        assertEquals(0, connections().sent.size, "the abandoned sub-chain must not send after the app was answered")
    }

    @Test
    fun a_cancelled_queued_passthrough_releases_its_bypass_lease() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        plugin.js.onDispatchRpc = {
            plugin.next(it.dispatchId, it.requestWire)
            plugin.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"boolTrue"}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()
        assertEquals(0, connections().sent.size)

        assertTrue(send(app), "the cancelled send must not leave this request bypassing later chains")
        drain()
        assertEquals(2, plugin.js.dispatches.size)
    }

    /**
     * the same rule one stage deeper, which is the only place `abandonBelow` is what does the work:
     * for the *first* stage `finalize` is the top-level one, so `collapseChain` removes every stage
     * anyway and would cover for it. A middle stage answers *upward* into its parent's continuation
     * and the chain stays live, so nothing else stops the stage below walking on to the real send.
     */
    @Test
    fun a_middle_stage_that_settles_without_awaiting_next_does_not_leave_its_sub_chain_walking() {
        passthroughPlugin("first")
        val second = startPlugin("second", "interceptRpc(users.getUsers)")
        assertNull(second.interceptRpc("users.getUsers"))
        passthroughPlugin("third")
        second.js.onDispatchRpc = {
            second.next(it.dispatchId, it.requestWire)
            second.complete(it.dispatchId, PluginWire.encodeJson("""{"_":"boolTrue"}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertTrue(app.answered)
        assertTrue(app.response is TLRPC.TL_boolTrue, "the app is answered by the stage that short-circuited")
        assertEquals(0, connections().sent.size, "the abandoned sub-chain must not send after the app was answered")
    }

    /** the three plugins of a chain, each parked in `await next()` except the deepest */
    private fun parkedChain(order: MutableList<String>): List<Plugin> =
        listOf("first", "second", "third").map { name ->
            val plugin = startPlugin(name, "interceptRpc(users.getUsers)")
            assertNull(plugin.interceptRpc("users.getUsers"))
            plugin.js.onDispatchRpc = { if (name != "third") plugin.next(it.dispatchId, it.requestWire) }
            plugin.js.onAbandonDispatch = { order.add(name) }
            plugin
        }

    @Test
    fun a_collapse_abandons_the_stages_deepest_first() {
        val order = ArrayList<String>()
        parkedChain(order)

        assertTrue(send(AppRequest()))
        drain()
        advanceBy(10_001)

        assertEquals(listOf("third", "second", "first"), order)
    }

    /**
     * the same order one stage down, where `abandonBelow` is what walks rather than `collapseChain`:
     * a stage that settles answers upward and the chain stays live, so its sub-chain is torn down on
     * its own and for the same reason - a rejection continuation re-enters `onNext`/`onComplete`,
     * and a shallower stage must never be gone by the time a deeper one's does. A sub-chain of one
     * is a walk with no observable order, which is why this takes four plugins.
     */
    @Test
    fun a_middle_stage_that_settles_abandons_the_stages_below_it_deepest_first() {
        val order = ArrayList<String>()
        val chain = listOf("first", "second", "third", "fourth").map { name ->
            val plugin = startPlugin(name, "interceptRpc(users.getUsers)")
            assertNull(plugin.interceptRpc("users.getUsers"))
            plugin.js.onDispatchRpc = { if (name != "fourth") plugin.next(it.dispatchId, it.requestWire) }
            plugin.js.onAbandonDispatch = { order.add(name) }
            plugin
        }
        val second = chain[1]

        assertTrue(send(AppRequest()))
        drain()
        assertTrue(chain.all { it.js.dispatches.size == 1 }, "every stage has to be live for this to mean anything")

        second.complete(second.js.dispatches.single().dispatchId, PluginWire.encodeJson("""{"_":"boolTrue"}"""))
        drain()

        assertEquals(listOf("fourth", "third"), order)
    }

    /**
     * a stage parked in `await next()` is rejected *inside* [QuickJs.abandonDispatch], so its catch
     * block runs while the collapse is still walking. Releasing the scope before that would leave it
     * reading `handle-expired` off every field of the request it was handed.
     */
    @Test
    fun a_stage_the_collapse_rejects_can_still_read_its_own_request() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers"))
        var requestWire = ""
        var readAtAbandon: String? = null
        plugin.js.onDispatchRpc = { requestWire = it.requestWire }
        plugin.js.onAbandonDispatch = { readAtAbandon = plugin.tl().tlGet(handleId(requestWire), "id") }

        assertTrue(send(AppRequest()))
        drain()
        advanceBy(10_001)

        assertTrue(
            PluginWire.decode(readAtAbandon!!) is PluginWire.Value.Handle,
            "the scope must outlive every abandon, got ${PluginWire.decode(readAtAbandon!!)}",
        )
    }

    /** the same ordering in `PluginManager.teardown`, where `session.tl.releaseAll` is what has to come last */
    @Test
    fun a_stage_detach_rejects_can_still_read_its_own_request() {
        // two registrations put one plugin in the chain twice, which is what gives detach a stage of
        // its own to abandon: its topmost one answers the app rather than the engine
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        assertNull(plugin.interceptRpc("users.getUsers", callbackId = 1))
        assertNull(plugin.interceptRpc("users.getUsers", callbackId = 2))
        var deepWire = ""
        var readAtAbandon: String? = null
        plugin.js.onDispatchRpc = { dispatch ->
            if (dispatch.callbackId == 1) plugin.next(dispatch.dispatchId, dispatch.requestWire)
            else deepWire = dispatch.requestWire
        }
        plugin.js.onAbandonDispatch = { readAtAbandon = plugin.tl().tlGet(handleId(deepWire), "id") }

        assertTrue(send(AppRequest()))
        drain()
        assertEquals(2, plugin.js.dispatches.size)

        detachPlugin(plugin)
        drain()

        assertTrue(
            PluginWire.decode(readAtAbandon!!) is PluginWire.Value.Handle,
            "the table must outlive every abandon, got ${PluginWire.decode(readAtAbandon!!)}",
        )
    }

    @Test
    fun the_passthrough_response_is_handed_over_rather_than_freed_under_the_plugin() {
        passthroughPlugin()
        val app = AppRequest()
        assertTrue(send(app))
        drain()

        val response = TL_userFull()
        connections().lastSent()!!.answer(response, null, 0L)
        drain()

        assertTrue(app.answered)
        assertEquals(1, response.freeCount, "the chain owns exactly one free of the response")
    }

    @Test
    fun next_may_rewrite_the_request_s_fields_but_not_its_method() {
        val plugin = startPlugin("p", "interceptRpc(users.getUsers)")
        plugin.interceptRpc("users.getUsers")
        var refusal: String? = null
        plugin.js.onDispatchRpc = { dispatch ->
            refusal = plugin.next(dispatch.dispatchId, PluginWire.encodeJson("""{"_":"messages.getHistory"}"""))
        }
        val app = AppRequest()

        assertTrue(send(app))
        drain()

        assertPluginError("forbidden", refusal)
        assertEquals(0, connections().sent.size)
    }

    @Test
    fun two_plugins_run_in_the_order_the_plugins_list_has_them_not_registration_order() {
        val first = passthroughPlugin("first")
        val second = passthroughPlugin("second")
        val order = ArrayList<String>()
        first.js.onDispatchRpc = { order.add("first"); first.next(it.dispatchId, it.requestWire) }
        second.js.onDispatchRpc = { order.add("second"); second.next(it.dispatchId, it.requestWire) }

        // the user drags `second` above `first`
        setInstalledPlugins(listOf(second, first))
        PluginRpc.refreshChainOrder()
        drain()

        assertTrue(send(AppRequest()))
        drain()

        assertEquals(listOf("second", "first"), order)
    }
}
