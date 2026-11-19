import XCTest
import UsageLimitsKit

final class OverviewOrderVectorsTests: XCTestCase {
    func testOverviewAndConfiguredAllAccountsFollowSharedOrder() throws {
        let data = try Data(contentsOf: SharedVectors.file("overview-order.json"))
        let file = try SharedVectors.parse(VectorFile.self, from: data)
        XCTAssertFalse(file.vectors.isEmpty)

        for vector in file.vectors {
            let now = date(vector.now)
            let accounts = vector.accounts.map(\.usage)
            for mode in [PercentDisplay.left, .used] {
                let context = "\(vector.name), \(mode)"
                let cache = GlanceModel.build(accounts, now: now, scope: .allAccounts, percentDisplay: mode)
                XCTAssertEqual(cache.overviewOrderedAccounts(at: now).map(\.id), vector.expected.automatic, "Overview: \(context)")
                let published = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(cache))
                XCTAssertEqual(published.accounts.map(\.id), vector.expected.stored, "Export: \(context)")
                let request = ConfiguredWidgetCapture(source: .loaded(published), preset: .notRead,
                    scope: .allAccounts, accountID: nil, providerID: nil, transparent: false)
                XCTAssertEqual(request.selectedSnapshot(at: now).accounts.map(\.id), vector.expected.automatic,
                    "All accounts: \(context)")
                XCTAssertEqual(published.selecting(scope: .provider, now: now, providerID: ProviderID.codex.rawValue)
                    .accounts.map(\.id), vector.expected.stored, "Provider: \(context)")
                let custom = ConfiguredWidgetCapture(source: .loaded(published), preset: .matched(accountIDs: vector.custom),
                    scope: .custom, accountID: nil, providerID: nil, transparent: false)
                XCTAssertEqual(custom.selectedSnapshot(at: now).accounts.map(\.id), vector.expected.custom, "Custom: \(context)")
            }
        }
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let now: Int64
        let accounts: [Account]
        let custom: [String]
        let expected: Expected

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "now", "accounts", "custom", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            now = try c.decode(Int64.self, forKey: Keys("now"))
            accounts = try c.decode([Account].self, forKey: Keys("accounts"))
            custom = try c.decode([String].self, forKey: Keys("custom"))
            expected = try c.decode(Expected.self, forKey: Keys("expected"))
        }
    }

    private struct Expected: Decodable {
        let automatic: [String]
        let stored: [String]
        let custom: [String]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["automatic", "stored", "custom"])
            let c = try decoder.container(keyedBy: Keys.self)
            automatic = try c.decode([String].self, forKey: Keys("automatic"))
            stored = try c.decode([String].self, forKey: Keys("stored"))
            custom = try c.decode([String].self, forKey: Keys("custom"))
        }
    }

    private struct Account: Decodable {
        let id: String
        let createdAt: Int64
        let connection: ConnectionStatus
        let reading: Reading?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "createdAt", "connection", "reading"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            createdAt = try c.decode(Int64.self, forKey: Keys("createdAt"))
            connection = try c.decode(ConnectionStatus.self, forKey: Keys("connection"))
            reading = try c.decodeIfPresent(Reading.self, forKey: Keys("reading"))
        }

        var usage: AccountUsage {
            AccountUsage(account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
                email: nil, displayName: nil, plan: nil, credentialReference: "",
                createdAt: date(createdAt), lastSuccessfulSync: nil), snapshot: reading.map {
                    UsageSnapshot(accountID: id, fetchedAt: date($0.fetchedAt), status: $0.status,
                        windows: $0.windows.map(\.window), connectionStatus: connection)
                })
        }
    }

    private struct Reading: Decodable {
        let fetchedAt: Int64
        let status: SnapshotStatus
        let windows: [Window]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["fetchedAt", "status", "windows"])
            let c = try decoder.container(keyedBy: Keys.self)
            fetchedAt = try c.decode(Int64.self, forKey: Keys("fetchedAt"))
            status = try c.decode(SnapshotStatus.self, forKey: Keys("status"))
            windows = try c.decode([Window].self, forKey: Keys("windows"))
        }
    }

    private struct Window: Decodable {
        let window: UsageWindow

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "scope", "remainingPercent", "exhausted", "resetAt"])
            let c = try decoder.container(keyedBy: Keys.self)
            let id = try c.decode(String.self, forKey: Keys("id"))
            window = UsageWindow(id: id, label: id, category: .other,
                usedPercent: try c.decodeIfPresent(Double.self, forKey: Keys("remainingPercent")).map { 100 - $0 },
                periodSeconds: nil, resetAt: try c.decodeIfPresent(Int64.self, forKey: Keys("resetAt")).map(date),
                exhausted: try c.decode(Bool.self, forKey: Keys("exhausted")),
                scope: try c.decode(UsageScope.self, forKey: Keys("scope")))
        }
    }
}

private func date(_ milliseconds: Int64) -> Date {
    Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
}
