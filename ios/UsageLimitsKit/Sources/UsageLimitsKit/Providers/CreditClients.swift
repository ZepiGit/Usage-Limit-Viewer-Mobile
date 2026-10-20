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

    static func getJSON(
        _ client: UsageHTTPClient, url: String, headers: [String: String], endpoint: String
    ) async throws -> [String: Any] {
        let response: HTTPResponse
        do {
            response = try await client.request(url: url, headers: headers)
        } catch let error as HTTPError {
            switch error {
            case .status(let code, _):
                if code == 401 || code == 403 { throw CreditError.rejected(code) }
                if code == 404 { throw CreditError.notFound("\(endpoint) answered HTTP 404") }
                throw CreditError.unavailable("\(endpoint) answered HTTP \(code)")
            case .rateLimited:
                throw CreditError.unavailable("\(endpoint) is rate limiting this key")
            default:
                throw CreditError.unavailable("\(endpoint) could not be reached")
            }
        }
        guard let data = response.body.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
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
    public init(httpClient: UsageHTTPClient) { http = httpClient }

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
            return try OpenRouterCreditParser.parseKey(payload)
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
