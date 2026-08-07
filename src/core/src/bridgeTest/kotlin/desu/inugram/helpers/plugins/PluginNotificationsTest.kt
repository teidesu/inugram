package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginNotifications
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig

/**
 * `unsafe.notificationCenter`: which registrations are accepted, what a post is allowed to say, and
 * that every observer is gone by the time the engine is.
 */
class PluginNotificationsTest {
    @Before
    fun setUp() {
        resetBridge()
    }

    private fun granted(vararg extra: String) =
        startPlugin("notifications", "unsafe.notificationCenter", *extra)

    private fun Plugin.observe(vararg events: String, callbackId: Int = 1): String? =
        js.listener!!.register(callbackId, arrayOf(*events))

    private fun Plugin.stopObserving(callbackId: Int = 1) =
        js.listener!!.unregister(callbackId)

    /** every observer the app is holding, on every centre a post could come from */
    private fun observerCount(): Int {
        var total = NotificationCenter.getGlobalInstance().inu_observerCount()
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            total += NotificationCenter.getInstance(account).inu_observerCount()
        }
        return total
    }

    private fun argsOf(notification: QuickJs.Notification): List<Any?> {
        val json = JSONArray(notification.argsJson)
        return (0 until json.length()).map { if (json.isNull(it)) null else json.get(it) }
    }

    @Test
    fun `a name the app does not have refuses the whole registration`() {
        val plugin = granted()
        assertPluginError("invalid-argument", plugin.observe("dialogsNeedReload", "notAnEvent"))
        assertEquals(0, observerCount(), "a refused registration must leave nothing observing")
    }

    @Test
    fun `a plugin without the grant observes nothing`() {
        val plugin = startPlugin("notifications")
        assertPluginError("not-granted", plugin.observe("dialogsNeedReload"))
        assertEquals(0, observerCount())
    }

    @Test
    fun `an accepted registration observes every centre a post could come from`() {
        val plugin = granted()
        assertNull(plugin.observe("dialogsNeedReload", "updateInterfaces"))
        assertEquals(2 * (UserConfig.MAX_ACCOUNT_COUNT + 1), observerCount())
    }

    @Test
    fun `a post names the slot it came from`() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        NotificationCenter.getInstance(3).postNotificationName(NotificationCenter.dialogsNeedReload)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload)
        drain()
        assertEquals(listOf(3, -1), plugin.js.notifications.map { it.accountId })
        assertEquals(listOf("dialogsNeedReload", "dialogsNeedReload"), plugin.js.notifications.map { it.name })
    }

    @Test
    fun `only scalars cross and everything else is null`() {
        val plugin = granted()
        plugin.observe("updateInterfaces")
        NotificationCenter.getInstance(0).postNotificationName(
            NotificationCenter.updateInterfaces,
            7,
            9_000_000_000L,
            "hi",
            true,
            1.5,
            Double.NaN,
            'c',
            arrayOf("payload"),
            Any(),
        )
        drain()
        assertEquals(
            """[7,9000000000,"hi",true,1.5,null,"c",null,null]""",
            plugin.js.notifications.single().argsJson,
        )
    }

    @Test
    fun `the engine is entered from globalQueue and never from the post`() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.dialogsNeedReload)
        assertEquals(0, plugin.js.notifications.size, "the post must not have entered the engine")
        drain()
        assertEquals(1, plugin.js.notifications.size)
    }

    @Test
    fun `the payload is what the post carried, not what the array held later`() {
        val plugin = granted()
        plugin.observe("updateInterfaces")
        // the app's own observers rewrite the array in place, and this one is downstream of the
        // plugin's, so what the plugin was handed is only intact while the post is still the post
        NotificationCenter.getInstance(0).addObserver(
            { _, _, args -> args[0] = "rewritten" },
            NotificationCenter.updateInterfaces,
        )
        NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.updateInterfaces, "first")
        drain()
        assertEquals(listOf<Any?>("first"), argsOf(plugin.js.notifications.single()))
    }

    @Test
    fun `the disposer stops every observer it added`() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload", "updateInterfaces")
        plugin.stopObserving()
        assertEquals(0, observerCount())
        NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.dialogsNeedReload)
        drain()
        assertEquals(0, plugin.js.notifications.size)
    }

    @Test
    fun `detach stops every registration the engine made`() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload", callbackId = 1)
        plugin.observe("updateInterfaces", callbackId = 2)
        assertTrue(observerCount() > 0)
        PluginNotifications.detach(plugin.js)
        assertEquals(0, observerCount(), "an unloaded engine must not be reachable from the centre")
    }

    @Test
    fun `a dispose issued before the observer landed still removes it`() {
        val plugin = granted()
        AndroidUtilities.inu_deferUi(true)
        plugin.observe("dialogsNeedReload")
        plugin.stopObserving()
        AndroidUtilities.inu_runUi()
        assertEquals(0, observerCount())
    }

    @Test
    fun `a post in flight when the plugin was dropped never reaches its engine`() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.dialogsNeedReload)
        val engine = plugin.js
        plugin.engine = null
        drain()
        assertEquals(0, engine.notifications.size)
    }
}
