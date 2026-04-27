package desu.inugram

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import desu.inugram.helpers.ApkInstaller
import desu.inugram.helpers.UpdateHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.IUpdateLayout

class ApplicationLoaderImpl : org.telegram.messenger.ApplicationLoaderImpl() {
    override fun isStandalone(): Boolean = true

    override fun isCustomUpdate(): Boolean = true

    override fun checkUpdate(force: Boolean, whenDone: Runnable?) {
        UpdateHelper.checkForCustomUpdate(force, whenDone)
    }

    override fun getUpdate(): BetaUpdate? = UpdateHelper.pendingBetaUpdate

    override fun showCustomUpdateAppPopup(context: Context, update: BetaUpdate, account: Int): Boolean {
        val tl = SharedConfig.pendingAppUpdate ?: return false
        return showUpdateAppPopup(context, tl, account)
    }

    override fun checkApkInstallPermissions(context: Context): Boolean {
        if (!ApplicationLoader.applicationContext.packageManager.canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show()
            return false
        }
        return true
    }

    override fun openApkInstall(activity: Activity, document: TLRPC.Document): Boolean {
        val file = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true)
        if (file == null || !file.exists()) return false
        ApkInstaller.installUpdate(activity, document)
        return true
    }

    override fun showUpdateAppPopup(context: Context, update: TLRPC.TL_help_appUpdate, account: Int): Boolean {
        try {
            UpdateAppAlertDialog(context, update, account).show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return true
    }

    override fun takeUpdateLayout(activity: Activity, sideMenuContainer: ViewGroup): IUpdateLayout {
        return UpdateLayout(activity, sideMenuContainer)
    }
}
