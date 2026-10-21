import Foundation

/// The providers this app can monitor.
///
/// `id` is persisted, so these string values are part of the on-disk contract and must not be
/// renamed without a migration.
public enum ProviderID: String, CaseIterable, Sendable, Codable {
    case codex
    case claude
    case antigravity
    case xai
    case kimi
    case devin
    case meta
    case zai
    case opencode

    public var displayName: String {
        switch self {
        case .codex: return "OpenAI Codex"
        case .claude: return "Claude"
        case .antigravity: return "Antigravity"
        case .xai: return "Grok"
        case .kimi: return "Kimi"
        case .devin: return "Devin"
        case .meta: return "Meta Muse"
        case .zai: return "Z.ai GLM"
        case .opencode: return "OpenCode Go"
        }
    }
}

/// A logged-in account, normalised across providers.
///
/// Identity is `provider` + `externalAccountID`, never the address alone: providers let the
/// same address back several accounts, and an address can change while the account does not.
///
/// Carries no token. Credentials live only in the Keychain, addressed by
/// `credentialReference` — the same split the Android app uses, and the reason the widget
/// can read this type freely.
public struct ProviderAccount: Sendable, Codable, Identifiable, Equatable {
    public let id: String
    public let provider: ProviderID
    public let externalAccountID: String
    public let email: String?
    public let displayName: String?
    public let plan: String?
    public let credentialReference: String
    public let createdAt: Date
    public let lastSuccessfulSync: Date?
    /// Non-secret provider extras, e.g. the Antigravity GCP project id.
    public let attributes: [String: String]

    public init(
        id: String,
        provider: ProviderID,
        externalAccountID: String,
        email: String?,
        displayName: String?,
        plan: String?,
        credentialReference: String,
        createdAt: Date,
        lastSuccessfulSync: Date?,
        attributes: [String: String] = [:]
    ) {
        self.id = id
        self.provider = provider
        self.externalAccountID = externalAccountID
        self.email = email
        self.displayName = displayName
        self.plan = plan
        self.credentialReference = credentialReference
        self.createdAt = createdAt
        self.lastSuccessfulSync = lastSuccessfulSync
        self.attributes = attributes
    }

    private enum CodingKeys: String, CodingKey {
        case id, provider, externalAccountID, email, displayName, plan, credentialReference
        case createdAt, lastSuccessfulSync, attributes
    }

    /// Lenient on `attributes`, which arrived after the first registers were written.
    ///
    /// The synthesised decoder ignores the initialiser's `[:]` default and demands the key, so
    /// a file from before it existed failed to decode — and the repository treats an
    /// undecodable register as one it must not overwrite, which strands every connected
    /// account and the credential reference each one names. Same rule as `UsageSnapshot`.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try c.decode(String.self, forKey: .id),
            provider: try c.decode(ProviderID.self, forKey: .provider),
            externalAccountID: try c.decode(String.self, forKey: .externalAccountID),
            email: try c.decodeIfPresent(String.self, forKey: .email),
            displayName: try c.decodeIfPresent(String.self, forKey: .displayName),
            plan: try c.decodeIfPresent(String.self, forKey: .plan),
            credentialReference: try c.decode(String.self, forKey: .credentialReference),
            createdAt: try c.decode(Date.self, forKey: .createdAt),
            lastSuccessfulSync: try c.decodeIfPresent(Date.self, forKey: .lastSuccessfulSync),
            attributes: try c.decodeIfPresent([String: String].self, forKey: .attributes) ?? [:])
    }

    /// `m***@example.com` — what the UI shows instead of the full address.
    public var maskedEmail: String? {
        guard let email, let at = email.firstIndex(of: "@"), at != email.startIndex else {
            return email
        }
        return "\(email[email.startIndex])***\(email[at...])"
    }

    public var label: String {
        if let displayName, !displayName.isEmpty { return displayName }
        if let maskedEmail { return maskedEmail }
        return String(externalAccountID.prefix(12))
    }
}

/// Why a snapshot looks the way it does. Drives the stale and failed banners.
public enum ConnectionStatus: String, Sendable, Codable {
    case connected, reconnectRequired, unknown

    public static func legacy(status: SnapshotStatus, message: String?) -> ConnectionStatus {
        // The evaluator's constant, not a second copy of the sentence: a reword there must not
        // leave a cached snapshot reading as connected.
        if message == NotificationEvaluator.signInExpiredMessage
            // Compatibility fallback: the wording an earlier build cached in snapshots that
            // outlive the upgrade. A literal on purpose — it is historical data to recognise,
            // not a message this build produces.
            || message == "This account needs signing in again."
        {
            return .reconnectRequired
        }
        return status == .failed ? .unknown : .connected
    }
}

public enum SnapshotStatus: String, Sendable, Codable {
    case ok
    case partial
    case failed
}

/// The result of one usage fetch for one account.
public struct UsageSnapshot: Sendable, Codable, Equatable {
    public let accountID: String
    public let fetchedAt: Date
    public let lastAttemptAt: Date
    public let status: SnapshotStatus
    public let windows: [UsageWindow]
    public let resetCredits: [ResetCredit]
    /// Provider-reported count, authoritative over `resetCredits.count` when present: the list
    /// can be truncated or filtered while the count stays exact.
    public let resetCreditCount: Int?

    /// How many of those credits the provider says can be applied RIGHT NOW.
    ///
    /// A different question from how many are held, and the one a redeem control has to ask.
    /// Codex reports both: an account can hold three credits and be able to spend none of them,
    /// because none applies to the limit currently in force. Nil means the provider did not say.
    public let applicableResetCreditCount: Int?

    public let errorMessage: String?
    public let connectionStatus: ConnectionStatus

    public init(
        accountID: String,
        fetchedAt: Date,
        status: SnapshotStatus,
        windows: [UsageWindow],
        resetCredits: [ResetCredit] = [],
        resetCreditCount: Int? = nil,
        applicableResetCreditCount: Int? = nil,
        errorMessage: String? = nil,
        connectionStatus: ConnectionStatus? = nil,
        lastAttemptAt: Date? = nil
    ) {
        self.accountID = accountID
        self.fetchedAt = fetchedAt
        self.lastAttemptAt = lastAttemptAt ?? fetchedAt
        self.status = status
        self.windows = windows
        self.resetCredits = resetCredits
        self.resetCreditCount = resetCreditCount
        self.applicableResetCreditCount = applicableResetCreditCount
        self.errorMessage = errorMessage
        self.connectionStatus = connectionStatus ?? .legacy(status: status, message: errorMessage)
    }

    /// Decoded field by field so a cache written before `applicableResetCreditCount` existed
    /// still reads. The synthesised decoder demands every key, and an undecodable cache is
    /// treated as empty — which would silently drop every account the user had connected.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let cachedEntries = try container.decodeIfPresent([CachedWindow].self, forKey: .windows) ?? []
        // The pre-scope parser only emitted on-demand with a positive cap. In those caches,
        // monthly and on-demand are alternative pools; explicit scopes remain authoritative.
        let hasLegacyOnDemand = cachedEntries.contains {
            $0.window.id == "xai-on-demand" && !$0.hasStoredScope
        }
        let cachedWindows = cachedEntries.map { entry in
            let window = entry.window
            guard hasLegacyOnDemand, !entry.hasStoredScope, window.id == "xai-monthly" else { return window }
            return UsageWindow(id: window.id, label: window.label, category: window.category,
                usedPercent: window.usedPercent, periodSeconds: window.periodSeconds,
                resetAt: window.resetAt, exhausted: window.exhausted, group: window.group, scope: .model)
        }
        // Earlier builds persisted deprecated monthly billing beside its replacement credit meter.
        let hasMeasuredCredits = cachedWindows.contains { $0.id == "xai-credits" && $0.remainingPercent != nil }
        self.init(
            accountID: try container.decode(String.self, forKey: .accountID),
            fetchedAt: try container.decode(Date.self, forKey: .fetchedAt),
            status: try container.decode(SnapshotStatus.self, forKey: .status),
            windows: hasMeasuredCredits ? cachedWindows.filter { $0.id != "xai-monthly" } : cachedWindows,
            resetCredits: try container.decodeIfPresent(
                [ResetCredit].self, forKey: .resetCredits) ?? [],
            resetCreditCount: try container.decodeIfPresent(
                Int.self, forKey: .resetCreditCount),
            applicableResetCreditCount: try container.decodeIfPresent(
                Int.self, forKey: .applicableResetCreditCount),
            errorMessage: try container.decodeIfPresent(String.self, forKey: .errorMessage),
            connectionStatus: try container.decodeIfPresent(ConnectionStatus.self, forKey: .connectionStatus),
            lastAttemptAt: try container.decodeIfPresent(Date.self, forKey: .lastAttemptAt))
    }

    private struct CachedWindow: Decodable {
        let window: UsageWindow
        let hasStoredScope: Bool

        private enum CodingKeys: String, CodingKey { case scope }

        init(from decoder: any Decoder) throws {
            window = try UsageWindow(from: decoder)
            let container = try decoder.container(keyedBy: CodingKeys.self)
            hasStoredScope = try container.decodeIfPresent(UsageScope.self, forKey: .scope) != nil
        }
    }

    /// Whether the last fetch failed.
    ///
    /// A named property rather than a status comparison at each call site, because "did this
    /// refresh work" is asked from several places and each one spelling it out invites one of
    /// them to spell it differently.
    public var failed: Bool { status == .failed }

    /// How many credits the account HOLDS — what a balance line shows.
    ///
    /// The provider's count is authoritative when it states one, because the row list can be
    /// truncated or filtered while the count stays exact. Falling back to the rows counts only
    /// the AVAILABLE ones: a spent credit is still listed, and counting it told a user with one
    /// consumed credit and nothing else that they held one to spend.
    public var heldResetCredits: Int {
        resetCreditCount
            ?? resetCredits.filter { $0.status.meansAvailable }.count
    }

    /// How many can be spent right now — what a redeem control is gated on.
    ///
    /// Deliberately separate from `heldResetCredits`, and this distinction is the whole reason
    /// the applicable count is fetched at all. An account can hold three credits and be able to
    /// apply none of them; gating the button on the held count offers a spend that the provider
    /// will refuse, against a balance the user watches go down.
    ///
    /// It falls back to the held count only when the provider stated no applicable figure, which
    /// is the older shape of the payload — there, held is the best evidence available.
    public var spendableResetCredits: Int { spendableResetCredits(at: Date()) }

    /// Credits available AND not past their own expiry at `now`. A snapshot is a photograph:
    /// a credit that read "available, expires at 14:00" is still in it at 15:00, and counting
    /// it offered a spend the provider would refuse. A provider count is authoritative as of
    /// the fetch and is left alone — subtracting from it with a possibly truncated row list
    /// would invent a number. Same rule as Android.
    public func spendableResetCredits(at now: Date) -> Int {
        if let applicable = applicableResetCreditCount { return applicable }
        if let count = resetCreditCount, resetCredits.isEmpty { return count }
        return resetCredits.filter {
            $0.status.meansAvailable && ($0.expiresAt.map { $0 > now } ?? true)
        }.count
    }

    /// The window closest to running out — what a summary leads with.
    public var mostCritical: UsageWindow? {
        // An explicitly exhausted window outranks everything, whatever its percentage says.
        // Ranked by percentage alone, a window the provider flagged exhausted but gave no
        // figure for sorted LAST — unknown reads as "infinitely much left" — and the summary
        // led with a 10 %-remaining neighbour while the real emergency sat below it.
        let global = windows.filter { $0.scope == .global }
        return (global.isEmpty ? windows : global).min { Self.rank($0) < Self.rank($1) }
    }

    private static func rank(_ window: UsageWindow) -> Double {
        window.exhausted ? -1 : (window.remainingPercent ?? .greatestFiniteMagnitude)
    }

    public var restrictedWindows: [UsageWindow] {
        windows.filter { $0.scope != .global && $0.severity == .exhausted }
    }

    public var statusMessage: String? {
        var messages: [String] = []
        if let errorMessage { messages.append(errorMessage) }
        if !restrictedWindows.isEmpty {
            messages.append("Restricted: " + restrictedWindows.map(\.label).joined(separator: ", "))
        }
        if windows.contains(where: \.hasConflictingAvailability) {
            messages.append("Provider reports a block despite remaining quota")
        }
        return messages.isEmpty ? nil : messages.joined(separator: " · ")
    }

    public var nextReset: Date? { windows.compactMap(\.resetAt).min() }

    /// Severity ignoring age. Prefer `severity(at:)` wherever a clock is available.
    public var severity: Severity {
        if status == .failed { return .error }
        let global = windows.filter { $0.scope == .global }
        if global.contains(where: { $0.severity == .exhausted }) { return .exhausted }
        if !restrictedWindows.isEmpty {
            let pools = Dictionary(grouping: windows, by: { $0.group ?? $0.id })
            let allPoolsBlocked = windows.allSatisfy { $0.scope == .model }
                && pools.values.allSatisfy { pool in pool.contains { $0.severity == .exhausted } }
            return global.isEmpty && allPoolsBlocked ? .exhausted : .limited
        }
        return (global.isEmpty ? windows : global).map(\.severity).filter { $0 != .unknown }.max() ?? .unknown
    }

    /// Severity including staleness.
    ///
    /// Age has to be part of the verdict. Without it, a snapshot that stopped refreshing keeps
    /// whatever status it had when it last succeeded, so day-old numbers still read healthy —
    /// which is the exact failure this app exists to prevent.
    /// - Parameter staleAfter: how old this snapshot may be before it reads as stale. Passed in
    ///   rather than assumed, because the threshold follows the user's chosen sync interval: at
    ///   the three-hour setting the app offers, a fixed hour marks every account stale
    ///   permanently. The default is only for callers that have no settings to hand.
    public func severity(at now: Date, staleAfter: TimeInterval = Severity.staleAfter) -> Severity {
        let base = severityOfOpenWindows(at: now)
        if base == .error { return base }
        return isStale(at: now, staleAfter: staleAfter) ? .stale : base
    }

    /// The verdict without windows whose reset passed since the fetch (`UsageWindow.resetElapsed`):
    /// they describe a closed window, so with none left the answer is unknown, not the old one.
    public func severityOfOpenWindows(at now: Date) -> Severity {
        let open = windows.filter { !$0.resetElapsed(fetchedAt: fetchedAt, at: now) }
        guard open.count != windows.count else { return severity }
        return UsageSnapshot(
            accountID: accountID, fetchedAt: fetchedAt, status: status, windows: open,
            resetCredits: resetCredits, resetCreditCount: resetCreditCount,
            applicableResetCreditCount: applicableResetCreditCount, errorMessage: errorMessage,
            connectionStatus: connectionStatus, lastAttemptAt: lastAttemptAt).severity
    }

    public func isStale(at now: Date, staleAfter: TimeInterval = Severity.staleAfter) -> Bool {
        now.timeIntervalSince(fetchedAt) >= staleAfter
    }
}

extension String {
    /// A spendable status is trimmed and lowercased, matching Kotlin meansAvailable.
    /// Case folding must not turn a dotless i into the ASCII i in available.
    var meansAvailable: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "available"
    }
}

extension String {
    /// Composed form, matching Kotlin's `canonical()`.
    ///
    /// Swift already compares strings by canonical equivalence and Kotlin does not, so
    /// normalising is a no-op for comparison here — it is done anyway so the KEY BYTES are the
    /// same on both platforms. A key is written to a ledger and read back; two spellings of
    /// one label must not produce two ledger entries on one platform and one on the other.
    var canonical: String { precomposedStringWithCanonicalMapping }
}

/// Display raw plan names consistently across providers. Parse structurally so unknown
/// tiers remain visible. Underscore and space are separators, matching Kotlin; hyphens
/// remain literal because vendor tier IDs do not use them as separators.
public func planLabel(_ raw: String?) -> String? {
    let parts = (raw ?? "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .split(whereSeparator: { $0 == "_" || $0 == " " })
        .map(String.init)
    guard !parts.isEmpty else { return nil }

    // A trailing `5x` multiplies the tier — but only when there is a tier for it to multiply.
    // `claude_20x` strips to a bare `20x`, which is the whole name, and reading it as a
    // multiplier of nothing yields no plan at all.
    var words = parts
    var multiplier: String?
    if parts.count > 1, let last = parts.last, last.hasSuffix("x") {
        let digits = String(last.dropLast())
        // ASCII digits only. `isNumber` accepts every Unicode numeric category, so an
        // Arabic-Indic digit would become a multiplier here and not on Android.
        if !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) {
            multiplier = digits
            words.removeLast()
        }
    }

    let name = words.map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    guard let multiplier else { return name }
    return "\(name) \(multiplier)×"
}
