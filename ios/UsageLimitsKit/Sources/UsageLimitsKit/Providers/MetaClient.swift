import Foundation

/// Meta Muse usage adapter.
///
/// Muse's device grant returns a DCA bearer token.  The token is exchanged at the `muse-code`
/// key endpoint for the short API key used by the public API.  We retain the DCA token in the
/// credential's protected `providerData` map because it is also the only credential accepted by
/// the subscription-quota response.
public struct MetaClient: SyncProvider, Sendable {
    public let providerID = ProviderID.meta.rawValue

    let httpClient: UsageHTTPClient
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(
        credentials: OAuthCredentials,
        attributes _: [String: String]
    ) async throws -> UsageResult {
        let payload = try await quotaPayload(credentials: credentials)
        let identity = MetaUsageParser.identity(in: payload)
        return UsageResult(
            windows: MetaUsageParser.parse(payload, now: now()),
            plan: identity.plan,
            accountIdentity: identity.externalAccountID)
    }

    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        let payload = try await quotaPayload(credentials: credentials)
        let identity = MetaUsageParser.identity(in: payload)
        let fallbackID = try dcaToken(of: credentials)
        let accountID = identity.externalAccountID?.trimmingCharacters(in: .whitespacesAndNewlines)
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? Self.stableAccountID(dcaToken: fallbackID)
        return ProviderProfile(
            externalAccountID: accountID,
            email: identity.email,
            displayName: identity.displayName,
            plan: identity.plan)
    }

    /// The key response normally names the account, but provisioning responses can contain only
    /// subscription fields. A one-way token digest keeps re-login idempotent without retaining or
    /// displaying the DCA bearer.
    private static func stableAccountID(dcaToken: String) -> String {
        let digest = SHA256.hash(Array(dcaToken.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return "meta-" + digest
    }

    /// Mints a fresh Muse API key using the retained DCA token.
    public func mintAPIKey(credentials: OAuthCredentials) async throws -> OAuthCredentials {
        let dcaToken = try dcaToken(of: credentials)
        let payload = try await mintPayload(dcaToken: dcaToken)
        guard let key = JSONSupport.string(payload, "api_key", "apiKey", "key"), !key.isEmpty else {
            throw ProviderError.malformedPayload("meta muse key returned no api_key")
        }
        return OAuthCredentials(
            accessToken: key,
            refreshToken: nil,
            idToken: credentials.idToken,
            expiresAt: nil,
            scope: credentials.scope,
            providerData: ["dca_token": dcaToken, "api_key": key])
    }

    private func dcaToken(of credentials: OAuthCredentials) throws -> String {
        if let stored = credentials.providerData["dca_token"]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !stored.isEmpty {
            return stored
        }
        // Backward compatibility for pre-providerData development builds.
        if let refresh = credentials.refreshToken?.trimmingCharacters(in: .whitespacesAndNewlines),
           !refresh.isEmpty {
            return refresh
        }
        if !credentials.accessToken.isEmpty { return credentials.accessToken }
        throw ProviderError.unauthorised
    }

    private func mintPayload(dcaToken: String) async throws -> [String: Any] {
        let body = try JSONSerialization.data(withJSONObject: ["dca_token": dcaToken], options: [])
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Meta.keyURL,
            method: "POST",
            headers: [
                "Authorization": "Bearer \(dcaToken)",
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": ProviderEndpoints.Meta.userAgent,
                "x-api-version": ProviderEndpoints.Meta.apiVersion,
            ],
            body: body,
            endpoint: "meta muse key")
        return try ProviderHTTP.decodeObject(response.body, endpoint: "meta muse key")
    }

    /// The quota read uses the DCA bearer but an empty JSON body. Sending the minting body here
    /// works on some rollouts and fails on the current CPAMC-compatible endpoint, so keep the two
    /// wire contracts separate.
    private func quotaPayload(credentials: OAuthCredentials) async throws -> [String: Any] {
        let dcaToken = try dcaToken(of: credentials)
        let body = Data("{}".utf8)
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Meta.keyURL,
            method: "POST",
            headers: [
                "Authorization": "Bearer \(dcaToken)",
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": ProviderEndpoints.Meta.userAgent,
                "x-api-version": ProviderEndpoints.Meta.apiVersion,
            ],
            body: body,
            endpoint: "meta muse quota")
        return try ProviderHTTP.decodeObject(response.body, endpoint: "meta muse quota")
    }
}
