import XCTest
@testable import UsageLimitsKit

/// The account headline, proven against the vector file `HeadlineVectorsTest.kt` reads too.
///
/// Android and iOS share behaviour, not code, so the only thing that keeps the two headline
/// functions answering the same question the same way is a table both run. The file lives once,
/// outside either platform, and is found relative to this source file rather than shipped as a
/// SwiftPM resource, so `swift test` reads the same bytes on Linux and macOS.
///
/// The loader is strict on purpose. `JSONDecoder` skips keys it does not know, so a renamed key
/// would quietly decode as missing and leave this suite green while proving nothing; every
/// object here must carry exactly the keys it declares.
final class HeadlineVectorsTests: XCTestCase {

    private struct VectorFile: Decodable {
        let description: String
        let vectors: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            description = try c.decode(String.self, forKey: Keys("description"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let fetchedAt: Date?
        let now: Date
        let windows: [Window]
        let expected: String?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "fetchedAt", "now", "windows", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            fetchedAt = try c.decodeIfPresent(Int64.self, forKey: Keys("fetchedAt")).map(millis)
            now = millis(try c.decode(Int64.self, forKey: Keys("now")))
            windows = try c.decode([Window].self, forKey: Keys("windows"))
            expected = try c.decodeIfPresent(String.self, forKey: Keys("expected"))
        }
    }

    private struct Window: Decodable {
        let id: String
        let scope: UsageScope
        let remainingPercent: Double?
        let exhausted: Bool
        let resetAt: Date?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "scope", "remainingPercent", "exhausted", "resetAt"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            scope = try c.decode(UsageScope.self, forKey: Keys("scope"))
            remainingPercent = try c.decodeIfPresent(Double.self, forKey: Keys("remainingPercent"))
            exhausted = try c.decode(Bool.self, forKey: Keys("exhausted"))
            resetAt = try c.decodeIfPresent(Int64.self, forKey: Keys("resetAt")).map(millis)
        }

        var usageWindow: UsageWindow {
            UsageWindow(id: id, label: id, category: .other, usedPercent: remainingPercent.map { 100 - $0 },
                        periodSeconds: nil, resetAt: resetAt, exhausted: exhausted, scope: scope)
        }

        /// The same window as the widget export carries it. A row has no id, so the label holds it.
        var glanceRow: GlanceRow {
            let window = usageWindow
            return GlanceRow(label: id, category: .other, remainingPercent: window.remainingPercent,
                             resetAt: resetAt, severity: window.severity, scope: scope)
        }
    }

    private static func parse(_ data: Data) throws -> [Vector] {
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).vectors
        guard !vectors.isEmpty else { throw VectorFileError(description: "the headline vector file holds no vectors") }
        return vectors
    }

    private static let file = SharedVectors.file("account-headline.json")

    func testEverySharedVectorPicksTheExpectedHeadline() throws {
        for vector in try Self.parse(Data(contentsOf: Self.file)) {
            let windows = vector.windows.map(\.usageWindow)
            XCTAssertEqual(windows.headline(fetchedAt: vector.fetchedAt, at: vector.now)?.id, vector.expected, vector.name)

            // The widget export chooses among rows, not windows, and must land on the same one.
            let account = GlanceAccount(id: "a", title: "A", subtitle: nil, rows: [], severity: .healthy,
                                        candidates: vector.windows.map(\.glanceRow), fetchedAt: vector.fetchedAt)
            XCTAssertEqual(account.representative(at: vector.now)?.label, vector.expected, vector.name)

            // The snapshot always knows its fetch time; where the vector does too, its own function
            // must give the same answer as the rule it delegates to.
            if let fetchedAt = vector.fetchedAt {
                let snapshot = UsageSnapshot(accountID: "a", fetchedAt: fetchedAt, status: .ok, windows: windows)
                XCTAssertEqual(snapshot.headline(at: vector.now)?.id, vector.expected, vector.name)
            }
        }
    }

    /// Not a count to keep in step with the file: a floor, so a file truncated by a bad merge
    /// cannot pass as a smaller suite.
    func testTheVectorFileCoversEveryCaseTheRuleNames() throws {
        XCTAssertGreaterThanOrEqual(try Self.parse(Data(contentsOf: Self.file)).count, 9)
    }

    func testARenamedKeyFailsTheSuiteInsteadOfSkippingTheVector() {
        let renamed = """
            {"description": "", "vectors": [{"name": "x", "fetchedAt": null, "now": 0,
              "windows": [{"id": "a", "scope": "global", "remaining": 5, "exhausted": false, "resetAt": null}],
              "expected": "a"}]}
            """
        XCTAssertThrowsError(try Self.parse(Data(renamed.utf8)))
    }

    func testAnEmptyVectorListFailsTheSuite() {
        XCTAssertThrowsError(try Self.parse(Data(#"{"description": "", "vectors": []}"#.utf8)))
    }
}

private func millis(_ value: Int64) -> Date {
    Date(timeIntervalSince1970: TimeInterval(value) / 1000)
}
