import AuthenticationServices
import Shared
import SwiftUI

/// AutoFill credential provider: passwords (iOS 17+), one-time codes and free text (iOS 18+).
///
/// iOS detects the fields itself; this extension ranks logins for the domains iOS reports
/// (via the shared Kotlin matcher), unlocks with Face ID / Touch ID and offers to remember
/// a link after a manual pick, exactly like the Android service.
final class CredentialProviderViewController: ASCredentialProviderViewController {
    private let bridge = CredentialProviderBridge.shared
    private var identifiers: [String] = []

    // MARK: Passwords

    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        identifiers = serviceIdentifiers.map(\.identifier)
        present(mode: .password)
    }

    override func provideCredentialWithoutUserInteraction(for credentialRequest: any ASCredentialRequest) {
        // Only fill silently while the vault is open in memory; otherwise iOS shows our UI.
        guard bridge.isUnlocked,
              let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity,
              let id = identity.recordIdentifier,
              let password = bridge.password(id: id)
        else {
            extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.userInteractionRequired.rawValue))
            return
        }
        bridge.recordUse(id: id, nowEpochMs: Self.nowMs)
        extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: identity.user, password: password))
    }

    override func prepareInterfaceToProvideCredential(for credentialRequest: any ASCredentialRequest) {
        guard let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity, let id = identity.recordIdentifier else {
            return cancel()
        }
        // Show the unlock screen right away (Face ID, or the master password if that fails).
        host(ExtensionRoot(mode: .password, identifiers: [identity.serviceIdentifier.identifier], singleId: id, bridge: bridge, onDone: { _ in self.cancel() }) { ok in
            guard ok, let password = self.bridge.password(id: id) else { return self.cancel() }
            self.bridge.recordUse(id: id, nowEpochMs: Self.nowMs)
            self.extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: identity.user, password: password))
        })
    }

    // MARK: One-time codes (iOS 18+)

    @available(iOS 18.0, *)
    override func prepareOneTimeCodeCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        identifiers = serviceIdentifiers.map(\.identifier)
        present(mode: .oneTimeCode)
    }

    // MARK: Arbitrary text (iOS 18+)

    @available(iOS 18.0, *)
    override func prepareInterfaceForUserChoosingTextToInsert() {
        identifiers = []
        present(mode: .text)
    }

    override func prepareInterfaceForExtensionConfiguration() {
        Task { await CredentialIdentitySync.run() }
        extensionContext.completeExtensionConfigurationRequest()
    }

    // MARK: UI

    private func present(mode: ExtensionRoot.Mode) {
        host(ExtensionRoot(mode: mode, identifiers: identifiers, singleId: nil, bridge: bridge, onDone: { result in
            self.finish(result, mode: mode)
        }) { _ in })
    }

    private func host(_ view: ExtensionRoot) {
        children.forEach { $0.view.removeFromSuperview(); $0.removeFromParent() }
        let host = UIHostingController(rootView: view)
        addChild(host)
        host.view.frame = self.view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    private func finish(_ result: ExtensionRoot.Result?, mode: ExtensionRoot.Mode) {
        guard let result else { return cancel() }
        if let offer = result.acceptedLink { bridge.acceptLink(offer: offer); Task { await CredentialIdentitySync.run() } }
        bridge.recordUse(id: result.row.id, nowEpochMs: Self.nowMs)
        switch mode {
        case .password:
            guard let password = bridge.password(id: result.row.id) else { return cancel() }
            extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: result.row.username, password: password))
        case .oneTimeCode:
            if #available(iOS 18.0, *), let code = bridge.totp(id: result.row.id, epochSeconds: Int64(Date().timeIntervalSince1970)) {
                extensionContext.completeOneTimeCodeRequest(using: ASOneTimeCodeCredential(code: code))
            } else {
                cancel()
            }
        case .text:
            if #available(iOS 18.0, *), let text = result.text {
                extensionContext.completeRequest(withTextToInsert: text)
            } else {
                cancel()
            }
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Nothing decrypted stays in the extension process after the sheet closes.
        Task { @MainActor in VaultService.shared.lock() }
    }

    private func cancel() {
        extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.userCanceled.rawValue))
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
