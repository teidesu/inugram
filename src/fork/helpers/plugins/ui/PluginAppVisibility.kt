package desu.inugram.helpers.plugins.ui

import desu.inugram.helpers.plugins.EngineDispatch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import desu.inugram.helpers.plugins.PluginManager
import org.telegram.messenger.AndroidUtilities

object PluginAppVisibility {
    // an activity restarting across a config change stops before its replacement starts
    private const val BACKGROUND_DEBOUNCE_MS = 700L

    // keep in sync with rust `lifecycle::AppMode::from_code`
    const val MODE_FOREGROUND = 0
    const val MODE_RESUMED = 1
    const val MODE_PAUSED = 2
    const val MODE_BACKGROUND = 3

    // false until an activity starts: a push-woken process has no ui
    @Volatile
    private var foreground = false

    val isForeground: Boolean get() = foreground
    private var watching = false
    private var startedActivities = 0
    private var resumedActivities = 0
    private val enterBackground = Runnable { if (startedActivities == 0) publish(MODE_BACKGROUND) }

    /** before any activity exists, so the count never misses the first start */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext as? Application
        if (app == null) {
            publish(MODE_FOREGROUND)
            return
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                AndroidUtilities.cancelRunOnUIThread(enterBackground)
                publish(MODE_FOREGROUND)
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities > 0) return
                startedActivities = 0
                AndroidUtilities.runOnUIThread(enterBackground, BACKGROUND_DEBOUNCE_MS)
            }

            // one activity handing over to another must not read as leaving the app
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                publish(MODE_RESUMED)
            }

            override fun onActivityPaused(activity: Activity) {
                if (--resumedActivities > 0) return
                resumedActivities = 0
                publish(MODE_PAUSED)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** a pause leaves the app visible */
    private fun publish(mode: Int) {
        when (mode) {
            MODE_FOREGROUND -> if (foreground) return else foreground = true
            MODE_BACKGROUND -> if (!foreground) return else foreground = false
        }
        // tracked either way, so a plugin started later reads the real state
        if (!PluginManager.anyRunning) return
        EngineDispatch.scheduler.postRunnable {
            for (plugin in PluginManager.plugins()) plugin.engine?.appVisibilityChanged(mode)
        }
    }
}
