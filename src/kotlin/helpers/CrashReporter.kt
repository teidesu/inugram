package desu.inugram.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import desu.inugram.ui.CrashReportBottomSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

object CrashReporter {
    private const val LOG_FILE = "last_crash.log"
    private const val TAIL_BYTES = 200 * 1024L
    private const val RESTART_LOOP_GUARD_MS = 30_000L
    private const val RESTART_CHANNEL_ID = "inu_crash_restart"
    private const val RESTART_NOTIFICATION_ID = 0x1e75
    private val installed = AtomicBoolean(false)
    private val sheetShown = AtomicBoolean(false)
    private var previousCrashMtime = 0L

    fun install() {
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        if (!installed.compareAndSet(false, true)) return
        // force BuildVars static init so our handler wraps stock's FileLog.fatal chain
        @Suppress("UNUSED_EXPRESSION") BuildVars.LOGS_ENABLED
        previousCrashMtime = getLogFile().takeIf { it.exists() }?.lastModified() ?: 0L
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(Handler(previous))
        dismissRestartNotification()
    }

    fun getLogFile(): File {
        val dir = File(ApplicationLoader.getFilesDirFixed(), "logs")
        dir.mkdirs()
        return File(dir, LOG_FILE)
    }

    fun isCrashed(): Boolean = getLogFile().let { it.exists() && it.length() > 0 }

    fun deleteCrashLog() {
        getLogFile().delete()
    }

    fun maybeShowReportSheet(activity: LaunchActivity) {
        if (!isCrashed()) return
        if (!sheetShown.compareAndSet(false, true)) return
        try {
            CrashReportBottomSheet(activity).apply { setCancelable(false) }.show()
        } catch (_: Throwable) {
            sheetShown.set(false)
        }
    }

    fun shareCrashLog(activity: LaunchActivity) {
        val src = getLogFile()
        if (!src.exists()) return
        val shareable = File(AndroidUtilities.getCacheDir().apply { mkdirs() }, "inugram-crash.log")
        src.copyTo(shareable, overwrite = true)
        SharePicker.shareFile(activity, shareable, "text/plain", onSent = ::deleteCrashLog)
    }

    private class Handler(
        private val chain: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { e.printStackTrace(it) }
                val tail = if (BuildVars.LOGS_ENABLED) tailCurrentFileLog() else null
                val body = buildString {
                    append(SystemInfo.build()).append("\n\n").append(sw.toString())
                    if (tail != null) {
                        append("\n----- FileLog tail (last ").append(tail.length).append(" chars) -----\n")
                        append(tail)
                    }
                }
                getLogFile().writeText(body)
            } catch (_: Throwable) {
            }
            val crashLoop = previousCrashMtime > 0 &&
                System.currentTimeMillis() - previousCrashMtime < RESTART_LOOP_GUARD_MS
            if (!crashLoop) postRestartNotification()
            chain?.uncaughtException(t, e)
        }
    }

    private fun tailCurrentFileLog(): String? = try {
        val file = LogsHelper.currentLogFile() ?: return null
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // drop the (likely partial) first line when we started mid-file
            if (start > 0) text.substringAfter('\n', text) else text
        }
    } catch (_: Throwable) {
        null
    }

    private fun postRestartNotification() {
        try {
            val ctx = ApplicationLoader.applicationContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(RESTART_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        RESTART_CHANNEL_ID,
                        LocaleController.getString(R.string.InuCrashChannel),
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, 0, intent, flags)
            val notif = NotificationCompat.Builder(ctx, RESTART_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.InuCrashNotifTitle))
                .setContentText(LocaleController.getString(R.string.InuCrashNotifText))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            nm.notify(RESTART_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {
        }
    }

    private fun dismissRestartNotification() {
        try {
            val ctx = ApplicationLoader.applicationContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(RESTART_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }
}
