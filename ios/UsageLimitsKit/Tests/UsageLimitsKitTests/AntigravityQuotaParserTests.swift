import XCTest
@testable import UsageLimitsKit
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class AntigravityQuotaRobustnessTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func parse(_ json: String) throws -> [UsageWindow] {
        let payload = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        return AntigravityQuotaParser.parse(payload, now: now)
    }

    func testGroupAndBucketAliasesRetainGeminiLimits() throws {
        for groupKey in ["groups", "quotaGroups", "quota_groups"] {
            for bucketKey in ["buckets", "quotaBuckets", "quota_buckets"] {
                let windows = try parse("""
                { "\(groupKey)": [{ "displayName": "Gemini Models", "\(bucketKey)": [
                    { "bucketId": "gemini-5h", "window": "5h", "remainingFraction": 0.5 },
                    { "bucketId": "gemini-weekly", "window": "weekly", "remainingFraction": 0.8 }
                ] }] }
                """)
                XCTAssertEqual(windows.map(\.id), ["gemini-5h", "gemini-weekly"])
                XCTAssertEqual(windows.first?.usedPercent, 50)
                XCTAssertEqual(windows.last?.category, .weekly)
            }
        }
    }

    func testGroupWithQuotaFieldsIsASingleBucket() throws {
        let windows = try parse("""
        { "groups": [{ "displayName": "Gemini Models", "window": "weekly", "remainingFraction": 0.25 }] }
        """)
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows.first?.id, "gemini-models-weekly")
        XCTAssertEqual(windows.first?.category, .weekly)
        XCTAssertEqual(windows.first?.usedPercent, 75)
    }

    func testNamedGroupsWithoutABucketSchemaRemainUnknown() throws {
        let windows = try parse("""
        { "groups": [{ "displayName": "Gemini Models" }] }
        """)
        XCTAssertEqual(windows.count, 1)
        let window = try XCTUnwrap(windows.first)
        XCTAssertEqual(window.id, "gemini-models-unknown")
        XCTAssertEqual(window.label, "Gemini Models")
        XCTAssertEqual(window.group, "Gemini Models")
        XCTAssertEqual(window.category, .other)
        XCTAssertNil(window.usedPercent)
        XCTAssertFalse(window.exhausted)
    }

    func testExplicitEmptyOrUnusableBucketsDoNotInventUnknownGroups() throws {
        for key in ["buckets", "quotaBuckets", "quota_buckets"] {
            for buckets in ["[]", "[null, 42]", "null", "{}"] {
                XCTAssertTrue(try parse("""
                { "groups": [{ "displayName": "Gemini Models", "\(key)": \(buckets) }] }
                """).isEmpty)
            }
        }
    }

    private actor Transport: HTTPTransport {
        let replies: [String: (Int, String)]
        private(set) var urls: [String] = []
        init(_ replies: [(Int, String)]) {
            self.replies = Dictionary(uniqueKeysWithValues: zip(ProviderEndpoints.Antigravity.quotaURLs, replies))
        }
        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let url = request.url!
            urls.append(url.absoluteString)
            let (status, body) = replies[url.absoluteString]!
            if status == 0 { throw URLError(.timedOut) }
            return (Data(body.utf8), HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!)
        }
    }

    private func group(_ id: String, _ name: String, fraction: Double = 0.5) -> String {
        """
        { "displayName": "\(name)", "buckets": [
            { "bucketId": "\(id)-5h", "window": "5h", "remainingFraction": \(fraction) },
            { "bucketId": "\(id)-weekly", "window": "weekly", "remainingFraction": \(fraction) }
        ] }
        """
    }

    private func fetch(_ replies: [(Int, String)]) async throws -> (UsageResult, [String]) {
        let transport = Transport(replies)
        let client = AntigravityClient(httpClient: UsageHTTPClient(transport: transport, maxRetries: 0))
        let result = try await client.fetchUsage(
            credentials: OAuthCredentials(accessToken: "synthetic"), attributes: ["project_id": "synthetic-project"])
        return (result, await transport.urls)
    }

    func testAllHostsContributeAndFirstMeasurementWins() async throws {
        let (result, urls) = try await fetch([
            (200, "{\"groups\":[\(group("3p", "Claude and GPT models"))]}"),
            (200, "{}"),
            (200, "{\"groups\":[\(group("gemini", "Gemini Models")),\(group("3p", "Claude and GPT models", fraction: 0.9))]}"),
        ])
        XCTAssertEqual(urls, ProviderEndpoints.Antigravity.quotaURLs)
        XCTAssertEqual(result.windows.map(\.id), ["3p-5h", "3p-weekly", "gemini-5h", "gemini-weekly"])
        XCTAssertEqual(result.windows.first?.usedPercent, 50)
        XCTAssertEqual(result.status, .ok)
    }

    func testFailuresBeforeOrAfterDataPreserveSuccess() async throws {
        for replies in [
            [(403, "{}"), (401, "{}"), (200, "{\"groups\":[\(group("gemini", "Gemini Models"))]}")],
            [(200, "{\"groups\":[\(group("gemini", "Gemini Models"))]}"), (403, "{}"), (401, "{}")],
        ] {
            let (result, urls) = try await fetch(replies)
            XCTAssertEqual(urls, ProviderEndpoints.Antigravity.quotaURLs)
            XCTAssertEqual(result.windows.map(\.id), ["gemini-5h", "gemini-weekly"])
            XCTAssertEqual(result.status, .ok)
            XCTAssertNil(result.errorMessage)
        }
    }

    func testPermanentRejectionsInEitherHostOrderDoNotMarkSuccessfulUnionPartial() async throws {
        let real = (200, "{\"groups\":[\(group("gemini", "Gemini Models"))]}")
        for code in [401, 403, 404] {
            for replies in [[(code, "{}"), real, (200, "{}")], [real, (200, "{}"), (code, "{}")]] {
                let (result, _) = try await fetch(replies)
                XCTAssertEqual(result.windows.count, 2)
                XCTAssertEqual(result.status, .ok)
                XCTAssertNil(result.errorMessage)
            }
        }
    }

    func testTransientFailuresInEitherHostOrderMarkSuccessfulUnionPartial() async throws {
        let real = (200, "{\"groups\":[\(group("gemini", "Gemini Models"))]}")
        for code in [0, 429, 503] {
            for replies in [[(code, "{}"), real, (404, "{}")], [real, (404, "{}"), (code, "{}")]] {
                let (result, _) = try await fetch(replies)
                XCTAssertEqual(result.windows.count, 2)
                XCTAssertEqual(result.status, .partial)
                XCTAssertNotNil(result.errorMessage)
            }
        }
    }

    func testRealUnknownWindowSurvivesPlaceholderReplacementInEitherHostOrder() async throws {
        let placeholder = #"{"groups":[{"displayName":"Gemini Models"}]}"#
        let real = """
        {"groups":[{"displayName":"Gemini Models","buckets":[
            {"window":"unknown","remainingFraction":0.25},
            {"window":"weekly","remainingFraction":0.5}
        ]}]}
        """
        for bodies in [[placeholder, real, "{}"], [real, placeholder, "{}"]] {
            let (result, _) = try await fetch(bodies.map { (200, $0) })
            XCTAssertEqual(Set(result.windows.map(\.id)), ["gemini-models-unknown", "gemini-models-weekly"])
            XCTAssertEqual(result.windows.count, 2)
            XCTAssertEqual(result.windows.first { $0.id == "gemini-models-unknown" }?.usedPercent, 75)
        }
    }

    func testAllFailedHostsKeepAuthErrorPriority() async {
        for codes in [[503, 401, 404], [403, 503, 404]] {
            do {
                _ = try await fetch(codes.map { ($0, "{}") })
                XCTFail("expected auth rejection")
            } catch ProviderError.unauthorised {
            } catch {
                XCTFail("unexpected error: \(error)")
            }
        }
    }

    func testEmptyBucketsAndRealHostsYieldOnlyRealWindows() async throws {
        let empty = #"{"groups":[{"displayName":"Gemini Models","buckets":[]}]}"#
        let real = "{\"groups\":[\(group("gemini", "Gemini Models"))]}"
        let (result, _) = try await fetch([(200, empty), (200, real), (200, empty)])
        XCTAssertEqual(result.windows.map(\.id), ["gemini-5h", "gemini-weekly"])
        XCTAssertEqual(result.status, .ok)
    }

    func testRealWindowsReplaceUnknownSchemaRowsInEitherHostOrder() async throws {
        let unknown = #"{"groups":[{"displayName":"Gemini Models"}]}"#
        let real = "{\"groups\":[\(group("gemini", "Gemini Models"))]}"
        for bodies in [[unknown, real, "{}"], [real, unknown, "{}"]] {
            let (result, _) = try await fetch(bodies.map { (200, $0) })
            XCTAssertEqual(result.windows.map(\.id), ["gemini-5h", "gemini-weekly"])
        }
    }

    func testAllEmptyBucketHostsStillThrowNoData() async {
        let empty = #"{"groups":[{"displayName":"Gemini Models","buckets":[]}]}"#
        do {
            _ = try await fetch(Array(repeating: (200, empty), count: 3))
            XCTFail("expected no quota groups error")
        } catch ProviderError.noData(let message) {
            XCTAssertEqual(message, "every quota host returned an empty summary")
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }
}
