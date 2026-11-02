import Foundation

/// Renders "how long until this resets".
///
/// In the kit rather than in the SwiftUI layer for the same reason the glance ordering is: it
/// is a decision about what the user is told, it has edge cases worth pinning, and neither
/// platform's rendering code is where a rounding rule should live.
public enum Countdown {

    /// Whole units only, and never more than two: `2d 4h`, `3h 12m`, `8m`.
    ///
    /// Seconds are dropped deliberately. A number read at a glance gains nothing from them, and
    /// a ticking seconds field invites the reader to trust a figure that is only refreshed
    /// every half hour.
    ///
    /// A sub-minute remainder rounds UP to `1m` rather than down to `0m`: zero reads as "it has
    /// reset", which is the one thing that is not yet true.
    ///
    /// The examples throughout are the English `texts`; the arithmetic is the same in any language.
    public static func format(
        until date: Date, from now: Date, texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String {
        // Clamped, not converted. `Int(_: Double)` TRAPS outside Int's range, and this is
        // called on raw parser output from four screens and the widget — so a provider
        // reporting an absurd reset instant crashed the process rather than printing an absurd
        // countdown. Kotlin's `toInt()` saturates, which is also why only this side could.
        let interval = date.timeIntervalSince(now)
        guard interval.isFinite else { return texts.now() }
        // Compared, not clamped to a computed upper bound. The previous bound was
        // `-Double(Int.min) - 1`, meant as Int.max — but 2^63 - 1 is not representable as a
        // Double and rounds back UP to 2^63, which is out of range, so the clamp admitted the
        // one value it existed to stop and `Int(clamped)` trapped on it. `Double(Int.max)` is
        // that same 2^63, so `>=` catches the boundary.
        if interval >= Double(Int.max) { return format(seconds: Int.max, texts: texts) }
        if interval <= Double(Int.min) { return format(seconds: Int.min, texts: texts) }
        return format(seconds: Int(interval), texts: texts)
    }

    public static func format(
        seconds: Int, texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String {
        guard seconds > 0 else { return texts.now() }

        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        let minutes = (seconds % 3_600) / 60

        if days > 0 { return hours > 0 ? texts.daysHours(days: days, hours: hours) : texts.days(days) }
        if hours > 0 {
            return minutes > 0 ? texts.hoursMinutes(hours: hours, minutes: minutes) : texts.hours(hours)
        }
        // "<1m", not "1m". Rounding the last fifty-nine seconds up to a minute invents time
        // the user does not have, on a countdown whose entire job is to say when something
        // comes back — and it disagreed with the Kotlin twin, which has always said "<1m".
        return minutes > 0 ? texts.minutes(minutes) : texts.underOneMinute()
    }

    // MARK: Lines

    // The sentences built around a duration or a clock time. They sit beside the arithmetic so a
    // surface asks for the line instead of concatenating words of its own: word order is the
    // texts' business, and one set of inputs can then assert the wording in every language.

    /// "Resets in 2d 4h". For a surface that re-renders on a clock; a widget cannot, see
    /// `resetLine`.
    public static func resetsInLine(
        until date: Date, from now: Date, texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String {
        texts.resetsIn(duration: format(until: date, from: now, texts: texts))
    }

    /// "Resets Tue 09:00", or the boundary state once the reset has passed; nil without a reset.
    ///
    /// The absolute form, for a surface that cannot tick. The cached numbers of a passed reset
    /// predate it, so the line says a refresh is pending instead of implying a reset still lies
    /// ahead.
    public static func resetLine(
        resetAt: Date?, now: Date, calendar: Calendar = .current,
        texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String? {
        guard let resetAt else { return nil }
        if resetAt <= now { return texts.resetPending() }
        return texts.resetsAt(stamp: absolute(resetAt, now: now, calendar: calendar))
    }

    /// "As of 12:40" — the data's own timestamp, or nil if the cache states none.
    public static func asOfLine(
        updatedAt: Date?, now: Date, calendar: Calendar = .current,
        texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String? {
        guard let updatedAt else { return nil }
        return texts.asOf(stamp: absolute(updatedAt, now: now, calendar: calendar))
    }

    /// "Latest update 12:40" — `asOfLine` for an aggregate, where the instant is the newest
    /// account's fetch and "As of" beside one account's numbers would date every row with it.
    public static func latestUpdateLine(
        updatedAt: Date?, now: Date, calendar: Calendar = .current,
        texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String? {
        guard let updatedAt else { return nil }
        return texts.latestUpdate(stamp: absolute(updatedAt, now: now, calendar: calendar))
    }

    /// "Updated just now" / "Updated 37m ago", for a surface that re-renders on a clock.
    public static func updatedLine(
        secondsAgo: Int, texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String {
        secondsAgo < 60
            ? texts.updatedJustNow()
            : texts.updatedAgo(duration: format(seconds: secondsAgo, texts: texts))
    }

    /// "Weekly renews 3d 2h". `label` is the window's own and is not translated.
    public static func renewsLine(
        label: String, until date: Date, from now: Date,
        texts: any CountdownTexts = EnglishCountdownTexts()
    ) -> String {
        texts.renews(label: label, duration: format(until: date, from: now, texts: texts))
    }

    /// The absolute local time, for a surface that cannot tick.
    ///
    /// A widget recomposes only when its worker updates it, so a relative countdown rendered
    /// half an hour ago is not merely stale — it can be wrong in the one direction that
    /// matters, because the limit may already have reset. An absolute time stays true however
    /// old the render is, so that is what a widget shows.
    ///
    /// The date is omitted when the reset is today, which is the common case and the one where
    /// a date would only add noise.
    public static func absolute(_ date: Date, now: Date, calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = .current
        formatter.dateStyle = calendar.isDate(date, inSameDayAs: now) ? .none : .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
