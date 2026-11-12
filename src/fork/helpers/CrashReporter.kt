package desu.inugram.helpers

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.util.Base64
import androidx.core.app.NotificationCompat
import desu.inugram.InuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.platform.PluginXposed
// #endif
import desu.inugram.ui.CrashReportBottomSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import android.os.Debug
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

object CrashReporter {
    private const val LOG_FILE = "last_crash.log"
    private const val HEAP_DUMP_FILE = "last_heap_dump.hprof"
    private const val TMP_HEAP_DUMP_PREFIX = "inugram-heap-dump-"
    private const val TAIL_BYTES = 200 * 1024L
    private const val ANR_TRACE_BYTES = 256 * 1024
    private const val HEAD_BYTES = 16 * 1024L
    private const val PLUGIN_HOOKS_HEADER = "\nplugin hooks:\n  "
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

    fun collectNativeExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (Application.getProcessName() != context.packageName) return
        try {
            val exit = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(null, 0, 0)
                .firstOrNull { it.processName == context.packageName }
                ?: return
            val lastSeen = InuConfig.LAST_EXIT_REASON_MS.value
            if (exit.timestamp <= lastSeen) return
            InuConfig.LAST_EXIT_REASON_MS.value = exit.timestamp
            if (lastSeen == 0L) return
            val details = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> describeNativeCrash(exit)
                ApplicationExitInfo.REASON_ANR -> describeAnr(exit)
                else -> return
            }
            getLogFile().writeText(buildString {
                append(SystemInfo.build())
                append("Exited ").append(Instant.ofEpochMilli(exit.timestamp))
                append(", importance ").append(exit.importance)
                append(", pss ").append(exit.pss).append("K, rss ").append(exit.rss).append("K")
                append("\n\n").append(details)
            })
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun describeNativeCrash(exit: ApplicationExitInfo): String {
        val trace = exit.traceInputStream?.use { it.readBytes() }
            ?: return "NativeCrash: signal ${exit.status}, ${exit.description}\n"
        return try {
            formatTombstone(Proto(trace, 0, trace.size))
        } catch (e: Throwable) {
            "NativeCrash: signal ${exit.status}, ${exit.description}\n\n" +
                "unparsable tombstone ($e), base64:\n" + Base64.encodeToString(trace, Base64.NO_WRAP)
        }
    }

    private fun describeAnr(exit: ApplicationExitInfo): String {
        val head = "ANR: ${exit.description}\n"
        val trace = exit.traceInputStream?.use { it.readBytes() } ?: return head
        return head + "\n" + String(trace, 0, minOf(trace.size, ANR_TRACE_BYTES), Charsets.UTF_8)
    }

    private fun formatTombstone(t: Proto): String = buildString {
        val tid = t.getLong(6)
        val threads = t.getMessages(16).mapNotNull { it.getMessage(2) }
            .sortedBy { if (it.getLong(1) == tid) 0 else 1 }
        val signal = t.getMessage(10)
        if (signal != null) {
            append(signal.getString(2).ifEmpty { "NativeCrash" }).append(": signal ").append(signal.getLong(1))
            append(", code ").append(signal.getLong(3).toInt()).append(" (").append(signal.getString(4)).append(")")
            if (signal.getLong(8) != 0L) append(", fault addr 0x").append(java.lang.Long.toHexString(signal.getLong(9)))
            append("\n")
        } else {
            append("NativeCrash: no signal info\n")
        }
        append("pid: ").append(t.getLong(5)).append(", tid: ").append(tid)
        append("  >>> ").append(t.getStrings(9).joinToString(" ")).append(" <<<\n")
        t.getString(14).takeIf { it.isNotEmpty() }?.let { append("Abort message: '").append(it).append("'\n") }
        t.getMessages(15).forEach { append("Cause: ").append(it.getString(1)).append("\n") }
        for (thread in threads) {
            append("\n--- tid ").append(thread.getLong(1)).append(" \"").append(thread.getString(2)).append("\" ---\n")
            thread.getStrings(7).forEach { append("  NOTE: ").append(it).append("\n") }
            thread.getMessages(4).forEachIndexed { i, frame ->
                append("  #").append(i.toString().padStart(2, '0'))
                append(" pc ").append(java.lang.Long.toHexString(frame.getLong(1)).padStart(16, '0'))
                append("  ").append(frame.getString(6))
                frame.getString(4).takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append("+").append(frame.getLong(5)).append(")") }
                frame.getString(8).takeIf { it.isNotEmpty() }?.let { append(" (BuildId: ").append(it).append(")") }
                append("\n")
            }
        }
        for (buffer in t.getMessages(18)) {
            append("\n--- log ").append(buffer.getString(1)).append(" ---\n")
            for (log in buffer.getMessages(2)) {
                append(log.getString(1)).append(" ").append(log.getLong(2)).append(" ").append(log.getLong(3)).append(" ")
                append("VDIWEF".getOrElse(log.getLong(4).toInt() - 2) { '?' }).append(" ")
                append(log.getString(5)).append(": ").append(log.getString(6)).append("\n")
            }
        }
    }

    private class Proto(private val buf: ByteArray, private val start: Int, private val end: Int) {
        private val fields: Map<Int, List<Any>> by lazy {
            val result = HashMap<Int, MutableList<Any>>()
            var pos = start
            fun readVarint(): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    require(pos < end && shift < 64) { "bad varint" }
                    val b = buf[pos++].toInt()
                    value = value or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }
            fun readFixed(size: Int): Long {
                require(end - pos >= size) { "truncated" }
                var value = 0L
                for (i in 0 until size) value = value or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
                pos += size
                return value
            }
            while (pos < end) {
                val key = readVarint()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint()
                    1 -> readFixed(8)
                    2 -> {
                        val len = readVarint()
                        require(len in 0..(end - pos).toLong()) { "truncated" }
                        Proto(buf, pos, pos + len.toInt()).also { pos += len.toInt() }
                    }
                    5 -> readFixed(4)
                    else -> throw IllegalArgumentException("wire type ${key and 7}")
                }
                result.getOrPut((key ushr 3).toInt()) { mutableListOf() }.add(value)
            }
            result
        }

        fun getLong(field: Int): Long = fields[field]?.firstOrNull() as? Long ?: 0L
        fun getMessages(field: Int): List<Proto> = fields[field].orEmpty().filterIsInstance<Proto>()
        fun getMessage(field: Int): Proto? = getMessages(field).firstOrNull()
        fun getStrings(field: Int): List<String> = getMessages(field).map { String(it.buf, it.start, it.end - it.start, Charsets.UTF_8) }
        fun getString(field: Int): String = getStrings(field).firstOrNull().orEmpty()
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

    private fun readLogHead(): String? {
        val file = getLogFile()
        if (!file.exists() || file.length() == 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(minOf(raf.length(), HEAD_BYTES).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getCrashErrorName(): String? {
        val head = readLogHead() ?: return null
        val sep = head.indexOf("\n\n")
        if (sep < 0) return null
        val line = head.substring(sep + 2).lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val colonIdx = line.indexOf(':')
        val fullClass = (if (colonIdx > 0) line.substring(0, colonIdx) else line).trim()
        return fullClass.substringAfterLast('.')
    }

    fun getCrashPluginHooks(): String? {
        val header = readLogHead()?.substringBefore("\n\n") ?: return null
        val start = header.indexOf(PLUGIN_HOOKS_HEADER)
        if (start < 0) return null
        return header.substring(start + PLUGIN_HOOKS_HEADER.length).replace("\n  ", "\n")
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
        val dir = AndroidUtilities.getCacheDir().apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(TMP_HEAP_DUMP_PREFIX) }?.forEach { it.delete() }
        val tmp = File.createTempFile(TMP_HEAP_DUMP_PREFIX, ".hprof", dir)
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
                        val out = activity.contentResolver.openOutputStream(uri)
                            ?: throw IOException("failed to open $uri")
                        out.use { src.inputStream().use { inp -> inp.copyTo(it) } }
                        ok = true
                    } catch (e: Throwable) {
                        FileLog.e(e)
                        try {
                            DocumentsContract.deleteDocument(activity.contentResolver, uri)
                        } catch (_: Throwable) {}
                    }
                    AndroidUtilities.runOnUIThread {
                        val bulletins = BulletinFactory.global()
                        if (ok) {
                            bulletins.createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.InuLogsHeapDumpSaved)).show()
                        } else {
                            bulletins.createErrorBulletin(LocaleController.getString(R.string.InuLogsHeapDumpSaveError)).show()
                        }
                        onDone(ok)
                    }
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
                    append(SystemInfo.build())
                    // #if PLUGINS
                    val hooks = PluginXposed.describeCrash(t, e)
                    if (hooks.isNotEmpty()) append(hooks.joinToString("\n  ", PLUGIN_HOOKS_HEADER))
                    // #endif
                    append("\n\n").append(sw.toString())
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
