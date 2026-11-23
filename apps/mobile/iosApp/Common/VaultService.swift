import BitwardenSdk
import Foundation
import Shared
import UIKit
import UniformTypeIdentifiers

/// Connects the app (and the AutoFill extension) to Vaultwarden.
///
/// Networking: `VaultwardenAPI` (device certificate). Cryptography: only the official
/// Bitwarden SDK — this class never derives keys or decrypts anything itself. Decrypted items
/// live in memory (Kotlin `VaultSession`) while unlocked and are wiped on lock.
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

    private let api = VaultwardenAPI.shared
    private var client: Client?
    private var encrypted: [String: BitwardenSdk.Cipher] = [:]
    private var decrypted: [String: CipherView] = [:]
    private var pendingLogin: (email: String, hash: String, password: String, kdf: Kdf)?
    private var backgroundedAt: Date?

    override private init() {
        super.init()
        VaultSession.shared.onLock { [weak self] in
            Task { @MainActor in self?.wipe() }
        }
        refreshPhase()
    }

    var account: StoredAccount? { SecureStore.getCodable(StoredAccount.self, .account) }
    var canUseBiometrics: Bool { SecureStore.hasUserKey }

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
            try await self.sync()
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
            await self.syncQuietly()
        }
    }

    /// Face ID / Touch ID. [syncTimeout] limits how long the extension waits for fresh data.
    @discardableResult
    func unlockWithBiometrics(syncTimeout: TimeInterval? = nil) async -> Bool {
        await run {
            guard let account = self.account,
                  let key = SecureStore.get(.userKey, reason: "Tresor entsperren"),
                  let userKey = String(data: key, encoding: .utf8)
            else { return }
            try await self.unlock(client: self.makeClient(), account: account, method: .decryptedKey(decryptedUserKey: userKey))
            if let syncTimeout {
                await self.syncQuietly(timeout: syncTimeout)
            } else {
                Task { await self.syncQuietly() }
            }
        }
        return phase == .unlocked
    }

    func lock() {
        VaultSession.shared.lock() // triggers wipe() through the listener
    }

    func logout() {
        lock()
        SecureStore.delete(.tokens)
        SecureStore.delete(.account)
        SecureStore.delete(.userKey)
        VaultCache.clear()
        Task { await CredentialIdentitySync.clear() }
        phase = .signedOut
    }

    func appDidEnterBackground() { backgroundedAt = Date() }

    func appWillEnterForeground() {
        if let since = backgroundedAt, Date().timeIntervalSince(since) > M3BConfig.autoLockSeconds { lock() }
        backgroundedAt = nil
        if phase == .unlocked { Task { await syncQuietly() } }
    }

    private func wipe() {
        client = nil
        encrypted = [:]
        decrypted = [:]
        if phase == .unlocked { phase = .locked }
    }

    private func unlock(client: Client, account: StoredAccount, method: InitUserCryptoMethod) async throws {
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
        if case .masterPasswordUnlock = method {
            let userKey = try await client.crypto().getUserEncryptionKey()
            try? SecureStore.set(Data(userKey.utf8), for: .userKey)
        }
        if let cached = VaultCache.load() { try await publish(syncData: cached) }
        VaultSession.shared.markUnlocked()
        phase = .unlocked
    }

    // MARK: Sync

    func sync() async throws {
        let data = try await api.sync()
        try VaultCache.save(data)
        try await publish(syncData: data)
        VaultSession.shared.syncStatus = "Aktualisiert " + Date().formatted(date: .omitted, time: .shortened)
        await CredentialIdentitySync.run()
    }

    func syncQuietly(timeout: TimeInterval? = nil) async {
        do {
            if let timeout {
                try await withThrowingTaskGroup(of: Void.self) { group in
                    group.addTask { try await self.sync() }
                    group.addTask { try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000)); throw CancellationError() }
                    try await group.next()
                    group.cancelAll()
                }
            } else {
                try await sync()
            }
        } catch is CancellationError {
            VaultSession.shared.syncStatus = "Offline-Daten"
        } catch {
            VaultSession.shared.syncStatus = "Nicht aktualisiert"
            if phase == .unlocked, (error as? VaultwardenAPI.Failure) != nil { errorMessage = error.localizedDescription }
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
            Folder(id: $0.id, name: $0.name, revisionDate: $0.revisionDate)
        })) ?? []
        let folderNames = Dictionary(folders.compactMap { f in f.id.map { ($0, f.name) } }, uniquingKeysWith: { a, _ in a })

        let ciphers = response.ciphers.filter { $0.deletedDate == nil }.map(BitwardenSdk.Cipher.init(model:))
        let result = try await client.vault().ciphers().decryptListFullWithFailures(ciphers: ciphers)
        encrypted = Dictionary(ciphers.compactMap { c in c.id.map { ($0, c) } }, uniquingKeysWith: { a, _ in a })
        decrypted = Dictionary(result.successes.compactMap { v in v.id.map { ($0, v) } }, uniquingKeysWith: { a, _ in a })

        var groups = (response.domains?.equivalentDomains ?? []).map { Set($0.map { $0.lowercased() }) }
        groups += (response.domains?.globalEquivalentDomains ?? []).filter { !$0.excluded }.map { Set($0.domains.map { $0.lowercased() }) }
        CredentialProviderBridge.shared.equivalentDomains = groups

        VaultSession.shared.loadLive(items: result.successes.compactMap { $0.kotlinItem(folders: folderNames) })
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
        Task { @MainActor in await self.write(item: item, isNew: isNew) }
    }

    nonisolated func itemDeleted(id: String) {
        Task { @MainActor in
            guard !id.hasPrefix(Self.localPrefix) else { return }
            do {
                try await self.api.softDeleteCipher(id: id)
                try await self.sync()
            } catch {
                self.errorMessage = "Löschen fehlgeschlagen: \(error.localizedDescription)"
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
        let options: [UIPasteboard.OptionsKey: Any] = sensitive
            ? [.localOnly: true, .expirationDate: Date().addingTimeInterval(90)]
            : [:]
        UIPasteboard.general.setItems([[UTType.plainText.identifier: text]], options: options)
    }

    /// Encrypts the change with the SDK and uploads it; the next sync replaces the optimistic copy.
    private func write(item: VaultItem, isNew: Bool) async {
        guard let client else { return }
        do {
            let creating = isNew || item.id.hasPrefix(Self.localPrefix)
            let view: CipherView
            if creating {
                guard item.type == .login else { return }
                view = .newLogin(from: item)
            } else {
                guard let original = decrypted[item.id] else { return }
                view = original.applying(item)
            }
            let context = try await client.vault().ciphers().encrypt(cipherView: view)
            let body = CipherRequestModel(cipher: context.cipher)
            if creating {
                _ = try await api.createCipher(body)
            } else {
                _ = try await api.updateCipher(id: item.id, body)
            }
            try await sync()
        } catch {
            errorMessage = "Speichern fehlgeschlagen: \(error.localizedDescription)"
        }
    }
}

/// Lets the SDK fetch a fresh access token when it makes its own requests.
final class SdkTokenProvider: ClientManagedTokens, @unchecked Sendable {
    func getAccessToken() async -> String? {
        try? await TokenStore.validAccessToken()
    }
}
