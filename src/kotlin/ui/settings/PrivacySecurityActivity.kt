package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ParanoiaHelper
import desu.inugram.helpers.UrlCleanerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PrivacySecurityActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPrivacySecurity)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_PASSCODE,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuPerAccountPasscode)
            )
        )
        if (!ParanoiaHelper.isParanoia()) {
            items.add(
                UItem.asButton(
                    BUTTON_PARANOIA,
                    R.drawable.menu_hide_gift,
                    LocaleController.getString(R.string.InuParanoiaMode)
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyTitle)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_MY_PHONE_NUMBER,
                LocaleController.getString(R.string.InuHideMyPhoneNumber)
            ).setChecked(InuConfig.HIDE_MY_PHONE_NUMBER.value)
        )
        items.add(stripTrackingParamsItem())
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_DRAFT_UPLOAD,
                R.string.InuDisableDraftUpload,
                R.string.InuDisableDraftUploadInfo,
                InuConfig.DISABLE_DRAFT_UPLOAD.value
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PASSCODE -> presentFragment(PasscodeSettingsActivity())
            BUTTON_PARANOIA -> presentFragment(ParanoiaActivity())

            TOGGLE_HIDE_MY_PHONE_NUMBER -> {
                val new = InuConfig.HIDE_MY_PHONE_NUMBER.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_STRIP_TRACKING_PARAMS -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_DRAFT_UPLOAD -> {
                val new = InuConfig.DISABLE_DRAFT_UPLOAD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (item.id == TOGGLE_STRIP_TRACKING_PARAMS) {
            showStripTrackingParamsOptions(item, view)
            return true
        }
        return super.onLongClick(item, view, position, x, y)
    }

    private fun stripTrackingParamsItem(): UItem {
        val title = LocaleController.getString(R.string.InuStripTrackingParams)
        val subtitle = LocaleController.formatString(
            R.string.InuStripTrackingParamsSource, UrlCleanerHelper.lastUpdated ?: "?",
        )
        val checked = InuConfig.STRIP_TRACKING_PARAMS.value
        return UItem.asButtonCheck(TOGGLE_STRIP_TRACKING_PARAMS, title, subtitle).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.apply {
                    setTextAndValueAndCheck(title, subtitle, checked, 0, true, true)
                    setDrawLine(false)
                }
            }
        }
    }

    private fun showStripTrackingParamsOptions(item: UItem, anchor: View) {
        val opts = ItemOptions.makeOptions(this, anchor)
            .add(R.drawable.msg_download, LocaleController.getString(R.string.InuStripTrackingParamsUpdate)) {
                fetchLatestStripTrackingParams()
            }
        if (UrlCleanerHelper.isUsingOverride) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuStripTrackingParamsRevert)) {
                UrlCleanerHelper.resetToBundled()
                Utilities.globalQueue.postRunnable {
                    UrlCleanerHelper.preload()
                    AndroidUtilities.runOnUIThread { listView?.adapter?.update(true) }
                }
            }
        }
        addCopyLinkOption(opts, item)
        opts.show()
    }

    private fun fetchLatestStripTrackingParams() {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { UrlCleanerHelper.fetchLatest() }
            UrlCleanerHelper.preload()
            AndroidUtilities.runOnUIThread {
                listView?.adapter?.update(true)
                val bulletin = BulletinFactory.of(this)
                result.fold(
                    onSuccess = { updated ->
                        val msg = if (updated) R.string.InuStripTrackingParamsUpdated
                        else R.string.InuStripTrackingParamsAlreadyLatest
                        bulletin.createSimpleBulletin(R.raw.contact_check, LocaleController.getString(msg)).show()
                    },
                    onFailure = { err ->
                        bulletin.createSimpleBulletin(
                            R.raw.error,
                            LocaleController.getString(R.string.InuStripTrackingParamsUpdateFailed),
                            err.message ?: "",
                        ).show()
                    },
                )
            }
        }
    }

    companion object {
        private val BUTTON_PASSCODE = InuUtils.generateId()
        private val BUTTON_PARANOIA = InuUtils.generateId()
        private val TOGGLE_HIDE_MY_PHONE_NUMBER = InuUtils.generateId()
        private val TOGGLE_STRIP_TRACKING_PARAMS = InuUtils.generateId()
        private val TOGGLE_DISABLE_DRAFT_UPLOAD = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "privacy-security",
            titleRes = R.string.InuPrivacySecurity,
            iconRes = R.drawable.msg_permissions,
            factory = ::PrivacySecurityActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-my-phone-number", R.string.InuHideMyPhoneNumber, TOGGLE_HIDE_MY_PHONE_NUMBER),
                SearchRegistry.Entry("strip-tracking-params", R.string.InuStripTrackingParams, TOGGLE_STRIP_TRACKING_PARAMS),
                SearchRegistry.Entry("disable-draft-upload", R.string.InuDisableDraftUpload, TOGGLE_DISABLE_DRAFT_UPLOAD),
            ),
        )
    }
}
