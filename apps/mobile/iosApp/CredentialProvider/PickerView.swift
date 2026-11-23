import Shared
import SwiftUI

/// Credential picker shown by the extension: matching logins first, then everything,
/// with a "remember for this site" switch after a manual pick.
struct PickerView: View {
    enum Mode { case password, oneTimeCode, text }

    struct Result {
        let row: ProviderRow
        let acceptedLink: ProviderLinkOffer?
        let text: String?
    }

    let mode: Mode
    let domain: String?
    let rows: [ProviderRow]
    let bridge: CredentialProviderBridge
    let onDone: (Result?) -> Void

    @State private var query = ""
    @State private var pending: (ProviderRow, ProviderLinkOffer?)?
    @State private var remember = true

    private var filtered: [ProviderRow] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let pool = mode == .oneTimeCode ? rows.filter(\.hasTotp) : rows
        guard !q.isEmpty else { return pool }
        return pool.filter { $0.name.lowercased().contains(q) || $0.username.lowercased().contains(q) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let pending {
                    confirm(pending.0, pending.1)
                } else {
                    list
                }
            }
            .navigationTitle(domain.map { "Anmelden bei \($0)" } ?? "Tresor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { onDone(nil) } } }
        }
    }

    private var list: some View {
        List {
            let matched = filtered.filter(\.matched)
            if query.isEmpty, !matched.isEmpty {
                Section("Passend") { ForEach(matched, id: \.id) { row in rowView(row) } }
            }
            Section(query.isEmpty ? "Alle Einträge" : "Treffer") {
                ForEach(query.isEmpty ? filtered.filter { !$0.matched } : filtered, id: \.id) { row in rowView(row) }
            }
        }
        .searchable(text: $query, prompt: "Tresor durchsuchen")
    }

    private func rowView(_ row: ProviderRow) -> some View {
        Button {
            pick(row)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(row.name).font(.body)
                    if row.needsConfirmation { Image(systemName: "exclamationmark.shield").foregroundStyle(.orange) }
                }
                if !row.username.isEmpty { Text(row.username).font(.subheadline).foregroundStyle(.secondary) }
            }
        }
        .foregroundStyle(.primary)
    }

    private func pick(_ row: ProviderRow) {
        let offer = domain.flatMap { bridge.linkOffer(id: row.id, identifier: $0) }
        if offer == nil && !row.needsConfirmation {
            onDone(Result(row: row, acceptedLink: nil, text: textValue(row)))
        } else {
            pending = (row, offer)
        }
    }

    private func confirm(_ row: ProviderRow, _ offer: ProviderLinkOffer?) -> some View {
        Form {
            Section { Text(row.name).font(.headline); if !row.username.isEmpty { Text(row.username) } }
            if row.needsConfirmation {
                Section { Label("Dieser Eintrag ist noch nicht mit dieser Seite verknüpft.", systemImage: "exclamationmark.shield") }
            }
            if let offer {
                Section { Toggle("Für \(offer.domain) merken", isOn: $remember) } footer: {
                    Text("Nächstes Mal direkt über der Tastatur vorschlagen.")
                }
            }
            Section {
                Button("Ausfüllen") { onDone(Result(row: row, acceptedLink: remember ? offer : nil, text: textValue(row))) }
                    .bold()
            }
        }
    }

    private func textValue(_ row: ProviderRow) -> String? {
        guard mode == .text else { return nil }
        return row.username.isEmpty ? row.name : row.username
    }
}
