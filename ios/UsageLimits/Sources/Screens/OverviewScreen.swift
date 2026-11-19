import SwiftUI
import UsageLimitsKit

struct OverviewScreen: View {
    @EnvironmentObject private var store: UsageStore
    @ReducesMotion private var reduceMotion
    @State private var filter = OverviewFilter.all
    @State private var listMotionArmed = false
    @State private var isAdding = false
    @State private var isAddingCredit = false

    let isSelected: Bool
    let openAccount: (String) -> Void

    var body: some View {
        let glance = store.glance
        let projection = glance.overview(filter: filter, at: store.now, in: store.timeZone)
        return NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    header(projection.header, attentionCount: projection.attentionCount)
                    if !store.hasLoaded {
                        loading
                    } else if glance.accounts.isEmpty {
                        empty
                    } else {
                        filters(attentionCount: projection.attentionCount)
                        accountList(projection.accounts, staleAfter: glance.staleAfter)
                        Text("The brighter line is the account’s headline.")
                            .usageFont(.small)
                            .foregroundStyle(UsageColors.faint)
                        addAccount.buttonStyle(OutlineButtonStyle(fullWidth: true))
                    }
                    if store.hasLoaded { credits }
                }
                .listMotion(value: projection.accounts.map(\.id), armed: listMotionArmed && isSelected)
                .padding(16)
                .readableWidth()
            }
            .accessibilityIdentifier("overview-scroll")
            .background(UsageColors.ground)
            .navigationTitle("Overview")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
            .refreshable { await store.refresh() }
            .sheet(isPresented: $isAdding) { AddAccountSheet() }
            .sheet(isPresented: $isAddingCredit) { AddCreditSheet() }
            .armingListMotion($listMotionArmed, loaded: store.hasLoaded)
            .onChange(of: isSelected) { selected in
                if !selected { filter = .all }
            }
        }
    }

    private func header(_ header: OverviewHeader, attentionCount: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { title; Spacer(minLength: 0); headerActions }
                VStack(alignment: .leading, spacing: 4) { title; headerActions }
            }
            if !store.hasLoaded {
                Text("Loading usage").usageFont(.small).foregroundStyle(UsageColors.faint)
            } else if store.accounts.isEmpty {
                Text("No accounts yet").usageFont(.small).foregroundStyle(UsageColors.faint)
            } else if let subtitle = headerSubtitle(header, attentionCount: attentionCount) {
                Text(verbatim: subtitle).usageFont(.small).foregroundStyle(UsageColors.faint)
            }
        }
    }

    private var title: some View {
        Text("Usage Limits").usageFont(.title).foregroundStyle(UsageColors.ink)
            .accessibilityAddTraits(.isHeader)
    }

    private var headerActions: some View {
        HStack(spacing: 8) {
            if store.hasLoaded && !store.accounts.isEmpty {
                let mode = store.settings.percentDisplay
                Button(action: toggleMode) {
                    Text(mode == .left ? String(localized: "% left") : String(localized: "% used"))
                        .usageFont(.smallStrong)
                        .foregroundStyle(UsageColors.muted)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 32)
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(UsageColors.control, lineWidth: 1))
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(mode == .left ? String(localized: "Show percentage used") : String(localized: "Show percentage left"))
                .accessibilityValue(mode == .left ? String(localized: "Left") : String(localized: "Used"))
                .accessibilityIdentifier("overview-display-mode")
            }
            Button { Task { await store.refresh() } } label: {
                Group {
                    if store.isRefreshing { ActivityMark(still: .progressIndicator) }
                    else { Image(systemName: "arrow.clockwise") }
                }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(UsageColors.ink)
            .accessibilityLabel(store.isRefreshing ? String(localized: "Refreshing…") : String(localized: "Refresh"))
            .disabled(store.isRefreshing)
        }
    }

    private func headerSubtitle(_ header: OverviewHeader, attentionCount: Int) -> String? {
        let phrase: String? = switch header.kind {
        case .attention: attentionCount == 1 ? String(localized: "1 needs attention")
            : String(localized: "\(attentionCount.formatted()) need attention")
        case .room: String(localized: "All accounts have room")
        case .resetOnly, .none: nil
        }
        if let reset = header.resetAt {
            let stamp = Countdown.absolute(reset, now: store.now, calendar: store.presentationCalendar)
            if let phrase { return String(localized: "\(phrase) · next reset \(stamp)") }
            return String(localized: "Next reset \(stamp)")
        }
        return phrase
    }

    private func filters(attentionCount: Int) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { filterButtons(attentionCount: attentionCount) }
                .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 0) { filterButtons(attentionCount: attentionCount) }
        }
    }

    private func filterButtons(attentionCount: Int) -> some View {
        ForEach(OverviewFilter.allCases, id: \.self) { option in
            let selected = filter == option
            Button { filter = option } label: {
                HStack(spacing: 5) {
                    Text(filterLabel(option))
                    if option == .needsAttention && attentionCount > 0 {
                        Text(attentionCount.formatted()).monospacedDigit()
                    }
                }
                .usageFont(selected ? .bodyStrong : .body)
                .foregroundStyle(selected ? UsageColors.ink : UsageColors.muted)
                .padding(.horizontal, 12)
                .frame(minHeight: 32)
                .background(selected ? UsageColors.raised : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? UsageColors.muted : UsageColors.rule, lineWidth: 1))
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(DisplayText.selection(selected))
            .accessibilityIdentifier("overview-filter-\(option.rawValue)")
        }
        .motion(.stateChange, value: filter)
    }

    private func filterLabel(_ option: OverviewFilter) -> String {
        switch option {
        case .all: return String(localized: "All")
        case .needsAttention: return String(localized: "Needs attention")
        case .resetsToday: return String(localized: "Resets today")
        }
    }

    private func accountList(_ rows: [OverviewProjection.Entry], staleAfter: TimeInterval) -> some View {
        UsageCard(padding: 0, spacing: 0) {
            if rows.isEmpty {
                Text("No accounts match this filter.")
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
                    .frame(minHeight: 56, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            ForEach(rows) { row in
                if row.id != rows.first?.id { LedgerDivider() }
                OverviewAccountRow(entry: row, staleAfter: staleAfter) { openAccount(row.id) }
                    .listItemMotion(armed: listMotionArmed && isSelected)
            }
        }
    }

    private var loading: some View {
        VStack(spacing: 8) {
            AccountSkeletonCard()
            AccountSkeletonCard()
            #if DEBUG
            if store.isMarketingDemo {
                Button { store.finishDemoLoad() } label: { Text(verbatim: "Load demo accounts") }
                    .accessibilityIdentifier("overview-release-demo-load")
            }
            #endif
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image("OverviewBrandMark")
                .resizable().scaledToFit().frame(width: 192, height: 192)
                .frame(maxWidth: .infinity, minHeight: 128, maxHeight: 128)
                .accessibilityHidden(true)
            Text("Nothing to monitor yet").usageFont(.emptyTitle).foregroundStyle(UsageColors.ink)
            Text("Add an AI subscription to see its limits and resets here. Sign-in details stay on this device.")
                .usageFont(.body).foregroundStyle(UsageColors.muted)
            addAccount.buttonStyle(InkButtonStyle(fullWidth: true))
            Button { isAddingCredit = true } label: {
                Label("Track a prepaid credit balance", systemImage: "plus")
            }
            .buttonStyle(OutlineButtonStyle(fullWidth: true))
            .accessibilityIdentifier("overview-add-credit")
        }
    }

    private var addAccount: some View {
        Button { isAdding = true } label: { Label("Add account", systemImage: "plus") }
            .accessibilityIdentifier("overview-add-account")
    }

    private var credits: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionTitle(text: String(localized: "Credits"))
                Spacer()
                Button { isAddingCredit = true } label: { Label("Add", systemImage: "plus") }
                    .buttonStyle(TextActionStyle())
            }
            if store.credits.isEmpty {
                DashedBox {
                    Text("No prepaid balances tracked.").usageFont(.body).foregroundStyle(UsageColors.muted)
                }
            } else {
                ForEach(store.credits) { entry in
                    CreditCard(entry: entry, now: store.now, staleAfter: store.settings.staleAfter)
                }
                NavigationLink(String(localized: "Manage credits")) { CreditsScreen() }
                    .usageFont(.bodyStrong)
                    .foregroundStyle(UsageColors.ink)
                    .frame(minHeight: 44)
            }
        }
    }

    private func toggleMode() {
        withAnimation(Motion.contentSwap.animation(reduceMotion: reduceMotion)) {
            store.settings.percentDisplay = store.settings.percentDisplay == .used ? .left : .used
        }
    }
}

private struct OverviewAccountRow: View {
    @EnvironmentObject private var store: UsageStore
    let entry: OverviewProjection.Entry
    let staleAfter: TimeInterval
    let open: () -> Void

    private var account: GlanceAccount { entry.account }
    private var expired: Bool { account.connectionStatus == .reconnectRequired }
    private var title: String {
        store.settings.showSubscriptionTier ? account.title : provider?.displayName ?? account.title
    }
    private var provider: ProviderID? { account.providerID.flatMap(ProviderID.init(rawValue:)) }

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    if let provider { ProviderBadge(provider: provider, size: 28).accessibilityHidden(true) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: title).usageFont(.accountTitle).foregroundStyle(UsageColors.ink)
                        if let identity = account.subtitle {
                            Text(verbatim: identity).usageFont(.small).foregroundStyle(UsageColors.muted)
                        }
                    }
                    Spacer(minLength: 0)
                    mark
                    Image(systemName: "chevron.right")
                        .font(.system(size: 16)).foregroundStyle(UsageColors.faint)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(account.candidates, id: \.sourceWindowID) { row in
                        WindowRow(row: row, now: store.now,
                            severity: expired ? .stale : account.displaySeverity(of: row, at: store.now, staleAfter: staleAfter),
                            validity: account.readingValidity(of: row, at: store.now, staleAfter: staleAfter),
                            mode: store.settings.percentDisplay, compact: true,
                            headline: row.sourceWindowID == entry.headlineWindowID,
                            expired: expired, calendar: store.presentationCalendar)
                    }
                    if expired {
                        Text("Sign-in expired · showing last known data")
                            .usageFont(.small).foregroundStyle(UsageColors.negativeText)
                    }
                    if let message = account.statusMessage, !expired || message != NotificationEvaluator.signInExpiredMessage {
                        Text(verbatim: DisplayText.stored(message)).usageFont(.small).foregroundStyle(UsageColors.muted)
                    }
                    if !expired && [.stale, .error, .unknown].contains(account.severity) {
                        Text(verbatim: SeverityPalette.label(account.severity))
                            .usageFont(.small).foregroundStyle(UsageColors.muted)
                    }
                }
                .padding(.leading, 38)
            }
            .padding(.leading, 16)
            .padding(.trailing, 12)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(OverviewRowButtonStyle())
        .accessibilityIdentifier("overview-account-\(account.id)")
    }

    @ViewBuilder private var mark: some View {
        let raw = account.candidates.map(\.severity).filter { $0 != .unknown }.max() ?? .unknown
        let severity: Severity = if expired { .error }
            else if raw == .exhausted && account.baseSeverity == .limited { .limited }
            else { raw }
        if severity != .healthy {
            Circle()
                .fill(severity == .medium ? Color.clear : SeverityPalette.accent(severity))
                .overlay(Circle().stroke(SeverityPalette.accent(severity), lineWidth: severity == .medium ? 1.5 : 0))
                .frame(width: 8, height: 8)
                .accessibilityLabel(expired ? String(localized: "Reconnect") : SeverityPalette.label(severity))
        }
    }
}

private struct OverviewRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? UsageColors.raised : Color.clear)
            .pressRelease(isPressed: configuration.isPressed)
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
    var compact = false
    var headline = false
    var expired = false
    var calendar = Calendar.current

    private var shown: Severity { severity ?? row.severity }
    /// Built from the raw row — an aged `severity` replaces it for the figure's colour only.
    private var presentation: WindowPresentation {
        WindowPresentation(remaining: row.remainingPercent, severity: row.severity, validity: expired ? .stale : validity, mode: mode)
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
        if compact {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    name.frame(minWidth: 58, alignment: .leading)
                        .fixedSize(horizontal: true, vertical: false)
                    bar.frame(minWidth: 24)
                    figure.frame(minWidth: 42, alignment: .trailing)
                        .fixedSize(horizontal: true, vertical: false)
                    resetStamp.frame(minWidth: 66, alignment: .trailing)
                        .fixedSize(horizontal: true, vertical: false)
                }
                VStack(alignment: .leading, spacing: 4) {
                    name
                    figure
                    bar
                    resetStamp
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(compactAccessibilityLabel)
        } else {
            detail
        }
    }

    private var name: some View {
        Text(verbatim: rowLabel)
            .usageFont(headline ? .smallStrong : .small)
            .foregroundStyle(headline && !expired ? UsageColors.ink : UsageColors.faint)
    }

    private var bar: some View {
        UsageBar(presentation: presentation, height: 4,
            emphasis: headline && !expired ? 1 : 0.4)
            .subject(row.sourceWindowID)
            .accessibilityHidden(true)
    }

    @ViewBuilder private var resetStamp: some View {
        if validity == .resetDue {
            Text("Reset due").usageFont(.small).foregroundStyle(UsageColors.faint)
        } else if let resetAt = row.resetAt {
            Text(verbatim: Countdown.absolute(resetAt, now: now, calendar: calendar))
                .usageFont(.small).foregroundStyle(UsageColors.faint)
        }
    }

    private var compactAccessibilityLabel: Text {
        let reading = String(localized: "\(rowLabel): \(PercentText.spoken(figureText, mode))")
        var parts = [headline ? String(localized: "Headline: \(reading)") : reading]
        if validity == .resetDue { parts.append(String(localized: "Reset due")) }
        else if let resetAt = row.resetAt { parts.append(Countdown.absolute(resetAt, now: now, calendar: calendar)) }
        if validity == .stale || validity == .error { parts.append(SeverityPalette.label(shown)) }
        return Text(verbatim: parts.joined(separator: ", "))
    }

    private var detail: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: rowLabel)
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.muted)
                Spacer()
                figure
            }

            if row.segments.isEmpty || presentation.fillState == .noFill {
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
                            Rectangle().fill(validity == .current && !expired
                                ? segmentColors[index % segmentColors.count] : UsageColors.staleMark)
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

            if validity == .resetDue {
                Text("Reset due").usageFont(.small).foregroundStyle(UsageColors.faint)
            } else if let resetAt = row.resetAt {
                Text(verbatim: Countdown.resetsInLine(until: resetAt, from: now, texts: LocalizedCountdownTexts()))
                    .usageFont(.small)
                    .foregroundStyle(UsageColors.faint)
            }
        }
    }

    private var figure: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(figureText)
                .usageFont(!presentation.hasFigure ? .small : compact ? .compactFigure : .figure)
                .foregroundStyle(!presentation.hasFigure || expired ? UsageColors.faint
                    : compact && !headline ? UsageColors.muted : SeverityPalette.figureColor(shown))
            if presentation.hasFigure && !compact {
                Text(sideWord).usageFont(.small).foregroundStyle(UsageColors.muted)
            }
        }
        .figureUnit(key: presentation.hasFigure ? [figureText, sideWord] : [figureText], anchor: .trailing)
        .subject(row.sourceWindowID)
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

