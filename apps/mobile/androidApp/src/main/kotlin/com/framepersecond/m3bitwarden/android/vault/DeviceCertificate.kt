package com.framepersecond.m3bitwarden.android.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager

/**
 * The device's TLS client certificate for vault.* (Cloudflare mTLS).
 *
 * The private key is created in the Android Keystore and never leaves it: the app shows a
 * certificate signing request (CSR), the user pastes it into Cloudflare ("Client certificates →
 * Create → Use my private key and CSR") and pastes the returned certificate back.
 */
class DeviceCertificate(context: Context) {
    class Failure(message: String) : Exception(message)

    private val prefs = context.getSharedPreferences("m3b_mtls", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    val hasKey: Boolean get() = keyStore.containsAlias(ALIAS)
    val isInstalled: Boolean get() = hasKey && certificate() != null

    /** Creates the key if needed and returns a PEM CSR for Cloudflare. */
    fun makeSigningRequest(deviceName: String): String {
        if (!hasKey) createKey()
        val publicKeyInfo = keyStore.getCertificate(ALIAS).publicKey.encoded // SubjectPublicKeyInfo DER
        val subject = Der.sequence(
            Der.set(Der.sequence(Der.oid("2.5.4.10"), Der.utf8("M3 Bitwarden"))),
            Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8(deviceName))),
        )
        val info = Der.sequence(Der.integer(0), subject, publicKeyInfo, Der.contextConstructed(0, ByteArray(0)))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey())
            update(info)
            sign()
        }
        val csr = Der.sequence(info, Der.sequence(Der.oid("1.2.840.10045.4.3.2")), Der.bitString(signature))
        return Der.pem(csr, "CERTIFICATE REQUEST")
    }

    /** Stores the certificate Cloudflare issued for this device's CSR. */
    fun install(pem: String) {
        val cert = parse(pem) ?: throw Failure("Das ist kein gültiges Zertifikat. Kopiere den ganzen Block mit BEGIN/END CERTIFICATE.")
        if (!hasKey) throw Failure("Es gibt noch keinen Geräteschlüssel. Erzeuge zuerst die Anfrage.")
        val ours = keyStore.getCertificate(ALIAS).publicKey.encoded
        if (!cert.publicKey.encoded.contentEquals(ours)) {
            throw Failure("Dieses Zertifikat gehört zu einer anderen Anfrage. Erstelle es in Cloudflare mit der Anfrage dieses Geräts.")
        }
        prefs.edit().putString(PREF_CERT, Base64.getEncoder().encodeToString(cert.encoded)).putLong(PREF_INSTALLED, System.currentTimeMillis()).apply()
    }

    /** Removes certificate and key (e.g. to re-enroll). */
    fun reset() {
        prefs.edit().clear().apply()
        if (hasKey) keyStore.deleteEntry(ALIAS)
    }

    val installedAt: Long? get() = prefs.getLong(PREF_INSTALLED, 0).takeIf { it > 0 }

    val expiresAt: Long? get() = certificate()?.notAfter?.time

    /** Socket factory that answers Cloudflare's client-certificate request with the device key. */
    fun socketFactory(): SSLSocketFactory {
        val cert = certificate() ?: throw Failure("Dieses Gerät hat noch kein Zertifikat für den Server.")
        val key = privateKey()
        val manager = object : X509KeyManager {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = key
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = emptyArray<String>()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }.socketFactory
    }

    private fun createKey() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun privateKey(): PrivateKey = keyStore.getKey(ALIAS, null) as PrivateKey

    private fun certificate(): X509Certificate? {
        val b64 = prefs.getString(PREF_CERT, null) ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(b64))) as X509Certificate
        }.getOrNull()
    }

    /** The first certificate of a pasted PEM (Cloudflare may add the CA after it). */
    private fun parse(text: String): X509Certificate? {
        val body = Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: text
        val der = runCatching { Base64.getMimeDecoder().decode(body.trim()) }.getOrNull() ?: return null
        return runCatching { CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate }.getOrNull()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "m3b_mtls_device_key"
        const val PREF_CERT = "certificate"
        const val PREF_INSTALLED = "installedAt"
    }
}
