package desu.inugram.ui

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuConfig.UpdateChannelItem
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class InuAboutActivity : InuSettingsPageActivity() {
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
            UItem.asButton(
                BUTTON_UPDATE_CHANNEL,
                LocaleController.getString(R.string.InuUpdateChannel),
                channelLabel(InuConfig.UPDATE_CHANNEL.value),
            )
        )
        if (InuConfig.UPDATE_CHANNEL.value != UpdateChannelItem.DISABLED) {
            items.add(
                UItem.asButton(
                    BUTTON_CHECK_NOW,
                    LocaleController.getString(R.string.InuUpdateCheckNow),
                )
            )
            items.add(UItem.asShadow(lastCheckLabel()))
        }
    }

    var isChecking = false;
    private fun lastCheckLabel(): String {
        val text = run {
            if (isChecking) LocaleController.getString(R.string.Checking)
            val ms = InuConfig.UPDATE_LAST_CHECK_MS.value
            if (ms == 0L) LocaleController.getString(R.string.MessageScheduledRepeatOptionNever)
            LocaleController.formatDateTime(ms / 1000, true)
        }

        return LocaleController.formatString(R.string.InuUpdateLastChecked, text)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val ctx = context ?: return
        when (item.id) {
            BUTTON_GITHUB -> Browser.openUrl(ctx, "https://github.com/teidesu/inugram")
            BUTTON_CHANNEL_LINK -> Browser.openUrl(ctx, "https://t.me/" + UpdateHelper.USERNAME)
            BUTTON_UPDATE_CHANNEL -> showChannelSelector()
            BUTTON_CHECK_NOW -> runManualCheck()
        }
    }

    private fun showChannelSelector() {
        val values = intArrayOf(
            UpdateChannelItem.STABLE,
            UpdateChannelItem.CANARY,
            UpdateChannelItem.DISABLED,
        )
        val labels = arrayOf<CharSequence>(
            LocaleController.getString(R.string.InuUpdateChannelStable),
            LocaleController.getString(R.string.InuUpdateChannelCanary),
            LocaleController.getString(R.string.Disable),
        )
        val current = values.indexOf(InuConfig.UPDATE_CHANNEL.value).coerceAtLeast(0)
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuUpdateChannel))
                .setItems(labels, current) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.UPDATE_CHANNEL.value == newValue) return@setItems
                    InuConfig.UPDATE_CHANNEL.value = newValue
                    UpdateHelper.clearPending()
                    listView.adapter.update(true)
                }.create()
        )
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
        private val BUTTON_UPDATE_CHANNEL = InuUtils.generateId()
        private val BUTTON_CHECK_NOW = InuUtils.generateId()

        fun channelLabel(value: Int): String = when (value) {
            UpdateChannelItem.DISABLED -> LocaleController.getString(R.string.Disable)
            UpdateChannelItem.CANARY -> LocaleController.getString(R.string.InuUpdateChannelCanary)
            else -> LocaleController.getString(R.string.InuUpdateChannelStable)
        }
    }
}
