package desu.inugram.helpers

import android.content.Context
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogsHelper {
    private const val SYSTEM_PREFS = "systemConfig"
    private const val LOGS_ENABLED_KEY = "logsEnabled"

    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED

    fun setEnabled(enabled: Boolean) {
        if (BuildVars.LOGS_ENABLED == enabled) return
        BuildVars.LOGS_ENABLED = enabled
        ApplicationLoader.applicationContext.getSharedPreferences(SYSTEM_PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(LOGS_ENABLED_KEY, enabled)
        }
        if (!enabled) FileLog.cleanupLogs()
    }

    fun computeSize(): Long {
        val dir = AndroidUtilities.getLogsDir() ?: return 0L
        return dirSize(dir)
    }

    private fun dirSize(dir: File): Long {
        var sum = 0L
        dir.listFiles()?.forEach { sum += if (it.isFile) it.length() else dirSize(it) }
        return sum
    }

    /** Stage the current session log with a SystemInfo header, then share via the in-app picker. */
    fun shareCurrent(activity: LaunchActivity, onDone: (ok: Boolean) -> Unit) {
        stageThenShare(activity, onDone, mime = "text/plain") { stageCurrentLog() }
    }

    /** Zip the whole logs dir + a system_info.txt entry, then share via the in-app picker. */
    fun shareZip(activity: LaunchActivity, onDone: (ok: Boolean) -> Unit) {
        stageThenShare(activity, onDone, mime = "application/zip") { stageZip() }
    }

    private fun stageThenShare(
        activity: LaunchActivity,
        onDone: (ok: Boolean) -> Unit,
        mime: String,
        stage: () -> File?,
    ) {
        Utilities.globalQueue.postRunnable {
            val staged = try {
                stage()
            } catch (_: Throwable) {
                null
            }
            AndroidUtilities.runOnUIThread {
                if (staged == null) {
                    onDone(false)
                    return@runOnUIThread
                }
                onDone(true)
                SharePicker.shareFile(activity, staged, mime)
            }
        }
    }

    private fun stageCurrentLog(): File? {
        val src = currentLogFile() ?: return null
        val cacheDir = AndroidUtilities.getCacheDir().apply { mkdirs() }
        val dst = File(cacheDir, "inugram-log.txt").apply { if (exists()) delete() }
        FileOutputStream(dst).buffered().use { out ->
            out.write(SystemInfo.build().toByteArray(Charsets.UTF_8))
            out.write("\n\n".toByteArray(Charsets.UTF_8))
            src.inputStream().buffered().use { it.copyTo(out) }
        }
        return dst
    }

    /** Latest non-mtproto FileLog session file, or null. */
    fun currentLogFile(): File? {
        val dir = AndroidUtilities.getLogsDir() ?: return null
        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".txt") && !f.name.endsWith("_mtproto.txt")
        }?.maxByOrNull { it.lastModified() }
    }

    private fun stageZip(): File? {
        val dir = AndroidUtilities.getLogsDir() ?: return null
        val cacheDir = AndroidUtilities.getCacheDir().apply { mkdirs() }
        val dst = File(cacheDir, "inugram-logs.zip").apply { if (exists()) delete() }
        ZipOutputStream(FileOutputStream(dst).buffered()).use { out ->
            out.putNextEntry(ZipEntry("system_info.txt"))
            out.write(SystemInfo.build().toByteArray(Charsets.UTF_8))
            out.closeEntry()
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().buffered().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return dst
    }
}
