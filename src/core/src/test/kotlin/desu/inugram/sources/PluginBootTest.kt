package desu.inugram.sources

import desu.inugram.core.plugins.BootCohort
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * When plugins are started, which of them, and what happens to the crash guard around it.
 *
 * `PluginManager` reaches a `Context`, an `AlarmManager` and the app's assets, and this is the whole
 * of the boot sequence - which runs before any test target exists, on a process a push woke. What
 * can still be checked is its shape, and none of the rules below is visible anywhere else in the
 * tree.
 */
class PluginBootTest {
    private val source = forkSource("PluginManager.kt").readText()

    private fun bodyOf(declaration: String): String {
        val at = source.indexOf(declaration)
        assertTrue(at >= 0, "PluginManager no longer declares '$declaration'")
        val body = source.substring(at)
        return body.substring(0, body.indexOf("\n    }\n").takeIf { it > 0 }?.plus(6) ?: body.length)
    }

    @Test
    fun `the crash guard spans one plugin's own code and nothing else`() {
        val pass = bodyOf("private fun runPass(")
        assertTrue(pass.contains("guard.startPass()"), "a pass that runs plugins with nothing to catch it")
        assertTrue(
            Regex("""guard\.guardPlugin \{ start\(plugin\) \}""").containsMatchIn(pass),
            "the flag has to be committed across one plugin's evaluation: across the whole pass it " +
                "is still set when android reaps a headless process it considers idle, and the next " +
                "start the user sees then runs nothing",
        )
        assertEquals(
            1,
            Regex("""guard\.guardPlugin""").findAll(source).count(),
            "every path that runs plugin code goes through runPass, or it runs unattributed",
        )
    }

    @Test
    fun `the boot pass is the cohort, and the app stops waiting for it`() {
        val boot = bodyOf("fun onAppBoot(")
        assertTrue(boot.contains("BootCohort.bootsEarly("), "every plugin would load on the push critical path")
        assertTrue(
            boot.contains("await(BootCohort.EARLY_BUDGET_MILLIS"),
            "the boot blocks so processUpdates cannot race the registrations, so it has to be capped",
        )
        assertTrue(boot.contains("runPass"), "the boot pass has to be guarded like any other")
    }

    @Test
    fun `everything the boot pass left loads at first ui`() {
        val late = bodyOf("fun onAppInteractive(")
        assertTrue(
            Regex("""runPass \{ it\.enabled \}""").containsMatchIn(late),
            "a plugin outside the boot cohort would never load at all",
        )
    }

    @Test
    fun `there are two automatic load passes and no more`() {
        assertEquals(
            2,
            Regex("""runPass \{""").findAll(source).count(),
            "a third automatic pass has to say what guards it, and which cohort it is for",
        )
    }

    /**
     * the residual gap `BootCohort` cannot close by itself: it names *grants*, and a new api whose
     * registrations a headless path dispatches into would be invisible to it. Every one of those
     * registers through `RpcListener` (the action and settings registrations go through
     * `ApiListener`, and nothing dispatches those without a ui), so a new registration family there
     * is the one moment someone can be asked.
     */
    @Test
    fun `a new host-dispatched registration family has to be weighed against the boot cohort`() {
        val listener = forkSource("PluginListener.kt").readText()
        val at = listener.indexOf("interface RpcListener")
        assertTrue(at >= 0, "PluginListener no longer declares RpcListener")
        val body = listener.substring(blockAt(listener, listener.indexOf('{', at)))
        val families = Regex("""fun (\w+Register)\(""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("onRpcRegister", "onUpdateRegister", "onInterceptUpdateRegister"),
            families,
            "if the api behind it can be dispatched with no ui, its grant belongs in " +
                "BootCohort.HEADLESS_APIS (currently ${BootCohort.HEADLESS_APIS.sorted()}), or " +
                "plugins holding it are absent for every push wakeup",
        )
    }
}
