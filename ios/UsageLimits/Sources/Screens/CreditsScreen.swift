import SwiftUI
import UsageLimitsKit

/// Prepaid and API balances: what is left to spend, not a quota that resets.
///
/// Reached from the Overview. Every number, severity and rule comes from `UsageLimitsKit`;
/// this file only lays them out.
struct CreditsScreen: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

    @EnvironmentObject private var store: UsageStore
    @State private var isAdding = false
    @State private var editing: CreditEntry?
    @State private var removing: CreditEntry?

    /// The name the confirmation uses for the balance being removed.
    private var removalName: String {
        removing?.account.label ?? String(localized: "this balance")
    }

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
                        .foregroundStyle(colors.muted)
                }
                .padding(.top, 8)
            }
            .padding(16)
            .readableWidth()
        }
        .background(colors.ground)
        .navigationTitle("Credits")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await store.refresh() }
        .sheet(isPresented: $isAdding) { AddCreditSheet() }
        .sheet(item: $editing) { entry in EditCreditSheet(entry: entry) }
        .confirmationDialog(
            "Remove \(removalName)?",
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
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

    @EnvironmentObject private var store: UsageStore

    let entry: CreditEntry
    let now: Date
    let staleAfter: TimeInterval
    var preview = false
    var onEdit: (() -> Void)?
    var onRemove: (() -> Void)?

    private func updatedText(since fetchedAt: Date) -> String {
        let seconds = Int(max(0, now.timeIntervalSince(fetchedAt)))
        return Countdown.updatedLine(secondsAgo: seconds, texts: LocalizedCountdownTexts())
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
        UsageCard(padding: preview ? 8 : 16, spacing: preview ? 6 : 12) {
            HStack(spacing: preview ? 8 : 10) {
                ProviderMark(assetName: entry.account.provider.iconAssetName)
                    .frame(width: preview ? 16 : 30, height: preview ? 16 : 30)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: entry.account.label)
                        .usageFont(preview ? .smallStrong : .section)
                        .foregroundStyle(colors.ink)
                    if !preview && !subline.isEmpty {
                        Text(verbatim: subline)
                            .usageFont(.small)
                            .foregroundStyle(colors.faint)
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
                            .foregroundStyle(colors.muted)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("More")
                }
            }

            if glance.balances.isEmpty {
                Text("No balance read yet")
                    .usageFont(.body)
                    .foregroundStyle(colors.muted)
            }
            ForEach(Array(glance.balances.enumerated()), id: \.offset) { _, balance in
                BalanceRow(
                    balance: balance, severity: severity, now: now,
                    calendar: store.presentationCalendar, preview: preview)
            }

            if let error = entry.snapshot?.error {
                NoticeLine(
                    message: error, dot: colors.negative,
                    retry: { Task { await store.refresh() } })
            }
        }
    }
}

private struct BalanceRow: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.locale) private var locale

    let balance: CreditBalance
    /// The account's aged severity: stale data must not keep its last colour.
    let severity: Severity
    let now: Date
    let calendar: Calendar
    let preview: Bool

    private var amount: String { money(balance.amount) }

    private func money(_ amount: Decimal) -> String {
        CreditFormat.string(amount, currency: balance.currency, locale: locale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if balance.unlimited {
                Text(verbatim: DisplayText.creditBalanceLabel(balance.label ?? "No limit"))
                    .usageFont(.body)
                    .foregroundStyle(colors.ink)
                Text("\(amount) spent this month")
                    .usageFont(.body)
                    .foregroundStyle(colors.muted)
            } else {
                // Money is always ink — status lives on the card's mark — and a real zero
                // prints as itself.
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) { figures }
                        .fixedSize(horizontal: true, vertical: false)
                    VStack(alignment: .leading, spacing: 4) { figures }
                }
                if let percent = balance.remainingPercent, let total = balance.total {
                    // Money, not a quota window: the percentage toggle does not apply here.
                    UsageBar(remainingPercent: percent, severity: severity)
                        .accessibilityLabel("\(amount) of \(money(total)) left")
                }
                // A capped key limit refills on a period; the app's existing absolute stamp says when.
                if let stamp = Countdown.resetLine(
                    resetAt: balance.resetsAt, now: now, calendar: calendar,
                    texts: LocalizedCountdownTexts()) {
                    Text(verbatim: stamp)
                        .usageFont(.small)
                        .foregroundStyle(colors.faint)
                }
            }
        }
    }

    @ViewBuilder private var figures: some View {
        Text(verbatim: amount)
            .usageFont(preview ? .rowMoney : .money)
            .foregroundStyle(colors.ink)
        if let total = balance.total {
            Text("of \(money(total))").usageFont(.body).foregroundStyle(colors.muted)
        }
        if let label = balance.label {
            Text(verbatim: DisplayText.creditUsedBalanceLabel(
                label, currency: balance.currency, locale: locale)
                ?? DisplayText.creditBalanceLabel(label))
                .usageFont(.small).foregroundStyle(colors.muted)
        }
    }
}

// MARK: - Add

struct AddCreditSheet: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

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
            .background(colors.ground)
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
                                    .foregroundStyle(colors.ink)
                                Text(verbatim: DisplayText.creditKeyHint(option))
                                    .usageFont(.small)
                                    .foregroundStyle(colors.muted)
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
                        .foregroundStyle(colors.faint)
                    Text(verbatim: DisplayText.creditUnavailableReason(option.reason))
                        .usageFont(.small)
                        .foregroundStyle(colors.faint)
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(String(localized: "\(option.displayName): not available. \(DisplayText.creditUnavailableReason(option.reason))"))
            }
        }
    }

    private func form(_ provider: CreditProviderID) -> some View {
        UsageCard {
            Text(verbatim: provider.displayName)
                .usageFont(.section)
                .foregroundStyle(colors.ink)
            Text(verbatim: DisplayText.creditKeyHint(provider))
                .usageFont(.small)
                .foregroundStyle(colors.muted)

            TextField(provider.displayName, text: $label)
                .ledgerField()
                .accessibilityLabel("Name")

            if provider.hasRegion {
                SegmentedRow(options: CreditProviderID.regions.map { option in
                    SegmentOption(
                        id: CreditProviderID.regionLabel(option), title: DisplayText.creditRegionLabel(option),
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
                .foregroundStyle(colors.faint)

            if let failure {
                NoticeLine(message: failure, dot: colors.negative)
            }

            HStack(spacing: 10) {
                Button { submit(provider) } label: {
                    if isWorking {
                        ActivityMark(still: .progressIndicator)
                    } else { Text("Add") }
                }
                .buttonStyle(InkButtonStyle())
                .disabled(isWorking || key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button("Open console") {
                    if let url = URL(string: provider.consoleURL) { openURL(url) }
                }
                .buttonStyle(OutlineButtonStyle())

                Button("Back") { self.provider = nil; failure = nil }
                    .buttonStyle(TextActionStyle(label: colors.muted))
                    .disabled(isWorking)
            }
        }
    }

    private func submit(_ provider: CreditProviderID) {
        guard let figure = CreditThresholdField.parse(threshold) else {
            failure = String(localized: "Enter the low-balance figure as a positive number, or leave it empty.")
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
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

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
                    Text("Compact Credits uses this threshold. For the balance status, a reported total takes precedence.")
                        .usageFont(.small)
                        .foregroundStyle(colors.faint)
                    if let failure {
                        NoticeLine(message: failure, dot: colors.negative)
                    }
                    Button("Save") {
                        guard let figure = CreditThresholdField.parse(threshold) else {
                            failure = String(localized: "Enter a positive number, or leave it empty.")
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
            .background(colors.ground)
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
