package desu.inugram.helpers.security

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

object BiometricHelper {
    fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(ApplicationLoader.applicationContext)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            return true
        }
        if (InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.value && Build.VERSION.SDK_INT >= 30) {
            return manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        }
        return false
    }

    fun isSupported(): Boolean {
        val manager = BiometricManager.from(ApplicationLoader.applicationContext)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        }
        return false
    }

    @JvmStatic
    fun gate(context: Context, enabled: Boolean, onSuccess: Runnable) {
        val activity = context as? Activity
        if (!enabled || activity !is FragmentActivity || !canAuthenticate()) {
            onSuccess.run()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess.run()
            }
        })
        val allowCredential = InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.value
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(LocaleController.getString(R.string.InuBiometricConfirmTitle))
            .setConfirmationRequired(false)
            .setDeviceCredentialAllowed(allowCredential)
        if (!allowCredential) {
            builder.setNegativeButtonText(LocaleController.getString(R.string.Cancel))
        }
        try {
            prompt.authenticate(builder.build())
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }
}
