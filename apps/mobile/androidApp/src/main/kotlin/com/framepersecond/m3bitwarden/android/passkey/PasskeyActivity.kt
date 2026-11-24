package com.framepersecond.m3bitwarden.android.passkey

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.bitwarden.fido.ClientData
import com.bitwarden.fido.Origin
import com.bitwarden.fido.UnverifiedAssetLink
import com.framepersecond.m3bitwarden.ThemeMode
import com.framepersecond.m3bitwarden.android.autofill.AppIdentity
import com.framepersecond.m3bitwarden.android.autofill.AssetLinksVerifier
import com.framepersecond.m3bitwarden.android.autofill.Unlock
import com.framepersecond.m3bitwarden.android.vault.AndroidVault
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Sheet behind the Credential Manager entries: unlocks the vault, verifies the user (biometrics or
 * screen lock) and lets the Bitwarden SDK sign in with a passkey or create one.
 *
 * Origins: a trusted browser (known package from a trusted store, see [AppIdentity]) reports the
 * website's origin; any other app must be listed in the site's `assetlinks.json`. The SDK then
 * checks that the relying party belongs to that origin before it signs.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Working : Screen
        data class Choose(val site: String, val user: String, val targets: List<PasskeyTarget>) : Screen
        data class Failed(val message: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Working)
    private var title by mutableStateOf("Passkey")

    /** The vault was opened with biometrics for this request, which already verified the user. */
    private var verifiedByUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            M3BitwardenTheme(themeMode = ThemeMode.System, dynamicColor = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Content()
                }
            }
        }
        val wasUnlocked = VaultSession.isUnlocked
        Unlock.ensure(this) { ok ->
            if (!ok) {
                screen = Screen.Failed(AndroidVault.get(this).error ?: "Entsperre den Tresor in der App M3 Bitwarden und versuche es dann noch einmal.")
                return@ensure
            }
            verifiedByUnlock = !wasUnlocked
            when (intent.action) {
                ACTION_UNLOCK -> finishUnlock()
                ACTION_GET -> signIn()
                ACTION_CREATE -> prepareCreate()
                else -> cancel()
            }
        }
    }

    // MARK: Locked list → entries

    private fun finishUnlock() {
        val begin = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: return cancel()
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, M3BCredentialProviderService.entries(this, begin))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    // MARK: Sign in

    private fun signIn() {
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return cancel()
        val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: return cancel()
        val rpId = runCatching { JSONObject(option.requestJson).optString("rpId") }.getOrDefault("")
        title = "Anmelden bei $rpId"
        run(onError = { message ->
            val result = Intent()
            PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException(message))
            result
        }) {
            val engine = AndroidVault.get(this).passkeyEngine() ?: throw PasskeyException("Der Tresor ist gesperrt.")
            val (origin, clientData) = originFor(request.callingAppInfo, rpId, option.clientDataHash)
            verifyUser("Mit Passkey anmelden", rpId)
            val json = engine.authenticate(origin, option.requestJson, clientData, intent.getStringExtra(EXTRA_CIPHER))
            Intent().also { PendingIntentHandler.setGetCredentialResponse(it, GetCredentialResponse(PublicKeyCredential(json))) }
        }
    }

    // MARK: Create

    private fun prepareCreate() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return cancel()
        val create = request.callingRequest as? CreatePublicKeyCredentialRequest ?: return cancel()
        val json = runCatching { JSONObject(create.requestJson) }.getOrNull() ?: return cancel()
        val rpId = json.optJSONObject("rp")?.optString("id").orEmpty()
        val user = json.optJSONObject("user")?.optString("name").orEmpty()
        val engine = AndroidVault.get(this).passkeyEngine() ?: return cancel()
        title = "Passkey speichern"
        screen = Screen.Choose(rpId, user, if (rpId.isEmpty()) emptyList() else engine.targets(rpId))
    }

    private fun create(cipherId: String?) {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return cancel()
        val create = request.callingRequest as? CreatePublicKeyCredentialRequest ?: return cancel()
        val rpId = runCatching { JSONObject(create.requestJson).optJSONObject("rp")?.optString("id") }.getOrNull().orEmpty()
        title = "Passkey für $rpId wird gespeichert"
        run(onError = { message ->
            val result = Intent()
            PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException(message))
            result
        }) {
            val engine = AndroidVault.get(this).passkeyEngine() ?: throw PasskeyException("Der Tresor ist gesperrt.")
            val (origin, clientData) = originFor(request.callingAppInfo, rpId, create.clientDataHash)
            verifyUser("Passkey speichern", rpId)
            val json = engine.register(origin, create.requestJson, clientData, cipherId)
            Intent().also { PendingIntentHandler.setCreateCredentialResponse(it, CreatePublicKeyCredentialResponse(json)) }
        }
    }

    // MARK: Helpers

    /** Runs [work]; a failure stays on screen until closed, then goes back to the caller. */
    private fun run(onError: (String) -> Intent, work: suspend () -> Intent) {
        screen = Screen.Working
        lifecycleScope.launch {
            try {
                val result = work()
                setResult(Activity.RESULT_OK, result)
                finish()
            } catch (e: Exception) {
                val message = (e as? PasskeyException)?.message ?: e.message ?: e.javaClass.simpleName
                failureResult = onError(message)
                screen = Screen.Failed(message)
            }
        }
    }

    private var failureResult: Intent? = null

    /**
     * The origin the SDK checks the relying party against, and how it builds the client data.
     * Browsers send their own client data hash; apps get Android client data with their package.
     */
    private suspend fun originFor(calling: CallingAppInfo, rpId: String, clientDataHash: ByteArray?): Pair<Origin, ClientData> {
        val app = AppIdentity.of(this, calling.packageName)
        val cert = app.certSha256 ?: throw PasskeyException("Die App ${app.label} ist nicht signiert.")
        if (app.isTrustedBrowser) {
            val allowlist = JSONObject().put(
                "apps",
                JSONArray().put(
                    JSONObject().put("type", "android").put(
                        "info",
                        JSONObject().put("package_name", calling.packageName)
                            .put("signatures", JSONArray().put(JSONObject().put("build", "release").put("cert_fingerprint_sha256", cert))),
                    ),
                ),
            ).toString()
            val origin = runCatching { calling.getOrigin(allowlist) }.getOrNull()
                ?: throw PasskeyException("${app.label} hat keine Website-Adresse mitgeschickt.")
            val data = clientDataHash?.let { ClientData.DefaultWithCustomHash(it) } ?: ClientData.DefaultWithExtraData(calling.packageName)
            return Origin.Web(origin) to data
        }
        if (rpId.isEmpty()) throw PasskeyException("Die Anfrage nennt keine Website.")
        val verified = withContext(Dispatchers.IO) { AssetLinksVerifier(this@PasskeyActivity).verifiedDomains(app, listOf(rpId), budgetMs = 5_000) }
        if (rpId !in verified) throw PasskeyException("${app.label} ist nicht mit $rpId verknüpft. Passkeys werden nur Apps gegeben, die die Website selbst freigibt.")
        return Origin.Android(UnverifiedAssetLink(calling.packageName, cert, rpId, null)) to ClientData.DefaultWithExtraData(calling.packageName)
    }

    /** Biometrics or screen lock, unless the unlock for this request just did that. */
    private suspend fun verifyUser(action: String, site: String) {
        if (verifiedByUnlock) return
        val ok = suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            BiometricPrompt.Builder(this)
                .setTitle(action)
                .setSubtitle(site)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    signal,
                    mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
        }
        if (!ok) throw PasskeyException("Abgebrochen.")
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun closeAfterFailure() {
        val result = failureResult
        if (result != null) setResult(Activity.RESULT_OK, result) else setResult(Activity.RESULT_CANCELED)
        finish()
    }

    // MARK: UI

    @Composable
    private fun Content() {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            when (val s = screen) {
                Screen.Working -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(96.dp)) }
                is Screen.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = ::closeAfterFailure, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Schließen") }
                }
                is Screen.Choose -> Choose(s)
            }
        }
    }

    @Composable
    private fun Choose(s: Screen.Choose) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Symbols.Passkey, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(s.site, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (s.user.isNotEmpty()) Text(s.user, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Wird verschlüsselt in deinem Vaultwarden gespeichert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = { create(null) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Als neuen Eintrag speichern") }
            if (s.targets.isNotEmpty()) {
                Text("Oder zu einem Login hinzufügen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(s.targets) { index, t ->
                        val top = if (index == 0) 24.dp else 4.dp
                        val bottom = if (index == s.targets.lastIndex) 24.dp else 4.dp
                        Surface(
                            shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable { create(t.cipherId) },
                        ) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(t.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                if (t.userName.isNotEmpty()) Text(t.userName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }

    companion object {
        const val ACTION_UNLOCK = "com.framepersecond.m3bitwarden.passkey.UNLOCK"
        const val ACTION_GET = "com.framepersecond.m3bitwarden.passkey.GET"
        const val ACTION_CREATE = "com.framepersecond.m3bitwarden.passkey.CREATE"
        const val EXTRA_CIPHER = "cipher"

        fun pendingIntent(context: Context, action: String, requestCode: Int, extras: Intent? = null): PendingIntent {
            val intent = Intent(context, PasskeyActivity::class.java).setAction(action).setPackage(context.packageName)
            extras?.extras?.let(intent::putExtras)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
