package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginPermissions

/** Push wakeups decrypt on stageQueue and call `processUpdates` with no activity; keep the cohort small. */
object BootCohort {
    /**
     * `unsafe.notificationCenter` qualifies: the bus posts on the main looper, which a headless process has.
     * `inu.registerSettings` and `inu.register*Action` need a screen, so they are absent.
     */
    val HEADLESS_APIS: Set<String> = setOf(
        "interceptRpc",
        "interceptUpdate",
        "interceptSendMessage",
        "onUpdate",
        "unsafe.notificationCenter",
    )

    fun bootsEarly(permissions: PluginPermissions): Boolean =
        HEADLESS_APIS.any { permissions.has(it) }

    /** added to every push notification, so it bounds the whole cohort */
    const val EARLY_BUDGET_MILLIS = 500L
}
