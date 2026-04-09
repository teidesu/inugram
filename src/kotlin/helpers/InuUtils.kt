package desu.inugram.helpers

import android.os.Build
import android.view.View

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
}