import XCTest
@testable import UsageLimitsKit

final class ResetConfirmationTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }

    func testEverySharedVectorDrivesProductionCommitReopenAndClaim() async throws {
        let vectors = try Self.parse(Data(contentsOf: SharedVectors.file("reset-confirmation.json")))
        XCTAssertEqual(vectors.count, 24)
        for vector in vectors {
            let repository = AccountRepository(directory: directory)
            try await repository.upsert(account())
            if let old = vector.old { try await repository.record(.success(accountID: "claude", result: old.result()), at: old.date) }
            if !vector.sameAccount {
                try await repository.remove(id: "claude")
                try await repository.upsert(account())
            }
            let oldSnapshot = vector.old?.snapshot(accountID: vector.sameAccount ? "claude" : "removed")
            let fresh = vector.new.snapshot(accountID: "claude", status: vector.status)
            let confirmed = vector.outcome == "skipped" ? [] : fresh.confirmedResetWindowIDs(previous: oldSnapshot)
            XCTAssertEqual(confirmed, vector.expected.windowIds, vector.name)
            if vector.outcome != "skipped" {
                try await repository.record(.success(accountID: "claude", result: vector.new.result(status: vector.status)), at: vector.new.date)
            }
            let reopened = AccountRepository(directory: directory)
            let claimed = try await reopened.claimPendingResetConfirmations()
            XCTAssertEqual(claimed, vector.expected.confirmed ? ["claude"] : [], vector.name)
            let again = try await AccountRepository(directory: directory).claimPendingResetConfirmations()
            XCTAssertTrue(again.isEmpty, vector.name)
            try await reopened.remove(id: "claude")
        }
    }

    func testReaderRejectsEmptyMalformedAndUnstableVectors() throws {
        let valid = try Data(contentsOf: SharedVectors.file("reset-confirmation.json"))
        let text = try XCTUnwrap(String(data: valid, encoding: .utf8))
        for invalid in [
            text.replacingOccurrences(of: "\"measured\"", with: "\"fresh\""),
            text.replacingOccurrences(of: "\"id\":\"five-hour\"", with: "\"id\":\"\""),
            "{\"description\":\"test\",\"schema\":\"reset-confirmation-v1\",\"vectors\":[]}"
        ] { XCTAssertThrowsError(try Self.parse(Data(invalid.utf8))) }
    }

    func testCoalescingFailureReconnectAndPostClaimRestart() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await confirm(repository)
        try await repository.record(.success(accountID: "claude", result: result(reset: 6)), at: date(4.5))
        try await repository.record(.failure(accountID: "claude", message: "Offline"), at: date(4.7))
        try await repository.upsert(account())
        let reopened = AccountRepository(directory: directory)
        let claimed = try await reopened.claimPendingResetConfirmations()
        XCTAssertEqual(claimed, ["claude"])
        let postDeath = try await AccountRepository(directory: directory).claimPendingResetConfirmations()
        XCTAssertTrue(postDeath.isEmpty)
        try await reopened.remove(id: "claude")
        try await reopened.upsert(account())
        try await reopened.record(.success(accountID: "claude", result: result(reset: 8)), at: date(6.5))
        let replacement = try await reopened.claimPendingResetConfirmations()
        XCTAssertTrue(replacement.isEmpty)
    }

    func testFailedAtomicSnapshotWriteDoesNotAdvanceReadingOrMark() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "claude", result: result(reset: 2)), at: date(1))
        let saved = directory.appendingPathComponent("saved")
        try FileManager.default.moveItem(at: directory.appendingPathComponent("accounts.json"), to: saved)
        try FileManager.default.createDirectory(at: directory.appendingPathComponent("accounts.json"), withIntermediateDirectories: false)
        do {
            try await repository.record(.success(accountID: "claude", result: result(reset: 4)), at: date(2.5))
            XCTFail("a directory cannot be replaced by the register write")
        } catch {}
        let current = await repository.usage()
        XCTAssertEqual(current.first?.snapshot?.fetchedAt, date(1))
        let marks = try await repository.claimPendingResetConfirmations()
        XCTAssertTrue(marks.isEmpty)
    }

    func testFailedClaimKeepsTheCommittedMarkForRetry() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await confirm(repository)
        let file = directory.appendingPathComponent("accounts.json")
        let saved = directory.appendingPathComponent("saved")
        try FileManager.default.moveItem(at: file, to: saved)
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        do { _ = try await repository.claimPendingResetConfirmations(); XCTFail("claim must persist its clear") } catch {}
        try FileManager.default.removeItem(at: file)
        try FileManager.default.moveItem(at: saved, to: file)
        let claimed = try await repository.claimPendingResetConfirmations()
        XCTAssertEqual(claimed, ["claude"])
        let restart = try await AccountRepository(directory: directory).claimPendingResetConfirmations()
        XCTAssertTrue(restart.isEmpty)
    }

    func testLegacyRecordStartsUnmarkedAndNonClaudeNeverCheers() async throws {
        let old = """
        [{"account":{"id":"claude","provider":"claude","externalAccountID":"test",
          "credentialReference":"test","createdAt":"1970-01-01T00:00:00Z"}}]
        """
        try Data(old.utf8).write(to: directory.appendingPathComponent("accounts.json"))
        let repository = AccountRepository(directory: directory)
        let legacy = try await repository.claimPendingResetConfirmations()
        XCTAssertTrue(legacy.isEmpty)
        try await repository.upsert(account("codex", provider: .codex))
        for (time, reset) in [(1.0, 2.0), (2.5, 4.0)] {
            try await repository.record(.success(accountID: "codex", result: result(reset: reset)), at: date(time))
        }
        let marks = try await repository.claimPendingResetConfirmations()
        XCTAssertTrue(marks.isEmpty)
    }

    func testConcurrentAtomicClaimsReturnEachAccountOnlyOnce() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await confirm(repository)
        let gate = ClaimGate()
        let first = Task {
            let claimed = try await repository.claimPendingResetConfirmations()
            await gate.hold()
            return claimed
        }
        await gate.waitForEntry()
        do {
            let second = try await repository.claimPendingResetConfirmations()
            XCTAssertTrue(second.isEmpty, "the first claim has committed while its caller is still held")
        } catch {
            await gate.release()
            _ = try await first.value
            throw error
        }
        await gate.release()
        let firstClaim = try await first.value
        XCTAssertEqual(firstClaim, ["claude"])
    }

    private actor ClaimGate {
        private var entered = false
        private var arrival: CheckedContinuation<Void, Never>?
        private var held: CheckedContinuation<Void, Never>?
        func hold() async {
            entered = true
            arrival?.resume(); arrival = nil
            await withCheckedContinuation { held = $0 }
        }
        func waitForEntry() async {
            guard !entered else { return }
            await withCheckedContinuation { arrival = $0 }
        }
        func release() { held?.resume(); held = nil }
    }

    func testFailureKeepsOriginalDatedEvidenceButCannotItselfConfirm() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "claude", result: result(reset: 2)), at: date(1))
        try await repository.record(.failure(accountID: "claude", message: "Offline"), at: date(1.5))
        let retained = await repository.usage()
        XCTAssertEqual(retained.first?.snapshot?.windows.first?.measured, true)
        XCTAssertEqual(retained.first?.snapshot?.fetchedAt, date(1))
        let failedClaim = try await repository.claimPendingResetConfirmations()
        XCTAssertTrue(failedClaim.isEmpty)
        try await repository.record(.success(accountID: "claude", result: result(reset: 4)), at: date(2.5))
        let marks = try await repository.claimPendingResetConfirmations()
        XCTAssertEqual(marks, ["claude"])
    }

    private func account(_ id: String = "claude", provider: ProviderID = .claude) -> ProviderAccount {
        ProviderAccount(id: id, provider: provider, externalAccountID: "test", email: nil, displayName: nil,
            plan: nil, credentialReference: "test", createdAt: date(0), lastSuccessfulSync: nil)
    }

    private func confirm(_ repository: AccountRepository) async throws {
        try await repository.record(.success(accountID: "claude", result: result(reset: 2)), at: date(1))
        try await repository.record(.success(accountID: "claude", result: result(reset: 4)), at: date(2.5))
    }

    private func result(reset: TimeInterval) -> UsageResult {
        UsageResult(windows: [UsageWindow(id: "five-hour", label: "5h", category: .fiveHour, usedPercent: 20,
            periodSeconds: nil, resetAt: date(reset), exhausted: false)])
    }

    private func date(_ seconds: TimeInterval) -> Date { Date(timeIntervalSince1970: seconds) }

    private struct VectorFile: Decodable {
        let description: String
        let schema: String
        let vectors: [Vector]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            description = try c.decode(String.self, forKey: Keys("description"))
            schema = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let sameAccount: Bool
        let outcome: String
        let old: Reading?
        let new: Reading
        let expected: Expected
        var status: SnapshotStatus { outcome == "failed" ? .failed : outcome == "partial" ? .partial : .ok }
    }

    private struct Reading: Decodable {
        let fetchedAt: Int64
        let windows: [Window]
        var date: Date { Date(timeIntervalSince1970: Double(fetchedAt) / 1000) }
        func result(status: SnapshotStatus = .ok) -> UsageResult { UsageResult(windows: windows.map(\.value), status: status) }
        func snapshot(accountID: String, status: SnapshotStatus = .ok) -> UsageSnapshot {
            UsageSnapshot(accountID: accountID, fetchedAt: date, status: status, windows: windows.map(\.value))
        }
    }

    private struct Window: Decodable {
        let id: String
        let resetAt: Int64?
        let measured: Bool
        let usedPercent: Double?
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "resetAt", "measured", "usedPercent"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            resetAt = try c.decodeIfPresent(Int64.self, forKey: Keys("resetAt"))
            measured = try c.decode(Bool.self, forKey: Keys("measured"))
            usedPercent = try c.decodeIfPresent(Double.self, forKey: Keys("usedPercent"))
        }
        var value: UsageWindow {
            UsageWindow(id: id, label: id, category: .other, usedPercent: usedPercent, periodSeconds: nil,
                resetAt: resetAt.map { Date(timeIntervalSince1970: Double($0) / 1000) }, exhausted: false, measured: measured)
        }
    }

    private struct Expected: Decodable { let confirmed: Bool; let windowIds: [String] }

    private static func parse(_ data: Data) throws -> [Vector] {
        let file = try JSONDecoder().decode(VectorFile.self, from: data)
        guard file.schema == "reset-confirmation-v1", !file.description.isEmpty, !file.vectors.isEmpty else {
            throw VectorFileError(description: "invalid reset vector file")
        }
        var names = Set<String>()
        for vector in file.vectors {
            guard !vector.name.isEmpty, names.insert(vector.name).inserted,
                  ["ok", "partial", "failed", "skipped"].contains(vector.outcome),
                  vector.expected.confirmed == !vector.expected.windowIds.isEmpty else {
                throw VectorFileError(description: "invalid reset vector")
            }
            for reading in [vector.old, vector.new].compactMap({ $0 }) {
                guard !reading.windows.isEmpty, reading.windows.allSatisfy({ !$0.id.isEmpty }),
                      Set(reading.windows.map(\.id)).count == reading.windows.count else {
                    throw VectorFileError(description: "unstable reset window identity")
                }
            }
        }
        return file.vectors
    }
}
