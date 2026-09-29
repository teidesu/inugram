package desu.inugram.helpers.media

import android.os.Build
import android.os.FileObserver
import android.os.StatFs
import desu.inugram.InuConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import java.io.File

object MediaSendDebugHelper {
    // outgoing temp files (converted videos, generated thumbs) are all named <Integer.MIN_VALUE>_<localId>.<ext>
    private val LOCAL_PREFIX = "${Int.MIN_VALUE}_"

    private const val MAX_LISTED_MESSAGES = 20
    private const val FREE_SPACE_TTL_MS = 2000L

    private var watcher: FileObserver? = null

    private var freeSpaceValue = -1L
    private var freeSpaceAt = 0L

    @JvmStatic
    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED && InuConfig.EXTRA_DEBUG_LOGS.value

    @JvmStatic
    @Synchronized
    fun startWatchingCache() {
        if (watcher != null || !isEnabled()) return
        val dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) ?: return
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onCacheEvent(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onCacheEvent(event, path)
            }
        }
        runCatching { observer.startWatching() }
            .onSuccess { watcher = observer }
            .onFailure { FileLog.e(it) }
    }

    @JvmStatic
    fun onUploadEnqueued(location: String, encrypted: Boolean, small: Boolean, estimatedSize: Long) {
        if (!isEnabled()) return
        startWatchingCache()
        FileLog.d("InuSend upload enqueue ${describeFile(File(location))} encrypted=$encrypted small=$small estimated=$estimatedSize free=${getFreeSpace()}")
    }

    @JvmStatic
    fun onUploadFailed(location: String, error: Throwable) {
        if (!isEnabled()) return
        FileLog.e("InuSend upload failed ${describeFile(File(location))} free=${getFreeSpace()} error=${error.javaClass.simpleName}: ${error.message}")
    }

    @JvmStatic
    fun onLocalFileDeleted(file: File?, reason: String) {
        if (file == null || !isEnabled() || !file.name.startsWith(LOCAL_PREFIX)) return
        FileLog.d("InuSend local file deleted by $reason ${describeFile(file)}")
    }

    @JvmStatic
    fun onDelayedMessageError(type: Int, groupId: Long, messageObjects: List<MessageObject>?, obj: MessageObject?) {
        if (!isEnabled()) return
        val affected = if (type == 4) messageObjects.orEmpty() else listOfNotNull(obj)
        FileLog.d("InuSend delayed message error type=$type group=$groupId ${describeMessageObjects(affected)}")
    }

    @JvmStatic
    fun onUnsentMessagesLoaded(messages: List<TLRPC.Message>?, scheduled: List<TLRPC.Message>?) {
        if (!isEnabled()) return
        FileLog.d(
            "InuSend retrying unsent count=${messages.orEmpty().size} scheduled=${scheduled.orEmpty().size}" +
                " ${describeMessages(messages.orEmpty() + scheduled.orEmpty())}"
        )
    }

    private fun onCacheEvent(event: Int, path: String?) {
        if (path == null || !path.startsWith(LOCAL_PREFIX) || !isEnabled()) return
        val action = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CREATE -> "created"
            FileObserver.DELETE -> "deleted"
            FileObserver.MOVED_FROM -> "renamed away"
            FileObserver.MOVED_TO -> "renamed in"
            else -> return
        }
        FileLog.d("InuSend local file $action $path free=${getFreeSpace()}")
    }

    private fun describeMessageObjects(messages: List<MessageObject>): String =
        describeMessages(messages.mapNotNull { it.messageOwner })

    private fun describeMessages(messages: List<TLRPC.Message>): String {
        val listed = messages.take(MAX_LISTED_MESSAGES).joinToString(",") { message ->
            val attach = message.attachPath
            val attachState = when {
                attach.isNullOrEmpty() -> "none"
                File(attach).exists() -> "ok"
                else -> "MISSING"
            }
            "[mid=${message.id} group=${message.grouped_id} state=${message.send_state} attach=$attachState]"
        }
        val rest = messages.size - MAX_LISTED_MESSAGES
        return if (rest > 0) "$listed +$rest more" else listed
    }

    private fun describeFile(file: File): String =
        if (file.exists()) "path=${file.absolutePath} size=${file.length()}" else "path=${file.absolutePath} MISSING"

    private fun getFreeSpace(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        if (freeSpaceValue >= 0 && now - freeSpaceAt < FREE_SPACE_TTL_MS) return freeSpaceValue
        val dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) ?: return -1
        freeSpaceValue = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(-1L)
        freeSpaceAt = now
        return freeSpaceValue
    }
}
