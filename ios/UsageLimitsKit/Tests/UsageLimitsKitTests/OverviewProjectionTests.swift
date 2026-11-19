import XCTest
@testable import UsageLimitsKit

final class OverviewProjectionTests: XCTestCase {
    func testSharedOverviewFilterAndHeaderAnswers() async throws {
        let fixture = try Data(contentsOf: SharedVectors.file("overview-filters-header.json"))
        let vectors = try parse(fixture)
        for vector in vectors {
            let zone = try XCTUnwrap(TimeZone(identifier: vector.zone), vector.name)
            var usage: [AccountUsage] = []
            for account in vector.accounts {
                usage.append(try await account.usageForProjection())
            }
            for mode in [PercentDisplay.left, .used] {
                let snapshot = GlanceModel.build(usage, now: vector.now,
                    scope: .allAccounts, staleAfter: vector.staleAfter, percentDisplay: mode)
                for filter in OverviewFilter.allCases {
                    let overview = snapshot.overview(filter: filter, at: vector.now, in: zone)
                    let context = "\(vector.name), \(filter), \(mode)"
                    XCTAssertEqual(overview.accounts.map { $0.account.id }, vector.expected.visible[filter], context)
                    XCTAssertEqual(overview.attentionCount, vector.expected.attentionCount, context)
                    XCTAssertEqual(overview.header.kind, vector.expected.kind, context)
                    XCTAssertEqual(overview.header.resetAt, vector.expected.resetAt, context)
                }
            }
        }
    }

    func testTheScheduledResetChangesOverviewAndWidgetOnTheSameBoundary() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let boundary = now.addingTimeInterval(0.125)
        let short = UsageWindow(id: "short", label: "5-hour", category: .fiveHour, usedPercent: 99,
            periodSeconds: 18000, resetAt: boundary, exhausted: false)
        let ample = UsageWindow(id: "long", label: "Weekly", category: .weekly, usedPercent: 5,
            periodSeconds: 604800, resetAt: nil, exhausted: false)
        let other = UsageWindow(id: "other", label: "Weekly", category: .weekly, usedPercent: 60,
            periodSeconds: 604800, resetAt: nil, exhausted: false)
        let a = usage("a", now: now, windows: [short, ample])
        let b = usage("b", now: now, windows: [other])
        let snapshot = GlanceModel.build([b, a], now: now, scope: .allAccounts)
        let zone = try XCTUnwrap(TimeZone(identifier: "Etc/UTC"))
        let due = snapshot.nextOverviewUpdate(after: now, in: zone)
        XCTAssertEqual(due, boundary)
        let file = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(snapshot))
        let widget = ConfiguredWidgetCapture(source: .loaded(file), preset: .notRead,
            scope: .allAccounts, accountID: nil, providerID: nil, transparent: false)
        for (date, expected) in [(now, ["a", "b"]), (due, ["b", "a"])] {
            XCTAssertEqual(snapshot.overview(filter: .all, at: date, in: zone).accounts.map { $0.account.id }, expected)
            XCTAssertEqual(widget.selectedSnapshot(at: date).accounts.map(\.id), expected)
        }
        XCTAssertEqual(snapshot.overview(filter: .resetsToday, at: now, in: zone).accounts.map { $0.account.id }, ["a"])
        XCTAssertEqual(snapshot.overview(filter: .resetsToday, at: due, in: zone).accounts.map { $0.account.id }, [])
    }

    func testAnEmptyRegisterSchedulesTheActualNextDayStart() throws {
        let now = ms(1788660000000)
        let zone = try XCTUnwrap(TimeZone(identifier: "America/Santiago"))
        XCTAssertEqual(GlanceSnapshot.empty.nextOverviewUpdate(after: now, in: zone), ms(1788667200000))
    }

    func testStrictReaderRejectsMissingAndEmptyVectors() {
        for invalid in [#"{"description":"","schema":"","vectors":[]}"#, #"{"description":"","vectors":[]}"#] {
            XCTAssertThrowsError(try parse(Data(invalid.utf8)))
        }
    }

    private func parse(_ data: Data) throws -> [Vector] {
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).vectors
        guard !vectors.isEmpty else { throw VectorFileError(description: "no Overview vectors") }
        return vectors
    }

    private func usage(_ id: String, now: Date, windows: [UsageWindow]) -> AccountUsage {
        AccountUsage(account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
            email: nil, displayName: nil, plan: nil, credentialReference: "", createdAt: now,
            lastSuccessfulSync: now), snapshot: UsageSnapshot(accountID: id, fetchedAt: now,
                status: .ok, windows: windows))
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
    let now: Date
    let zone: String
    let staleAfter: TimeInterval
    let accounts: [Account]
    let expected: Expected

    init(from decoder: Decoder) throws {
        try requireExactKeys(decoder, ["name", "zone", "zoneRule", "now", "staleAfterMs", "accounts", "expected"])
        let c = try decoder.container(keyedBy: Keys.self)
        name = try c.decode(String.self, forKey: Keys("name"))
        zone = try c.decode(String.self, forKey: Keys("zone"))
        _ = try c.decodeIfPresent(String.self, forKey: Keys("zoneRule"))
        now = ms(try c.decode(Int64.self, forKey: Keys("now")))
        staleAfter = TimeInterval(try c.decode(Int64.self, forKey: Keys("staleAfterMs"))) / 1000
        accounts = try c.decode([Account].self, forKey: Keys("accounts"))
        expected = try c.decode(Expected.self, forKey: Keys("expected"))
    }
}

private struct Expected: Decodable {
    let visible: [OverviewFilter: [String]]
    let attentionCount: Int
    let kind: OverviewHeader.Kind
    let resetAt: Date?

    init(from decoder: Decoder) throws {
        try requireExactKeys(decoder, ["visible", "attentionCount", "header"])
        let c = try decoder.container(keyedBy: Keys.self)
        attentionCount = try c.decode(Int.self, forKey: Keys("attentionCount"))
        let filters = try c.superDecoder(forKey: Keys("visible"))
        try requireExactKeys(filters, Set(OverviewFilter.allCases.map(\.rawValue)))
        let outputs = try filters.container(keyedBy: Keys.self)
        visible = try Dictionary(uniqueKeysWithValues: OverviewFilter.allCases.map {
            ($0, try outputs.decode([String].self, forKey: Keys($0.rawValue)))
        })
        let header = try c.superDecoder(forKey: Keys("header"))
        try requireExactKeys(header, ["kind", "resetAt"])
        let h = try header.container(keyedBy: Keys.self)
        kind = try h.decode(OverviewHeader.Kind.self, forKey: Keys("kind"))
        resetAt = try h.decodeIfPresent(Int64.self, forKey: Keys("resetAt")).map(ms)
    }
}

private struct Account: Decodable {
    let usage: AccountUsage
    let pauseUntil: Date?

    func usageForProjection() async throws -> AccountUsage {
        guard let pauseUntil else { return usage }
        let reading = try XCTUnwrap(usage.snapshot)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(usage.account)
        let result = UsageResult(windows: reading.windows, status: reading.status,
            errorMessage: StoredMessage.rateLimitedPauseMessage, connectionStatus: reading.connectionStatus,
            rateLimit: .init(retryAfter: pauseUntil.timeIntervalSince(reading.fetchedAt)))
        try await repository.record(.success(accountID: usage.account.id, result: result), at: reading.fetchedAt)
        let reopened = AccountRepository(directory: directory)
        let deadline = await RepositorySink(repository: reopened).automaticSyncNotBefore(accountID: usage.account.id)
        XCTAssertEqual(deadline, pauseUntil.timeIntervalSince1970, usage.account.id)
        let saved = await reopened.usage()
        return try XCTUnwrap(saved.first)
    }

    init(from decoder: Decoder) throws {
        try requireExactKeys(decoder, ["id", "createdAt", "connection", "pauseUntil", "reading"])
        let c = try decoder.container(keyedBy: Keys.self)
        let id = try c.decode(String.self, forKey: Keys("id"))
        let connection = try c.decode(ConnectionStatus.self, forKey: Keys("connection"))
        pauseUntil = try c.decodeIfPresent(Int64.self, forKey: Keys("pauseUntil")).map(ms)
        let account = ProviderAccount(id: id, provider: .codex, externalAccountID: id, email: nil,
            displayName: nil, plan: nil, credentialReference: "", createdAt: ms(try c.decode(Int64.self,
                forKey: Keys("createdAt"))), lastSuccessfulSync: nil)
        let snapshot: UsageSnapshot?
        if try c.decodeNil(forKey: Keys("reading")) {
            snapshot = nil
        } else {
            let reading = try c.superDecoder(forKey: Keys("reading"))
            try requireExactKeys(reading, ["fetchedAt", "status", "windows"])
            let r = try reading.container(keyedBy: Keys.self)
            snapshot = UsageSnapshot(accountID: id, fetchedAt: ms(try r.decode(Int64.self, forKey: Keys("fetchedAt"))),
                status: try r.decode(SnapshotStatus.self, forKey: Keys("status")),
                windows: try r.decode([OverviewOrderVectorsTests.Window].self, forKey: Keys("windows")).map(\.window),
                connectionStatus: connection)
        }
        usage = AccountUsage(account: account, snapshot: snapshot)
    }
}

private func ms(_ value: Int64) -> Date {
    Date(timeIntervalSince1970: TimeInterval(value) / 1000)
}
