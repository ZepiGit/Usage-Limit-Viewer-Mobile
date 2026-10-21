import Foundation

/// The configured widget's display outcome, including why a selection is empty.
/// Resolve it once with the inputs and carry it with the entry; views must not reread
/// files while being archived or guess the cause of missing content.
public enum ConfiguredSelectionOutcome: Sendable, Equatable {
    /// The selection resolved against a loaded snapshot.
    case ready
    /// The source snapshot exists and genuinely holds no accounts.
    case noAccounts
    /// The configured account/provider selection matches nothing in a snapshot that does have
    /// accounts. "Edit this widget", not "open the app".
    case missingAccount
    /// The referenced custom layout is gone or unreadable. "Edit this widget", not "open the app".
    case missingPreset
    /// The snapshot file exists but could not be opened — data protection before first unlock
    /// is the expected cause, and the only one unlocking fixes.
    case sourceUnavailable
    /// The snapshot file was read and could not be decoded.
    case corruptSource
}

/// Capture snapshot and preset inputs once per configured timeline request.
/// Every entry uses this generation; a later request adopts new publications so a
/// single timeline cannot mix source generations.
public struct ConfiguredWidgetCapture: Sendable, Equatable {

    /// What the preset resolution did. `notRead` for selections that never consult the store;
    /// reading a preset for those would be work, and treating its absence as data would be wrong.
    public enum PresetResolution: Sendable, Equatable {
        case notRead
        case matched(accountIDs: [String])
        case missing
    }

    public let source: GlanceSnapshotCodec.Load
    public let preset: PresetResolution
    public let scope: GlanceScope
    public let accountID: String?
    public let providerID: String?
    public let transparent: Bool

    public init(source: GlanceSnapshotCodec.Load, preset: PresetResolution, scope: GlanceScope,
        accountID: String?, providerID: String?, transparent: Bool) {
        self.source = source
        self.preset = preset
        self.scope = scope
        self.accountID = accountID
        self.providerID = providerID
        self.transparent = transparent
    }

    /// The selected snapshot at `date`, derived from the captured inputs alone.
    ///
    /// Pure over the capture: the same capture and date always produce the same snapshot, and
    /// no input file is touched here.
    public func selectedSnapshot(at date: Date) -> GlanceSnapshot {
        let customIDs: [String]
        if case .matched(let ids) = preset { customIDs = ids } else { customIDs = [] }
        return source.snapshot.selecting(scope: scope, now: date, accountID: accountID,
            providerID: providerID, customAccountIDs: customIDs)
    }

    /// The outcome this capture renders, decided once at resolution time.
    ///
    /// A missing snapshot file reads as "no accounts yet" — the app has not published anything
    /// yet, and that is the honest message. It is NOT "locked": inferring device lock from a
    /// file's presence was the empty state's original sin. Only an unreadable existing file,
    /// whose expected cause is data protection, claims that.
    public func outcome(at date: Date) -> ConfiguredSelectionOutcome {
        switch source {
        case .empty, .missing: return .noAccounts
        case .unreadable: return .sourceUnavailable
        case .corrupt: return .corruptSource
        case .loaded: break
        }
        if case .missing = preset { return .missingPreset }
        return selectedSnapshot(at: date).accounts.isEmpty ? .missingAccount : .ready
    }

    /// The dates at which this request's presentation changes on its own, with no new data.
    ///
    /// Timelines that only schedule entries across the next hour render an aged verdict for
    /// as long as the timeline lasts: the severity is computed at each entry's date, but only
    /// where an ENTRY exists to compute it. A staleness boundary past the horizon needs its
    /// own entry, drawn from the immutable cache — no reload, no network, just the flip the
    /// cached data itself implies. So does each SELECTED row's reset, not only the lead's.
    public func presentationBoundaries(after date: Date) -> [Date] {
        guard case .loaded = source else { return [] }
        return selectedSnapshot(at: date).presentationBoundaries(after: date)
    }
}
