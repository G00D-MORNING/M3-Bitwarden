import AuthenticationServices
import Shared

/// Publishes login metadata (domain + username, never passwords) and passkey metadata (site,
/// username, credential id) to iOS, so suggestions appear above the keyboard and in the passkey
/// sheet without opening the extension first.
/// Called after every sync, after a link was accepted and after a passkey was created.
enum CredentialIdentitySync {
    @MainActor
    static func run() async {
        // Only a decrypted vault knows the logins; never replace the store with an empty list.
        guard CredentialProviderBridge.shared.isUnlocked else { return }
        let store = ASCredentialIdentityStore.shared
        let state = await store.state()
        guard state.isEnabled else { return }
        let logins = CredentialProviderBridge.shared.identities()
        var identities: [ASCredentialIdentity] = logins.map {
            let identity = ASPasswordCredentialIdentity(
                serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.domain, type: .domain),
                user: $0.user,
                recordIdentifier: $0.recordId
            )
            // The login picked last for a site comes first above the keyboard.
            identity.rank = Int($0.rank)
            return identity
        }
        identities += VaultService.shared.passkeys().map(passkeyIdentity)
        // Logins with a code (e.g. linked from the Proton Authenticator): iOS then offers the
        // code above the keyboard in 2FA fields of that site.
        if #available(iOS 18.0, *) {
            identities += logins.filter(\.hasTotp).map {
                let identity = ASOneTimeCodeCredentialIdentity(
                    serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.domain, type: .domain),
                    label: $0.user.isEmpty ? $0.domain : $0.user,
                    recordIdentifier: $0.recordId
                )
                identity.rank = Int($0.rank)
                return identity
            }
        }
        try? await store.replaceCredentialIdentities(identities)
    }

    /// Adds one new passkey right away (the next sync rewrites the whole list anyway).
    static func add(_ passkey: PasskeyEntry) async {
        let store = ASCredentialIdentityStore.shared
        guard await store.state().isEnabled else { return }
        try? await store.saveCredentialIdentities([passkeyIdentity(passkey)])
    }

    /// On lock or logout: nothing about the vault stays visible to the system.
    static func clear() async {
        try? await ASCredentialIdentityStore.shared.removeAllCredentialIdentities()
    }

    private static func passkeyIdentity(_ p: PasskeyEntry) -> ASPasskeyCredentialIdentity {
        let identity = ASPasskeyCredentialIdentity(
            relyingPartyIdentifier: p.rpId,
            userName: p.userName.isEmpty ? p.itemName : p.userName,
            credentialID: p.credentialId,
            userHandle: p.userHandle,
            recordIdentifier: p.cipherId
        )
        identity.rank = Int(CredentialProviderBridge.shared.rank(id: p.cipherId))
        return identity
    }
}
