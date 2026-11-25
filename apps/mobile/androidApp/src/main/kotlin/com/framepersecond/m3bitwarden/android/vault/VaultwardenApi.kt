package com.framepersecond.m3bitwarden.android.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Bitwarden API calls against Vaultwarden, over connections that present the device certificate. */
class VaultwardenApi(private val certificate: DeviceCertificate, private val store: SecureStore) {

    sealed class Failure(message: String) : Exception(message) {
        class NoCertificate : Failure("Dieses Gerät hat noch kein Zertifikat für den Server.")
        class BlockedByCloudflare : Failure("Cloudflare hat die Anfrage abgelehnt. Prüfe, ob das Gerätezertifikat in Cloudflare noch gültig ist.")
        class TwoFactorRequired(val providers: List<Int>) : Failure("Zwei-Faktor-Code erforderlich.")
        class InvalidCredentials(message: String) : Failure(message.ifEmpty { "E-Mail oder Master-Passwort ist falsch." })
        class Server(code: Int, message: String) : Failure("Serverfehler $code${if (message.isEmpty()) "" else ": $message"}")
        class NotSignedIn : Failure("Nicht angemeldet.")
        class Offline(cause: IOException) : Failure("Server nicht erreichbar (${cause.message ?: cause.javaClass.simpleName}).")
    }

    data class Prelogin(val kdf: Int, val iterations: Int, val memory: Int?, val parallelism: Int?)

    private val refreshLock = Mutex()

    // MARK: Identity

    suspend fun prelogin(email: String): Prelogin {
        val json = JSONObject(send("POST", "${M3BConfig.IDENTITY_URL}/accounts/prelogin", JSONObject().put("email", email).toString(), auth = false))
        return Prelogin(json.int("kdf") ?: 0, json.int("kdfIterations") ?: 600_000, json.int("kdfMemory"), json.int("kdfParallelism"))
    }

    /**
     * Starts a registration. Returns the verification token when the server hands it out right
     * away (mail off or no e-mail check); null when it sent a confirmation link by e-mail.
     */
    suspend fun registerVerification(email: String, name: String?): String? {
        val body = JSONObject().put("email", email).apply { if (!name.isNullOrBlank()) put("name", name) }
        val text = send("POST", "${M3BConfig.IDENTITY_URL}/accounts/register/send-verification-email", body.toString(), auth = false).trim()
        if (text.isEmpty()) return null
        return (runCatching { org.json.JSONTokener(text).nextValue() as? String }.getOrNull() ?: text).ifBlank { null }
    }

    /** Creates the account with keys made by the SDK (nothing secret leaves the device unencrypted). */
    suspend fun registerFinish(body: JSONObject) {
        send("POST", "${M3BConfig.IDENTITY_URL}/accounts/register/finish", body.toString(), auth = false)
    }

    /** Password grant. [passwordHash] is the SDK's server-authorization hash, never the password. */
    suspend fun login(email: String, passwordHash: String, deviceIdentifier: String, deviceName: String, twoFactor: Pair<Int, String>?): JSONObject {
        val form = mutableListOf(
            "grant_type" to "password",
            "username" to email,
            "password" to passwordHash,
            "scope" to "api offline_access",
            "client_id" to M3BConfig.CLIENT_ID,
            "deviceIdentifier" to deviceIdentifier,
            "deviceName" to deviceName,
            "deviceType" to M3BConfig.DEVICE_TYPE.toString(),
        )
        if (twoFactor != null) {
            form += listOf("twoFactorToken" to twoFactor.second, "twoFactorProvider" to twoFactor.first.toString(), "twoFactorRemember" to "1")
        }
        return token(form, email)
    }

    private suspend fun refresh(refreshToken: String): JSONObject = token(
        listOf("grant_type" to "refresh_token", "client_id" to M3BConfig.CLIENT_ID, "refresh_token" to refreshToken),
        email = null,
    )

    suspend fun sendEmailCode(email: String, passwordHash: String) {
        send("POST", "${M3BConfig.API_URL}/two-factor/send-email-login", JSONObject().put("email", email).put("masterPasswordHash", passwordHash).toString(), auth = false)
    }

    // MARK: Vault

    suspend fun sync(): String = send("GET", "${M3BConfig.API_URL}/sync?excludeDomains=false", null, auth = true)

    suspend fun createCipher(body: JSONObject): JSONObject = JSONObject(send("POST", "${M3BConfig.API_URL}/ciphers", body.toString(), auth = true))

    /** Uploads an encrypted text Send (secure link); returns the server's answer with its accessId. */
    suspend fun createSend(body: JSONObject): JSONObject = JSONObject(send("POST", "${M3BConfig.API_URL}/sends", body.toString(), auth = true))

    suspend fun updateCipher(id: String, body: JSONObject): JSONObject = JSONObject(send("PUT", "${M3BConfig.API_URL}/ciphers/$id", body.toString(), auth = true))

    /** Moves the item to the trash (restorable in the web vault). */
    suspend fun softDeleteCipher(id: String) {
        send("PUT", "${M3BConfig.API_URL}/ciphers/$id/delete", null, auth = true)
    }

    suspend fun createFolder(encryptedName: String): JSONObject =
        JSONObject(send("POST", "${M3BConfig.API_URL}/folders", JSONObject().put("name", encryptedName).toString(), auth = true))

    // MARK: Tokens

    fun saveTokens(response: JSONObject) {
        val previous = store.get(TOKENS)?.let(::JSONObject)
        val refresh = response.str("refresh_token") ?: previous?.str("refresh") ?: throw Failure.NotSignedIn()
        val expires = System.currentTimeMillis() + (response.int("expires_in") ?: 3600) * 1000L
        store.put(TOKENS, JSONObject().put("access", response.str("access_token")).put("refresh", refresh).put("expires", expires).toString())
    }

    fun clearTokens() = store.put(TOKENS, null)

    suspend fun validAccessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val tokens = store.get(TOKENS)?.let(::JSONObject) ?: throw Failure.NotSignedIn()
        val access = tokens.str("access") ?: throw Failure.NotSignedIn()
        if (!forceRefresh && tokens.optLong("expires") - System.currentTimeMillis() > 120_000) return@withLock access
        val response = refresh(tokens.str("refresh") ?: throw Failure.NotSignedIn())
        saveTokens(response)
        response.str("access_token") ?: throw Failure.NotSignedIn()
    }

    // MARK: Plumbing

    private suspend fun token(form: List<Pair<String, String>>, email: String?): JSONObject {
        val body = form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val headers = buildMap {
            put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            if (email != null) put("Auth-Email", Base64.getUrlEncoder().withoutPadding().encodeToString(email.toByteArray()))
        }
        val (code, text) = perform("POST", "${M3BConfig.IDENTITY_URL}/connect/token", body, headers)
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code == 200) return json
        json.arr("TwoFactorProviders")?.let { arr ->
            throw Failure.TwoFactorRequired((0 until arr.length()).mapNotNull { arr.opt(it)?.toString()?.toIntOrNull() })
        }
        if (code == 400 || code == 401) throw Failure.InvalidCredentials(message(json))
        throw Failure.Server(code, message(json))
    }

    private suspend fun send(method: String, url: String, json: String?, auth: Boolean): String {
        val headers = mutableMapOf<String, String>()
        if (json != null) headers["Content-Type"] = "application/json"
        if (auth) headers["Authorization"] = "Bearer ${validAccessToken()}"
        var (code, text) = perform(method, url, json, headers)
        if (auth && code == 401) {
            headers["Authorization"] = "Bearer ${validAccessToken(forceRefresh = true)}"
            val retry = perform(method, url, json, headers)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            if (code == 401) throw Failure.NotSignedIn()
            throw Failure.Server(code, message(runCatching { JSONObject(text) }.getOrElse { JSONObject() }))
        }
        return text
    }

    private suspend fun perform(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (!certificate.isInstalled) throw Failure.NoCertificate()
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.sslSocketFactory = certificate.socketFactory()
                conn.requestMethod = method
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Bitwarden-Client-Name", M3BConfig.CLIENT_ID)
                conn.setRequestProperty("Bitwarden-Client-Version", M3BConfig.CLIENT_VERSION)
                conn.setRequestProperty("Device-Type", M3BConfig.DEVICE_TYPE.toString())
                conn.setRequestProperty("User-Agent", "M3Bitwarden/${M3BConfig.CLIENT_VERSION} (Android)")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // Cloudflare's WAF answers with an HTML page when the certificate is missing or revoked.
                if (code == 403 && conn.contentType.orEmpty().contains("text/html")) throw Failure.BlockedByCloudflare()
                code to text
            } catch (e: Failure) {
                throw e
            } catch (e: IOException) {
                throw Failure.Offline(e)
            } finally {
                conn.disconnect()
            }
        }

    /** Website icon from the server's icon service (`/icons/<host>/icon.png`), or null. */
    suspend fun icon(host: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!certificate.isInstalled || !host.matches(Regex("^[a-z0-9.-]+$"))) return@withContext null
        val conn = URL("${M3BConfig.SERVER_URL}/icons/$host/icon.png").openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = certificate.socketFactory()
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "M3Bitwarden/${M3BConfig.CLIENT_VERSION} (Android)")
            if (conn.responseCode != 200 || !(conn.contentType ?: "image").startsWith("image")) return@withContext null
            conn.inputStream.use { it.readBytes() }.takeIf { it.size < 300_000 }
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun message(body: JSONObject): String =
        body.obj("ErrorModel")?.str("Message") ?: body.str("error_description") ?: body.str("message") ?: ""

    private companion object {
        const val TOKENS = "tokens"
    }
}
