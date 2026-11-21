// Lock Screen rectangular and circular accessory views.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

/// accessoryRectangular (Lock Screen, StandBy): three short lines, system colours.
///
/// Accessories are re-tinted by the system against arbitrary backgrounds — the
/// app palette would collapse into an unreadable mid-grey — so this view spends
/// nothing on custom colour and states severity in words instead. There is no
/// room for an "As of" line, so the tighter trade is made: the reset line, the
/// claim most likely to go wrong, wins the space.
struct UsageAccessoryRectangularView: View {

    let entry: UsageEntry

    var body: some View {
        if let focus = WidgetFocus(snapshot: entry.snapshot) {
            content(for: focus)
        } else {
            emptyContent
        }
    }

    private func content(for focus: WidgetFocus) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: focus.account.title)
                .font(.headline)
                .lineLimit(1)

            let p = presentation(for: focus)
            HStack(spacing: 4) {
                WidgetPercentage(text: PercentText.figure(p))
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .lineLimit(1)
                // Names the side the figure counts; a bare dash for an unknown figure stays bare.
                if p.hasFigure {
                    Text(verbatim: PercentText.sideWord(entry.snapshot.percentDisplay))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                // The ACCOUNT's severity, which is the one that carries staleness. The row's
                // is computed from the percentage alone, so the lock screen wrote "Healthy"
                // over numbers fetched a day ago. Now it says "Stale", which is one property
                // access away and the truth.
                Text(verbatim: SeverityPalette.label(
                    focus.account.severity(at: entry.date, staleAfter: entry.snapshot.staleAfter)))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            if let message = focus.account.statusMessage {
                Text(verbatim: DisplayText.stored(message)).font(.caption2).lineLimit(1)
            } else if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
    }

    private func presentation(for focus: WidgetFocus) -> WindowPresentation {
        focus.account.presentation(
            of: focus.row, at: entry.date, staleAfter: entry.snapshot.staleAfter,
            mode: entry.snapshot.percentDisplay)
    }

    private var emptyContent: some View {
        VStack(alignment: .leading, spacing: 1) {
            // The app's name is a brand, not a sentence.
            Text(verbatim: "UsageLimits")
                .font(.headline)
            Text("Open the app to see your quota")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }
}

/// accessoryCircular (Lock Screen, StandBy): the same ring, in the system's own tint.
///
/// No app colour at all, for the reason `UsageAccessoryRectangularView` gives: accessories are
/// re-tinted against arbitrary backgrounds and a custom palette collapses into an unreadable
/// grey. The arc carries the number and the middle carries it again in digits, because at this
/// size an arc alone is a guess.
struct UsageAccessoryCircularView: View {

    let entry: UsageEntry

    var body: some View {
        let focus = WidgetFocus(snapshot: entry.snapshot)
        let mode = entry.snapshot.percentDisplay
        // Built from the raw row, so an exhausted window with no percentage still closes the ring
        // and reads "100%" in USED; a missing focus is the absent presentation.
        let p = focus?.account.presentation(
            of: focus?.row, at: entry.date, staleAfter: entry.snapshot.staleAfter, mode: mode)
            ?? WindowPresentation.absent(mode)
        let text = PercentText.figure(p)
        return ZStack {
            Circle()
                .stroke(.tertiary, style: StrokeStyle(lineWidth: 5, lineCap: .round))
            // Stroked in `.primary` whatever the fill state: the system re-tints this accessory and
            // the arc has never followed severity, so only a missing fill draws no arc.
            if let fraction = p.fill {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(.primary, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(Angle.degrees(-90))
            }
            VStack(spacing: 1) {
                WidgetPercentage(text: text)
                    .font(.system(.caption, design: .monospaced).weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                if focus?.account.statusMessage != nil {
                    Image(systemName: "exclamationmark.circle").font(.caption2)
                }
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement()
        .accessibilityLabel(
            focus.map { WidgetRingSpeech.label(account: $0.account, row: $0.row,
                presentation: p, date: entry.date, staleAfter: entry.snapshot.staleAfter) }
                ?? String(localized: "Open the app to see your quota"))
    }
}


