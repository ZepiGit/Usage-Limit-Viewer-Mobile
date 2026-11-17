import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

struct PinnedAccountsWidgetEntry: TimelineEntry {
    let capture: PinnedAccountsCapture

    var date: Date { capture.date }
}

struct PinnedAccountsProvider: TimelineProvider {
    private static let routineReloadInterval: TimeInterval = 4 * 60 * 60
    private static let overdueReloadInterval: TimeInterval = 15 * 60

    func placeholder(in context: Context) -> PinnedAccountsWidgetEntry {
        PinnedAccountsWidgetEntry(capture: .capture(snapshot: .empty, at: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (PinnedAccountsWidgetEntry) -> Void) {
        let source = SnapshotCache.loadResult()
        completion(PinnedAccountsWidgetEntry(capture: .capture(source: source, at: Date())))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PinnedAccountsWidgetEntry>) -> Void) {
        let source = SnapshotCache.loadResult()
        let now = Date()
        let entries = PinnedAccountsCapture.entryDates(snapshot: source.snapshot, after: now).map { date in
            PinnedAccountsWidgetEntry(capture: .capture(source: source, at: date))
        }
        let interval = source == .unreadable ? Self.overdueReloadInterval : Self.routineReloadInterval
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(interval))))
    }
}

struct PinnedAccountsWidgetView: View {
    let entry: PinnedAccountsWidgetEntry

    var body: some View {
        Group {
            if entry.capture.outcome == .sourceUnavailable {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Locked").font(.headline)
                    Text("Unlock this device to see how much quota is left").font(.caption2)
                }
            } else if entry.capture.outcome == .corruptSource {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Can't read usage").font(.headline)
                    Text("Open UsageLimits to rebuild the data").font(.caption2)
                }
            } else if entry.capture.accounts.isEmpty {
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
            if let contentText = PinnedAccountText.cellText(entry) {
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
        PinnedAccountText.spoken(entry: entry, texts: LocalizedPinnedAccountTexts())
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
