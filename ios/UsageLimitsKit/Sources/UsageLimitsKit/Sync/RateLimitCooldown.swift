import Foundation

enum RateLimitCooldown {
    private static let maximumWait: TimeInterval = 21_600

    static func deadline(from time: Date, retryAfter: TimeInterval?) -> TimeInterval {
        time.timeIntervalSince1970 + min(maximumWait, max(900, retryAfter ?? 0))
    }

    static func shouldSkip(automatic: Bool, until deadline: TimeInterval?, now: Date) -> Bool {
        guard automatic, let deadline else { return false }
        let remaining = deadline - now.timeIntervalSince1970
        return remaining > 0 && remaining <= maximumWait
    }
}
