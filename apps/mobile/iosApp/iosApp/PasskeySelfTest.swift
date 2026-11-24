#if DEBUG
import BitwardenSdk
import CryptoKit
import Foundation

/// Debug builds started with `-m3bPasskeySelfTest`: runs the passkey engine end to end against a
/// throwaway vault that exists only in memory (made-up account, no server) and prints one line per
/// check with the prefix "M3BPasskeyTest". Prints no keys and no vault data.
///
/// Covers: creating a passkey, the encrypted upload body, finding it again after decryption,
/// signing in with it, verifying the signature with the public key from the registration, the
/// relying-party binding and the exclude list.
enum PasskeySelfTest {
    static func runIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-m3bPasskeySelfTest") else { return }
        do {
            try await run()
            log("done: all checks passed")
        } catch {
            log("FAIL: \(error)")
        }
    }

    private static func run() async throws {
        let client = Client(
            tokenProvider: SdkTokenProvider(),
            settings: ClientSettings(
                identityUrl: "https://localhost/identity", apiUrl: "https://localhost/api", userAgent: "selftest",
                deviceType: .iOs, deviceIdentifier: "selftest", bitwardenClientVersion: "1", bitwardenPackageType: nil
            ),
            managedSettings: ManagedSettingsBindingClient()
        )
        let email = "selftest@example.invalid"
        let password = "selftest-throwaway-password"
        let kdf = Kdf.pbkdf2(iterations: 600_000)
        let keys = try client.auth().makeRegisterKeys(email: email, password: password, kdf: kdf)
        try await client.crypto().initializeUserCrypto(req: InitUserCryptoRequest(
            userId: UUID().uuidString.lowercased(), kdfParams: kdf, email: email,
            accountCryptographicState: .v1(privateKey: keys.keys.private),
            method: .masterPasswordUnlock(password: password, masterPasswordUnlock: MasterPasswordUnlockData(kdf: kdf, masterKeyWrappedUserKey: keys.encryptedUserKey, salt: email)),
            upgradeToken: nil
        ))
        log("ok: throwaway vault unlocked")

        // In-memory "server".
        let server = MemoryServer()
        let upload: (EncryptionContext) async throws -> String = { context in
            let body = String(decoding: try JSON.encoder.encode(CipherRequestModel(context: context)), as: UTF8.self)
            guard body.contains("\"encryptedFor\":\"") else { throw Failure("upload body without encryptedFor") }
            return await server.save(context.cipher)
        }

        // 1. Create a passkey (new login).
        let rpId = "webauthn.io"
        let userHandle = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        let empty = PasskeyEngine(client: client, decrypted: [:], encrypted: [], upload: upload)
        let registration = PasskeyRegistrationRequest(
            rpId: rpId, rpName: "WebAuthn.io", userName: "selftest-user", userDisplayName: "Self Test",
            userHandle: userHandle, clientDataHash: random32(), algorithms: [-7, -257], excludedCredentials: [], userVerification: .required
        )
        let (made, cipherId) = try await empty.registration(registration, cipherId: nil)
        try check(!made.credentialId.isEmpty && !made.attestationObject.isEmpty, "passkey created (credential id \(made.credentialId.count) bytes)")

        // 2. What went to the server: an encrypted login with the passkey inside.
        guard let stored = await server.cipher(cipherId) else { throw Failure("uploaded login missing") }
        let body = String(decoding: try JSON.encoder.encode(CipherRequestModel(cipher: stored)), as: UTF8.self)
        try check(body.contains("\"fido2Credentials\"") && body.contains("\"keyValue\":\"2."), "upload body carries the encrypted passkey")
        try check(!body.contains("selftest-user"), "upload body has no plaintext username")

        // 3. After a "sync": decrypt and find it again.
        let view = try await client.vault().ciphers().decrypt(cipher: stored)
        let engine = PasskeyEngine(client: client, decrypted: [cipherId: view], encrypted: [stored], upload: upload)
        let found = engine.passkeys(rpId: rpId)
        try check(found.count == 1 && found[0].credentialId == made.credentialId && found[0].userHandle == userHandle, "passkey listed for \(rpId)")
        try check(engine.passkeys(rpId: "example.com").isEmpty, "no passkey listed for another site")
        try check(view.name == "WebAuthn.io" && view.login?.uris?.first?.uri == "https://\(rpId)", "new login named after the site with its address")

        // 4. Sign in (discoverable, as the passkey sheet does) and verify the signature.
        let clientDataHash = random32()
        let assertion = try await engine.assertion(PasskeyAssertionRequest(rpId: rpId, clientDataHash: clientDataHash, allowedCredentials: [], userVerification: .required), cipherId: nil)
        try check(assertion.credentialId == made.credentialId && assertion.userHandle == userHandle, "assertion uses the stored passkey")
        let authData = assertion.authenticatorData
        try check(authData.prefix(32) == Data(SHA256.hash(data: Data(rpId.utf8))), "authenticator data is bound to \(rpId)")
        try check(authData.count > 32 && authData[authData.startIndex + 32] & 0x05 == 0x05, "user present and verified flags set")
        let publicKey = try publicKey(fromRegistration: made.authenticatorData)
        let signature = try P256.Signing.ECDSASignature(derRepresentation: assertion.signature)
        try check(publicKey.isValidSignature(signature, for: authData + clientDataHash), "signature verifies with the registered public key")
        try check(!publicKey.isValidSignature(signature, for: authData + random32()), "signature does not verify for other client data")

        // 5. Sign in with an allow list (QuickType / passkey sheet tap on one passkey).
        let allowed = try await engine.assertion(PasskeyAssertionRequest(rpId: rpId, clientDataHash: random32(), allowedCredentials: [made.credentialId], userVerification: .preferred), cipherId: cipherId)
        try check(allowed.credentialId == made.credentialId, "assertion with allow list")

        // 6. Wrong site must not sign.
        do {
            _ = try await engine.assertion(PasskeyAssertionRequest(rpId: "evil.example", clientDataHash: random32(), allowedCredentials: [], userVerification: .required), cipherId: nil)
            throw Failure("signed for another site")
        } catch is Failure {
            throw Failure("signed for another site")
        } catch {
            log("ok: refuses to sign for another site")
        }

        // 7. Exclude list: the site already has this passkey.
        do {
            var again = registration
            again = PasskeyRegistrationRequest(
                rpId: again.rpId, rpName: again.rpName, userName: again.userName, userDisplayName: again.userDisplayName,
                userHandle: again.userHandle, clientDataHash: random32(), algorithms: [-7], excludedCredentials: [made.credentialId], userVerification: .required
            )
            _ = try await engine.registration(again, cipherId: nil)
            throw Failure("created a second passkey despite the exclude list")
        } catch is Failure {
            throw Failure("created a second passkey despite the exclude list")
        } catch {
            log("ok: exclude list respected")
        }

        // 8. Adding a passkey to an existing login keeps its password.
        let existing = CipherView.newPasskeyLogin(rpId: "example.org", name: "Example", userName: "anna")
        let withPassword = CipherView(
            id: nil, organizationId: nil, folderId: nil, collectionIds: [], key: nil, name: existing.name, notes: nil, type: .login,
            login: LoginView(username: "anna", password: "pw-throwaway", passwordRevisionDate: nil, uris: existing.login?.uris, totp: nil,
                             autofillOnPageLoad: nil, httpAuthenticationEnabled: nil, fido2Credentials: nil),
            identity: nil, card: nil, secureNote: nil, sshKey: nil, bankAccount: nil, driversLicense: nil, passport: nil,
            favorite: false, reprompt: .none, organizationUseTotp: false, edit: true, permissions: nil, viewPassword: true,
            localData: nil, attachments: nil, attachmentDecryptionFailures: nil, fields: nil, passwordHistory: nil,
            creationDate: Date(), deletedDate: nil, revisionDate: Date(), archivedDate: nil, partial: false
        )
        let loginId = await server.save(try await client.vault().ciphers().encrypt(cipherView: withPassword).cipher)
        let loginView = try await client.vault().ciphers().decrypt(cipher: await server.cipher(loginId)!)
        let engine2 = PasskeyEngine(client: client, decrypted: [cipherId: view, loginId: loginView], encrypted: [], upload: upload)
        try check(engine2.targets(rpId: "example.org").map(\.cipherId) == [loginId], "existing login offered for a new passkey")
        try check(engine2.targets(rpId: "example.org", account: "Anna").first?.match == .same, "login with the same account marked")
        try check(PasskeyTarget.match("+49 170 1234567", "01701234567") == .same && PasskeyTarget.match("anna@mail.de", "anna") == .similar
            && PasskeyTarget.match("bob@mail.de", "anna@mail.de") == .none, "account matching: phone, e-mail part, different")
        try check(engine2.targets(rpId: rpId).isEmpty, "login that already has a passkey is not offered")
        let (_, savedInto) = try await engine2.registration(PasskeyRegistrationRequest(
            rpId: "example.org", rpName: nil, userName: "anna", userDisplayName: "anna", userHandle: random32(),
            clientDataHash: random32(), algorithms: [-7], excludedCredentials: [], userVerification: .preferred
        ), cipherId: loginId)
        let updated = try await client.vault().ciphers().decrypt(cipher: await server.cipher(savedInto)!)
        try check(savedInto == loginId && updated.login?.password == "pw-throwaway" && updated.login?.fido2Credentials?.count == 1, "passkey added to the existing login, password kept")
    }

    /// x/y of the COSE EC2 key in the attested credential data of a registration.
    private static func publicKey(fromRegistration authData: Data) throws -> P256.Signing.PublicKey {
        let bytes = [UInt8](authData)
        guard bytes.count > 55 else { throw Failure("short authenticator data") }
        let idLength = Int(bytes[53]) << 8 | Int(bytes[54])
        let cose = Array(bytes[(55 + idLength)...])
        func coordinate(_ label: UInt8) throws -> [UInt8] {
            for i in 0..<(cose.count - 34) where cose[i] == label && cose[i + 1] == 0x58 && cose[i + 2] == 0x20 {
                return Array(cose[(i + 3)..<(i + 35)])
            }
            throw Failure("COSE key without coordinate")
        }
        return try P256.Signing.PublicKey(rawRepresentation: Data(try coordinate(0x21) + coordinate(0x22)))
    }

    private static func random32() -> Data { Data((0..<32).map { _ in UInt8.random(in: 0...255) }) }

    private static func check(_ ok: Bool, _ what: String) throws {
        guard ok else { throw Failure(what) }
        log("ok: \(what)")
    }

    private static func log(_ line: String) { print("M3BPasskeyTest: \(line)") }

    private struct Failure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    private actor MemoryServer {
        private var ciphers: [String: BitwardenSdk.Cipher] = [:]

        func save(_ cipher: BitwardenSdk.Cipher) -> String {
            let id = cipher.id ?? UUID().uuidString.lowercased()
            let c = cipher
            ciphers[id] = BitwardenSdk.Cipher(
                id: id, organizationId: c.organizationId, folderId: c.folderId, collectionIds: c.collectionIds, key: c.key,
                name: c.name, notes: c.notes, type: c.type, login: c.login, identity: c.identity, card: c.card,
                secureNote: c.secureNote, sshKey: c.sshKey, bankAccount: c.bankAccount, driversLicense: c.driversLicense,
                passport: c.passport, favorite: c.favorite, reprompt: c.reprompt, organizationUseTotp: c.organizationUseTotp,
                edit: c.edit, permissions: c.permissions, viewPassword: c.viewPassword, localData: c.localData,
                attachments: c.attachments, fields: c.fields, passwordHistory: c.passwordHistory, creationDate: c.creationDate,
                deletedDate: c.deletedDate, revisionDate: c.revisionDate, archivedDate: c.archivedDate, data: c.data, partialData: c.partialData
            )
            return id
        }

        func cipher(_ id: String) -> BitwardenSdk.Cipher? { ciphers[id] }
    }
}
#endif
