//
//  UsageWidget.swift
//  UsageLimitsWidget
//
//  Home Screen tiles (plus a Lock Screen accessory) summarising how much quota is
//  left on the user's AI subscriptions. Every number comes from the JSON snapshot
//  the main app writes into the shared App Group container; the widget itself never
//  fetches anything.
//
//  This file holds the timeline entry, the provider, the snapshot cache, the
//  entry-view routers and the widget declarations. The views live beside it:
//  UsageHomeViews, UsageRingView, UsageAccessoryViews, WidgetSharedViews and
//  WidgetPresentation.
//
//  Two invariants govern every file in this directory:
//
//  1. A widget cannot tick. It re-renders only when WidgetKit asks it to, so
//     nothing shown is phrased relative to "now": resets are absolute clock times,
//     and the data carries its own "As of" timestamp rather than an age.
//  2. The tile may state only what the cache states. An unknown percentage is an
//     em dash over an empty bar — never a 0 % or a 100 % invented for the occasion.
//

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Timeline entry

/// A single renderable moment in the widget's timeline.
///
/// `date` is the moment WidgetKit will *display* this entry, not the moment it was
/// built: every absolute time in the views below is formatted against it, so an
/// entry replayed from the timeline still names times that were true at that moment.
struct UsageEntry: TimelineEntry {
    let date: Date
    let snapshot: GlanceSnapshot

    /// Why there may be nothing to draw, captured where the source was read. The views render
    /// this instead of re-reading the snapshot file while being archived — a second read can
    /// return a different generation than the one the entry was built from. Nil while the
    /// entry carries a usable snapshot.
    var unavailable: ConfiguredSelectionOutcome? = nil
}

// MARK: - Timeline provider

/// Supplies the widget's timeline from the App Group cache.
struct UsageProvider: TimelineProvider {

    /// Routine reload cadence when nothing is about to change; comfortably inside
    /// the system's daily budget, so the greedy requests that get a widget
    /// throttled are never made in the first place.
    private static let routineReloadInterval: TimeInterval = 4 * 60 * 60

    /// A cache whose reset has already passed is wrong, not merely old, so it gets
    /// a prompt retry — prompt, not instant: WidgetKit throttles greed.
    private static let overdueReloadInterval: TimeInterval = 15 * 60

    /// Grace after a reset for the app to rewrite the cache before we reload.
    private static let postResetGrace: TimeInterval = 2 * 60

    func placeholder(in context: Context) -> UsageEntry {
        // The gallery placeholder is deliberately the empty state rather than
        // plausible-looking sample numbers: a preview that invents "12 %" invites
        // the user to trust a figure no app ever wrote. It is a worse advert than
        // a fabricated one — and the only one we can defend.
        UsageEntry(date: Date(), snapshot: .empty, unavailable: .noAccounts)
    }

    func getSnapshot(in context: Context, completion: @escaping (UsageEntry) -> Void) {
        // One synchronous read of a few hundred bytes: the user is staring at the
        // tile, so a fast honest answer beats a deferred one.
        let load = SnapshotCache.loadResult()
        completion(Self.entry(from: load, at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<UsageEntry>) -> Void) {
        let load = SnapshotCache.loadResult()
        let now = Date()
        let snapshot = Self.criticalSelection(of: load.snapshot, now: now)
        var dates = Self.entryDates(now: now, nextResetAt: snapshot.nextResetAt)
        // A staleness boundary past the reset horizon needs its own entry: the views age
        // severity at each entry's date, but only where an entry exists to age it. Drawn
        // from the captured cache — no reload, no network, just the flip the cached data
        // itself implies when no refresh has come to overwrite it.
        // Every shown row's reset too, not only the lead's: a medium tile's second account
        // otherwise kept its spent number in quota colour until the next budgeted reload.
        dates.append(contentsOf: snapshot.presentationBoundaries(after: now))
        let unavailable = Self.unavailable(from: load)
        // Ranked at each entry's own date, as a fresh build then would: an account that ages
        // stale mid-timeline stops leading on its write-time verdict.
        let entries = Set(dates).sorted().map {
            UsageEntry(date: $0, snapshot: Self.criticalSelection(of: load.snapshot, now: $0), unavailable: unavailable)
        }
        completion(
            Timeline(
                entries: entries,
                policy: .after(Self.refreshDate(now: now, nextResetAt: snapshot.nextResetAt))
            )
        )
    }

    // MARK: Snapshot shaping

    /// The legacy tiles' stated focus is the account most in need of attention. The published
    /// export is in register order, the order accounts were stored in, not in order of urgency,
    /// so the tiles run a selection pass of their own. It runs against a COPY of the export:
    /// the export keeps register order, because Pinned accounts and the Provider scope read it.
    static func criticalSelection(of snapshot: GlanceSnapshot, now: Date) -> GlanceSnapshot {
        snapshot.selecting(scope: .mostCritical, now: now)
    }

    /// One entry from one read. `mostCritical` ordering and every view decision derive from
    /// the same captured generation.
    private static func entry(from load: GlanceSnapshotCodec.Load, at date: Date) -> UsageEntry {
        UsageEntry(date: date, snapshot: criticalSelection(of: load.snapshot, now: date),
            unavailable: unavailable(from: load))
    }

    /// The read outcome, translated once. A missing file is "no accounts yet" — the app has
    /// not published anything, and it is not a locked device. Only an unreadable existing
    /// file, whose expected cause is data protection, claims that.
    private static func unavailable(from load: GlanceSnapshotCodec.Load) -> ConfiguredSelectionOutcome {
        switch load {
        case .loaded: return .ready
        case .empty, .missing: return .noAccounts
        case .unreadable: return .sourceUnavailable
        case .corrupt: return .corruptSource
        }
    }

    // MARK: Timeline maths

    /// Timeline policy, and why it has this shape.
    ///
    /// WidgetKit hands a widget a small reload budget (tens of system-scheduled
    /// loads per day) and throttles greed: requesting a reload every minute earns
    /// *fewer* reloads, not more. Replaying entries we have already supplied is
    /// free, by contrast — WidgetKit simply re-renders the existing view at each
    /// date, without loading the extension at all. The policy therefore:
    ///
    /// 1. Bakes in a handful of entries clustered around the next reset (6 h,
    ///    1 h, 15 min, 5 min and 1 min before it, then the boundary itself) so the
    ///    tile re-renders precisely when its display can change. What it shows are
    ///    absolute clock times, which stay correct between renders; the boundary
    ///    entry exists so the "Resets …" line flips to "Reset · refresh pending"
    ///    the instant that claim becomes untrue.
    /// 2. Spends exactly one budgeted reload `.after` the reset — two minutes into
    ///    the grace window in which the app rewrites the cache — because that is
    ///    the only moment at which the cached numbers are wrong rather than merely
    ///    old.
    /// 3. Falls back to a four-hourly reload when no reset is near, picking up
    ///    whatever the app has since written, still comfortably inside budget.
    private static func entryDates(now: Date, nextResetAt reset: Date?) -> [Date] {
        var dates = [now]
        guard let reset, reset > now else { return dates }
        // Annotated, because the literal mixes a Double expression with integer ones and Swift
        // then infers `[Any]` rather than complaining about the mix — which compiles as an array
        // and fails at the arithmetic.
        let leadTimes: [TimeInterval] = [6 * 60 * 60, 60 * 60, 15 * 60, 5 * 60, 60]
        for lead in leadTimes {
            let date = reset.addingTimeInterval(-lead)
            // Skip lead-ins already past, or so close to `now` they would merely duplicate it.
            if date > now.addingTimeInterval(2 * 60) {
                dates.append(date)
            }
        }
        // The boundary entry: crosses the reset so the view can stop claiming one is pending.
        dates.append(reset)
        return dates
    }

    /// The single budgeted reload point; see `entryDates(now:nextResetAt:)`'s
    /// discussion for the shape of the policy.
    private static func refreshDate(now: Date, nextResetAt reset: Date?) -> Date {
        guard let reset else {
            return now.addingTimeInterval(routineReloadInterval)
        }
        if reset <= now {
            return now.addingTimeInterval(overdueReloadInterval)
        }
        // Whichever comes first: just after the reset, or the routine check.
        return min(
            reset.addingTimeInterval(postResetGrace),
            now.addingTimeInterval(routineReloadInterval)
        )
    }
}
// MARK: - Snapshot cache

/// Read the app's complete reduced snapshot from the App Group container through the
/// shared kit codec. Recomputing derived values here could disagree with the app.
enum SnapshotCache {

    /// The App Group identifier shared by the app and this extension. One named constant, so
    /// the container can never be opened under two spellings.
    static let appGroupID = "group.com.usagelimits.shared"

    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    /// Never throws and never returns nil: the caller always gets something renderable.
    ///
    /// Every failure — no entitlement, a missing file, a half-written one — funnels to the empty
    /// snapshot, which renders as stale rather than as an all-clear. A widget cannot open a
    /// dialogue or ask for a retry, so the pessimistic reading is the only honest one.
    static func load() -> GlanceSnapshot {
        loadResult().snapshot
    }

    /// The read outcome, so the tile can say which of the several nothings it got.
    static func loadResult() -> GlanceSnapshotCodec.Load {
        guard let containerURL else { return .missing }
        return GlanceSnapshotCodec.load(fromDirectory: containerURL)
    }

}

/// Routes each render to the view for the family the system asked for.
struct UsageWidgetEntryView: View {

    @Environment(\.widgetFamily) private var family

    let entry: UsageEntry
    /// Whether this gallery entry is the transparent one. Carried rather than read from a
    /// setting, because a widget's look is chosen per placed tile and two tiles of the same
    /// kind may legitimately differ.
    var transparent: Bool = false
    /// The ink to write with. Opaque tiles pair with the light palette; a transparent tile
    /// whose tone is configured dark gets the dark one. Defaults to light, which is what the
    /// legacy widgets — with no tone configuration — have always written.
    var ink: WidgetInk = .light

    var body: some View {
        switch family {
        case .systemLarge:
            UsageWidgetLargeView(entry: entry, transparent: transparent, ink: ink)
        case .systemMedium:
            UsageWidgetMediumView(entry: entry, transparent: transparent, ink: ink)
        case .accessoryRectangular:
            UsageAccessoryRectangularView(entry: entry)
        default:
            UsageWidgetSmallView(entry: entry, transparent: transparent, ink: ink)
        }
    }
}

// MARK: - Widget

extension View {
    /// Tile background that survives both generations of widget chrome: iOS 17
    /// requires `containerBackground` for full-bleed colour, and iOS 16 never
    /// heard of it. One wrapper keeps the call sites honest on either. Internal,
    /// because the configured widgets share it.
    @ViewBuilder
    func widgetBackground(_ color: Color?) -> some View {
        if #available(iOS 17.0, *) {
            // `Color.clear` rather than no container background at all. On iOS 17 a widget that
            // declares none is rejected from the Home Screen entirely; declaring a clear one is
            // what "transparent" actually means there — the system's own widget material shows
            // the wallpaper through it.
            containerBackground(for: .widget) { color ?? Color.clear }
        } else {
            background(color ?? Color.clear)
        }
    }
}

/// The subscription-quota widget.
struct UsageWidget: Widget {

    /// Reverse-DNS kind, unique among the app's widgets.
    ///
    /// Never changed once shipped: the kind is how the system identifies a PLACED tile, so a
    /// rename silently orphans every widget the user has already put on their home screen.
    private static let kind = "com.usagelimits.widget.usage"

    var body: some WidgetConfiguration {
        // An instance, not `UsageProvider.self`. `StaticConfiguration` takes a provider VALUE;
        // a metatype does not conform to `TimelineProvider`, so the metatype spelling does not
        // compile — and this file had never been compiled anywhere.
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Usage Limits")
        .description("Quota left on your AI subscriptions, at a glance.")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge, .accessoryRectangular,
        ])
    }
}

/// The same tile, with the wallpaper showing through it.
///
/// A separate gallery entry rather than a switch inside one, which is what Android offers.
/// The difference is not a preference: a per-tile option needs a configurable widget, and
/// configuration means `AppIntentConfiguration` — iOS 17, where this app supports 16. Two
/// entries cost the user one extra row in the gallery and work on every version the app runs
/// on; the alternative would be a checkbox that half the installed base could not reach.
struct UsageClearWidget: Widget {

    private static let kind = "com.usagelimits.widget.usage.clear"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageWidgetEntryView(entry: entry, transparent: true)
        }
        .configurationDisplayName("Usage Limits (Clear)")
        .description("The same tile with a transparent background.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// One account as a ring, small enough that several fit where one list tile would.
struct UsageRingWidget: Widget {

    private static let kind = "com.usagelimits.widget.ring"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageRingEntryView(entry: entry)
        }
        .configurationDisplayName("Usage Ring")
        .description("What is left on one subscription, as a ring, with its reset time.")
        .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}

/// Routes the ring widget's two families.
///
/// Separate from `UsageWidgetEntryView` rather than another case in it, because the two widgets
/// support disjoint families and a shared router would silently fall through to the small list
/// view if a family were ever added to one of them.
struct UsageRingEntryView: View {

    @Environment(\.widgetFamily) private var family

    let entry: UsageEntry

    var body: some View {
        switch family {
        case .accessoryCircular:
            UsageAccessoryCircularView(entry: entry)
        default:
            UsageRingWidgetView(entry: entry)
        }
    }
}

@main
struct UsageWidgets: WidgetBundle {
    var body: some Widget {
        #if canImport(AppIntents)
        if #available(iOS 17.0, *) {
            ConfiguredUsageWidget()
            ConfiguredRingWidget(mini: false)
            ConfiguredRingWidget(mini: true)
        }
        #endif
        UsageWidget()
        UsageClearWidget()
        UsageRingWidget()
        PinnedAccountsWidget()
        CreditsWidget()
    }
}
