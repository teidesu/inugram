package desu.inugram.helpers

import android.app.NotificationManager
import android.os.Build
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.io.File

object NotificationDebugHelper {
    @JvmStatic
    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED && InuConfig.EXTRA_DEBUG_LOGS.value

    @JvmStatic
    fun onPushSkippedAsRead(dialogId: Long, msgId: Int, readMax: Int) {
        if (!isEnabled()) return
        FileLog.d("InuNotify push dropped as read did=$dialogId mid=$msgId readMax=$readMax")
    }

    @JvmStatic
    fun onPushMessageSkipped(dialogId: Long, msgId: Int, reason: String) {
        if (!isEnabled()) return
        FileLog.d("InuNotify push message skipped did=$dialogId mid=$msgId reason=$reason")
    }

    @JvmStatic
    fun onNotificationPosted(id: Int, dialogId: Long) {
        if (!isEnabled() || Build.VERSION.SDK_INT < 23) return
        try {
            val manager = ApplicationLoader.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            if (manager.activeNotifications.any { it.id == id }) return
            FileLog.e("InuNotify posted but not active id=$id did=$dialogId, dropped by the system")
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun onAvatarDecodeFailed(dialogId: Long, file: File?, e: Throwable) {
        if (!isEnabled()) return
        FileLog.e("InuNotify avatar decode failed did=$dialogId ${describeFile(file)}", e)
    }

    @JvmStatic
    fun onPersonIconSkipped(dialogId: Long, reason: String) {
        if (!isEnabled()) return
        FileLog.d("InuNotify person icon skipped did=$dialogId reason=$reason")
    }

    @JvmStatic
    fun onAvatarResolved(account: Int, dialogId: Long, label: String, owner: TLObject?, file: File?) {
        if (!isEnabled()) return
        FileLog.d("InuNotify avatar did=$dialogId $label ${describeOwner(account, owner)} ${describeFile(file)}")
    }

    private fun describeOwner(account: Int, owner: TLObject?): String = when (owner) {
        null -> "owner=null"
        is TLRPC.User -> "user=${owner.id} min=${owner.min} applyMinPhoto=${owner.apply_min_photo} " +
            "photo=${describePhoto(owner.photo?.photo_small)} stored=${describeStoredUser(account, owner.id)}"
        is TLRPC.Chat -> "chat=${owner.id} min=${owner.min} photo=${describePhoto(owner.photo?.photo_small)}"
        else -> "owner=${owner.javaClass.simpleName}"
    }

    private fun describeStoredUser(account: Int, userId: Long): String {
        val stored = MessagesStorage.getInstance(account).getUserSync(userId) ?: return "none"
        return "min=${stored.min},photo=${describePhoto(stored.photo?.photo_small)}"
    }

    private fun describePhoto(location: TLRPC.FileLocation?): String {
        if (location == null) return "null"
        return "${location.volume_id}_${location.local_id}(${FileLoader.getAttachFileName(location)})"
    }

    private fun describeFile(file: File?): String {
        if (file == null) return "file=null"
        return if (file.exists()) "file=${file.absolutePath} size=${file.length()}" else "file=${file.absolutePath} MISSING"
    }
}
