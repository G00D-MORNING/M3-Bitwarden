package com.framepersecond.m3bitwarden.android.vault

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.bitwarden.core.ClientManagedTokens
import com.bitwarden.core.ClientSettings
import com.bitwarden.core.DeviceType
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.sdk.Client
import com.bitwarden.sdk.ManagedSettingsBindingClient
import java.time.Instant

/**
 * Debug builds only (`adb shell am start -n …/.MainActivity --ez m3b_selftest true`): checks that
 * the native Bitwarden SDK loads and computes known values. Results go to logcat tag "M3BSelfTest".
 */
object SelfTest {
    suspend fun run(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val client = Client(
            object : ClientManagedTokens { override suspend fun getAccessToken(): String? = null },
            ClientSettings("https://localhost/identity", "https://localhost/api", "selftest", DeviceType.ANDROID, "selftest", "1", null),
            ManagedSettingsBindingClient(),
        )
        val pbkdf = client.auth().hashPassword("test@example.com", "correct horse battery staple", Kdf.Pbkdf2(600_000u), HashPurpose.SERVER_AUTHORIZATION)
        Log.i(TAG, "pbkdf2 hash=$pbkdf")
        val argon = client.auth().hashPassword("test@example.com", "correct horse battery staple", Kdf.Argon2id(3u, 64u, 4u), HashPurpose.SERVER_AUTHORIZATION)
        Log.i(TAG, "argon2id hash=$argon")
        // RFC 6238 test secret "12345678901234567890" at T=59 → 94287082 (8 digits) / 287082 (6 digits).
        val totp = client.vault().generateTotp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Instant.ofEpochSecond(59))
        Log.i(TAG, "totp code=${totp.code} period=${totp.period}")
        val otpauth = client.vault().generateTotp("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&period=30", Instant.ofEpochSecond(59))
        Log.i(TAG, "totp8 code=${otpauth.code}")
        Log.i(TAG, "csr=" + runCatching { DeviceCertificate(context).makeSigningRequest("selftest").lines().first() }.getOrElse { it.toString() })
        client.close()
        Log.i(TAG, "done")
    }

    private const val TAG = "M3BSelfTest"
}
