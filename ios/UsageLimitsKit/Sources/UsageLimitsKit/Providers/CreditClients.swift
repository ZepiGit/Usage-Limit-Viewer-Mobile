import Foundation

/// Reads one provider's balance with the key the user pasted. No token refresh: a key either
/// works or it does not.
public protocol CreditProvider: Sendable {
    var providerID: CreditProviderID { get }

    /// `attributes` are the account's non-secret extras (region, team id).
    func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance]
}

/// The shared request path. `UsageHTTPClient` throws on a non-2xx, so the statuses a caller can
/// act on (401/403 wrong key, 404 wrong team) are translated here, once.
enum CreditHTTP {

    /// The body text of a 2xx answer, after the status translation below.
    static func getText(
        _ client: UsageHTTPClient, url: String, headers: [String: String], endpoint: String
    ) async throws -> String {
        let response: HTTPResponse
        do {
            response = try await client.request(url: url, headers: headers)
        } catch let error as HTTPError {
            switch error {
            case .status(let code, _):
                if code == 401 || code == 403 { throw CreditError.rejected(code) }
                if code == 404 { throw CreditError.notFound("\(endpoint) answered HTTP 404") }
                throw CreditError.unavailable("\(endpoint) answered HTTP \(code)")
            case .rateLimited(let retryAfter):
                throw CreditError.rateLimited(endpoint: endpoint, retryAfter: retryAfter)
            default:
                throw CreditError.unavailable("\(endpoint) could not be reached")
            }
        }
        return response.body
    }

    static func getJSON(
        _ client: UsageHTTPClient, url: String, headers: [String: String], endpoint: String
    ) async throws -> [String: Any] {
        let text = try await getText(client, url: url, headers: headers, endpoint: endpoint)
        guard let object = CreditJSON.object(fromText: text) else {
            throw CreditError.malformed("\(endpoint) did not return a JSON object")
        }
        return object
    }

    static func bearer(_ key: String) -> [String: String] {
        ["Authorization": "Bearer \(key)", "Accept": "application/json"]
    }
}

public struct OpenRouterCreditClient: CreditProvider {
    public let providerID = CreditProviderID.openrouter
    let http: UsageHTTPClient
    /// The clock the key limit's period boundary is placed from.
    let now: @Sendable () -> Date
    public init(httpClient: UsageHTTPClient, now: @escaping @Sendable () -> Date = { Date() }) {
        http = httpClient
        self.now = now
    }

    /// `/credits` needs a management key and answers 403 to any other; that 403 (and only that
    /// one) falls back to `/key`, which any key can read for its own limit.
    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        do {
            let payload = try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.openRouterCredits,
                headers: CreditHTTP.bearer(key), endpoint: "openrouter credits")
            return try OpenRouterCreditParser.parseCredits(payload)
        } catch CreditError.rejected(403) {
            let payload = try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.openRouterKey,
                headers: CreditHTTP.bearer(key), endpoint: "openrouter key")
            return try OpenRouterCreditParser.parseKey(payload, now: now())
        }
    }
}

public struct DeepSeekCreditClient: CreditProvider {
    public let providerID = CreditProviderID.deepseek
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        try DeepSeekCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.deepSeekBalance,
                headers: CreditHTTP.bearer(key), endpoint: "deepseek balance"))
    }
}

public struct MoonshotCreditClient: CreditProvider {
    public let providerID = CreditProviderID.moonshot
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        let region = attributes[ProviderRegion.key].flatMap(ProviderRegion.init(rawValue:)) ?? .intl
        return try MoonshotCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.moonshotBalance(region),
                headers: CreditHTTP.bearer(key), endpoint: "moonshot balance"),
            region: region)
    }
}

public struct XaiCreditClient: CreditProvider {
    public let providerID = CreditProviderID.xai
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    /// The team the key is scoped to. Org-wide keys name no team; the user supplies it.
    public func resolveTeamID(key: String) async throws -> String? {
        XaiCreditParser.parseTeamID(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.xaiValidation,
                headers: CreditHTTP.bearer(key), endpoint: "xai key validation"))
    }

    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        let stored = attributes[CreditAccount.teamIDKey].flatMap { $0.isEmpty ? nil : $0 }
        let resolved: String?
        if let stored { resolved = stored } else { resolved = try await resolveTeamID(key: key) }
        guard let teamID = resolved else { throw CreditError.teamIDRequired }
        do {
            return try XaiCreditParser.parseBalance(
                try await CreditHTTP.getJSON(
                    http, url: ProviderEndpoints.Credits.xaiBalance(teamID: teamID),
                    headers: CreditHTTP.bearer(key), endpoint: "xai prepaid balance"))
        } catch CreditError.notFound {
            // Never $0: a team with no prepaid ledger and a wrong team id look the same here.
            throw CreditError.notFound(
                "xAI has no prepaid balance for this team (HTTP 404). Check the team id, and that the team prepays.")
        }
    }
}

public struct FalCreditClient: CreditProvider {
    public let providerID = CreditProviderID.fal
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        try FalCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.falBilling,
                // `Key`, not `Bearer`.
                headers: ["Authorization": "Key \(key)", "Accept": "application/json"],
                endpoint: "fal billing"))
    }
}

public struct NovitaCreditClient: CreditProvider {
    public let providerID = CreditProviderID.novita
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        try NovitaCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.novitaBalance,
                headers: CreditHTTP.bearer(key).merging(["Content-Type": "application/json"]) { a, _ in a },
                endpoint: "novita balance"))
    }
}

public struct VercelCreditClient: CreditProvider {
    public let providerID = CreditProviderID.vercelAiGateway
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    /// The key names its team, so no extra lookup is needed. A 403 means the key belongs to
    /// another team; it is translated once here, and a 401 stays a rejected key. Nothing is
    /// retried.
    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        let teamID = attributes[CreditAccount.teamIDKey].flatMap { $0.isEmpty ? nil : $0 }
        do {
            return try VercelCreditParser.parse(
                try await CreditHTTP.getText(
                    http, url: ProviderEndpoints.Credits.vercelCredits(teamID: teamID),
                    headers: CreditHTTP.bearer(key), endpoint: "vercel credits"))
        } catch CreditError.rejected(403) {
            throw CreditError.teamDenied
        }
    }
}

public struct DeepInfraCreditClient: CreditProvider {
    public let providerID = CreditProviderID.deepinfra
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    /// A 403 means the key lacks `account:read`, which no retry fixes; it is translated once here.
    /// A 401 stays a rejected key. Nothing is retried.
    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        do {
            return try DeepInfraCreditParser.parse(
                try await CreditHTTP.getJSON(
                    http, url: ProviderEndpoints.Credits.deepInfraMe,
                    headers: CreditHTTP.bearer(key), endpoint: "deepinfra me"))
        } catch CreditError.rejected(403) {
            throw CreditError.missingScope
        }
    }
}

public struct TokenRouterCreditClient: CreditProvider {
    public let providerID = CreditProviderID.tokenrouter
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    /// A 401 is a bad key and a 403 a disabled or expired one; both are the shared rejection.
    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        try TokenRouterCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.tokenRouterWallet,
                headers: CreditHTTP.bearer(key), endpoint: "tokenrouter wallet"))
    }
}

public struct OllamaCreditClient: CreditProvider {
    public let providerID = CreditProviderID.ollama
    let http: UsageHTTPClient
    public init(httpClient: UsageHTTPClient) { http = httpClient }

    /// The same call the usage account reads. A 401 is a bad key and a 403 is a suspended account
    /// (the doc's own word for it); both are the shared rejection, since no other key and no retry
    /// changes either. Nothing is retried.
    public func fetchBalances(key: String, attributes: [String: String]) async throws -> [CreditBalance] {
        try OllamaCreditParser.parse(
            try await CreditHTTP.getJSON(
                http, url: ProviderEndpoints.Credits.ollamaBalance,
                headers: CreditHTTP.bearer(key), endpoint: "ollama balance"))
    }
}
