import Foundation

/// Why a balance could not be read. Descriptions are built from endpoint names and status codes
/// only, never a payload, header or key, so they are safe to show and to store.
public enum CreditError: Error, LocalizedError, Equatable {
    /// 401 or 403: the key is wrong, expired, or the wrong kind for this endpoint.
    case rejected(Int)
    case notFound(String)
    case malformed(String)
    case unavailable(String)
    case teamIDRequired
    case noKey

    public var errorDescription: String? {
        switch self {
        case .rejected(let status):
            return "The key was rejected (HTTP \(status)). Check that it is the right kind of key."
        case .notFound(let detail): return detail
        case .malformed(let detail): return "The provider answered in a shape this app does not understand (\(detail))."
        case .unavailable(let detail): return "No balance could be read (\(detail))."
        case .noKey: return "No key was entered."
        case .teamIDRequired:
            return "This xAI key is not tied to one team. Enter the team id from console.x.ai."
        }
    }
}

/// Reading the balance payloads. Pure: payload in, balances out, so every quirk is testable
/// without a network. Amounts are `Decimal` from the text the provider sent where it sent text.
public enum CreditJSON {

    /// A strict decimal: digits with an optional sign, fraction and exponent. `Decimal(string:)`
    /// alone accepts a numeric prefix ("12abc" is 12), which would turn garbage into a balance.
    static func decimal(text: String) -> Decimal? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: #"^[+-]?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil
        else { return nil }
        return Decimal(string: trimmed, locale: Locale(identifier: "en_US_POSIX"))
    }

    /// A JSON object whose numbers arrive as the text the provider sent. `JSONSerialization`
    /// turns a number into a `Double`, and 100.000000000000001 is exactly 100 as one: a balance
    /// of 0.000000000000001 would read as empty. Quoting every number literal first keeps the
    /// digits, and `decimal` then reads them as `Decimal`. Nil when the text is not a JSON object.
    public static func object(fromText text: String) -> [String: Any]? {
        var out = ""
        var inString = false
        var escaped = false
        var number = false
        for character in text {
            if inString {
                out.append(character)
                if escaped { escaped = false } else if character == "\\" { escaped = true } else if character == "\"" { inString = false }
                continue
            }
            // A literal starts with a digit or "-"; the "e" of true/false/null is not one.
            let digit = character.isASCII && character.isNumber
            let numeric = number ? (digit || "+-.eE".contains(character)) : (digit || character == "-")
            if numeric && !number { out.append("\""); number = true }
            if !numeric && number { out.append("\""); number = false }
            if character == "\"" { inString = true }
            out.append(character)
        }
        if number { out.append("\"") }
        return (try? JSONSerialization.jsonObject(with: Data(out.utf8))) as? [String: Any]
    }

    /// A numeric string, or a number from a payload that did not come through `object(fromText:)`
    /// (a test literal); that fallback goes through its shortest decimal spelling.
    static func decimal(_ source: [String: Any]?, _ names: String...) -> Decimal? {
        guard let raw = JSONSupport.field(source, names) else { return nil }
        if let text = raw as? String { return decimal(text: text) }
        guard let double = JSONSupport.double(source, in: names) else { return nil }
        return Decimal(string: "\(double)", locale: Locale(identifier: "en_US_POSIX"))
    }

    static func currency(_ value: String?, default fallback: String) -> String {
        guard let code = value?.trimmingCharacters(in: .whitespaces).uppercased(),
              code.count == 3, code.allSatisfy({ $0.isLetter }) else { return fallback }
        return code
    }
}

public enum OpenRouterCreditParser {

    /// `/credits` (management key): purchased minus used, and the purchased total for the bar.
    public static func parseCredits(_ payload: [String: Any]) throws -> [CreditBalance] {
        let data = JSONSupport.object(payload, "data")
        guard let total = CreditJSON.decimal(data, "total_credits"),
              let used = CreditJSON.decimal(data, "total_usage") else {
            throw CreditError.malformed("openrouter credits has no totals")
        }
        return [CreditBalance(currency: "USD", amount: total - used, total: total)]
    }

    /// `/key` (any key): the key's own budget. `effective_budget` wins when it states a
    /// remainder; `limit` null means no cap, so there is no balance and the month's spend shows.
    public static func parseKey(_ payload: [String: Any]) throws -> [CreditBalance] {
        guard let data = JSONSupport.object(payload, "data") else {
            throw CreditError.malformed("openrouter key has no data")
        }
        let budget = JSONSupport.object(data, "effective_budget")
        if let remaining = CreditJSON.decimal(budget, "remaining_usd") {
            return [CreditBalance(
                currency: "USD", amount: remaining, total: CreditJSON.decimal(budget, "limit_usd"),
                label: "Key limit")]
        }
        // `limit` must be SENT: an explicit null is an uncapped key, an absent one is a payload
        // that is not what this app expects, and must not read as a healthy $0.
        guard let limit = data["limit"] else {
            throw CreditError.malformed("openrouter key has no limit field")
        }
        if limit is NSNull {
            guard let spent = CreditJSON.decimal(data, "usage_monthly") else {
                throw CreditError.malformed("openrouter key has no limit and no monthly usage")
            }
            return [CreditBalance(currency: "USD", amount: spent, label: "No limit", unlimited: true)]
        }
        guard let remaining = CreditJSON.decimal(data, "limit_remaining") else {
            throw CreditError.malformed("openrouter key has a limit but no remainder")
        }
        return [CreditBalance(
            currency: "USD", amount: remaining, total: CreditJSON.decimal(data, "limit"), label: "Key limit")]
    }
}

public enum DeepSeekCreditParser {

    /// One balance per currency. `is_available: false` means requests are refused, so the
    /// balances read as empty whatever the grant figures say.
    public static func parse(_ payload: [String: Any]) throws -> [CreditBalance] {
        let available = JSONSupport.bool(payload, "is_available") ?? true
        let infos = (JSONSupport.field(payload, ["balance_infos"]) as? [[String: Any]]) ?? []
        let balances: [CreditBalance] = infos.compactMap { info in
            guard let amount = CreditJSON.decimal(info, "total_balance") else { return nil }
            let currency = CreditJSON.currency(JSONSupport.string(info, "currency"), default: "")
            guard !currency.isEmpty else { return nil }
            return available
                ? CreditBalance(currency: currency, amount: amount)
                : CreditBalance(currency: currency, amount: 0, label: "Unavailable")
        }
        if !balances.isEmpty { return balances }
        if !available { return [CreditBalance(currency: "CNY", amount: 0, label: "Unavailable")] }
        throw CreditError.malformed("deepseek balance has no balances")
    }
}

public enum MoonshotCreditParser {

    /// `available_balance` is what requests can spend; `cash_balance` may be negative
    /// (arrears) and is deliberately ignored. The currency follows the host.
    public static func parse(_ payload: [String: Any], region: ProviderRegion) throws -> [CreditBalance] {
        guard let available = CreditJSON.decimal(JSONSupport.object(payload, "data"), "available_balance") else {
            throw CreditError.malformed("moonshot balance has no available_balance")
        }
        return [CreditBalance(currency: region == .cn ? "CNY" : "USD", amount: available)]
    }
}

public enum XaiCreditParser {

    /// The team a management key is scoped to, or nil for an organisation-wide key (the caller
    /// then needs the user's team id).
    public static func parseTeamID(_ validation: [String: Any]) -> String? {
        guard JSONSupport.string(validation, "scope") == "SCOPE_TEAM" else { return nil }
        return JSONSupport.string(validation, "scopeId")
    }

    /// `total.val` is USD cents as a string, from a ledger: a top-up is NEGATIVE, so what is
    /// left is minus the total. A missing or unreadable total is an error, never $0.
    public static func parseBalance(_ payload: [String: Any]) throws -> [CreditBalance] {
        guard let cents = CreditJSON.decimal(JSONSupport.object(payload, "total"), "val") else {
            throw CreditError.malformed("xai prepaid balance has no total")
        }
        return [CreditBalance(currency: "USD", amount: -cents / 100)]
    }
}

public enum FalCreditParser {

    /// A 200 without the credits block happens during fal incidents; that is a failed read.
    public static func parse(_ payload: [String: Any]) throws -> [CreditBalance] {
        guard let credits = JSONSupport.object(payload, "credits"),
              let balance = CreditJSON.decimal(credits, "current_balance") else {
            throw CreditError.unavailable("fal returned no credits")
        }
        return [CreditBalance(
            currency: CreditJSON.currency(JSONSupport.string(credits, "currency"), default: "USD"),
            amount: balance)]
    }
}

public enum NovitaCreditParser {

    /// Every figure is a string in 1/10000 USD ("10000" is $1.00).
    public static func parse(_ payload: [String: Any]) throws -> [CreditBalance] {
        guard let units = CreditJSON.decimal(payload, "availableBalance") else {
            throw CreditError.malformed("novita balance has no availableBalance")
        }
        return [CreditBalance(currency: "USD", amount: units / 10_000)]
    }
}
