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
    func kotlinItem(folders: [String: String], passkeys: [PasskeyEntry] = []) -> VaultItem? {
        guard let id else { return nil }
        let uris = (login?.uris ?? []).compactMap(\.uri).filter { !$0.isEmpty }
        let kind: ItemType
        switch type {
        case .login: kind = .login
        case .card: kind = .card
        case .identity: kind = .identity
        case .sshKey: kind = .sshkey
        default: kind = .note
        }

        return VaultItem(
            id: id,
            name: name,
            type: kind,
            folder: folderId.flatMap { folders[$0] },
            folderId: folderId,
            favorite: favorite,
            notes: notes ?? "",
            login: login.map {
                LoginData(
                    username: $0.username ?? "",
                    password: $0.password ?? "",
                    uri: uris.first ?? "",
                    totpSecret: $0.totp,
                    additionalUris: Array(uris.dropFirst()),
                    passkeys: Int32($0.fido2Credentials?.count ?? 0),
                    passkeyDetails: passkeys.map {
                        PasskeyInfo(site: $0.rpId, userName: $0.userName, createdLabel: $0.created.map(Self.day.string(from:)) ?? "")
                    }
                )
            },
            card: card.map {
                CardData(cardholder: $0.cardholderName ?? "", brand: $0.brand ?? "", number: $0.number ?? "",
                         expMonth: $0.expMonth ?? "", expYear: $0.expYear ?? "", code: $0.code ?? "")
            },
            identity: identity.map { i in
                IdentityData(
                    title: i.title ?? "", firstName: i.firstName ?? "", middleName: i.middleName ?? "", lastName: i.lastName ?? "",
                    username: i.username ?? "", company: i.company ?? "", email: i.email ?? "", phone: i.phone ?? "",
                    address1: i.address1 ?? "", address2: i.address2 ?? "", address3: i.address3 ?? "",
                    postalCode: i.postalCode ?? "", city: i.city ?? "", state: i.state ?? "", country: i.country ?? "",
                    ssn: i.ssn ?? "", passportNumber: i.passportNumber ?? "", licenseNumber: i.licenseNumber ?? ""
                )
            },
            sshKey: sshKey.map { SshKeyData(publicKey: $0.publicKey, fingerprint: $0.fingerprint, privateKey: $0.privateKey) },
            fields: (fields ?? []).map { f in
                CustomField(name: f.name ?? "", value: f.value ?? "", type: Self.kotlinFieldType(f.type))
            },
            passwordHistory: (passwordHistory ?? []).map {
                PasswordHistoryEntry(password: $0.password, dateLabel: Self.relative.localizedString(for: $0.lastUsedDate, relativeTo: Date()))
            },
            updatedLabel: Self.relative.localizedString(for: revisionDate, relativeTo: Date()),
            revisionEpoch: Int64(revisionDate.timeIntervalSince1970),
            editable: edit,
            shared: organizationId != nil
        )
    }

    private static func kotlinFieldType(_ t: BitwardenSdk.FieldType) -> Shared.CustomFieldType {
        switch t {
        case .text: .text
        case .hidden: .hidden
        case .boolean: .boolean
        case .linked: .linked
        }
    }

    private static func sdkFieldType(_ t: Shared.CustomFieldType) -> BitwardenSdk.FieldType {
        switch t {
        case .hidden: .hidden
        case .boolean: .boolean
        case .linked: .linked
        default: .text
        }
    }

    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.dateFormat = "dd.MM.yyyy"
        return f
    }()

    private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.unitsStyle = .full
        return f
    }()

    /// Applies what the Kotlin UI can change to this decrypted cipher, keeping everything else
    /// (attachments, passkeys, URI match rules, linked fields) intact.
    func applying(_ item: VaultItem, folderId newFolderId: String?, now: Date = Date()) -> CipherView {
        var history = passwordHistory ?? []
        var newLogin = login
        if let l = login, let k = item.login {
            var revision = l.passwordRevisionDate
            if let old = l.password, !old.isEmpty, old != k.password {
                history.insert(PasswordHistoryView(password: old, lastUsedDate: now), at: 0)
                revision = now
            }
            let existing = Dictionary((l.uris ?? []).compactMap { u in u.uri.map { ($0.lowercased(), u) } }, uniquingKeysWith: { a, _ in a })
            let uris = (k.allUris as [String]).map { existing[$0.lowercased()] ?? LoginUriView(uri: $0, match: nil, uriChecksum: nil) }
            newLogin = BitwardenSdk.LoginView(
                username: k.username.isEmpty ? nil : k.username,
                password: k.password.isEmpty ? nil : k.password,
                passwordRevisionDate: revision,
                uris: uris,
                totp: k.totpSecret,
                autofillOnPageLoad: l.autofillOnPageLoad,
                httpAuthenticationEnabled: l.httpAuthenticationEnabled,
                fido2Credentials: l.fido2Credentials
            )
        }
        // Hidden custom fields keep their history too, like the official clients.
        let oldHidden = Dictionary((fields ?? []).filter { $0.type == .hidden }.compactMap { f in f.name.map { ($0, f.value) } }, uniquingKeysWith: { a, _ in a })
        for f in item.fields where f.type == .hidden {
            if let old = oldHidden[f.name] ?? nil, !old.isEmpty, old != f.value {
                history.insert(PasswordHistoryView(password: "\(f.name): \(old)", lastUsedDate: now), at: 0)
            }
        }
        let linked = (fields ?? []).filter { $0.type == .linked }
        let newFields = item.fields.filter { $0.type != .linked }.map {
            FieldView(name: $0.name, value: $0.value, type: Self.sdkFieldType($0.type), linkedId: nil)
        } + linked

        return CipherView(
            id: id, organizationId: organizationId, folderId: newFolderId, collectionIds: collectionIds, key: key,
            name: item.name, notes: item.notes.isEmpty ? nil : item.notes, type: type,
            login: newLogin,
            identity: item.identity.map(Self.identityView) ?? identity,
            card: item.card.map(Self.cardView) ?? card,
            secureNote: secureNote, sshKey: item.sshKey.map(Self.sshKeyView) ?? sshKey,
            bankAccount: bankAccount, driversLicense: driversLicense, passport: passport,
            favorite: item.favorite, reprompt: reprompt, organizationUseTotp: organizationUseTotp, edit: edit,
            permissions: permissions, viewPassword: viewPassword, localData: localData, attachments: attachments,
            attachmentDecryptionFailures: attachmentDecryptionFailures, fields: newFields.isEmpty ? nil : newFields,
            passwordHistory: history.isEmpty ? nil : Array(history.prefix(5)),
            creationDate: creationDate, deletedDate: deletedDate, revisionDate: revisionDate, archivedDate: archivedDate,
            partial: partial
        )
    }

    /// A new item created in the app or saved through autofill.
    static func new(from item: VaultItem, folderId: String?, now: Date = Date()) -> CipherView {
        let k = item.login
        let type: CipherType
        switch item.type {
        case .card: type = .card
        case .identity: type = .identity
        case .note: type = .secureNote
        case .sshkey: type = .sshKey
        default: type = .login
        }
        let fields = item.fields.filter { $0.type != .linked }.map {
            FieldView(name: $0.name, value: $0.value, type: sdkFieldType($0.type), linkedId: nil)
        }
        return CipherView(
            id: nil, organizationId: nil, folderId: folderId, collectionIds: [], key: nil,
            name: item.name, notes: item.notes.isEmpty ? nil : item.notes, type: type,
            login: type != .login ? nil : BitwardenSdk.LoginView(
                username: k?.username.isEmpty == false ? k?.username : nil,
                password: k?.password.isEmpty == false ? k?.password : nil,
                passwordRevisionDate: nil,
                uris: (k?.allUris as [String]? ?? []).map { LoginUriView(uri: $0, match: nil, uriChecksum: nil) },
                totp: k?.totpSecret,
                autofillOnPageLoad: nil,
                httpAuthenticationEnabled: nil,
                fido2Credentials: nil
            ),
            identity: type == .identity ? item.identity.map(identityView) : nil,
            card: type == .card ? item.card.map(cardView) : nil,
            secureNote: type == .secureNote ? SecureNoteView(type: .generic) : nil,
            sshKey: type == .sshKey ? item.sshKey.map(sshKeyView) : nil,
            bankAccount: nil, driversLicense: nil, passport: nil,
            favorite: item.favorite, reprompt: .none, organizationUseTotp: false, edit: true, permissions: nil,
            viewPassword: true, localData: nil, attachments: nil, attachmentDecryptionFailures: nil,
            fields: fields.isEmpty ? nil : fields,
            passwordHistory: nil, creationDate: now, deletedDate: nil, revisionDate: now, archivedDate: nil, partial: false
        )
    }

    private static func opt(_ s: String) -> String? { s.isEmpty ? nil : s }

    private static func cardView(_ c: CardData) -> CardView {
        CardView(cardholderName: opt(c.cardholder), expMonth: opt(c.expMonth), expYear: opt(c.expYear), code: opt(c.code), brand: opt(c.brand), number: opt(c.number))
    }

    private static func identityView(_ i: IdentityData) -> IdentityView {
        IdentityView(
            title: opt(i.title), firstName: opt(i.firstName), middleName: opt(i.middleName), lastName: opt(i.lastName),
            address1: opt(i.address1), address2: opt(i.address2), address3: opt(i.address3), city: opt(i.city), state: opt(i.state),
            postalCode: opt(i.postalCode), country: opt(i.country), company: opt(i.company), email: opt(i.email), phone: opt(i.phone),
            ssn: opt(i.ssn), username: opt(i.username), passportNumber: opt(i.passportNumber), licenseNumber: opt(i.licenseNumber)
        )
    }

    private static func sshKeyView(_ k: SshKeyData) -> SshKeyView {
        SshKeyView(privateKey: k.privateKey, publicKey: k.publicKey, fingerprint: k.fingerprint)
    }
}
