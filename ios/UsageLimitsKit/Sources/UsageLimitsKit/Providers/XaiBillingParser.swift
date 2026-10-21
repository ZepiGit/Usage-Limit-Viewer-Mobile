import Foundation

public enum XaiBillingParser: Sendable {
    private static let creditsWindowID = "xai-credits"
    private static let monthlyWindowID = "xai-monthly"
    private static let onDemandWindowID = "xai-on-demand"
    private static let creditFields = ["creditUsagePercent", "credit_usage_percent"]
    private static let monthlyLimitFields = ["monthlyLimit", "monthly_limit"]
    private static let usedFields = ["used", "includedUsed", "included_used", "totalUsed", "total_used"]
    private static let onDemandCapFields = ["onDemandCap", "on_demand_cap"]
    private static let onDemandUsedFields = ["onDemandUsed", "on_demand_used"]

    // A nominal 30-day month keeps calendar months of different lengths categorised alike.
    private static let billingPeriodSeconds: Int64 = 2_592_000

    // A clock never supplies a missing provider measurement, even at a period boundary.
    public static func parseCredits(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        let body = config(payload)

        // Two spellings of the period: nested `currentPeriod{type,start,end}` in the credit
        // view, and the flat `usagePeriodType/Start/End` trio the unified-billing view carries
        // beside its monthly figures.
        let period = JSONSupport.object(body, "currentPeriod", "current_period")
        let start = date(period, "start") ?? date(body, "usagePeriodStart", "usage_period_start")
        let end = date(period, "end") ?? date(body, "usagePeriodEnd", "usage_period_end")
        let periodType = (JSONSupport.string(period, "type")
            ?? JSONSupport.string(body, "usagePeriodType", "usage_period_type"))?.lowercased()

        // xai-org/grok-build BillingConfig.credit_usage_percent is Option<f64> in 0...100.
        // Period metadata identifies a window, but supplies no default for its percentage.
        let usedPercent = percentage(firstValue(body, creditFields))
        guard firstValue(body, creditFields) != nil || start != nil || end != nil || periodType != nil else {
            return []
        }

        var periodSeconds: Int64?
        if let start, let end {
            let span = end.timeIntervalSince(start)
            if span.isFinite, span > 0 {
                // Exact conversion avoids trapping on an out-of-range upstream timestamp.
                periodSeconds = Int64(exactly: span.rounded(.towardZero))
            }
        }

        // Measured length wins; the type preserves weekly/monthly classification when
        // timestamps are missing or unusable.
        if periodSeconds == nil {
            if periodType?.contains("week") == true {
                periodSeconds = 604_800
            } else if periodType?.contains("month") == true {
                periodSeconds = billingPeriodSeconds
            }
        }

        let segments = (firstValue(body, ["productUsage", "product_usage"]) as? [Any] ?? [])
            .compactMap { entry -> UsageSegment? in
                guard let product = entry as? [String: Any],
                      let name = product["product"] as? String,
                      !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let percent = percentage(firstValue(product, ["usagePercent", "usage_percent"])),
                      percent > 0 else { return nil }
                return UsageSegment(label: name.trimmingCharacters(in: .whitespacesAndNewlines), usedPercent: percent)
            }.sorted { $0.usedPercent > $1.usedPercent }
        let category = WindowCategory.from(periodSeconds: periodSeconds)
        let label: String
        switch category {
        case .weekly:
            label = "Weekly credits"
        case .monthly:
            label = "Monthly credits"
        default:
            label = "Credits"
        }

        return [
            UsageWindow(
                id: creditsWindowID,
                label: label,
                category: category,
                usedPercent: usedPercent,
                periodSeconds: periodSeconds,
                resetAt: end,
                exhausted: isExhausted(usedPercent),
                segments: segments
            )
        ]
    }

    public static func parseBilling(_ payload: [String: Any], now: Date) -> [UsageWindow] {
        let body = config(payload)

        // The unified-billing shape of this view carries the weekly figures too —
        // `creditUsagePercent` with the flat `usagePeriod*` trio — so the weekly row is read
        // from here as well. It costs nothing when both views answer (merge keeps the credit
        // view's copy) and keeps the row when the credit view alone stops answering.
        let weekly = parseCredits(payload, now: now)

        let monthlyLimit = cents(body, monthlyLimitFields)
        // Every name the production shape carries for spend — see the Kotlin twin for why
        // reading `used` alone rendered a 90 %-spent account as untouched.
        let used = cents(body, usedFields)
        let onDemandCap = cents(body, onDemandCapFields)
        // A positive on-demand facility provides an alternative to the included allowance.
        // Exhaustion of either individual pool alone must not block the entire account.
        let billingScope: UsageScope = (onDemandCap ?? 0) > 0 ? .model : .global

        let resetAt = date(body, "billingPeriodEnd", "billing_period_end")

        // Excess spend belongs to the on-demand row. Absent spend stays UNKNOWN: a limit with
        // no spend figure is a window whose percentage the app does not know, not one it has
        // decided is untouched.
        let includedPercent = monthlyLimit.flatMap { limit in
            used.flatMap { percentOf(amount: min($0, limit), total: limit) }
        }

        var windows = weekly
        if firstValue(body, monthlyLimitFields) != nil || firstValue(body, usedFields) != nil {
            windows.append(UsageWindow(
                id: monthlyWindowID,
                label: "Monthly included",
                category: .monthly,
                usedPercent: includedPercent,
                periodSeconds: billingPeriodSeconds,
                resetAt: resetAt,
                exhausted: isExhausted(includedPercent),
                scope: billingScope
            ))
        }

        // A known zero cap disables this facility. An absent or malformed cap supplies no
        // percentage, but reported on-demand fields still identify an unknown window.
        let hasOnDemand = firstValue(body, onDemandCapFields) != nil || firstValue(body, onDemandUsedFields) != nil
        if hasOnDemand, onDemandCap != 0 {
            // Deriving overage needs BOTH a known allowance and known spend; with either
            // missing the row is shown with an unknown percentage rather than omitted.
            let onDemandUsed = firstValue(body, onDemandUsedFields) != nil
                ? cents(body, onDemandUsedFields)
                : monthlyLimit.flatMap { limit in used.map { max(0, $0 - limit) } }
            let onDemandPercent = onDemandCap.flatMap { cap in
                onDemandUsed.flatMap { percentOf(amount: $0, total: cap) }
            }

            windows.append(
                UsageWindow(
                    id: onDemandWindowID,
                    label: "On-demand",
                    category: .monthly,
                    usedPercent: onDemandPercent,
                    periodSeconds: billingPeriodSeconds,
                    resetAt: resetAt,
                    exhausted: isExhausted(onDemandPercent),
                    scope: .model
                )
            )
        }

        return merge([], windows)
    }

    public static func merge(
        _ credits: [UsageWindow],
        _ billing: [UsageWindow]
    ) -> [UsageWindow] {
        // Preserve endpoint order and direct measurements, but let a measured fallback
        // replace an unknown duplicate from the credit endpoint.
        var windows = [UsageWindow]()
        var indices = [String: Int]()
        for window in credits + billing {
            if let index = indices[window.id] {
                if windows[index].usedPercent.flatMap({ percentage($0) }) == nil,
                   window.usedPercent.flatMap({ percentage($0) }) != nil {
                    windows[index] = window
                }
            } else {
                indices[window.id] = windows.count
                windows.append(window)
            }
        }
        // BillingConfig prefers measured credit usage over the deprecated monthly fields.
        // Unknown credit still needs that fallback; on-demand is a separate optional pool.
        if windows.contains(where: { $0.id == creditsWindowID && $0.remainingPercent != nil }) {
            windows.removeAll { $0.id == monthlyWindowID }
        }
        // Both representations describe one allowance, so they must agree on whether it is the
        // only pool. A positive cap (the monthly window's model scope) or a measured on-demand
        // window (a percentage needs a positive cap) proves an alternative reserve. Without
        // that evidence the credit view stays global: an unknown on-demand row proves nothing.
        let all = credits + billing
        let reserve = all.contains { $0.id == monthlyWindowID && $0.scope == .model }
            || all.contains { $0.id == onDemandWindowID && $0.remainingPercent != nil }
        guard reserve else { return windows }
        return windows.map { window in
            guard window.id == creditsWindowID else { return window }
            return UsageWindow(
                id: window.id, label: window.label, category: window.category,
                usedPercent: window.usedPercent, periodSeconds: window.periodSeconds,
                resetAt: window.resetAt, exhausted: window.exhausted, group: window.group,
                scope: .model, segments: window.segments)
        }
    }

    /// Missing and explicit null remain unknown; malformed reported numbers require a warning.
    public static func hasInvalidUsage(_ payload: [String: Any]) -> Bool {
        let body = config(payload)
        let credit = firstValue(body, creditFields)
        if let credit, !(credit is NSNull), percentage(credit) == nil { return true }
        for fields in [monthlyLimitFields, usedFields, onDemandCapFields, onDemandUsedFields] {
            let value = firstValue(body, fields)
            if !isUnknown(value), cents(body, fields) == nil { return true }
        }
        return false
    }

    private static func config(_ payload: [String: Any]) -> [String: Any] {
        // Both endpoints wrap their bodies. Flat legacy responses remain valid, but
        // reading only the root silently loses every row from successful HTTP responses.
        JSONSupport.object(payload, "config") ?? payload
    }

    private static func cents(_ source: [String: Any], _ names: [String]) -> Double? {
        // Money may be bare or wrapped in {"val": ...}. Ratios use cents throughout,
        // so no currency conversion or display-unit assumption is necessary.
        let raw = firstValue(source, names)
        let value: Double?
        if let wrapped = raw as? [String: Any] {
            // The provider's Cent.val has serde(default): only a present empty cent object
            // proves zero. A missing field, null, or malformed object does not.
            value = wrapped.isEmpty ? 0 : number(wrapped["val"])
        } else {
            value = number(raw)
        }
        return value.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
    }

    private static func firstValue(_ source: [String: Any], _ names: [String]) -> Any? {
        for name in names {
            if let value = source[name] { return value }
        }
        return nil
    }

    private static func number(_ value: Any?) -> Double? {
        guard let value else { return nil }
        return JSONSupport.double(["value": value], "value")
    }

    private static func percentage(_ value: Any?) -> Double? {
        number(value).flatMap { (0...100).contains($0) ? $0 : nil }
    }

    private static func isUnknown(_ value: Any?) -> Bool {
        guard let value else { return true }
        if value is NSNull { return true }
        return (value as? [String: Any])?["val"] is NSNull
    }

    private static func date(
        _ source: [String: Any]?,
        _ name: String,
        _ alternate: String? = nil
    ) -> Date? {
        let alternateName = alternate ?? name
        return JSONSupport.date(JSONSupport.string(source, name, alternateName))
            ?? JSONSupport.date(epoch: JSONSupport.int64(source, name, alternateName))
    }

    private static func percentOf(amount: Double, total: Double) -> Double? {
        // Guard the denominator and the result: even valid finite amounts can overflow
        // during percentage calculation, and non-finite bars have no useful meaning.
        guard amount.isFinite, amount >= 0, total.isFinite, total > 0 else {
            return nil
        }
        // Valid spend over a known cap exhausts that quota; clamp the amounts before division
        // so valid finite values cannot overflow into a fabricated or unknown percentage.
        let percentage = min(amount, total) / total * 100
        return percentage.isFinite ? percentage : nil
    }

    private static func isExhausted(_ usedPercent: Double?) -> Bool {
        guard let usedPercent else {
            return false
        }
        return usedPercent >= 100
    }
}
