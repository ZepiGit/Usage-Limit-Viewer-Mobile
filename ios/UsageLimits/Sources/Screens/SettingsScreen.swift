import SwiftUI
import UsageLimitsKit

/// Refresh cadence and which alerts are wanted.
///
/// The two low-quota tiers are separate switches rather than one adjustable threshold, because
/// the point of the second is that an account already warned at 18 % should still say something
/// at 8 % — a single slider cannot express "warn once, then escalate once".
struct SettingsScreen: View {

    @EnvironmentObject private var store: UsageStore

    private let intervals = [5, 15, 30, 60, 180]

    var body: some View {
        NavigationStack {
            Form {
                Section(header: LedgerHeader(String(localized: "Refresh"))) {
                    Picker("Every", selection: $store.settings.syncIntervalMinutes) {
                        ForEach(intervals, id: \.self) { minutes in
                            Text(label(for: minutes)).tag(minutes)
                        }
                    }
                    Text("iOS decides when to refresh and may refresh less often.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                    Text(stalenessNote)
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Notifications"))) {
                    // Titles follow the display mode; the thresholds do not move. "Below 20% left"
                    // and "Above 80% used" are one crossing, so the same switch drives both.
                    Toggle(warningTitle, isOn: $store.settings.notifications.notifyBelow20Percent)
                    Toggle(criticalTitle, isOn: $store.settings.notifications.notifyBelow10Percent)
                    Toggle("Limit exhausted", isOn: $store.settings.notifications.notifyOnExhausted)
                    Toggle("Reset approaching", isOn: $store.settings.notifications.notifyOnResetApproaching)
                    Toggle(
                        "Unused reset credit expires soon",
                        isOn: $store.settings.notifications.notifyOnResetCreditExpiring)
                    Text("Notify me when an unused Codex reset credit expires within 24 hours. A credit lets you reset an eligible usage limit.")
                        .usageFont(.small).foregroundStyle(UsageColors.muted)
                    Toggle("Sign-in expired", isOn: $store.settings.notifications.notifyOnAuthExpired)
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Display"))) {
                    NavigationLink("Provider icons") { ProviderIconsScreen() }
                    NavigationLink("App icon") { AppIconScreen() }
                    NavigationLink("Widget layouts") { WidgetPresetsScreen() }
                    Toggle(
                        "Show subscription tier",
                        isOn: $store.settings.showSubscriptionTier)
                    // A segmented picker draws only its options, so its title has to be written
                    // out; the Picker keeps its own label for VoiceOver, and this copy is hidden
                    // so the control is not announced twice.
                    Text("Show percentages as")
                        .accessibilityHidden(true)
                    Picker("Show percentages as", selection: $store.settings.percentDisplay) {
                        Text("Left").tag(PercentDisplay.left)
                        Text("Used").tag(PercentDisplay.used)
                    }
                    .pickerStyle(.segmented)
                    Text("Whether figures count down what is left or up what you have used.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Pinned accounts"))) {
                    Toggle("Show pinned accounts", isOn: $store.settings.statusIconsEnabled)
                    Text("Show up to three pinned usage accounts in the Lock Screen widget.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                    let pinnedKeys = selectedPinKeys
                    ForEach(store.accounts, id: \.account.id) { usage in
                        Toggle(isOn: pinBinding(for: usage.account.pinKey)) {
                            Text(verbatim: usage.account.label)
                        }
                        .disabled(
                            !store.settings.pinnedAccountKeys.contains(usage.account.pinKey)
                                && pinnedKeys.count >= PinnedAccounts.limit)
                    }
                    Text("\(pinnedKeys.count) of \(PinnedAccounts.limit) pinned")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Privacy"))) {
                    Text("Tokens are stored in the keychain and excluded from backups and device transfer. Moving to a new phone means signing in again.")
                        .usageFont(.small)
                        .foregroundStyle(UsageColors.muted)
                }
                .listRowBackground(UsageColors.surface)
                Section {
                    Link(destination: URL(string: "https://github.com/ZepiGit")!) {
                        Text(verbatim: "GitHub · ZepiGit")
                    }
                }
                .listRowBackground(UsageColors.surface)
            }
            .accessibilityIdentifier("settings-form")
            .readableWidth()
            .usageFont(.body)
            .foregroundStyle(UsageColors.ink)
            .ledgerList()
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
        }
    }

    /// The pinned accounts that count, as keys: only pins for an account that exists now, at
    /// most the limit, in account order.
    private var selectedPinKeys: [String] {
        PinnedAccounts.select(
            enabled: true,
            pinnedIDs: store.settings.pinnedAccountKeys,
            usageAccountIDs: store.accounts.map { $0.account.pinKey })
    }

    private func pinBinding(for pinKey: String) -> Binding<Bool> {
        Binding(
            get: { store.settings.pinnedAccountKeys.contains(pinKey) },
            set: { setPinned($0, pinKey: pinKey) })
    }

    private func setPinned(_ pinned: Bool, pinKey: String) {
        if pinned {
            let selected = selectedPinKeys
            guard !store.settings.pinnedAccountKeys.contains(pinKey),
                selected.count < PinnedAccounts.limit else { return }
            store.settings.pinnedAccountKeys = selected + [pinKey]
        } else {
            store.settings.pinnedAccountKeys.removeAll { $0 == pinKey }
        }
    }

    private var warningTitle: String {
        store.settings.percentDisplay == .used
            ? String(localized: "Above 80% used") : String(localized: "Below 20% left")
    }

    private var criticalTitle: String {
        store.settings.percentDisplay == .used
            ? String(localized: "Above 90% used") : String(localized: "Below 10% left")
    }

    private func label(for minutes: Int) -> String {
        minutes < 60 ? String(localized: "\(minutes) minutes") : String(localized: "\(minutes / 60) hours")
    }

    /// Says out loud what the interval implies, because the two are linked and the link is not
    /// obvious: pick a three-hour refresh and data three hours old is normal, not suspect.
    private var stalenessNote: String {
        let minutes = Int(store.settings.staleAfter / 60)
        let text = minutes < 120 ? String(localized: "\(minutes) minutes") : String(localized: "\(minutes / 60) hours")
        return String(localized: "Numbers older than \(text) are marked stale.")
    }
}
