package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginNotifications
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig

/**
 * `unsafe.notificationCenter`: which registrations are accepted, what a post is allowed to say, and
 * that every observer is gone by the time the engine is.
 *
 * On a device because the event *vocabulary* is reflected off [NotificationCenter]'s own
 * `public static final int` fields, so a stand-in class with names of its own tests nothing: the
 * name a plugin passes has to be one the app really has.
 */
class PluginNotificationsTest {
    @Before
    fun setUp() = resetBridge()

    private fun granted(vararg extra: String) =
        startPlugin("notifications", "unsafe.notificationCenter", *extra)

    /** the add takes a ui hop, and a test does not run on the ui thread, so it is really deferred */
    private fun Plugin.observe(vararg events: String, callbackId: Int = 1): String? =
        js.listener!!.register(callbackId, arrayOf(*events)).also { flushUi() }

    private fun Plugin.stopObserving(callbackId: Int = 1) =
        js.listener!!.unregister(callbackId).also { flushUi() }

    private fun post(centre: NotificationCenter, id: Int, vararg args: Any?) =
        onUi { centre.postNotificationName(id, *args) }

    private fun argsOf(notification: RecordingQuickJs.Notification): List<Any?> {
        val json = JSONArray(notification.argsJson)
        return (0 until json.length()).map { if (json.isNull(it)) null else json.get(it) }
    }

    @Test
    fun a_name_the_app_does_not_have_refuses_the_whole_registration() {
        val plugin = granted()
        assertPluginError("invalid-argument", plugin.observe("dialogsNeedReload", "notAnEvent"))
        assertEquals(0, pluginObserverCount(), "a refused registration must leave nothing observing")
    }

    @Test
    fun a_plugin_without_the_grant_observes_nothing() {
        val plugin = startPlugin("notifications")
        assertPluginError("not-granted", plugin.observe("dialogsNeedReload"))
        assertEquals(0, pluginObserverCount())
    }

    @Test
    fun an_accepted_registration_observes_every_centre_a_post_could_come_from() {
        val plugin = granted()
        assertNull(plugin.observe("dialogsNeedReload", "updateInterfaces"))
        assertEquals(2 * (UserConfig.MAX_ACCOUNT_COUNT + 1), pluginObserverCount())
    }

    @Test
    fun a_post_names_the_slot_it_came_from() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        post(NotificationCenter.getInstance(3), NotificationCenter.dialogsNeedReload)
        post(NotificationCenter.getGlobalInstance(), NotificationCenter.dialogsNeedReload)
        drain()
        assertEquals(listOf(3, -1), plugin.js.notifications.map { it.accountId })
        assertEquals(listOf("dialogsNeedReload", "dialogsNeedReload"), plugin.js.notifications.map { it.name })
    }

    /**
     * a slot the app has no observers of its own on, and an event stock does not debounce:
     * `shouldDebounce` holds `updateInterfaces` for 250ms and dedups it, so a payload posted under
     * that name arrives after the test, if at all.
     */
    private val quietCentre get() = NotificationCenter.getInstance(5)

    @Test
    fun only_scalars_cross_and_everything_else_is_null() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        post(
            quietCentre,
            NotificationCenter.dialogsNeedReload,
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
    fun the_engine_is_entered_from_globalQueue_and_never_from_the_post() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        post(NotificationCenter.getInstance(0), NotificationCenter.dialogsNeedReload)
        assertEquals(0, plugin.js.notifications.size, "the post must not have entered the engine")
        drain()
        assertEquals(1, plugin.js.notifications.size)
    }

    @Test
    fun the_payload_is_what_the_post_carried_not_what_the_array_held_later() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        // the app's own observers rewrite the array in place, and this one is downstream of the
        // plugin's, so what the plugin was handed is only intact while the post is still the post
        val rewriter = NotificationCenter.NotificationCenterDelegate { _, _, args -> args[0] = "rewritten" }
        onUi { quietCentre.addObserver(rewriter, NotificationCenter.dialogsNeedReload) }
        post(quietCentre, NotificationCenter.dialogsNeedReload, "first")
        drain()
        onUi { quietCentre.removeObserver(rewriter, NotificationCenter.dialogsNeedReload) }
        assertEquals(listOf<Any?>("first"), argsOf(plugin.js.notifications.single()))
    }

    @Test
    fun the_disposer_stops_every_observer_it_added() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload", "updateInterfaces")
        plugin.stopObserving()
        assertEquals(0, pluginObserverCount())
        post(NotificationCenter.getInstance(0), NotificationCenter.dialogsNeedReload)
        drain()
        assertEquals(0, plugin.js.notifications.size)
    }

    @Test
    fun detach_stops_every_registration_the_engine_made() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload", callbackId = 1)
        plugin.observe("updateInterfaces", callbackId = 2)
        assertTrue(pluginObserverCount() > 0)
        PluginNotifications.detach(plugin.js)
        flushUi()
        assertEquals(0, pluginObserverCount(), "an unloaded engine must not be reachable from the centre")
    }

    @Test
    fun a_dispose_issued_before_the_observer_landed_still_removes_it() {
        val plugin = granted()
        // no flushUi between the two: `runOnUIThread` always posts, so both hops are still queued
        plugin.js.listener!!.register(1, arrayOf("dialogsNeedReload"))
        plugin.js.listener!!.unregister(1)
        flushUi()
        assertEquals(0, pluginObserverCount())
    }

    @Test
    fun a_post_in_flight_when_the_plugin_was_dropped_never_reaches_its_engine() {
        val plugin = granted()
        plugin.observe("dialogsNeedReload")
        post(NotificationCenter.getInstance(0), NotificationCenter.dialogsNeedReload)
        val engine = plugin.js
        plugin.engine = null
        drain()
        assertEquals(0, engine.notifications.size)
    }
}
