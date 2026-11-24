import SwiftUI
import UsageLimitsKit

/// Every limit that has a known reset time, soonest first.
///
/// The second half of the question this app exists to answer. A window whose reset the provider
/// does not state is absent rather than shown with a blank — a row with no time is a row that
/// cannot be acted on, and it would push a real one off the screen.
struct ResetsScreen: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

    @EnvironmentObject private var store: UsageStore
    @State private var listMotionArmed = false

    /// Resets that fall on one calendar day, soonest first.
    private struct Day {
        let start: Date
        var entries: [(account: ProviderAccount, window: UsageWindow)]
    }

    private var days: [Day] {
        let calendar = Calendar.current
        var result: [Day] = []
        for entry in store.upcomingResets {
            guard let at = entry.window.resetAt else { continue }
            let start = calendar.startOfDay(for: at)
            if let last = result.last, last.start == start {
                result[result.count - 1].entries.append(entry)
            } else {
                result.append(Day(start: start, entries: [entry]))
            }
        }
        return result
    }

    /// The soonest reset that is still ahead — the current period, and the one row that gets
    /// the data hue.
    private var nextKey: String? {
        store.upcomingResets.first { ($0.window.resetAt ?? .distantPast) > store.now }
            .map { "\($0.account.id)/\($0.window.id)" }
    }

    var body: some View {
        let visibleDays = days
        return NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    if store.upcomingResets.isEmpty {
                        DashedBox {
                            Text("Nothing scheduled")
                                .usageFont(.body)
                                .foregroundStyle(colors.ink)
                            Text("Reset times appear here once a provider reports one.")
                                .usageFont(.body)
                                .foregroundStyle(colors.muted)
                        }
                        .listItemMotion(armed: listMotionArmed)
                    } else {
                        ForEach(visibleDays, id: \.start) { day in
                            Text(dayTitle(day.start))
                                .usageFont(.smallStrong)
                                .foregroundStyle(colors.muted)
                                .padding(.top, 16)
                                .accessibilityAddTraits(.isHeader)
                                .listItemMotion(armed: listMotionArmed)
                            // One card per day, split by hairlines: a card per reset is a stack
                            // of identical boxes with nothing to tell one from the next.
                            UsageCard(padding: 0, spacing: 0) {
                                ForEach(Array(day.entries.enumerated()), id: \.offset) { index, entry in
                                    if index > 0 { LedgerDivider() }
                                    ResetRow(
                                        account: entry.account,
                                        window: entry.window,
                                        now: store.now,
                                        mode: store.settings.percentDisplay,
                                        isNext: nextKey == "\(entry.account.id)/\(entry.window.id)")
                                }
                            }
                            .listItemMotion(armed: listMotionArmed)
                        }
                    }
                }
                .listMotion(value: visibleDays.map(\.start), armed: listMotionArmed)
                .padding(16)
                .readableWidth()
            }
            .background(colors.ground)
            .navigationTitle("Resets")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
            .refreshable { await store.refresh() }
            .armingListMotion($listMotionArmed, loaded: store.hasLoaded)
        }
    }

    /// "Today", "Tomorrow", then the weekday and date.
    private func dayTitle(_ start: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDate(start, inSameDayAs: store.now) { return String(localized: "Today") }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: store.now),
            calendar.isDate(start, inSameDayAs: tomorrow) { return String(localized: "Tomorrow") }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return formatter.string(from: start)
    }
}

private struct ResetRow: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.dynamicTypeSize) private var typeSize

    let account: ProviderAccount
    private var accountLabel: String { account.label }
    let window: UsageWindow
    let now: Date
    /// Only the spoken sentence carries a percentage on this row.
    let mode: PercentDisplay
    /// The first reset still ahead: its countdown takes the data hue and it gets a rule on its
    /// leading edge. One per screen, because the colour is only worth anything while it is rare.
    let isNext: Bool

    var body: some View {
        HStack(spacing: 12) {
            ClaudeProviderBadge(provider: account.provider, mood: .hooray, size: 28)
            #if DEBUG
            .environment(\.claudeAnimationDiagnosticID, "reset:\(account.id)")
            #endif
            if typeSize >= .accessibility1 {
                VStack(alignment: .leading, spacing: 6) {
                    HStack { mark; labels }
                    clockText
                    remainingTime
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                clockText.frame(minWidth: 64, alignment: .trailing)
                mark
                labels
                Spacer(minLength: 8)
                remainingTime
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, typeSize >= .accessibility1 ? 8 : 0)
        .frame(minHeight: 56)
        .overlay(alignment: .leading) {
            if isNext { colors.data.frame(width: 2).padding(.vertical, 8) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    private var clockText: some View {
        Text(clock)
            .usageFont(.figure)
            .foregroundStyle(colors.ink)
            .fixedSize(horizontal: true, vertical: false)
    }

    private var mark: some View {
        Circle()
            .fill(window.severity == .healthy ? Color.clear : colors.accent(window.severity))
            .frame(width: 6, height: 6)
    }

    private var labels: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: DisplayText.windowLabel(window.label, category: window.category, scope: window.scope))
                .usageFont(.body)
                .foregroundStyle(colors.ink)
            Text(accountLabel)
                .usageFont(.small)
                .foregroundStyle(colors.muted)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var remainingTime: some View {
        Text(countdown)
            .usageFont(.figure)
            .foregroundStyle(isNext ? colors.dataText : colors.muted)
    }

    private var clock: String {
        guard let resetAt = window.resetAt else { return "—" }
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: resetAt)
    }

    private var countdown: String {
        guard let resetAt = window.resetAt else { return "—" }
        return Countdown.format(until: resetAt, from: now, texts: LocalizedCountdownTexts())
    }

    /// The status word lives here now, not on screen: a dot says it to the eye and this says
    /// it to VoiceOver.
    private var spoken: String {
        // Absent while the figure is unknown, as before; in USED an exhausted window with no
        // percentage still has a figure to speak ("100% used").
        let p = window.presentation(validity: .current, mode: mode)
        let figure = PercentText.figure(p)
        let label = DisplayText.windowLabel(window.label, category: window.category, scope: window.scope)
        let status = SeverityPalette.label(window.severity)
        let sentence = String(localized: "\(label), \(accountLabel), resets at \(clock), in \(countdown), \(status)")
        guard p.hasFigure else { return sentence }
        let said = PercentText.spoken(figure, mode)
        return String(localized: "\(sentence), \(said)")
    }
}
