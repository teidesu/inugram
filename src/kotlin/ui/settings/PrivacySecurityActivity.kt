package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.BiometricHelper
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.UrlCleanerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PrivacySecurityActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPrivacySecurity)

    private var sourceRow: TextDetailSettingsCell? = null

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
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_DRAFT_UPLOAD,
                R.string.InuDisableDraftUpload,
                R.string.InuDisableDraftUploadInfo,
                InuConfig.DISABLE_DRAFT_UPLOAD.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStripTrackingParams)))
        items.add(
            UItem.asCheck(
                TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN,
                LocaleController.getString(R.string.InuStripTrackingParamsOnOpen)
            ).setChecked(InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE,
                LocaleController.getString(R.string.InuStripTrackingParamsOnPaste)
            ).setChecked(InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value)
        )
        items.add(UItem.asCustom(BUTTON_STRIP_TRACKING_PARAMS_SOURCE, getOrCreateSourceRow()))
        items.add(UItem.asShadow(null))

        if (BiometricHelper.isSupported()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuBiometricConfirm)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_DELETE_CHAT,
                    R.string.InuBiometricConfirmDeleteChat,
                    R.string.InuBiometricConfirmDeleteChatInfo,
                    InuConfig.BIOMETRIC_CONFIRM_DELETE_CHAT.value
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_LOGOUT,
                    R.string.InuBiometricConfirmLogout,
                    R.string.InuBiometricConfirmLogoutInfo,
                    InuConfig.BIOMETRIC_CONFIRM_LOGOUT.value
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL,
                    R.string.InuBiometricAllowDeviceCredential,
                    R.string.InuBiometricAllowDeviceCredentialInfo,
                    InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.value
                )
            )
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PASSCODE -> presentFragment(PasscodeSettingsActivity())
            BUTTON_PARANOIA -> presentFragment(ParanoiaActivity())

            TOGGLE_HIDE_MY_PHONE_NUMBER -> {
                val new = InuConfig.HIDE_MY_PHONE_NUMBER.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_DRAFT_UPLOAD -> {
                val new = InuConfig.DISABLE_DRAFT_UPLOAD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_DELETE_CHAT -> {
                val new = InuConfig.BIOMETRIC_CONFIRM_DELETE_CHAT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_LOGOUT -> {
                val new = InuConfig.BIOMETRIC_CONFIRM_LOGOUT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL -> {
                val new = InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    private fun getOrCreateSourceRow(): TextDetailSettingsCell {
        sourceRow?.let { return it }
        val cell = TextDetailSettingsCell(context!!)
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        ))
        cell.setOnClickListener { showStripTrackingParamsOptions(it) }
        cell.setOnLongClickListener { showStripTrackingParamsOptions(it); true }
        sourceRow = cell
        refreshSourceRow()
        return cell
    }

    private fun refreshSourceRow() {
        val cell = sourceRow ?: return
        val title = LocaleController.getString(R.string.InuStripTrackingParamsSourceRow)
        val subtitle = LocaleController.formatString(
            R.string.InuStripTrackingParamsSource, UrlCleanerHelper.lastUpdated ?: "?",
        )
        cell.setTextAndValue(title, subtitle, false)
    }

    private fun showStripTrackingParamsOptions(anchor: View) {
        // scrim on the asCustom FrameLayout parent so ItemOptions picks up the section clip bg
        val scrim = (anchor.parent as? View) ?: anchor
        val opts = ItemOptions.makeOptions(this, scrim)
            .add(R.drawable.msg_download, LocaleController.getString(R.string.InuStripTrackingParamsUpdate)) {
                fetchLatestStripTrackingParams()
            }
        if (UrlCleanerHelper.isUsingOverride) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuStripTrackingParamsRevert)) {
                UrlCleanerHelper.resetToBundled()
                Utilities.globalQueue.postRunnable {
                    UrlCleanerHelper.preload()
                    AndroidUtilities.runOnUIThread { refreshSourceRow() }
                }
            }
        }
        opts.show()
    }

    private fun fetchLatestStripTrackingParams() {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { UrlCleanerHelper.fetchLatest() }
            UrlCleanerHelper.preload()
            AndroidUtilities.runOnUIThread {
                refreshSourceRow()
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
        private val TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN = InuUtils.generateId()
        private val TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE = InuUtils.generateId()
        private val BUTTON_STRIP_TRACKING_PARAMS_SOURCE = InuUtils.generateId()
        private val TOGGLE_DISABLE_DRAFT_UPLOAD = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_DELETE_CHAT = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_LOGOUT = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "privacy-security",
            titleRes = R.string.InuPrivacySecurity,
            iconRes = R.drawable.msg_permissions,
            factory = ::PrivacySecurityActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-my-phone-number", R.string.InuHideMyPhoneNumber, TOGGLE_HIDE_MY_PHONE_NUMBER),
                SearchRegistry.Entry("strip-tracking-params", R.string.InuStripTrackingParamsOnOpen, TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN),
                SearchRegistry.Entry("strip-tracking-params-paste", R.string.InuStripTrackingParamsOnPaste, TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE),
                SearchRegistry.Entry("disable-draft-upload", R.string.InuDisableDraftUpload, TOGGLE_DISABLE_DRAFT_UPLOAD),
                SearchRegistry.Entry("biometric-confirm-delete-chat", R.string.InuBiometricConfirmDeleteChat, TOGGLE_BIOMETRIC_DELETE_CHAT),
                SearchRegistry.Entry("biometric-confirm-logout", R.string.InuBiometricConfirmLogout, TOGGLE_BIOMETRIC_LOGOUT),
                SearchRegistry.Entry("biometric-allow-device-credential", R.string.InuBiometricAllowDeviceCredential, TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL),
            ),
        )
    }
}
