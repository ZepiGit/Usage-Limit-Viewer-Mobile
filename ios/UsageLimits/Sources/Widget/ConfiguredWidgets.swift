#if canImport(AppIntents)
import AppIntents
import SwiftUI
import WidgetKit
import UsageLimitsKit

@available(iOS 17.0, *)
enum WidgetContentChoice: String, AppEnum {
    case closestResets, allAccounts, provider, custom, account
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Widget content"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .closestResets: "Closest Resets", .allAccounts: "All accounts", .provider: "One provider",
        .custom: "Custom", .account: "One account"]
}

@available(iOS 17.0, *)
enum WidgetProviderChoice: String, AppEnum {
    case codex, claude, antigravity, xai, kimi, devin, meta, zai, opencode
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Provider"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .codex: "OpenAI Codex", .claude: "Claude", .antigravity: "Antigravity", .xai: "Grok", .kimi: "Kimi",
        .devin: "Devin", .meta: "Meta Muse", .zai: "Z.ai GLM", .opencode: "OpenCode Go"]
}

@available(iOS 17.0, *)
enum WidgetToneChoice: String, AppEnum {
    case light, dark
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Text tone"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .light: "Light", .dark: "Dark"]
}

@available(iOS 17.0, *)
enum WidgetModeChoice: String, AppEnum {
    // Raw values are the contract: `left`/`used` must equal `PercentDisplay`'s, because
    // `PercentDisplay.widgetOverride(rawChoice:)` reads the choice by raw value.
    case appSetting, left, used
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Show percentages as"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .appSetting: "App setting", .left: "Left", .used: "Used"]
}

@available(iOS 17.0, *)
struct WidgetAccountEntity: AppEntity {
    var id: String
    var name: String
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Account"
    static let defaultQuery = WidgetAccountQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

@available(iOS 17.0, *)
struct WidgetAccountQuery: EntityQuery {
    func suggestedEntities() async throws -> [WidgetAccountEntity] {
        let fallback = String(localized: "Account")
        return SnapshotCache.load().accounts.map {
            WidgetAccountEntity(id: $0.id, name: "\($0.title) · \($0.subtitle ?? fallback)")
        }
    }
    func entities(for identifiers: [String]) async throws -> [WidgetAccountEntity] {
        let all = try await suggestedEntities()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }
}

@available(iOS 17.0, *)
struct WidgetPresetEntity: AppEntity {
    var id: String
    var name: String
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Custom layout"
    static let defaultQuery = WidgetPresetQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

@available(iOS 17.0, *)
struct WidgetPresetQuery: EntityQuery {
    func suggestedEntities() async throws -> [WidgetPresetEntity] {
        try await WidgetPresetStore.shared.load().map { WidgetPresetEntity(id: $0.id, name: $0.name) }
    }
    func entities(for identifiers: [String]) async throws -> [WidgetPresetEntity] {
        let all = try await suggestedEntities()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }
}

@available(iOS 17.0, *)
struct UsageWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Widget content"
    static let description = IntentDescription("Closest Resets automatically shows whatever account resets next. Create custom layouts in the app's widget settings.")
    @Parameter(title: "Content", default: .closestResets) var content: WidgetContentChoice
    @Parameter(title: "Provider", default: .codex) var provider: WidgetProviderChoice
    @Parameter(title: "Account") var account: WidgetAccountEntity?
    @Parameter(title: "Custom layout") var custom: WidgetPresetEntity?
    @Parameter(title: "Transparent background", default: false) var transparent: Bool
    // A transparent tile has no panel: the wallpaper is the backdrop and the widget cannot
    // see it. The fixed light ink is unreadable over a light wallpaper, so the tone is the
    // user's explicit choice — the same contract as the Android LIGHT/DARK override. Light
    // is the default, which is what every existing transparent tile has always drawn.
    @Parameter(title: "Text tone", default: .light) var tone: WidgetToneChoice
    // App setting is the default because every widget placed before this existed follows the
    // app's mode, as it always did; Left and Used pin this one widget regardless of the app.
    @Parameter(title: "Show percentages as", default: .appSetting) var percentDisplay: WidgetModeChoice
}

@available(iOS 17.0, *)
struct ConfiguredUsageProvider: AppIntentTimelineProvider {
    typealias Intent = UsageWidgetIntent
    typealias Entry = ConfiguredUsageEntry
    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snapshot: .empty, transparent: false) }

    func snapshot(for configuration: Intent, in context: Context) async -> Entry {
        let capture = await self.capture(configuration)
        let date = Date()
        return Entry(date: date, snapshot: capture.selectedSnapshot(at: date),
            transparent: configuration.transparent, tone: configuration.tone,
            outcome: capture.outcome(at: date))
    }

    /// One request, one capture. The snapshot file is read exactly once and the preset store
    /// at most once — only for the Custom content, where a preset means anything. Every entry
    /// of the returned timeline derives from that capture, so the app publishing (or a preset
    /// being edited) mid-construction can no longer mix two input generations into one
    /// timeline; the NEXT request adopts the new generation wholesale.
    func timeline(for configuration: Intent, in context: Context) async -> Timeline<Entry> {
        let capture = await self.capture(configuration)
        let now = Date()
        let outcome = capture.outcome(at: now)
        let lead = capture.selectedSnapshot(at: now)
        var dates = stride(from: 0, through: 3600, by: 900).map { now.addingTimeInterval(Double($0)) }
        if let reset = lead.nextResetAt, reset > now, reset < now.addingTimeInterval(3600) {
            dates.append(reset.addingTimeInterval(2))
        }
        // Staleness and every selected row's reset need their own entries, inside the hour or
        // past it: the views age each account's severity at the entry's date, but only where
        // an entry exists to age it. Drawn from the captured cache — no reload, no network.
        dates.append(contentsOf: capture.presentationBoundaries(after: now))
        let entries = Set(dates).sorted().map { date in
            Entry(date: date, snapshot: capture.selectedSnapshot(at: date),
                transparent: configuration.transparent, tone: configuration.tone, outcome: outcome)
        }
        return Timeline(entries: entries, policy: .after(now.addingTimeInterval(900)))
    }

    /// Resolves everything one request needs, once: one snapshot read, at most one preset
    /// load. The outcome — including WHY a selection came up empty — is decided here and
    /// travels with the entry, instead of being re-derived (or re-guessed) by a view.
    private func capture(_ config: Intent) async -> ConfiguredWidgetCapture {
        let source = SnapshotCache.loadResult()
        let preset: ConfiguredWidgetCapture.PresetResolution
        if config.content == .custom {
            // A failed read is NOT an empty preset list: it is the reason the selection
            // is empty, and the entry keeps it.
            if let presets = try? await WidgetPresetStore.shared.load(),
               let match = presets.first(where: { $0.id == config.custom?.id }) {
                preset = .matched(accountIDs: match.accountIDs)
            } else {
                preset = .missing
            }
        } else {
            preset = .notRead
        }
        let scope: GlanceScope = switch config.content {
        case .closestResets: .closestResets
        case .allAccounts: .allAccounts
        case .provider: .provider
        case .custom: .custom
        case .account: .account
        }
        let percentOverride = PercentDisplay.widgetOverride(rawChoice: config.percentDisplay.rawValue)
        return ConfiguredWidgetCapture(source: source, preset: preset, scope: scope,
            accountID: config.account?.id, providerID: config.provider.rawValue,
            transparent: config.transparent, percentOverride: percentOverride)
    }
}

@available(iOS 17.0, *)
struct ConfiguredUsageEntry: TimelineEntry {
    var date: Date
    var snapshot: GlanceSnapshot
    var transparent: Bool
    var tone: WidgetToneChoice = .light
    var outcome: ConfiguredSelectionOutcome = .noAccounts
}

@available(iOS 17.0, *)
struct ConfiguredUsageWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "com.usagelimits.widget.configurable", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            Group {
                if entry.outcome == .ready {
                    UsageWidgetEntryView(entry: UsageEntry(date: entry.date, snapshot: entry.snapshot),
                        transparent: entry.transparent,
                        ink: WidgetInk.ink(transparent: entry.transparent, darkTone: entry.tone == .dark))
                } else {
                    WidgetEmptyStateView(reason: entry.outcome, ink: WidgetInk.ink(transparent: entry.transparent, darkTone: entry.tone == .dark))
                }
            }
            .widgetBackground(entry.transparent ? nil : UsageColors.ground)
            .widgetURL(DeepLink.glance)
        }.configurationDisplayName("Usage Bars")
            .description("Your accounts, your order. Choose one account, a provider or a custom layout.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
struct ConfiguredRingWidget: Widget {
    var mini: Bool
    init() { mini = false }
    init(mini: Bool) { self.mini = mini }
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: mini ? "com.usagelimits.widget.mini-rings" : "com.usagelimits.widget.account-rings", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            ConfiguredRingGrid(entry: entry, mini: mini,
                ink: WidgetInk.ink(transparent: entry.transparent, darkTone: entry.tone == .dark))
        }.configurationDisplayName(mini ? "Mini Rings" : "Account Rings")
            .description(mini ? "Only your usage rings and provider logos." : "Usage, account and reset at a glance.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
private struct ConfiguredRingGrid: View {
    let entry: ConfiguredUsageEntry
    let mini: Bool
    var ink: WidgetInk

    /// The line the mode caption takes above the grid, taken off the height before rows are
    /// counted so a captioned grid cannot overflow its tile. A deliberate capacity drop for a
    /// one-row-tall mini widget: one ring where two fitted before.
    @ScaledMetric(relativeTo: .caption2) private var captionSpace: CGFloat = 14

    /// From the snapshot alone: the widget process cannot read the app's settings.
    private var mode: PercentDisplay { entry.snapshot.percentDisplay }

    var body: some View {
        Group {
            if entry.outcome == .ready {
                grid
            } else {
                // Render the captured empty-selection outcome so its cause remains visible.
                WidgetEmptyStateView(reason: entry.outcome, ink: ink)
            }
        }
        .containerBackground(for: .widget) { entry.transparent ? Color.clear : UsageColors.ground }
    }

    private var grid: some View {
        GeometryReader { geometry in
            let columns = max(1, Int(geometry.size.width / (mini ? 48 : 140)))
            let rows = max(1, Int((geometry.size.height - captionSpace) / (mini ? 48 : 56)))
            VStack(alignment: .leading, spacing: 0) {
                if !entry.snapshot.accounts.isEmpty {
                    // One hint for the whole grid, independent of which status line each ring shows:
                    // a bare ring does not say which side it counts. The rings speak the mode
                    // themselves, so VoiceOver does not need the caption as well.
                    Text(verbatim: PercentText.caption(mode))
                        .usageFont(.micro)
                        .foregroundStyle(ink.secondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(height: captionSpace)
                        .accessibilityHidden(true)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns), spacing: 8) {
                    ForEach(entry.snapshot.accounts.prefix(columns * rows)) { account in
                        Link(destination: URL(string: "usagelimits://account/\(account.id)")!) {
                            // Resolved above the row so the drawn figure and the spoken one share it.
                            let limit = account.representative(at: entry.date)
                            let severity = account.displaySeverity(of: limit, at: entry.date, staleAfter: entry.snapshot.staleAfter)
                            // Built from the raw row; the aged `severity` above only colours the figure.
                            let p = account.presentation(of: limit, at: entry.date, staleAfter: entry.snapshot.staleAfter, mode: mode)
                            let figure = PercentText.figure(p)
                            HStack(spacing: 8) {
                                ZStack {
                                    // Reduce track opacity in accented/monochrome modes: the host can recolor both
                                    // circles white, so opacity must distinguish the track from the amount.
                                    Circle().stroke(ink.track, lineWidth: mini ? 4 : 5).opacity(trackOpacity)
                                    // No fraction, no arc: an unknown figure must not become a ring
                                    // drawn from "100 − 0".
                                    if let fill = p.fill, let arc = ink.fill(p.fillState) {
                                        Circle().trim(from: 0, to: CGFloat(fill))
                                            .stroke(arc, style: StrokeStyle(lineWidth: mini ? 4 : 5, lineCap: .round))
                                            .rotationEffect(.degrees(-90))
                                    }
                                    if mini {
                                        // Only room for the logo: the figure is the arc.
                                        logo(for: account).frame(width: 20, height: 20)
                                    } else {
                                        // A ring this size reads best with its number inside; the
                                        // logo goes beside the name instead.
                                        Text(verbatim: figure)
                                            .usageFont(p.hasFigure ? .smallStrong : .small)
                                            .foregroundStyle(ink.figure(severity))
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.85)
                                            .monospaced()
                                    }
                                }.frame(width: mini ? 38 : 46, height: mini ? 38 : 46)
                                if mini && account.statusMessage != nil {
                                    // Partial update: a dot, not a badge.
                                    Circle().fill(ink.warning).frame(width: 6, height: 6)
                                }
                                if !mini {
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            logo(for: account).frame(width: 16, height: 16)
                                            Text(verbatim: account.title).usageFont(.smallStrong).lineLimit(1)
                                        }
                                        if account.connectionStatus == .reconnectRequired { Text("Reconnect").usageFont(.micro) }
                                        else if let message = account.statusMessage { Text(verbatim: DisplayText.stored(message)).usageFont(.micro).lineLimit(2) }
                                        // Passed resets need an overdue label; future-day resets need a date, not only a time.
                                        else if let reset = GlanceText.resetLine(resetAt: limit?.resetAt, now: entry.date) {
                                            Text(verbatim: reset).usageFont(.micro).lineLimit(1)
                                        }
                                    }.foregroundStyle(ink.primary)
                                }
                            }.accessibilityLabel(spoken(account, drawnFigure: figure))
                        }
                    }
                }
            }
        }
    }

    /// LEFT is the sentence spoken before the mode existed, which reads the lowest figure across
    /// every window. USED speaks `drawnFigure`, the very string the ring prints: an exhausted
    /// 5h window whose reset has passed, beside an open weekly at 60% left, draws "40% used" from
    /// the representative row, and a second derivation over all rows announced "100% used".
    /// Taking the drawn figure in means the two cannot be computed apart again.
    private func spoken(_ account: GlanceAccount, drawnFigure: String) -> String {
        let sentence: String
        if mode == .used {
            sentence = PercentText.spoken(drawnFigure, .used)
        } else {
            let lowest = account.rows.map(\.remainingPercent).compactMap { $0 }.min()
            sentence = PercentText.spoken(UsageWindow.percentLabel(lowest), .left)
        }
        return "\(account.title), \(account.subtitle ?? ""), \(sentence)"
    }

    /// The provider's mark, drawn in the ink's own colour when the tile sits on a light
    /// wallpaper, where the white artwork would vanish.
    @ViewBuilder
    private func logo(for account: GlanceAccount) -> some View {
        if let provider = ProviderID(rawValue: account.providerID ?? "") {
            ProviderMark(
                assetName: account.iconAssetName ?? provider.assetName,
                tint: ink.tintsLogos ? ink.primary : nil)
        }
    }

    private var trackOpacity: Double {
        renderingMode == .fullColor ? 1 : 0.3
    }

    @Environment(\.widgetRenderingMode) private var renderingMode
}
#endif
