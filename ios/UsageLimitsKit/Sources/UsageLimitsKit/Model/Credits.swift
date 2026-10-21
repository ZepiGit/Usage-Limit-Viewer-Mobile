import Foundation

/// Prepaid / API balances ("credits"), kept apart from the usage-limit accounts: a credit
/// account has no windows and no reset, only an amount of money left.
///
/// Raw values, field names and the severity rules are the shared Android/iOS contract written
/// down in `docs/credits.md`; change them on both platforms or neither.

/// The providers whose balance can be read with a key. `rawValue` is persisted.
public enum CreditProviderID: String, CaseIterable, Sendable, Codable {
    case openrouter
    case deepseek
    case moonshot
    case xai
    case fal
    case novita

    public var displayName: String {
        switch self {
        case .openrouter: return "OpenRouter"
        case .deepseek: return "DeepSeek"
        case .moonshot: return "Moonshot (Kimi API)"
        case .xai: return "xAI (prepaid)"
        case .fal: return "fal.ai"
        case .novita: return "Novita AI"
        }
    }

    public var iconAssetName: String {
        switch self {
        case .openrouter: return "CreditIconOpenrouter"
        case .deepseek: return "CreditIconDeepseek"
        case .moonshot: return "CreditIconMoonshot"
        case .xai: return "CreditIconXai"
        case .fal: return "CreditIconFal"
        case .novita: return "CreditIconNovita"
        }
    }

    /// What the user has to paste, in the words the add sheet shows.
    public var keyHint: String {
        switch self {
        case .openrouter: return "Management key for the account balance, or any API key for that key's own limit."
        case .deepseek: return "DeepSeek API key."
        case .moonshot: return "Moonshot platform API key (not a Kimi Code key)."
        case .xai: return "xAI management key (console.x.ai, Settings, Management Keys), not an inference key."
        case .fal: return "fal key with the billing:usage:read scope (the BILLING preset), not an inference key."
        case .novita: return "Novita API key."
        }
    }

    /// Only Moonshot runs two hosts (and two currencies) that keys do not cross.
    public var hasRegion: Bool { self == .moonshot }

    /// xAI needs a team id when the management key is not scoped to one team.
    public var acceptsTeamID: Bool { self == .xai }

    public var consoleURL: String {
        switch self {
        case .openrouter: return "https://openrouter.ai/settings/keys"
        case .deepseek: return "https://platform.deepseek.com/api_keys"
        case .moonshot: return "https://platform.moonshot.ai/console/api-keys"
        case .xai: return "https://console.x.ai/team/default/api-keys"
        case .fal: return "https://fal.ai/dashboard/keys"
        case .novita: return "https://novita.ai/settings/key-management"
        }
    }

    /// Regions in picker order. Default is the first.
    public static let regions: [ProviderRegion] = [.intl, .cn]

    public static func regionLabel(_ region: ProviderRegion) -> String {
        region == .cn ? "China (moonshot.cn, CNY)" : "International (moonshot.ai, USD)"
    }

    /// Providers shown in the picker but disabled, with the reason. Not cases of the enum: there
    /// is nothing to store or sync for them.
    public static let notPossible: [CreditUnavailableProvider] = [
        CreditUnavailableProvider(
            id: "anthropic", displayName: "Anthropic API",
            reason: "No balance endpoint for API keys"),
        CreditUnavailableProvider(
            id: "openai", displayName: "OpenAI API",
            reason: "No balance endpoint for API keys"),
    ]
}

public struct CreditUnavailableProvider: Sendable, Equatable, Identifiable {
    public let id: String
    public let displayName: String
    public let reason: String
}

/// One amount of money left, in one currency.
///
/// `Decimal`, never `Double`: this is money, and a balance of 0.1 + 0.2 must read 0.30. On disk
/// the amounts are strings for the same reason, and so the file reads the same on Android.
public struct CreditBalance: Sendable, Equatable, Codable {
    /// ISO 4217 code.
    public let currency: String
    public let amount: Decimal
    /// The cap or purchased total, when the provider states one; draws the bar.
    public let total: Decimal?
    public let label: String?
    /// An uncapped OpenRouter key: there is no balance to be low on. `amount` then holds what
    /// the key spent this month and `label` is "No limit".
    public let unlimited: Bool

    public init(currency: String, amount: Decimal, total: Decimal? = nil, label: String? = nil, unlimited: Bool = false) {
        self.currency = currency
        self.amount = amount
        self.total = total
        self.label = label
        self.unlimited = unlimited
    }

    /// Remaining share of `total`, 0...100, or nil without a positive total.
    public var remainingPercent: Double? {
        guard let total, total > 0 else { return nil }
        let share = (amount / total) * 100
        return min(max(NSDecimalNumber(decimal: share).doubleValue, 0), 100)
    }

    private enum CodingKeys: String, CodingKey { case currency, amount, total, label, unlimited }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        currency = try c.decode(String.self, forKey: .currency)
        // A balance with no readable amount is a corrupt file, not $0.
        guard let amount = try Self.decimal(c, .amount) else {
            throw DecodingError.dataCorruptedError(forKey: .amount, in: c, debugDescription: "missing or invalid amount")
        }
        self.amount = amount
        total = try Self.decimal(c, .total)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        unlimited = try c.decodeIfPresent(Bool.self, forKey: .unlimited) ?? false
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(currency, forKey: .currency)
        try c.encode("\(amount)", forKey: .amount)
        try c.encodeIfPresent(total.map { "\($0)" }, forKey: .total)
        try c.encodeIfPresent(label, forKey: .label)
        if unlimited { try c.encode(true, forKey: .unlimited) }
    }

    /// A decimal string, or a bare JSON number from a hand-edited file.
    private static func decimal(_ c: KeyedDecodingContainer<CodingKeys>, _ key: CodingKeys) throws -> Decimal? {
        if let text = try? c.decodeIfPresent(String.self, forKey: key) {
            return CreditJSON.decimal(text: text)
        }
        return try? c.decodeIfPresent(Decimal.self, forKey: key)
    }
}

public enum CreditStatus: String, Sendable, Codable {
    case ok
    case failed
}

/// The last read of one credit account. A failure keeps the previous balances and the original
/// `fetchedAt`: stale numbers marked stale beat an empty card.
public struct CreditSnapshot: Sendable, Equatable, Codable {
    public let accountID: String
    public let balances: [CreditBalance]
    public let fetchedAt: Date
    public let lastAttemptAt: Date
    public let status: CreditStatus
    public let error: String?

    public init(
        accountID: String, balances: [CreditBalance], fetchedAt: Date,
        lastAttemptAt: Date? = nil, status: CreditStatus = .ok, error: String? = nil
    ) {
        self.accountID = accountID
        self.balances = balances
        self.fetchedAt = fetchedAt
        self.lastAttemptAt = lastAttemptAt ?? fetchedAt
        self.status = status
        self.error = error
    }
}

/// A connected credit account. Carries no key: `credentialReference` names the keychain item.
public struct CreditAccount: Sendable, Equatable, Codable, Identifiable {
    public let id: String
    public let provider: CreditProviderID
    public let label: String
    public let credentialReference: String
    /// Non-secret extras: `region` (Moonshot), `teamId` (xAI), `lowThreshold` (decimal string).
    public let attributes: [String: String]
    public let sortOrder: Int
    public let createdAt: Date

    public static let regionKey = ProviderRegion.key
    public static let teamIDKey = "teamId"
    public static let lowThresholdKey = "lowThreshold"

    public init(
        id: String, provider: CreditProviderID, label: String, credentialReference: String,
        attributes: [String: String] = [:], sortOrder: Int = 0, createdAt: Date
    ) {
        self.id = id
        self.provider = provider
        self.label = label
        self.credentialReference = credentialReference
        self.attributes = attributes
        self.sortOrder = sortOrder
        self.createdAt = createdAt
    }

    public var region: ProviderRegion {
        attributes[Self.regionKey].flatMap(ProviderRegion.init(rawValue:)) ?? .intl
    }

    public var teamID: String? { attributes[Self.teamIDKey].flatMap { $0.isEmpty ? nil : $0 } }

    /// What counts as low for `currency`: the user's own figure, else 5 units (35 for CNY).
    public func lowThreshold(for currency: String) -> Decimal {
        if let text = attributes[Self.lowThresholdKey],
           let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")), value > 0 {
            return value
        }
        return currency.uppercased() == "CNY" ? 35 : 5
    }

    public func with(label: String, lowThreshold: Decimal?) -> CreditAccount {
        var extras = attributes
        extras[Self.lowThresholdKey] = lowThreshold.map { "\($0)" }
        return CreditAccount(
            id: id, provider: provider, label: label, credentialReference: credentialReference,
            attributes: extras, sortOrder: sortOrder, createdAt: createdAt)
    }
}

/// An account and its latest snapshot, as the repository hands them over.
public struct CreditEntry: Sendable, Equatable, Identifiable {
    public let account: CreditAccount
    public let snapshot: CreditSnapshot?
    public var id: String { account.id }

    public init(account: CreditAccount, snapshot: CreditSnapshot?) {
        self.account = account
        self.snapshot = snapshot
    }
}
