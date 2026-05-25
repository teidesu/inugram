package desu.inugram.helpers

import android.content.Context
import android.os.Build
import android.os.PowerManager
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
