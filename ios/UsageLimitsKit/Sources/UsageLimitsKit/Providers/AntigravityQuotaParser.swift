import Foundation

/// Preserves the server's shared-bucket grouping instead of duplicating limits per model.
public enum AntigravityQuotaParser: Sendable {
    struct Row {
        let window: UsageWindow
        var isPlaceholder = false
    }

    static func parseRows(_ payload: [String: Any]) -> [Row] {
        var windows: [Row] = []

        for (groupIndex, element) in JSONSupport.array(payload, "groups", "quotaGroups", "quota_groups").enumerated() {
            guard let group = element as? [String: Any] else { continue }
            windows.append(contentsOf: windowsFor(group, groupIndex: groupIndex))
        }

        return windows
    }

    private static func windowsFor(
        _ group: [String: Any],
        groupIndex: Int
    ) -> [Row] {
        let groupName = JSONSupport.string(group, "display_name", "displayName")
        // Preserve source indices so malformed siblings do not change fallback IDs.
        let groupSlug = slug(groupName ?? "group-\(groupIndex)")

        let bucketKeys = ["buckets", "quotaBuckets", "quota_buckets"]
        let hasBucketKey = bucketKeys.contains { group[$0] != nil }
        let bucketArray = JSONSupport.field(group, bucketKeys) as? [Any]
        let hasQuota = ["remainingFraction", "remaining_fraction", "resetTime", "reset_time", "window"]
            .contains { group[$0] != nil }
        let buckets: [Any] = bucketArray ?? (hasQuota ? [group] : [])
        let windows = buckets.enumerated().compactMap {
            bucketIndex, element -> UsageWindow? in
            guard let bucket = element as? [String: Any] else { return nil }
            return toWindow(
                bucket,
                groupName: groupName,
                groupSlug: groupSlug,
                bucketIndex: bucketIndex
            )
        }

        if windows.isEmpty, !hasBucketKey, !hasQuota, let groupName {
            // A named pool must stay visible even when its bucket schema is unfamiliar.
            return [Row(window: UsageWindow(id: "\(groupSlug)-unknown", label: groupName,
                                category: classify(nil).0, usedPercent: nil, periodSeconds: nil,
                                resetAt: nil, exhausted: false, group: groupName, scope: .model), isPlaceholder: true)]
        }

        // Stable sorting preserves provider order for the known windows and equal labels.
        return windows.sorted { lhs, rhs in
            let leftRank = rank(lhs.category)
            let rightRank = rank(rhs.category)

            if leftRank != rightRank {
                return leftRank < rightRank
            }
            if leftRank == 2 {
                return lhs.label.lowercased() < rhs.label.lowercased()
            }
            return false
        }.map { Row(window: $0) }
    }

    private static func toWindow(
        _ bucket: [String: Any],
        groupName: String?,
        groupSlug: String,
        bucketIndex: Int
    ) -> UsageWindow? {
        let resetAt = JSONSupport.date(JSONSupport.string(bucket, "reset_time", "resetTime"))
        // Google's JSON omits a zero fraction: a spent, enabled bucket arrives with resetTime only.
        let fraction = JSONSupport.double(bucket, "remaining_fraction", "remainingFraction")
            ?? (JSONSupport.field(bucket, ["remaining_fraction", "remainingFraction"]) == nil
                && resetAt != nil && JSONSupport.bool(bucket, "disabled") != true ? 0 : nil)
        let remaining = fraction.flatMap { $0.isFinite && (0...1).contains($0) ? $0 : nil }
        let window = JSONSupport.string(bucket, "window")
        let (category, periodSeconds) = classify(window)
        let id = JSONSupport.string(bucket, "bucket_id", "bucketId")
            ?? "\(groupSlug)-\(window.map { slug($0) } ?? String(bucketIndex))"

        return UsageWindow(
            id: id,
            label: JSONSupport.string(bucket, "display_name", "displayName") ?? id,
            category: category,
            // The provider reports the fraction remaining; the model stores percent consumed.
            usedPercent: remaining.map { (1.0 - $0) * 100.0 },
            periodSeconds: periodSeconds,
            resetAt: resetAt,
            exhausted: remaining == 0,
            group: groupName,
            scope: .model
        )
    }

    private static func classify(_ window: String?) -> (WindowCategory, Int64?) {
        // These are free-form provider names; unknown windows remain visible, unclassified.
        switch window?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "5h", "five-hour", "five_hour":
            return (.fiveHour, 18_000)
        case "weekly", "week":
            return (.weekly, 604_800)
        default:
            return (.other, nil)
        }
    }

    private static func rank(_ category: WindowCategory) -> Int {
        switch category {
        case .fiveHour:
            return 0
        case .weekly:
            return 1
        default:
            return 2
        }
    }

    private static func slug(_ value: String) -> String {
        let result = value.lowercased()
            .replacingOccurrences(
                of: "[^a-z0-9]+",
                with: "-",
                options: .regularExpression
            )
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))

        return result.isEmpty ? "quota" : result
    }
}
