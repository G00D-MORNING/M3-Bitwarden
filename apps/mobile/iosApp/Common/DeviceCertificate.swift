import Foundation
import Security

/// The device's TLS client certificate for vault.* (Cloudflare mTLS).
///
/// The private key is created here, kept in the shared keychain and never leaves the device:
/// the app shows a certificate signing request (CSR), the user pastes it into Cloudflare
/// ("Client certificates → Create → Use my private key and CSR") and pastes the returned
/// certificate back.
enum DeviceCertificate {
    enum Failure: LocalizedError {
        case keyCreation(String)
        case noKey
        case signing(String)
        case invalidCertificate
        case wrongKey
        case keychain(OSStatus)

        var errorDescription: String? {
            switch self {
            case let .keyCreation(m): "Schlüssel konnte nicht erzeugt werden: \(m)"
            case .noKey: "Es gibt noch keinen Geräteschlüssel. Erzeuge zuerst die Anfrage."
            case let .signing(m): "Anfrage konnte nicht signiert werden: \(m)"
            case .invalidCertificate: "Das ist kein gültiges Zertifikat. Kopiere den ganzen Block mit BEGIN/END CERTIFICATE."
            case .wrongKey: "Dieses Zertifikat gehört zu einer anderen Anfrage. Erstelle es in Cloudflare mit der Anfrage dieses Geräts."
            case let .keychain(s): "Schlüsselbund-Fehler \(s)."
            }
        }
    }

    private static let keyTag = Data("com.framepersecond.m3bitwarden.mtls.key".utf8)
    private static let certLabel = "M3 Bitwarden Gerätezertifikat"

    // MARK: State

    static var hasKey: Bool { privateKey() != nil }
    static var isInstalled: Bool { identity() != nil }

    /// When the certificate was installed on this device, for display.
    static var installedAt: Date? {
        UserDefaults(suiteName: M3BConfig.appGroup)?.object(forKey: "mtls.installed") as? Date
    }

    // MARK: Enrollment

    /// Creates the key if needed and returns a PEM CSR for Cloudflare.
    static func makeSigningRequest(deviceName: String) throws -> String {
        let key = try privateKey() ?? createKey()
        guard let publicKey = SecKeyCopyPublicKey(key),
              let point = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?
        else { throw Failure.noKey }

        let spki = DER.sequence([
            DER.sequence([DER.oid("1.2.840.10045.2.1"), DER.oid("1.2.840.10045.3.1.7")]),
            DER.bitString(point),
        ])
        let subject = DER.sequence([
            DER.set([DER.sequence([DER.oid("2.5.4.10"), DER.utf8("M3 Bitwarden")])]),
            DER.set([DER.sequence([DER.oid("2.5.4.3"), DER.utf8(deviceName)])]),
        ])
        let info = DER.sequence([DER.integer(0), subject, spki, DER.contextConstructed(0, Data())])

        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, info as CFData, &error) as Data? else {
            throw Failure.signing(error?.takeRetainedValue().localizedDescription ?? "unbekannt")
        }
        let csr = DER.sequence([
            info,
            DER.sequence([DER.oid("1.2.840.10045.4.3.2")]),
            DER.bitString(signature),
        ])
        return DER.pem(csr, label: "CERTIFICATE REQUEST")
    }

    /// Stores the certificate Cloudflare issued for this device's CSR.
    static func install(pem: String) throws {
        guard let der = firstCertificate(in: pem), let cert = SecCertificateCreateWithData(nil, der as CFData) else {
            throw Failure.invalidCertificate
        }
        guard let key = privateKey(),
              let ours = SecKeyCopyPublicKey(key).flatMap({ SecKeyCopyExternalRepresentation($0, nil) as Data? }),
              let theirs = SecCertificateCopyKey(cert).flatMap({ SecKeyCopyExternalRepresentation($0, nil) as Data? })
        else { throw Failure.noKey }
        guard ours == theirs else { throw Failure.wrongKey }

        removeCertificate()
        let status = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecValueRef: cert,
            kSecAttrLabel: certLabel,
            kSecAttrAccessGroup: M3BConfig.appGroup,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ] as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
        UserDefaults(suiteName: M3BConfig.appGroup)?.set(Date(), forKey: "mtls.installed")
    }

    /// Removes certificate and key (e.g. to re-enroll).
    static func reset() {
        removeCertificate()
        SecItemDelete([
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: keyTag,
            kSecAttrAccessGroup: M3BConfig.appGroup,
        ] as CFDictionary)
    }

    // MARK: TLS

    /// Key + certificate as one identity, used to answer Cloudflare's client-certificate request.
    static func identity() -> SecIdentity? {
        guard let key = privateKey(),
              let ours = SecKeyCopyPublicKey(key).flatMap({ SecKeyCopyExternalRepresentation($0, nil) as Data? })
        else { return nil }
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassIdentity,
            kSecAttrAccessGroup: M3BConfig.appGroup,
            kSecReturnRef: true,
            kSecMatchLimit: kSecMatchLimitAll,
        ] as CFDictionary, &result)
        guard status == errSecSuccess, let identities = result as? [SecIdentity] else { return nil }
        return identities.first { identity in
            var cert: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &cert) == errSecSuccess, let cert else { return false }
            return SecCertificateCopyKey(cert).flatMap { SecKeyCopyExternalRepresentation($0, nil) as Data? } == ours
        }
    }

    // MARK: Keychain

    private static func createKey() throws -> SecKey {
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: keyTag,
                kSecAttrAccessGroup: M3BConfig.appGroup,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                kSecAttrLabel: "M3 Bitwarden Geräteschlüssel",
            ] as [CFString: Any],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw Failure.keyCreation(error?.takeRetainedValue().localizedDescription ?? "unbekannt")
        }
        return key
    }

    private static func privateKey() -> SecKey? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: keyTag,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrAccessGroup: M3BConfig.appGroup,
            kSecReturnRef: true,
        ] as CFDictionary, &result)
        guard status == errSecSuccess, let result else { return nil }
        return (result as! SecKey) // swiftlint:disable:this force_cast
    }

    private static func certificate() -> SecCertificate? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: certLabel,
            kSecAttrAccessGroup: M3BConfig.appGroup,
            kSecReturnRef: true,
        ] as CFDictionary, &result)
        guard status == errSecSuccess, let result else { return nil }
        return (result as! SecCertificate) // swiftlint:disable:this force_cast
    }

    private static func removeCertificate() {
        SecItemDelete([
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: certLabel,
            kSecAttrAccessGroup: M3BConfig.appGroup,
        ] as CFDictionary)
    }

    /// The first certificate of a pasted PEM (Cloudflare may add the CA after it).
    private static func firstCertificate(in text: String) -> Data? {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
        guard let start = lines.firstIndex(where: { $0.hasPrefix("-----BEGIN CERTIFICATE") }) else {
            return DER.fromPEM(text)
        }
        let rest = lines[(start + 1)...]
        guard let end = rest.firstIndex(where: { $0.hasPrefix("-----END CERTIFICATE") }) else { return nil }
        return Data(base64Encoded: rest[rest.startIndex..<end].joined())
    }
}

/// Answers Cloudflare's client-certificate request with the device identity.
final class ClientCertificateDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge
    ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        Self.answer(challenge)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge
    ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        Self.answer(challenge)
    }

    private static func answer(_ challenge: URLAuthenticationChallenge) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodClientCertificate,
              let identity = DeviceCertificate.identity()
        else { return (.performDefaultHandling, nil) }
        return (.useCredential, URLCredential(identity: identity, certificates: nil, persistence: .forSession))
    }
}
