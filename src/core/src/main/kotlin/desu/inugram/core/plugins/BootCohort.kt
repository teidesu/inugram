package desu.inugram.core.plugins

/**
 * Selects plugins to load synchronously in `ApplicationLoader.postInitApplication` before
 * updates are applied. Other plugins wait for the first UI.
 *
 * Push wakeups call this method, decrypt updates on stageQueue, and call `processUpdates`
 * without creating an activity. Keep the cohort as small as grants allow to limit push latency.
 */
object BootCohort {
    /**
     * apis a headless path dispatches into. `unsafe.notificationCenter` qualifies because the
     * app's bus is posted on the main looper, which a process with no activity still has: the
     * same `processUpdates` posts `didReceiveNewMessages` from it.
     *
     * `inu.registerSettings` and `inu.register*Action` are deliberately absent - they need no
     * grant, and nothing dispatches into either without a screen the user is looking at.
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

    /**
     * every millisecond here is added to every push notification, which is why it bounds the
     * whole cohort rather than each plugin.
     */
    const val EARLY_BUDGET_MILLIS = 500L
}
