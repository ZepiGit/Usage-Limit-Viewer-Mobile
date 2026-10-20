import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Kimi's China / International regions, and the two key-only providers Z.ai and OpenCode Go.
///
/// Every key and token is synthetic. Fixtures are the shapes recorded in
/// docs/providers-zai.md and docs/providers-opencode.md.
final class RegionalProvidersTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private actor Transport: HTTPTransport {
        private var replies: [(status: Int, body: String)]
        private(set) var requests: [URLRequest] = []

        init(_ replies: [(status: Int, body: String)]) { self.replies = replies }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            requests.append(request)
            let reply = replies.count > 1 ? replies.removeFirst() : (replies.first ?? (200, "{}"))
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(reply.body.utf8), response)
        }
    }

    private func client(_ replies: [(status: Int, body: String)]) -> (UsageHTTPClient, Transport) {
        let transport = Transport(replies)
        return (UsageHTTPClient(transport: transport, maxRetries: 0, now: { [now] in now }), transport)
    }

    private func payload(_ text: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
    }

    private func intl(_ token: String = "tok", refresh: String? = nil) -> OAuthCredentials {
        OAuthCredentials(accessToken: token, refreshToken: refresh, providerData: ["region": "intl"])
    }

    // MARK: - Kimi regions

    func testKimiHostsPerRegion() {
        XCTAssertEqual(ProviderEndpoints.Kimi.urls(.cn), ProviderEndpoints.Kimi.china)
        XCTAssertEqual(ProviderEndpoints.Kimi.urls(.intl), ProviderEndpoints.Kimi.international)
        XCTAssertEqual(ProviderEndpoints.Kimi.china.deviceCodeURL, "https://auth.kimi.com/api/oauth/device_authorization")
        XCTAssertEqual(ProviderEndpoints.Kimi.china.tokenURL, "https://auth.kimi.com/api/oauth/token")
        XCTAssertEqual(ProviderEndpoints.Kimi.china.usageEndpoint, "https://api.kimi.com/coding/v1/usages")
        XCTAssertEqual(ProviderEndpoints.Kimi.china.consoleURL, "https://www.kimi.com/code/console")
        XCTAssertEqual(ProviderEndpoints.Kimi.international.deviceCodeURL, "https://auth.kimi.ai/api/oauth/device_authorization")
        XCTAssertEqual(ProviderEndpoints.Kimi.international.tokenURL, "https://auth.kimi.ai/api/oauth/token")
        XCTAssertEqual(ProviderEndpoints.Kimi.international.usageEndpoint, "https://api.kimi.ai/coding/v1/usages")
        XCTAssertEqual(ProviderEndpoints.Kimi.international.consoleURL, "https://www.kimi.ai/code/console")
    }

    func testRegionDefaultsAndStoredLookup() {
        // No stored region: Kimi accounts predate the choice and are China; Z.ai leads international.
        let bare = OAuthCredentials(accessToken: "k")
        XCTAssertEqual(ProviderRegion.stored(provider: .kimi, credentials: bare), .cn)
        XCTAssertEqual(ProviderRegion.stored(provider: .zai, credentials: bare), .intl)
        // Credentials win over attributes; junk falls back to the default.
        XCTAssertEqual(ProviderRegion.stored(provider: .kimi, credentials: intl(), attributes: ["region": "cn"]), .intl)
        XCTAssertEqual(ProviderRegion.stored(provider: .kimi, credentials: bare, attributes: ["region": "intl"]), .intl)
        XCTAssertEqual(ProviderRegion.stored(provider: .kimi, credentials: OAuthCredentials(accessToken: "k", providerData: ["region": "mars"])), .cn)
        XCTAssertEqual(ProviderRegion.options(for: .kimi), [.cn, .intl])
        XCTAssertEqual(ProviderRegion.options(for: .zai), [.intl, .cn])
        XCTAssertTrue(ProviderRegion.options(for: .opencode).isEmpty)
    }

    func testKimiDeviceAuthAndTokenPollFollowTheChosenRegion() async throws {
        let device = #"{"device_code":"dev-1","user_code":"AB-CD","verification_uri":"https://www.kimi.ai/code/authorize_device","expires_in":900,"interval":5}"#
        let (http, transport) = client([
            (200, device),
            (200, #"{"access_token":"acc","refresh_token":"ref","expires_in":3600}"#),
        ])
        let login = KimiDeviceLogin(httpClient: http, now: { [now] in now }, region: .intl)
        let challenge = try await login.begin()
        XCTAssertEqual(challenge.continuation["region"], "intl")

        // `complete` follows the challenge, not the instance: a login started on .ai polls .ai.
        let credentials = try await KimiDeviceLogin(httpClient: http, now: { [now] in now }).complete(challenge)
        XCTAssertEqual(credentials.providerData["region"], "intl")
        let urls = await transport.requests.map { $0.url?.absoluteString }
        XCTAssertEqual(urls, [ProviderEndpoints.Kimi.international.deviceCodeURL, ProviderEndpoints.Kimi.international.tokenURL])
    }

    func testKimiDeviceLoginDefaultsToChinaAndRecordsIt() async throws {
        let (http, transport) = client([
            (200, #"{"device_code":"d","user_code":"U","verification_uri":"https://www.kimi.com/code/authorize_device"}"#),
            (200, #"{"access_token":"acc"}"#),
        ])
        let login = KimiDeviceLogin(httpClient: http, now: { [now] in now })
        let challenge = try await login.begin()
        let credentials = try await login.complete(challenge)
        XCTAssertEqual(credentials.providerData["region"], "cn")
        let urls = await transport.requests.map { $0.url?.absoluteString }
        XCTAssertEqual(urls, [ProviderEndpoints.Kimi.china.deviceCodeURL, ProviderEndpoints.Kimi.china.tokenURL])
    }

    func testKimiRefreshAndUsageReadTheRegionFromTheCredentials() async throws {
        let usage = #"{"usage":{"limit":100,"used":10}}"#
        let (http, transport) = client([
            (200, #"{"access_token":"acc2","expires_in":3600}"#), (200, usage), (200, usage),
            (200, usage),
        ])
        let kimi = KimiClient(httpClient: http)

        // refresh sees credentials only: the region must be in them.
        _ = try await kimi.refresh(credentials: intl(refresh: "ref"))
        _ = try await kimi.fetchUsage(credentials: intl(), attributes: [:])
        // legacy: no region anywhere -> China, exactly as before.
        _ = try await kimi.fetchUsage(credentials: OAuthCredentials(accessToken: "old"), attributes: [:])
        // attributes alone are enough for usage.
        _ = try await kimi.fetchUsage(credentials: OAuthCredentials(accessToken: "k"), attributes: ["region": "intl"])

        let urls = await transport.requests.map { $0.url?.absoluteString }
        XCTAssertEqual(urls, [
            ProviderEndpoints.Kimi.international.tokenURL,
            ProviderEndpoints.Kimi.international.usageEndpoint,
            ProviderEndpoints.Kimi.china.usageEndpoint,
            ProviderEndpoints.Kimi.international.usageEndpoint,
        ])
    }

    func testLegacyKimiRefreshStillGoesToChina() async throws {
        let (http, transport) = client([(200, #"{"access_token":"acc2"}"#)])
        _ = try await KimiClient(httpClient: http).refresh(
            credentials: OAuthCredentials(accessToken: "old", refreshToken: "ref"))
        let url = await transport.requests.first?.url?.absoluteString
        XCTAssertEqual(url, ProviderEndpoints.Kimi.china.tokenURL)
    }

    // MARK: - Z.ai

    static let zaiLite = #"""
    {"code":200,"msg":"Operation successful","data":{"limits":[
      {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":2000,"currentValue":402,"remaining":1597,"percentage":20,"nextResetTime":1788351145586},
      {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":10000,"currentValue":5207,"remaining":4792,"percentage":52,"nextResetTime":1788784466996}
    ],"level":"lite"},"success":true}
    """#

    func testZaiLiteCreditLimits() {
        let windows = ZaiUsageParser.parse(payload(Self.zaiLite))
        XCTAssertEqual(windows.map(\.label), ["5h limit", "Weekly"])
        XCTAssertEqual(windows.map(\.category), [.fiveHour, .weekly])
        XCTAssertEqual(windows.map(\.periodSeconds), [18_000, 604_800])
        XCTAssertEqual(windows[0].usedPercent, 20)
        XCTAssertEqual(windows[1].usedPercent, 52)
        XCTAssertEqual(windows[0].resetAt, Date(timeIntervalSince1970: 1_788_351_145.586))
        XCTAssertEqual(ZaiUsageParser.plan(payload(Self.zaiLite)), "Lite")
    }

    func testZaiMaxPlanPercentageOnlyAndMissingReset() {
        let windows = ZaiUsageParser.parse(payload(#"""
        {"data":{"limits":[
          {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":0},
          {"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":37,"nextResetTime":1788784466996}
        ],"level":"max"}}
        """#))
        XCTAssertEqual(windows.map(\.usedPercent), [0, 37])
        XCTAssertNil(windows[0].resetAt, "nextResetTime is absent at 0 %")
        XCTAssertNotNil(windows[1].resetAt)
    }

    func testZaiFallsBackToCurrentValueOverCapAndMonthlyTimeLimit() {
        let windows = ZaiUsageParser.parse(payload(#"""
        {"data":{"limits":[
          {"type":"TIME_LIMIT","unit":5,"number":1,"usage":1000,"currentValue":250,"nextResetTime":1788784466996},
          {"type":"TOKENS_LIMIT","unit":1,"number":1,"usage":0,"currentValue":5}
        ]}}
        """#))
        XCTAssertEqual(windows[0].category, .monthly)
        XCTAssertEqual(windows[0].periodSeconds, 2_592_000)
        XCTAssertEqual(windows[0].usedPercent, 25)
        // Day window, and a zero cap is "no limit stated", not a full bar.
        XCTAssertEqual(windows[1].periodSeconds, 86_400)
        XCTAssertNil(windows[1].usedPercent)
    }

    func testZaiInvalidFiguresAreUnknownNotFull() {
        let windows = ZaiUsageParser.parse(payload(#"""
        {"data":{"limits":[
          {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":140},
          {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":10,"currentValue":50},
          {"type":"TOKENS_LIMIT","unit":9,"number":2}
        ]}}
        """#))
        XCTAssertEqual(windows.count, 3)
        XCTAssertTrue(windows.allSatisfy { $0.usedPercent == nil && !$0.exhausted })
        XCTAssertEqual(windows[2].label, "Token limit")
        XCTAssertNil(windows[2].periodSeconds)
    }

    func testZaiClientUsesTheRegionHostAndBearerKey() async throws {
        let (http, transport) = client([(200, Self.zaiLite)])
        let zai = ZaiClient(httpClient: http)
        _ = try await zai.fetchUsage(credentials: OAuthCredentials(accessToken: "zk"), attributes: [:])
        _ = try await zai.fetchUsage(
            credentials: OAuthCredentials(accessToken: "zk", providerData: ["region": "cn"]), attributes: [:])
        let sent = await transport.requests
        XCTAssertEqual(sent.map { $0.url?.absoluteString }, [
            "https://api.z.ai/api/monitor/usage/quota/limit",
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit",
        ])
        XCTAssertEqual(sent[0].value(forHTTPHeaderField: "Authorization"), "Bearer zk")
        let result = try await zai.fetchUsage(credentials: OAuthCredentials(accessToken: "zk"), attributes: [:])
        XCTAssertEqual(result.plan, "Lite")
        XCTAssertNil(result.accountIdentity)
        let same = try await zai.refresh(credentials: OAuthCredentials(accessToken: "zk"))
        XCTAssertEqual(same.accessToken, "zk")
    }

    func testZaiRefusalInsideA200IsUnauthorised() async throws {
        let (http, _) = client([(200, #"{"code":401,"msg":"token expired","success":false}"#)])
        do {
            _ = try await ZaiClient(httpClient: http)
                .fetchUsage(credentials: OAuthCredentials(accessToken: "zk"), attributes: [:])
            XCTFail("a refused key must not read as usage")
        } catch ProviderError.unauthorised {}
    }

    // MARK: - OpenCode Go

    static let opencode = #"""
    {"usage":{"rolling":{"status":"ok","percent":98,"resetsAt":"2026-10-07T22:03:52.000Z"},
     "weekly":{"status":"ok","percent":39,"resetsAt":"2026-10-12T00:00:00.000Z"},
     "monthly":{"status":"ok","percent":19,"resetsAt":"2026-10-22T18:11:46.000Z"}}}
    """#

    func testOpenCodeWindows() {
        let windows = OpenCodeUsageParser.parse(payload(Self.opencode))
        XCTAssertEqual(windows.map(\.label), ["5h limit", "Weekly", "Monthly"])
        XCTAssertEqual(windows.map(\.category), [.fiveHour, .weekly, .monthly])
        XCTAssertEqual(windows.map(\.usedPercent), [98, 39, 19])
        XCTAssertEqual(windows[0].resetAt, JSONSupport.date("2026-10-07T22:03:52.000Z"))
        XCTAssertNotNil(windows[0].resetAt)
    }

    func testOpenCodeUnknownStatusOrMissingPercentIsUnknown() {
        let windows = OpenCodeUsageParser.parse(payload(#"""
        {"usage":{"rolling":{"status":"degraded","percent":50,"resetsAt":"2026-10-07T22:03:52.000Z"},
         "weekly":{"status":"ok","resetsAt":"2026-10-12T00:00:00.000Z"},
         "monthly":{"status":"ok","percent":120}}}
        """#))
        XCTAssertEqual(windows.count, 3)
        XCTAssertTrue(windows.allSatisfy { $0.usedPercent == nil && !$0.exhausted })
    }

    func testOpenCodeClientSendsBearerKey() async throws {
        let (http, transport) = client([(200, Self.opencode)])
        let result = try await OpenCodeClient(httpClient: http)
            .fetchUsage(credentials: OAuthCredentials(accessToken: "ock"), attributes: [:])
        XCTAssertEqual(result.windows.count, 3)
        let request = await transport.requests.first
        XCTAssertEqual(request?.url?.absoluteString, "https://opencode.ai/zen/go/v1/usage")
        XCTAssertEqual(request?.value(forHTTPHeaderField: "Authorization"), "Bearer ock")
    }

    // MARK: - Adding accounts

    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("regional-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: directory) }

    private func container(_ replies: [(status: Int, body: String)]) -> (UsageLimitsContainer, InMemoryCredentialStore) {
        let store = InMemoryCredentialStore()
        return (UsageLimitsContainer(
            directory: directory, credentials: store,
            transport: Transport(replies), now: { [now] in now }), store)
    }

    func testZaiKeyIsProvedStoredWithItsRegionAndNamedByDigest() async throws {
        let (subject, store) = container([(200, Self.zaiLite)])
        let account = try await subject.completePastedKeyLogin(provider: .zai, key: " zk-synthetic ", region: .cn)

        XCTAssertEqual(account.provider, .zai)
        // International is Z.ai's default region and keeps the bare digest; China is namespaced.
        XCTAssertEqual(account.externalAccountID, "cn:" + UsageLimitsContainer.pastedKeyIdentity("zk-synthetic"))
        XCTAssertFalse(account.externalAccountID.contains("zk-synthetic"))
        XCTAssertEqual(account.attributes["region"], "cn")
        XCTAssertEqual(account.plan, "Lite")
        let saved = try await store.load(reference: account.credentialReference)
        XCTAssertEqual(saved?.accessToken, "zk-synthetic")
        XCTAssertEqual(saved?.providerData["region"], "cn")
    }

    func testARejectedKeyCreatesNoAccount() async throws {
        let (subject, store) = container([(401, #"{"error":"nope"}"#)])
        do {
            _ = try await subject.completePastedKeyLogin(provider: .opencode, key: "bad-key")
            XCTFail("a key that cannot read usage must not become an account")
        } catch {}
        let accounts = await subject.usage()
        XCTAssertTrue(accounts.isEmpty)
        let references = try await store.allReferences()
        XCTAssertTrue(references.isEmpty)
    }

    func testOpenCodeKeyHasNoRegionAttribute() async throws {
        let (subject, _) = container([(200, Self.opencode)])
        let account = try await subject.completePastedKeyLogin(provider: .opencode, key: "ock-synthetic")
        XCTAssertNil(account.attributes["region"])
        XCTAssertEqual(account.externalAccountID, UsageLimitsContainer.pastedKeyIdentity("ock-synthetic"))
    }

    func testTheSameKimiIdInBothRegionsKeepsBothAccountsAndCredentials() async throws {
        let usage = #"{"user_id":"u1","usage":{"limit":100,"used":1}}"#
        let (subject, store) = container([(200, usage)])
        let china = try await subject.completePastedKeyLogin(provider: .kimi, key: "sk-cn", region: .cn)
        let abroad = try await subject.completePastedKeyLogin(provider: .kimi, key: "sk-intl", region: .intl)

        // China keeps the id it always had; International is namespaced.
        XCTAssertEqual(china.externalAccountID, "u1")
        XCTAssertEqual(abroad.externalAccountID, "intl:u1")
        let accounts = await subject.usage()
        XCTAssertEqual(accounts.count, 2)
        let savedChina = try await store.load(reference: china.credentialReference)
        let savedAbroad = try await store.load(reference: abroad.credentialReference)
        XCTAssertEqual(savedChina?.accessToken, "sk-cn")
        XCTAssertEqual(savedChina?.providerData["region"], "cn")
        XCTAssertEqual(savedAbroad?.accessToken, "sk-intl")
        XCTAssertEqual(savedAbroad?.providerData["region"], "intl")
    }

    func testTheSameKeyInBothRegionsKeepsBothAccounts() async throws {
        let (subject, store) = container([(200, #"{"usage":{"limit":100,"used":1}}"#)])
        let kimiCN = try await subject.completePastedKeyLogin(provider: .kimi, key: "sk-same", region: .cn)
        let kimiIntl = try await subject.completePastedKeyLogin(provider: .kimi, key: "sk-same", region: .intl)
        let digest = UsageLimitsContainer.pastedKeyIdentity("sk-same")
        XCTAssertEqual(kimiCN.externalAccountID, digest)
        XCTAssertEqual(kimiIntl.externalAccountID, "intl:" + digest)

        let accounts = await subject.usage()
        XCTAssertEqual(accounts.count, 2)
        let references = try await store.allReferences()
        XCTAssertEqual(references.count, 2)
    }

    func testTheSameZaiKeyInBothRegionsKeepsBothAccounts() async throws {
        let (zaiSubject, _) = container([(200, Self.zaiLite)])
        let zaiIntl = try await zaiSubject.completePastedKeyLogin(provider: .zai, key: "zk-same", region: .intl)
        let zaiCN = try await zaiSubject.completePastedKeyLogin(provider: .zai, key: "zk-same", region: .cn)
        let zaiDigest = UsageLimitsContainer.pastedKeyIdentity("zk-same")
        XCTAssertEqual(zaiIntl.externalAccountID, zaiDigest)
        XCTAssertEqual(zaiCN.externalAccountID, "cn:" + zaiDigest)
        let zaiAccounts = await zaiSubject.usage()
        XCTAssertEqual(zaiAccounts.count, 2)
    }

    func testKimiDeviceLoginNamespacesInternationalAndKeepsLegacyChina() async throws {
        let usage = #"{"user_id":"u1","usage":{"limit":100,"used":1}}"#
        let (http, _) = client([(200, usage)])
        let login = KimiDeviceLogin(httpClient: http, now: { [now] in now })

        let china = try await login.profile(OAuthCredentials(accessToken: "a", providerData: ["region": "cn"]))
        let legacy = try await login.profile(OAuthCredentials(accessToken: "a"))
        let abroad = try await login.profile(intl("a"))
        XCTAssertEqual(china.externalAccountID, "u1")
        XCTAssertEqual(legacy.externalAccountID, "u1")
        XCTAssertEqual(abroad.externalAccountID, "intl:u1")
    }

    func testKimiKeyDefaultsToChinaAndCanBeInternational() async throws {
        let usage = #"{"user_id":"u1","usage":{"limit":100,"used":1}}"#
        let (subject, store) = container([(200, usage)])
        let china = try await subject.completePastedKeyLogin(provider: .kimi, key: "sk-1")
        XCTAssertEqual(china.attributes["region"], "cn")

        let (other, otherStore) = container([(200, usage)])
        let abroad = try await other.completePastedKeyLogin(provider: .kimi, key: "sk-2", region: .intl)
        XCTAssertEqual(abroad.attributes["region"], "intl")
        let saved = try await otherStore.load(reference: abroad.credentialReference)
        XCTAssertEqual(saved?.providerData["region"], "intl")
        _ = store
    }
}
