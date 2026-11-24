import Foundation
import LocalAuthentication
import Security

/// Keychain storage shared by app and extension (access group = App Group).
///
/// - Account and tokens: readable after first unlock, so the extension can sync.
/// - Vault user key: only behind Face ID / Touch ID / device passcode, invalidated when the
///   enrolled biometrics change. Without it the encrypted vault cache on disk is useless.
enum SecureStore {
    enum Item: String {
        case account = "account"
        case tokens = "tokens"
        case userKey = "userKey"
    }

    private static let service = "com.framepersecond.m3bitwarden"

    static func set(_ data: Data, for item: Item) throws {
        delete(item)
        var query = base(item)
        query[kSecValueData] = data
        guard item == .userKey else {
            query[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(query as CFDictionary, nil)
            guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
            return
        }
        // Prefer "current biometrics only"; fall back to biometrics-or-passcode if none are enrolled.
        var status = errSecParam
        for flags in [SecAccessControlCreateFlags.biometryCurrentSet, .userPresence] {
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, flags, nil) else { continue }
            query[kSecAttrAccessControl] = access
            status = SecItemAdd(query as CFDictionary, nil)
            if status == errSecSuccess { return }
        }
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }

    /// Reads an item; for `.userKey` this shows the Face ID prompt with [reason].
    static func get(_ item: Item, reason: String? = nil) -> Data? {
        var query = base(item)
        query[kSecReturnData] = true
        if item == .userKey {
            let context = LAContext()
            context.localizedReason = reason ?? "Tresor entsperren"
            query[kSecUseAuthenticationContext] = context
        }
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    /// Reads the Face ID protected user key off the main thread, so the system prompt can appear
    /// (a blocking keychain call on the main thread froze the AutoFill sheet as a white page).
    static func userKey(reason: String) async -> Data? {
        await Task.detached(priority: .userInitiated) { get(.userKey, reason: reason) }.value
    }

    /// Whether the biometric key exists, without prompting.
    static var hasUserKey: Bool {
        var query = base(.userKey)
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext] = context
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    static func delete(_ item: Item) {
        SecItemDelete(base(item) as CFDictionary)
    }

    static func setCodable<T: Encodable>(_ value: T, for item: Item) throws {
        try set(JSONEncoder().encode(value), for: item)
    }

    static func getCodable<T: Decodable>(_ type: T.Type, _ item: Item) -> T? {
        get(item).flatMap { try? JSONDecoder().decode(type, from: $0) }
    }

    private static func base(_ item: Item) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: item.rawValue,
            kSecAttrAccessGroup: M3BConfig.appGroup,
        ]
    }
}

/// What we keep about the signed-in account (everything secret in it is ciphertext).
struct StoredAccount: Codable {
    let userId: String
    let email: String
    let kdf: KdfModel
    /// User key wrapped with the master key ("Key" from the token response).
    let masterKeyWrappedUserKey: String
    /// Private key wrapped with the user key.
    let privateKey: String
    let deviceIdentifier: String
}

struct StoredTokens: Codable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
}

/// The encrypted sync response, kept so the extension and offline starts have data.
enum VaultCache {
    private static var url: URL { M3BConfig.containerURL.appendingPathComponent("vault-sync.json") }

    static func save(_ data: Data) throws {
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func load() -> Data? { try? Data(contentsOf: url) }

    static func clear() { try? FileManager.default.removeItem(at: url) }
}
