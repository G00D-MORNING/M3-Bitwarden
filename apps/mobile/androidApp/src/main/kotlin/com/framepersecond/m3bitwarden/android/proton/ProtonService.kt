package com.framepersecond.m3bitwarden.android.proton

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.framepersecond.m3bitwarden.android.vault.SecureStore
import com.framepersecond.m3bitwarden.authenticator.AuthEntry
import com.framepersecond.m3bitwarden.authenticator.ProtonAuthenticator
import com.framepersecond.m3bitwarden.authenticator.ProtonBackend
import com.framepersecond.m3bitwarden.data.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.m3_proton.ProtonFfiException
import uniffi.m3_proton.protonCode
import uniffi.m3_proton.protonSignInPoll
import uniffi.m3_proton.protonSignInStart
import uniffi.m3_proton.protonSignOut
import uniffi.m3_proton.protonSync

/**
 * The Proton Authenticator on Android: browser sign-in, session in the Keystore-backed store
 * (renewed by the Rust core, so no new sign-in is needed while it is used), entries in memory only.
 */
class ProtonService(private val context: Context) : ProtonBackend {
    private val store = SecureStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signIn: Job? = null
    private var syncing = false

    private var session: String?
        get() = store.get(KEY)
        set(value) = store.put(KEY, value)

    fun install() {
        ProtonAuthenticator.backend = this
        ProtonAuthenticator.setStatus(if (session == null) ProtonAuthenticator.Status.Disconnected else ProtonAuthenticator.Status.Syncing, null)
    }

    override fun connect() {
        signIn?.cancel()
        ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.SigningIn, null)
        signIn = scope.launch {
            try {
                val start = withContext(Dispatchers.IO) { protonSignInStart() }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // Proton keeps the sign-in open for about ten minutes.
                repeat(150) {
                    delay(4_000)
                    val s = withContext(Dispatchers.IO) { protonSignInPoll(start.state) }
                    if (s != null) {
                        session = s
                        refresh()
                        return@launch
                    }
                }
                ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Disconnected, "Die Anmeldung bei Proton ist abgelaufen.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Failed, e.message)
            }
        }
    }

    override fun cancelConnect() {
        signIn?.cancel()
        signIn = null
        ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Disconnected, null)
    }

    override fun refresh() {
        val current = session ?: return
        if (syncing || !VaultSession.isUnlocked) return
        syncing = true
        if (ProtonAuthenticator.entries.isEmpty()) ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Syncing, null)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { protonSync(current) }
                session = result.session
                ProtonAuthenticator.publish(result.entries.map { AuthEntry(it.id, it.issuer, it.name, it.uri, it.period.toInt()) })
            } catch (e: ProtonFfiException.Expired) {
                session = null
                ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Disconnected, e.message)
            } catch (e: Exception) {
                ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Failed, e.message)
            } finally {
                syncing = false
            }
        }
    }

    override fun disconnect() {
        session?.let { s -> scope.launch(Dispatchers.IO) { protonSignOut(s) } }
        session = null
        ProtonAuthenticator.entries.clear()
        ProtonAuthenticator.setStatus(ProtonAuthenticator.Status.Disconnected, null)
    }

    override fun code(uri: String, epochSeconds: Long): String? = protonCode(uri, epochSeconds.coerceAtLeast(0).toULong())

    companion object {
        private const val KEY = "protonSession"
    }
}
