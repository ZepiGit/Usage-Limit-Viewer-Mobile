// Shared widget bars, empty states, and percentage figures.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Shared views

/// Track opacity for accented and monochrome host renderings.
/// The environment read is availability-gated for iOS 17; iOS 16 keeps full-opacity tracks.
struct AccentedTrackYield: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.modifier(ModernTrackYield())
        } else {
            content
        }
    }
}

@available(iOS 17.0, *)
private struct ModernTrackYield: ViewModifier {
    @Environment(\.widgetRenderingMode) private var renderingMode

    func body(content: Content) -> some View {
        content.opacity(renderingMode == .fullColor ? 1 : 0.3)
    }
}

/// One quota bar.
///
/// A fixed height on a *bar* is legitimate — the Dynamic Type rule concerns text —
/// and `@ScaledMetric` grows the bar with the user's type size regardless.
struct QuotaBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The figure, the fill and how it is painted, built once per row from the RAW row so the aged
    /// severity that colours the figure cannot change the bar.
    let presentation: WindowPresentation
    /// Spoken context ("Claude, Messages") so the label below reads as a sentence.
    let context: String
    var ink: WidgetInk = .light

    @ScaledMetric(relativeTo: .caption) private var barHeight = 4

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(ink.track)
                    .modifier(AccentedTrackYield())
                if let fraction = presentation.fill, let fill = ink.fill(presentation.fillState) {
                    // Unknown percentages draw the empty track only — never a 0 % sliver.
                    Capsule()
                        .fill(fill)
                        .frame(width: proxy.size.width * fraction)
                }
            }
        }
        .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: presentation.fill)
        .frame(height: barHeight)
        // A bar shape tells VoiceOver nothing; state the number in words.
        .accessibilityElement()
        .accessibilityLabel(spokenText)
    }

    private var spokenText: String {
        guard presentation.hasFigure else {
            return String(localized: "\(context): remaining unknown")
        }
        let said = PercentText.spoken(PercentText.figure(presentation), presentation.side)
        return String(localized: "\(context): \(said)")
    }
}

/// Rendered whenever the cache is missing, unreadable or empty.
///
/// The widget cannot open a dialogue with the user, so its only honest message is
/// that the app has the numbers — never a fabricated percentage to look busy.

/// What both tiles show when there is nothing to show.
///
/// Deliberately not a blank tile, and deliberately not one message for every cause.
///
/// A tile with nothing on it has several quite different causes, and each wants a different
/// sentence. The message comes from the outcome CAPTURED with the entry — the configured
/// provider resolves its selection and carries the reason; the legacy provider carries the
/// read outcome. No view re-reads the snapshot file while being archived: a second read is
/// both wasted I/O and a fresh generation that can disagree with the one the entry was
/// built from. The reason decides between "open the app" and "edit this widget" — a selected
/// account that no longer exists is not "no accounts yet", and no wording here says
/// "you have quota left".
/// Internal: the configured widgets render this same empty state from their captured
/// outcome, rather than growing a second copy of its wording.
struct WidgetEmptyStateView: View {

    var reason: ConfiguredSelectionOutcome = .noAccounts
    var ink: WidgetInk = .light

    private var title: String {
        switch reason {
        case .missingAccount: return String(localized: "Account unavailable")
        case .missingPreset: return String(localized: "Layout unavailable")
        case .sourceUnavailable: return String(localized: "Locked")
        case .corruptSource: return String(localized: "Can't read usage")
        case .ready, .noAccounts: return String(localized: "No accounts yet")
        }
    }

    private var detail: String {
        switch reason {
        case .missingAccount: return String(localized: "Edit this widget and choose another account")
        case .missingPreset:
            return String(localized: "Recreate this layout in UsageLimits, then edit this widget")
        case .sourceUnavailable: return String(localized: "Unlock this device to see how much quota is left")
        case .corruptSource: return String(localized: "Open UsageLimits to rebuild the data")
        case .ready, .noAccounts: return String(localized: "Open UsageLimits to see how much quota is left")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .usageFont(.bodyStrong)
                .foregroundColor(ink.primary)
            Text(detail)
                .usageFont(.small)
                .foregroundColor(ink.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// WidgetKit animates only when a timeline value changes; no timer or background task is added.
struct WidgetPercentage: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The composed figure ("45%", "—"), already decided for the snapshot's mode by `PercentText`.
    let text: String

    var body: some View {
        if #available(iOS 17.0, *), !reduceMotion {
            Text(verbatim: text)
                .contentTransition(ContentTransition.numericText())
        } else {
            Text(verbatim: text)
                .transaction { if reduceMotion { $0.animation = nil; $0.disablesAnimations = true } }
        }
    }
}

/// A percentage in the figure face and the colour its severity earns on this ink — or, when the
/// provider reported none, a small dash. A missing value must not hold the weight of a figure.
///
/// The dash is decided by the figure, not by the raw percentage, so an exhausted window with no
/// percentage still reads "100%" in USED. In LEFT the two are the same test.
struct WidgetFigure: View {
    /// Built from the raw row; the aged `severity` below only colours the figure.
    let presentation: WindowPresentation
    let font: UsageFont
    let ink: WidgetInk
    let severity: Severity
    /// Bars show the side beside the figure; rings retain it in their spoken label only.
    var showsSideWord: Bool = true

    private var text: String {
        PercentText.figure(presentation)
    }

    var body: some View {
        if !presentation.hasFigure {
            Text(verbatim: text)
                .usageFont(.small)
                .foregroundColor(ink.secondary)
        } else if showsSideWord {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                WidgetPercentage(text: text)
                    .usageFont(font)
                    .foregroundColor(ink.figure(severity))
                Text(verbatim: PercentText.sideWord(presentation.side))
                    .usageFont(.micro)
                    .foregroundColor(ink.secondary)
            }
        } else {
            WidgetPercentage(text: text)
                .usageFont(font)
                .foregroundColor(ink.figure(severity))
        }
    }
}
