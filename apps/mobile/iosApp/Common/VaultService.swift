import BitwardenSdk
import Foundation
import Shared
import UIKit
import Network
import UniformTypeIdentifiers

/// Connects the app (and the AutoFill extension) to Vaultwarden.
///
/// Networking: `VaultwardenAPI` (device certificate). Cryptography: only the official
/// Bitwarden SDK — this class never derives keys or decrypts anything itself.
///
/// Nothing of the vault is stored on the device, not even encrypted: unlocking always loads it
/// fresh from Vaultwarden into memory (Kotlin `VaultSession`), and it is removed again on lock,
/// when the connection is lost, and when the app has been in the background for the chosen time
/// (at the latest before iOS suspends it).
@MainActor
final class VaultService: NSObject, ObservableObject {
    enum Phase: Equatable {
        case needsCertificate
        case signedOut
        case locked
        case unlocked
    }

    static let shared = VaultService()

    @Published private(set) var phase: Phase = .signedOut
    @Published private(set) var busy = false
    @Published var errorMessage: String?
    /// Two-factor providers the server asked for during login (0 = authenticator app, 1 = e-mail).
    @Published private(set) var twoFactorProviders: [Int] = []

    let api = VaultwardenAPI.shared
    private(set) var client: Client?
    private(set) var encrypted: [String: BitwardenSdk.Cipher] = [:]
    private(set) var decrypted: [String: CipherView] = [:]
    private var pendingLogin: (email: String, hash: String, password: String, kdf: Kdf)?
    private var backgroundedAt: Date?
    private var backgroundLock: Task<Void, Never>?
    private var endBackgroundTime: (() -> Void)?

    /// Asks iOS for time in the background and calls the handler just before it runs out; returns
    /// a function that gives the time back. Set by the app (extensions have no UIApplication).
    var requestBackgroundTime: ((@escaping () -> Void) -> () -> Void)?
    private let connection = NWPathMonitor()
    private var offlineLock: Task<Void, Never>?

    override private init() {
        super.init()
        VaultSession.shared.onLock { [weak self] in
            Task { @MainActor in self?.wipe() }
        }
        // Older versions kept an encrypted copy of the vault on disk; remove it.
        VaultCache.removeLeftover()
        watchConnection()
        refreshPhase()
    }

    var account: StoredAccount? { SecureStore.getCodable(StoredAccount.self, .account) }
    var canUseBiometrics: Bool { SecureStore.hasUserKey }

    /// User preference; when off, the vault key is never stored behind Face ID.
    private var biometricsWanted: Bool {
        get { UserDefaults(suiteName: M3BConfig.appGroup)?.object(forKey: "biometricUnlock") as? Bool ?? true }
        set { UserDefaults(suiteName: M3BConfig.appGroup)?.set(newValue, forKey: "biometricUnlock") }
    }

    private var writeQueue: Task<Void, Never>?
    /// Local folder id -> server id, for folders created in this session.
    private var folderIds: [String: String] = [:]

    func refreshPhase() {
        if !DeviceCertificate.isInstalled {
            phase = .needsCertificate
        } else if let account {
            if phase != .unlocked { phase = .locked }
            VaultSession.shared.connect(backend: self, email: account.email, server: M3BConfig.serverURL.host ?? "")
        } else {
            phase = .signedOut
        }
    }

    // MARK: Login

    func login(email rawEmail: String, password: String, twoFactorCode: String? = nil, provider: Int? = nil) async {
        await run {
            let email = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let client = self.makeClient()
            var pending = self.pendingLogin
            if pending == nil || pending?.email != email || pending?.password != password {
                let prelogin = try await self.api.prelogin(email: email)
                let kdf = Kdf(model: KdfModel(kdfType: prelogin.kdf, iterations: prelogin.kdfIterations, memory: prelogin.kdfMemory, parallelism: prelogin.kdfParallelism))
                let hash = try await client.auth().hashPassword(email: email, password: password, kdfParams: kdf, purpose: .serverAuthorization)
                pending = (email, hash, password, kdf)
                self.pendingLogin = pending
            }
            guard let pending else { return }

            let token: TokenResponse
            do {
                token = try await self.api.login(
                    email: email,
                    passwordHash: pending.hash,
                    deviceIdentifier: Self.deviceIdentifier,
                    deviceName: "\(UIDevice.current.model) · M3 Bitwarden",
                    twoFactor: twoFactorCode.map { code in
                        (provider: provider ?? self.twoFactorProviders.first ?? 0, code: code.filter { !$0.isWhitespace })
                    }
                )
            } catch let VaultwardenAPI.Failure.twoFactorRequired(providers) {
                self.twoFactorProviders = providers.isEmpty ? [0] : providers
                if providers == [1] { try? await self.api.sendEmailCode(email: email, passwordHash: pending.hash) }
                return
            }

            guard let key = token.userDecryptionOptions?.masterPasswordUnlock?.masterKeyEncryptedUserKey ?? token.key,
                  let privateKey = token.privateKey
            else { throw VaultwardenAPI.Failure.server(200, "Antwort ohne Schlüssel") }
            let kdfModel = token.userDecryptionOptions?.masterPasswordUnlock?.kdf
                ?? KdfModel(kdfType: token.kdf ?? 0, iterations: token.kdfIterations ?? 600_000, memory: token.kdfMemory, parallelism: token.kdfParallelism)

            try TokenStore.save(token)
            let account = StoredAccount(
                userId: Self.subject(of: token.accessToken) ?? "",
                email: token.userDecryptionOptions?.masterPasswordUnlock?.salt ?? email,
                kdf: kdfModel,
                masterKeyWrappedUserKey: key,
                privateKey: privateKey,
                deviceIdentifier: Self.deviceIdentifier
            )
            try SecureStore.setCodable(account, for: .account)
            self.twoFactorProviders = []
            self.pendingLogin = nil
            VaultSession.shared.connect(backend: self, email: account.email, server: M3BConfig.serverURL.host ?? "")
            try await self.unlock(client: client, account: account, method: .masterPasswordUnlock(
                password: password,
                masterPasswordUnlock: MasterPasswordUnlockData(kdf: Kdf(model: kdfModel), masterKeyWrappedUserKey: key, salt: account.email)
            ))
        }
    }

    func cancelTwoFactor() {
        twoFactorProviders = []
        pendingLogin = nil
    }

    // MARK: Unlock / lock

    func unlock(password: String) async {
        await run {
            guard let account = self.account else { return self.refreshPhase() }
            try await self.unlock(client: self.makeClient(), account: account, method: .masterPasswordUnlock(
                password: password,
                masterPasswordUnlock: MasterPasswordUnlockData(kdf: Kdf(model: account.kdf), masterKeyWrappedUserKey: account.masterKeyWrappedUserKey, salt: account.email)
            ))
        }
    }

    /// Face ID / Touch ID. [loadTimeout] limits how long the extension waits for the vault.
    @discardableResult
    func unlockWithBiometrics(loadTimeout: TimeInterval? = nil) async -> Bool {
        await run {
            guard let account = self.account,
                  let key = await SecureStore.userKey(reason: "Tresor entsperren"),
                  let userKey = String(data: key, encoding: .utf8)
            else { return }
            try await self.unlock(client: self.makeClient(), account: account, method: .decryptedKey(decryptedUserKey: userKey), loadTimeout: loadTimeout)
        }
        return phase == .unlocked
    }

    func lock() {
        VaultSession.shared.lock() // clears the Kotlin side and calls wipe() through the listener
        wipe() // right away too: iOS may suspend the app before the listener's task runs
    }

    func signOut() {
        lock()
        SecureStore.delete(.tokens)
        SecureStore.delete(.account)
        SecureStore.delete(.userKey)
        VaultCache.removeLeftover()
        Task { await CredentialIdentitySync.clear() }
        VaultSession.shared.disconnect()
        folderIds = [:]
        phase = .signedOut
    }

    /// Locks after the chosen time in the background, and at the latest when iOS is about to
    /// suspend the app (its memory would otherwise keep the vault until the next start).
    func appDidEnterBackground() {
        backgroundedAt = Date()
        guard phase == .unlocked else { return }
        let timeout = max(0, AppSettings.shared.timeoutSeconds)
        if timeout == 0 { return lock() }
        endBackgroundTime = requestBackgroundTime? { [weak self] in
            // iOS calls this on the main thread just before suspending the app.
            MainActor.assumeIsolated { self?.lockFromBackground() }
        }
        backgroundLock = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            self?.lockFromBackground()
        }
    }

    func appWillEnterForeground() {
        backgroundLock?.cancel()
        backgroundLock = nil
        endBackgroundTask()
        let timeout = AppSettings.shared.timeoutSeconds
        if let since = backgroundedAt, Date().timeIntervalSince(since) >= TimeInterval(max(0, timeout)) { lock() }
        backgroundedAt = nil
        if phase == .unlocked { Task { await syncQuietly() } }
    }

    private func lockFromBackground() {
        lock()
        endBackgroundTask()
    }

    private func endBackgroundTask() {
        endBackgroundTime?()
        endBackgroundTime = nil
    }

    /// Without a connection to Vaultwarden the vault is not shown: losing the network for more
    /// than a few seconds (a handover between Wi-Fi and mobile data is shorter) locks it.
    private func watchConnection() {
        connection.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.connectionChanged(online: online) }
        }
        connection.start(queue: DispatchQueue(label: "m3b.connection"))
    }

    private func connectionChanged(online: Bool) {
        offlineLock?.cancel()
        offlineLock = nil
        guard !online, phase == .unlocked else { return }
        offlineLock = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled, let self, self.phase == .unlocked else { return }
            self.lockForLostConnection()
        }
    }

    private func lockForLostConnection() {
        lock()
        errorMessage = Self.offlineMessage
    }

    static let offlineMessage = "Keine Verbindung zu Vaultwarden. Der Tresor wird nur mit Verbindung geöffnet und nie auf dem Gerät gespeichert."

    /// Errors that mean "no connection to the server" (as opposed to the server refusing).
    static func isConnectionError(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let failure = error as? VaultwardenAPI.Failure {
            switch failure {
            case .blockedByCloudflare, .noCertificate: return true
            default: return false
            }
        }
        let code = (error as? URLError)?.code ?? URLError.Code(rawValue: (error as NSError).domain == NSURLErrorDomain ? (error as NSError).code : 0)
        return [
            .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost, .timedOut,
            .dnsLookupFailed, .secureConnectionFailed, .internationalRoamingOff, .dataNotAllowed,
            .clientCertificateRejected, .clientCertificateRequired,
        ].contains(code)
    }

    private func wipe() {
        client = nil
        encrypted = [:]
        decrypted = [:]
        if phase == .unlocked { phase = .locked }
    }

    private func unlock(client: Client, account: StoredAccount, method: InitUserCryptoMethod, loadTimeout: TimeInterval? = nil) async throws {
        try await client.crypto().initializeUserCrypto(req: InitUserCryptoRequest(
            userId: account.userId.isEmpty ? nil : account.userId,
            kdfParams: Kdf(model: account.kdf),
            email: account.email,
            accountCryptographicState: .v1(privateKey: account.privateKey),
            method: method,
            upgradeToken: nil
        ))
        self.client = client
        // Keep the user key behind Face ID so the next unlock (and the extension) skip the password.
        if case .masterPasswordUnlock = method, biometricsWanted {
            let userKey = try await client.crypto().getUserEncryptionKey()
            try? SecureStore.set(Data(userKey.utf8), for: .userKey)
        }
        // The vault is only opened with fresh data from the server; nothing is read from the device.
        do {
            if let loadTimeout {
                try await withTimeout(loadTimeout) { try await self.sync() }
            } else {
                try await sync()
            }
        } catch {
            wipe()
            throw Self.isConnectionError(error) ? OfflineFailure() : error
        }
        VaultSession.shared.markUnlocked()
        phase = .unlocked
        // The sync above ran before the vault counted as open, so publish the identities now.
        await CredentialIdentitySync.run()
    }

    private struct OfflineFailure: LocalizedError {
        var errorDescription: String? { VaultService.offlineMessage }
    }

    private func withTimeout(_ seconds: TimeInterval, _ work: @escaping @MainActor () async throws -> Void) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { try await work() }
            group.addTask { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)); throw CancellationError() }
            try await group.next()
            group.cancelAll()
        }
    }

    // MARK: Sync

    func sync() async throws {
        VaultSession.shared.syncing = true
        defer { VaultSession.shared.syncing = false }
        let data = try await api.sync()
        try await publish(syncData: data)
        VaultSession.shared.syncStatus = "Aktualisiert " + Date().formatted(date: .omitted, time: .shortened)
        await CredentialIdentitySync.run()
    }

    /// Refreshes the open vault; if Vaultwarden cannot be reached, the vault is locked.
    func syncQuietly() async {
        do {
            try await sync()
        } catch {
            guard phase == .unlocked else { return }
            if Self.isConnectionError(error) {
                lockForLostConnection()
            } else {
                VaultSession.shared.syncStatus = "Nicht aktualisiert"
                if (error as? VaultwardenAPI.Failure) != nil { VaultSession.shared.message = error.localizedDescription }
            }
        }
    }

    /// Decrypts a sync response with the SDK and hands the items to the Kotlin UI.
    private func publish(syncData: Data) async throws {
        guard let client else { return }
        let response = try JSON.decoder.decode(SyncResponse.self, from: syncData)

        let orgKeys = (response.profile.organizations ?? []).reduce(into: [OrganizationId: UnsignedSharedKey]()) { keys, org in
            if let key = org.key { keys[org.id] = key }
        }
        if !orgKeys.isEmpty {
            try await client.crypto().initializeOrgCrypto(req: InitOrgCryptoRequest(organizationKeys: orgKeys))
        }

        let folders = (try? client.vault().folders().decryptList(folders: (response.folders ?? []).map {
            BitwardenSdk.Folder(id: $0.id, name: $0.name, revisionDate: $0.revisionDate)
        })) ?? []
        let folderNames = Dictionary(folders.compactMap { f in f.id.map { ($0, f.name) } }, uniquingKeysWith: { a, _ in a })

        let ciphers = response.ciphers.filter { $0.deletedDate == nil }.map(BitwardenSdk.Cipher.init(model:))
        let result = try await client.vault().ciphers().decryptListFullWithFailures(ciphers: ciphers)
        encrypted = Dictionary(ciphers.compactMap { c in c.id.map { ($0, c) } }, uniquingKeysWith: { a, _ in a })
        decrypted = Dictionary(result.successes.compactMap { v in v.id.map { ($0, v) } }, uniquingKeysWith: { a, _ in a })

        var groups = (response.domains?.equivalentDomains ?? []).map { Set($0.map { $0.lowercased() }) }
        groups += (response.domains?.globalEquivalentDomains ?? []).filter { !$0.excluded }.map { Set($0.domains.map { $0.lowercased() }) }
        CredentialProviderBridge.shared.equivalentDomains = groups
        let passkeysById = Dictionary(grouping: passkeys(), by: \.cipherId)

        VaultSession.shared.loadLive(
            items: result.successes.compactMap { $0.kotlinItem(folders: folderNames, passkeys: passkeysById[$0.id ?? ""] ?? []) },
            folders: folders.compactMap { f in f.id.map { Shared.Folder(id: $0, name: f.name) } }
        )
        if !result.failures.isEmpty {
            VaultSession.shared.syncStatus = "\(result.failures.count) Einträge nicht lesbar"
        }
    }

    // MARK: Helpers

    private func makeClient() -> Client {
        Client(
            tokenProvider: SdkTokenProvider(),
            settings: ClientSettings(
                identityUrl: M3BConfig.identityURL.absoluteString,
                apiUrl: M3BConfig.apiURL.absoluteString,
                userAgent: "M3Bitwarden/\(M3BConfig.clientVersion) (iOS)",
                deviceType: .iOs,
                deviceIdentifier: Self.deviceIdentifier,
                bitwardenClientVersion: M3BConfig.clientVersion,
                bitwardenPackageType: nil
            ),
            managedSettings: ManagedSettingsBindingClient()
        )
    }

    /// Runs uploads one after another, so a new folder exists before an item is moved into it.
    private func enqueue(_ work: @escaping @MainActor () async -> Void) {
        let previous = writeQueue
        writeQueue = Task { @MainActor in
            await previous?.value
            await work()
        }
    }

    private func run(_ work: @escaping () async throws -> Void) async {
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            try await work()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    static var deviceIdentifier: String {
        let defaults = UserDefaults(suiteName: M3BConfig.appGroup)
        if let id = defaults?.string(forKey: "deviceIdentifier") { return id }
        let id = UUID().uuidString.lowercased()
        defaults?.set(id, forKey: "deviceIdentifier")
        return id
    }

    /// The user id ("sub") from the access token.
    private static func subject(of jwt: String) -> String? {
        let parts = jwt.split(separator: ".")
        guard parts.count > 1 else { return nil }
        var b64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return claims["sub"] as? String
    }
}

// MARK: - Kotlin backend

extension VaultService: VaultBackend {
    /// Same as Kotlin's `VaultRepository.LOCAL_PREFIX`: ids not yet known to the server.
    nonisolated static let localPrefix = "local-"

    nonisolated func itemSaved(item: VaultItem, isNew: Bool) {
        Task { @MainActor in self.enqueue { await self.write(item: item, isNew: isNew) } }
    }

    nonisolated func itemDeleted(id: String) {
        Task { @MainActor in
            self.enqueue {
                let serverId = id.hasPrefix(Self.localPrefix) ? VaultSession.shared.serverIdFor(localId: id) : id
                guard let serverId else { return }
                do {
                    try await self.api.softDeleteCipher(id: serverId)
                    try await self.sync()
                } catch {
                    VaultSession.shared.message = "Löschen fehlgeschlagen: \(error.localizedDescription)"
                    await self.syncQuietly()
                }
            }
        }
    }

    nonisolated func folderCreated(folder: Shared.Folder) {
        Task { @MainActor in
            self.enqueue {
                guard let client = self.client else { return }
                do {
                    let encrypted = try client.vault().folders().encrypt(folder: FolderView(id: nil, name: folder.name, revisionDate: Date()))
                    let created = try await self.api.createFolder(name: encrypted.name)
                    self.folderIds[folder.id] = created.id
                    VaultSession.shared.registerServerId(localId: folder.id, serverId: created.id)
                } catch {
                    VaultSession.shared.message = "Ordner konnte nicht angelegt werden: \(error.localizedDescription)"
                }
            }
        }
    }

    nonisolated func totpCode(secret: String, epochSeconds: Int64) -> String? {
        MainActor.assumeIsolated {
            try? client?.vault().generateTotp(key: secret, time: Date(timeIntervalSince1970: TimeInterval(epochSeconds))).code
        }
    }

    nonisolated func refresh() {
        Task { @MainActor in await self.syncQuietly() }
    }

    nonisolated func toClipboard(text: String, sensitive: Bool) {
        let seconds = AppSettings.shared.clearClipboardSeconds
        var options: [UIPasteboard.OptionsKey: Any] = [:]
        if sensitive {
            options[.localOnly] = true
            if seconds > 0 { options[.expirationDate] = Date().addingTimeInterval(TimeInterval(seconds)) }
        }
        UIPasteboard.general.setItems([[UTType.plainText.identifier: text]], options: options)
    }

    nonisolated func setBiometricUnlock(enabled: Bool) {
        Task { @MainActor in
            self.biometricsWanted = enabled
            if enabled {
                guard let key = try? await self.client?.crypto().getUserEncryptionKey() else { return }
                try? SecureStore.set(Data(key.utf8), for: .userKey)
            } else {
                SecureStore.delete(.userKey)
            }
        }
    }

    nonisolated func biometricUnlockEnabled() -> Bool {
        MainActor.assumeIsolated { biometricsWanted && canUseBiometrics }
    }

    nonisolated func logout() {
        Task { @MainActor in self.signOut() }
    }

    /// Server id for a folder chosen in the UI (folders created offline get theirs on upload).
    private func serverFolderId(_ id: String?) -> String? {
        guard let id else { return nil }
        if id.hasPrefix(Self.localPrefix) { return folderIds[id] ?? VaultSession.shared.serverIdFor(localId: id) }
        return id
    }

    /// Encrypts the change with the SDK and uploads it; the next sync replaces the optimistic copy.
    private func write(item: VaultItem, isNew: Bool) async {
        guard let client else { return }
        do {
            let localId = item.id.hasPrefix(Self.localPrefix) ? item.id : nil
            let serverId = localId.flatMap { VaultSession.shared.serverIdFor(localId: $0) } ?? (localId == nil ? item.id : nil)
            let view: CipherView
            if let serverId, let original = decrypted[serverId] {
                view = original.applying(item, folderId: serverFolderId(item.folderId))
            } else {
                view = .new(from: item, folderId: serverFolderId(item.folderId))
            }
            let context = try await client.vault().ciphers().encrypt(cipherView: view)
            let body = CipherRequestModel(context: context)
            if let serverId, decrypted[serverId] != nil {
                _ = try await api.updateCipher(id: serverId, body)
            } else {
                let created = try await api.createCipher(body)
                if let localId { VaultSession.shared.registerServerId(localId: localId, serverId: created.id) }
            }
            try await sync()
        } catch {
            VaultSession.shared.message = "Speichern fehlgeschlagen: \(error.localizedDescription)"
            await syncQuietly()
        }
    }
}

/// Lets the SDK fetch a fresh access token when it makes its own requests.
final class SdkTokenProvider: ClientManagedTokens, @unchecked Sendable {
    func getAccessToken() async -> String? {
        try? await TokenStore.validAccessToken()
    }
}
