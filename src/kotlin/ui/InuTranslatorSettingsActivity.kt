package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.RestrictedLanguagesSelectActivity

class InuTranslatorSettingsActivity : InuSettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslator)

    private val translateController get() = MessagesController.getInstance(currentAccount).translateController

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_TRANSLATE_BUTTON,
                LocaleController.getString(R.string.ShowTranslateButton),
            ).setChecked(translateController.isContextTranslateEnabled)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON,
                LocaleController.getString(R.string.ShowTranslateChatButton),
            ).setChecked(translateController.isChatTranslateEnabled)
        )
        items.add(
            UItem.asButton(
                BUTTON_TARGET_LANG,
                LocaleController.getString(R.string.InuTranslationTarget),
                targetLangLabel(),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DO_NOT_TRANSLATE,
                LocaleController.getString(R.string.DoNotTranslate),
                doNotTranslateLabel(),
            )
        )
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asCheck(
                TOGGLE_IN_PLACE_TRANSLATION,
                LocaleController.getString(R.string.InuInPlaceTranslation),
            ).setChecked(InuConfig.IN_PLACE_TRANSLATION.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_TRANSLATE_WEB_PREVIEWS,
                LocaleController.getString(R.string.InuTranslateWebPreviews),
            ).setChecked(InuConfig.TRANSLATE_WEB_PREVIEWS.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateWebPreviewsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_IN_PLACE_TRANSLATION -> {
                val new = InuConfig.IN_PLACE_TRANSLATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_TRANSLATE_WEB_PREVIEWS -> {
                val new = InuConfig.TRANSLATE_WEB_PREVIEWS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_TRANSLATE_BUTTON -> {
                val new = !translateController.isContextTranslateEnabled
                translateController.isContextTranslateEnabled = new
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateSearchSettings)
            }

            TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON -> {
                val new = !translateController.isChatTranslateEnabled
                translateController.isChatTranslateEnabled = new
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateSearchSettings)
            }

            BUTTON_DO_NOT_TRANSLATE -> presentFragment(RestrictedLanguagesSelectActivity())

            BUTTON_TARGET_LANG -> presentFragment(InuTranslationTargetActivity())
        }
    }

    private fun targetLangLabel(): String {
        if (!MessagesController.getGlobalMainSettings().contains("translate_to_language")) {
            return LocaleController.getString(R.string.InuTranslationTargetFollowApp)
        }
        val code = TranslateAlert2.getToLanguage()
        return TranslateAlert2.languageName(code)?.let { TranslateAlert2.capitalFirst(it) } ?: code.uppercase()
    }

    private fun doNotTranslateLabel(): String {
        val langs = RestrictedLanguagesSelectActivity.getRestrictedLanguages()
        if (langs.isEmpty()) return LocaleController.getString(R.string.None)
        if (langs.size > 2) return LocaleController.formatPluralString("Languages", langs.size)
        return langs.joinToString(", ") {
            TranslateAlert2.languageName(it)?.let { name -> TranslateAlert2.capitalFirst(name) } ?: it.uppercase()
        }
    }

    companion object {
        private val TOGGLE_IN_PLACE_TRANSLATION = InuUtils.generateId()
        private val TOGGLE_TRANSLATE_WEB_PREVIEWS = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_BUTTON = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON = InuUtils.generateId()
        private val BUTTON_DO_NOT_TRANSLATE = InuUtils.generateId()
        private val BUTTON_TARGET_LANG = InuUtils.generateId()
    }
}
