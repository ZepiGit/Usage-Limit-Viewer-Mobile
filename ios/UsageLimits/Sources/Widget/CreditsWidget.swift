//
//  CreditsWidget.swift
//  UsageLimitsWidget
//
//  Prepaid and API balances on the Home Screen: one account on the small tile, three on the
//  medium, six on the large, worst first. Like the usage tiles it fetches nothing: the app
//  writes the balances into the shared snapshot (`GlanceSnapshot.credits`) and this reads them.
//  The same two invariants hold: nothing is phrased relative to "now" (the data carries its
//  own "As of" clock time), and an unknown amount is an em dash, never a $0.
//

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Timeline

struct CreditsEntry: TimelineEntry {
    enum State: Equatable {
        case ready
        case noCredits
        case locked
        case unreadable
    }

    let date: Date
    /// Worst first, ranked at `date` so an account that has aged stale stops leading.
    let credits: [GlanceCredit]
    let staleAfter: TimeInterval
    let updatedAt: Date?
    let state: State
}

struct CreditsProvider: TimelineProvider {

    /// The app asks WidgetKit to reload on every publish; this is the fallback for a tile the
    /// app has not touched, and it also picks up the aging boundaries below.
    private static let routineReloadInterval: TimeInterval = 4 * 60 * 60

    func placeholder(in context: Context) -> CreditsEntry {
        // The empty state, not sample amounts: a gallery preview must not invent a balance.
        CreditsEntry(date: Date(), credits: [], staleAfter: Severity.staleAfter, updatedAt: nil, state: .noCredits)
    }

    func getSnapshot(in context: Context, completion: @escaping (CreditsEntry) -> Void) {
        completion(Self.entries(from: Self.load(), now: Date()).first ?? placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CreditsEntry>) -> Void) {
        let now = Date()
        let entries = Self.entries(from: Self.load(), now: now)
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(Self.routineReloadInterval))))
    }

    private static func load() -> GlanceSnapshotCodec.CreditsLoad {
        guard let url = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: SnapshotCache.appGroupID) else { return .missing }
        return GlanceSnapshotCodec.loadCredits(fromDirectory: url)
    }

    /// One entry now and one at each instant a balance turns stale, so the tile says so on
    /// time rather than keeping the colour of its last number.
    private static func entries(from load: GlanceSnapshotCodec.CreditsLoad, now: Date) -> [CreditsEntry] {
        switch load {
        case .loaded(let snapshot):
            let boundaries = snapshot.credits
                .compactMap { $0.fetchedAt?.addingTimeInterval(snapshot.staleAfter) }
                .filter { $0 > now }
            return Set([now] + boundaries).sorted().map { date in
                CreditsEntry(
                    date: date, credits: snapshot.creditsByUrgency(at: date),
                    staleAfter: snapshot.staleAfter,
                    updatedAt: snapshot.credits.compactMap(\.fetchedAt).max(), state: .ready)
            }
        case .empty, .missing:
            return [CreditsEntry(date: now, credits: [], staleAfter: Severity.staleAfter, updatedAt: nil, state: .noCredits)]
        case .unreadable:
            return [CreditsEntry(date: now, credits: [], staleAfter: Severity.staleAfter, updatedAt: nil, state: .locked)]
        case .corrupt:
            return [CreditsEntry(date: now, credits: [], staleAfter: Severity.staleAfter, updatedAt: nil, state: .unreadable)]
        }
    }
}

// MARK: - Views

private extension GlanceCredit {
    /// The figure a row leads with. Unlimited has no balance, only a spend.
    var headlineText: String {
        guard let balance = balances.first else { return "—" }
        return balance.unlimited ? String(localized: "No limit") : CreditFormat.string(balance.amount, currency: balance.currency)
    }

    /// Other currencies, for the second line ("¥110.00 · $5.00" leads with the worse one).
    var extraText: String? {
        guard balances.count > 1 else { return nil }
        return CreditFormat.summary(Array(balances.dropFirst()))
    }

    var providerName: String? {
        CreditProviderID(rawValue: providerID)?.displayName
    }
}

/// A balance bar, drawn only when the provider states a total.
private struct CreditBar: View {
    let remainingPercent: Double?
    let severity: Severity
    var ink: WidgetInk = .light

    @ScaledMetric(relativeTo: .caption) private var barHeight = 4

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(ink.track)
                if let remainingPercent, let fill = ink.bar(severity) {
                    Capsule()
                        .fill(fill)
                        .frame(width: proxy.size.width * QuotaFormatting.fraction(remainingPercent))
                }
            }
        }
        .frame(height: barHeight)
    }
}

/// systemSmall: the account that most needs topping up.
struct CreditsSmallView: View {
    let entry: CreditsEntry
    var ink: WidgetInk = .light
    @ScaledMetric(relativeTo: .title2) private var amountSize: Double = 28
    @ScaledMetric(relativeTo: .subheadline) private var titleSize: Double = 14
    @ScaledMetric(relativeTo: .caption2) private var statusSize: Double = 11
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            if let credit = entry.credits.first {
                let severity = credit.severity(at: entry.date, staleAfter: entry.staleAfter)
                let status = statusLine(credit, severity)
                // Measure the complete wording in both layouts before considering a shorter label.
                ViewThatFits(in: .vertical) {
                    reading(credit, severity: severity, status: status, label: status, reflow: false)
                    reading(credit, severity: severity, status: status, label: status, reflow: true)
                    if let compact = Self.compactStatus(for: credit) {
                        reading(credit, severity: severity, status: compact, label: status, reflow: true)
                    }
                }
            } else {
                CreditsEmptyView(state: entry.state, ink: ink)
            }
        }
        .widgetBackground(UsageColors.ground)
        .widgetURL(DeepLink.glance)
    }

    private func reading(
        _ credit: GlanceCredit, severity: Severity, status: String, label: String, reflow: Bool
    ) -> some View {
        let spacing = reflow ? 2.0 : 4.0
        return VStack(alignment: .center, spacing: spacing) {
            HStack(spacing: 6) {
                Text(verbatim: credit.title)
                    .font(.system(size: min(titleSize, 20), weight: .medium))
                    .foregroundColor(ink.primary).lineLimit(1).minimumScaleFactor(0.7)
                if severity != .healthy {
                    Circle().fill(SeverityPalette.accent(severity)).frame(width: 6, height: 6)
                        .accessibilityLabel(SeverityPalette.label(severity))
                }
            }
            if dynamicTypeSize < .xLarge, let name = credit.providerName, name != credit.title {
                Text(verbatim: name).usageFont(.small).foregroundColor(ink.secondary).lineLimit(1)
            }
            if !reflow { Spacer(minLength: 4) }
            // A known balance stays ink regardless of severity. The dash is not a zero.
            VStack(spacing: spacing) {
                Text(verbatim: credit.headlineText)
                    .font(.system(size: credit.balances.isEmpty ? 12 : min(amountSize, 40),
                                  weight: credit.balances.isEmpty ? .regular : .bold, design: .monospaced))
                    .foregroundColor(credit.balances.isEmpty ? ink.secondary : ink.primary)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                Text(verbatim: status)
                    .font(.system(size: min(statusSize, 14), weight: .medium))
                    .foregroundColor(ink.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(label)
                    .accessibilityIdentifier("credit-widget-status")
                if dynamicTypeSize < .xLarge, let extra = credit.extraText {
                    Text(verbatim: extra).usageFont(.micro).foregroundColor(ink.secondary).lineLimit(1)
                }
            }
            if !reflow { Spacer(minLength: 4) }
            CreditBar(remainingPercent: credit.balances.first?.remainingPercent, severity: severity, ink: ink)
        }
        .padding(reflow ? 0 : 12)
        .frame(maxWidth: .infinity, alignment: .center)
    }

    static func compactStatus(for credit: GlanceCredit) -> String? {
        // A retained failed reading is still coloured by its balance or age, not .error.
        if credit.statusMessage != nil || credit.baseSeverity == .error {
            return credit.balances.isEmpty ? String(localized: "Read error") : String(localized: "Update error")
        }
        return credit.balances.isEmpty && credit.baseSeverity == .unknown ? String(localized: "Not read") : nil
    }

    private func statusLine(_ credit: GlanceCredit, _ severity: Severity) -> String {
        if let message = credit.statusMessage { return DisplayText.stored(message) }
        // Stale, failed or unread: say so rather than dating a number that cannot be trusted.
        if severity == .stale || severity == .error || severity == .unknown { return SeverityPalette.label(severity) }
        return GlanceText.asOfLine(updatedAt: credit.fetchedAt, now: entry.date) ?? SeverityPalette.label(severity)
    }
}

/// One account on a medium or large tile.
private struct CreditRow: View {
    let credit: GlanceCredit
    let now: Date
    let staleAfter: TimeInterval
    let ink: WidgetInk

    var body: some View {
        let severity = credit.severity(at: now, staleAfter: staleAfter)
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                // A healthy balance is the default and leaves the slot empty, so titles align.
                Circle().fill(severity == .healthy ? Color.clear : SeverityPalette.accent(severity))
                    .frame(width: 6, height: 6)
                    .accessibilityLabel(SeverityPalette.label(severity))
                Text(verbatim: credit.title).usageFont(.bodyStrong)
                    .foregroundColor(ink.primary).lineLimit(1)
                Spacer(minLength: 4)
                Text(verbatim: credit.headlineText)
                    .usageFont(credit.balances.isEmpty ? .small : .rowMoney)
                    .foregroundColor(credit.balances.isEmpty ? ink.secondary : ink.primary)
                    .lineLimit(1)
            }
            CreditBar(remainingPercent: credit.balances.first?.remainingPercent, severity: severity, ink: ink)
            if let message = credit.statusMessage.map({ DisplayText.stored($0) }) ?? credit.extraText {
                Text(verbatim: message).usageFont(.micro).foregroundColor(ink.secondary).lineLimit(1)
            }
        }
    }
}

/// systemMedium (3) and systemLarge (6): the lowest balances first.
struct CreditsListView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: CreditsEntry
    let limit: Int
    var ink: WidgetInk = .light

    /// A row clipped mid-bar is worse than an honest "+N more".
    private var visibleLimit: Int {
        dynamicTypeSize >= .xLarge ? max(limit - 1, 1) : limit
    }

    var body: some View {
        Group {
            if entry.credits.isEmpty {
                CreditsEmptyView(state: entry.state, ink: ink)
            } else {
                let shown = Array(entry.credits.prefix(visibleLimit))
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Credits").usageFont(.smallStrong).foregroundColor(ink.secondary)
                        Spacer(minLength: 4)
                        if let asOf = GlanceText.latestUpdateLine(
                            updatedAt: entry.updatedAt, now: entry.date) {
                            Text(verbatim: asOf).usageFont(.micro).foregroundColor(ink.tertiary).lineLimit(1)
                        }
                    }
                    ForEach(shown) { credit in
                        CreditRow(credit: credit, now: entry.date, staleAfter: entry.staleAfter, ink: ink)
                    }
                    let hidden = entry.credits.count - shown.count
                    if hidden > 0 {
                        Text(verbatim: String(localized: "+\(hidden) more"))
                            .usageFont(.micro).foregroundColor(ink.tertiary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .widgetBackground(UsageColors.ground)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .widgetURL(DeepLink.glance)
    }
}

struct CreditsEmptyView: View {
    let state: CreditsEntry.State
    var ink: WidgetInk = .light

    private var title: String {
        switch state {
        case .locked: return String(localized: "Locked")
        case .unreadable: return String(localized: "Can't read balances")
        case .ready, .noCredits: return String(localized: "No credit balances")
        }
    }

    private var detail: String {
        switch state {
        case .locked: return String(localized: "Unlock this device to see your balances")
        case .unreadable: return String(localized: "Open UsageLimits to rebuild the data")
        case .ready, .noCredits: return String(localized: "Add one in UsageLimits to see what is left to spend")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).usageFont(.bodyStrong).foregroundColor(ink.primary)
            Text(detail).usageFont(.small).foregroundColor(ink.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

struct CreditsEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CreditsEntry

    var body: some View {
        switch family {
        case .systemLarge: CreditsListView(entry: entry, limit: 6)
        case .systemMedium: CreditsListView(entry: entry, limit: 3)
        default: CreditsSmallView(entry: entry)
        }
    }
}

// MARK: - Widget

struct CreditsWidget: Widget {

    /// Never changed once shipped: the kind identifies a PLACED tile.
    private static let kind = "com.usagelimits.widget.credits"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: CreditsProvider()) { entry in
            CreditsEntryView(entry: entry)
        }
        .configurationDisplayName("Credits")
        .description("Prepaid and API balances left to spend, lowest first.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
