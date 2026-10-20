import Foundation

/// How low a balance is. The rules are the shared Android/iOS ones: empty is exhausted; with a
/// known total the remaining share goes through the same thresholds as usage; without one the
/// per-account `lowThreshold` decides (below it low, below twice it medium).
public enum CreditSeverity {

    public static func of(_ balance: CreditBalance, lowThreshold: Decimal) -> Severity {
        if balance.unlimited { return .healthy }
        if balance.amount <= 0 { return .exhausted }
        if let percent = balance.remainingPercent { return Severity.from(remainingPercent: percent) }
        if balance.amount < lowThreshold { return .low }
        if balance.amount < lowThreshold * 2 { return .medium }
        return .healthy
    }

    /// What the numbers say, before age is considered. Never fetched and failed is an error;
    /// never fetched and not failed is unknown.
    public static func base(_ account: CreditAccount, _ snapshot: CreditSnapshot?) -> Severity {
        guard let snapshot else { return .unknown }
        if snapshot.balances.isEmpty { return snapshot.status == .failed ? .error : .unknown }
        return snapshot.balances
            .map { of($0, lowThreshold: account.lowThreshold(for: $0.currency)) }
            .max() ?? .unknown
    }

    /// The same staleness rule usage uses: an error stays an error, anything older than the
    /// threshold reads stale rather than keeping the colour of its last number.
    public static func aged(
        base: Severity, fetchedAt: Date?, now: Date, staleAfter: TimeInterval
    ) -> Severity {
        guard let fetchedAt else { return base }
        if base == .error { return .error }
        return now.timeIntervalSince(fetchedAt) >= staleAfter ? .stale : base
    }
}

/// Locale-aware money text, two fraction digits.
public enum CreditFormat {

    private static let minimumUnit: Decimal = Decimal(string: "0.01")!

    /// "$12.34". A positive amount under one cent reads "<$0.01" instead of "$0.00"; negative
    /// (arrears) is allowed and shown as is.
    public static func string(_ amount: Decimal, currency: String, locale: Locale = .current) -> String {
        if amount > 0 && amount < minimumUnit { return "<" + plain(minimumUnit, currency, locale) }
        return plain(amount, currency, locale)
    }

    /// Every balance of an account on one line: "¥110.00 · $5.00".
    public static func summary(_ balances: [CreditBalance], locale: Locale = .current) -> String {
        balances.map { string($0.amount, currency: $0.currency, locale: locale) }.joined(separator: " · ")
    }

    private static func plain(_ amount: Decimal, _ currency: String, _ locale: Locale) -> String {
        amount.formatted(.currency(code: currency).locale(locale).precision(.fractionLength(2)))
    }
}

/// One credit account as the widget draws it. Raw balances travel, not text: the widget formats
/// in its own locale, and ages `baseSeverity` itself at each timeline date.
public struct GlanceCredit: Sendable, Equatable, Identifiable, Codable {
    public let id: String
    public let title: String
    public let providerID: String
    public let balances: [CreditBalance]
    /// Severity as of write time, with staleness.
    public let severity: Severity
    /// Without the staleness judgement.
    public let baseSeverity: Severity
    public let fetchedAt: Date?
    public let statusMessage: String?

    public init(
        id: String, title: String, providerID: String, balances: [CreditBalance],
        severity: Severity, baseSeverity: Severity, fetchedAt: Date?, statusMessage: String?
    ) {
        self.id = id
        self.title = title
        self.providerID = providerID
        self.balances = balances
        self.severity = severity
        self.baseSeverity = baseSeverity
        self.fetchedAt = fetchedAt
        self.statusMessage = statusMessage
    }

    public func severity(at now: Date, staleAfter: TimeInterval) -> Severity {
        CreditSeverity.aged(base: baseSeverity, fetchedAt: fetchedAt, now: now, staleAfter: staleAfter)
    }

    /// The balance whose severity is worst, for the one figure a small tile can show.
    public var headline: CreditBalance? { balances.first }

    public init(entry: CreditEntry, now: Date, staleAfter: TimeInterval) {
        let base = CreditSeverity.base(entry.account, entry.snapshot)
        // Worst balance leads, so the figure shown is the one the colour is about.
        let ordered = (entry.snapshot?.balances ?? []).sorted {
            CreditSeverity.of($0, lowThreshold: entry.account.lowThreshold(for: $0.currency))
                > CreditSeverity.of($1, lowThreshold: entry.account.lowThreshold(for: $1.currency))
        }
        let fetchedAt = entry.snapshot.flatMap { $0.balances.isEmpty ? nil : $0.fetchedAt }
        self.init(
            id: entry.account.id, title: entry.account.label,
            providerID: entry.account.provider.rawValue, balances: ordered,
            severity: CreditSeverity.aged(base: base, fetchedAt: fetchedAt, now: now, staleAfter: staleAfter),
            baseSeverity: base, fetchedAt: fetchedAt, statusMessage: entry.snapshot?.error)
    }
}

public extension GlanceSnapshot {
    /// The credit accounts worst first, aged at `now`. Ties keep the user's order.
    func creditsByUrgency(at now: Date) -> [GlanceCredit] {
        credits.enumerated().sorted { lhs, rhs in
            let l = GlanceModel.urgency(lhs.element.severity(at: now, staleAfter: staleAfter))
            let r = GlanceModel.urgency(rhs.element.severity(at: now, staleAfter: staleAfter))
            return l == r ? lhs.offset < rhs.offset : l < r
        }.map(\.element)
    }
}

public extension GlanceModel {
    /// Credit accounts as the widget file carries them, in the user's order.
    static func buildCredits(
        _ entries: [CreditEntry], now: Date, staleAfter: TimeInterval = Severity.staleAfter
    ) -> [GlanceCredit] {
        entries.map { GlanceCredit(entry: $0, now: now, staleAfter: staleAfter) }
    }
}
