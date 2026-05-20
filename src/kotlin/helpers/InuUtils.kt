package desu.inugram.helpers

import android.app.Activity
import android.os.Build
import android.view.View
import kotlin.system.exitProcess

public object InuUtils {
    private var _nextId = 1;
    fun generateId(): Int {
        return _nextId++
    }

    @JvmStatic
    fun setAutofillHint(view: View, hint: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setAutofillHints(hint)
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
    }

    @JvmStatic
    fun restartApp(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        activity.finishAffinity()
        activity.startActivity(intent)
        exitProcess(0)
    }
}