import SwiftUI
import UsageLimitsKit

/// The ink a tile writes with.
///
/// An OPAQUE tile is the app's near-black panel, so the light palette is the only readable
/// choice and no configuration is needed. A TRANSPARENT tile has no panel — the wallpaper
/// shows through and the widget process cannot see it (and must not sample it) — so the
/// user's configured tone decides, exactly as the Android `WidgetStyle` LIGHT/DARK override
/// does. Restated as values rather than dynamic system colours, for the same reason the
/// palette is numeric: the home-screen rendering must not drift with whatever the host
/// decides to recolor.
struct WidgetInk {
    let primary: Color
    let secondary: Color
    let tertiary: Color
    let track: Color
    /// The quota hue, the stale grey, and the two figure colours that have to be re-pulled for
    /// a light wallpaper (the dark panel's amber and red are unreadable on it).
    let data: Color
    let staleMark: Color
    let warning: Color
    let negativeText: Color
    /// Whether single-colour artwork must be drawn in `primary`: the white provider logos
    /// vanish on a light wallpaper.
    let tintsLogos: Bool

    static let light = WidgetInk(
        primary: UsageColors.ink,
        secondary: UsageColors.muted,
        tertiary: UsageColors.faint,
        track: UsageColors.dataTrack,
        data: UsageColors.data,
        staleMark: UsageColors.staleMark,
        warning: UsageColors.warning,
        negativeText: UsageColors.negativeText,
        tintsLogos: false)

    /// The light palette's counterpart for a transparent tile over a light wallpaper: the
    /// same hues pulled down, mirroring the Android dark-ink constants.
    static let dark = WidgetInk(
        primary: Color(hex: 0x1A1918),
        secondary: Color(hex: 0x5C5A53),
        tertiary: Color(hex: 0x6B6960),
        track: Color(hex: 0x1A1918).opacity(0.18),
        data: Color(hex: 0xB35F42),
        staleMark: Color(hex: 0x6B6960),
        warning: Color(hex: 0x8A5A0A),
        negativeText: Color(hex: 0xB03A31),
        tintsLogos: true)

    /// The bar or arc colour for a severity: the data hue, or the stale grey for a reading that
    /// is no longer to be trusted.
    func bar(_ severity: Severity) -> Color? {
        switch severity {
        case .stale: return staleMark
        case .unknown, .error, .exhausted: return nil
        default: return data
        }
    }

    /// The bar or arc colour for how a presentation paints its fill, on THIS ink. Whether it
    /// paints is the Kit's decision (`WindowPresentation.fillState`); nil is the bare track.
    func fill(_ state: FillState) -> Color? {
        switch state {
        case .data: return data
        case .stale: return staleMark
        case .noFill: return nil
        }
    }

    /// The colour a percentage is written in, on THIS ink — `SeverityPalette.figureColor` is
    /// the dark panel's and reads wrongly on a light wallpaper.
    func figure(_ severity: Severity) -> Color {
        switch SeverityPalette.figureTone(severity) {
        case .ink: return primary
        case .warning: return warning
        case .negative: return negativeText
        case .faint: return tertiary
        }
    }

    /// The tone only matters when the wallpaper is the backdrop; an opaque panel always
    /// pairs with light ink. `darkTone` arrives as a Bool because the tone choice is an
    /// AppIntents type living behind an iOS 17 gate this file does not share.
    static func ink(transparent: Bool, darkTone: Bool) -> WidgetInk {
        transparent && darkTone ? .dark : .light
    }
}
