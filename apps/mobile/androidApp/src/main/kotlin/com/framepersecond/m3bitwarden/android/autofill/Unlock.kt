package com.framepersecond.m3bitwarden.android.autofill

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PersistableBundle
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.data.VaultSession

/**
 * Unlocks the vault from an autofill sheet with fingerprint/face or the device PIN.
 *
 * Prototype note: the real app unwraps the vault key from the Android Keystore here
 * (key bound to biometrics); the prompt is the same, only the success step changes.
 */
object Unlock {
    fun ensure(activity: Activity, onResult: (Boolean) -> Unit) {
        if (VaultSession.isUnlocked) return onResult(true)
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.autofill_unlock_title))
            .setSubtitle(activity.getString(R.string.autofill_unlock_prompt))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    VaultSession.markUnlocked()
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
    }

    /** Copies a one-time code marked as sensitive, so keyboards and previews hide it. */
    fun copySensitive(context: Context, label: String, value: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
    }
}
