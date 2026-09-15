package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.helpers.plugins.telegram.PluginRpc
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLRPC
import kotlin.test.assertEquals

class InterceptRpcBenchTest {
    private val sessions = ArrayList<PluginSession>()

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        for (session in sessions) {
            session.engine.stopCallbacks()
            PluginJvm.detach(session)
            session.engine.close()
        }
        sessions.clear()
    }

    private fun startBenchEngine(): Plugin {
        val plugin = startPlugin("rpc-bench", "interceptRpc(users.getUsers)")
        plugin.session = PluginSession(plugin, QuickJs())
        attachBridge(
            plugin.session!!,
            object : CoreListener {
                override fun onConsole(level: Int, message: String) = Unit
                override fun onTimerSchedule(delayMs: Long) = Unit
            },
            accountsJson = { """[{"id":0,"userId":100,"isCurrent":true,"isPremium":false}]""" },
        )
        sessions.add(plugin.session!!)
        return plugin
    }

    private val middlewares = mapOf(
        "explicitNext" to "({ request }, next) => next(request)",
        "defaultNext" to "(_, next) => next()",
    )

    private fun runChains(count: Int): Long {
        var answered = 0
        val start = System.nanoTime()
        repeat(count) { i ->
            PluginRpc.maybeIntercept(
                connections(0),
                TLRPC.TL_users_getUsers(),
                RequestDelegate { _, _ -> answered++ },
                null, null, null,
                0, 0, 0, false, 1000 + i, 0,
            )
            drain()
            connections(0).lastSent()!!.answer(TLRPC.TL_error().apply { code = 0; text = "ok" }, null)
            drain()
        }
        val elapsed = System.nanoTime() - start
        assertEquals(count, answered)
        return elapsed
    }

    @Test
    fun bench_intercept_rpc_middleware_shapes() {
        val plugin = startBenchEngine()
        val engine = plugin.engine!!
        val chains = 300
        val lines = ArrayList<String>()
        for ((name, middleware) in middlewares) {
            engine.evaluate("globalThis.dispose = inu.interceptRpc('users.getUsers', $middleware)")
            drain()
            runChains(50)
            val rounds = (1..5).map { runChains(chains) }.sorted()
            engine.evaluate("dispose()")
            drain()
            lines += "$name=%.1fus".format(rounds[rounds.size / 2] / 1000.0 / chains)
        }
        Log.i("InuBench", "interceptRpc chain, per request, median of 5x$chains: ${lines.joinToString(" ")}")
    }
}
