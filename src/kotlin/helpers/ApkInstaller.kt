package desu.inugram.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.messenger.XiaomiUtilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ApkInstaller {
    private const val ACTION = "desu.inugram.helpers.ApkInstaller.STATUS"

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var dialog: AlertDialog? = null
    @Volatile
    private var brokenInstaller: Boolean? = null

    fun installUpdate(activity: Activity, document: TLRPC.Document) {
        if (hasBrokenPackageInstaller(activity)) {
            AndroidUtilities.openForView(document, false, activity)
            return
        }
        val apk = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true) ?: return
        if (!apk.exists()) {
            AndroidUtilities.openForView(document, false, activity)
            return
        }
        if (dialog?.isShowing == true) return

        dialog = buildProgressDialog(activity).also { it.show() }

        Utilities.globalQueue.postRunnable {
            val receiver = registerStatusReceiver(activity) {
                AndroidUtilities.runOnUIThread {
                    dialog?.dismiss()
                    dialog = null
                }
            }
            val started = runCatching { commitSession(activity, apk) }
            if (started.isFailure) {
                val err = started.exceptionOrNull()
                FileLog.e(err)
                AndroidUtilities.runOnUIThread {
                    dialog?.dismiss()
                    dialog = null
                    AlertsCreator.createSimpleAlert(
                        activity,
                        LocaleController.getString(R.string.ErrorOccurred) + "\n" + (err?.localizedMessage ?: ""),
                    ).show()
                    AndroidUtilities.openForView(document, false, activity)
                }
                runCatching { activity.unregisterReceiver(receiver) }
                return@postRunnable
            }
            val intent = receiver.waitIntent()
            if (intent != null) {
                AndroidUtilities.runOnUIThread { activity.startActivity(intent) }
            }
        }
    }

    @Throws(IOException::class)
    private fun commitSession(activity: Activity, apk: File) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(activity, 0, intent, flags)

        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        installer.openSession(installer.createSession(params)).use { session ->
            session.openWrite(apk.name, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
            }
            session.commit(pending.intentSender)
        }
    }

    private fun registerStatusReceiver(activity: Activity, onDone: Runnable): InstallReceiver {
        val receiver = InstallReceiver(activity, ApplicationLoader.getApplicationId(), onDone)
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return receiver
    }

    fun hasBrokenPackageInstaller(context: Context): Boolean {
        if (!XiaomiUtilities.isMIUI()) return false
        brokenInstaller?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            brokenInstaller = true
            return true
        }
        val intent = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved == null) {
            brokenInstaller = true
            return true
        }
        val pkg = resolved.activityInfo.packageName ?: ""
        FileLog.d("current package installer: $pkg")
        val broken = pkg.startsWith("com.miui") &&
            resolved.activityInfo.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
        brokenInstaller = broken
        return broken
    }

    private fun buildProgressDialog(context: Context): AlertDialog {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.TOP or Gravity.LEFT, 4f, 4f, 4f, 4f,
            )
        }
        val image = RLottieImageView(context).apply {
            setAutoRepeat(true)
            setAnimation(R.raw.db_migration_placeholder, 160, 160)
            playAnimation()
        }
        container.addView(
            image,
            LayoutHelper.createLinear(160, 160, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 17, 24, 17, 0),
        )
        val title = TextView(context).apply {
            typeface = AndroidUtilities.bold()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = LocaleController.getString(R.string.InuUpdateInstalling)
        }
        container.addView(
            title,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP, 17, 20, 17, 0,
            ),
        )
        val subtitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray))
            text = LocaleController.getString(R.string.InuUpdateInstallingHint)
        }
        container.addView(
            subtitle,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP, 17, 4, 17, 24,
            ),
        )
        val builder = AlertDialog.Builder(context)
        builder.setView(container)
        return builder.create().apply {
            setCanceledOnTouchOutside(false)
            setCancelable(false)
        }
    }

    private class InstallReceiver(
        private val context: Context,
        private val packageName: String,
        private val onDone: Runnable,
    ) : BroadcastReceiver() {
        private val latch = CountDownLatch(1)
        @Volatile
        private var pendingIntent: Intent? = null
        @Volatile
        private var unregistered = false

        override fun onReceive(c: Context, i: Intent) {
            if (Intent.ACTION_PACKAGE_ADDED == i.action) {
                if (i.data?.schemeSpecificPart == packageName) {
                    onDone.run()
                    safeUnregister()
                }
                return
            }
            val status = i.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE_INVALID,
            )
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                pendingIntent = i.getParcelableExtra(Intent.EXTRA_INTENT)
            } else {
                if (isFailure(status)) {
                    abandonSession(i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0))
                    showInstallError(status)
                }
                onDone.run()
                safeUnregister()
            }
            latch.countDown()
        }

        private fun isFailure(status: Int) = when (status) {
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> true
            else -> false
        }

        private fun abandonSession(sessionId: Int) {
            if (sessionId <= 0) return
            runCatching {
                val pi = context.packageManager.packageInstaller
                pi.getSessionInfo(sessionId)?.let { pi.abandonSession(it.sessionId) }
            }
        }

        private fun showInstallError(status: Int) {
            val launch = LaunchActivity.instance ?: return
            AndroidUtilities.runOnUIThread {
                launch.showBulletin { factory ->
                    factory.createErrorBulletin(
                        LocaleController.formatString(R.string.InuUpdateFailedToInstall, status),
                    )
                }
            }
        }

        fun waitIntent(): Intent? {
            runCatching { latch.await(5, TimeUnit.SECONDS) }
            return pendingIntent
        }

        private fun safeUnregister() {
            if (unregistered) return
            unregistered = true
            runCatching { context.unregisterReceiver(this) }
        }
    }
}
