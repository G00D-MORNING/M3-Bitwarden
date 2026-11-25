import Foundation
import Shared
import UIKit

/// The Proton Authenticator on iOS: browser sign-in, session in the keychain (renewed by the
/// Rust core, so no new sign-in is needed while it is used), entries in memory only.
@MainActor
final class ProtonService: NSObject, ProtonBackend {
    static let shared = ProtonService()

    private var signIn: Task<Void, Never>?
    private var syncing = false

    private var session: String? {
        get { SecureStore.get(.protonSession).map { String(decoding: $0, as: UTF8.self) } }
        set {
            if let newValue { try? SecureStore.set(Data(newValue.utf8), for: .protonSession) } else { SecureStore.delete(.protonSession) }
        }
    }

    func install() {
        ProtonAuthenticator.shared.backend = self
        ProtonAuthenticator.shared.setStatus(value: session == nil ? .disconnected : .syncing, text: nil)
        VaultService.shared.onUnlocked = { [weak self] in self?.refreshNow() }
        #if DEBUG
        // Test hook: `-m3bProtonSession <file>` takes a session saved by proton-core's probe.
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-m3bProtonSession"), i + 1 < args.count,
           let text = try? String(contentsOfFile: args[i + 1], encoding: .utf8) {
            session = text
            ProtonAuthenticator.shared.setStatus(value: .syncing, text: nil)
        }
        #endif
    }

    /// Debug demo mode (sample vault, no server): Proton can still be tried out.
    private static var demo: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-m3bDemo")
        #else
        false
        #endif
    }

    // MARK: ProtonBackend

    nonisolated func connect() { Task { @MainActor in self.beginSignIn() } }
    nonisolated func cancelConnect() {
        Task { @MainActor in
            self.signIn?.cancel()
            self.signIn = nil
            ProtonAuthenticator.shared.setStatus(value: .disconnected, text: nil)
        }
    }
    nonisolated func refresh() { Task { @MainActor in self.refreshNow() } }
    nonisolated func disconnect() {
        Task { @MainActor in
            if let s = self.session { Task.detached { protonSignOut(session: s) } }
            self.session = nil
            ProtonAuthenticator.shared.entries.removeAllObjects()
            ProtonAuthenticator.shared.setStatus(value: .disconnected, text: nil)
        }
    }
    nonisolated func code(uri: String, epochSeconds: Int64) -> String? {
        protonCode(uri: uri, unixTime: UInt64(max(0, epochSeconds)))
    }

    // MARK: Work

    private func beginSignIn() {
        signIn?.cancel()
        ProtonAuthenticator.shared.setStatus(value: .signingin, text: nil)
        signIn = Task { @MainActor in
            do {
                let start = try await Task.detached { try protonSignInStart() }.value
                if let url = URL(string: start.url) { await UIApplication.shared.open(url) }
                // Proton keeps the sign-in open for about ten minutes.
                for _ in 0..<150 {
                    try Task.checkCancellation()
                    try await Task.sleep(nanoseconds: 4_000_000_000)
                    if let s = try await Task.detached(operation: { try protonSignInPoll(state: start.state) }).value {
                        self.session = s
                        self.refreshNow()
                        return
                    }
                }
                ProtonAuthenticator.shared.setStatus(value: .disconnected, text: "Die Anmeldung bei Proton ist abgelaufen.")
            } catch is CancellationError {
            } catch {
                ProtonAuthenticator.shared.setStatus(value: .failed, text: (error as? LocalizedError)?.errorDescription ?? "\(error)")
            }
        }
    }

    private func refreshNow() {
        guard let current = session, !syncing, VaultSession.shared.isUnlocked || Self.demo else { return }
        syncing = true
        if ProtonAuthenticator.shared.entries.count == 0 { ProtonAuthenticator.shared.setStatus(value: .syncing, text: nil) }
        Task { @MainActor in
            defer { self.syncing = false }
            do {
                let result = try await Task.detached { try protonSync(session: current) }.value
                self.session = result.session
                ProtonAuthenticator.shared.publish(list: result.entries.map {
                    AuthEntry(id: $0.id, issuer: $0.issuer, name: $0.name, uri: $0.uri, period: Int32($0.period))
                })
            } catch let ProtonFfiError.Expired(message) {
                self.session = nil
                ProtonAuthenticator.shared.setStatus(value: .disconnected, text: message)
            } catch {
                ProtonAuthenticator.shared.setStatus(value: .failed, text: "\(error)")
            }
        }
    }
}
