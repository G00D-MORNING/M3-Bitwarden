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
        var identities: [ASCredentialIdentity] = CredentialProviderBridge.shared.identities().map {
            ASPasswordCredentialIdentity(
                serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.domain, type: .domain),
                user: $0.user,
                recordIdentifier: $0.recordId
            )
        }
        identities += VaultService.shared.passkeys().map(passkeyIdentity)
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
        ASPasskeyCredentialIdentity(
            relyingPartyIdentifier: p.rpId,
            userName: p.userName.isEmpty ? p.itemName : p.userName,
            credentialID: p.credentialId,
            userHandle: p.userHandle,
            recordIdentifier: p.cipherId
        )
    }
}
