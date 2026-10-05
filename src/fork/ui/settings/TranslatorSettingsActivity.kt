package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
// #if PLUGINS
import desu.inugram.helpers.plugins.telegram.PluginTranslation
// #endif
import desu.inugram.helpers.translate.TranslationProviderHelper
import desu.inugram.ui.settings.TranslationTargetActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet
import org.telegram.ui.PremiumPreviewFragment
import org.telegram.ui.RestrictedLanguagesSelectActivity

class TranslatorSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslator)

    private val translateController get() = MessagesController.getInstance(currentAccount).translateController

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // #if PLUGINS
        items.add(
            UItem.asButton(
                BUTTON_PROVIDER,
                LocaleController.getString(R.string.InuTranslationProvider),
                providerLabel(),
            )
        )
        items.add(
            UItem.asShadow(
                AndroidUtilities.replaceArrows(
                    AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.InuTranslationProviderInfo)) {
                        presentFragment(PluginsActivity())
                    },
                    true,
                )
            )
        )
        // #endif
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
                .setLocked(!TranslationProviderHelper.canTranslateChats(currentAccount))
                .onBind(Utilities.Callback { view ->
                    (view as TextCheckCell).setCheckBoxIcon(
                        if (TranslationProviderHelper.canTranslateChats(currentAccount)) 0 else R.drawable.permission_locked
                    )
                })
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
        items.add(
            UItem.asCheck(
                TOGGLE_KEEP_ORIGINAL,
                LocaleController.getString(R.string.InuKeepOriginalAfterTranslation),
            ).setChecked(InuConfig.KEEP_ORIGINAL_AFTER_TRANSLATION.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_AUTO_DETECT_LANG,
                LocaleController.getString(R.string.InuTranslateAutoDetectLang),
            ).setChecked(InuConfig.TRANSLATE_AUTO_DETECT_LANG.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateAutoDetectLangInfo)))
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

            TOGGLE_KEEP_ORIGINAL -> {
                val new = InuConfig.KEEP_ORIGINAL_AFTER_TRANSLATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateInterfaces, 0)
            }

            TOGGLE_AUTO_DETECT_LANG -> {
                val new = InuConfig.TRANSLATE_AUTO_DETECT_LANG.toggle()
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
                if (new && !TranslationProviderHelper.canTranslateChats(currentAccount)) {
                    showDialog(
                        PremiumFeatureBottomSheet(
                            this,
                            PremiumPreviewFragment.PREMIUM_FEATURE_TRANSLATIONS,
                            false,
                        )
                    )
                    return
                }
                translateController.isChatTranslateEnabled = new
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateSearchSettings)
            }

            BUTTON_DO_NOT_TRANSLATE -> presentFragment(RestrictedLanguagesSelectActivity())

            // #if PLUGINS
            BUTTON_PROVIDER -> showProviderDialog()
            // #endif

            BUTTON_TARGET_LANG -> presentFragment(TranslationTargetActivity())
        }
    }

    // #if PLUGINS
    private fun showProviderDialog() {
        val providers = PluginTranslation.providers
        val selected = InuConfig.TRANSLATION_PROVIDER.value
        val items = listOf(RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTranslationProviderTelegram))) +
            providers.map { RadioDialogBuilder.Item(it.name, LocaleController.formatString(R.string.InuTranslationProviderFromPlugin, it.session.manifest.name)) }
        val selectedIndex = if (selected.isEmpty()) 0 else providers.indexOfFirst { it.key == selected }.let { if (it < 0) -1 else it + 1 }
        val dialog = RadioDialogBuilder(context, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuTranslationProvider))
            .setItems(items, selectedIndex) { _, index ->
                InuConfig.TRANSLATION_PROVIDER.value = if (index == 0) "" else providers[index - 1].key
                listView?.adapter?.update(true)
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateSearchSettings)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create()
        showDialog(dialog)
    }

    private fun providerLabel(): String {
        val selected = InuConfig.TRANSLATION_PROVIDER.value
        if (selected.isEmpty()) return LocaleController.getString(R.string.InuTranslationProviderTelegram)
        return PluginTranslation.findProvider(selected)?.name
            ?: LocaleController.getString(R.string.InuTranslationProviderUnavailable)
    }

    // #endif

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
        private val TOGGLE_KEEP_ORIGINAL = InuUtils.generateId()
        private val TOGGLE_AUTO_DETECT_LANG = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_BUTTON = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON = InuUtils.generateId()
        private val BUTTON_DO_NOT_TRANSLATE = InuUtils.generateId()
        private val BUTTON_TARGET_LANG = InuUtils.generateId()
        private val BUTTON_PROVIDER = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "translator",
            titleRes = R.string.InuTranslator,
            iconRes = R.drawable.msg_translate,
            factory = ::TranslatorSettingsActivity,
            entries = listOf(
                // #if PLUGINS
                SearchRegistry.Entry("translation-provider", R.string.InuTranslationProvider, BUTTON_PROVIDER),
                // #endif
                SearchRegistry.Entry("show-translate-button", R.string.ShowTranslateButton, TOGGLE_SHOW_TRANSLATE_BUTTON),
                SearchRegistry.Entry("show-translate-chat-button", R.string.ShowTranslateChatButton, TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON),
                SearchRegistry.Entry("translation-target", R.string.InuTranslationTarget, BUTTON_TARGET_LANG),
                SearchRegistry.Entry("do-not-translate", R.string.DoNotTranslate, BUTTON_DO_NOT_TRANSLATE),
                SearchRegistry.Entry("in-place-translation", R.string.InuInPlaceTranslation, TOGGLE_IN_PLACE_TRANSLATION),
                SearchRegistry.Entry("translate-web-previews", R.string.InuTranslateWebPreviews, TOGGLE_TRANSLATE_WEB_PREVIEWS),
                SearchRegistry.Entry("keep-original-after-translation", R.string.InuKeepOriginalAfterTranslation, TOGGLE_KEEP_ORIGINAL),
                SearchRegistry.Entry("translate-auto-detect-lang", R.string.InuTranslateAutoDetectLang, TOGGLE_AUTO_DETECT_LANG),
            ),
        )
    }
}
