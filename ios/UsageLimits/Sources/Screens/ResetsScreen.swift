import SwiftUI
import UsageLimitsKit

/// Every limit that has a known reset time, soonest first.
///
/// The second half of the question this app exists to answer. A window whose reset the provider
/// does not state is absent rather than shown with a blank — a row with no time is a row that
/// cannot be acted on, and it would push a real one off the screen.
struct ResetsScreen: View {

    @EnvironmentObject private var store: UsageStore

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
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    if store.upcomingResets.isEmpty {
                        DashedBox {
                            Text("Nothing scheduled")
                                .usageFont(.body)
                                .foregroundStyle(UsageColors.ink)
                            Text("Reset times appear here once a provider reports one.")
                                .usageFont(.body)
                                .foregroundStyle(UsageColors.muted)
                        }
                    } else {
                        ForEach(days, id: \.start) { day in
                            Text(dayTitle(day.start))
                                .usageFont(.smallStrong)
                                .foregroundStyle(UsageColors.muted)
                                .padding(.top, 16)
                                .accessibilityAddTraits(.isHeader)
                            // One card per day, split by hairlines: a card per reset is a stack
                            // of identical boxes with nothing to tell one from the next.
                            UsageCard(padding: 0, spacing: 0) {
                                ForEach(Array(day.entries.enumerated()), id: \.offset) { index, entry in
                                    if index > 0 { LedgerDivider() }
                                    ResetRow(
                                        accountLabel: entry.account.label,
                                        window: entry.window,
                                        now: store.now,
                                        isNext: nextKey == "\(entry.account.id)/\(entry.window.id)")
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .readableWidth()
            }
            .background(UsageColors.ground)
            .navigationTitle("Resets")
            .navigationBarTitleDisplayMode(.inline)
            .marketingDemoLabel()
            .refreshable { await store.refresh() }
        }
    }

    /// "Today", "Tomorrow", then the weekday and date.
    private func dayTitle(_ start: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDate(start, inSameDayAs: store.now) { return "Today" }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: store.now),
            calendar.isDate(start, inSameDayAs: tomorrow) { return "Tomorrow" }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return formatter.string(from: start)
    }
}

private struct ResetRow: View {

    let accountLabel: String
    let window: UsageWindow
    let now: Date
    /// The first reset still ahead: its countdown takes the data hue and it gets a rule on its
    /// leading edge. One per screen, because the colour is only worth anything while it is rare.
    let isNext: Bool

    var body: some View {
        HStack(spacing: 12) {
            // Both forms, deliberately: the countdown is what the eye wants, and the
            // wall-clock time is what survives being read a few minutes later.
            Text(clock)
                .usageFont(.figure)
                .foregroundStyle(UsageColors.ink)
                .lineLimit(1)
                // A column, not a width: "10:30 AM" is wider than "10:30" and must not clip.
                .fixedSize(horizontal: true, vertical: false)
                .frame(minWidth: 64, alignment: .trailing)

            // Severity is this dot and nothing else; a healthy window is the default and
            // leaves the slot empty so the labels still line up.
            Circle()
                .fill(window.severity == .healthy ? Color.clear : SeverityPalette.accent(window.severity))
                .frame(width: 6, height: 6)

            VStack(alignment: .leading, spacing: 2) {
                Text(window.label)
                    .usageFont(.body)
                    .foregroundStyle(UsageColors.ink)
                Text(accountLabel)
                    .usageFont(.small)
                    .foregroundStyle(UsageColors.muted)
            }

            Spacer(minLength: 8)

            Text(countdown)
                .usageFont(.figure)
                .foregroundStyle(isNext ? UsageColors.data : UsageColors.muted)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 56)
        .overlay(alignment: .leading) {
            if isNext { UsageColors.data.frame(width: 2).padding(.vertical, 8) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
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
        return Countdown.format(until: resetAt, from: now)
    }

    /// The status word lives here now, not on screen: a dot says it to the eye and this says
    /// it to VoiceOver.
    private var spoken: String {
        let remaining = window.remainingPercent.map { ", \(UsageWindow.percentLabel($0)) remaining" } ?? ""
        return "\(window.label), \(accountLabel), resets at \(clock), in \(countdown), \(SeverityPalette.label(window.severity))\(remaining)"
    }
}
