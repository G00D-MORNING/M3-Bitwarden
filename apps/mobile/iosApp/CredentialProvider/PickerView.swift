import Shared
import SwiftUI

/// Everything the AutoFill sheet shows: first the unlock screen (Face ID, master password as
/// fallback, never a blank page), then the credential list with the website of every entry.
struct ExtensionRoot: View {
    enum Mode { case password, oneTimeCode, text }

    struct Result {
        let row: ProviderRow
        let acceptedLink: ProviderLinkOffer?
        let text: String?
    }

    let mode: Mode
    let identifiers: [String]
    /// Set when iOS asks for one specific suggestion (tap in the QuickType bar).
    let singleId: String?
    let bridge: CredentialProviderBridge
    let onDone: (Result?) -> Void
    let onUnlockedSingle: (Bool) -> Void

    @StateObject private var service = VaultService.shared
    @State private var unlocked = false
    @State private var trying = false
    @State private var password = ""
    @State private var rows: [ProviderRow] = []

    var body: some View {
        ZStack {
            M3.surfaceContainer.ignoresSafeArea()
            if unlocked && singleId == nil {
                PickerList(mode: mode, domain: identifiers.first, rows: rows, bridge: bridge, onDone: onDone)
            } else {
                unlockView
            }
        }
        .task { await start() }
    }

    private var unlockView: some View {
        ScrollView {
            VStack(spacing: 28) {
                HStack {
                    Spacer()
                    GlassCloseButton { onDone(nil) }
                }
                M3Emblem(symbol: trying ? "faceid" : "lock.fill", size: 140, busy: trying)
                VStack(spacing: 6) {
                    Text(trying ? "Wird entsperrt…" : "Tresor gesperrt").font(.system(size: 30, weight: .bold))
                    Text(identifiers.first.map { "Anmelden bei \($0)" } ?? (service.account?.email ?? ""))
                        .font(.body).foregroundStyle(M3.onSurfaceVariant).multilineTextAlignment(.center)
                }
                if !trying {
                    VStack(spacing: 12) {
                        M3Field(label: "Master-Passwort", text: $password, symbol: "key.fill", secure: true, content: .password) { unlockWithPassword() }
                        if let error = service.errorMessage { M3Error(text: error) }
                        Button(action: unlockWithPassword) {
                            HStack { if service.busy { ProgressView().tint(M3.onPrimary) } else { Image(systemName: "lock.open.fill"); Text("Entsperren") } }
                        }
                        .buttonStyle(M3ButtonStyle())
                        .disabled(password.isEmpty || service.busy)
                        if service.canUseBiometrics {
                            Button { Task { await tryBiometrics() } } label: { Label("Mit Face ID entsperren", systemImage: "faceid") }
                                .buttonStyle(M3ButtonStyle(kind: .tonal, height: 48))
                        }
                    }
                }
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func start() async {
        service.refreshPhase()
        if bridge.isUnlocked { return finishUnlock() }
        guard service.account != nil else {
            service.errorMessage = "Melde dich zuerst in der App M3 Bitwarden an."
            return
        }
        if service.canUseBiometrics { await tryBiometrics() }
    }

    private func tryBiometrics() async {
        trying = true
        let ok = await service.unlockWithBiometrics(syncTimeout: 4)
        trying = false
        if ok { finishUnlock() }
    }

    private func unlockWithPassword() {
        guard !password.isEmpty else { return }
        Task {
            await service.unlock(password: password)
            password = ""
            if service.phase == .unlocked { finishUnlock() }
        }
    }

    private func finishUnlock() {
        if singleId != nil { return onUnlockedSingle(true) }
        rows = bridge.rows(identifiers: identifiers, nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        withAnimation(.spring(response: 0.45, dampingFraction: 0.8)) { unlocked = true }
    }
}

/// Matching logins first, then everything; every row shows its website so entries for
/// different subdomains (nas.…, mail.…) can be told apart.
private struct PickerList: View {
    let mode: ExtensionRoot.Mode
    let domain: String?
    let rows: [ProviderRow]
    let bridge: CredentialProviderBridge
    let onDone: (ExtensionRoot.Result?) -> Void

    @State private var query = ""
    @State private var pending: (ProviderRow, ProviderLinkOffer?)?
    @State private var remember = true

    private var filtered: [ProviderRow] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let pool = mode == .oneTimeCode ? rows.filter(\.hasTotp) : rows
        guard !q.isEmpty else { return pool }
        return pool.filter { $0.name.lowercased().contains(q) || $0.username.lowercased().contains(q) || $0.host.lowercased().contains(q) }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(mode == .oneTimeCode ? "Code einsetzen" : "Anmelden").font(.system(size: 28, weight: .bold))
                    if let domain { Text(domain).font(.subheadline).foregroundStyle(M3.onSurfaceVariant) }
                }
                Spacer()
                GlassCloseButton { onDone(nil) }
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 12)

            M3Field(label: "Tresor durchsuchen", text: $query, symbol: "magnifyingglass")
                .padding(.horizontal, 16).padding(.bottom, 8)

            if let pending {
                confirm(pending.0, pending.1)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        let matched = filtered.filter(\.matched)
                        let rest = query.isEmpty ? filtered.filter { !$0.matched } : filtered
                        if query.isEmpty, !matched.isEmpty { section("Passend für diese Seite", matched) }
                        if !rest.isEmpty { section(query.isEmpty ? "Alle Einträge" : "\(rest.count) Treffer", rest) }
                        if filtered.isEmpty {
                            Text("Nichts gefunden.").foregroundStyle(M3.onSurfaceVariant).frame(maxWidth: .infinity).padding(32)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    private func section(_ title: String, _ items: [ProviderRow]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(M3.primary).padding(.leading, 4)
            VStack(spacing: 2) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, row in
                    rowView(row)
                        .background(M3.surface, in: SegmentShape(index: index, count: items.count))
                }
            }
        }
    }

    private func rowView(_ row: ProviderRow) -> some View {
        Button { pick(row) } label: {
            HStack(spacing: 16) {
                Text(String(row.name.prefix(1)).uppercased())
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 44, height: 44)
                    .foregroundStyle(M3.onPrimaryContainer)
                    .background(M3.primaryContainer, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(row.name).font(.body.weight(.semibold)).lineLimit(1)
                        if row.needsConfirmation { Image(systemName: "exclamationmark.shield.fill").foregroundStyle(.orange).font(.caption) }
                    }
                    if !row.host.isEmpty {
                        Label(row.host, systemImage: "globe").font(.subheadline).foregroundStyle(M3.primary).lineLimit(1).labelStyle(CompactLabel())
                    }
                    if !row.username.isEmpty {
                        Text(row.username).font(.subheadline).foregroundStyle(M3.onSurfaceVariant).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(M3.onSurfaceVariant).font(.footnote)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(M3.onSurface)
    }

    private func pick(_ row: ProviderRow) {
        let offer = domain.flatMap { bridge.linkOffer(id: row.id, identifier: $0) }
        if offer == nil && !row.needsConfirmation {
            onDone(.init(row: row, acceptedLink: nil, text: textValue(row)))
        } else {
            pending = (row, offer)
        }
    }

    private func confirm(_ row: ProviderRow, _ offer: ProviderLinkOffer?) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.name).font(.title3.weight(.bold))
                    if !row.host.isEmpty { Text(row.host).foregroundStyle(M3.primary) }
                    if !row.username.isEmpty { Text(row.username).foregroundStyle(M3.onSurfaceVariant) }
                }
                .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                .background(M3.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                if row.needsConfirmation {
                    M3Error(text: "Dieser Eintrag ist noch nicht mit dieser Seite verknüpft. Prüfe die Adresse, bevor du ausfüllst.")
                }
                if let offer {
                    Toggle(isOn: $remember) {
                        VStack(alignment: .leading) {
                            Text("Für \(offer.domain) merken")
                            Text("Nächstes Mal direkt über der Tastatur vorschlagen.").font(.caption).foregroundStyle(M3.onSurfaceVariant)
                        }
                    }
                    .tint(M3.primary)
                    .padding(16)
                    .background(M3.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                }
                Button("Ausfüllen") { onDone(.init(row: row, acceptedLink: remember ? offer : nil, text: textValue(row))) }
                    .buttonStyle(M3ButtonStyle())
                Button("Zurück") { pending = nil }.buttonStyle(M3ButtonStyle(kind: .text, height: 44)).frame(maxWidth: .infinity)
            }
            .padding(16)
        }
    }

    private func textValue(_ row: ProviderRow) -> String? {
        guard mode == .text else { return nil }
        return row.username.isEmpty ? row.name : row.username
    }
}

/// Connected list: big outer corners, small inner ones (as in the prototype).
struct SegmentShape: Shape {
    let index: Int
    let count: Int
    func path(in rect: CGRect) -> Path {
        let big: CGFloat = 24, small: CGFloat = 4
        let top = index == 0 ? big : small
        let bottom = index == count - 1 ? big : small
        return Path(UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom, bottomTrailingRadius: bottom, topTrailingRadius: top, style: .continuous).path(in: rect).cgPath)
    }
}

private struct CompactLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) { configuration.icon.font(.caption); configuration.title }
    }
}
