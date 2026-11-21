import XCTest
@testable import UsageLimitsKit

final class RetainedQuotaMarkTests: XCTestCase {
    func testFailedRetainedWindowsKeepLimitedMarkWithoutChangingHeadlineValidity() throws {
        let file = try SharedVectors.parse(VectorFile.self,
            from: Data(contentsOf: SharedVectors.file("retained-quota-mark.json")))
        XCTAssertFalse(file.vectors.isEmpty)
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        for vector in file.vectors {
            let windows = vector.windows.map {
                UsageWindow(id: $0.id, label: $0.id, category: .weekly,
                    usedPercent: $0.remainingPercent.map { 100 - $0 }, periodSeconds: nil,
                    resetAt: nil, exhausted: $0.exhausted, scope: $0.scope)
            }
            let snapshot = UsageSnapshot(accountID: "account", fetchedAt: now,
                status: vector.status, windows: windows)
            let account = ProviderAccount(id: "account", provider: .claude, externalAccountID: "account",
                email: nil, displayName: nil, plan: nil, credentialReference: "synthetic",
                createdAt: now, lastSuccessfulSync: now)
            let glance = try XCTUnwrap(GlanceModel.build([AccountUsage(account: account, snapshot: snapshot)],
                now: now, scope: .allAccounts).accounts.first)
            XCTAssertEqual(glance.quotaSeverity, vector.expectedMark, vector.name)
            XCTAssertEqual(glance.baseSeverity, .error, vector.name)
            XCTAssertEqual(glance.severity(at: now, staleAfter: 3600), .error, vector.name)
            let headline = glance.representative(at: now)
            XCTAssertEqual(headline?.sourceWindowID, vector.expectedHeadline, vector.name)
            XCTAssertEqual(glance.readingValidity(of: headline, at: now, staleAfter: 3600),
                vector.expectedValidity, vector.name)
        }
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }
    private struct Vector: Decodable {
        let name: String
        let status: SnapshotStatus
        let windows: [Window]
        let expectedMark: Severity
        let expectedValidity: ReadingValidity
        let expectedHeadline: String
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "status", "windows", "expectedMark", "expectedValidity", "expectedHeadline"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            status = try c.decode(SnapshotStatus.self, forKey: Keys("status"))
            windows = try c.decode([Window].self, forKey: Keys("windows"))
            guard try c.decode(String.self, forKey: Keys("expectedMark")) == "limited",
                  try c.decode(String.self, forKey: Keys("expectedValidity")) == "error" else {
                throw VectorFileError(description: "unknown mark or validity")
            }
            expectedMark = .limited
            expectedValidity = .error
            expectedHeadline = try c.decode(String.self, forKey: Keys("expectedHeadline"))
        }
    }
    private struct Window: Decodable {
        let id: String
        let scope: UsageScope
        let remainingPercent: Double?
        let exhausted: Bool
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "scope", "remainingPercent", "exhausted"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            scope = try c.decode(UsageScope.self, forKey: Keys("scope"))
            remainingPercent = try c.decodeIfPresent(Double.self, forKey: Keys("remainingPercent"))
            exhausted = try c.decode(Bool.self, forKey: Keys("exhausted"))
        }
    }
}
