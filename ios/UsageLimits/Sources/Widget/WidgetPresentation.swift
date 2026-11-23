// Shared widget selection, formatting, and deep links.

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Snapshot lead

/// Use the kit's leading account, summary rows and reset together so the reading
/// and its reset always belong to the same account.
struct WidgetFocus {
    let account: GlanceAccount
    /// The kit's summary for the lead: the five-hour row when it has one, else the longer.
    let row: GlanceRow?

    init?(snapshot: GlanceSnapshot) {
        guard let account = snapshot.accounts.first else { return nil }
        self.account = account
        self.row = snapshot.widgetRow(for: account)
    }
}

extension GlanceSnapshot {
    /// The row a tile shows for one account: the kit's summary for the LEAD, and every other
    /// account's own first row. Stated once — three views spelled it out separately, and the
    /// precedence decides which number a user sees.
    func widgetRow(for account: GlanceAccount) -> GlanceRow? {
        if account.id == accounts.first?.id {
            return summaryLineShort ?? summaryLineLong ?? account.rows.first
        }
        return account.rows.first
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

/// Non-pinned rings omit the visible side line, not the window, validity or reset from speech.
enum WidgetRingSpeech {
    static func label(account: GlanceAccount, row: GlanceRow?, presentation: WindowPresentation,
                      date: Date, staleAfter: TimeInterval, resetAt: Date? = nil) -> String {
        var parts = [presentation.hasFigure
            ? ConfiguredRingText.spoken(account, presentation: presentation, texts: LocalizedPercentTexts())
            : String(localized: "\(GlanceText.subject(account, row)): remaining unknown")]
        if !presentation.hasFigure, let identity = account.subtitle { parts.append(identity) }
        if let row { parts.append(row.displayLabel) }
        if account.connectionStatus == .reconnectRequired { parts.append(String(localized: "Reconnect")) }
        if let message = account.statusMessage { parts.append(DisplayText.stored(message)) }
        let severity = account.displaySeverity(of: row, at: date, staleAfter: staleAfter)
        if severity != .healthy { parts.append(SeverityPalette.label(severity)) }
        if let reset = GlanceText.resetLine(resetAt: resetAt ?? row?.resetAt, now: date) { parts.append(reset) }
        if let stamp = GlanceText.asOfLine(updatedAt: account.fetchedAt, now: date) { parts.append(stamp) }
        return parts.joined(separator: ", ")
    }
}

extension GlanceRow {
    /// The row's label in the current language: "5h limit", "Weekly" and "Monthly" of a global
    /// window are the app's words, anything else is the provider's.
    var displayLabel: String {
        DisplayText.windowLabel(label, category: category, scope: scope)
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

