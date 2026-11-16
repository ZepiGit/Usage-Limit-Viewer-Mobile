// Shared widget selection, formatting, ink, and deep links.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Snapshot lead

/// Use the kit's leading account, headline rows and reset together so the reading
/// and its reset always belong to the same account.
struct WidgetFocus {
    let account: GlanceAccount
    /// The kit's headline for the lead: the five-hour row when it has one, else the longer.
    let row: GlanceRow?

    init?(snapshot: GlanceSnapshot) {
        guard let account = snapshot.accounts.first else { return nil }
        self.account = account
        self.row = snapshot.widgetRow(for: account)
    }
}

extension GlanceSnapshot {
    /// The row a tile shows for one account: the kit's headline for the LEAD, and every other
    /// account's own first row. Stated once — three views spelled it out separately, and the
    /// precedence decides which number a user sees.
    func widgetRow(for account: GlanceAccount) -> GlanceRow? {
        if account.id == accounts.first?.id {
            return headlineShort ?? headlineLong ?? account.rows.first
        }
        return account.rows.first
    }
}

enum QuotaFormatting {

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

/// Builds every time string the tiles show. Everything here is *absolute*.
///
/// The kit offers `Countdown.format` ("2d 4h") and the widget deliberately refuses
/// it. A relative countdown is frozen at the moment WidgetKit last rendered, and it
/// goes wrong in the one direction that matters: the limit can already have reset
/// while the tile still insists "in 20m". A clock time ("Resets 14:05") was true
/// when it was written and stays true whatever the system does next. The same
/// argument forbids "5 min ago" styling — an age silently goes stale — so the data
/// instead carries its own timestamp: "As of 12:40".
/// Internal, not private: the configured widgets in `ConfiguredWidgets.swift` render the
/// same reset line, and a second copy of the wording is a second answer to one question.
///
/// The wording is the app's: the same `Countdown` lines the screens use, with the catalog-backed
/// texts, so a clock time reads the same on the tile and in the overview.
enum GlanceText {

    /// "Resets Tue 09:00", or the boundary state once the reset has passed.
    ///
    /// The cached numbers of a passed reset predate it, so the tile says a refresh is pending
    /// instead of implying a reset is still ahead. The timeline requests fresh data moments
    /// after the boundary, so this state is short-lived.
    static func resetLine(resetAt: Date?, now: Date) -> String? {
        Countdown.resetLine(resetAt: resetAt, now: now, texts: LocalizedCountdownTexts())
    }

    /// "As of 12:40" — the data's own timestamp, or nothing if the cache states none.
    static func asOfLine(updatedAt: Date?, now: Date) -> String? {
        Countdown.asOfLine(updatedAt: updatedAt, now: now, texts: LocalizedCountdownTexts())
    }

    /// "Latest update 12:40" — for an aggregate, because an aggregate max does not mean what
    /// "As of" implies beside one account's numbers: on a multi-account tile the maximum
    /// fetch instant across the selection is the NEWEST account's update, so the header
    /// says that rather than dating every row with it.
    static func latestUpdateLine(updatedAt: Date?, now: Date) -> String? {
        Countdown.latestUpdateLine(updatedAt: updatedAt, now: now, texts: LocalizedCountdownTexts())
    }

    /// "Claude, Messages": the account and the limit a figure belongs to, as VoiceOver reads
    /// them ahead of the number. The limit's own label is data except for the app's three
    /// horizons, which `DisplayText` translates.
    static func subject(_ account: GlanceAccount, _ row: GlanceRow?) -> String {
        guard let row else { return account.title }
        return String(localized: "\(account.title), \(row.displayLabel)")
    }
}

extension GlanceRow {
    /// The row's label in the current language: "5h limit", "Weekly" and "Monthly" of a global
    /// window are the app's words, anything else is the provider's.
    var displayLabel: String {
        DisplayText.windowLabel(label, category: category, scope: scope)
    }
}

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

/// Tap destinations. Lock Screen accessories ignore these; the app registers the scheme in its
/// `CFBundleURLTypes` and answers them in `onOpenURL`. Internal: the configured widgets share
/// the same destinations.
enum DeepLink {
    static let glance = URL(string: "usagelimits://glance")

    /// Opens the app and refreshes there.
    ///
    /// Not a refresh performed inside the widget, and that is a design decision rather than a
    /// shortcut: the extension holds no credentials. Tokens live in the keychain and are read
    /// only by the app process — which is precisely what keeps them out of widget state — so an
    /// extension that fetched would have to be given them. A button that reloads the TIMELINE
    /// without fetching would be worse still: it would redraw the same cached numbers and look
    /// like a refresh that changed nothing.
    static let refresh = URL(string: "usagelimits://refresh")
}

