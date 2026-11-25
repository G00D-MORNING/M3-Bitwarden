import AuthenticationServices
import BitwardenSdk
import Shared
import SwiftUI

/// AutoFill credential provider: passwords and passkeys (iOS 17+), one-time codes and free text
/// (iOS 18+).
///
/// iOS detects the fields itself; this extension ranks logins for the domains iOS reports
/// (via the shared Kotlin matcher), unlocks with Face ID / Touch ID and offers to remember
/// a link after a manual pick, exactly like the Android service. Passkeys from Vaultwarden are
/// signed and created by the Bitwarden SDK (see Passkeys.swift); every passkey use needs Face ID
/// or the master password, which is the user verification the site asks for.
final class CredentialProviderViewController: ASCredentialProviderViewController {
    private let bridge = CredentialProviderBridge.shared
    private var identifiers: [String] = []

    // MARK: Passwords

    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        identifiers = serviceIdentifiers.map(\.identifier)
        present(mode: .password)
    }

    override func provideCredentialWithoutUserInteraction(for credentialRequest: any ASCredentialRequest) {
        // Passkeys always go through Face ID; passwords and codes fill silently only while the
        // vault is open in memory. Otherwise iOS shows our UI (prepareInterfaceToProvideCredential).
        if #available(iOS 18.0, *), let otc = credentialRequest.credentialIdentity as? ASOneTimeCodeCredentialIdentity {
            guard bridge.isUnlocked, let id = otc.recordIdentifier, let code = currentCode(id) else {
                extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.userInteractionRequired.rawValue))
                return
            }
            extensionContext.completeOneTimeCodeRequest(using: ASOneTimeCodeCredential(code: code))
            return
        }
        guard bridge.isUnlocked,
              let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity,
              let id = identity.recordIdentifier,
              let password = bridge.password(id: id)
        else {
            extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.userInteractionRequired.rawValue))
            return
        }
        bridge.recordUse(id: id, nowEpochMs: Self.nowMs)
        afterLearning { self.extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: identity.user, password: password)) }
    }

    override func prepareInterfaceToProvideCredential(for credentialRequest: any ASCredentialRequest) {
        if let request = credentialRequest as? ASPasskeyCredentialRequest {
            return providePasskey(request)
        }
        if #available(iOS 18.0, *), let otc = credentialRequest.credentialIdentity as? ASOneTimeCodeCredentialIdentity {
            guard let id = otc.recordIdentifier else { return cancel() }
            host(ExtensionRoot(mode: .oneTimeCode, identifiers: [otc.serviceIdentifier.identifier], singleId: id, bridge: bridge, onDone: { _ in self.cancel() }) { ok in
                guard ok, let code = self.currentCode(id) else { return self.cancel() }
                self.extensionContext.completeOneTimeCodeRequest(using: ASOneTimeCodeCredential(code: code))
            })
            return
        }
        guard let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity, let id = identity.recordIdentifier else {
            return cancel()
        }
        // Show the unlock screen right away (Face ID, or the master password if that fails).
        host(ExtensionRoot(mode: .password, identifiers: [identity.serviceIdentifier.identifier], singleId: id, bridge: bridge, onDone: { _ in self.cancel() }) { ok in
            guard ok, let password = self.bridge.password(id: id) else { return self.cancel() }
            self.bridge.recordUse(id: id, nowEpochMs: Self.nowMs)
            self.afterLearning { self.extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: identity.user, password: password)) }
        })
    }

    // MARK: Passkeys: sign in

    /// The user picked one of our passkeys in the system sheet or above the keyboard.
    private func providePasskey(_ request: ASPasskeyCredentialRequest) {
        guard let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity else { return cancel() }
        let assertion = PasskeyAssertionRequest(
            rpId: identity.relyingPartyIdentifier,
            clientDataHash: request.clientDataHash,
            allowedCredentials: [identity.credentialID],
            userVerification: Uv(request.userVerificationPreference)
        )
        host(ExtensionRoot(mode: .passkey, identifiers: [identity.relyingPartyIdentifier], singleId: identity.recordIdentifier ?? "", bridge: bridge, onDone: { _ in self.cancel() }) { ok in
            guard ok else { return self.cancel() }
            self.sign(assertion, cipherId: identity.recordIdentifier)
        })
    }

    /// "Passkey or password" list for a site (the user chose our app in the passkey sheet).
    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier], requestParameters: ASPasskeyCredentialRequestParameters) {
        identifiers = [requestParameters.relyingPartyIdentifier] + serviceIdentifiers.map(\.identifier)
        let assertion = PasskeyAssertionRequest(
            rpId: requestParameters.relyingPartyIdentifier,
            clientDataHash: requestParameters.clientDataHash,
            allowedCredentials: requestParameters.allowedCredentials,
            userVerification: Uv(requestParameters.userVerificationPreference)
        )
        host(ExtensionRoot(mode: .passkey, identifiers: identifiers, singleId: nil, passkeySite: assertion.rpId, allowedPasskeys: assertion.allowedCredentials, bridge: bridge, onDone: { result in
            guard let result else { return self.cancel() }
            if let passkey = result.passkey {
                self.sign(assertion, cipherId: passkey.cipherId)
            } else {
                self.finish(result, mode: .password)
            }
        }) { _ in })
    }

    private func sign(_ request: PasskeyAssertionRequest, cipherId: String?) {
        work(title: "Anmelden bei \(request.rpId)") {
            let result = try await VaultService.shared.passkeyAssertion(request, cipherId: cipherId)
            if let id = result.selectedCredential.cipher.id {
                self.bridge.recordUse(id: id, nowEpochMs: Self.nowMs)
                await CredentialIdentitySync.run()
            }
            _ = await self.extensionContext.completeAssertionRequest(using: ASPasskeyAssertionCredential(
                userHandle: result.userHandle,
                relyingParty: request.rpId,
                signature: result.signature,
                clientDataHash: request.clientDataHash,
                authenticatorData: result.authenticatorData,
                credentialID: result.credentialId
            ))
        }
    }

    // MARK: Passkeys: create

    override func prepareInterface(forPasskeyRegistration registrationRequest: any ASCredentialRequest) {
        guard let request = registrationRequest as? ASPasskeyCredentialRequest,
              let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity
        else { return cancel() }
        var excluded: [Data] = []
        if #available(iOS 18.0, *) { excluded = request.excludedCredentials?.map(\.credentialID) ?? [] }
        let registration = PasskeyRegistrationRequest(
            rpId: identity.relyingPartyIdentifier,
            rpName: nil,
            userName: identity.userName,
            userDisplayName: identity.userName,
            userHandle: identity.userHandle,
            clientDataHash: request.clientDataHash,
            algorithms: request.supportedAlgorithms.map { Int64($0.rawValue) },
            excludedCredentials: excluded,
            userVerification: Uv(request.userVerificationPreference)
        )
        identifiers = [registration.rpId]
        host(ExtensionRoot(mode: .newPasskey, identifiers: identifiers, singleId: nil, passkeySite: registration.rpId, newPasskeyUser: registration.userName, bridge: bridge, onDone: { result in
            guard let result else { return self.cancel() }
            self.create(registration, in: result.passkeyTarget)
        }) { _ in })
    }

    private func create(_ request: PasskeyRegistrationRequest, in cipherId: String?) {
        work(title: "Passkey für \(request.rpId) wird gespeichert") {
            let (result, savedId) = try await VaultService.shared.passkeyRegistration(request, cipherId: cipherId)
            await CredentialIdentitySync.add(PasskeyEntry(
                cipherId: savedId, credentialId: result.credentialId, rpId: request.rpId,
                userName: request.userName, userHandle: request.userHandle, itemName: request.rpName ?? request.rpId
            ))
            _ = await self.extensionContext.completeRegistrationRequest(using: ASPasskeyRegistrationCredential(
                relyingParty: request.rpId,
                clientDataHash: request.clientDataHash,
                credentialID: result.credentialId,
                attestationObject: result.attestationObject
            ))
        }
    }

    /// Runs a passkey operation behind a progress screen; a failure stays on screen until closed.
    private func work(title: String, _ operation: @escaping @MainActor () async throws -> Void) {
        hostView(PasskeyProgressView(title: title, failure: nil) { self.cancel() })
        Task { @MainActor in
            do {
                try await operation()
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                self.hostView(PasskeyProgressView(title: title, failure: message) {
                    self.extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.failed.rawValue))
                })
            }
        }
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
        hostView(view)
    }

    private func hostView(_ view: some View) {
        children.forEach { $0.view.removeFromSuperview(); $0.removeFromParent() }
        let host = UIHostingController(rootView: view)
        addChild(host)
        host.view.frame = self.view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    private func finish(_ result: ExtensionRoot.Result?, mode: ExtensionRoot.Mode) {
        guard let result, let row = result.row else { return cancel() }
        if let offer = result.acceptedLink { bridge.acceptLink(offer: offer); Task { await CredentialIdentitySync.run() } }
        bridge.recordUse(id: row.id, nowEpochMs: Self.nowMs)
        afterLearning { self.complete(result, row: row, mode: mode) }
    }

    private func complete(_ result: ExtensionRoot.Result, row: ProviderRow, mode: ExtensionRoot.Mode) {
        switch mode {
        case .password, .passkey, .newPasskey:
            guard let password = bridge.password(id: row.id) else { return cancel() }
            extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: row.username, password: password))
        case .oneTimeCode:
            if #available(iOS 18.0, *), let code = bridge.totp(id: row.id, epochSeconds: Int64(Date().timeIntervalSince1970)) {
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

    /// Hands iOS the new suggestion order (the login just picked first) before the sheet closes.
    private func afterLearning(_ complete: @escaping () -> Void) {
        Task { @MainActor in
            await CredentialIdentitySync.run()
            complete()
        }
    }

    private func currentCode(_ id: String) -> String? {
        bridge.totp(id: id, epochSeconds: Int64(Date().timeIntervalSince1970))
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
