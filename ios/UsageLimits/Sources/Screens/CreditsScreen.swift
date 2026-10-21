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
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(store.credits) { entry in
                    CreditCard(
                        entry: entry, now: store.now, staleAfter: store.settings.staleAfter,
                        onEdit: { editing = entry }, onRemove: { removing = entry })
                }
                VStack(alignment: .leading, spacing: 8) {
                    Button { isAdding = true } label: { Text("Add a credit balance") }
                        .buttonStyle(OutlineButtonStyle(fullWidth: true))
                    Text("OpenRouter, DeepSeek, Moonshot, xAI, fal.ai and Novita. Keys stay in the keychain on this device.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                }
                .padding(.top, 8)
            }
            .padding(16)
            .readableWidth()
        }
        .background(UsageColors.ground)
        .navigationTitle("Credits")
        .navigationBarTitleDisplayMode(.inline)
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

    @EnvironmentObject private var store: UsageStore

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

    /// The provider and the age of the reading, on one line.
    private var subline: String {
        var parts: [String] = []
        if entry.account.label != entry.account.provider.displayName {
            parts.append(entry.account.provider.displayName)
        }
        if let fetchedAt = glance.fetchedAt { parts.append(updatedText(since: fetchedAt)) }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        UsageCard {
            HStack(spacing: 10) {
                ProviderMark(assetName: entry.account.provider.iconAssetName)
                    .frame(width: 30, height: 30)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: entry.account.label)
                        .usageFont(.section)
                        .foregroundStyle(UsageColors.ink)
                    if !subline.isEmpty {
                        Text(verbatim: subline)
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.faint)
                    }
                }
                Spacer()
                StatusMark(severity: severity)
                if onEdit != nil || onRemove != nil {
                    Menu {
                        if let onEdit { Button("Edit", action: onEdit) }
                        if let onRemove { Button("Remove", role: .destructive, action: onRemove) }
                    } label: {
                        // 44pt to hit, however small the glyph.
                        Image(systemName: "ellipsis")
                            .foregroundStyle(UsageColors.muted)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("More")
                }
            }

            if glance.balances.isEmpty {
                Text("No balance read yet")
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
            }
            ForEach(Array(glance.balances.enumerated()), id: \.offset) { _, balance in
                BalanceRow(balance: balance, severity: severity)
            }

            if let error = entry.snapshot?.error {
                NoticeLine(
                    message: error, dot: UsageColors.negative,
                    retry: { Task { await store.refresh() } })
            }
        }
    }
}

private struct BalanceRow: View {
    let balance: CreditBalance
    /// The account's aged severity: stale data must not keep its last colour.
    let severity: Severity

    private var amount: String { CreditFormat.string(balance.amount, currency: balance.currency) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if balance.unlimited {
                Text(verbatim: balance.label ?? "No limit")
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.ink)
                Text("\(amount) spent this month")
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
            } else {
                // Money is always ink — status lives on the card's mark — and a real zero
                // prints as itself.
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: amount)
                        .usageFont(.money)
                        .foregroundStyle(UsageColors.ink)
                    if let total = balance.total {
                        Text(verbatim: "of \(CreditFormat.string(total, currency: balance.currency))")
                            .usageFont(.body)
                            .foregroundStyle(UsageColors.muted)
                    }
                    Spacer(minLength: 8)
                    if let label = balance.label {
                        Text(verbatim: label)
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.muted)
                    }
                }
                if let percent = balance.remainingPercent, let total = balance.total {
                    UsageBar(remainingPercent: percent, severity: severity)
                        .accessibilityLabel("\(amount) of \(CreditFormat.string(total, currency: balance.currency)) left")
                }
            }
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
            .background(UsageColors.ground)
            .navigationTitle("Add credit balance")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }

    /// The providers as ledger rows in one card, 56pt apart, with the ones that cannot be added
    /// listed after them — so a missing provider reads as impossible, not as forgotten.
    private var chooser: some View {
        UsageCard(padding: 0, spacing: 0) {
            ForEach(Array(CreditProviderID.allCases.enumerated()), id: \.offset) { index, option in
                if index > 0 { LedgerDivider() }
                HStack(spacing: 12) {
                    Button { provider = option } label: {
                        HStack(spacing: 12) {
                            ProviderMark(assetName: option.iconAssetName)
                                .frame(width: 30, height: 30)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: option.displayName)
                                    .usageFont(.section)
                                    .foregroundStyle(UsageColors.ink)
                                Text(verbatim: option.keyHint)
                                    .usageFont(.small)
                                    .foregroundStyle(UsageColors.muted)
                            }
                            Spacer()
                        }
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Button("Open") { openURL(URL(string: option.consoleURL)!) }
                        .buttonStyle(.bordered)
                        .accessibilityLabel("Open \(option.displayName) key page")
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
            }
            ForEach(CreditProviderID.notPossible) { option in
                LedgerDivider()
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: option.displayName)
                        .usageFont(.section)
                        .foregroundStyle(UsageColors.faint)
                    Text(verbatim: option.reason)
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.faint)
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(option.displayName): not available. \(option.reason)")
            }
        }
    }

    private func form(_ provider: CreditProviderID) -> some View {
        UsageCard {
            Text(verbatim: provider.displayName)
                .usageFont(.section)
                .foregroundStyle(UsageColors.ink)
            Text(verbatim: provider.keyHint)
                .usageFont(.small)
                .foregroundStyle(UsageColors.muted)

            TextField(provider.displayName, text: $label)
                .ledgerField()
                .accessibilityLabel("Name")

            if provider.hasRegion {
                SegmentedRow(options: CreditProviderID.regions.map { option in
                    SegmentOption(
                        id: CreditProviderID.regionLabel(option), title: CreditProviderID.regionLabel(option),
                        selected: region == option) { region = option }
                })
            }
            if provider.acceptsTeamID {
                TextField("Team id (only if the key is not team-scoped)", text: $teamID)
                    .ledgerField()
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            TextField("Low balance below (optional)", text: $threshold)
                .ledgerField()
                .keyboardType(.decimalPad)

            SecureField("Key", text: $key)
                .ledgerField()
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Text("Checked against \(provider.displayName) before it is saved, then kept in the keychain on this device only.")
                .usageFont(.small)
                .foregroundStyle(UsageColors.faint)

            if let failure {
                NoticeLine(message: failure, dot: UsageColors.negative)
            }

            HStack(spacing: 10) {
                Button { submit(provider) } label: {
                    if isWorking { ProgressView().tint(UsageColors.muted).controlSize(.small) } else { Text("Add") }
                }
                .buttonStyle(InkButtonStyle())
                .disabled(isWorking || key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button("Open console") {
                    if let url = URL(string: provider.consoleURL) { openURL(url) }
                }
                .buttonStyle(OutlineButtonStyle())

                Button("Back") { self.provider = nil; failure = nil }
                    .buttonStyle(TextActionStyle(label: UsageColors.muted))
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
        _threshold = State(initialValue: CreditThresholdField.display(entry.account.attributes[CreditAccount.lowThresholdKey]))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                UsageCard {
                    TextField("Name", text: $label)
                        .ledgerField()
                    TextField("Low balance below (empty for the default)", text: $threshold)
                        .ledgerField()
                        .keyboardType(.decimalPad)
                    Text("Below this the balance reads Low, below twice it Moderate. Ignored when the provider reports a total: the share left decides.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.faint)
                    if let failure {
                        NoticeLine(message: failure, dot: UsageColors.negative)
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
                    .buttonStyle(InkButtonStyle())
                }
                .padding(16)
                .readableWidth()
            }
            .background(UsageColors.ground)
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
