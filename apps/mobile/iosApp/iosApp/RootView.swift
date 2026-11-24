import Shared
import SwiftUI

/// Setup and lock screens around the Compose vault: certificate → sign in → unlock → vault.
struct RootView: View {
    @StateObject private var service = VaultService.shared
    @Environment(\.scenePhase) private var scenePhase

    /// Debug builds started with `-m3bDemo`: the vault UI with sample data, no server needed.
    private var demo: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-m3bDemo")
        #else
        false
        #endif
    }

    var body: some View {
        Group {
            if demo {
                VaultHost()
            } else {
            switch service.phase {
            case .needsCertificate: CertificateSetupView(service: service)
            case .signedOut: SignInView(service: service)
            case .locked: UnlockView(service: service)
            case .unlocked:
                VaultHost()
            }
            }
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.85), value: service.phase)
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background: service.appDidEnterBackground()
            case .active: service.appWillEnterForeground()
            default: break
            }
        }
    }
}

/// Common frame of the setup pages: emblem, title, subtitle, content.
private struct SetupPage<Content: View>: View {
    let symbol: String
    let title: String
    let subtitle: String
    var busy = false
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                VStack(spacing: 24) {
                    M3Emblem(symbol: symbol, size: 176, busy: busy)
                    VStack(spacing: 4) {
                        Text(title).font(.system(size: 36, weight: .bold)).multilineTextAlignment(.center)
                        Text(subtitle).font(.body).foregroundStyle(M3.onSurfaceVariant).multilineTextAlignment(.center)
                    }
                }
                VStack(spacing: 16) { content }
            }
            .padding(.horizontal, 24)
            .padding(.top, 48)
            .padding(.bottom, 24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(M3Backdrop())
        .foregroundStyle(M3.onSurface)
    }
}

// MARK: - Certificate

struct CertificateSetupView: View {
    @ObservedObject var service: VaultService
    @State private var csr = ""
    @State private var certificate = ""
    @State private var error: String?
    @State private var copied = false

    var body: some View {
        SetupPage(symbol: "key.horizontal.fill", title: "Gerät freischalten", subtitle: "Der Server lässt nur Geräte mit eigenem Zertifikat herein. Der Schlüssel entsteht auf diesem iPhone und verlässt es nie.") {
            step(1, "Anfrage erzeugen") {
                if csr.isEmpty {
                    Button("Zertifikatsanfrage erzeugen", action: makeCSR).buttonStyle(M3ButtonStyle(height: 48))
                } else {
                    Text(csr).font(.system(.caption2, design: .monospaced)).lineLimit(5).foregroundStyle(M3.onSurfaceVariant).textSelection(.enabled)
                    HStack(spacing: 8) {
                        Button(copied ? "Kopiert" : "Kopieren") { UIPasteboard.general.string = csr; copied = true }
                            .buttonStyle(M3ButtonStyle(kind: .tonal, height: 44))
                        ShareLink(item: csr) { Text("Teilen") }.buttonStyle(M3ButtonStyle(kind: .tonal, height: 44))
                    }
                }
            }
            step(2, "In Cloudflare ausstellen") {
                Text("Cloudflare → jochenderrochen.de → SSL/TLS → Client-Zertifikate → Zertifikat erstellen → „Eigenen privaten Schlüssel und CSR verwenden“ → Anfrage einfügen → Erstellen → Zertifikat kopieren.")
                    .font(.callout)
            }
            step(3, "Zertifikat einfügen") {
                TextEditor(text: $certificate)
                    .font(.system(.caption2, design: .monospaced))
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 110)
                    .padding(8)
                    .background(M3.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                HStack(spacing: 8) {
                    Button("Einfügen") { certificate = UIPasteboard.general.string ?? "" }.buttonStyle(M3ButtonStyle(kind: .tonal, height: 44))
                    Button("Speichern", action: install).buttonStyle(M3ButtonStyle(height: 44)).disabled(!certificate.contains("BEGIN CERTIFICATE"))
                }
            }
            if let error { M3Error(text: error) }
        }
        .onAppear { if DeviceCertificate.hasKey { makeCSR() } }
    }

    private func step<C: View>(_ n: Int, _ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Text("\(n)").font(.subheadline.weight(.bold)).foregroundStyle(M3.onPrimary).frame(width: 28, height: 28).background(M3.primary, in: Circle())
                Text(title).font(.headline)
            }
            content()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(M3.surfaceContainer, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func makeCSR() {
        do {
            csr = try DeviceCertificate.makeSigningRequest(deviceName: "M3B \(UIDevice.current.name)")
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func install() {
        do {
            try DeviceCertificate.install(pem: certificate)
            service.refreshPhase()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - Sign in

struct SignInView: View {
    @ObservedObject var service: VaultService
    @State private var email = ""
    @State private var password = ""
    @State private var code = ""

    private var twoFactor: Bool { !service.twoFactorProviders.isEmpty }

    var body: some View {
        SetupPage(
            symbol: twoFactor ? "lock.badge.clock.fill" : "shield.lefthalf.filled",
            title: twoFactor ? "Bestätigen" : "Anmelden",
            subtitle: twoFactor
                ? (service.twoFactorProviders.contains(0) ? "Gib den Code aus deiner Authenticator-App ein." : "Wir haben dir einen Code per E-Mail geschickt.")
                : "Server: \(M3BConfig.serverURL.host ?? "")",
            busy: service.busy
        ) {
            if twoFactor {
                M3Field(label: "Code", text: $code, symbol: "number", content: .oneTimeCode, keyboard: .numberPad) { submitCode() }
                if let error = service.errorMessage { M3Error(text: error) }
                Button(action: submitCode) { busyLabel("Bestätigen") }.buttonStyle(M3ButtonStyle()).disabled(code.isEmpty || service.busy)
                Button("Abbrechen") { service.cancelTwoFactor(); code = "" }.buttonStyle(M3ButtonStyle(kind: .text, height: 44))
            } else {
                M3Field(label: "E-Mail", text: $email, symbol: "envelope.fill", content: .username, keyboard: .emailAddress)
                M3Field(label: "Master-Passwort", text: $password, symbol: "key.fill", secure: true, content: .password) { submit() }
                if let error = service.errorMessage { M3Error(text: error) }
                Button(action: submit) { busyLabel("Anmelden") }.buttonStyle(M3ButtonStyle()).disabled(email.isEmpty || password.isEmpty || service.busy)
                Link("Neues Konto in der Weboberfläche anlegen", destination: M3BConfig.webVaultURL).font(.subheadline.weight(.semibold)).tint(M3.primary).padding(.top, 8)
            }
        }
    }

    private func busyLabel(_ text: String) -> some View {
        HStack { if service.busy { ProgressView().tint(M3.onPrimary) } else { Text(text) } }
    }

    private func submit() {
        guard !email.isEmpty, !password.isEmpty else { return }
        Task { await service.login(email: email, password: password) }
    }

    private func submitCode() {
        guard !code.isEmpty else { return }
        Task {
            await service.login(email: email, password: password, twoFactorCode: code,
                                provider: service.twoFactorProviders.contains(0) ? 0 : service.twoFactorProviders.first)
        }
    }
}

// MARK: - Unlock

struct UnlockView: View {
    @ObservedObject var service: VaultService
    @State private var password = ""
    @State private var askedBiometrics = false

    var body: some View {
        SetupPage(symbol: service.busy ? "lock.open.fill" : "lock.fill", title: service.busy ? "Wird entsperrt" : "Tresor gesperrt", subtitle: "\(service.account?.email ?? "") auf \(M3BConfig.serverURL.host ?? "")", busy: service.busy) {
            M3Field(label: "Master-Passwort", text: $password, symbol: "key.fill", secure: true, content: .password) { unlock() }
            if let error = service.errorMessage { M3Error(text: error) }
            Button(action: unlock) {
                HStack { if service.busy { ProgressView().tint(M3.onPrimary) } else { Image(systemName: "lock.open.fill"); Text("Entsperren") } }
            }
            .buttonStyle(M3ButtonStyle())
            .disabled(password.isEmpty || service.busy)
            if service.canUseBiometrics {
                Button { Task { await service.unlockWithBiometrics() } } label: { Label("Mit Face ID entsperren", systemImage: "faceid") }
                    .buttonStyle(M3ButtonStyle(kind: .tonal, height: 48))
            }
            Button("Abmelden", role: .destructive) { service.signOut() }
                .buttonStyle(M3ButtonStyle(kind: .text, height: 44))
                .padding(.top, 24)
        }
        .task {
            guard !askedBiometrics, service.canUseBiometrics else { return }
            askedBiometrics = true
            await service.unlockWithBiometrics()
        }
    }

    private func unlock() {
        guard !password.isEmpty else { return }
        Task {
            await service.unlock(password: password)
            password = ""
        }
    }
}
