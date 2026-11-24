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
import com.bitwarden.core.InitUserCryptoMethod
import com.bitwarden.core.InitUserCryptoRequest
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.fido.ClientData
import com.bitwarden.fido.Origin
import com.bitwarden.vault.Cipher
import com.bitwarden.vault.EncryptionContext
import com.framepersecond.m3bitwarden.android.passkey.PasskeyEngine
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

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
        runCatching { passkeys(client) }.onFailure { Log.e(TAG, "passkey FAIL: ${it.message}", it) }
        client.close()
        Log.i(TAG, "done")
    }

    /**
     * Passkeys end to end against a throwaway vault in memory (made-up account, no server): create,
     * upload body, find again, sign in, verify the signature, origin binding, exclude list.
     * Logs one line per check; no keys and no vault data.
     */
    private suspend fun passkeys(client: Client) {
        val email = "selftest@example.invalid"
        val password = "selftest-throwaway-password"
        val kdf = Kdf.Pbkdf2(600_000u)
        val keys = client.auth().makeRegisterKeys(email, password, kdf)
        client.crypto().initializeUserCrypto(
            InitUserCryptoRequest(
                userId = UUID.randomUUID().toString(),
                kdfParams = kdf,
                email = email,
                accountCryptographicState = WrappedAccountCryptographicState.V1(keys.keys.private),
                method = InitUserCryptoMethod.MasterPasswordUnlock(password, MasterPasswordUnlockData(kdf, keys.encryptedUserKey, email)),
                upgradeToken = null,
            ),
        )
        val server = mutableMapOf<String, Cipher>()
        val upload: suspend (EncryptionContext) -> String = { ctx ->
            check(ctx.toRequestJson().optString("encryptedFor").isNotEmpty()) { "upload body without encryptedFor" }
            val id = ctx.cipher.id ?: UUID.randomUUID().toString()
            server[id] = ctx.cipher.copy(id = id)
            id
        }
        fun check(ok: Boolean, what: String) {
            if (!ok) throw IllegalStateException(what)
            Log.i(TAG, "passkey ok: $what")
        }
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val unb64 = Base64.getUrlDecoder()
        val rpId = "webauthn.io"
        val origin = Origin.Web("https://$rpId")
        val app = ClientData.DefaultWithExtraData("com.framepersecond.m3bitwarden.selftest")
        val userHandle = ByteArray(16).also { SecureRandom().nextBytes(it) }
        fun challenge() = b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun createJson(exclude: String? = null) = JSONObject()
            .put("rp", JSONObject().put("id", rpId).put("name", "WebAuthn.io"))
            .put("user", JSONObject().put("id", b64.encodeToString(userHandle)).put("name", "selftest-user").put("displayName", "Self Test"))
            .put("challenge", challenge())
            .put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
            .put("authenticatorSelection", JSONObject().put("residentKey", "required").put("userVerification", "required"))
            .put("excludeCredentials", JSONArray().apply { exclude?.let { put(JSONObject().put("type", "public-key").put("id", it)) } })
            .put("attestation", "none")
            .toString()

        // 1. Create a passkey in a new login.
        val created = JSONObject(PasskeyEngine(client, emptyMap(), upload).register(origin, createJson(), app, null))
        val credentialId = created.getString("id")
        val spki = unb64.decode(created.getJSONObject("response").getString("publicKey"))
        check(server.size == 1 && credentialId.isNotEmpty(), "passkey created and uploaded")
        val stored = server.values.first()
        val body = stored.toRequestJson().toString()
        check(body.contains("fido2Credentials") && body.contains("\"keyValue\":\"2."), "upload body carries the encrypted passkey")
        check(!body.contains("selftest-user"), "upload body has no plaintext username")

        // 2. After a "sync": decrypt and find it again.
        val view = client.vault().ciphers().decrypt(stored)
        val engine = PasskeyEngine(client, mapOf(stored.id!! to view), upload)
        check(engine.passkeys(rpId).size == 1 && engine.passkeys("example.com").isEmpty(), "passkey listed only for $rpId")
        check(engine.details(view).singleOrNull()?.site == rpId, "item page shows the passkey's site")

        // 3. Sign in as an app (SDK builds the client data) and verify the signature.
        val challenge = challenge()
        val getJson = JSONObject().put("challenge", challenge).put("rpId", rpId).put("allowCredentials", JSONArray()).put("userVerification", "required").toString()
        val assertion = JSONObject(engine.authenticate(origin, getJson, app, null)).getJSONObject("response")
        val clientDataJson = unb64.decode(assertion.getString("clientDataJSON"))
        val clientData = JSONObject(String(clientDataJson))
        check(clientData.getString("type") == "webauthn.get" && clientData.getString("challenge") == challenge && clientData.getString("origin") == "https://$rpId", "client data names the challenge and origin")
        val authData = unb64.decode(assertion.getString("authenticatorData"))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        fun verifies(signed: ByteArray, signature: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey); update(signed); verify(signature)
        }
        val sha = MessageDigest.getInstance("SHA-256")
        check(verifies(authData + sha.digest(clientDataJson), unb64.decode(assertion.getString("signature"))), "signature verifies with the registered public key")
        check(authData.copyOfRange(0, 32).contentEquals(sha.digest(rpId.toByteArray())), "authenticator data is bound to $rpId")

        // 4. Browser path: the browser's own client data hash is signed.
        val hash = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val browser = JSONObject(engine.authenticate(origin, getJson, ClientData.DefaultWithCustomHash(hash), null)).getJSONObject("response")
        check(verifies(unb64.decode(browser.getString("authenticatorData")) + hash, unb64.decode(browser.getString("signature"))), "browser client data hash is signed")

        // 5. Another origin must not get a signature for this site.
        val refused = runCatching { engine.authenticate(Origin.Web("https://evil.example"), getJson, app, null) }.isFailure
        check(refused, "refuses a request from another origin")

        // 6. Exclude list: the site already has this passkey.
        val excluded = runCatching { engine.register(origin, createJson(exclude = credentialId), app, null) }.isFailure
        check(excluded && server.size == 1, "exclude list respected")
        Log.i(TAG, "passkey done: all checks passed")
    }

    private const val TAG = "M3BSelfTest"
}
