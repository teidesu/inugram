package desu.inugram.ui.settings


import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AboutActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAbout)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asHeader(UpdateHelper.getVersionInfoString())
        )
        items.add(
            UItem.asButton(
                BUTTON_GITHUB,
                LocaleController.getString(R.string.InuAboutGitHub),
                "teidesu/inugram",
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHANNEL_LINK,
                LocaleController.getString(R.string.InuAboutChannel),
                "@" + UpdateHelper.USERNAME,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUpdates)))
        items.add(
            UItem.asCheck(
                TOGGLE_UPDATES_ENABLED,
                LocaleController.getString(R.string.InuUpdatesEnabled),
            ).setChecked(InuConfig.UPDATES_ENABLED.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_CHECK_NOW,
                LocaleController.getString(R.string.InuUpdateCheckNow),
            )
        )
        items.add(UItem.asShadow(lastCheckLabel()))
    }

    var isChecking = false
    private fun lastCheckLabel(): String {
        val ms = InuConfig.UPDATE_LAST_CHECK_MS.value
        val text = when {
            isChecking -> LocaleController.getString(R.string.Checking)
            ms == 0L -> LocaleController.getString(R.string.MessageScheduledRepeatOptionNever)
            else -> LocaleController.formatDateTime(ms / 1000, true)
        }
        return LocaleController.formatString(R.string.InuUpdateLastChecked, text)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val ctx = context ?: return
        when (item.id) {
            BUTTON_GITHUB -> Browser.openUrl(ctx, "https://github.com/teidesu/inugram")
            BUTTON_CHANNEL_LINK -> Browser.openUrl(ctx, "https://t.me/" + UpdateHelper.USERNAME)
            TOGGLE_UPDATES_ENABLED -> {
                val new = InuConfig.UPDATES_ENABLED.toggle()
                (view as? TextCheckCell)?.isChecked = new
                if (!new) UpdateHelper.clearPending()
            }
            BUTTON_CHECK_NOW -> runManualCheck()
        }
    }

    private fun runManualCheck() {
        isChecking = true
        listView.adapter.update(true)
        UpdateHelper.check { result ->
            AndroidUtilities.runOnUIThread {
                isChecking = false
                listView?.adapter?.update(true)
                val msg: CharSequence = when (result) {
                    UpdateHelper.CheckResult.InFlight ->
                        LocaleController.getString(R.string.Checking)

                    UpdateHelper.CheckResult.UpToDate ->
                        LocaleController.getString(R.string.InuUpdateUpToDate)

                    is UpdateHelper.CheckResult.Updated -> {
                        val ctx = context ?: return@runOnUIThread
                        ApplicationLoader.applicationLoaderInstance?.showUpdateAppPopup(
                            ctx, result.update, UserConfig.selectedAccount,
                        )
                        return@runOnUIThread
                    }

                    is UpdateHelper.CheckResult.Error ->
                        LocaleController.formatString(R.string.InuUpdateError, result.message)
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, msg).show()
            }
        }
    }

    companion object {
        private val BUTTON_GITHUB = InuUtils.generateId()
        private val BUTTON_CHANNEL_LINK = InuUtils.generateId()
        private val TOGGLE_UPDATES_ENABLED = InuUtils.generateId()
        private val BUTTON_CHECK_NOW = InuUtils.generateId()
    }
}
