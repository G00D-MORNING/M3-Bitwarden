package com.framepersecond.m3bitwarden.android.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Device-bound storage. This is only the platform keystore wrapping app data at rest; all
 * vault cryptography stays in the Bitwarden SDK.
 *
 * - Account and tokens: AES-GCM with a Keystore key (usable without user authentication, so
 *   the autofill service can refresh tokens).
 * - Vault user key for biometric unlock: RSA-OAEP with a Keystore key pair whose private half
 *   needs a strong biometric for every use and dies when the enrolled biometrics change.
 * - Encrypted sync response (already ciphertext from the server) as a file, for offline use.
 */
class SecureStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("m3b_secure", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    // MARK: Account and tokens

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, storeKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(sealed)).apply()
    }

    fun get(name: String): String? {
        val sealed = prefs.getString(name, null)?.let { Base64.getDecoder().decode(it) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, storeKey(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun storeKey(): SecretKey {
        (keyStore.getKey(STORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(STORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    // MARK: Biometric user key

    val hasBiometricKey: Boolean
        get() = prefs.contains(PREF_BIO) && keyStore.containsAlias(BIO_ALIAS)

    /** Wraps [userKey] so only a strong biometric can unwrap it. Needs no prompt. */
    fun storeBiometricKey(userKey: String) {
        if (keyStore.containsAlias(BIO_ALIAS)) keyStore.deleteEntry(BIO_ALIAS)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            val spec = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            initialize(spec.build())
            generateKeyPair()
        }
        // Encrypting with the public half is a software operation, no authentication needed.
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(keyStore.getCertificate(BIO_ALIAS).publicKey.encoded),
        )
        val cipher = Cipher.getInstance(RSA_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP)
        val wrapped = cipher.doFinal(userKey.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREF_BIO, Base64.getEncoder().encodeToString(wrapped)).apply()
    }

    /** Cipher to hand to BiometricPrompt; null if the key is gone or was invalidated. */
    fun biometricCipher(): Cipher? = try {
        val key = keyStore.getKey(BIO_ALIAS, null) as? PrivateKey ?: return null
        Cipher.getInstance(RSA_MODE).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        deleteBiometricKey()
        null
    } catch (_: Exception) {
        null
    }

    /** Unwraps the user key with a cipher BiometricPrompt has authenticated. */
    fun unwrapBiometricKey(authenticated: Cipher): String? {
        val wrapped = prefs.getString(PREF_BIO, null)?.let { Base64.getDecoder().decode(it) } ?: return null
        return runCatching { String(authenticated.doFinal(wrapped), Charsets.UTF_8) }.getOrNull()
    }

    fun deleteBiometricKey() {
        prefs.edit().remove(PREF_BIO).apply()
        if (keyStore.containsAlias(BIO_ALIAS)) keyStore.deleteEntry(BIO_ALIAS)
    }

    // MARK: Old vault cache

    /** The encrypted copy of the vault that older versions kept; the vault is no longer stored. */
    private val cacheFile get() = File(context.noBackupFilesDir, "vault-sync.json")

    fun removeLeftoverCache() {
        cacheFile.delete()
        File(cacheFile.parentFile, "vault-sync.json.tmp").delete()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        deleteBiometricKey()
        removeLeftoverCache()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val STORE_ALIAS = "m3b_store_key"
        const val BIO_ALIAS = "m3b_biometric_key"
        const val PREF_BIO = "biometricUserKey"
        const val AES_MODE = "AES/GCM/NoPadding"
        const val RSA_MODE = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
