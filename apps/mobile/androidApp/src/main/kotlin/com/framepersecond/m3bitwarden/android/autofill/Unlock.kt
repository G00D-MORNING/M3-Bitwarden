package com.framepersecond.m3bitwarden.android.autofill

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import com.framepersecond.m3bitwarden.android.MainActivity
import com.framepersecond.m3bitwarden.android.vault.AndroidVault
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.framepersecond.m3bitwarden.data.VaultSession

/**
 * Unlocks the vault from an autofill sheet: with biometrics when set up (the vault key is
 * unwrapped from the Android Keystore and the SDK unlocks), otherwise by opening the app for
 * the master password.
 */
object Unlock {
    fun ensure(activity: Activity, onResult: (Boolean) -> Unit) {
        if (VaultSession.isUnlocked) return onResult(true)
        val vault = AndroidVault.get(activity)
        if (vault.phase == AndroidVault.Phase.Locked && vault.canUseBiometrics) {
            MainScope().launch { onResult(vault.unlockWithBiometrics(activity, syncTimeoutMs = 3_000)) }
            return
        }
        // Not signed in, no certificate or no biometrics: the app asks for the master password.
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onResult(false)
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
