package desu.inugram.helpers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import desu.inugram.InuConfig
import org.telegram.messenger.voip.VoIPService
import org.webrtc.voiceengine.WebRtcAudioTrack

object CallAudioHelper {
    @JvmStatic
    @Volatile
    var isActive = false
        private set

    @Volatile
    private var currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH

    @JvmStatic
    fun evaluate(service: VoIPService): Boolean {
        isActive = InuConfig.HD_BLUETOOTH_CALL_AUDIO.value &&
            !VoIPService.hasRtmpStream() &&
            !VoIPService.USE_CONNECTION_SERVICE &&
            service.isBluetoothHeadsetConnected()
        return isActive
    }

    @JvmStatic
    fun onConfigureCall(service: VoIPService): Boolean {
        evaluate(service)
        currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH
        WebRtcAudioTrack.inu_setPreferredDevice(null)
        return isActive
    }

    @JvmStatic
    fun getCurrentRoute(): Int = currentRoute

    @JvmStatic
    fun handleSetAudioOutput(service: VoIPService, output: Int): Boolean {
        if (!isActive) return false
        val deviceType = when (output) {
            0 -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            1 -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            else -> null
        }
        if (deviceType == null) {
            WebRtcAudioTrack.inu_setPreferredDevice(null)
            currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == deviceType }
                ?: return true
            WebRtcAudioTrack.inu_setPreferredDevice(device)
            currentRoute = if (output == 0) VoIPService.AUDIO_ROUTE_SPEAKER else VoIPService.AUDIO_ROUTE_EARPIECE
        }
        return true
    }
}
