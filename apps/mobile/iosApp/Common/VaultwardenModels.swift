import Foundation

// Response models for the Bitwarden API as served by Vaultwarden. Every secret in them is an
// EncString (ciphertext); decryption happens only in the Bitwarden SDK.

enum JSON {
    /// Bitwarden servers mix PascalCase ("Key", "PrivateKey") and camelCase; normalise to camelCase.
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .custom { path in
            let raw = path.last!.stringValue
            guard let first = raw.first else { return path.last! }
            return AnyKey(first.lowercased() + raw.dropFirst())
        }
        d.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = parseDate(text) else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Datum \(text)"))
            }
            return date
        }
        return d
    }()

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            try c.encode(formatDate(date))
        }
        return e
    }()

    private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// Accepts "2024-01-02T03:04:05Z", "…05.1234567Z" and "…05.123456" (no zone, UTC).
    static func parseDate(_ text: String) -> Date? {
        var s = text
        if !(s.hasSuffix("Z") || s.range(of: #"[+-]\d\d:\d\d$"#, options: .regularExpression) != nil) { s += "Z" }
        if let dot = s.firstIndex(of: "."), let zone = s[dot...].firstIndex(where: { $0 == "Z" || $0 == "+" || $0 == "-" }) {
            let digits = s[s.index(after: dot)..<zone].prefix(3)
            s = String(s[..<dot]) + "." + digits.padding(toLength: 3, withPad: "0", startingAt: 0) + String(s[zone...])
            return withFraction.date(from: s)
        }
        return plain.date(from: s)
    }

    static func formatDate(_ date: Date) -> String { withFraction.string(from: date) }

    struct AnyKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ string: String) { stringValue = string }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }
}

// MARK: Auth

struct PreloginResponse: Decodable {
    let kdf: Int
    let kdfIterations: Int
    let kdfMemory: Int?
    let kdfParallelism: Int?
}

struct TokenResponse: Decodable {
    let accessToken: String
    let expiresIn: Int
    let refreshToken: String?
    let key: String?
    let privateKey: String?
    let kdf: Int?
    let kdfIterations: Int?
    let kdfMemory: Int?
    let kdfParallelism: Int?
    let userDecryptionOptions: UserDecryptionOptions?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case expiresIn = "expires_in"
        case refreshToken = "refresh_token"
        case key, privateKey, kdf, kdfIterations, kdfMemory, kdfParallelism, userDecryptionOptions
    }
}

struct UserDecryptionOptions: Decodable {
    let hasMasterPassword: Bool?
    let masterPasswordUnlock: MasterPasswordUnlockModel?
}

struct MasterPasswordUnlockModel: Decodable {
    let kdf: KdfModel
    let masterKeyEncryptedUserKey: String
    let salt: String
}

struct KdfModel: Codable, Equatable {
    let kdfType: Int
    let iterations: Int
    let memory: Int?
    let parallelism: Int?
}

// MARK: Sync

struct SyncResponse: Decodable {
    let profile: ProfileModel
    let folders: [FolderModel]?
    let ciphers: [CipherModel]
    let domains: DomainsModel?
}

struct ProfileModel: Decodable {
    let id: String
    let email: String
    let name: String?
    let key: String?
    let privateKey: String?
    let organizations: [OrganizationModel]?
}

struct OrganizationModel: Decodable {
    let id: String
    let key: String?
}

struct FolderModel: Decodable {
    let id: String
    let name: String
    let revisionDate: Date
}

struct DomainsModel: Decodable {
    let equivalentDomains: [[String]]?
    let globalEquivalentDomains: [GlobalDomainModel]?
}

struct GlobalDomainModel: Decodable {
    let domains: [String]
    let excluded: Bool
}

struct CipherModel: Codable {
    var id: String
    var organizationId: String?
    var folderId: String?
    var collectionIds: [String]?
    var key: String?
    var name: String?
    var notes: String?
    var type: Int
    var login: LoginModel?
    var card: CardModel?
    var identity: IdentityModel?
    var secureNote: SecureNoteModel?
    var sshKey: SshKeyModel?
    var favorite: Bool?
    var reprompt: Int?
    var organizationUseTotp: Bool?
    var edit: Bool?
    var viewPassword: Bool?
    var permissions: PermissionsModel?
    var attachments: [AttachmentModel]?
    var fields: [FieldModel]?
    var passwordHistory: [PasswordHistoryModel]?
    var creationDate: Date?
    var deletedDate: Date?
    var revisionDate: Date
    var archivedDate: Date?
    // "data" is left out on purpose: Vaultwarden sends a legacy JSON object there, Bitwarden a
    // blob string; Vaultwarden never produces blob-encrypted ciphers, so it is not needed.
}

struct LoginModel: Codable {
    var username: String?
    var password: String?
    var passwordRevisionDate: Date?
    var uris: [LoginUriModel]?
    var totp: String?
    var autofillOnPageLoad: Bool?
    var fido2Credentials: [Fido2CredentialModel]?
}

struct LoginUriModel: Codable {
    var uri: String?
    var match: Int?
    var uriChecksum: String?
}

struct Fido2CredentialModel: Codable {
    var credentialId: String
    var keyType: String
    var keyAlgorithm: String
    var keyCurve: String
    var keyValue: String
    var rpId: String
    var userHandle: String?
    var userName: String?
    var counter: String
    var rpName: String?
    var userDisplayName: String?
    var discoverable: String
    var creationDate: Date
}

struct CardModel: Codable {
    var cardholderName: String?
    var brand: String?
    var number: String?
    var expMonth: String?
    var expYear: String?
    var code: String?
}

struct IdentityModel: Codable {
    var title: String?
    var firstName: String?
    var middleName: String?
    var lastName: String?
    var address1: String?
    var address2: String?
    var address3: String?
    var city: String?
    var state: String?
    var postalCode: String?
    var country: String?
    var company: String?
    var email: String?
    var phone: String?
    var ssn: String?
    var username: String?
    var passportNumber: String?
    var licenseNumber: String?
}

struct SecureNoteModel: Codable {
    var type: Int
}

struct SshKeyModel: Codable {
    var privateKey: String?
    var publicKey: String?
    var keyFingerprint: String?
}

struct PermissionsModel: Codable {
    var delete: Bool
    var restore: Bool
}

struct AttachmentModel: Codable {
    var id: String?
    var url: String?
    var size: String?
    var sizeName: String?
    var fileName: String?
    var key: String?
}

struct FieldModel: Codable {
    var name: String?
    var value: String?
    var type: Int
    var linkedId: UInt32?
}

struct PasswordHistoryModel: Codable {
    var password: String
    var lastUsedDate: Date
}

/// Body for POST /api/ciphers and PUT /api/ciphers/{id}.
struct CipherRequestModel: Encodable {
    var type: Int
    var organizationId: String?
    var folderId: String?
    var key: String?
    var name: String?
    var notes: String?
    var favorite: Bool
    var reprompt: Int
    var login: LoginModel?
    var card: CardModel?
    var identity: IdentityModel?
    var secureNote: SecureNoteModel?
    var sshKey: SshKeyModel?
    var fields: [FieldModel]?
    var passwordHistory: [PasswordHistoryModel]?
    var lastKnownRevisionDate: Date?
}
