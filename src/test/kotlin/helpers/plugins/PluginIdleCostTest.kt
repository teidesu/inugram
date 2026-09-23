package desu.inugram.helpers.plugins

import desu.inugram.helpers.NotificationsHelper
import desu.inugram.helpers.plugins.telegram.PluginUpdates
import org.junit.Before
import org.junit.Test
import org.telegram.tgnet.tl.TL_update
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginIdleCostTest {
    @Before
    fun setUp() = resetBridge()

    @Test
    fun nothing_runs_with_no_plugin_running() {
        assertFalse(PluginManager.anyRunning, "no plugin was started")
        assertFalse(PluginUpdates.isDropped(TL_update.TL_updateNewMessage()), "no update was ever dropped")
        assertFalse(NotificationsHelper.shouldSuppressNotifications(0), "no hold was ever taken")
    }

    @Test
    fun a_started_plugin_flips_the_flag_and_a_closed_one_puts_it_back() {
        val plugin = startPlugin("idle-cost")
        try {
            assertTrue(PluginManager.anyRunning, "a started plugin runs")
        } finally {
            closeEngine(plugin)
        }
        assertFalse(PluginManager.anyRunning, "the last plugin closed")
    }
}
