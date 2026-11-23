import XCTest
@testable import UsageLimitsKit
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class AppOpeningTests: XCTestCase {
    private var directory: URL!
    private let now = Date(timeIntervalSince1970: 2_500)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }

    private actor Gate {
        private var entered = false
        private var arrivals: [CheckedContinuation<Void, Never>] = []
        private var held: CheckedContinuation<Void, Never>?
        private var released = false
        func hold() async {
            entered = true
            arrivals.forEach { $0.resume() }; arrivals.removeAll()
            guard !released else { return }
            await withCheckedContinuation { held = $0 }
        }
        func waitForEntry() async {
            guard !entered else { return }
            await withCheckedContinuation { arrivals.append($0) }
        }
        func release() { released = true; held?.resume(); held = nil }
    }

    private struct Transport: HTTPTransport {
        let gate: Gate
        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            await gate.hold()
            try Task.checkCancellation()
            let body = #"{"five_hour":{"utilization":5,"resets_at":"1970-01-01T01:06:40Z"}}"#
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(body.utf8), response)
        }
    }

    private func world(_ gate: Gate) -> UsageLimitsContainer {
        UsageLimitsContainer(directory: directory,
            credentials: InMemoryCredentialStore(credentials: ["test": OAuthCredentials(accessToken: "synthetic", refreshToken: nil)]),
            transport: Transport(gate: gate), now: { [now] in now })
    }

    private func account() -> ProviderAccount {
        ProviderAccount(id: "claude", provider: .claude, externalAccountID: "test", email: nil,
            displayName: nil, plan: nil, credentialReference: "test", createdAt: .distantPast, lastSuccessfulSync: nil)
    }

    private func oldReading(_ repository: AccountRepository) async throws {
        try await repository.record(.success(accountID: "claude", result: result(reset: 2_000)),
                                    at: Date(timeIntervalSince1970: 1_000))
    }

    private func result(reset: TimeInterval) -> UsageResult {
        UsageResult(windows: [UsageWindow(id: "five-hour", label: "5h", category: .fiveHour, usedPercent: 95,
            periodSeconds: 18_000, resetAt: Date(timeIntervalSince1970: reset), exhausted: false)])
    }

    @MainActor
    func testOnOpenSyncCommitsForFollowingOpeningNotInactiveBounceOrRecreation() async throws {
        let gate = Gate()
        let container = world(gate)
        try await container.repository.upsert(account())
        try await oldReading(container.repository)
        var presented: [Set<String>] = []
        var errors: [String] = []
        let opening = AppOpeningCoordinator(
            claim: { try await container.claimPendingResetConfirmations() },
            present: { presented.append($0) },
            refresh: { do { try await container.refresh() } catch { errors.append(String(describing: error)) } },
            interrupt: {}, failed: { errors.append(String(describing: $0)) })
        opening.sceneChanged(.active)
        await gate.waitForEntry()
        XCTAssertEqual(presented, [[]], "the persisted claim finished before the actual provider request")
        opening.sceneChanged(.active)
        opening.sceneChanged(.inactive)
        opening.sceneChanged(.active)
        await gate.release()
        await opening.task?.value
        XCTAssertEqual(presented, [[]], "a transient phase must not steal the sync's new mark")
        opening.sceneChanged(.background)
        opening.sceneChanged(.inactive)
        opening.sceneChanged(.active)
        await opening.task?.value
        XCTAssertEqual(presented, [[], ["claude"]])
        opening.sceneChanged(.background)
        opening.sceneChanged(.active)
        await opening.task?.value
        XCTAssertEqual(presented, [[], ["claude"], []])
        XCTAssertTrue(errors.isEmpty, errors.joined(separator: ", "))
    }

    @MainActor
    func testInterruptedClaimNeverPresentsAfterReturnOrLeavesALaterSlot() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await oldReading(repository)
        try await repository.record(.success(accountID: "claude", result: result(reset: 4_000)), at: now)
        let gate = Gate()
        var presentation = HoorayPresentation()
        var refreshes = 0
        var errors: [String] = []
        let opening = AppOpeningCoordinator(
            claim: {
                let claimed = try await repository.claimPendingResetConfirmations()
                await gate.hold()
                return claimed
            }, present: { presentation.open($0) }, refresh: { refreshes += 1 },
            interrupt: { presentation.stop() }, failed: { errors.append(String(describing: $0)) })
        opening.sceneChanged(.active)
        await gate.waitForEntry()
        opening.sceneChanged(.inactive)
        opening.sceneChanged(.active)
        await gate.release()
        await opening.task?.value
        presentation.placed(visibleAccountIDs: ["claude"], at: 10)
        XCTAssertTrue(presentation.startedAt.isEmpty)
        XCTAssertEqual(refreshes, 1)
        let restarted = try await AccountRepository(directory: directory).claimPendingResetConfirmations()
        XCTAssertTrue(restarted.isEmpty, "claim cleared before the interrupted UI")
        XCTAssertTrue(errors.isEmpty)
    }

    @MainActor
    func testNewOpeningWaitsForOldHeldClaimAndRefreshBeforeClaimingAgain() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await oldReading(repository)
        let gate = Gate()
        var claims: [Set<String>] = []
        var presented: [Set<String>] = []
        var errors: [String] = []
        var refreshCount = 0
        let opening = AppOpeningCoordinator(claim: {
            let result = try await repository.claimPendingResetConfirmations()
            claims.append(result)
            if claims.count == 1 { await gate.hold() }
            return result
        }, present: { presented.append($0) }, refresh: {
            refreshCount += 1
            if refreshCount == 1 {
                do { try await repository.record(.success(accountID: "claude", result: self.result(reset: 4_000)), at: self.now) }
                catch { errors.append(String(describing: error)) }
            }
        }, interrupt: {}, failed: { errors.append(String(describing: $0)) })
        opening.sceneChanged(.active)
        await gate.waitForEntry()
        opening.sceneChanged(.background)
        opening.sceneChanged(.active)
        await gate.release()
        await opening.task?.value
        XCTAssertEqual(claims, [[], ["claude"]])
        XCTAssertEqual(presented, [["claude"]], "the interrupted generation never appears")
        XCTAssertEqual(refreshCount, 2)
        XCTAssertTrue(errors.isEmpty)
    }

    @MainActor
    func testRemovedAndReaddedSameTimestampRejectsActualHeldOldSync() async throws {
        let gate = Gate()
        let container = world(gate)
        try await container.repository.upsert(account())
        let original = await container.repository.accounts()
        let sync = Task { try await container.refresh() }
        await gate.waitForEntry()
        try await container.repository.remove(id: "claude")
        try await container.repository.upsert(account())
        let replacement = await container.repository.accounts()
        XCTAssertNotEqual(original.first?.instanceID, replacement.first?.instanceID)
        try await oldReading(container.repository)
        await gate.release()
        _ = try await sync.value
        let afterOld = await container.repository.usage()
        XCTAssertEqual(afterOld.first?.snapshot?.fetchedAt, Date(timeIntervalSince1970: 1_000))
        let oldClaim = try await container.claimPendingResetConfirmations()
        XCTAssertTrue(oldClaim.isEmpty)
        _ = try await container.refresh()
        let currentClaim = try await container.claimPendingResetConfirmations()
        XCTAssertEqual(currentClaim, ["claude"], "the current instance's real response remains eligible")
    }

    @MainActor
    func testCancelledActualSyncCannotCreateConfirmation() async throws {
        let gate = Gate()
        let container = world(gate)
        try await container.repository.upsert(account())
        try await oldReading(container.repository)
        let sync = Task { try await container.refresh() }
        await gate.waitForEntry()
        sync.cancel()
        await gate.release()
        do { _ = try await sync.value; XCTFail("cancelled provider request must throw") } catch is CancellationError {} catch { XCTFail("unexpected \(error)") }
        let claimed = try await container.claimPendingResetConfirmations()
        XCTAssertTrue(claimed.isEmpty)
    }

    func testFourSecondPresentationUsesFirstPlacementNotOpeningOrLaterNavigation() {
        var presentation = HoorayPresentation()
        presentation.open(["claude", "hidden"])
        XCTAssertTrue(presentation.startedAt.isEmpty)
        presentation.placed(visibleAccountIDs: ["claude"], at: 50)
        XCTAssertEqual(presentation.startedAt, ["claude": 50])
        presentation.placed(visibleAccountIDs: ["hidden"], at: 51)
        XCTAssertNil(presentation.startedAt["hidden"])
        presentation.advance(to: 53.999)
        XCTAssertEqual(presentation.startedAt["claude"], 50)
        presentation.advance(to: 54)
        XCTAssertTrue(presentation.startedAt.isEmpty)
        presentation.placed(visibleAccountIDs: ["claude"], at: 55)
        XCTAssertTrue(presentation.startedAt.isEmpty)
        presentation.open(["claude"])
        presentation.placed(visibleAccountIDs: [], at: 100)
        presentation.placed(visibleAccountIDs: ["claude"], at: 101)
        XCTAssertTrue(presentation.startedAt.isEmpty, "plain artwork/no eligible slot spends the opening")
    }
}
