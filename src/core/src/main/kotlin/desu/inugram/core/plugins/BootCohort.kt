package desu.inugram.core.plugins

/**
 * Which plugins must be running before the app applies anything, and so load synchronously in
 * `ApplicationLoader.postInitApplication` rather than at first UI.
 *
 * The path that forces it is a push wakeup: `PushListenerController` calls `postInitApplication`,
 * posts to `stageQueue`, decrypts and hands a synthesized `TL_updates` to `processUpdates` with no
 * activity ever created. A plugin loaded at first UI is absent for all of it - and since this is
 * on the critical path of every push, the cohort stays as small as the manifest allows.
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
