package com.framepersecond.m3bitwarden.android.passkey

import com.bitwarden.fido.CheckUserOptions
import com.bitwarden.fido.ClientData
import com.bitwarden.fido.Origin
import com.bitwarden.fido.PublicKeyCredentialAuthenticatorAssertionResponse
import com.bitwarden.fido.PublicKeyCredentialAuthenticatorAttestationResponse
import com.bitwarden.sdk.CheckUserAndPickCredentialForCreationResult
import com.bitwarden.sdk.CheckUserResult
import com.bitwarden.sdk.CipherViewWrapper
import com.bitwarden.sdk.Client
import com.bitwarden.sdk.Fido2CredentialStore
import com.bitwarden.sdk.Fido2UserInterface
import com.bitwarden.sdk.UiHint
import com.bitwarden.vault.CipherListView
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.EncryptionContext
import com.bitwarden.vault.Fido2CredentialNewView
import com.framepersecond.m3bitwarden.android.vault.newCipherView
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.LoginData
import com.framepersecond.m3bitwarden.data.PasskeyInfo
import com.framepersecond.m3bitwarden.data.VaultItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/*
 * Passkeys stored in Vaultwarden (the `fido2Credentials` of login items), for Android's
 * Credential Manager.
 *
 * All FIDO2 work — checking the origin against the relying party, building client data, decrypting
 * the private key, signing, creating and encrypting a new passkey — is done by the Bitwarden SDK's
 * FIDO2 client, the code the official Bitwarden app uses. This file hands it the vault, reports
 * what the user chose and turns the result into the JSON Credential Manager expects.
 */

/** One passkey as the system sheet shows it (no secret in here). */
class PasskeyEntry(
    val cipherId: String,
    val credentialId: ByteArray,
    val rpId: String,
    val userName: String,
    val userHandle: ByteArray,
    val itemName: String,
)

/** A login that can take a new passkey (Bitwarden keeps one passkey per login). */
data class PasskeyTarget(val cipherId: String, val name: String, val userName: String, val host: String, val match: Match = Match.None) {
    /** How well the login's username fits the account the site creates the passkey for. */
    enum class Match { None, Similar, Same }

    companion object {
        /**
         * Compares the account a site sends (e-mail, phone number or user name) with a username:
         * equal ignoring case, or the same phone digits, is [Match.Same]; the same e-mail local part
         * or one containing the other is [Match.Similar].
         */
        fun match(account: String, username: String): Match {
            val a = account.trim().lowercase()
            val b = username.trim().lowercase()
            if (a.isEmpty() || b.isEmpty()) return Match.None
            if (a == b) return Match.Same
            val da = a.filter(Char::isDigit)
            val db = b.filter(Char::isDigit)
            fun phoneLike(s: String, d: String) = d.length >= 6 && d.length * 10 >= s.count { !it.isWhitespace() } * 7
            if (phoneLike(a, da) && phoneLike(b, db) && da.takeLast(9) == db.takeLast(9)) return Match.Same
            val la = a.substringBefore('@')
            val lb = b.substringBefore('@')
            if (la == lb && la.length >= 3) return Match.Similar
            if (minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a))) return Match.Similar
            return Match.None
        }
    }
}

class PasskeyException(message: String) : Exception(message)

class PasskeyEngine(
    private val client: Client,
    /** Decrypted logins by id (passkeys inside stay encrypted until the SDK signs with them). */
    private val decrypted: Map<String, CipherView>,
    /** Saves an encrypted login on the server and returns its id. */
    private val upload: suspend (EncryptionContext) -> String,
) {
    private val passkeyCiphers: List<CipherView>
        get() = decrypted.values.filter { it.deletedDate == null && !it.login?.fido2Credentials.isNullOrEmpty() }

    /** Passkeys for [rpId], limited to [allowed] credential ids if given. */
    fun passkeys(rpId: String, allowed: List<ByteArray> = emptyList()): List<PasskeyEntry> {
        val fido2 = client.platform().fido2()
        return passkeyCiphers.flatMap { cipher ->
            val id = cipher.id ?: return@flatMap emptyList()
            val views = runCatching { fido2.getFido2AutofillCredentials(cipher) }.getOrDefault(emptyList())
            views.filter { v -> v.rpId == rpId && (allowed.isEmpty() || allowed.any { it.contentEquals(v.credentialId) }) }.map { v ->
                PasskeyEntry(id, v.credentialId, v.rpId, v.userNameForUi ?: cipher.login?.username.orEmpty(), v.userHandle, cipher.name)
            }
        }.sortedWith(compareBy({ it.itemName.lowercase() }, { it.userName }))
    }

    /** Site, account and creation date of each passkey in [cipher], for the item page. */
    fun details(cipher: CipherView): List<PasskeyInfo> {
        val stored = cipher.login?.fido2Credentials.orEmpty()
        if (stored.isEmpty()) return emptyList()
        val views = runCatching { client.platform().fido2().getFido2AutofillCredentials(cipher) }.getOrDefault(emptyList())
        // The SDK lists the credentials in stored order; the creation date is not encrypted.
        return views.mapIndexed { i, v ->
            val created = stored.getOrNull(i)?.creationDate?.let { DAY.format(it.atZone(ZoneId.systemDefault())) }.orEmpty()
            PasskeyInfo(v.rpId, v.userNameForUi ?: cipher.login?.username.orEmpty(), created)
        }
    }

    /** Logins for [rpId] that have no passkey yet; those whose username fits [account] first. */
    fun targets(rpId: String, account: String = ""): List<PasskeyTarget> {
        val site = rpId.lowercase()
        return decrypted.values.mapNotNull { c ->
            val id = c.id ?: return@mapNotNull null
            val login = c.login ?: return@mapNotNull null
            if (c.deletedDate != null || !c.edit || !login.fido2Credentials.isNullOrEmpty()) return@mapNotNull null
            val host = login.uris.orEmpty().mapNotNull { u -> u.uri?.let(::hostOf) }
                .firstOrNull { it == site || it.endsWith(".$site") || site.endsWith(".$it") } ?: return@mapNotNull null
            PasskeyTarget(id, c.name, login.username.orEmpty(), host, PasskeyTarget.match(account, login.username.orEmpty()))
        }.sortedWith(compareByDescending<PasskeyTarget> { it.match }.thenBy { it.name.lowercase() })
    }

    /**
     * Signs the WebAuthn get request [requestJson] for [origin] with the passkey of [cipherId] (the
     * entry the user tapped). Returns the `PublicKeyCredential` JSON.
     */
    suspend fun authenticate(origin: Origin, requestJson: String, clientData: ClientData, cipherId: String?): String {
        val session = Session(Purpose.Assertion(cipherId))
        val response = client.platform().fido2().client(session, session).authenticate(origin, wrapped(requestJson), clientData)
        return assertionJson(response)
    }

    /**
     * Creates a passkey for the WebAuthn create request [requestJson] in the login [cipherId] (a new
     * login when null) and saves it in Vaultwarden. Returns the `PublicKeyCredential` JSON.
     */
    suspend fun register(origin: Origin, requestJson: String, clientData: ClientData, cipherId: String?): String {
        val request = JSONObject(requestJson)
        val rp = request.optJSONObject("rp")
        val rpId = rp?.optString("id").orEmpty().ifEmpty { originHost(origin) }
        val userName = request.optJSONObject("user")?.optString("name").orEmpty()
        val target = if (cipherId != null) {
            decrypted[cipherId] ?: throw PasskeyException("Der Eintrag ist nicht mehr im Tresor.")
        } else {
            val name = rp?.optString("name").orEmpty().ifEmpty { rpId }
            newCipherView(
                VaultItem(id = "", name = name, type = ItemType.Login, login = LoginData(username = userName, uri = "https://$rpId")),
                folderId = null,
            )
        }
        val session = Session(Purpose.Registration(target))
        val response = client.platform().fido2().client(session, session).register(origin, wrapped(requestJson), clientData)
        if (session.savedCipherId == null) throw PasskeyException("Der Passkey wurde nicht gespeichert.")
        return attestationJson(response)
    }

    private sealed interface Purpose {
        data class Assertion(val cipherId: String?) : Purpose
        data class Registration(val target: CipherView) : Purpose
    }

    /** The SDK's callbacks for one request. The user is verified before a request gets here. */
    private inner class Session(private val purpose: Purpose) : Fido2UserInterface, Fido2CredentialStore {
        var savedCipherId: String? = null

        override suspend fun checkUser(options: CheckUserOptions, hint: UiHint): CheckUserResult {
            if (hint is UiHint.InformExcludedCredentialFound) throw PasskeyException("Für dieses Konto ist schon ein Passkey im Tresor gespeichert.")
            return CheckUserResult(userPresent = true, userVerified = true)
        }

        override suspend fun pickCredentialForAuthentication(availableCredentials: List<CipherView>): CipherViewWrapper {
            val wanted = (purpose as? Purpose.Assertion)?.cipherId
            val picked = availableCredentials.firstOrNull { it.id == wanted } ?: availableCredentials.firstOrNull()
                ?: throw PasskeyException("Dieser Passkey ist nicht mehr im Tresor.")
            return CipherViewWrapper(picked)
        }

        override suspend fun checkUserAndPickCredentialForCreation(options: CheckUserOptions, newCredential: Fido2CredentialNewView): CheckUserAndPickCredentialForCreationResult {
            val target = (purpose as? Purpose.Registration)?.target ?: throw PasskeyException("Kein Ziel für den neuen Passkey.")
            return CheckUserAndPickCredentialForCreationResult(CipherViewWrapper(target), CheckUserResult(userPresent = true, userVerified = true))
        }

        override fun isVerificationEnabled(): Boolean = true

        override suspend fun findCredentials(ids: List<ByteArray>?, ripId: String, userHandle: ByteArray?): List<CipherView> {
            val wanted = ids.orEmpty()
            // While creating, the SDK only asks to check the site's exclude list.
            if (purpose is Purpose.Registration && wanted.isEmpty()) return emptyList()
            val fido2 = client.platform().fido2()
            return passkeyCiphers.filter { cipher ->
                val views = runCatching { fido2.getFido2AutofillCredentials(cipher) }.getOrDefault(emptyList())
                views.any { v ->
                    v.rpId == ripId &&
                        (wanted.isEmpty() || wanted.any { it.contentEquals(v.credentialId) }) &&
                        (userHandle == null || userHandle.contentEquals(v.userHandle))
                }
            }
        }

        override suspend fun allCredentials(): List<CipherListView> = emptyList()

        override suspend fun saveCredential(cred: EncryptionContext) {
            when (purpose) {
                // Must reach the server: the site keeps the public key either way.
                is Purpose.Registration -> savedCipherId = upload(cred)
                // Only passkeys with a signature counter get here; a failed upload must not block the sign-in.
                is Purpose.Assertion -> runCatching { upload(cred) }
            }
        }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** Credential Manager hands over the bare options; the SDK reads `{"publicKey": options}` as in `navigator.credentials`. */
        fun wrapped(options: String): String = "{\"publicKey\": $options}"

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun hostOf(uri: String): String? = runCatching { URI(if ("://" in uri) uri else "https://$uri").host?.lowercase() }.getOrNull()

        fun originHost(origin: Origin): String = when (origin) {
            is Origin.Web -> hostOf(origin.v1).orEmpty()
            is Origin.Android -> origin.v1.host
        }

        fun assertionJson(r: PublicKeyCredentialAuthenticatorAssertionResponse): String = JSONObject()
            .put("id", r.id)
            .put("rawId", b64(r.rawId))
            .put("type", r.ty)
            .put("authenticatorAttachment", r.authenticatorAttachment ?: "platform")
            .put(
                "response",
                JSONObject()
                    .put("clientDataJSON", b64(r.response.clientDataJson))
                    .put("authenticatorData", b64(r.response.authenticatorData))
                    .put("signature", b64(r.response.signature))
                    .put("userHandle", b64(r.response.userHandle)),
            )
            .put("clientExtensionResults", JSONObject())
            .toString()

        fun attestationJson(r: PublicKeyCredentialAuthenticatorAttestationResponse): String {
            val response = JSONObject()
                .put("clientDataJSON", b64(r.response.clientDataJson))
                .put("attestationObject", b64(r.response.attestationObject))
                .put("authenticatorData", b64(r.response.authenticatorData))
                .put("publicKeyAlgorithm", r.response.publicKeyAlgorithm)
                .put("transports", JSONArray(r.response.transports ?: listOf("internal", "hybrid")))
            r.response.publicKey?.let { response.put("publicKey", b64(it)) }
            val extensions = JSONObject()
            r.clientExtensionResults.credProps?.rk?.let { extensions.put("credProps", JSONObject().put("rk", it)) }
            return JSONObject()
                .put("id", r.id)
                .put("rawId", b64(r.rawId))
                .put("type", r.ty)
                .put("authenticatorAttachment", r.authenticatorAttachment ?: "platform")
                .put("response", response)
                .put("clientExtensionResults", extensions)
                .toString()
        }
    }
}
