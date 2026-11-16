// Home Screen ring view and its quota ring.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

/// A quota as a ring: the arc is what is LEFT, and the middle names what of.
///
/// The one shape that survives being small. A bar has to be as wide as the tile to be
/// readable, so a tile holding several of them holds little else; a ring carries the same
/// number in a square and leaves its own middle free for the two facts that qualify it — which
/// limit, and when it comes back.
private struct UsageRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Built from the raw row and the snapshot's mode, so an exhausted window closes the ring in
    /// USED even with no percentage behind it.
    let presentation: WindowPresentation
    /// `Double`, not `CGFloat`: this file imports Foundation, where corelibs declares its own
    /// CGFloat, so the name is ambiguous on the Linux typecheck. Swift converts between the two
    /// implicitly (SE-0307), so `StrokeStyle` takes it unchanged on the real SDK.
    let lineWidth: Double
    var ink: WidgetInk = .light

    var body: some View {
        ZStack {
            Circle()
                .stroke(ink.track, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .modifier(AccentedTrackYield())
            if let fraction = presentation.fill, let arc = ink.fill(presentation.fillState) {
                // Unknown draws the track alone. A full ring for "the provider did not say"
                // would be an all-clear nothing reported, which is the one direction a quota
                // display must never round in.
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(
                        arc,
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    // From twelve o'clock, clockwise. `trim` starts at three o'clock.
                    .rotationEffect(Angle.degrees(-90))
                    .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: presentation.fill)
            }
        }
    }
}

/// systemSmall, transparent: one ring, the percentage inside it, the limit and its reset below.
///
/// The tile the feature list asked for — "usage as a circle with the reset time and the model
/// inside, so many accounts fit a small area". Several of these side by side is how a home
/// screen shows five subscriptions without five list tiles.
struct UsageRingWidgetView: View {

    let entry: UsageEntry
    var ink: WidgetInk = .light

    var body: some View {
        Group {
            if let focus = WidgetFocus(snapshot: entry.snapshot) {
                content(for: focus)
            } else {
                WidgetEmptyStateView(reason: entry.unavailable ?? .noAccounts, ink: ink)
            }
        }
        // Transparent: the wallpaper shows through the system's widget material. A ring reads
        // perfectly well against it, which a list of bars would not.
        .widgetBackground(nil)
        .widgetURL(DeepLink.glance)
    }

    private func content(for focus: WidgetFocus) -> some View {
        let severity = focus.account.displaySeverity(of: focus.row, at: entry.date, staleAfter: entry.snapshot.staleAfter)
        // Same rule as the small tile: a row whose own reset has passed shows that instant,
        // not some other window's still-future one. See `UsageWidgetSmallView`.
        let shownReset: Date? = {
            if let rowReset = focus.row?.resetAt, rowReset <= entry.date { return rowReset }
            return entry.snapshot.nextResetAt
        }()
        let mode = entry.snapshot.percentDisplay
        // Built from the raw row; the aged `severity` above only colours the figure.
        let p = presentation(for: focus)
        return VStack(spacing: 6) {
            ZStack {
                UsageRing(
                    presentation: p,
                    lineWidth: 9,
                    ink: ink)
                VStack(spacing: 1) {
                    // No room beside the figure inside a ring, so the side word is a caption
                    // under it, in the smallest face the widgets have.
                    WidgetFigure(
                        presentation: p,
                        font: .ringFigure, ink: ink, severity: severity, showsSideWord: false)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                    if p.hasFigure {
                        Text(verbatim: PercentText.sideWord(mode))
                            .usageFont(.micro)
                            .foregroundColor(ink.secondary)
                            .lineLimit(1)
                    }
                    if let row = focus.row {
                        Text(verbatim: row.displayLabel)
                            .usageFont(.micro)
                            .foregroundColor(ink.secondary)
                            .lineLimit(1)
                    }
                }
                .padding(10)
            }
            .accessibilityElement()
            .accessibilityLabel(spoken(p, focus))

            Text(verbatim: focus.account.title)
                .usageFont(.smallStrong)
                .foregroundColor(ink.primary)
                .lineLimit(1)
            if let message = focus.account.statusMessage {
                Text(verbatim: DisplayText.stored(message)).usageFont(.micro).foregroundColor(ink.secondary).lineLimit(2)
            }
            if let reset = GlanceText.resetLine(resetAt: shownReset, now: entry.date) {
                Text(verbatim: reset)
                    .usageFont(.micro)
                    .foregroundColor(ink.secondary)
                    .lineLimit(1)
            }
            // The data's own stamp, like every other tile that has room for one.
            if let asOf = GlanceText.asOfLine(updatedAt: focus.account.fetchedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .usageFont(.micro)
                    .foregroundColor(ink.tertiary)
                    .lineLimit(1)
            }
        }
        .padding(10)
    }

    private func presentation(for focus: WidgetFocus) -> WindowPresentation {
        focus.account.presentation(
            of: focus.row, at: entry.date, staleAfter: entry.snapshot.staleAfter,
            mode: entry.snapshot.percentDisplay)
    }

    private func spoken(_ p: WindowPresentation, _ focus: WidgetFocus) -> String {
        let subject = GlanceText.subject(focus.account, focus.row)
        guard p.hasFigure else {
            return String(localized: "\(subject): remaining unknown")
        }
        let said = PercentText.spoken(PercentText.figure(p), entry.snapshot.percentDisplay)
        return String(localized: "\(subject): \(said)")
    }
}

