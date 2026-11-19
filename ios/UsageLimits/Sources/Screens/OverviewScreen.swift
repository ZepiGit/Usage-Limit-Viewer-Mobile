import SwiftUI
import UsageLimitsKit

/// The screen the app opens on: what is left, and when it comes back.
///
/// Everything shown here is decided in `UsageLimitsKit` — which accounts lead, which two
/// windows each shows, what counts as stale. This file only lays them out, which is what keeps
/// the two platforms saying the same thing about the same account.
struct OverviewScreen: View {

    @ReducesMotion private var reduceMotion
    @EnvironmentObject private var store: UsageStore

    /// Whether a change of the rows is one to animate: not before the first content is on
    /// screen, which replaces the skeleton in one cut.
    @State private var listMotionArmed = false

    var body: some View {
        let glance = store.glance
        // Built once per evaluation: the rows and the motion's value are the same list.
        let ordered = glance.overviewOrderedAccounts(at: store.now)
        return NavigationStack {
            List {
                Section {
                    ScreenHeader(title: "Usage Limits", stamp: stamp(glance))
                        .plainRow()

                    // Until the cache has been read the lists are empty because nothing has
                    // asked yet, not because there are no accounts: drawing the empty state
                    // here is a false "No accounts yet" on every cold start.
                    if !store.hasLoaded {
                        LoadingOverview().plainRow()
                    } else if !store.accounts.isEmpty {
                        LeadStrip(
                            snapshot: glance, now: store.now, mode: store.settings.percentDisplay,
                            toggleMode: toggleMode)
                            .plainRow()
                    }
                }

                if store.hasLoaded {
                    Section {
                        if store.accounts.isEmpty {
                            EmptyStateCard().plainRow()
                        } else {
                            ForEach(ordered) { account in
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
                        }
                    }

                    Section {
                        SectionTitle(text: String(localized: "Credits"))
                            .padding(.top, 28)
                            .plainRow()
                        ForEach(store.credits) { entry in
                            CreditCard(entry: entry, now: store.now, staleAfter: store.settings.staleAfter)
                                .plainRow()
                        }
                        // Both arms are looked up here: a ternary of two literals would be taken for a
                        // plain String and shown as written.
                        NavigationLink(
                            store.credits.isEmpty
                                ? String(localized: "Add a credit balance")
                                : String(localized: "Manage credits")
                        ) {
                            CreditsScreen()
                        }
                        .usageFont(.bodyStrong)
                        .foregroundStyle(UsageColors.ink)
                        .frame(minHeight: 44)
                        .plainRow()
                    }
                }
            }
            // Both lists in one value, so a credit row that arrives or leaves moves its
            // neighbours the way an account row does.
            .listMotion(.move, value: [ordered.map(\.id), store.credits.map(\.id)], armed: listMotionArmed)
            .armingListMotion($listMotionArmed, loaded: store.hasLoaded)
            .readableWidth()
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(UsageColors.ground)
            .navigationTitle("Overview")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
            .refreshable { await store.refresh() }
        }
    }

    /// Flips the global percentage mode, through the same property the Settings picker writes, so
    /// persistence, the widget snapshot and the timeline reload follow without a second path.
    ///
    /// Inside an animation, so whatever else depends on the mode changes with it. The figures and
    /// the bars bring their own motion: each figure fades through together with its side word
    /// (`figureUnit`) while its bar moves. Under Reduce Motion the animation is nil and the flip
    /// is applied at once.
    private func toggleMode() {
        withAnimation(Motion.contentSwap.animation(reduceMotion: reduceMotion)) {
            store.settings.percentDisplay = store.settings.percentDisplay == .used ? .left : .used
        }
    }

    /// The one line that dates this screen: how many accounts, and the instant their numbers
    /// were read — or that a read is under way, which replaces the stamp rather than adding a
    /// spinner that would shift the layout.
    ///
    /// The instant, not an age: this screen does re-render on a clock, but naming the instant
    /// matches the widget, and one wording across both surfaces is worth more than the marginal
    /// readability of "12 minutes ago".
    private func stamp(_ glance: GlanceSnapshot) -> String {
        if !store.hasLoaded || store.isRefreshing { return String(localized: "Refreshing…") }
        let accounts = String(localized: "\(glance.accountCount) accounts")
        guard let updated = glance.updatedAt else {
            let never = String(localized: "Never updated")
            return String(localized: "\(accounts) · \(never)")
        }
        let asOf = Countdown.asOfLine(updatedAt: updated, now: store.now, texts: LocalizedCountdownTexts()) ?? ""
        return String(localized: "\(accounts) · \(asOf)")
    }
}

private extension View {
    /// A row that looks like the card it contains rather than like a table row.
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
    let mode: PercentDisplay
    /// Tapping the figure switches the mode. Scoped to the figure: the rest of the band has no
    /// action of its own, and a whole-card tap would invite accidental flips while scrolling.
    let toggleMode: () -> Void

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
                cell(String(localized: "Connected accounts")) {
                    Text(verbatim: "\(connected)/\(snapshot.accountCount)").usageFont(.figureLarge).foregroundStyle(UsageColors.ink)
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
                // Built from the raw row, so an aged severity cannot turn an exhausted window into
                // a partial one in USED; the lead's `stale` verdict outranks the row's own validity.
                let p = WindowPresentation(
                    remaining: row.remainingPercent, severity: row.severity,
                    validity: stale ? ReadingValidity.stale : lead.readingValidity(of: row, at: now, staleAfter: snapshot.staleAfter),
                    mode: mode)
                let percent = PercentText.figure(p)
                let side = PercentText.sideWord(mode)
                let rowLabel = DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
                Text(verbatim: String(localized: "\(lead.title) · \(rowLabel)"))
                    .usageFont(.small).foregroundStyle(UsageColors.muted).lineLimit(1)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: percent)
                        .usageFont(.lead).foregroundStyle(SeverityPalette.figureColor(severity))
                    Text(side).usageFont(.small).foregroundStyle(UsageColors.muted)
                }
                // The pair is one unit, keyed by what it reads: on a mode flip or a refresh the
                // old figure leaves with its own word before the new pair arrives, so "60%" is
                // never drawn beside "used".
                .figureUnit(key: [percent, side], anchor: .leading)
                .contentShape(Rectangle())
                .onTapGesture(perform: toggleMode)
                UsageBar(presentation: p)
                    .subject(LeadSubject(lead: lead, row: row))
                    .padding(.vertical, 4)
                if let resetAt = row.resetAt {
                    Text(verbatim: Countdown.resetsInLine(until: resetAt, from: now, texts: LocalizedCountdownTexts()))
                        .usageFont(.small).foregroundStyle(UsageColors.faint)
                }
            } else {
                Text(verbatim: "—").usageFont(.small).foregroundStyle(UsageColors.faint)
            }
        }
        // Another account or another window leading is another subject, not this one with new
        // values: the band is replaced in one cut, so the figure does not fade out under the
        // new account's name and the bar does not glide from the old window's fill.
        .subject(LeadSubject(lead: lead, row: row))
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken(lead: lead, row: row))
        // The figure's tap is unreachable once the children are folded into one element, so the
        // switch is offered as a named action as well.
        .accessibilityHint(switchHint)
        .accessibilityAction(named: switchActionName, toggleMode)
        // For UI tests, which reach the figure's tap through this element's frame.
        .accessibilityIdentifier("overview-lead")
    }

    /// Worded as the destination, not the current state: the current mode is already in the
    /// spoken label, so the rotor entry has to say what activating it will change.
    private var switchActionName: String {
        mode == .used ? String(localized: "Show percentage left") : String(localized: "Show percentage used")
    }

    /// A whole sentence per mode rather than the action name lower-cased into one: lower-casing
    /// is wrong in a language that capitalises its nouns.
    private var switchHint: String {
        mode == .used
            ? String(localized: "Use the actions rotor to show percentage left")
            : String(localized: "Use the actions rotor to show percentage used")
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
        guard let lead, let row else { return String(localized: "No limit to report") }
        // Validity never changes the figure, so the lead's `stale` override is not needed here.
        let p = lead.presentation(of: row, at: now, staleAfter: snapshot.staleAfter, mode: mode)
        let figure = PercentText.figure(p)
        let rowLabel = DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
        // Gated on the figure rather than on the raw percentage, so the sentence agrees with what
        // is drawn: an exhausted window with no percentage reads "100% used" in USED and is
        // still "remaining unknown" in LEFT, as before.
        guard p.hasFigure else { return String(localized: "\(lead.title), \(rowLabel): remaining unknown") }
        let said = PercentText.spoken(figure, mode)
        return String(localized: "\(lead.title), \(rowLabel): \(said)")
    }

    /// Counted through the connection status, so an account that needs signing in again stops
    /// counting as connected.
    private var connected: Int {
        snapshot.accounts.filter { $0.connectionStatus == .connected }.count
    }
}

/// Which account and which of its windows the lead strip shows; equal values for the empty strip.
private struct LeadSubject: Hashable {
    let account: String?
    let window: String?

    init(lead: GlanceAccount?, row: GlanceRow?) {
        account = lead?.id
        window = row?.sourceWindowID
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

                StatusMark(
                    severity: account.severity,
                    label: account.connectionStatus == .reconnectRequired ? String(localized: "Reconnect") : nil)
            }

            if let message = account.statusMessage {
                NoticeLine(
                    message: message,
                    dot: account.severity == .error ? UsageColors.negative : UsageColors.warning,
                    retry: canRetry ? { Task { await store.refresh() } } : nil)
            }

            // Identified by the window a row shows, not by its position: when another window
            // takes a slot it is a new row, so its figure and bar do not animate from the values
            // of the window that was there.
            ForEach(account.rows, id: \.sourceWindowID) { row in
                WindowRow(
                    row: row, now: now,
                    severity: account.displaySeverity(of: row, at: now, staleAfter: store.settings.staleAfter),
                    validity: account.readingValidity(of: row, at: now, staleAfter: store.settings.staleAfter),
                    mode: store.settings.percentDisplay)
            }
        }
    }
}

/// One quota window: its name, what is left, a bar, and when it returns.
struct WindowRow: View {

    let row: GlanceRow
    let now: Date
    /// What colours the figure. The caller's aged verdict where it has one; a row's own severity
    /// knows only its percentage, so left alone it paints a stale reading as fresh.
    var severity: Severity? = nil
    /// Whether the reading can be trusted, which decides how the bar is painted. Separate from
    /// `severity`, which only colours the figure. Not defaulted: a caller that forgets it would
    /// paint a stale reading as fresh.
    let validity: ReadingValidity
    /// Which side the figure and the bar count. Not defaulted: a caller that forgets it would
    /// silently show LEFT on a screen the user switched to USED.
    let mode: PercentDisplay

    private var shown: Severity { severity ?? row.severity }
    /// Built from the raw row — an aged `severity` replaces it for the figure's colour only.
    private var presentation: WindowPresentation {
        WindowPresentation(remaining: row.remainingPercent, severity: row.severity, validity: validity, mode: mode)
    }
    private let segmentColors = [UsageColors.data, UsageColors.ink, UsageColors.faint, UsageColors.muted]

    /// The window's name: the three horizons the app names itself are translated, a provider's
    /// own label is shown as it came.
    private var rowLabel: String {
        DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
    }

    /// "left" or "used": the word that says which side the figure counts.
    private var sideWord: String { PercentText.sideWord(mode) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: rowLabel)
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
                Spacer()
                // The figure and its side word, or the dash that stands for a missing figure, as
                // one unit keyed by what it reads: it fades through as a whole while the bar
                // moves, on a refresh as on a mode flip, so the old figure is never drawn beside
                // the new side word and a dash never beside a figure.
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    if !presentation.hasFigure {
                        // A missing value is a small dash, not a figure-sized one: it is the
                        // absence of a number and should not hold the weight of one. Gated on the
                        // figure, so an exhausted window with no percentage still reads "100%" in
                        // USED.
                        Text(figureText).usageFont(.small).foregroundStyle(UsageColors.faint)
                    } else {
                        Text(figureText)
                            .usageFont(.figure)
                            .foregroundStyle(SeverityPalette.figureColor(shown))
                        Text(sideWord).usageFont(.small).foregroundStyle(UsageColors.muted)
                    }
                }
                .figureUnit(key: presentation.hasFigure ? [figureText, sideWord] : [figureText], anchor: .trailing)
                .subject(row.sourceWindowID)
            }

            if row.segments.isEmpty {
                UsageBar(presentation: presentation)
                    .subject(row.sourceWindowID)
                    .accessibilityLabel(String(localized: "\(rowLabel): \(PercentText.spoken(figureText, mode))"))
            } else {
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(UsageColors.rule)
                        ForEach(row.segments.indices, id: \.self) { index in
                            let start = min(row.segments.prefix(index).reduce(0) { $0 + $1.usedPercent }, totalUsed)
                            let end = min(start + row.segments[index].usedPercent, totalUsed)
                            Rectangle().fill(segmentColors[index % segmentColors.count])
                                .frame(width: geometry.size.width * CGFloat((end - start) / 100))
                                .offset(x: geometry.size.width * CGFloat(start / 100))
                        }
                        if totalUsed - segmentTotal >= 0.5 {
                            Rectangle().fill(UsageColors.faint)
                                .frame(width: geometry.size.width * CGFloat((totalUsed - segmentTotal) / 100))
                                .offset(x: geometry.size.width * CGFloat(segmentTotal / 100))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                }
                .frame(height: 6)
                .accessibilityLabel(String(localized: "\(rowLabel): \(PercentText.spoken(figureText, mode))"))
                Text("Usage breakdown").usageFont(.small).foregroundStyle(UsageColors.muted)
                ForEach(row.segments.indices, id: \.self) { index in
                    HStack(spacing: 6) {
                        Circle().fill(segmentColors[index % segmentColors.count]).frame(width: 6, height: 6)
                            .accessibilityHidden(true)
                        Text(verbatim: segmentLegend(row.segments[index]))
                            .usageFont(.small).foregroundStyle(UsageColors.muted)
                    }
                }
            }

            if let resetAt = row.resetAt {
                Text(verbatim: Countdown.resetsInLine(until: resetAt, from: now, texts: LocalizedCountdownTexts()))
                    .usageFont(.small)
                    .foregroundStyle(UsageColors.faint)
            }
        }
    }

    /// "Opus usage: 20%": the segment's own label is data, and its share is a used figure whatever
    /// the mode, since the bar it belongs to always draws usage.
    private func segmentLegend(_ segment: UsageSegment) -> String {
        let token = UsageWindow.percentToken(segment.usedPercent, exhausted: false, mode: .left)
        let share = PercentText.figure(token: token)
        return String(localized: "\(segment.label) usage: \(share)")
    }

    private var totalUsed: Double { min(row.remainingPercent.map { 100 - $0 } ?? row.segments.reduce(0) { $0 + $1.usedPercent }, 100) }
    private var segmentTotal: Double { min(row.segments.reduce(0) { $0 + $1.usedPercent }, totalUsed) }

    /// An em dash, never a zero.
    ///
    /// A window whose percentage the provider did not report is unknown, and "0%" would state
    /// something it never said — in the one direction that would make a user stop working.
    private var figureText: String {
        PercentText.figure(presentation)
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
