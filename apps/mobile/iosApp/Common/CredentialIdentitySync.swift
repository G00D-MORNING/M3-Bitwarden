import AuthenticationServices
import Shared

/// Publishes login metadata (domain + username, never passwords) to iOS so suggestions
/// appear above the keyboard without opening the extension first.
/// Called after every sync and after a link was accepted.
enum CredentialIdentitySync {
    static func run() async {
        // Only a decrypted vault knows the logins; never replace the store with an empty list.
        guard CredentialProviderBridge.shared.isUnlocked else { return }
        let store = ASCredentialIdentityStore.shared
        let state = await store.state()
        guard state.isEnabled else { return }
        let identities: [ASCredentialIdentity] = CredentialProviderBridge.shared.identities().map {
            ASPasswordCredentialIdentity(
                serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.domain, type: .domain),
                user: $0.user,
                recordIdentifier: $0.recordId
            )
        }
        try? await store.replaceCredentialIdentities(identities)
    }

    /// On lock or logout: nothing about the vault stays visible to the system.
    static func clear() async {
        try? await ASCredentialIdentityStore.shared.removeAllCredentialIdentities()
    }
}
