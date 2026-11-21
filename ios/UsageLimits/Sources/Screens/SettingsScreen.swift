import SwiftUI
import UsageLimitsKit

/// Refresh cadence and which alerts are wanted.
///
/// The two low-quota tiers are separate switches rather than one adjustable threshold, because
/// the point of the second is that an account already warned at 18 % should still say something
/// at 8 % — a single slider cannot express "warn once, then escalate once".
struct SettingsScreen: View {

    @EnvironmentObject private var store: UsageStore
    @Environment(\.dynamicTypeSize) private var typeSize

    private let intervals = [5, 15, 30, 60, 180]

    var body: some View {
        #if DEBUG
        let _ = SettingsTrace.settingsBody()
        #endif
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

                Section {
                    providerIconsLink
                    if ClaudeProviderBadge.isClaudeCode(claudeIconChoice) {
                        Toggle("Animate Claude Code icon", isOn: $store.settings.animateClaudeCodeIcon)
                            .accessibilityIdentifier("settings-animate-claude-code-icon")
                    }
                    NavigationLink("App icon") { AppIconScreen() }
                    NavigationLink("Widget layouts") { WidgetPresetsScreen() }
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Display"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Show percentages as").accessibilityHidden(true)
                        Text("What is left, or what you have used. Applies to the whole app and to every widget.")
                            .usageFont(.small).foregroundStyle(UsageColors.muted)
                        Picker("Show percentages as", selection: $store.settings.percentDisplay) {
                            Text("Left").tag(PercentDisplay.left)
                            Text("Used").tag(PercentDisplay.used)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("settings-percent-display")
                    }
                    displayPreview
                }
                .listRowBackground(UsageColors.surface)

                Section(header: LedgerHeader(String(localized: "Overview"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Layout").accessibilityHidden(true)
                        Text("List shows every window per account. Dials show one ring per account, like the ring widgets.")
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.muted)
                            #if DEBUG
                            .accessibilityIdentifier(layoutHelpIdentifier)
                            #endif
                        Picker("Layout", selection: $store.settings.overviewLayout) {
                            Text("List").tag(OverviewLayout.list)
                            Text("Dials").tag(OverviewLayout.dials)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("settings-overview-layout")
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Toggle("Show credits", isOn: $store.settings.overviewShowCredits)
                            .accessibilityIdentifier("settings-overview-show-credits")
                        Text("Hide prepaid balances from Overview without removing them.")
                            .usageFont(.small).foregroundStyle(UsageColors.muted)
                            #if DEBUG
                            .accessibilityIdentifier(creditHelpIdentifier)
                            #endif
                    }
                    if store.settings.overviewShowCredits {
                        creditLayoutControl
                        VStack(alignment: .leading, spacing: 4) {
                            Picker("Show first", selection: $store.settings.overviewShowFirst) {
                                Text("Usage providers").tag(OverviewShowFirst.usageProviders)
                                Text("Credit accounts").tag(OverviewShowFirst.creditAccounts)
                            }
                            .pickerStyle(.menu)
                            .accessibilityIdentifier("settings-overview-show-first")
                            Text("Which block sits at the top of Overview.")
                                .usageFont(.small).foregroundStyle(UsageColors.muted)
                        }
                    }
                    Toggle("Show subscription tier", isOn: $store.settings.showSubscriptionTier)
                    NavigationLink("Manage credit accounts") { CreditsScreen() }
                        .accessibilityIdentifier("settings-manage-credit-accounts")
                    overviewPreview
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
            .disabled(!store.settingsLoaded)
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

    private var claudeIconChoice: ProviderIconChoice {
        ProviderIconCatalog.selected(for: .claude, id: store.settings.providerIcons[ProviderID.claude.rawValue])
    }

    private var providerIconsLink: some View {
        let mood: ClaudeCodeMood = if ClaudeProviderBadge.isClaudeCode(claudeIconChoice) {
            store.glance.overviewOrderedAccounts(at: store.now)
                .first { $0.providerID == ProviderID.claude.rawValue }?.claudeCodeMood(at: store.now) ?? .idle
        } else {
            .idle
        }
        return NavigationLink { ProviderIconsScreen() } label: {
            HStack(spacing: 16) {
                ClaudeProviderBadge(provider: .claude, mood: mood,
                    size: 28, staticSize: 30, figureSlotSize: 30)
                #if DEBUG
                .environment(\.claudeAnimationDiagnosticID, "provider:provider")
                #endif
                VStack(alignment: .leading, spacing: 2) {
                    Text("Provider icons")
                    Text(verbatim: DisplayText.providerIconLabel(claudeIconChoice.label))
                        .usageFont(.small).foregroundStyle(UsageColors.muted)
                }
            }
        }
        .accessibilityIdentifier("settings-provider-icons")
    }

    private var creditLayoutControl: some View {
        VStack(alignment: .leading, spacing: 4) {
            if typeSize >= .accessibility1 {
                creditLayoutPicker.pickerStyle(.menu)
            } else {
                Text("Credit accounts").accessibilityHidden(true)
                creditLayoutPicker.pickerStyle(.segmented)
            }
            Text("Normal lists the balances. Compact shows a count and the balance furthest below its threshold.")
                .usageFont(.small).foregroundStyle(UsageColors.muted)
        }
    }

    private var creditLayoutPicker: some View {
        Picker("Credit accounts", selection: $store.settings.overviewCreditLayout) {
            Text("Normal").tag(OverviewCreditLayout.normal)
            Text("Compact").tag(OverviewCreditLayout.compact)
        }
        .accessibilityIdentifier("settings-overview-credit-layout")
    }

    #if DEBUG
    private var layoutHelpIdentifier: String {
        UsageLimitsApp.isUITesting && store.uiTestSavedSettings?.overviewLayout == store.settings.overviewLayout
            ? "settings-overview-layout-saved" : "settings-overview-layout-help"
    }

    private var creditHelpIdentifier: String {
        UsageLimitsApp.isUITesting && store.uiTestSavedSettings == store.settings
            ? "settings-overview-credits-saved" : "settings-overview-credits-help"
    }
    #endif

    private var displayPreview: some View {
        let account = SettingsUsagePreview.snapshot.accounts[0]
        let row = account.candidates[0]
        let window = DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
        return previewPanel("settings-display-preview") {
            WindowRow(row: row, now: SettingsUsagePreview.now, validity: .current,
                mode: store.settings.percentDisplay, displayPreviewTitle: String(localized: "\(account.title) · \(window)"))
        }
    }

    @ViewBuilder
    private var overviewPreview: some View {
        let settings = store.settings
        let sample = SettingsUsagePreview.projection
        let preview = previewPanel(settings.overviewLayout == .list ? "settings-preview-list" : "settings-preview-dials") {
            OverviewBlocks(showCredits: settings.overviewShowCredits, showFirst: settings.overviewShowFirst) {
                if settings.overviewLayout == .list {
                    ForEach(sample.accounts) { entry in
                        if entry.id != sample.accounts.first?.id { LedgerDivider(inset: 0) }
                        OverviewAccountContent(entry: entry, staleAfter: SettingsUsagePreview.snapshot.staleAfter,
                            now: SettingsUsagePreview.now, mode: settings.percentDisplay, calendar: SettingsUsagePreview.calendar,
                            showSubscriptionTier: settings.showSubscriptionTier, preview: true)
                    }
                } else {
                    OverviewDials(entries: sample.accounts, now: SettingsUsagePreview.now,
                        staleAfter: SettingsUsagePreview.snapshot.staleAfter, mode: settings.percentDisplay,
                        calendar: SettingsUsagePreview.calendar, preview: true)
                }
            } credits: {
                OverviewCredits(entries: SettingsUsagePreview.credits, now: SettingsUsagePreview.now,
                    staleAfter: SettingsUsagePreview.snapshot.staleAfter, layout: settings.overviewCreditLayout, preview: true)
            }
        }
        #if DEBUG
        if SettingsTrace.gapVariant == "no-figure" {
            preview.environment(\.providerIconChoices,
                settings.providerIcons.merging([ProviderID.claude.rawValue: "claude-color"]) { _, icon in icon })
        } else {
            preview
        }
        #else
        preview
        #endif
    }

    private func previewPanel<Content: View>(_ identifier: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: String(localized: "Preview").localizedUppercase)
                .usageFont(.micro).tracking(0.66).foregroundStyle(UsageColors.faint)
                .accessibilityAddTraits(.isHeader)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(UsageColors.ground, in: RoundedRectangle(cornerRadius: UsageRadius.card))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
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

// These are examples, never the current user's account data. The same projection that renders
// Overview ranks them and selects the dial headline.
private enum SettingsUsagePreview {
    static let now = Date(timeIntervalSince1970: 1_789_206_060)
    static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }()
    static let claude = account(.claude, plan: "Pro", used: (82, 39))
    static let codex = account(.codex, plan: "Plus", used: (28, 56))
    static let snapshot = GlanceModel.build([claude, codex], now: now, scope: .allAccounts)
    static let projection = snapshot.overview(filter: .all, at: now, in: calendar.timeZone)
    static let credits = OverviewCreditSamples.entries(at: now)

    private static func account(_ provider: ProviderID, plan: String, used: (Double, Double)) -> AccountUsage {
        let id = "preview-\(provider.rawValue)"
        let account = ProviderAccount(id: id, provider: provider, externalAccountID: id, email: nil,
            displayName: nil, plan: plan, credentialReference: "", createdAt: now, lastSuccessfulSync: now)
        let windows = [
            UsageWindow(id: "short", label: "5h limit", category: .fiveHour, usedPercent: used.0,
                periodSeconds: 18_000, resetAt: now.addingTimeInterval(2_520), exhausted: false),
            UsageWindow(id: "week", label: "Weekly", category: .weekly, usedPercent: used.1,
                periodSeconds: 604_800, resetAt: now.addingTimeInterval(273_600), exhausted: false)
        ]
        return AccountUsage(account: account, snapshot: UsageSnapshot(accountID: id, fetchedAt: now,
            status: .ok, windows: windows, connectionStatus: .connected))
    }
}
