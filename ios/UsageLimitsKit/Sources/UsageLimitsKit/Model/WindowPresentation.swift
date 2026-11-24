import Foundation

/// Whether a reading can be trusted as current.
///
/// `stale` is a reading too old to trust, `resetDue` one whose window has already ended, `error`
/// one the account failed to deliver. They are separate because they draw differently: the first
/// two keep their figure under a grey fill, the last draws no fill at all.
public enum ReadingValidity: Sendable, Equatable {
    case current
    case stale
    case resetDue
    case error

    /// The validity an account's aged severity gives every reading it carries.
    ///
    /// Only `stale` and `error` are verdicts about the reading itself; any other severity speaks
    /// about the quota, so the reading is current. Kept here, not in the account screen's view,
    /// so the mapping is covered by the Linux tests.
    public static func aged(_ severity: Severity) -> ReadingValidity {
        switch severity {
        case .stale: return .stale
        case .error: return .error
        case .healthy, .medium, .low, .limited, .exhausted, .unknown: return .current
        }
    }
}

/// Whether and how a fill is painted; the colour belongs to each surface's own palette.
///
/// Not `.none`: that would read as `Optional.none` at a call site holding an optional state.
public enum FillState: Sendable, Equatable {
    case data
    case stale
    case noFill
}

/// Everything a surface needs to present one usage window, with no strings and no colours.
///
/// Every surface presents a usage window through this one value, never through its own copy of the
/// rules, so the figure, the fill and the exhausted state cannot drift apart between surfaces.
/// Words, the percent sign and palettes stay with each surface.
public struct WindowPresentation: Equatable, Sendable {
    /// The figure the window shows in `side`; `PercentToken.unknown` when there is none.
    public let token: String
    /// The mode the figure counts, so a surface can pick its word without re-reading the setting.
    public let side: PercentDisplay
    /// 0.0 to 1.0, or nil for a bare track: a figure that is missing or out of range never fills.
    public let fill: Double?
    public let fillState: FillState
    /// The provider's verdict from the raw severity, never an aged or display severity.
    public let exhausted: Bool

    public var hasFigure: Bool { token != PercentToken.unknown }

    /// Presents a window from its `remaining` figure and its raw `severity`.
    ///
    /// `severity` is the raw one, never an aged or display severity, because `exhausted` is the
    /// provider's verdict. The fill is `UsageWindow.fillFraction` kept only when finite and within
    /// 0 to 1: a missing or out-of-range figure never fills, matching the dash it reads as.
    public init(remaining: Double?, severity: Severity, validity: ReadingValidity, mode: PercentDisplay) {
        let exhausted = severity == .exhausted
        let fraction = UsageWindow.fillFraction(remaining, exhausted: exhausted, mode: mode)
        let fill: Double?
        if let fraction, fraction.isFinite, (0...1).contains(fraction) {
            fill = fraction
        } else {
            fill = nil
        }
        self.token = UsageWindow.percentToken(remaining, exhausted: exhausted, mode: mode)
        self.side = mode
        self.fill = fill
        self.fillState = Self.fillState(validity: validity, severity: severity, fill: fill, mode: mode)
        self.exhausted = exhausted
    }

    private init(token: String, side: PercentDisplay, fill: Double?, fillState: FillState, exhausted: Bool) {
        self.token = token
        self.side = side
        self.fill = fill
        self.fillState = fillState
        self.exhausted = exhausted
    }

    /// What a surface shows for a window that is not there: a dash and no fill, never exhausted.
    public static func absent(_ mode: PercentDisplay) -> WindowPresentation {
        WindowPresentation(
            token: PercentToken.unknown, side: mode, fill: nil, fillState: .noFill, exhausted: false)
    }

    /// How the fill of a window with this `severity` and `fill` is painted, checked in this order.
    ///
    /// Validity outranks severity: a reading that is stale or past its reset is grey whatever it
    /// says, and a failed one draws nothing. Only a current reading lets severity decide, where an
    /// exhausted window has no fill ink except in USED, where it is the full bar and must not sit
    /// bare beside its own "100" (`PercentDisplay.usedFillOverridesMissingInk`).
    public static func fillState(
        validity: ReadingValidity, severity: Severity, fill: Double?, mode: PercentDisplay
    ) -> FillState {
        switch validity {
        case .stale, .resetDue:
            return .stale
        case .error:
            return .noFill
        case .current:
            switch severity {
            case .stale: return .stale
            case .error, .unknown: return .noFill
            case .exhausted: return mode.usedFillOverridesMissingInk(fill) ? .data : .noFill
            case .healthy, .medium, .low, .limited: return .data
            }
        }
    }
}

public extension UsageWindow {
    /// `WindowPresentation` for this whole window.
    ///
    /// Reads the raw `severity`, not the stored `exhausted` flag the instance `percentToken` and
    /// `fillFraction` keep: the two differ only for a zero remainder without the flag, where both
    /// modes give the same token and fill, so the module and those wrappers never disagree.
    func presentation(validity: ReadingValidity, mode: PercentDisplay) -> WindowPresentation {
        WindowPresentation(remaining: remainingPercent, severity: severity, validity: validity, mode: mode)
    }
}
