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
        let source = SnapshotCache.loadResult()
        completion(PinnedAccountsWidgetEntry(capture: .capture(source: source, at: Date())))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PinnedAccountsWidgetEntry>) -> Void) {
        let source = SnapshotCache.loadResult()
        let now = Date()
        let entries = PinnedAccountsCapture.entryDates(snapshot: source.snapshot, after: now).map { date in
            PinnedAccountsWidgetEntry(capture: .capture(source: source, at: date))
        }
        let interval = PinnedAccountsCapture.capture(source: source, at: now).reloadInterval
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(interval))))
    }
}

/// Routes each render to the family the system asked for; the views are in PinnedAccountsViews.
struct PinnedAccountsWidgetView: View {
    @Environment(\.widgetFamily) private var family

    let entry: PinnedAccountsWidgetEntry

    var body: some View {
        PinnedAccountsFamilyView(family: family, capture: entry.capture)
            .foregroundStyle(.primary)
            .widgetBackground(nil)
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
        .supportedFamilies([.accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}
