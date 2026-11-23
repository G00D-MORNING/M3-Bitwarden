import Shared
import SwiftUI

/// Setup and lock screens around the Compose vault: certificate → sign in → unlock → vault.
struct RootView: View {
    @StateObject private var service = VaultService.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch service.phase {
            case .needsCertificate: CertificateSetupView(service: service)
            case .signedOut: LoginView(service: service)
            case .locked: UnlockView(service: service)
            case .unlocked:
                ComposeView()
                    .ignoresSafeArea()
                    .alert("Hinweis", isPresented: Binding(get: { service.errorMessage != nil }, set: { if !$0 { service.errorMessage = nil } })) {
                        Button("OK") { service.errorMessage = nil }
                    } message: {
                        Text(service.errorMessage ?? "")
                    }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background: service.appDidEnterBackground()
            case .active: service.appWillEnterForeground()
            default: break
            }
        }
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
        NavigationStack {
            Form {
                Section {
                    Text("Der Server lässt nur Geräte mit eigenem Zertifikat herein. Der Schlüssel dazu entsteht auf diesem iPhone und verlässt es nie.")
                        .font(.callout)
                }
                Section("1. Anfrage erzeugen") {
                    if csr.isEmpty {
                        Button("Zertifikatsanfrage erzeugen") { makeCSR() }
                    } else {
                        Text(csr).font(.system(.caption2, design: .monospaced)).lineLimit(6).textSelection(.enabled)
                        Button(copied ? "Kopiert" : "Anfrage kopieren") {
                            UIPasteboard.general.string = csr
                            copied = true
                        }
                    }
                }
                Section {
                    Text("Cloudflare → jochenderrochen.de → SSL/TLS → Client-Zertifikate → Zertifikat erstellen → „Eigenen privaten Schlüssel und CSR verwenden“ → Anfrage einfügen → Erstellen → Zertifikat kopieren.")
                        .font(.callout)
                } header: {
                    Text("2. In Cloudflare ausstellen")
                }
                Section("3. Zertifikat einfügen") {
                    TextEditor(text: $certificate)
                        .font(.system(.caption2, design: .monospaced))
                        .frame(minHeight: 120)
                    Button("Aus Zwischenablage einfügen") { certificate = UIPasteboard.general.string ?? "" }
                    Button("Zertifikat speichern") { install() }
                        .disabled(!certificate.contains("BEGIN CERTIFICATE"))
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Gerät freischalten")
        }
        .onAppear { if DeviceCertificate.hasKey { makeCSR() } }
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

// MARK: - Login

struct LoginView: View {
    @ObservedObject var service: VaultService
    @State private var email = ""
    @State private var password = ""
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                if service.twoFactorProviders.isEmpty {
                    Section {
                        TextField("E-Mail", text: $email)
                            .textContentType(.username)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("Master-Passwort", text: $password)
                            .textContentType(.password)
                    } footer: {
                        Text("Server: \(M3BConfig.serverURL.host ?? "")")
                    }
                    Section {
                        Button {
                            Task { await service.login(email: email, password: password) }
                        } label: {
                            HStack { Text("Anmelden"); if service.busy { Spacer(); ProgressView() } }
                        }
                        .disabled(email.isEmpty || password.isEmpty || service.busy)
                    }
                    Section {
                        Link("Neues Konto in der Weboberfläche anlegen", destination: M3BConfig.webVaultURL)
                    }
                } else {
                    Section {
                        TextField(service.twoFactorProviders.contains(0) ? "Code aus der Authenticator-App" : "Code aus der E-Mail", text: $code)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                    } header: {
                        Text("Zwei-Faktor-Anmeldung")
                    }
                    Section {
                        Button("Bestätigen") {
                            Task { await service.login(email: email, password: password, twoFactorCode: code, provider: service.twoFactorProviders.contains(0) ? 0 : service.twoFactorProviders.first) }
                        }
                        .disabled(code.isEmpty || service.busy)
                        Button("Abbrechen", role: .cancel) { service.cancelTwoFactor() }
                    }
                }
                if let error = service.errorMessage {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Anmelden")
        }
    }
}

// MARK: - Unlock

struct UnlockView: View {
    @ObservedObject var service: VaultService
    @State private var password = ""
    @State private var askedBiometrics = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(service.account?.email ?? "", systemImage: "lock.shield")
                }
                if service.canUseBiometrics {
                    Section {
                        Button("Mit Face ID entsperren") { Task { await service.unlockWithBiometrics() } }
                    }
                }
                Section {
                    SecureField("Master-Passwort", text: $password)
                        .textContentType(.password)
                        .onSubmit(unlock)
                    Button {
                        unlock()
                    } label: {
                        HStack { Text("Entsperren"); if service.busy { Spacer(); ProgressView() } }
                    }
                    .disabled(password.isEmpty || service.busy)
                }
                if let error = service.errorMessage {
                    Section { Text(error).foregroundStyle(.red) }
                }
                Section {
                    Button("Abmelden", role: .destructive) { service.logout() }
                }
            }
            .navigationTitle("Tresor gesperrt")
        }
        .task {
            guard !askedBiometrics, service.canUseBiometrics else { return }
            askedBiometrics = true
            await service.unlockWithBiometrics()
        }
    }

    private func unlock() {
        Task {
            await service.unlock(password: password)
            password = ""
        }
    }
}
