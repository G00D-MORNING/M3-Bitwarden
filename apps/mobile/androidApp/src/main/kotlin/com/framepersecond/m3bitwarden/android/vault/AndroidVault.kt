package com.framepersecond.m3bitwarden.android.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bitwarden.core.ClientManagedTokens
import com.bitwarden.core.ClientSettings
import com.bitwarden.core.DeviceType
import com.bitwarden.core.InitOrgCryptoRequest
import com.bitwarden.core.InitUserCryptoMethod
import com.bitwarden.core.InitUserCryptoRequest
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.sdk.Client
import com.bitwarden.sdk.ManagedSettingsBindingClient
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.EncryptionContext
import com.framepersecond.m3bitwarden.android.passkey.PasskeyEngine
import com.framepersecond.m3bitwarden.android.passkey.PasskeyException
import com.bitwarden.vault.FolderView
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.data.Folder
import com.framepersecond.m3bitwarden.data.VaultBackend
import com.framepersecond.m3bitwarden.data.VaultItem
import com.framepersecond.m3bitwarden.data.VaultRepository
import com.framepersecond.m3bitwarden.data.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Connects the Android app and its autofill service to Vaultwarden.
 *
 * Networking: [VaultwardenApi] (device certificate). Cryptography: only the official Bitwarden
 * SDK — this class never derives keys or decrypts anything itself. Decrypted items live in
 * memory ([VaultSession]) while unlocked and are wiped on lock.
 */
class AndroidVault private constructor(private val context: Context) : VaultBackend {

    enum class Phase { NeedsCertificate, SignedOut, Locked, Unlocked }

    var phase by mutableStateOf(Phase.SignedOut)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Two-factor providers the server asked for during login (0 = authenticator app, 1 = e-mail). */
    var twoFactorProviders by mutableStateOf<List<Int>>(emptyList())
        private set

    val certificate = DeviceCertificate(context)
    private val store = SecureStore(context)
    private val api = VaultwardenApi(certificate, store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private var client: Client? = null
    private var decrypted: Map<String, CipherView> = emptyMap()
    private var pendingLogin: PendingLogin? = null
    private val folderIds = mutableMapOf<String, String>()
    private var writeQueue: Job? = null

    private data class PendingLogin(val email: String, val password: String, val hash: String)

    private val prefs = context.getSharedPreferences("m3b_vault", Context.MODE_PRIVATE)

    init {
        VaultSession.onLock { wipe() }
        refreshPhase()
    }

    // MARK: Account

    private data class Account(
        val userId: String,
        val email: String,
        val kdfType: Int,
        val iterations: Int,
        val memory: Int?,
        val parallelism: Int?,
        val masterKeyWrappedUserKey: String,
        val privateKey: String,
    ) {
        val kdf: Kdf
            get() = if (kdfType == 1) {
                Kdf.Argon2id(iterations.toUInt(), (memory ?: 64).toUInt(), (parallelism ?: 4).toUInt())
            } else {
                Kdf.Pbkdf2(iterations.toUInt())
            }

        fun json(): String = JSONObject().put("userId", userId).put("email", email).put("kdfType", kdfType).put("iterations", iterations)
            .putOpt("memory", memory).putOpt("parallelism", parallelism).put("key", masterKeyWrappedUserKey).put("privateKey", privateKey).toString()

        companion object {
            fun parse(text: String): Account? = runCatching {
                val j = JSONObject(text)
                Account(
                    j.getString("userId"), j.getString("email"), j.getInt("kdfType"), j.getInt("iterations"),
                    j.int("memory"), j.int("parallelism"), j.getString("key"), j.getString("privateKey"),
                )
            }.getOrNull()
        }
    }

    private val account: Account? get() = store.get(ACCOUNT)?.let(Account::parse)

    val accountEmail: String get() = account?.email.orEmpty()

    val canUseBiometrics: Boolean get() = biometricsWanted && store.hasBiometricKey && biometricsAvailable()

    private var biometricsWanted: Boolean
        get() = prefs.getBoolean("biometricUnlock", true)
        set(value) = prefs.edit().putBoolean("biometricUnlock", value).apply()

    private val deviceIdentifier: String
        get() = prefs.getString("deviceIdentifier", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceIdentifier", it).apply()
        }

    fun refreshPhase() {
        val acc = account
        phase = when {
            !certificate.isInstalled -> Phase.NeedsCertificate
            acc == null -> Phase.SignedOut
            phase == Phase.Unlocked && VaultSession.isUnlocked -> Phase.Unlocked
            else -> Phase.Locked
        }
        if (acc != null) VaultSession.connect(this, acc.email, M3BConfig.serverHost)
    }

    // MARK: Login

    fun login(rawEmail: String, password: String, twoFactorCode: String? = null) = launchTask {
        val email = rawEmail.trim().lowercase()
        val client = makeClient()
        var pending = pendingLogin
        if (pending == null || pending.email != email || pending.password != password) {
            val pre = api.prelogin(email)
            val kdf = if (pre.kdf == 1) {
                Kdf.Argon2id(pre.iterations.toUInt(), (pre.memory ?: 64).toUInt(), (pre.parallelism ?: 4).toUInt())
            } else {
                Kdf.Pbkdf2(pre.iterations.toUInt())
            }
            val hash = client.auth().hashPassword(email, password, kdf, HashPurpose.SERVER_AUTHORIZATION)
            pending = PendingLogin(email, password, hash)
            pendingLogin = pending
        }

        val provider = if (twoFactorProviders.contains(0)) 0 else twoFactorProviders.firstOrNull() ?: 0
        val token = try {
            api.login(
                email = email,
                passwordHash = pending.hash,
                deviceIdentifier = deviceIdentifier,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL} · M3 Bitwarden",
                twoFactor = twoFactorCode?.let { provider to it.filterNot(Char::isWhitespace) },
            )
        } catch (e: VaultwardenApi.Failure.TwoFactorRequired) {
            twoFactorProviders = e.providers.ifEmpty { listOf(0) }
            if (e.providers == listOf(1)) runCatching { api.sendEmailCode(email, pending.hash) }
            return@launchTask
        }

        val mpUnlock = token.obj("UserDecryptionOptions")?.obj("MasterPasswordUnlock")
        val key = mpUnlock?.str("MasterKeyEncryptedUserKey") ?: token.str("Key")
        val privateKey = token.str("PrivateKey")
        if (key == null || privateKey == null) throw VaultwardenApi.Failure.Server(200, "Antwort ohne Schlüssel")
        val kdfJson = mpUnlock?.obj("Kdf")
        api.saveTokens(token)
        val acc = Account(
            userId = subject(token.str("access_token").orEmpty()).orEmpty(),
            email = mpUnlock?.str("Salt") ?: email,
            kdfType = kdfJson?.int("KdfType") ?: token.int("Kdf") ?: 0,
            iterations = kdfJson?.int("Iterations") ?: token.int("KdfIterations") ?: 600_000,
            memory = kdfJson?.int("Memory") ?: token.int("KdfMemory"),
            parallelism = kdfJson?.int("Parallelism") ?: token.int("KdfParallelism"),
            masterKeyWrappedUserKey = key,
            privateKey = privateKey,
        )
        store.put(ACCOUNT, acc.json())
        twoFactorProviders = emptyList()
        pendingLogin = null
        VaultSession.connect(this, acc.email, M3BConfig.serverHost)
        unlock(client, acc, InitUserCryptoMethod.MasterPasswordUnlock(password, MasterPasswordUnlockData(acc.kdf, acc.masterKeyWrappedUserKey, acc.email)))
        sync()
    }

    fun cancelTwoFactor() {
        twoFactorProviders = emptyList()
        pendingLogin = null
    }

    // MARK: Unlock / lock

    fun unlock(password: String) = launchTask {
        val acc = account ?: return@launchTask refreshPhase()
        unlock(makeClient(), acc, InitUserCryptoMethod.MasterPasswordUnlock(password, MasterPasswordUnlockData(acc.kdf, acc.masterKeyWrappedUserKey, acc.email)))
        syncQuietly()
    }

    /** Fingerprint / face unlock. [syncTimeoutMs] limits how long autofill waits for fresh data. */
    suspend fun unlockWithBiometrics(activityContext: Context, syncTimeoutMs: Long? = null): Boolean {
        val acc = account ?: return false
        val cipher = store.biometricCipher() ?: run {
            error = "Biometrisches Entsperren ist nicht mehr gültig. Bitte mit dem Master-Passwort entsperren."
            return false
        }
        val authenticated = suspendCancellableCoroutine<javax.crypto.Cipher?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            BiometricPrompt.Builder(activityContext)
                .setTitle("Tresor entsperren")
                .setSubtitle(acc.email)
                .setNegativeButton("Master-Passwort", activityContext.mainExecutor) { _, _ -> if (cont.isActive) cont.resume(null) }
                .build()
                .authenticate(
                    BiometricPrompt.CryptoObject(cipher),
                    signal,
                    activityContext.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
        } ?: return false
        val userKey = store.unwrapBiometricKey(authenticated) ?: return false
        var ok = false
        runSuspending {
            unlock(makeClient(), acc, InitUserCryptoMethod.DecryptedKey(userKey))
            ok = true
            if (syncTimeoutMs != null) {
                try {
                    withTimeout(syncTimeoutMs) { syncQuietly() }
                } catch (_: TimeoutCancellationException) {
                    VaultSession.syncStatus = "Offline-Daten"
                }
            } else {
                scope.launch { syncQuietly() }
            }
        }
        return ok
    }

    fun lock() = VaultSession.lock() // triggers wipe() through the listener

    fun signOut() {
        lock()
        api.clearTokens()
        store.clearAll()
        folderIds.clear()
        VaultSession.disconnect()
        phase = Phase.SignedOut
    }

    private fun wipe() {
        client?.close()
        client = null
        decrypted = emptyMap()
        if (phase == Phase.Unlocked) phase = Phase.Locked
    }

    private suspend fun unlock(client: Client, acc: Account, method: InitUserCryptoMethod) {
        client.crypto().initializeUserCrypto(
            InitUserCryptoRequest(
                userId = acc.userId.ifEmpty { null },
                kdfParams = acc.kdf,
                email = acc.email,
                accountCryptographicState = WrappedAccountCryptographicState.V1(acc.privateKey),
                method = method,
                upgradeToken = null,
            ),
        )
        this.client = client
        // Keep the user key behind biometrics so the next unlock (and autofill) skip the password.
        if (method is InitUserCryptoMethod.MasterPasswordUnlock && biometricsWanted && biometricsAvailable()) {
            runCatching { store.storeBiometricKey(client.crypto().getUserEncryptionKey()) }
        }
        store.loadCache()?.let { runCatching { publish(it) } }
        VaultSession.markUnlocked()
        phase = Phase.Unlocked
    }

    // MARK: Sync

    suspend fun sync() {
        VaultSession.syncing = true
        try {
            val data = api.sync()
            store.saveCache(data)
            publish(data)
            VaultSession.syncStatus = "Aktualisiert " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        } finally {
            VaultSession.syncing = false
        }
    }

    suspend fun syncQuietly() {
        try {
            sync()
        } catch (e: VaultwardenApi.Failure) {
            VaultSession.syncStatus = "Nicht aktualisiert"
            if (phase == Phase.Unlocked) VaultSession.message = e.message
        } catch (e: Exception) {
            VaultSession.syncStatus = "Nicht aktualisiert"
        }
    }

    /** Decrypts a sync response with the SDK and hands the items to the UI. */
    private suspend fun publish(syncJson: String) {
        val client = client ?: return
        val response = JSONObject(syncJson)
        val profile = response.obj("profile")
        val orgKeys = profile?.arr("organizations")?.objects().orEmpty().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val key = o.str("key") ?: return@mapNotNull null
            id to key
        }.toMap()
        if (orgKeys.isNotEmpty()) client.crypto().initializeOrgCrypto(InitOrgCryptoRequest(orgKeys))

        val folders = runCatching {
            client.vault().folders().decryptList(
                response.arr("folders")?.objects().orEmpty().mapNotNull { f ->
                    com.bitwarden.vault.Folder(f.str("id"), f.str("name") ?: return@mapNotNull null, f.date("revisionDate") ?: Instant.now())
                },
            )
        }.getOrDefault(emptyList())
        val folderNames = folders.mapNotNull { f -> f.id?.let { it to f.name } }.toMap()

        val ciphers = response.arr("ciphers")?.objects().orEmpty().filter { it.str("deletedDate") == null }.mapNotNull { runCatching { cipherFromJson(it) }.getOrNull() }
        val result = client.vault().ciphers().decryptListFullWithFailures(ciphers)
        decrypted = result.successes.mapNotNull { v -> v.id?.let { it to v } }.toMap()

        val groups = response.obj("domains")?.let { d ->
            val custom = d.arr("equivalentDomains")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { arr -> (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet() } }.orEmpty()
            val global = d.arr("globalEquivalentDomains")?.objects().orEmpty().filter { it.bool("excluded") != true }.mapNotNull { g ->
                g.arr("domains")?.let { arr -> (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet() }
            }
            custom + global
        }.orEmpty()
        equivalentDomains = groups

        val engine = passkeyEngine()
        VaultSession.loadLive(
            items = result.successes.mapNotNull { it.toItem(folderNames, engine?.details(it).orEmpty()) },
            folders = folders.mapNotNull { f -> f.id?.let { Folder(it, f.name) } },
        )
        if (result.failures.isNotEmpty()) VaultSession.syncStatus = "${result.failures.size} Einträge nicht lesbar"
    }

    // MARK: VaultBackend

    override fun itemSaved(item: VaultItem, isNew: Boolean) = enqueue { write(item) }

    override fun itemDeleted(id: String) = enqueue {
        val serverId = if (id.startsWith(VaultRepository.LOCAL_PREFIX)) VaultSession.serverIdFor(id) else id
        if (serverId == null) return@enqueue
        try {
            api.softDeleteCipher(serverId)
            sync()
        } catch (e: Exception) {
            VaultSession.message = "Löschen fehlgeschlagen: ${e.message}"
            syncQuietly()
        }
    }

    override fun folderCreated(folder: Folder) = enqueue {
        val client = client ?: return@enqueue
        try {
            val encrypted = client.vault().folders().encrypt(FolderView(null, folder.name, Instant.now()))
            val created = api.createFolder(encrypted.name)
            val id = created.str("id") ?: return@enqueue
            folderIds[folder.id] = id
            VaultSession.registerServerId(folder.id, id)
        } catch (e: Exception) {
            VaultSession.message = "Ordner konnte nicht angelegt werden: ${e.message}"
        }
    }

    override fun totpCode(secret: String, epochSeconds: Long): String? =
        runCatching { client?.vault()?.generateTotp(secret, Instant.ofEpochSecond(epochSeconds))?.code }.getOrNull()

    override fun refresh() {
        scope.launch { syncQuietly() }
    }

    private var lastCopy: String? = null

    override fun toClipboard(text: String, sensitive: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("M3 Bitwarden", text)
        if (sensitive) {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
        cm.setPrimaryClip(clip)
        lastCopy = text
        val seconds = AppSettings.clearClipboardSeconds
        if (sensitive && seconds > 0) {
            main.postDelayed({
                if (lastCopy == text) {
                    runCatching { cm.clearPrimaryClip() }
                    lastCopy = null
                }
            }, seconds * 1000L)
        }
    }

    override fun setBiometricUnlock(enabled: Boolean) {
        biometricsWanted = enabled
        if (!enabled) {
            store.deleteBiometricKey()
            return
        }
        scope.launch {
            val key = runCatching { client?.crypto()?.getUserEncryptionKey() }.getOrNull() ?: return@launch
            runCatching { store.storeBiometricKey(key) }
        }
    }

    override fun biometricUnlockEnabled(): Boolean = biometricsWanted && biometricsAvailable()

    override fun logout() = signOut()

    // MARK: Writes

    private fun serverFolderId(id: String?): String? = when {
        id == null -> null
        id.startsWith(VaultRepository.LOCAL_PREFIX) -> folderIds[id] ?: VaultSession.serverIdFor(id)
        else -> id
    }

    // MARK: Passkeys

    /** Passkeys of the open vault for Credential Manager; null while locked. */
    fun passkeyEngine(): PasskeyEngine? {
        val client = client ?: return null
        return PasskeyEngine(client, decrypted) { context -> uploadPasskey(context) }
    }

    /** Uploads a login with a new or changed passkey and returns its server id. */
    private suspend fun uploadPasskey(context: EncryptionContext): String {
        val body = context.cipher.toRequestJson()
        val id = context.cipher.id
        val saved = try {
            if (id != null) api.updateCipher(id, body) else api.createCipher(body)
        } catch (e: Exception) {
            throw PasskeyException("Der Passkey konnte nicht in Vaultwarden gespeichert werden: ${e.message}")
        }
        scope.launch { syncQuietly() }
        return saved.str("id") ?: id ?: throw PasskeyException("Vaultwarden hat keine Antwort geschickt.")
    }

    /** Encrypts the change with the SDK and uploads it; the next sync replaces the optimistic copy. */
    private suspend fun write(item: VaultItem) {
        val client = client ?: return
        try {
            val localId = item.id.takeIf { it.startsWith(VaultRepository.LOCAL_PREFIX) }
            val serverId = if (localId != null) VaultSession.serverIdFor(localId) else item.id
            val original = serverId?.let { decrypted[it] }
            val view = original?.applying(item, serverFolderId(item.folderId)) ?: newCipherView(item, serverFolderId(item.folderId))
            val context = client.vault().ciphers().encrypt(view)
            val body = context.cipher.toRequestJson()
            if (serverId != null && original != null) {
                api.updateCipher(serverId, body)
            } else {
                val created = api.createCipher(body)
                val id = created.str("id")
                if (localId != null && id != null) VaultSession.registerServerId(localId, id)
            }
            sync()
        } catch (e: Exception) {
            VaultSession.message = "Speichern fehlgeschlagen: ${e.message}"
            syncQuietly()
        }
    }

    /** Runs uploads one after another, so a new folder exists before an item is moved into it. */
    private fun enqueue(work: suspend () -> Unit) {
        val previous = writeQueue
        writeQueue = scope.launch {
            previous?.join()
            work()
        }
    }

    // MARK: Background lock

    private var lockRunnable: Runnable? = null

    fun onAppBackground() {
        val seconds = AppSettings.timeoutSeconds
        if (seconds < 0 || !VaultSession.isUnlocked) return
        if (seconds == 0L) return lock()
        lockRunnable = Runnable { lock() }.also { main.postDelayed(it, seconds * 1000) }
    }

    fun onAppForeground() {
        lockRunnable?.let(main::removeCallbacks)
        lockRunnable = null
        if (phase == Phase.Unlocked) scope.launch { syncQuietly() }
    }

    // MARK: Helpers

    var equivalentDomains: List<Set<String>> = emptyList()
        private set

    private fun makeClient(): Client = Client(
        object : ClientManagedTokens {
            override suspend fun getAccessToken(): String? = runCatching { api.validAccessToken() }.getOrNull()
        },
        ClientSettings(
            identityUrl = M3BConfig.IDENTITY_URL,
            apiUrl = M3BConfig.API_URL,
            userAgent = "M3Bitwarden/${M3BConfig.CLIENT_VERSION} (Android)",
            deviceType = DeviceType.ANDROID,
            deviceIdentifier = deviceIdentifier,
            bitwardenClientVersion = M3BConfig.CLIENT_VERSION,
            bitwardenPackageType = null,
        ),
        ManagedSettingsBindingClient(),
    )

    private fun biometricsAvailable(): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun launchTask(work: suspend () -> Unit) {
        scope.launch { runSuspending(work) }
    }

    private suspend fun runSuspending(work: suspend () -> Unit) {
        busy = true
        error = null
        try {
            work()
        } catch (e: VaultwardenApi.Failure) {
            error = e.message
        } catch (e: Exception) {
            error = when {
                e.javaClass.simpleName.contains("Crypto", true) || e.message.orEmpty().contains("decrypt", true) ->
                    "Entsperren fehlgeschlagen. Ist das Master-Passwort richtig?"
                else -> e.message ?: e.javaClass.simpleName
            }
        } finally {
            busy = false
        }
    }

    /** The user id ("sub") from the access token. */
    private fun subject(jwt: String): String? = runCatching {
        val part = jwt.split('.')[1]
        JSONObject(String(java.util.Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))).str("sub")
    }.getOrNull()

    companion object {
        private const val ACCOUNT = "account"

        @Volatile private var instance: AndroidVault? = null

        fun get(context: Context): AndroidVault =
            instance ?: synchronized(this) { instance ?: AndroidVault(context.applicationContext).also { instance = it } }
    }
}
