// Home Screen small, medium and large tiles, and the row and header they share.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

/// One account line on the medium tile: title, one row's percentage, its bar.
///
/// The row is handed in rather than chosen here, so the lead shows the kit's headline and
/// every other account shows its first row in the kit's order.
private struct MediumAccountRow: View {

    let account: GlanceAccount
    let row: GlanceRow?
    /// The date of the timeline entry this row is being drawn for, so freshness is judged
    /// there rather than at the moment the app wrote the snapshot.
    let now: Date
    let staleAfter: TimeInterval
    /// From the snapshot, never from settings: the widget process cannot see them.
    let mode: PercentDisplay
    var ink: WidgetInk = .light

    /// Built from the raw row — `rowSeverity` below is aged and only colours the figure.
    private var presentation: WindowPresentation {
        account.presentation(of: row, at: now, staleAfter: staleAfter, mode: mode)
    }

    /// The row's own severity while the account is healthy, and the account's otherwise.
    ///
    /// A row's severity comes from its percentage and knows nothing about age, so letting it
    /// speak unconditionally painted a stale or failed account in the colour of its last good
    /// number — the one reading a quota widget must never give.
    private var rowSeverity: Severity {
        account.displaySeverity(of: row, at: now, staleAfter: staleAfter)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                // A severity dot: the colour-blind-readable twin of the bar colour.
                // Redundant to VoiceOver, which already hears the percentage.
                // A healthy account is the default and keeps the slot empty, so titles align.
                let accountSeverity = account.severity(at: now, staleAfter: staleAfter)
                Circle()
                    .fill(accountSeverity == .healthy ? Color.clear : SeverityPalette.accent(accountSeverity))
                    .frame(width: 6, height: 6)
                    .accessibilityHidden(true)
                Text(verbatim: account.title)
                    .usageFont(.bodyStrong)
                    .foregroundColor(ink.primary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                WidgetFigure(
                    presentation: presentation,
                    font: .figure, ink: ink, severity: rowSeverity)
                    .lineLimit(1)
            }
            if let message = account.statusMessage {
                Text(verbatim: DisplayText.stored(message)).usageFont(.micro).foregroundColor(ink.secondary).lineLimit(2)
            }
            QuotaBar(
                presentation: presentation,
                context: GlanceText.subject(account, row),
                ink: ink
            )
        }
    }
}

// MARK: - Tiles

/// systemSmall: one thing, said well.
///
/// The single most critical account — title, one percentage, one bar, the next
/// reset time, and the "As of" line that dates the claim. Nothing else competes
/// for the tile: a small surface that tries to say two things says neither.
struct UsageWidgetSmallView: View {

    let entry: UsageEntry
    var transparent: Bool = false
    var ink: WidgetInk = .light

    var body: some View {
        Group {
            if let focus = WidgetFocus(snapshot: entry.snapshot) {
                content(for: focus)
            } else {
                WidgetEmptyStateView(reason: entry.unavailable ?? .noAccounts, ink: ink)
            }
        }
        .widgetBackground(transparent ? nil : UsageColors.ground)
        .widgetURL(DeepLink.glance)
    }

    private func content(for focus: WidgetFocus) -> some View {
        // The snapshot's next reset is the LEAD's soonest FUTURE reset. When the row this
        // tile actually displays has a reset that has already passed, that row's instant is
        // the truthful one: the reading above it predates its own rollover, and naming some
        // other, still-future window's reset beside it — "0 %, Resets Friday" — is exactly
        // the pairing that made a pre-reset reading look explained. resetLine renders the
        // passed instant as "Reset · refresh pending"; nothing synthesises post-reset
        // availability from clock arithmetic.
        let shownReset: Date? = {
            if let rowReset = focus.row?.resetAt, rowReset <= entry.date { return rowReset }
            return entry.snapshot.nextResetAt
        }()
        // Aged to THIS entry's date, not to the moment the app wrote the snapshot. A timeline
        // holds several entries built from one snapshot, so a frozen verdict means a tile that
        // was healthy at write time still reads healthy hours later with nothing behind it.
        // The row's severity comes from its percentage alone and knows nothing about age, so
        // it may only speak while the account itself is healthy and the row's reset has not
        // passed since the fetch. Otherwise a stale, failed or rolled-over reading was painted
        // in the colour of its last good number.
        let severity = focus.account.displaySeverity(of: focus.row, at: entry.date, staleAfter: entry.snapshot.staleAfter)
        // Built from the raw row, not the aged `severity`: it decides the figure and the fill.
        let p = focus.account.presentation(
            of: focus.row, at: entry.date, staleAfter: entry.snapshot.staleAfter,
            mode: entry.snapshot.percentDisplay)
        return VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: focus.account.title)
                .usageFont(.bodyStrong)
                .foregroundColor(ink.primary)
                .lineLimit(1)

            if let row = focus.row {
                Text(verbatim: row.displayLabel)
                    .usageFont(.small)
                    .foregroundColor(ink.secondary)
                    .lineLimit(1)
            }

            if let message = focus.account.statusMessage {
                Text(verbatim: DisplayText.stored(message)).usageFont(.micro).foregroundColor(ink.secondary).lineLimit(2)
            }

            Spacer(minLength: 4)

            WidgetFigure(
                presentation: p, font: .widgetLead, ink: ink, severity: severity)
                .lineLimit(1)

            QuotaBar(
                presentation: p,
                context: GlanceText.subject(focus.account, focus.row),
                ink: ink
            )

            if let reset = GlanceText.resetLine(resetAt: shownReset, now: entry.date) {
                Text(verbatim: reset)
                    .usageFont(.micro)
                    .foregroundColor(ink.secondary)
                    .lineLimit(1)
            }

            // This tile shows ONE account's numbers; the timestamp must belong to that
            // account. The snapshot-level maximum is the newest account's fetch and can
            // freshen a lead that was read an hour earlier — date the number being shown.
            if let asOf = GlanceText.asOfLine(
                updatedAt: focus.account.fetchedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .usageFont(.micro)
                    .foregroundColor(ink.tertiary)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// systemMedium: the accounts that most need attention, at most three of them.
///
/// In the kit's order — most urgent first, as GlanceModel ranks them — everything past the
/// third is counted ("+N more"),
/// not drawn — a fourth cramped row helps nobody. At larger type sizes even three
/// rows will not fit, so the limit drops to two; and no text anywhere is given a
/// fixed height, so rows grow with the user's chosen size instead of being
/// clipped to one.
struct UsageWidgetMediumView: View {

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: UsageEntry
    var transparent: Bool = false
    var ink: WidgetInk = .light

    /// The kit's order, untouched. See `WidgetFocus` for what re-ranking here cost.
    private var rankedAccounts: [GlanceAccount] {
        entry.snapshot.accounts
    }

    /// The lead shows the kit's headline row; every other account its own first row.
    private func row(for account: GlanceAccount) -> GlanceRow? {
        entry.snapshot.widgetRow(for: account)
    }

    /// Three rows fit at regular sizes; from xLarge upwards two fill the tile, and
    /// a third clipped mid-bar would be worse than an honest "+N more".
    private var visibleLimit: Int {
        dynamicTypeSize >= .xLarge ? 2 : 3
    }

    private var visibleAccounts: [GlanceAccount] {
        Array(rankedAccounts.prefix(visibleLimit))
    }

    private var hiddenCount: Int {
        max(rankedAccounts.count - visibleAccounts.count, 0)
    }

    var body: some View {
        Group {
            if entry.snapshot.accounts.isEmpty {
                WidgetEmptyStateView(reason: entry.unavailable ?? .noAccounts, ink: ink)
            } else {
                content
            }
        }
        .widgetBackground(transparent ? nil : UsageColors.ground)
        .widgetURL(DeepLink.glance)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 5) {
            WidgetHeader(entry: entry, ink: ink)

            ForEach(visibleAccounts) { account in
                MediumAccountRow(
                    account: account,
                    row: row(for: account),
                    now: entry.date,
                    staleAfter: entry.snapshot.staleAfter,
                    mode: entry.snapshot.percentDisplay,
                    ink: ink)
            }

            if hiddenCount > 0 {
                // Counted, not drawn: the reader learns the rest exist without
                // being shown three unreadable half-rows.
                Text(verbatim: String(localized: "+\(hiddenCount) more"))
                    .usageFont(.micro)
                    .foregroundColor(ink.tertiary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

}

/// systemLarge: every account that fits, one dense row each.
///
/// The medium tile stops at three and counts the rest, which is right for its height. A large
/// tile has room for the whole fleet, and seeing all of it at once is the thing the app is for
/// — the feature list asked for exactly this, "eight accounts in one tile". Eight is the cap:
/// past that the rows are thinner than the text in them, and an honest "+N more" beats a row
/// nobody can read.
struct UsageWidgetLargeView: View {

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: UsageEntry
    let transparent: Bool
    var ink: WidgetInk = .light

    /// Eight at regular sizes, five once the type is large enough that eight would clip.
    private var visibleLimit: Int { dynamicTypeSize >= .xLarge ? 5 : 8 }

    private var visibleAccounts: [GlanceAccount] {
        Array(entry.snapshot.accounts.prefix(visibleLimit))
    }

    private var hiddenCount: Int {
        max(entry.snapshot.accounts.count - visibleAccounts.count, 0)
    }

    /// The lead shows the kit's headline row; every other account its own first row. Same rule
    /// as the medium tile, and for the same reason — see `WidgetFocus`.
    private func row(for account: GlanceAccount) -> GlanceRow? {
        entry.snapshot.widgetRow(for: account)
    }

    var body: some View {
        Group {
            if entry.snapshot.accounts.isEmpty {
                WidgetEmptyStateView(reason: entry.unavailable ?? .noAccounts, ink: ink)
            } else {
                content
            }
        }
        .widgetBackground(transparent ? nil : UsageColors.ground)
        .widgetURL(DeepLink.glance)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 8) {
            WidgetHeader(entry: entry, ink: ink)

            ForEach(visibleAccounts) { account in
                MediumAccountRow(
                    account: account,
                    row: row(for: account),
                    now: entry.date,
                    staleAfter: entry.snapshot.staleAfter,
                    mode: entry.snapshot.percentDisplay,
                    ink: ink)
            }

            if hiddenCount > 0 {
                Text(verbatim: String(localized: "+\(hiddenCount) more"))
                    .usageFont(.micro)
                    .foregroundColor(ink.tertiary)
            }

            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// The line both list tiles carry: when the numbers were read, when the next limit rolls over,
/// and the way to ask for fresher ones.
private struct WidgetHeader: View {

    let entry: UsageEntry
    var ink: WidgetInk = .light

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            // The maximum fetch instant across the accounts shown. "As of" beside one
            // account's row means THAT account's fetch — a newer hidden account must not
            // freshen an older visible one, so the aggregate names itself truthfully.
            if let asOf = GlanceText.latestUpdateLine(updatedAt: entry.snapshot.updatedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .usageFont(.micro)
                    .foregroundColor(ink.tertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
                Text(verbatim: reset)
                    .usageFont(.micro)
                    .foregroundColor(ink.secondary)
                    .lineLimit(1)
            }
            if let refresh = DeepLink.refresh {
                // A `Link` rather than the tile-wide `widgetURL`, so this one glyph opens the
                // app on a refresh while the rest of the tile still opens the overview. The
                // system honours `Link` on medium and large only, which is why the small tile
                // has no such button rather than a decorative one that does nothing.
                Link(destination: refresh) {
                    Image(systemName: "arrow.clockwise")
                        .usageFont(.small)
                        .foregroundColor(ink.secondary)
                }
                .accessibilityLabel("Refresh in the app")
            }
        }
    }
}

