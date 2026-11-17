import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

struct PinnedAccountsWidgetEntry: TimelineEntry {
    let capture: PinnedAccountsCapture

    var date: Date { capture.date }
}

struct PinnedAccountsProvider: TimelineProvider {
    func placeholder(in context: Context) -> PinnedAccountsWidgetEntry {
        PinnedAccountsWidgetEntry(capture: .capture(snapshot: .empty, at: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (PinnedAccountsWidgetEntry) -> Void) {
        let snapshot = SnapshotCache.load()
        completion(PinnedAccountsWidgetEntry(capture: .capture(snapshot: snapshot, at: Date())))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PinnedAccountsWidgetEntry>) -> Void) {
        let snapshot = SnapshotCache.load()
        let now = Date()
        let entries = PinnedAccountsCapture.entryDates(snapshot: snapshot, after: now).map { date in
            PinnedAccountsWidgetEntry(capture: .capture(snapshot: snapshot, at: date))
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(4 * 60 * 60))))
    }
}

struct PinnedAccountsWidgetView: View {
    let entry: PinnedAccountsWidgetEntry

    var body: some View {
        Group {
            if entry.capture.accounts.isEmpty {
                Text("Pin an account in Settings")
                    .font(.caption)
            } else {
                HStack(spacing: 4) {
                    ForEach(entry.capture.accounts) { account in
                        PinnedAccountCell(entry: account)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .foregroundStyle(.primary)
        .widgetBackground(nil)
    }
}

private struct PinnedAccountCell: View {
    let entry: PinnedAccountEntry

    @ScaledMetric(relativeTo: .headline) private var glyphSize = 16

    var body: some View {
        HStack(spacing: 2) {
            glyph
                .frame(width: glyphSize, height: glyphSize)
                .overlay(alignment: .bottom) {
                    if case .none = entry.content.content, entry.content.outdated {
                        Rectangle().frame(height: 1).offset(y: 3)
                    }
                }
            if let contentText {
                if entry.content.outdated {
                    Text(verbatim: contentText).underline()
                } else {
                    Text(verbatim: contentText)
                }
            }
        }
        .font(.system(.headline, design: .monospaced).weight(.semibold))
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenText)
    }

    private var contentText: String? {
        switch entry.content.content {
        case .figure(let value): return String(value)
        case .none: return nil
        case .unknown: return PercentToken.unknown
        case .error: return "!"
        case .exhausted: return "⊗"
        }
    }

    @ViewBuilder
    private var glyph: some View {
        if let assetName = entry.account.iconAssetName {
            ProviderMark(assetName: assetName, tint: .primary)
        } else {
            Image(systemName: "person.crop.circle")
                .resizable()
                .scaledToFit()
        }
    }

    private var spokenText: String {
        PinnedAccountText.spoken(
            entry: entry,
            texts: LocalizedPercentTexts(),
            error: entry.account.connectionStatus == .reconnectRequired
                ? String(localized: "Sign-in expired") : String(localized: "Error"),
            exhausted: String(localized: "Exhausted"),
            unknown: String(localized: "Unknown"),
            outdated: String(localized: "Stale"))
    }
}

struct PinnedAccountsWidget: Widget {
    private static let kind = "com.usagelimits.widget.pinned"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: PinnedAccountsProvider()) { entry in
            PinnedAccountsWidgetView(entry: entry)
        }
        .configurationDisplayName("Pinned accounts")
        .description("Your pinned usage accounts on the Lock Screen.")
        .supportedFamilies([.accessoryRectangular])
    }
}
