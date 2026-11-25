import Foundation

/// Why a balance could not be read. Descriptions are built from endpoint names and status codes
/// only, never a payload, header or key, so they are safe to show and to store.
public enum CreditError: Error, LocalizedError, Equatable {
    /// 401 or 403: the key is wrong, expired, or the wrong kind for this endpoint.
    case rejected(Int)
    case notFound(String)
    case malformed(String)
    case unavailable(String)
    case rateLimited(endpoint: String, retryAfter: TimeInterval?)
    case teamIDRequired
    /// Vercel answers 403 when the key belongs to another team. Not a broken key, and a retry
    /// cannot help, so the sentence says what to change instead.
    case teamDenied
    /// DeepInfra answers 403 to a key without the `account:read` scope. The balance is closed to
    /// this key until the scope is granted, so the sentence names the scope.
    case missingScope
    case noKey

    public var errorDescription: String? {
        switch self {
        case .rejected(let status):
            return "The key was rejected (HTTP \(status)). Check that it is the right kind of key."
        case .notFound(let detail): return detail
        case .malformed(let detail): return "The provider answered in a shape this app does not understand (\(detail))."
        case .unavailable(let detail): return "No balance could be read (\(detail))."
        case .rateLimited:
            return StoredMessage.rateLimitedRequestMessage
        case .noKey: return "No key was entered."
        case .teamIDRequired:
            return "This xAI key is not tied to one team. Enter the team id from console.x.ai."
        case .teamDenied:
            return "Vercel refused this key (HTTP 403). Enter the team id the key belongs to."
        case .missingScope:
            return "This key lacks the account:read scope."
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
        // Scalars, not Characters: a quote followed by a combining mark is one Character.
        var out = String.UnicodeScalarView()
        var inString = false
        var escaped = false
        var number = false
        for character in text.unicodeScalars {
            if inString {
                out.append(character)
                if escaped { escaped = false } else if character == "\\" { escaped = true } else if character == "\"" { inString = false }
                continue
            }
            // A literal starts with a digit or "-"; the "e" of true/false/null is not one.
            let digit = ("0"..."9").contains(character)
            let numeric = number ? (digit || "+-.eE".unicodeScalars.contains(character)) : (digit || character == "-")
            if numeric && !number { out.append("\""); number = true }
            if !numeric && number { out.append("\""); number = false }
            if character == "\"" { inString = true }
            out.append(character)
        }
        if number { out.append("\"") }
        return (try? JSONSerialization.jsonObject(with: Data(String(out).utf8))) as? [String: Any]
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
    ///
    /// `now` only places the next period boundary of a capped key: the payload names the period
    /// (`limit_reset`) but never its instant. The budget row is left alone, because a budget's
    /// scope can be a member or a workspace rather than this key.
    public static func parseKey(_ payload: [String: Any], now: Date) throws -> [CreditBalance] {
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
        let cap = CreditJSON.decimal(data, "limit")
        let window = (cap.map { $0 > 0 } ?? false)
            ? keyLimitWindow(JSONSupport.string(data, "limit_reset"), now: now) : nil
        return [CreditBalance(
            currency: "USD", amount: remaining, total: cap,
            label: window?.label ?? "Key limit", resetsAt: window?.resetsAt)]
    }

    /// A key limit's period label and the next boundary strictly after `now`, or nil for
    /// `limit_reset == nil` (a lifetime cap) and for a value this app does not know — no reset is
    /// invented. The boundaries are OpenRouter's: daily 00:00 UTC, weekly Monday 00:00 UTC,
    /// monthly on the first at 00:00 UTC.
    private static func keyLimitWindow(
        _ limitReset: String?, now: Date
    ) -> (label: String, resetsAt: Date)? {
        let calendar = utcCalendar
        let today = calendar.startOfDay(for: now)
        switch limitReset {
        case "daily":
            guard let at = calendar.date(byAdding: .day, value: 1, to: today) else { return nil }
            return ("Daily key limit", at)
        case "weekly":
            // 1 is Sunday and 2 Monday in Foundation's Gregorian calendar; a Monday today means
            // the boundary is seven days out, never today.
            let ahead = (2 - calendar.component(.weekday, from: today) + 7) % 7
            guard let at = calendar.date(byAdding: .day, value: ahead == 0 ? 7 : ahead, to: today) else { return nil }
            return ("Weekly key limit", at)
        case "monthly":
            var parts = calendar.dateComponents([.year, .month], from: today)
            parts.day = 1
            guard let monthStart = calendar.date(from: parts),
                  let at = calendar.date(byAdding: .month, value: 1, to: monthStart) else { return nil }
            return ("Monthly key limit", at)
        default:
            return nil
        }
    }

    private static let utcCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar
    }()
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

public enum VercelCreditParser {

    /// Takes the body **text**, not the normalised object: `CreditJSON.object` quotes every JSON
    /// number to keep its digits, which would hide the one thing the gateway's documentation says
    /// about the figures — they are strings. A number is a payload this app refuses rather than
    /// rounds, and only a plain `JSONSerialization` pass still knows the difference.
    ///
    /// `balance` is what is left and excludes the Token Pass; `total_used` is lifetime spend and
    /// rides on the row's label. An active Token Pass becomes a second row with its reset and no
    /// bar, and one whose remainder is null adds no row at all — never a zero.
    public static func parse(_ body: String) throws -> [CreditBalance] {
        guard let payload = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any] else {
            throw CreditError.malformed("vercel credits did not return a JSON object")
        }
        guard let balance = try stringDecimal(payload, "balance") else {
            throw CreditError.malformed("vercel credits has no balance")
        }
        var rows = [CreditBalance(
            currency: "USD", amount: balance,
            label: try stringDecimal(payload, "total_used").map { "Used: \($0)" })]
        let pass = JSONSupport.object(payload, "token_pass")
        if let remaining = try stringDecimal(pass, "remaining") {
            rows.append(CreditBalance(
                currency: "USD", amount: remaining, label: "Token Pass",
                resetsAt: JSONSupport.date(JSONSupport.string(pass, "resets_at"))))
        }
        return rows
    }

    /// A figure the provider states as a decimal string, or nil when it states none. A JSON number
    /// (or any other type) is a payload this app does not read: returning nil would hide it, and
    /// reading it as a float would lose digits.
    private static func stringDecimal(_ source: [String: Any]?, _ name: String) throws -> Decimal? {
        guard let raw = JSONSupport.field(source, [name]) else { return nil }
        guard let text = raw as? String else {
            throw CreditError.malformed("vercel credits states \(name) as something other than a decimal string")
        }
        guard let figure = CreditJSON.decimal(text: text) else {
            throw CreditError.malformed("vercel credits has no readable \(name)")
        }
        return figure
    }
}

public enum DeepInfraCreditParser {

    /// The billing checklist. `stripe_balance` is signed: NEGATIVE is money left to spend and
    /// positive is money owed, so the amount is its negation. The cents reading is the spec's
    /// inference — the OpenAPI types the field `number` and states no unit — while the scoped
    /// credits name their cents outright. Each live scoped credit is one more row named after it,
    /// an expired one adds none, and `limit` is a spending cap that never becomes a balance. A
    /// checklist that is null or absent is a failed read, never a zero balance.
    public static func parse(_ payload: [String: Any]) throws -> [CreditBalance] {
        guard let checklist = JSONSupport.object(payload, "checklist") else {
            throw CreditError.unavailable("deepinfra me returned no checklist")
        }
        guard let stripe = CreditJSON.decimal(checklist, "stripe_balance") else {
            throw CreditError.malformed("deepinfra checklist has no stripe_balance")
        }
        var rows = [CreditBalance(currency: "USD", amount: -stripe / 100, label: "Prepaid balance")]
        for element in (JSONSupport.field(checklist, ["scoped_credits"]) as? [Any]) ?? [] {
            guard let credit = element as? [String: Any] else {
                throw CreditError.malformed("deepinfra scoped_credits holds a non-object")
            }
            if JSONSupport.bool(credit, "expired") == true { continue }
            guard let remaining = CreditJSON.decimal(credit, "remaining_cents") else {
                throw CreditError.malformed("deepinfra scoped credit has no remaining_cents")
            }
            rows.append(CreditBalance(
                currency: "USD", amount: remaining / 100,
                label: JSONSupport.string(credit, "name")))
        }
        return rows
    }
}
