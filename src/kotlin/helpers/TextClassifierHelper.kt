package desu.inugram.helpers

import android.os.Build
import android.os.LocaleList
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextSelection
import android.widget.TextView
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig

object TextClassifierHelper {
    private val ALLOWED_ENTITIES = setOf(
        TextClassifier.TYPE_EMAIL,
        TextClassifier.TYPE_PHONE,
        TextClassifier.TYPE_URL,
    )

    @JvmStatic
    fun configure(view: TextView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        when (InuConfig.TEXT_CLASSIFIER_MODE.value) {
            InuConfig.TextClassifierModeItem.IMPROVED -> {
                val system = view.context.getSystemService(TextClassificationManager::class.java)?.textClassifier
                    ?: return
                view.setTextClassifier(FilteringTextClassifier(system))
            }

            InuConfig.TextClassifierModeItem.OFF -> view.setTextClassifier(TextClassifier.NO_OP)
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class FilteringTextClassifier(private val delegate: TextClassifier) : TextClassifier {
        override fun suggestSelection(request: TextSelection.Request): TextSelection {
            val result = delegate.suggestSelection(request)
            if (topEntityAllowed(result)) return result
            return TextSelection.Builder(request.startIndex, request.endIndex).build()
        }

        override fun suggestSelection(
            text: CharSequence,
            startIndex: Int,
            endIndex: Int,
            defaultLocales: LocaleList?,
        ): TextSelection {
            val result = delegate.suggestSelection(text, startIndex, endIndex, defaultLocales)
            if (topEntityAllowed(result)) return result
            return TextSelection.Builder(startIndex, endIndex).build()
        }

        override fun classifyText(request: TextClassification.Request): TextClassification {
            val result = delegate.classifyText(request)
            return if (topEntityAllowed(result)) result else TextClassification.Builder().build()
        }

        override fun classifyText(
            text: CharSequence,
            startIndex: Int,
            endIndex: Int,
            defaultLocales: LocaleList?,
        ): TextClassification {
            val result = delegate.classifyText(text, startIndex, endIndex, defaultLocales)
            return if (topEntityAllowed(result)) result else TextClassification.Builder().build()
        }

        private fun topEntityAllowed(selection: TextSelection): Boolean {
            if (selection.entityCount == 0) return false
            return selection.getEntity(0) in ALLOWED_ENTITIES
        }

        private fun topEntityAllowed(classification: TextClassification): Boolean {
            if (classification.entityCount == 0) return false
            return classification.getEntity(0) in ALLOWED_ENTITIES
        }
    }
}
