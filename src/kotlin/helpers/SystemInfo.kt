package desu.inugram.helpers

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.XiaomiUtilities
import java.time.Instant

object SystemInfo {
    fun build(): String = buildString {
        append(UpdateHelper.getVersionInfoString()).append("\n")
        append("Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}\n")
        append("Device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.FINGERPRINT})\n")
        append("Time ${Instant.now()}\n")
        append(profileInfo()).append("\n")
        append(permissionsInfo()).append("\n")
        append(storageInfo()).append("\n")
        append("Battery optimization ").append(batteryOptimizationState()).append("\n")
        if (XiaomiUtilities.isMIUI()) {
            append("MIUI ${XiaomiUtilities.getMIUIMajorVersion()}, optimization=")
            append(AndroidUtilities.getSystemProperty("persist.sys.miui_optimization") ?: "<unk>")
        } else {
            VENDOR_PROPS.forEach { prop ->
                val value = AndroidUtilities.getSystemProperty(prop)
                if (!value.isNullOrEmpty()) append("$prop=$value ")
            }
        }
    }

    private fun profileInfo(): String = buildString {
        val ctx = ApplicationLoader.applicationContext
        append("Profile ")
        val um = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        val managed = runCatching {
            UserManager::class.java.getMethod("isManagedProfile").invoke(um) as Boolean
        }.getOrNull()
        append("managed=").append(managed?.toString() ?: "<unk>")
        append(" userSerial=").append(runCatching {
            um?.getSerialNumberForUser(Process.myUserHandle())
        }.getOrNull() ?: "<unk>")
    }

    private fun permissionsInfo(): String = buildString {
        val ctx = ApplicationLoader.applicationContext
        append("Permissions\n")
        val requested = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        }.getOrNull()

        fun shortPermission(permission: String): String {
            return permission.removePrefix("android.permission.")
        }

        val relevant = requested?.filter { permission ->
            permission == Manifest.permission.READ_EXTERNAL_STORAGE ||
                permission == Manifest.permission.WRITE_EXTERNAL_STORAGE ||
                permission == "android.permission.READ_MEDIA_IMAGES" ||
                permission == "android.permission.READ_MEDIA_VIDEO" ||
                permission == "android.permission.READ_MEDIA_AUDIO" ||
                permission == "android.permission.MANAGE_EXTERNAL_STORAGE"
        }?.sorted()
        if (relevant.isNullOrEmpty()) {
            append("storage/media <none>")
            return@buildString
        }
        relevant.forEach { permission ->
            val granted = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            append(shortPermission(permission)).append('=').append(if (granted) "granted" else "denied").append("\n")
        }
    }

    private fun storageInfo(): String = buildString {
        val ctx = ApplicationLoader.applicationContext

        val appInfo = ctx.applicationInfo
        append("Install external=").append((appInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0)
        append("\n")
        append("External storage ").append(runCatching { Environment.getExternalStorageState() }.getOrDefault("<err>"))
        append(" emulated=").append(runCatching { Environment.isExternalStorageEmulated() }.getOrNull() ?: "<unk>")
        append(" removable=").append(runCatching { Environment.isExternalStorageRemovable() }.getOrNull() ?: "<unk>")
    }

    private fun batteryOptimizationState(): String = try {
        val ctx = ApplicationLoader.applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "exempt" else "optimized"
    } catch (_: Throwable) {
        "unk"
    }

    private val VENDOR_PROPS = listOf(
        "persist.sys.miui_optimization",
        "ro.build.version.emui",
        "hw_sc.build.platform.version",
        "ro.build.version.opporom",
        "ro.vivo.os.version",
        "ro.build.version.oneui",
        "ro.flyme.published",
    )
}
