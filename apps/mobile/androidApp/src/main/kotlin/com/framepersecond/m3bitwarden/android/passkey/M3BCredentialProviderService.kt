package com.framepersecond.m3bitwarden.android.passkey

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.android.vault.AndroidVault
import com.framepersecond.m3bitwarden.data.VaultSession
import org.json.JSONObject
import java.util.Base64

/**
 * Credential Manager provider (Android 14+): offers the passkeys stored in Vaultwarden to Chrome
 * and apps, and saves new passkeys there.
 *
 * Listing needs the open vault (only names and accounts, no secret). While it is locked, the sheet
 * shows one "unlock" entry; using or creating a passkey always goes through [PasskeyActivity],
 * which verifies the user and lets the Bitwarden SDK check the origin and sign.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class M3BCredentialProviderService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (request.beginGetCredentialOptions.none { it is BeginGetPublicKeyCredentialOption }) {
            return callback.onResult(BeginGetCredentialResponse())
        }
        if (!VaultSession.isUnlocked) {
            val unlock = PasskeyActivity.pendingIntent(this, PasskeyActivity.ACTION_UNLOCK, requestCode = 1)
            return callback.onResult(BeginGetCredentialResponse(authenticationActions = listOf(AuthenticationAction(getString(R.string.app_name), unlock))))
        }
        callback.onResult(entries(this, request))
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            return callback.onError(CreateCredentialUnknownException("Nur Passkeys"))
        }
        val vault = AndroidVault.get(this)
        val entry = CreateEntry.Builder(
            vault.accountEmail.ifEmpty { getString(R.string.app_name) },
            PasskeyActivity.pendingIntent(this, PasskeyActivity.ACTION_CREATE, requestCode = 2),
        ).setDescription("Passkey verschlüsselt in Vaultwarden speichern").build()
        callback.onResult(BeginCreateCredentialResponse(createEntries = listOf(entry)))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    companion object {
        /** One entry per matching passkey of the open vault. */
        fun entries(context: Context, request: BeginGetCredentialRequest): BeginGetCredentialResponse {
            val engine = AndroidVault.get(context).passkeyEngine() ?: return BeginGetCredentialResponse()
            val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
            // Android lists the passkey used last first.
            val usage = com.framepersecond.m3bitwarden.android.autofill.PrefsAutofillStore(context).usage()
            val entries = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>().flatMap { option ->
                val json = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return@flatMap emptyList()
                val rpId = json.optString("rpId")
                if (rpId.isEmpty()) return@flatMap emptyList()
                val allowed = json.optJSONArray("allowCredentials")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.let(::fromB64) }
                }.orEmpty()
                engine.passkeys(rpId, allowed).map { p ->
                    val intent = PasskeyActivity.pendingIntent(
                        context,
                        PasskeyActivity.ACTION_GET,
                        requestCode = (p.cipherId + p.rpId).hashCode(),
                        extras = Intent().putExtra(PasskeyActivity.EXTRA_CIPHER, p.cipherId),
                    )
                    PublicKeyCredentialEntry.Builder(context, p.userName.ifEmpty { p.itemName }, intent, option)
                        .setDisplayName(p.itemName)
                        .setIcon(icon)
                        .setLastUsedTime(usage[p.cipherId]?.first?.let(java.time.Instant::ofEpochMilli))
                        .build()
                }
            }
            return BeginGetCredentialResponse(credentialEntries = entries)
        }

        private fun fromB64(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text.trimEnd('=')) }.getOrNull()
    }
}
