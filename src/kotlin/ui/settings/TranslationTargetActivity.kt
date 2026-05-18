package desu.inugram.ui.settings


import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogRadioCell
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TranslationTargetActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslationTarget)

    private val languages by lazy { TranslateController.getLanguages() }
    private val suggested by lazy { TranslateController.getSuggestedLanguages(null) }
    private val cells = HashMap<String, DialogRadioCell>()

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val app = LocaleController.getInstance().currentLocaleInfo
        items.add(UItem.asCustom(cellFor("", "") {
            it.setTextAndValue(
                LocaleController.getString(R.string.InuTranslationTargetFollowApp),
                app?.name ?: "",
                currentValue().isEmpty(),
                suggested.isNotEmpty(),
            )
        }))
        appendLangs(items, "suggest:", suggested)
        items.add(UItem.asShadow(null))
        appendLangs(items, "", languages)
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {}

    private fun appendLangs(items: ArrayList<UItem>, keyPrefix: String, langs: List<TranslateController.Language>) {
        val current = currentValue()
        for ((i, lang) in langs.withIndex()) {
            val code = lang.code ?: continue
            val title = lang.displayName
            val subtitle = lang.ownDisplayName.takeIf { it != title } ?: ""
            items.add(UItem.asCustom(cellFor("$keyPrefix$code", code) {
                it.setTextAndValue(title, subtitle, code == current, i < langs.size - 1)
            }))
        }
    }

    private fun currentValue(): String =
        if (MessagesController.getGlobalMainSettings().contains(PREF_KEY)) TranslateAlert2.getToLanguage() else ""

    private fun select(newValue: String) {
        if (newValue == currentValue()) return
        if (newValue.isEmpty()) TranslateAlert2.resetToLanguage()
        else TranslateAlert2.setToLanguage(newValue)
        cells.values.forEach { it.setChecked(it.tag == newValue, true) }
    }

    private inline fun cellFor(key: String, code: String, configure: (DialogRadioCell) -> Unit): DialogRadioCell =
        cells.getOrPut(key) {
            DialogRadioCell(context).also {
                configure(it)
                it.tag = code
                it.background = Theme.getSelectorDrawable(false)
                it.setOnClickListener { _ -> select(code) }
            }
        }

    companion object {
        private const val PREF_KEY = "translate_to_language"
    }
}
