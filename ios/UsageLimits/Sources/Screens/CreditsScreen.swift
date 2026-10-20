import SwiftUI
import UsageLimitsKit

/// Prepaid and API balances: what is left to spend, not a quota that resets.
///
/// Reached from the Overview. Every number, severity and rule comes from `UsageLimitsKit`;
/// this file only lays them out.
struct CreditsScreen: View {

    @EnvironmentObject private var store: UsageStore
    @State private var isAdding = false
    @State private var editing: CreditEntry?
    @State private var removing: CreditEntry?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(store.credits) { entry in
                    CreditCard(
                        entry: entry, now: store.now, staleAfter: store.settings.staleAfter,
                        onEdit: { editing = entry }, onRemove: { removing = entry })
                }
                Button { isAdding = true } label: {
                    UsageCard {
                        Label("Add a credit balance", systemImage: "plus.circle")
                            .font(.headline)
                            .foregroundStyle(UsageColors.terracotta)
                        Text("OpenRouter, DeepSeek, Moonshot, xAI, fal.ai and Novita. Keys stay in the keychain on this device.")
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            .readableWidth()
        }
        .background(UsageColors.background)
        .navigationTitle("Credits")
        .refreshable { await store.refresh() }
        .sheet(isPresented: $isAdding) { AddCreditSheet() }
        .sheet(item: $editing) { entry in EditCreditSheet(entry: entry) }
        .confirmationDialog(
            "Remove \(removing?.account.label ?? "this balance")?",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let id = removing?.account.id { Task { await store.removeCreditAccount(id: id) } }
                removing = nil
            }
            Button("Cancel", role: .cancel) { removing = nil }
        } message: {
            Text("The key is deleted from this device. It is not revoked at the provider.")
        }
    }
}

/// One credit account: who, how much is left, and how sure we are.
struct CreditCard: View {

    let entry: CreditEntry
    let now: Date
    let staleAfter: TimeInterval
    var onEdit: (() -> Void)?
    var onRemove: (() -> Void)?

    private func updatedText(since fetchedAt: Date) -> String {
        let seconds = Int(max(0, now.timeIntervalSince(fetchedAt)))
        return seconds < 60 ? "Updated just now" : "Updated \(Countdown.format(seconds: seconds)) ago"
    }

    private var glance: GlanceCredit { GlanceCredit(entry: entry, now: now, staleAfter: staleAfter) }
    private var severity: Severity { glance.severity(at: now, staleAfter: staleAfter) }

    var body: some View {
        UsageCard {
            HStack(spacing: 10) {
                CreditGlyph(provider: entry.account.provider)
                Circle()
                    .fill(SeverityPalette.accent(severity))
                    .frame(width: 8, height: 8)
                    .accessibilityLabel(SeverityPalette.label(severity))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: entry.account.label)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textPrimary)
                    if entry.account.label != entry.account.provider.displayName {
                        Text(verbatim: entry.account.provider.displayName)
                            .font(.footnote)
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                }
                Spacer()
                StatusPill(severity: severity)
                if onEdit != nil || onRemove != nil {
                    Menu {
                        if let onEdit { Button("Edit", action: onEdit) }
                        if let onRemove { Button("Remove", role: .destructive, action: onRemove) }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .foregroundStyle(UsageColors.textSecondary)
                    }
                    .accessibilityLabel("More")
                }
            }

            if glance.balances.isEmpty {
                Text("No balance read yet")
                    .font(.subheadline)
                    .foregroundStyle(UsageColors.textSecondary)
            }
            ForEach(Array(glance.balances.enumerated()), id: \.offset) { _, balance in
                BalanceRow(balance: balance, account: entry.account, severity: severity)
            }

            if let error = entry.snapshot?.error {
                Text(verbatim: error)
                    .font(.footnote)
                    .foregroundStyle(UsageColors.amber)
            }
            if let fetchedAt = glance.fetchedAt {
                Text(updatedText(since: fetchedAt))
                    .font(.caption)
                    .foregroundStyle(UsageColors.textTertiary)
            }
        }
    }
}

private struct BalanceRow: View {
    let balance: CreditBalance
    let account: CreditAccount
    /// The account's aged severity: stale data must not keep its last colour.
    let severity: Severity

    /// The balance's own colour, unless the whole reading is stale or failed.
    private var shown: Severity {
        severity == .stale || severity == .error
            ? severity
            : CreditSeverity.of(balance, lowThreshold: account.lowThreshold(for: balance.currency))
    }
    private var amount: String { CreditFormat.string(balance.amount, currency: balance.currency) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if balance.unlimited {
                Text(verbatim: balance.label ?? "No limit")
                    .font(.system(.title2, design: .rounded).weight(.semibold))
                    .foregroundStyle(UsageColors.textPrimary)
                Text("\(amount) spent this month")
                    .font(.subheadline)
                    .foregroundStyle(UsageColors.textSecondary)
            } else {
                if let label = balance.label {
                    Text(verbatim: label)
                        .font(.subheadline)
                        .foregroundStyle(UsageColors.textSecondary)
                }
                Text(verbatim: amount)
                    .font(.system(.title, design: .rounded).weight(.semibold))
                    .foregroundStyle(SeverityPalette.text(shown))
                    .monospacedDigit()
                if let percent = balance.remainingPercent, let total = balance.total {
                    UsageBar(remainingPercent: percent, severity: shown)
                        .accessibilityLabel("\(amount) of \(CreditFormat.string(total, currency: balance.currency)) left")
                }
            }
        }
    }
}

/// A lettered ring, like the providers that have no artwork.
struct CreditGlyph: View {
    let provider: CreditProviderID

    var body: some View {
        Text(verbatim: glyph)
            .font(.headline.weight(.bold))
            .minimumScaleFactor(0.5)
            .foregroundStyle(UsageColors.terracotta)
            .frame(width: 28, height: 28)
            .overlay(Circle().stroke(UsageColors.terracotta, lineWidth: 2))
            .padding(6)
            .accessibilityLabel(provider.displayName)
    }

    private var glyph: String {
        switch provider {
        case .openrouter: return "R"
        case .deepseek: return "D"
        case .moonshot: return "M"
        case .xai: return "X"
        case .fal: return "F"
        case .novita: return "N"
        }
    }
}

// MARK: - Add

struct AddCreditSheet: View {

    @EnvironmentObject private var store: UsageStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var provider: CreditProviderID?
    @State private var label = ""
    @State private var key = ""
    @State private var region: ProviderRegion = .intl
    @State private var teamID = ""
    @State private var threshold = ""
    @State private var isWorking = false
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let provider { form(provider) } else { chooser }
                }
                .padding(16)
                .readableWidth()
            }
            .background(UsageColors.background)
            .navigationTitle("Add credit balance")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }

    private var chooser: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(CreditProviderID.allCases, id: \.self) { option in
                Button { provider = option } label: {
                    UsageCard {
                        HStack {
                            CreditGlyph(provider: option)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: option.displayName)
                                    .font(.headline)
                                    .foregroundStyle(UsageColors.terracotta)
                                Text(verbatim: option.keyHint)
                                    .font(.footnote)
                                    .foregroundStyle(UsageColors.textSecondary)
                            }
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            // Listed so a missing provider reads as impossible, not as forgotten.
            ForEach(CreditProviderID.notPossible) { option in
                UsageCard {
                    Text(verbatim: option.displayName)
                        .font(.headline)
                        .foregroundStyle(UsageColors.textTertiary)
                    Text(verbatim: option.reason)
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textTertiary)
                }
                .opacity(0.6)
                .accessibilityLabel("\(option.displayName): not available. \(option.reason)")
            }
        }
    }

    private func form(_ provider: CreditProviderID) -> some View {
        UsageCard {
            Text(verbatim: provider.displayName)
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)
            Text(verbatim: provider.keyHint)
                .font(.footnote)
                .foregroundStyle(UsageColors.textSecondary)

            TextField(provider.displayName, text: $label)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("Name")

            if provider.hasRegion {
                Picker("Region", selection: $region) {
                    ForEach(CreditProviderID.regions, id: \.self) { option in
                        Text(CreditProviderID.regionLabel(option)).tag(option)
                    }
                }
                .tint(UsageColors.terracotta)
            }
            if provider.acceptsTeamID {
                TextField("Team id (only if the key is not team-scoped)", text: $teamID)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            TextField("Low balance below (optional)", text: $threshold)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.decimalPad)

            SecureField("Key", text: $key)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Text("Checked against \(provider.displayName) before it is saved, then kept in the keychain on this device only.")
                .font(.caption)
                .foregroundStyle(UsageColors.textTertiary)

            if let failure {
                Text(verbatim: failure)
                    .font(.footnote)
                    .foregroundStyle(UsageColors.redText)
            }

            HStack(spacing: 10) {
                Button { submit(provider) } label: {
                    if isWorking { ProgressView() } else { Text("Add") }
                }
                .buttonStyle(.borderedProminent)
                .tint(UsageColors.terracotta)
                .disabled(isWorking || key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button("Open console") {
                    if let url = URL(string: provider.consoleURL) { openURL(url) }
                }
                .buttonStyle(.bordered)
                .tint(UsageColors.terracotta)

                Button("Back") { self.provider = nil; failure = nil }
                    .buttonStyle(.bordered)
                    .tint(UsageColors.textSecondary)
                    .disabled(isWorking)
            }
        }
    }

    private func submit(_ provider: CreditProviderID) {
        guard let figure = CreditThresholdField.parse(threshold) else {
            failure = "Enter the low-balance figure as a positive number, or leave it empty."
            return
        }
        isWorking = true
        failure = nil
        Task {
            defer { isWorking = false }
            do {
                try await store.addCreditAccount(
                    provider: provider, key: key, label: label,
                    region: provider.hasRegion ? region : nil,
                    teamID: provider.acceptsTeamID ? teamID : nil,
                    lowThreshold: figure.value)
                key = ""
                dismiss()
            } catch is CancellationError {
            } catch {
                failure = error.localizedDescription
            }
        }
    }
}

/// An empty field means "use the default"; anything else must be a positive number.
enum CreditThresholdField {
    struct Parsed { let value: Decimal? }

    static func parse(_ text: String) -> Parsed? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return Parsed(value: nil) }
        guard let value = Decimal(string: trimmed, locale: Locale.current), value > 0 else { return nil }
        return Parsed(value: value)
    }
}

// MARK: - Edit

struct EditCreditSheet: View {

    @EnvironmentObject private var store: UsageStore
    @Environment(\.dismiss) private var dismiss

    let entry: CreditEntry
    @State private var label: String
    @State private var threshold: String
    @State private var failure: String?

    init(entry: CreditEntry) {
        self.entry = entry
        _label = State(initialValue: entry.account.label)
        _threshold = State(initialValue: entry.account.attributes[CreditAccount.lowThresholdKey] ?? "")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                UsageCard {
                    TextField("Name", text: $label)
                        .textFieldStyle(.roundedBorder)
                    TextField("Low balance below (empty for the default)", text: $threshold)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.decimalPad)
                    Text("Below this the balance reads Low, below twice it Moderate. Ignored when the provider reports a total: the share left decides.")
                        .font(.caption)
                        .foregroundStyle(UsageColors.textTertiary)
                    if let failure {
                        Text(verbatim: failure).font(.footnote).foregroundStyle(UsageColors.redText)
                    }
                    Button("Save") {
                        guard let figure = CreditThresholdField.parse(threshold) else {
                            failure = "Enter a positive number, or leave it empty."
                            return
                        }
                        Task {
                            await store.updateCreditAccount(id: entry.account.id, label: label, lowThreshold: figure.value)
                            dismiss()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(UsageColors.terracotta)
                }
                .padding(16)
                .readableWidth()
            }
            .background(UsageColors.background)
            .navigationTitle("Edit")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
