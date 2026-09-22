import Foundation

/// Devin usage adapter.  Devin's official CLI calls a Connect-RPC protobuf endpoint; the parser
/// also accepts the JSON representation returned by CPAMC gateways and test transports.
public struct DevinClient: SyncProvider, Sendable {
    public let providerID = ProviderID.devin.rawValue

    let httpClient: UsageHTTPClient
    let now: @Sendable () -> Date

    public init(httpClient: UsageHTTPClient, now: @Sendable @escaping () -> Date = { Date() }) {
        self.httpClient = httpClient
        self.now = now
    }

    public func fetchUsage(
        credentials: OAuthCredentials,
        attributes: [String: String]
    ) async throws -> UsageResult {
        let status = try await fetchStatus(credentials: credentials, attributes: attributes)
        return UsageResult(
            windows: DevinUsageParser.windows(from: status),
            plan: status.plan,
            accountIdentity: status.userID ?? status.email)
    }

    public func profile(_ credentials: OAuthCredentials) async throws -> ProviderProfile {
        let token = Self.sessionToken(credentials.accessToken)
        var profilePayload: [String: Any] = [:]
        var profileFailure: Error?
        do {
            let response = try await ProviderHTTP.request(
                httpClient,
                url: ProviderEndpoints.Devin.profileURL,
                headers: ["Authorization": "Bearer \(token)", "Accept": "application/json"],
                endpoint: "devin profile")
            profilePayload = try ProviderHTTP.decodeObject(response.body, endpoint: "devin profile")
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // The status RPC carries the same identity fields; use it as the CLI does when the
            // profile endpoint is unavailable (for example while an account is provisioning).
            profileFailure = error
        }

        var status: DevinUserStatus?
        do {
            status = try await fetchStatus(credentials: credentials, attributes: [:])
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if profilePayload.isEmpty, let profileFailure { throw profileFailure }
        }

        let rawAccountID = JSONSupport.string(profilePayload, "user_id", "userId", "id")
            ?? status?.userID
            ?? JSONSupport.string(profilePayload, "email")
            ?? status?.email
            ?? JSONSupport.string(profilePayload, "user_name", "userName", "name")
            ?? status?.userName
        let accountID = rawAccountID?.trimmingCharacters(in: .whitespacesAndNewlines)
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? Self.stableAccountID(token: token)
        var attributes: [String: String] = [:]
        let orgID = JSONSupport.string(profilePayload, "org_id", "orgId") ?? status?.orgID
        let teamID = JSONSupport.string(profilePayload, "team_id", "teamId") ?? status?.teamID
        if let orgID = orgID?.trimmingCharacters(in: .whitespacesAndNewlines), !orgID.isEmpty {
            attributes["org_id"] = orgID
        }
        if let teamID = teamID?.trimmingCharacters(in: .whitespacesAndNewlines), !teamID.isEmpty {
            attributes["team_id"] = teamID
        }
        return ProviderProfile(
            externalAccountID: accountID,
            email: JSONSupport.string(profilePayload, "email") ?? status?.email,
            displayName: JSONSupport.string(profilePayload, "user_name", "userName", "name")
                ?? status?.userName,
            plan: status?.plan,
            attributes: attributes)
    }

    /// `/v3/self` can briefly omit identity while a new seat is provisioning. Keep a stable,
    /// one-way account key so that the first successful quota read can still create the account.
    private static func stableAccountID(token: String) -> String {
        let digest = SHA256.hash(Array(token.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return "devin-" + digest
    }

    private func fetchStatus(
        credentials: OAuthCredentials,
        attributes: [String: String]
    ) async throws -> DevinUserStatus {
        let token = Self.sessionToken(credentials.accessToken)
        let fingerprint = attributes["device_fingerprint"] ??
            DevinUsageParser.deviceFingerprintForSeed(token)
        let body = DevinUsageParser.requestData(sessionToken: token, deviceFingerprint: fingerprint)
        do {
            let response = try await ProviderHTTP.request(
                httpClient,
                url: ProviderEndpoints.Devin.userStatusURL,
                method: "POST",
                headers: [
                    "Authorization": "Basic \(token)-\(token)",
                    "Accept": "*/*",
                    "Content-Type": "application/proto",
                    "Connect-Protocol-Version": "1",
                    "User-Agent": ProviderEndpoints.Devin.userAgent,
                ],
                body: body,
                endpoint: "devin user status")
            return try DevinUsageParser.parse(response.data)
        } catch let error as ProviderError {
            // CPAMC deployments expose the same Connect-RPC method as JSON. The binary route is
            // the official CLI contract; retrying with CPAMC's metadata envelope is safe because
            // GetUserStatus is read-only and keeps the iOS client compatible with both servers.
            switch error {
            case .malformedPayload, .noData:
                break
            default:
                throw error
            }
            return try await fetchStatusJSON(token: token)
        }
    }

    private func fetchStatusJSON(token: String) async throws -> DevinUserStatus {
        let body: [String: Any] = [
            "metadata": [
                "ideName": ProviderEndpoints.Devin.ideName,
                "ideVersion": ProviderEndpoints.Devin.ideVersion,
                "apiKey": token,
                "locale": ProviderEndpoints.Devin.locale,
                "os": "ios",
                "extensionVersion": ProviderEndpoints.Devin.ideVersion,
                "clientName": ProviderEndpoints.Devin.ideName,
            ],
        ]
        let data = try JSONSerialization.data(withJSONObject: body, options: [])
        let response = try await ProviderHTTP.request(
            httpClient,
            url: ProviderEndpoints.Devin.userStatusURL,
            method: "POST",
            headers: [
                "Authorization": "Basic \(token)-\(token)",
                "Accept": "application/json",
                "Content-Type": "application/json",
                "Connect-Protocol-Version": "1",
                "User-Agent": ProviderEndpoints.Devin.userAgent,
            ],
            body: data,
            endpoint: "devin user status")
        return try DevinUsageParser.parse(response.data)
    }

    /// The CLI prefixes raw JWT session tokens before using the Basic Connect-RPC credential.
    public static func sessionToken(_ token: String) -> String {
        guard !token.hasPrefix(ProviderEndpoints.Devin.sessionTokenPrefix),
              token.hasPrefix("eyJ") else { return token }
        return ProviderEndpoints.Devin.sessionTokenPrefix + token
    }
}
