import BitwardenSdk
import Foundation
import Shared

// Conversions between the server JSON, the Bitwarden SDK types and the Kotlin UI model.
// Field-for-field ports of the official Bitwarden iOS app's mappers (GPL-3.0).

// MARK: Server JSON → SDK (encrypted)

extension BitwardenSdk.Cipher {
    init(model m: CipherModel) {
        self.init(
            id: m.id,
            organizationId: m.organizationId,
            folderId: m.folderId,
            collectionIds: m.collectionIds ?? [],
            key: m.key,
            name: m.name,
            notes: m.notes,
            type: .init(serverType: m.type),
            login: m.login.map(BitwardenSdk.Login.init(model:)),
            identity: m.identity.map(BitwardenSdk.Identity.init(model:)),
            card: m.card.map(BitwardenSdk.Card.init(model:)),
            secureNote: m.secureNote.map { _ in BitwardenSdk.SecureNote(type: .generic) },
            sshKey: m.sshKey.flatMap { k in k.privateKey.map { BitwardenSdk.SshKey(privateKey: $0, publicKey: k.publicKey, fingerprint: k.keyFingerprint) } },
            bankAccount: nil,
            driversLicense: nil,
            passport: nil,
            favorite: m.favorite ?? false,
            reprompt: m.reprompt == 1 ? .password : .none,
            organizationUseTotp: m.organizationUseTotp ?? false,
            edit: m.edit ?? true,
            permissions: m.permissions.map { CipherPermissions(delete: $0.delete, restore: $0.restore) },
            viewPassword: m.viewPassword ?? true,
            localData: nil,
            attachments: m.attachments?.map {
                BitwardenSdk.Attachment(id: $0.id, url: $0.url, size: $0.size, sizeName: $0.sizeName, fileName: $0.fileName, key: $0.key)
            },
            fields: m.fields?.map { BitwardenSdk.Field(name: $0.name, value: $0.value, type: .init(serverType: $0.type), linkedId: $0.linkedId) },
            passwordHistory: m.passwordHistory?.map { BitwardenSdk.PasswordHistory(password: $0.password, lastUsedDate: $0.lastUsedDate) },
            creationDate: m.creationDate ?? m.revisionDate,
            deletedDate: m.deletedDate,
            revisionDate: m.revisionDate,
            archivedDate: m.archivedDate,
            data: nil,
            partialData: nil
        )
    }
}

extension BitwardenSdk.Login {
    init(model m: LoginModel) {
        self.init(
            username: m.username,
            password: m.password,
            passwordRevisionDate: m.passwordRevisionDate,
            uris: m.uris?.map { BitwardenSdk.LoginUri(uri: $0.uri, match: $0.match.flatMap(UriMatchType.init(serverValue:)), uriChecksum: $0.uriChecksum) },
            totp: m.totp,
            autofillOnPageLoad: m.autofillOnPageLoad,
            fido2Credentials: m.fido2Credentials?.map {
                BitwardenSdk.Fido2Credential(
                    credentialId: $0.credentialId, keyType: $0.keyType, keyAlgorithm: $0.keyAlgorithm, keyCurve: $0.keyCurve,
                    keyValue: $0.keyValue, rpId: $0.rpId, userHandle: $0.userHandle, userName: $0.userName, counter: $0.counter,
                    rpName: $0.rpName, userDisplayName: $0.userDisplayName, discoverable: $0.discoverable, creationDate: $0.creationDate
                )
            }
        )
    }
}

extension BitwardenSdk.Card {
    init(model m: CardModel) {
        self.init(cardholderName: m.cardholderName, expMonth: m.expMonth, expYear: m.expYear, code: m.code, brand: m.brand, number: m.number)
    }
}

extension BitwardenSdk.Identity {
    init(model m: IdentityModel) {
        self.init(
            title: m.title, firstName: m.firstName, middleName: m.middleName, lastName: m.lastName,
            address1: m.address1, address2: m.address2, address3: m.address3, city: m.city, state: m.state,
            postalCode: m.postalCode, country: m.country, company: m.company, email: m.email, phone: m.phone,
            ssn: m.ssn, username: m.username, passportNumber: m.passportNumber, licenseNumber: m.licenseNumber
        )
    }
}

extension BitwardenSdk.CipherType {
    init(serverType: Int) {
        switch serverType {
        case 2: self = .secureNote
        case 3: self = .card
        case 4: self = .identity
        case 5: self = .sshKey
        default: self = .login
        }
    }

    var serverValue: Int {
        switch self {
        case .login: 1
        case .secureNote: 2
        case .card: 3
        case .identity: 4
        case .sshKey: 5
        default: 2
        }
    }
}

extension BitwardenSdk.FieldType {
    init(serverType: Int) {
        switch serverType {
        case 1: self = .hidden
        case 2: self = .boolean
        case 3: self = .linked
        default: self = .text
        }
    }

    var serverValue: Int {
        switch self {
        case .text: 0
        case .hidden: 1
        case .boolean: 2
        case .linked: 3
        }
    }
}

extension BitwardenSdk.UriMatchType {
    init?(serverValue: Int) {
        switch serverValue {
        case 0: self = .domain
        case 1: self = .host
        case 2: self = .startsWith
        case 3: self = .exact
        case 4: self = .regularExpression
        case 5: self = .never
        default: return nil
        }
    }

    var serverValue: Int {
        switch self {
        case .domain: 0
        case .host: 1
        case .startsWith: 2
        case .exact: 3
        case .regularExpression: 4
        case .never: 5
        }
    }
}

extension BitwardenSdk.Kdf {
    init(model: KdfModel) {
        if model.kdfType == 1 {
            self = .argon2id(
                iterations: UInt32(model.iterations),
                memory: UInt32(model.memory ?? 64),
                parallelism: UInt32(model.parallelism ?? 4)
            )
        } else {
            self = .pbkdf2(iterations: UInt32(model.iterations))
        }
    }
}

// MARK: SDK (encrypted) → request body

extension CipherRequestModel {
    init(cipher c: BitwardenSdk.Cipher) {
        self.init(
            type: c.type.serverValue,
            organizationId: c.organizationId,
            folderId: c.folderId,
            key: c.key,
            name: c.name,
            notes: c.notes,
            favorite: c.favorite,
            reprompt: c.reprompt == .password ? 1 : 0,
            login: c.login.map { l in
                LoginModel(
                    username: l.username,
                    password: l.password,
                    passwordRevisionDate: l.passwordRevisionDate,
                    uris: l.uris?.map { LoginUriModel(uri: $0.uri, match: $0.match?.serverValue, uriChecksum: $0.uriChecksum) },
                    totp: l.totp,
                    autofillOnPageLoad: l.autofillOnPageLoad,
                    fido2Credentials: l.fido2Credentials?.map {
                        Fido2CredentialModel(
                            credentialId: $0.credentialId, keyType: $0.keyType, keyAlgorithm: $0.keyAlgorithm, keyCurve: $0.keyCurve,
                            keyValue: $0.keyValue, rpId: $0.rpId, userHandle: $0.userHandle, userName: $0.userName, counter: $0.counter,
                            rpName: $0.rpName, userDisplayName: $0.userDisplayName, discoverable: $0.discoverable, creationDate: $0.creationDate
                        )
                    }
                )
            },
            card: c.card.map {
                CardModel(cardholderName: $0.cardholderName, brand: $0.brand, number: $0.number, expMonth: $0.expMonth, expYear: $0.expYear, code: $0.code)
            },
            identity: c.identity.map {
                IdentityModel(
                    title: $0.title, firstName: $0.firstName, middleName: $0.middleName, lastName: $0.lastName,
                    address1: $0.address1, address2: $0.address2, address3: $0.address3, city: $0.city, state: $0.state,
                    postalCode: $0.postalCode, country: $0.country, company: $0.company, email: $0.email, phone: $0.phone,
                    ssn: $0.ssn, username: $0.username, passportNumber: $0.passportNumber, licenseNumber: $0.licenseNumber
                )
            },
            secureNote: c.secureNote.map { _ in SecureNoteModel(type: 0) },
            sshKey: c.sshKey.map { SshKeyModel(privateKey: $0.privateKey, publicKey: $0.publicKey, keyFingerprint: $0.fingerprint) },
            fields: c.fields?.map { FieldModel(name: $0.name, value: $0.value, type: $0.type.serverValue, linkedId: $0.linkedId) },
            passwordHistory: c.passwordHistory?.map { PasswordHistoryModel(password: $0.password, lastUsedDate: $0.lastUsedDate) },
            lastKnownRevisionDate: c.id == nil ? nil : c.revisionDate
        )
    }
}

// MARK: SDK (decrypted) → Kotlin UI model

extension CipherView {
    /// The UI model; [folders] maps folder ids to their decrypted names.
    func kotlinItem(folders: [String: String]) -> VaultItem? {
        guard let id else { return nil }
        let uris = (login?.uris ?? []).compactMap(\.uri).filter { !$0.isEmpty }
        let kind: ItemType
        switch type {
        case .login: kind = .login
        case .card: kind = .card
        case .identity: kind = .identity
        case .sshKey: kind = .sshKey
        default: kind = .note
        }
        let fullName = [identity?.title, identity?.firstName, identity?.middleName, identity?.lastName]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        let address = [
            identity?.address1, identity?.address2, identity?.address3,
            [identity?.postalCode, identity?.city].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " "),
            identity?.country,
        ].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")

        return VaultItem(
            id: id,
            name: name,
            type: kind,
            folder: folderId.flatMap { folders[$0] },
            favorite: favorite,
            notes: notes ?? "",
            login: login.map {
                LoginData(
                    username: $0.username ?? "",
                    password: $0.password ?? "",
                    uri: uris.first ?? "",
                    totpSecret: $0.totp,
                    additionalUris: Array(uris.dropFirst())
                )
            },
            card: card.map {
                CardData(cardholder: $0.cardholderName ?? "", brand: $0.brand ?? "", number: $0.number ?? "",
                         expMonth: $0.expMonth ?? "", expYear: $0.expYear ?? "", code: $0.code ?? "")
            },
            identity: identity.map {
                IdentityData(fullName: fullName, email: $0.email ?? "", phone: $0.phone ?? "", address: address, company: $0.company ?? "")
            },
            sshKey: sshKey.map { SshKeyData(publicKey: $0.publicKey, fingerprint: $0.fingerprint, privateKey: $0.privateKey) },
            updatedLabel: Self.relative.localizedString(for: revisionDate, relativeTo: Date())
        )
    }

    private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.unitsStyle = .full
        return f
    }()

    /// Applies what the Kotlin UI can change to this decrypted cipher, keeping everything else
    /// (custom fields, attachments, history, URI match rules) intact.
    func applying(_ item: VaultItem, now: Date = Date()) -> CipherView {
        var newLogin = login
        if let l = login, let k = item.login {
            var history = passwordHistory ?? []
            var revision = l.passwordRevisionDate
            if let old = l.password, !old.isEmpty, old != k.password {
                history.insert(PasswordHistoryView(password: old, lastUsedDate: now), at: 0)
                revision = now
            }
            let existing = Dictionary((l.uris ?? []).compactMap { u in u.uri.map { ($0.lowercased(), u) } }, uniquingKeysWith: { a, _ in a })
            let uris = (k.allUris as [String]).map { existing[$0.lowercased()] ?? LoginUriView(uri: $0, match: nil, uriChecksum: nil) }
            newLogin = LoginView(
                username: k.username.isEmpty ? nil : k.username,
                password: k.password.isEmpty ? nil : k.password,
                passwordRevisionDate: revision,
                uris: uris,
                totp: k.totpSecret,
                autofillOnPageLoad: l.autofillOnPageLoad,
                httpAuthenticationEnabled: l.httpAuthenticationEnabled,
                fido2Credentials: l.fido2Credentials
            )
            return copy(name: item.name, notes: item.notes, favorite: item.favorite, login: newLogin, passwordHistory: Array(history.prefix(5)))
        }
        return copy(name: item.name, notes: item.notes, favorite: item.favorite, login: newLogin, passwordHistory: passwordHistory)
    }

    private func copy(name: String, notes: String, favorite: Bool, login: LoginView?, passwordHistory: [PasswordHistoryView]?) -> CipherView {
        CipherView(
            id: id, organizationId: organizationId, folderId: folderId, collectionIds: collectionIds, key: key,
            name: name, notes: notes.isEmpty ? nil : notes, type: type, login: login, identity: identity, card: card,
            secureNote: secureNote, sshKey: sshKey, bankAccount: bankAccount, driversLicense: driversLicense, passport: passport,
            favorite: favorite, reprompt: reprompt, organizationUseTotp: organizationUseTotp, edit: edit,
            permissions: permissions, viewPassword: viewPassword, localData: localData, attachments: attachments,
            attachmentDecryptionFailures: attachmentDecryptionFailures, fields: fields, passwordHistory: passwordHistory,
            creationDate: creationDate, deletedDate: deletedDate, revisionDate: revisionDate, archivedDate: archivedDate,
            partial: partial
        )
    }

    /// A new login created in the app or saved through autofill.
    static func newLogin(from item: VaultItem, now: Date = Date()) -> CipherView {
        let k = item.login
        return CipherView(
            id: nil, organizationId: nil, folderId: nil, collectionIds: [], key: nil,
            name: item.name, notes: item.notes.isEmpty ? nil : item.notes, type: .login,
            login: LoginView(
                username: k?.username.isEmpty == false ? k?.username : nil,
                password: k?.password.isEmpty == false ? k?.password : nil,
                passwordRevisionDate: nil,
                uris: (k?.allUris as [String]? ?? []).map { LoginUriView(uri: $0, match: nil, uriChecksum: nil) },
                totp: k?.totpSecret,
                autofillOnPageLoad: nil,
                httpAuthenticationEnabled: nil,
                fido2Credentials: nil
            ),
            identity: nil, card: nil, secureNote: nil, sshKey: nil, bankAccount: nil, driversLicense: nil, passport: nil,
            favorite: item.favorite, reprompt: .none, organizationUseTotp: false, edit: true, permissions: nil,
            viewPassword: true, localData: nil, attachments: nil, attachmentDecryptionFailures: nil, fields: nil,
            passwordHistory: nil, creationDate: now, deletedDate: nil, revisionDate: now, archivedDate: nil, partial: false
        )
    }
}
