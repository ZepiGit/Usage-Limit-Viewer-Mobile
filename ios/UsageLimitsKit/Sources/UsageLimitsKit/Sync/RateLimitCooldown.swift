import Foundation

enum RateLimitCooldown {
    static func deadline(from time: Date, retryAfter: TimeInterval?) -> TimeInterval {
        time.timeIntervalSince1970 + min(21_600, max(900, retryAfter ?? 0))
    }

    static func shouldSkip(automatic: Bool, until deadline: TimeInterval?, now: Date) -> Bool {
        guard automatic, let deadline else { return false }
        return now.timeIntervalSince1970 < deadline
    }
}
