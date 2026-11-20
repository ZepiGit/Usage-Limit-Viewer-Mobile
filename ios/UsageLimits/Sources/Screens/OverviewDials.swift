import SwiftUI
import UsageLimitsKit

struct OverviewDials: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .headline) private var appColumnWidth: Double = 140
    @ScaledMetric(relativeTo: .caption) private var previewColumnWidth: Double = 100

    let entries: [OverviewProjection.Entry]
    let now: Date
    let staleAfter: TimeInterval
    let mode: PercentDisplay
    let calendar: Calendar
    var preview = false
    var openAccount: ((String) -> Void)? = nil
    var addAccount: (() -> Void)? = nil

    var body: some View {
        let identities = OverviewProjection.sameProviderIdentities(in: entries)
        if dynamicTypeSize >= .accessibility1 {
            singleColumn(identities)
        } else if preview {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 8) { cells(identities) }
                    .frame(minWidth: columnWidth * 2 + 8)
                singleColumn(identities)
            }
        } else {
            ViewThatFits(in: .horizontal) {
                LazyVGrid(columns: [
                    GridItem(.flexible(minimum: columnWidth), spacing: 10),
                    GridItem(.flexible(minimum: columnWidth), spacing: 10)
                ], spacing: 10) {
                    cells(identities)
                }
                .frame(minWidth: columnWidth * 2 + 10)
                singleColumn(identities)
            }
        }
    }

    private var columnWidth: Double { preview ? previewColumnWidth : appColumnWidth }

    private func singleColumn(_ identities: [String: SameProviderIdentity]) -> some View {
        VStack(spacing: 10) { cells(identities) }
    }

    @ViewBuilder private func cells(_ identities: [String: SameProviderIdentity]) -> some View {
        ForEach(entries) { entry in
            OverviewDialTile(entry: entry, now: now, staleAfter: staleAfter,
                mode: mode, calendar: calendar, preview: preview,
                identity: identities[entry.id] ?? .providerOnly, openAccount: openAccount)
        }
        if !preview, let addAccount {
            Button(action: addAccount) {
                VStack(spacing: 10) {
                    Image(systemName: "plus").font(.system(size: 28)).accessibilityHidden(true)
                    Text("Add account").usageFont(.body)
                }
                .foregroundStyle(UsageColors.muted)
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 178)
                .contentShape(RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(OverviewDialButtonStyle(dashed: true))
            .accessibilityLabel(String(localized: "Add account"))
            .accessibilityIdentifier("overview-add-account")
        }
    }
}

private struct OverviewDialTile: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: OverviewProjection.Entry
    let now: Date
    let staleAfter: TimeInterval
    let mode: PercentDisplay
    let calendar: Calendar
    let preview: Bool
    let identity: SameProviderIdentity
    let openAccount: ((String) -> Void)?

    private var identityLabel: String? {
        switch identity {
        case .providerOnly: return nil
        case .email(let email): return email
        case .number(let number): return String(localized: "Account \(number)")
        }
    }

    private var account: GlanceAccount { entry.account }
    private var expired: Bool { account.connectionStatus == .reconnectRequired }
    private var provider: ProviderID? { account.providerID.flatMap(ProviderID.init(rawValue:)) }
    private var name: String { provider?.displayName ?? account.title }
    private var diameter: Double { preview ? 64 : 104 }
    private var stroke: Double { preview ? 5 : 8 }
    private var figureOutside: Bool { dynamicTypeSize >= .xLarge }

    var body: some View {
        let row = entry.headlineWindowID.flatMap { id in account.candidates.first { $0.sourceWindowID == id } }
        let validity = account.readingValidity(of: row, at: now, staleAfter: staleAfter)
        let presentation = row.map {
            WindowPresentation(remaining: $0.remainingPercent, severity: $0.severity,
                validity: expired ? .stale : validity, mode: mode)
        } ?? .absent(mode)
        let severity = account.displaySeverity(of: row, at: now, staleAfter: staleAfter)
        let value = spoken(presentation, row: row, validity: validity)
        let identity = identityLabel
        let label = identity.map { String(localized: "\(name) · \($0)") } ?? name
        let tile = content(presentation, row: row, validity: validity, severity: severity, identity: identity)
        if let openAccount {
            Button { openAccount(entry.id) } label: { tile }
                .buttonStyle(OverviewDialButtonStyle())
                .accessibilityLabel(label)
                .accessibilityValue(value)
                .accessibilityIdentifier("overview-dial-\(entry.id)")
        } else {
            tile
                .background(UsageColors.surface, in: RoundedRectangle(cornerRadius: 16))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
                .accessibilityValue(value)
                .accessibilityIdentifier("settings-preview-dial-\(entry.id)")
        }
    }

    private func content(_ presentation: WindowPresentation, row: GlanceRow?,
                         validity: ReadingValidity, severity: Severity, identity: String?) -> some View {
        VStack(spacing: 10) {
            ZStack {
                ring(presentation, row: row)
                VStack(spacing: preview ? 2 : 4) {
                    if let provider {
                        ProviderBadge(provider: provider, size: preview ? 16 : 30)
                            .accessibilityHidden(true)
                    }
                    if !figureOutside { figure(presentation, severity: severity) }
                }
            }
            .frame(width: diameter, height: diameter)
            .accessibilityHidden(true)
            if figureOutside { figure(presentation, severity: severity).accessibilityHidden(true) }
            VStack(spacing: 4) {
                Text(verbatim: name)
                    .usageFont(preview ? .smallStrong : .bodyStrong)
                    .foregroundStyle(UsageColors.ink)
                if let identity {
                    Text(verbatim: identity).usageFont(.small).foregroundStyle(UsageColors.muted)
                }
                if expired {
                    Text("Reconnect").usageFont(.small).foregroundStyle(UsageColors.negativeText)
                } else if let row {
                    Text(verbatim: windowStamp(row, validity: validity))
                        .usageFont(.small).foregroundStyle(UsageColors.faint)
                }
            }
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, preview ? 12 : 16)
        .padding(.horizontal, 12)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity, minHeight: 44)
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    private func ring(_ presentation: WindowPresentation, row: GlanceRow?) -> some View {
        ZStack {
            Circle().stroke(UsageColors.rule, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            Circle()
                .trim(from: 0, to: presentation.fill ?? 0)
                .stroke(arcColor(presentation, row: row), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(Angle.degrees(-90))
                .motion(.move, value: presentation.fill)
        }
        .padding(stroke / 2)
        .frame(width: diameter, height: diameter)
        .subject(entry.headlineWindowID)
    }

    private func arcColor(_ presentation: WindowPresentation, row: GlanceRow?) -> Color {
        guard presentation.fill != nil, let fill = SeverityPalette.fill(presentation.fillState) else { return .clear }
        if expired { return UsageColors.faint }
        if presentation.fillState == .data, row?.severity == .low || row?.severity == .exhausted {
            return UsageColors.warning
        }
        return fill
    }

    private func figure(_ presentation: WindowPresentation, severity: Severity) -> some View {
        let text = PercentText.figure(presentation)
        return Text(verbatim: text)
            .usageFont(preview ? .compactFigure : .dialFigure)
            .foregroundStyle(expired || !presentation.hasFigure ? UsageColors.faint : SeverityPalette.figureColor(severity))
            .fixedSize(horizontal: true, vertical: true)
            .figureUnit(key: [text, mode.rawValue], anchor: .center)
            .subject(entry.headlineWindowID)
    }

    private func windowStamp(_ row: GlanceRow, validity: ReadingValidity) -> String {
        let label = DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
        if validity == .resetDue {
            let stamp = String(localized: "Reset due")
            return String(localized: "\(label) · \(stamp)")
        }
        if let reset = row.resetAt {
            let stamp = Countdown.absolute(reset, now: now, calendar: calendar)
            return String(localized: "\(label) · \(stamp)")
        }
        return label
    }

    private func spoken(_ presentation: WindowPresentation, row: GlanceRow?, validity: ReadingValidity) -> String {
        var parts = [PercentText.spoken(PercentText.figure(presentation), mode)]
        if !presentation.hasFigure { parts.append(String(localized: "Unknown")) }
        if let row {
            let label = DisplayText.windowLabel(row.label, category: row.category, scope: row.scope)
            parts.append(String(localized: "Headline: \(label)"))
            if let reset = row.resetAt {
                let stamp = Countdown.absolute(reset, now: now, calendar: calendar)
                parts.append(String(localized: "Resets \(stamp)"))
            }
        }
        switch validity {
        case .current:
            if let row, row.severity == .low || row.severity == .exhausted || row.severity == .limited {
                parts.append(SeverityPalette.label(row.severity))
            }
        case .stale: parts.append(String(localized: "Stale"))
        case .resetDue: parts.append(String(localized: "Reset due"))
        case .error: parts.append(String(localized: "Error"))
        }
        if expired {
            parts.append(String(localized: "Sign-in expired · showing last known data"))
            parts.append(String(localized: "Reconnect"))
        }
        if let message = account.statusMessage, !expired || message != NotificationEvaluator.signInExpiredMessage {
            parts.append(DisplayText.stored(message))
        }
        return parts.joined(separator: ", ")
    }
}

private struct OverviewDialButtonStyle: ButtonStyle {
    var dashed = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? UsageColors.raised : dashed ? Color.clear : UsageColors.surface,
                in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                if dashed {
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(UsageColors.control, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
            }
            .pressRelease(isPressed: configuration.isPressed)
    }
}
