import SwiftUI
import UsageLimitsKit

/// The screen the app opens on: what is left, and when it comes back.
///
/// Everything shown here is decided in `UsageLimitsKit` — which accounts lead, which two
/// windows each shows, what counts as stale. This file only lays them out, which is what keeps
/// the two platforms saying the same thing about the same account.
struct OverviewScreen: View {

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: UsageStore

    /// Whether the list is in reordering mode.
    ///
    /// Owned here rather than taken from the environment, because leaving the screen should end
    /// it: an edit mode that persists across tabs leaves a user back on a list whose rows do not
    /// respond to a tap, with no memory of having asked for that.
    @State private var editMode: EditMode = .inactive

    var body: some View {
        let glance = store.glance
        return NavigationStack {
            List {
                Section {
                    ScreenHeader(title: "Usage Limits", stamp: stamp(glance))
                        .plainRow()
                        .moveDisabled(true)

                    // Until the cache has been read the lists are empty because nothing has
                    // asked yet, not because there are no accounts: drawing the empty state
                    // here is a false "No accounts yet" on every cold start.
                    if !store.hasLoaded {
                        LoadingOverview().plainRow().moveDisabled(true)
                    } else if !store.accounts.isEmpty {
                        // Never draggable: it is the headline, not one of the accounts, and a
                        // list that lets it be dropped between two cards makes the whole gesture
                        // read as broken.
                        LeadStrip(snapshot: glance, now: store.now)
                            .plainRow()
                            .moveDisabled(true)
                    }
                }

                if store.hasLoaded {
                    Section {
                        if store.accounts.isEmpty {
                            EmptyStateCard().plainRow()
                        } else {
                            ForEach(store.orderedAccounts) { account in
                                AccountCard(
                                    account: account,
                                    provider: store.accounts.first { $0.account.id == account.id }?.account.provider,
                                    now: store.now,
                                    tier: store.settings.showSubscriptionTier
                                        ? store.tierLabel(accountID: account.id) : nil,
                                    renewal: store.settings.showRenewalTime
                                        ? store.renewalLabel(accountID: account.id, now: store.now)
                                        : nil)
                                    .plainRow()
                            }
                            .onMove(perform: move)
                        }
                    }

                    // Prepaid balances: a separate list with its own screen, below the usage
                    // accounts and outside the reorder gesture.
                    Section {
                        SectionTitle(text: "Credits")
                            .padding(.top, 28)
                            .plainRow()
                            .moveDisabled(true)
                        ForEach(store.credits) { entry in
                            CreditCard(entry: entry, now: store.now, staleAfter: store.settings.staleAfter)
                                .plainRow()
                                .moveDisabled(true)
                        }
                        NavigationLink(store.credits.isEmpty ? "Add a credit balance" : "Manage credits") {
                            CreditsScreen()
                        }
                        .usageFont(.bodyStrong)
                        .foregroundStyle(UsageColors.ink)
                        .frame(minHeight: 44)
                        .plainRow()
                        .moveDisabled(true)
                    }
                }
            }
            .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: store.orderedAccounts.map(\.id))
            .readableWidth()
            .listStyle(.plain)
            .environment(\.editMode, $editMode)
            .scrollContentBackground(.hidden)
            .background(UsageColors.ground)
            .navigationTitle("Overview")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
            .toolbar {
                // `|| editMode.isEditing` is the half that matters. Gated on the count alone,
                // the button — and with it the only way OUT of reorder mode — vanished the
                // moment a second account was disconnected on another tab: a TabView child is
                // not torn down on a switch, so `editMode` came back still `.active`, the last
                // card stayed in drag mode, and pull-to-refresh was suppressed with no control
                // left to turn any of it off. Killing the app was the only exit.
                if store.accounts.count > 1 || editMode.isEditing {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(editMode.isEditing ? "Done" : "Reorder") {
                            withAnimation(reduceMotion ? nil : Animation.easeInOut(duration: 0.2)) { editMode = editMode.isEditing ? .inactive : .active }
                        }
                    }
                }
            }
            // And ended outright once there is nothing left to reorder, so the mode cannot
            // outlive its purpose even while the button is on screen.
            .onChange(of: store.accounts.count) { count in
                if count < 2 { editMode = .inactive }
            }
            // Suspended while reordering: a pull that starts on a row being dragged is a refresh
            // the user did not ask for, and it would replace the list under their finger.
            .refreshable { if !editMode.isEditing { await store.refresh() } }
        }
    }

    /// Hands the store the WHOLE new order rather than the pair that swapped.
    ///
    /// The repository renumbers every row it is given, so sending the complete list is what
    /// makes a partially-applied write impossible — and the list is short enough that there is
    /// nothing to save by sending less.
    private func move(from offsets: IndexSet, to destination: Int) {
        var ids = store.orderedAccounts.map(\.id)
        ids.move(fromOffsets: offsets, toOffset: destination)
        Task { await store.reorderAccounts(ids: ids) }
    }

    /// The one line that dates this screen: how many accounts, and the instant their numbers
    /// were read — or that a read is under way, which replaces the stamp rather than adding a
    /// spinner that would shift the layout.
    ///
    /// The instant, not an age: this screen does re-render on a clock, but naming the instant
    /// matches the widget, and one wording across both surfaces is worth more than the marginal
    /// readability of "12 minutes ago".
    private func stamp(_ glance: GlanceSnapshot) -> String {
        if !store.hasLoaded || store.isRefreshing { return "Refreshing…" }
        let accounts = glance.accountCount == 1 ? "1 account" : "\(glance.accountCount) accounts"
        guard let updated = glance.updatedAt else { return "\(accounts) · Never updated" }
        return "\(accounts) · As of \(Countdown.absolute(updated, now: store.now))"
    }
}

private extension View {
    /// A row that looks like the card it contains rather than like a table row.
    ///
    /// The overview was a `ScrollView` of cards until reordering arrived. `List` is what gives
    /// the drag its native feel — the lift, the gap, the haptic — and none of that is worth
    /// reimplementing on a drag gesture; stripping the row chrome is what keeps the design the
    /// ScrollView had.
    func plainRow() -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
    }
}

/// Keep the account, remaining quota and countdown together so the next reset is unambiguous.
private struct LeadStrip: View {

    let snapshot: GlanceSnapshot
    let now: Date

    var body: some View {
        let lead = snapshot.nextResettingAccount(at: now)
        let row = lead?.nextResettingRow(at: now) ?? lead?.representative(at: now)
        let stale = lead.map {
            [.stale, .error].contains($0.severity(at: now, staleAfter: snapshot.staleAfter))
                && $0.nextResettingRow(at: now) != nil
        } ?? false
        UsageCard(padding: 0, spacing: 0) {
            band(lead: lead, row: row, stale: stale)
            UsageColors.rule.frame(height: 1)
            HStack(spacing: 0) {
                cell("Connected accounts") {
                    Text("\(connected)/\(snapshot.accountCount)").usageFont(.figureLarge).foregroundStyle(UsageColors.ink)
                        .accessibilityLabel("\(connected)/\(snapshot.accountCount) connected")
                }
                Image(AppIconChoice.active.previewAsset)
                    .resizable().scaledToFit().frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)
                    .padding(.trailing, 16)
            }
        }
    }

    private func band(lead: GlanceAccount?, row: GlanceRow?, stale: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let lead, let row {
                // Aged to now, like every other surface: a stale account's last number is
                // still shown, but it stops speaking in a severity colour.
                let severity = stale ? Severity.stale : lead.displaySeverity(of: row, at: now, staleAfter: snapshot.staleAfter)
                let percent = UsageWindow.percentLabel(row.remainingPercent)
                Text(verbatim: "\(lead.title) · \(row.label)")
                    .usageFont(.small).foregroundStyle(UsageColors.muted).lineLimit(1)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: percent)
                        .usageFont(.lead).foregroundStyle(SeverityPalette.figureColor(severity))
                    Text("left").usageFont(.small).foregroundStyle(UsageColors.muted)
                }
                UsageBar(remainingPercent: row.remainingPercent, severity: severity)
                    .padding(.vertical, 4)
                if let resetAt = row.resetAt {
                    Text("Resets in \(Countdown.format(until: resetAt, from: now))")
                        .usageFont(.small).foregroundStyle(UsageColors.faint)
                }
            } else {
                Text("—").usageFont(.small).foregroundStyle(UsageColors.faint)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken(lead: lead, row: row))
    }

    private func cell<Value: View>(_ title: String, @ViewBuilder value: () -> Value) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).usageFont(.small).foregroundStyle(UsageColors.muted)
            value()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func spoken(lead: GlanceAccount?, row: GlanceRow?) -> String {
        guard let lead, let row else { return "No limit to report" }
        guard row.remainingPercent != nil else { return "\(lead.title), \(row.label): remaining unknown" }
        return "\(lead.title), \(row.label): \(UsageWindow.percentLabel(row.remainingPercent)) remaining"
    }

    /// Counted through the connection status, so an account that needs signing in again stops
    /// counting as connected.
    private var connected: Int {
        snapshot.accounts.filter { $0.connectionStatus == .connected }.count
    }
}

/// The overview's final layout in `raised` blocks at real size, so nothing jumps when the cache
/// arrives. No shimmer: it would animate a screen that is about to be replaced.
private struct LoadingOverview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            UsageCard {
                SkeletonBar(width: 0.4, height: 12)
                SkeletonBar(width: 0.3, height: 30)
                UsageBar(remainingPercent: nil, severity: .healthy)
            }
            AccountSkeletonCard()
            AccountSkeletonCard()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading usage")
    }
}

/// One account: who it is, how it is doing, and its two horizons.
private struct AccountCard: View {

    @EnvironmentObject private var store: UsageStore

    let account: GlanceAccount
    let provider: ProviderID?
    let now: Date
    /// The plan the provider reports — Plus, Pro, Max — or nil when it is switched off or the
    /// provider never said.
    let tier: String?
    /// When the long allowance comes back, when the user has asked to see it.
    let renewal: String?

    /// A failure the user can answer with another try, as opposed to one that needs a sign-in.
    private var canRetry: Bool {
        account.severity == .error && account.connectionStatus != .reconnectRequired
    }

    var body: some View {
        UsageCard {
            HStack(spacing: 10) {
                if let provider { ProviderBadge(provider: provider) }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(provider?.displayName ?? account.title)
                            .usageFont(.section)
                            .foregroundStyle(UsageColors.ink)
                        if let tier {
                            Text(verbatim: " · \(tier)")
                                .usageFont(.body)
                                .foregroundStyle(UsageColors.muted)
                        }
                    }
                    .lineLimit(1)
                    if let subtitle = account.subtitle {
                        Text(subtitle)
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.faint)
                    }
                    if let renewal {
                        Text(renewal)
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.faint)
                            .lineLimit(1)
                    }
                }

                Spacer()

                StatusMark(severity: account.severity, label: account.connectionStatus == .reconnectRequired ? "Reconnect" : nil)
            }

            if let message = account.statusMessage {
                NoticeLine(
                    message: message,
                    dot: account.severity == .error ? UsageColors.negative : UsageColors.warning,
                    retry: canRetry ? { Task { await store.refresh() } } : nil)
            }

            ForEach(Array(account.rows.enumerated()), id: \.offset) { _, row in
                WindowRow(
                    row: row, now: now,
                    severity: account.displaySeverity(of: row, at: now, staleAfter: store.settings.staleAfter))
            }
        }
    }
}

/// One quota window: its name, what is left, a bar, and when it returns.
struct WindowRow: View {

    let row: GlanceRow
    let now: Date
    /// What colours the figure and the bar. The caller's aged verdict where it has one; a row's
    /// own severity knows only its percentage, so left alone it paints a stale reading as fresh.
    var severity: Severity? = nil

    private var shown: Severity { severity ?? row.severity }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(row.label)
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
                Spacer()
                if row.remainingPercent == nil {
                    // A missing value is a small dash, not a figure-sized one: it is the absence
                    // of a number and should not hold the weight of one.
                    Text(remainingText).usageFont(.small).foregroundStyle(UsageColors.faint)
                } else {
                    Text(remainingText)
                        .usageFont(.figure)
                        .foregroundStyle(SeverityPalette.figureColor(shown))
                }
            }

            UsageBar(remainingPercent: row.remainingPercent, severity: shown)
                .accessibilityLabel("\(row.label): \(remainingText) remaining")

            if let resetAt = row.resetAt {
                Text("Resets in \(Countdown.format(until: resetAt, from: now))")
                    .usageFont(.small)
                    .foregroundStyle(UsageColors.faint)
            }
        }
    }

    /// An em dash, never a zero.
    ///
    /// A window whose percentage the provider did not report is unknown, and "0%" would state
    /// something it never said — in the one direction that would make a user stop working.
    private var remainingText: String {
        UsageWindow.percentLabel(row.remainingPercent)
    }
}

struct EmptyStateCard: View {
    var body: some View {
        DashedBox {
            Text("No accounts yet")
                .usageFont(.body)
                .foregroundStyle(UsageColors.ink)
            Text("Add a subscription to see how much usage is left and when it resets.")
                .usageFont(.body)
                .foregroundStyle(UsageColors.muted)
        }
    }
}
