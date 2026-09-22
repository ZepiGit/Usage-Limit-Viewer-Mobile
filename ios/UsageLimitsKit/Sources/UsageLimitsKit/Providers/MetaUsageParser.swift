import Foundation

/// Normalises the subscription/quota document returned by Meta Muse's `muse-code/key` endpoint.
///
/// The endpoint is primarily a key-minting call and its stable response contains the account and
/// subscription tier. Newer deployments also include quota objects, but those have appeared in
/// several spellings (`limits`, `quotas`, `usage`, and flat daily/weekly fields). This parser keeps
/// the stable fields useful and promotes every numeric quota it can identify without treating a
/// missing quota as zero.
public enum MetaUsageParser {

    public struct Identity: Sendable, Equatable {
        public let externalAccountID: String?
        public let email: String?
        public let displayName: String?
        public let plan: String?

        public init(
            externalAccountID: String?,
            email: String?,
            displayName: String?,
            plan: String?
        ) {
            self.externalAccountID = externalAccountID
            self.email = email
            self.displayName = displayName
            self.plan = plan
        }
    }

    /// Parses all quota-shaped values in a Meta response.
    public static func parse(_ payload: [String: Any], now _: Date = Date()) -> [UsageWindow] {
        var windows: [UsageWindow] = []
        var seen = Set<String>()

        // Array forms are the least ambiguous: each entry is one quota bucket.
        for key in ["limits", "quotas", "windows", "rate_limits", "rateLimits"] {
            for (index, value) in JSONSupport.array(payload, key).enumerated() {
                guard let object = value as? [String: Any],
                      let window = makeWindow(object, fallbackID: "meta-\(key)-\(index)")
                else { continue }
                append(window, to: &windows, seen: &seen)
            }
        }

        // CPAMC's current response nests these buckets under `subs_usage`. Keep stable ids so
        // Android, iOS and widget caches identify the same rolling and weekly rows.
        let subscriptionContainer = JSONSupport.object(
            payload, "subs_usage", "subsUsage", "subscription_usage", "subscriptionUsage")
        let usage = subscriptionContainer.flatMap {
            JSONSupport.object($0, "subscription", "subscription_usage") ?? $0
        }
        if let usage {
            let rolling = JSONSupport.object(usage, "window")
            let label = rollingLabel(rolling)
            if let window = makeWindow(
                rolling, fallbackID: "window", fallbackLabel: label, fallbackPeriod: nil,
                allowUnknown: true) {
                append(window, to: &windows, seen: &seen)
            }
            let weekly = JSONSupport.object(usage, "weekly")
            if let window = makeWindow(
                weekly, fallbackID: "weekly", fallbackLabel: "Weekly", fallbackPeriod: 604_800,
                allowUnknown: true) {
                append(window, to: &windows, seen: &seen)
            }
        } else {
            // A valid key response can omit subscription usage while the account is being
            // provisioned. Keep two explicit unknown meters so callers can distinguish that
            // observation from a transport failure or a fabricated zero-usage response.
            if let window = makeWindow(
                nil, fallbackID: "window", fallbackLabel: "Rolling window", fallbackPeriod: nil,
                allowUnknown: true) {
                append(window, to: &windows, seen: &seen)
            }
            if let window = makeWindow(
                nil, fallbackID: "weekly", fallbackLabel: "Weekly", fallbackPeriod: 604_800,
                allowUnknown: true) {
                append(window, to: &windows, seen: &seen)
            }
        }

        // Older or transitional responses put a quota map under `quota` or `usage`, keyed by
        // model/window name. These are fallback shapes only; the exact subscription shape above
        // wins and supplies stable ids.
        for key in ["quota", "usage", "subscription", "subscription_quota"] {
            guard let object = JSONSupport.object(payload, key) else { continue }
            collectMap(object, prefix: "meta-\(slug(key))", into: &windows, seen: &seen)
        }

        // Flat daily/weekly fields are accepted as a final fallback. We only create a row when
        // one of the fields actually carries a numeric value.
        for (id, label, aliases, period) in [
            ("meta-daily", "Daily", ["daily", "daily_quota", "dailyQuota"], Int64(86_400)),
            ("meta-weekly", "Weekly", ["weekly", "weekly_quota", "weeklyQuota"], Int64(604_800)),
        ] as [(String, String, [String], Int64)] {
            for alias in aliases {
                if let object = JSONSupport.object(payload, alias),
                   let window = makeWindow(object, fallbackID: id, fallbackLabel: label,
                                           fallbackPeriod: period) {
                    append(window, to: &windows, seen: &seen)
                    break
                }
            }
        }

        return windows
    }

    /// Stable account and subscription fields from the key response.
    public static func identity(in payload: [String: Any]) -> Identity {
        let email = JSONSupport.string(
            payload, "user_email", "userEmail", "email", "user_email_address")
        let displayName = JSONSupport.string(
            payload, "user_full_name", "userFullName", "display_name", "displayName", "name")
        let external = JSONSupport.string(
            payload, "user_id", "userId", "account_id", "accountId", "subject", "sub")
            ?? email
        return Identity(
            externalAccountID: external,
            email: email,
            displayName: displayName,
            plan: parsePlan(payload))
    }

    /// Reads the subscription tier without assuming a fixed set of future tier ids.
    public static func parsePlan(_ payload: [String: Any]) -> String? {
        let direct = JSONSupport.string(
            payload, "subs_tier_name", "subsTierName", "plan_name", "planName", "plan", "tier")
        if let direct, !direct.isEmpty { return direct }
        if let usage = JSONSupport.object(
            payload, "subs_usage", "subsUsage", "subscription_usage", "subscriptionUsage"),
           let nested = JSONSupport.object(usage, "subscription", "subscription_usage"),
           let tier = JSONSupport.string(nested, "tier", "plan", "plan_name", "planName"),
           !tier.isEmpty {
            return tier
        }
        if let usage = JSONSupport.object(payload, "subs_usage", "subsUsage"),
           let nested = JSONSupport.string(usage, "tier"), !nested.isEmpty {
            return nested
        }
        if let tierID = JSONSupport.string(payload, "subs_tier_id", "subsTierId", "tier_id", "tierId"),
           !tierID.isEmpty {
            return tierID
        }
        if JSONSupport.bool(payload, "is_subs_active", "isSubsActive", "active") == true {
            return "Active subscription"
        }
        return nil
    }

    private static func collectMap(
        _ object: [String: Any],
        prefix: String,
        into windows: inout [UsageWindow],
        seen: inout Set<String>
    ) {
        // A quota object itself is a valid window; only recurse when it is a map of buckets.
        if let direct = makeWindow(object, fallbackID: prefix) {
            append(direct, to: &windows, seen: &seen)
            return
        }
        for key in object.keys.sorted() {
            let childKey = slug(key)
            guard let child = object[key] as? [String: Any],
                  !childKey.isEmpty,
                  let window = makeWindow(
                    child,
                    fallbackID: "\(prefix)-\(childKey)",
                    fallbackLabel: childKey == "weekly" ? "Weekly" : humanize(key),
                    fallbackPeriod: childKey == "weekly" ? 604_800 : nil)
            else { continue }
            append(window, to: &windows, seen: &seen)
        }
    }

    private static func makeWindow(
        _ object: [String: Any]?,
        fallbackID: String,
        fallbackLabel: String? = nil,
        fallbackPeriod: Int64? = nil,
        allowUnknown: Bool = false
    ) -> UsageWindow? {
        let used: Double?
        if let value = JSONSupport.double(
            object, "used_percent", "usedPercent", "utilization", "percent_used", "percentUsed") {
            used = normalisePercent(value)
        } else if let value = JSONSupport.double(object, "remaining_percent", "remainingPercent") {
            used = normalisePercent(100 - value)
        } else if let value = JSONSupport.double(object, "percent") {
            // `percent` is used as remaining by quota responses; the explicit consumed keys
            // above win when both are present.
            let remaining = value >= 0 && value <= 100 ? value : nil
            used = remaining.map { 100 - $0 }
        } else if let limit = JSONSupport.double(object, "limit", "total", "max"), limit > 0,
                  let remaining = JSONSupport.double(object, "remaining", "available") {
            used = normalisePercent((limit - remaining) / limit * 100)
        } else if let usedValue = JSONSupport.double(object, "used", "consumed") ,
                  let limit = JSONSupport.double(object, "limit", "total", "max"), limit > 0 {
            used = normalisePercent(usedValue / limit * 100)
        } else {
            used = nil
        }
        guard used != nil || allowUnknown else { return nil }

        let rawID = JSONSupport.string(object, "id", "key", "name", "window", "type")
        let id = slug(rawID ?? fallbackID)
        guard !id.isEmpty else { return nil }
        let label = JSONSupport.string(object, "label", "display_name", "displayName", "name")
            ?? fallbackLabel
            ?? humanize(rawID ?? fallbackID)
        var period = JSONSupport.int64(
            object, "period_seconds", "periodSeconds", "window_seconds", "windowSeconds")
            ?? fallbackPeriod
        if period == nil,
           let minutes = JSONSupport.int64(
                object, "window_duration_mins", "windowDurationMins", "duration_mins", "durationMins") {
            period = minutes.multipliedReportingOverflow(by: 60).overflow ? nil : minutes * 60
        }
        let reset = dateValue(
            object, "reset_at", "resetAt", "resets_at", "resetsAt", "reset_time", "resetTime")
        return UsageWindow(
            id: id.hasPrefix("meta-") ? id : "meta-\(id)",
            label: label,
            category: WindowCategory.from(periodSeconds: period),
            usedPercent: used,
            periodSeconds: period,
            resetAt: reset,
            exhausted: used.map { $0 >= 100 } ?? false)
    }

    private static func normalisePercent(_ value: Double) -> Double? {
        guard value.isFinite else { return nil }
        return min(max(value, 0), 100)
    }

    private static func append(
        _ window: UsageWindow,
        to windows: inout [UsageWindow],
        seen: inout Set<String>
    ) {
        guard seen.insert(window.id).inserted else { return }
        windows.append(window)
    }

    private static func slug(_ value: String) -> String {
        let mapped = value.lowercased().map { character -> Character in
            character.isASCII && (character.isLetter || character.isNumber) ? character : "-"
        }
        return String(mapped).split(separator: "-", omittingEmptySubsequences: true)
            .joined(separator: "-")
    }

    private static func humanize(_ value: String) -> String {
        value.split(whereSeparator: { $0 == "_" || $0 == "-" })
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }

    private static func rollingLabel(_ object: [String: Any]?) -> String {
        guard let minutes = JSONSupport.int64(
            object, "window_duration_mins", "windowDurationMins"), minutes > 0 else {
            return "Rolling window"
        }
        return minutes % 60 == 0 ? "\(minutes / 60)h limit" : "\(minutes) min limit"
    }

    private static func dateValue(_ source: [String: Any]?, _ names: String...) -> Date? {
        for name in names {
            if let raw = JSONSupport.string(source, name), let parsed = JSONSupport.date(raw) {
                return parsed
            }
            if let epoch = JSONSupport.int64(source, name), let parsed = JSONSupport.date(epoch: epoch) {
                return parsed
            }
        }
        return nil
    }
}
