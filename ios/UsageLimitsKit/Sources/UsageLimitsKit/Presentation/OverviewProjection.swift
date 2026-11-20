import Foundation

public enum OverviewFilter: String, CaseIterable, Sendable {
    case all, needsAttention, resetsToday
}

public struct OverviewHeader: Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        case attention, room, resetOnly, none
    }

    public let kind: Kind
    public let resetAt: Date?
}

public struct OverviewProjection: Sendable {
    public struct Entry: Identifiable, Sendable {
        public let account: GlanceAccount
        public let headlineWindowID: String?
        public var id: String { account.id }
    }

    public let accounts: [Entry]
    public let attentionCount: Int
    public let header: OverviewHeader
}

public extension GlanceSnapshot {
    func overview(filter: OverviewFilter, at now: Date, in zone: TimeZone) -> OverviewProjection {
        let dayEnd = nextLocalDay(after: now, in: zone)
        var attentionCount = 0
        var everyoneHasRoom = !accounts.isEmpty
        var nextReset: Date?
        var visible: [OverviewProjection.Entry] = []
        visible.reserveCapacity(accounts.count)

        for account in overviewOrderedAccounts(at: now) {
            let expired = account.connectionStatus == .reconnectRequired
            if expired { attentionCount += 1 }
            let headline = account.representative(at: now)
            if everyoneHasRoom {
                everyoneHasRoom = !expired && headline?.severity != .exhausted
                    && (headline?.remainingPercent ?? 0) > 20
                    && account.readingValidity(of: headline, at: now, staleAfter: staleAfter) == .current
            }
            var resetsToday = false
            for reset in account.resetDates where reset > now {
                if reset < dayEnd { resetsToday = true }
                if account.connectionStatus == .connected && (nextReset.map { reset < $0 } ?? true) {
                    nextReset = reset
                }
            }
            if filter == .all || (filter == .needsAttention && expired) || (filter == .resetsToday && resetsToday) {
                visible.append(OverviewProjection.Entry(account: account, headlineWindowID: headline?.sourceWindowID))
            }
        }
        let kind: OverviewHeader.Kind = if attentionCount > 0 { .attention }
            else if everyoneHasRoom { .room }
            else if nextReset != nil { .resetOnly }
            else { .none }
        return OverviewProjection(accounts: visible, attentionCount: attentionCount,
            header: OverviewHeader(kind: kind, resetAt: nextReset))
    }

    func nextOverviewUpdate(after now: Date, in zone: TimeZone) -> Date {
        let dayEnd = nextLocalDay(after: now, in: zone)
        return presentationBoundaries(after: now).first.map { min($0, dayEnd) } ?? dayEnd
    }
}

private func nextLocalDay(after now: Date, in zone: TimeZone) -> Date {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    // A calendar day's end is the first valid instant of the next day, including skipped midnight.
    return calendar.dateInterval(of: .day, for: now)?.end ?? .distantFuture
}
