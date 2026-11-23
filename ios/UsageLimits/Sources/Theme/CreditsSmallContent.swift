import Foundation
import SwiftUI
import UsageLimitsKit

struct CreditsSmallContent: View {
    let credit: GlanceCredit
    let date: Date
    let staleAfter: TimeInterval
    let ink: WidgetInk
    @ScaledMetric(relativeTo: .title2) private var amountSize: Double = 28
    @ScaledMetric(relativeTo: .subheadline) private var titleSize: Double = 14
    @ScaledMetric(relativeTo: .caption2) private var statusSize: Double = 11
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let severity = credit.severity(at: date, staleAfter: staleAfter)
        let status = statusLine(severity)
        // Measure the complete wording in both layouts before considering a shorter label.
        ViewThatFits(in: .vertical) {
            reading(severity: severity, status: status, label: status, reflow: false)
            reading(severity: severity, status: status, label: status, reflow: true)
            if let compact = Self.compactStatus(for: credit) {
                reading(severity: severity, status: compact, label: status, reflow: true)
            }
        }
    }

    private func reading(severity: Severity, status: String, label: String, reflow: Bool) -> some View {
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

    private func statusLine(_ severity: Severity) -> String {
        if let message = credit.statusMessage { return DisplayText.stored(message) }
        // Stale, failed or unread: say so rather than dating a number that cannot be trusted.
        if severity == .stale || severity == .error || severity == .unknown { return SeverityPalette.label(severity) }
        return Countdown.asOfLine(updatedAt: credit.fetchedAt, now: date, texts: LocalizedCountdownTexts())
            ?? SeverityPalette.label(severity)
    }
}

extension GlanceCredit {
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

    fileprivate var providerName: String? {
        CreditProviderID(rawValue: providerID)?.displayName
    }
}

/// A balance bar, drawn only when the provider states a total.
struct CreditBar: View {
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

private enum QuotaFormatting {

    /// Normalises a 0…100 percentage to 0…1 for bar widths and labels.
    ///
    /// The previous version guessed the scale from the value — anything above 1 was
    /// treated as a percentage and anything at or below 1 as a fraction already. But the
    /// kit's `remainingPercent` is 0…100 everywhere (`UsageWindow.swift`, `min(max(100 -
    /// used, 0), 100)`), so a quota with 1 % left arrived as `1.0`, was taken to be a
    /// fraction, and rendered as "100%" with a completely full bar. The Android widget
    /// showed "1%" for the identical snapshot. A nearly-exhausted quota reading as nearly
    /// full is the single worst number this app can show, so the scale is no longer
    /// inferred: the input is always a percentage. Clamping still stops a corrupt cache
    /// value from overflowing the bar.
    static func fraction(_ percent: Double) -> Double {
        guard percent.isFinite, (0...100).contains(percent) else { return 0 }
        return percent / 100
    }
}
