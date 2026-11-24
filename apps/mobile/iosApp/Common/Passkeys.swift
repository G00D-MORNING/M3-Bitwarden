import AuthenticationServices
import BitwardenSdk
import Foundation
import os

/*
 * Passkeys stored in Vaultwarden (the `fido2Credentials` of login items).
 *
 * All FIDO2 work — decrypting the private key, signing, building authenticator data and
 * attestation, encrypting a new passkey — is done by the Bitwarden SDK's authenticator, the same
 * code the official Bitwarden apps use. This file only hands the SDK the vault, reports what the
 * user chose and uploads the encrypted result.
 */

/// One passkey as the picker shows it (no secret in here).
struct PasskeyEntry: Identifiable, Hashable {
    let cipherId: String
    let credentialId: Data
    let rpId: String
    let userName: String
    let userHandle: Data
    let itemName: String
    var created: Date?

    var id: String { cipherId + ":" + credentialId.base64EncodedString() }
}

/// A login that can take a new passkey (Bitwarden keeps one passkey per login).
struct PasskeyTarget: Identifiable, Hashable {
    let cipherId: String
    let name: String
    let userName: String
    let host: String

    var id: String { cipherId }
}

/// What a website or app asks for, independent of the iOS request type.
struct PasskeyAssertionRequest {
    let rpId: String
    let clientDataHash: Data
    /// Credential ids the site accepts; empty means any passkey for [rpId].
    let allowedCredentials: [Data]
    let userVerification: Uv
}

struct PasskeyRegistrationRequest {
    let rpId: String
    let rpName: String?
    let userName: String
    let userDisplayName: String
    let userHandle: Data
    let clientDataHash: Data
    /// COSE algorithm ids the site accepts (-7 = ES256, the one Bitwarden supports).
    let algorithms: [Int64]
    let excludedCredentials: [Data]
    let userVerification: Uv
}

enum PasskeyFailure: LocalizedError {
    case locked
    case notFound
    case unsupportedAlgorithm
    case alreadyRegistered
    case upload(String)
    case sdk(String)

    var errorDescription: String? {
        switch self {
        case .locked: "Der Tresor ist gesperrt."
        case .notFound: "Dieser Passkey ist nicht mehr im Tresor."
        case .unsupportedAlgorithm: "Die Seite verlangt ein Schlüsselverfahren, das Bitwarden-Passkeys nicht unterstützen."
        case .alreadyRegistered: "Für dieses Konto ist schon ein Passkey im Tresor gespeichert."
        case let .upload(message): "Der Passkey konnte nicht in Vaultwarden gespeichert werden: \(message)"
        case let .sdk(message): message
        }
    }
}

extension Uv {
    init(_ preference: ASAuthorizationPublicKeyCredentialUserVerificationPreference) {
        switch preference {
        case .required: self = .required
        case .discouraged: self = .discouraged
        default: self = .preferred
        }
    }
}

/// The SDK's callbacks for one passkey request.
///
/// The user is always verified before a request gets here (Face ID or master password in the
/// AutoFill sheet), so presence and verification are reported as given.
private final class PasskeySession: Fido2UserInterface, Fido2CredentialStore, @unchecked Sendable {
    enum Purpose {
        /// Sign in; [cipherId] is the passkey the user tapped (nil: let the SDK take the only match).
        case assertion(cipherId: String?)
        /// Create a passkey in [target] (a new login or one without a passkey).
        case registration(target: CipherView)
    }

    private let purpose: Purpose
    private let ciphers: [CipherView]
    private let encrypted: [BitwardenSdk.Cipher]
    private let client: Client
    private let upload: (EncryptionContext) async throws -> String
    /// Server id of the login a new passkey was saved in.
    private(set) var savedCipherId: String?
    /// Why saving failed; the SDK only reports "vendor error 0xF1" for it.
    private(set) var saveError: Error?

    init(purpose: Purpose, ciphers: [CipherView], encrypted: [BitwardenSdk.Cipher], client: Client, upload: @escaping (EncryptionContext) async throws -> String) {
        self.purpose = purpose
        self.ciphers = ciphers
        self.encrypted = encrypted
        self.client = client
        self.upload = upload
    }

    // MARK: Fido2UserInterface

    /// A refusal in a user check (e.g. passkey already saved), reported instead of the SDK's code.
    private(set) var checkError: Error?

    func checkUser(options: CheckUserOptions, hint: UiHint) async throws -> CheckUserResult {
        if case .informExcludedCredentialFound = hint {
            checkError = PasskeyFailure.alreadyRegistered
            throw PasskeyFailure.alreadyRegistered
        }
        return CheckUserResult(userPresent: true, userVerified: true)
    }

    func pickCredentialForAuthentication(availableCredentials: [CipherView]) async throws -> CipherViewWrapper {
        guard case let .assertion(cipherId) = purpose else { throw PasskeyFailure.notFound }
        let picked = cipherId.map { id in availableCredentials.first { $0.id == id } } ?? availableCredentials.first
        guard let picked else { throw PasskeyFailure.notFound }
        return CipherViewWrapper(cipher: picked)
    }

    func checkUserAndPickCredentialForCreation(options: CheckUserOptions, newCredential: Fido2CredentialNewView) async throws -> CheckUserAndPickCredentialForCreationResult {
        guard case let .registration(target) = purpose else { throw PasskeyFailure.notFound }
        return CheckUserAndPickCredentialForCreationResult(
            cipher: CipherViewWrapper(cipher: target),
            checkUserResult: CheckUserResult(userPresent: true, userVerified: true)
        )
    }

    func isVerificationEnabled() -> Bool { true }

    // MARK: Fido2CredentialStore

    func findCredentials(ids: [Data]?, ripId: String, userHandle: Data?) async throws -> [CipherView] {
        let wanted = ids ?? []
        // While creating, the SDK only asks to check the site's exclude list.
        if case .registration = purpose, wanted.isEmpty { return [] }
        let fido2 = client.platform().fido2()
        return ciphers.filter { cipher in
            guard let views = try? fido2.getFido2AutofillCredentials(cipherView: cipher) else { return false }
            return views.contains { view in
                view.rpId == ripId
                    && (wanted.isEmpty || wanted.contains(view.credentialId))
                    && (userHandle == nil || view.userHandle == userHandle)
            }
        }
    }

    func allCredentials() async throws -> [CipherListView] {
        try await client.vault().ciphers().decryptList(ciphers: encrypted)
    }

    func saveCredential(cred: EncryptionContext) async throws {
        switch purpose {
        case .registration:
            // Must reach the server: the site keeps the public key either way.
            do {
                savedCipherId = try await upload(cred)
            } catch {
                saveError = error
                PasskeyLog.failure("save", error)
                throw error
            }
        case .assertion:
            // Only passkeys with a signature counter (imported ones) get here. A failed upload
            // must not block the sign-in; the next use sends the newer counter again.
            _ = try? await upload(cred)
        }
    }
}

/// Passkey failures in the device log (Console, subsystem com.framepersecond.m3bitwarden), without
/// any key or vault data: only the operation and the error text.
enum PasskeyLog {
    private static let log = Logger(subsystem: "com.framepersecond.m3bitwarden", category: "passkey")

    static func failure(_ operation: String, _ error: Error) {
        log.error("passkey \(operation, privacy: .public) failed: \(describe(error), privacy: .public)")
    }

    static func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }
}

// MARK: - Engine

/// Passkey operations over one decrypted vault. The app and the AutoFill extension build it from
/// `VaultService`; the debug self-test builds it from a throwaway vault.
struct PasskeyEngine {
    let client: Client
    /// Decrypted logins by id (passkeys inside stay encrypted until the SDK signs with them).
    let decrypted: [String: CipherView]
    let encrypted: [BitwardenSdk.Cipher]
    /// Saves an encrypted login on the server and returns its id.
    let upload: (EncryptionContext) async throws -> String

    /// Logins with a passkey that is not in the trash.
    private var passkeyCiphers: [CipherView] {
        decrypted.values.filter { $0.deletedDate == nil && $0.login?.fido2Credentials?.isEmpty == false }
    }

    /// Passkeys for [rpId] (all of them when nil), limited to [allowed] credential ids if given.
    func passkeys(rpId: String? = nil, allowed: [Data] = []) -> [PasskeyEntry] {
        let fido2 = client.platform().fido2()
        return passkeyCiphers.flatMap { cipher -> [PasskeyEntry] in
            guard let id = cipher.id, let views = try? fido2.getFido2AutofillCredentials(cipherView: cipher) else { return [] }
            let stored = cipher.login?.fido2Credentials ?? []
            return views.enumerated().compactMap { index, view in
                guard rpId == nil || view.rpId == rpId, allowed.isEmpty || allowed.contains(view.credentialId) else { return nil }
                return PasskeyEntry(
                    cipherId: id,
                    credentialId: view.credentialId,
                    rpId: view.rpId,
                    userName: view.userNameForUi ?? cipher.login?.username ?? "",
                    userHandle: view.userHandle,
                    itemName: cipher.name,
                    // The SDK lists the credentials in stored order; the date is not encrypted.
                    created: index < stored.count ? stored[index].creationDate : nil
                )
            }
        }
        .sorted { ($0.itemName.lowercased(), $0.userName) < ($1.itemName.lowercased(), $1.userName) }
    }

    /// Logins for [rpId] that have no passkey yet and could take the new one.
    func targets(rpId: String) -> [PasskeyTarget] {
        let site = rpId.lowercased()
        return decrypted.values.compactMap { cipher -> PasskeyTarget? in
            guard let id = cipher.id, cipher.deletedDate == nil, cipher.edit, let login = cipher.login,
                  login.fido2Credentials?.isEmpty != false
            else { return nil }
            let hosts = (login.uris ?? []).compactMap { $0.uri.flatMap(Self.host(of:)) }
            guard let host = hosts.first(where: { $0 == site || $0.hasSuffix("." + site) || site.hasSuffix("." + $0) }) else { return nil }
            return PasskeyTarget(cipherId: id, name: cipher.name, userName: login.username ?? "", host: host)
        }
        .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    /// Signs [request] with the passkey of [cipherId] (or the only match when nil).
    func assertion(_ request: PasskeyAssertionRequest, cipherId: String?) async throws -> GetAssertionResult {
        let session = PasskeySession(purpose: .assertion(cipherId: cipherId), ciphers: passkeyCiphers, encrypted: encrypted, client: client, upload: upload)
        let allowList = request.allowedCredentials.map { PublicKeyCredentialDescriptor(ty: "public-key", id: $0, transports: nil) }
        do {
            return try await client.platform().fido2()
                .authenticator(userInterface: session, credentialStore: session)
                .getAssertion(request: GetAssertionRequest(
                    rpId: request.rpId,
                    clientDataHash: request.clientDataHash,
                    allowList: allowList.isEmpty ? nil : allowList,
                    options: Options(rk: false, uv: request.userVerification),
                    extensions: nil
                ))
        } catch {
            PasskeyLog.failure("assertion", error)
            throw PasskeyFailure.sdk("Anmelden mit dem Passkey ist fehlgeschlagen (\(PasskeyLog.describe(error))).")
        }
    }

    /// Creates a passkey for [request] in the login [cipherId], or in a new login when nil, and
    /// saves it on the server. Returns the result and the id of the login that holds it.
    func registration(_ request: PasskeyRegistrationRequest, cipherId: String?) async throws -> (result: MakeCredentialResult, cipherId: String) {
        guard request.algorithms.isEmpty || request.algorithms.contains(-7) else { throw PasskeyFailure.unsupportedAlgorithm }
        let target: CipherView
        if let cipherId {
            guard let existing = decrypted[cipherId] else { throw PasskeyFailure.notFound }
            target = existing
        } else {
            target = .newPasskeyLogin(rpId: request.rpId, name: request.rpName, userName: request.userName)
        }
        let session = PasskeySession(purpose: .registration(target: target), ciphers: passkeyCiphers, encrypted: encrypted, client: client, upload: upload)
        let excluded = request.excludedCredentials.map { PublicKeyCredentialDescriptor(ty: "public-key", id: $0, transports: nil) }
        let result: MakeCredentialResult
        do {
            result = try await client.platform().fido2()
                .authenticator(userInterface: session, credentialStore: session)
                .makeCredential(request: MakeCredentialRequest(
                    clientDataHash: request.clientDataHash,
                    rp: PublicKeyCredentialRpEntity(id: request.rpId, name: request.rpName),
                    user: PublicKeyCredentialUserEntity(id: request.userHandle, displayName: request.userDisplayName, name: request.userName),
                    pubKeyCredParams: [PublicKeyCredentialParameters(ty: "public-key", alg: -7)],
                    excludeList: excluded.isEmpty ? nil : excluded,
                    options: Options(rk: true, uv: request.userVerification),
                    extensions: nil
                ))
        } catch {
            // The SDK hides why saving failed behind a vendor error; report our own reason.
            if let reason = session.saveError { throw reason }
            if let reason = session.checkError { throw reason }
            PasskeyLog.failure("registration", error)
            throw PasskeyFailure.sdk("Der Passkey konnte nicht erstellt werden (\(PasskeyLog.describe(error))).")
        }
        guard let saved = session.savedCipherId else { throw PasskeyFailure.upload("keine Antwort") }
        return (result, saved)
    }

    private static func host(of uri: String) -> String? {
        let text = uri.contains("://") ? uri : "https://" + uri
        return URL(string: text)?.host?.lowercased()
    }
}

// MARK: - Vault access

extension VaultService {
    /// Nil while the vault is locked.
    var passkeyEngine: PasskeyEngine? {
        guard let client else { return nil }
        return PasskeyEngine(client: client, decrypted: decrypted, encrypted: Array(encrypted.values)) { context in
            try await VaultService.shared.uploadPasskey(context)
        }
    }

    func passkeys(rpId: String? = nil, allowed: [Data] = []) -> [PasskeyEntry] {
        passkeyEngine?.passkeys(rpId: rpId, allowed: allowed) ?? []
    }

    func passkeyTargets(rpId: String) -> [PasskeyTarget] {
        passkeyEngine?.targets(rpId: rpId) ?? []
    }

    func passkeyAssertion(_ request: PasskeyAssertionRequest, cipherId: String?) async throws -> GetAssertionResult {
        guard let engine = passkeyEngine else { throw PasskeyFailure.locked }
        return try await engine.assertion(request, cipherId: cipherId)
    }

    func passkeyRegistration(_ request: PasskeyRegistrationRequest, cipherId: String?) async throws -> (result: MakeCredentialResult, cipherId: String) {
        guard let engine = passkeyEngine else { throw PasskeyFailure.locked }
        return try await engine.registration(request, cipherId: cipherId)
    }

    /// Uploads an encrypted login (new or changed passkey) and returns its server id.
    func uploadPasskey(_ context: EncryptionContext) async throws -> String {
        let body = CipherRequestModel(cipher: context.cipher)
        do {
            return try await api.saveCipher(id: context.cipher.id, body)
        } catch {
            throw PasskeyFailure.upload(PasskeyLog.describe(error))
        }
    }
}

extension CipherView {
    /// The login a new passkey goes into when the user does not pick an existing one.
    static func newPasskeyLogin(rpId: String, name: String?, userName: String, now: Date = Date()) -> CipherView {
        CipherView(
            id: nil, organizationId: nil, folderId: nil, collectionIds: [], key: nil,
            name: (name?.isEmpty == false ? name : nil) ?? rpId, notes: nil, type: .login,
            login: LoginView(
                username: userName.isEmpty ? nil : userName,
                password: nil,
                passwordRevisionDate: nil,
                uris: [LoginUriView(uri: "https://\(rpId)", match: nil, uriChecksum: nil)],
                totp: nil,
                autofillOnPageLoad: nil,
                httpAuthenticationEnabled: nil,
                fido2Credentials: nil
            ),
            identity: nil, card: nil, secureNote: nil, sshKey: nil,
            bankAccount: nil, driversLicense: nil, passport: nil,
            favorite: false, reprompt: .none, organizationUseTotp: false, edit: true, permissions: nil,
            viewPassword: true, localData: nil, attachments: nil, attachmentDecryptionFailures: nil,
            fields: nil, passwordHistory: nil, creationDate: now, deletedDate: nil, revisionDate: now, archivedDate: nil, partial: false
        )
    }
}
