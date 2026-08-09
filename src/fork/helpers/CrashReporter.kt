package desu.inugram.helpers

import android.app.Activity
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
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import android.os.Debug
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

object CrashReporter {
    private const val LOG_FILE = "last_crash.log"
    private const val HEAP_DUMP_FILE = "last_heap_dump.hprof"
    private const val TAIL_BYTES = 200 * 1024L
    private const val RESTART_LOOP_GUARD_MS = 30_000L
    private const val RESTART_CHANNEL_ID = "inu_crash_restart"
    private const val RESTART_NOTIFICATION_ID = 0x1e75
    private const val SAVE_HEAP_DUMP_REQUEST = 0x1e76
    private val installed = AtomicBoolean(false)
    private val sheetShown = AtomicBoolean(false)
    private var previousCrashMtime = 0L

    fun install() {
        // if (BuildConfig.INU_BUILD_TYPE == "debug") return
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

    fun getHeapDumpFile(): File {
        val dir = File(ApplicationLoader.getFilesDirFixed(), "logs")
        dir.mkdirs()
        return File(dir, HEAP_DUMP_FILE)
    }

    fun isCrashed(): Boolean = getLogFile().let { it.exists() && it.length() > 0 }

    fun hasHeapDump(): Boolean = BuildVars.LOGS_ENABLED && getHeapDumpFile().let { it.exists() && it.length() > 0 }

    fun getCrashErrorName(): String? {
        val file = getLogFile()
        if (!file.exists() || file.length() == 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = minOf(raf.length(), 2048L).toInt()
                val bytes = ByteArray(len)
                raf.readFully(bytes)
                val head = String(bytes, Charsets.UTF_8)
                val sep = head.indexOf("\n\n")
                if (sep < 0) return null
                val line = head.substring(sep + 2).lineSequence().firstOrNull { it.isNotBlank() } ?: return null
                val colonIdx = line.indexOf(':')
                val fullClass = (if (colonIdx > 0) line.substring(0, colonIdx) else line).trim()
                fullClass.substringAfterLast('.')
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun deleteCrashLog() {
        getLogFile().delete()
        getHeapDumpFile().delete()
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

    fun saveHeapDump(activity: LaunchActivity) {
        val src = getHeapDumpFile()
        if (!src.exists()) return
        saveHprofFile(activity, src) { saved -> if (saved) deleteCrashLog() }
    }

    fun dumpAndSaveHeap(activity: LaunchActivity) {
        val tmp = File(AndroidUtilities.getCacheDir().apply { mkdirs() }, "inugram-heap-dump.hprof")
        Debug.dumpHprofData(tmp.absolutePath)
        saveHprofFile(activity, tmp) { tmp.delete() }
    }

    private fun saveHprofFile(activity: LaunchActivity, src: File, onDone: (saved: Boolean) -> Unit) {
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                val reqCode = args[0] as Int
                if (reqCode != SAVE_HEAP_DUMP_REQUEST) return
                NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.onActivityResultReceived)
                val resultCode = args[1] as Int
                val uri = (args[2] as? Intent)?.data
                if (resultCode != Activity.RESULT_OK || uri == null) {
                    onDone(false)
                    return
                }
                Thread {
                    var ok = false
                    try {
                        activity.contentResolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        ok = true
                    } catch (_: Throwable) {}
                    AndroidUtilities.runOnUIThread { onDone(ok) }
                }.start()
            }
        }
        NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.onActivityResultReceived)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "inugram-heap-dump.hprof")
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, SAVE_HEAP_DUMP_REQUEST)
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
            if (e is OutOfMemoryError && BuildVars.LOGS_ENABLED) {
                try {
                    Debug.dumpHprofData(getHeapDumpFile().absolutePath)
                } catch (_: Throwable) {
                }
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
