import XCTest
@testable import UsageLimitsKit

/// The cache the app and the widget both read.
///
/// The behaviour worth pinning is what happens when a sync FAILS, because the obvious
/// implementation — replace the snapshot with the new one — silently throws away the last
/// numbers the user had. On a screen whose whole promise is "how much is left", blanking a card
/// because one refresh timed out is worse than showing yesterday's figure with a marker on it.
final class AccountRepositoryTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("accounts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func account(_ id: String = "a", created: TimeInterval = 0) -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: .codex,
            externalAccountID: "ext-\(id)",
            email: "\(id)@example.com",
            displayName: nil,
            plan: nil,
            credentialReference: "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: created),
            lastSuccessfulSync: nil)
    }

    private func window(_ usedPercent: Double) -> UsageWindow {
        UsageWindow(
            id: "5h", label: "5h limit", category: .fiveHour,
            usedPercent: usedPercent, periodSeconds: 18_000,
            resetAt: now.addingTimeInterval(3_600), exhausted: false)
    }

    private func result(_ usedPercent: Double, plan: String? = nil) -> UsageResult {
        UsageResult(
            windows: [window(usedPercent)], resetCredits: [],
            resetCreditCount: nil, applicableResetCreditCount: nil, plan: plan)
    }

    // MARK: - Round trip

    func testAnAccountSurvivesAReopen() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        let reopened = AccountRepository(directory: directory)

        let ids = await reopened.accounts().map(\.id)
        XCTAssertEqual(ids, ["a"])
    }

    func testAccountsComeBackInAStableOrder() async throws {
        // Oldest first, so the list does not reshuffle because a dictionary rehashed — a user
        // who learns where an account sits should find it there next time.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("third", created: 300))
        try await repository.upsert(account("first", created: 100))
        try await repository.upsert(account("second", created: 200))

        let ids = await AccountRepository(directory: directory).accounts().map(\.id)
        XCTAssertEqual(ids, ["first", "second", "third"])
    }

    func testRemovingAnAccountRemovesItsUsageToo() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.remove(id: "a")

        let usage = await AccountRepository(directory: directory).usage()
        XCTAssertTrue(usage.isEmpty)
    }

    // MARK: - Recording a sync

    func testASuccessfulSyncIsStored() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        let usage = await repository.usage()
        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
        XCTAssertEqual(usage.first?.snapshot?.windows.first?.usedPercent, 20)
        XCTAssertEqual(usage.first?.snapshot?.fetchedAt, now)
    }

    func testAPlanReportedBySyncIsRemembered() async throws {
        // The provider is the authority on which plan an account is on, and it only says so
        // during a sync. Discarding it means the subtitle stays blank for ever.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(
            .success(accountID: "a", result: result(20, plan: "Max 5×")), at: now)

        let plan = await repository.accounts().first?.plan
        XCTAssertEqual(plan, "Max 5×")
    }

    func testAFailedSyncKeepsTheLastKnownNumbers() async throws {
        // The case this repository exists to get right. Replacing the snapshot on failure —
        // the obvious implementation — blanks the card, so one timeout costs the user the only
        // figures they had.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.record(
            .failure(accountID: "a", message: "the network went away"),
            at: now.addingTimeInterval(3_600))

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.status, .failed)
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 20)
        XCTAssertEqual(snapshot?.errorMessage, "the network went away")
    }

    func testAFailedSyncKeepsTheORIGINALFetchTime() async throws {
        // Stamping a failure with the current time would make stale numbers look freshly
        // confirmed — and staleness is derived from exactly this field, so the card would go on
        // claiming to be current for as long as the failures continued.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.record(
            .failure(accountID: "a", message: "still gone"),
            at: now.addingTimeInterval(86_400))

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.fetchedAt, now)
    }

    func testAFailureBeforeAnySuccessIsEmptyRatherThanWrong() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())

        try await repository.record(
            .failure(accountID: "a", message: "never worked"), at: now)

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.status, .failed)
        XCTAssertTrue(snapshot?.windows.isEmpty ?? false)
    }

    func testAnOutcomeForAnUnknownAccountIsIgnored() async throws {
        // An account removed while its sync was in flight must not be resurrected by the
        // outcome arriving afterwards.
        let repository = AccountRepository(directory: directory)

        try await repository.record(.success(accountID: "ghost", result: result(20)), at: now)

        let accounts = await repository.accounts()
        XCTAssertTrue(accounts.isEmpty)
    }

    func testReSavingAnAccountKeepsItsUsage() async throws {
        // Re-authenticating changes the tokens, not the quota. Blanking the numbers would make
        // a successful sign-in look like a regression.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        try await repository.upsert(account())

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 20)
    }

    // MARK: - What the file may contain

    func testTheFileHoldsNoTokenMaterial() async throws {
        // The structural reason this cache can live in a container the widget reads: an account
        // names its credentials, it does not carry them.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)

        let contents = try String(
            contentsOf: directory.appendingPathComponent("accounts.json"), encoding: .utf8)

        XCTAssertTrue(contents.contains("credentialReference"))
        XCTAssertFalse(contents.lowercased().contains("accesstoken"))
        XCTAssertFalse(contents.lowercased().contains("refreshtoken"))
    }

    func testAnUnreadableCacheIsEmptyRatherThanFatal() throws {
        // It holds nothing a sync cannot rebuild, so refusing to open would strand the user
        // over a file that costs one refresh to replace.
        try "this is not JSON".write(
            to: directory.appendingPathComponent("accounts.json"),
            atomically: true, encoding: .utf8)

        let repository = AccountRepository(directory: directory)

        let expectation = expectation(description: "loaded")
        Task {
            let accounts = await repository.accounts()
            XCTAssertTrue(accounts.isEmpty)
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)
    }

    // MARK: - The sink

    func testTheSinkPersistsWhatTheEngineReports() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        let sink = RepositorySink(repository: repository)

        await sink.record(.success(accountID: "a", result: result(35)),
            at: now, accountCreatedAt: account().createdAt)

        let snapshot = await repository.usage().first?.snapshot
        XCTAssertEqual(snapshot?.windows.first?.usedPercent, 35)
    }

    // MARK: - Account instances

    func testAnInsertionGetsANewInstanceAndAReconnectKeepsIt() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a"))
        let firstAccounts = await repository.accounts()
        let first = try XCTUnwrap(firstAccounts.first?.instanceID)

        try await repository.upsert(account("a"))
        let reopened = await AccountRepository(directory: directory).accounts()
        XCTAssertEqual(reopened.first?.instanceID, first, "a reconnect is the same instance, across a reopen")

        try await repository.remove(id: "a")
        try await repository.upsert(account("a"))
        let againAccounts = await repository.accounts()
        let again = try XCTUnwrap(againAccounts.first?.instanceID)
        XCTAssertNotEqual(again, first, "removed and added again is a new instance")
    }

    func testARowFromBeforeInstancesKeepsItsAccountIdAsItsPinKey() async throws {
        let json = #"[{"account": {"id": "a", "provider": "codex", "externalAccountID": "ext-a", "credentialReference": "ref-a", "createdAt": "1970-01-01T00:00:00Z"}}]"#
        try Data(json.utf8).write(to: accountsFile)
        let repository = AccountRepository(directory: directory)

        try await repository.upsert(account("a"))

        let accounts = await repository.accounts()
        let stored = try XCTUnwrap(accounts.first)
        XCTAssertNil(stored.instanceID, "a reconnect does not turn an existing row into a new instance")
        XCTAssertEqual(stored.pinKey, "a", "so a pin made for it before still applies")
    }

    // MARK: - A read that fails must not become an empty register

    private var accountsFile: URL { directory.appendingPathComponent(AccountRepository.fileName) }

    func testAnUndecodableRegisterIsNotTreatedAsNoAccounts() async throws {
        // The file is not a usage cache a sync could rebuild: it names the credential
        // reference for every account. Reading it as "no accounts" strands keychain entries
        // that no row points at any more.
        try Data("not json".utf8).write(to: accountsFile)
        let repository = AccountRepository(directory: directory)

        do {
            try await repository.upsert(account("new"))
            XCTFail("a mutation on top of an unreadable register must not be persisted")
        } catch let error as AccountStoreError {
            XCTAssertEqual(error, .corrupt)
        }
    }

    func testAFailedReadDoesNotLetTheNextWriteReplaceTheRegister() async throws {
        // The destructive half. `upsert` persists the WHOLE file, so a write on top of a
        // failed read replaces a register holding several accounts with the one this call
        // knows about. The bytes must still be there afterwards.
        let original = Data("not json".utf8)
        try original.write(to: accountsFile)
        let repository = AccountRepository(directory: directory)

        _ = try? await repository.upsert(account("new"))

        XCTAssertEqual(try Data(contentsOf: accountsFile), original,
                       "the unreadable file must be left intact for recovery")
    }

    func testAReadIsRetriedAfterItFails() async throws {
        // A transient register-read failure must leave loading retryable.
        try Data("not json".utf8).write(to: accountsFile)
        let repository = AccountRepository(directory: directory)
        let duringFailure = await repository.accounts()
        XCTAssertEqual(duringFailure.count, 0)

        // The fault clears — the file becomes readable.
        try FileManager.default.removeItem(at: accountsFile)
        let writable = AccountRepository(directory: directory)
        try await writable.upsert(account("a"))

        let recovered = await repository.accounts()
        XCTAssertEqual(recovered.map(\.id), ["a"], "the next call must read again, not stay empty")
    }

    func testAMissingFileIsAGenuineEmptyStateAndStillAcceptsWrites() async throws {
        // The other direction: a first launch has no file, which is not a fault, and must not
        // be blocked by the guard that protects an unreadable one.
        let repository = AccountRepository(directory: directory)

        try await repository.upsert(account("a"))

        let ids = await repository.accounts().map(\.id)
        XCTAssertEqual(ids, ["a"])
    }

    // MARK: - A checked read tells an unread register from an empty one

    func testACheckedReadReportsACorruptRegisterWhileThePlainReadStaysEmpty() async throws {
        // The screens ask through the checked read, so "the file could not be read" does not
        // reach a user as "this account is no longer on this device".
        try Data("not json".utf8).write(to: accountsFile)
        let repository = AccountRepository(directory: directory)

        do {
            _ = try await repository.checkedUsage()
            XCTFail("an undecodable register must not read as no accounts")
        } catch {
            XCTAssertEqual(error as? AccountStoreError, .corrupt)
        }

        // Callers that draw whatever is there keep the empty list they always had.
        let plain = await repository.usage()
        XCTAssertTrue(plain.isEmpty)
    }

    func testACheckedReadOfAMissingFileIsAnEmptyRegister() async throws {
        // A first launch has no file. That is the one case in which "no accounts" is true.
        let repository = AccountRepository(directory: directory)

        let usage = try await repository.checkedUsage()

        XCTAssertTrue(usage.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: accountsFile.path),
                       "reading must not write the register it found missing")
    }

    func testACheckedReadRecoversOnceAFileThatCouldNotBeOpenedCanBe() async throws {
        // A directory where the file belongs exists and cannot be opened as data, which is the
        // shape a protected file has while the device is locked.
        try FileManager.default.createDirectory(at: accountsFile, withIntermediateDirectories: false)
        let repository = AccountRepository(directory: directory)

        do {
            _ = try await repository.checkedUsage()
            XCTFail("a file that is present and cannot be opened must not read as no accounts")
        } catch {
            XCTAssertEqual(error as? AccountStoreError, .unreadable)
        }

        // The fault clears: a readable register takes the place of the unreadable entry.
        try FileManager.default.removeItem(at: accountsFile)
        let writer = AccountRepository(directory: directory)
        try await writer.upsert(account("b", created: 200))
        try await writer.upsert(account("a", created: 100))
        try await writer.record(.success(accountID: "a", result: result(20)), at: now)

        let recovered = try await repository.checkedUsage()
        XCTAssertEqual(recovered.map(\.account.id), ["a", "b"], "the same repository must read again")
        XCTAssertEqual(recovered.first?.snapshot?.windows.first?.usedPercent, 20)
        let plain = await repository.usage()
        XCTAssertEqual(recovered, plain, "both reads return the same register in the same order")
    }

    func testADuplicateAccountIdDoesNotTrap() async throws {
        // `uniqueKeysWithValues` traps on a duplicate key, which would crash on launch over a
        // file this app itself could have written before ids were enforced.
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a"))
        let one = try Data(contentsOf: accountsFile)
        let doubled = try JSONSerialization.jsonObject(with: one) as! [Any]
        try JSONSerialization.data(withJSONObject: doubled + doubled).write(to: accountsFile)

        let reopened = AccountRepository(directory: directory)

        let ids = await reopened.accounts().map(\.id)
        XCTAssertEqual(ids, ["a"])
    }
    // MARK: - The order the user dragged them into

    func testAReorderSurvivesAReopen() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a", created: 100))
        try await repository.upsert(account("b", created: 200))
        try await repository.upsert(account("c", created: 300))

        try await repository.reorder(ids: ["c", "a", "b"])

        let reopened = AccountRepository(directory: directory)
        let ids = await reopened.accounts().map(\.id)
        XCTAssertEqual(ids, ["c", "a", "b"])
    }

    /// Until anything is dragged the list is oldest first, which is what a never-reordered
    /// register has always meant.
    func testWithoutAReorderTheOldestAccountIsStillFirst() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("b", created: 200))
        try await repository.upsert(account("a", created: 100))

        let ids = await repository.accounts().map(\.id)
        XCTAssertEqual(ids, ["a", "b"])
    }

    /// A new account joins the END of a hand-arranged list.
    ///
    /// With `sortOrder` defaulting to 0 rather than nil it would tie with whatever the user
    /// dragged to the front and land in the middle of their arrangement.
    func testAnAccountAddedAfterAReorderJoinsTheEnd() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a", created: 100))
        try await repository.upsert(account("b", created: 200))
        try await repository.reorder(ids: ["b", "a"])

        // Created before both of them, so age alone would put it first.
        try await repository.upsert(account("c", created: 1))

        let ids = await repository.accounts().map(\.id)
        XCTAssertEqual(ids, ["b", "a", "c"])
    }

    /// An account this caller never saw keeps its place instead of being renumbered to the
    /// front — the overview can be showing a list that another screen has since added to.
    func testReorderingLeavesAnUnknownIdAlone() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a", created: 100))
        try await repository.upsert(account("b", created: 200))

        try await repository.reorder(ids: ["b", "a", "ghost"])

        let ids = await repository.accounts().map(\.id)
        XCTAssertEqual(ids, ["b", "a"])
    }

    /// Re-authenticating changes the tokens, not where the user put the card.
    func testReSavingAnAccountKeepsItsPlace() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a", created: 100))
        try await repository.upsert(account("b", created: 200))
        try await repository.reorder(ids: ["b", "a"])

        try await repository.upsert(account("a", created: 100))

        let ids = await repository.accounts().map(\.id)
        XCTAssertEqual(ids, ["b", "a"])
    }

    /// A register written before manual ordering existed must still decode. The synthesised
    /// decoder would reject it for the missing key, and this type treats an undecodable file as
    /// a register it must not overwrite — which would strand every account the user had.
    func testARegisterWrittenBeforeOrderingStillLoads() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account("a", created: 100))

        // Strip the key the old format never wrote.
        let url = directory.appendingPathComponent(AccountRepository.fileName)
        let text = try String(contentsOf: url, encoding: .utf8)
            .split(separator: "\n")
            .filter { !$0.contains("sortOrder") }
            .joined(separator: "\n")
        try Data(text.utf8).write(to: url)

        let ids = await AccountRepository(directory: directory).accounts().map(\.id)
        XCTAssertEqual(ids, ["a"])
    }

    /// A register written before `attributes` existed has no such key, and the synthesised
    /// decoder demanded it — so the whole file read as corrupt, which this repository treats as
    /// a register it must not overwrite. Every connected account was stranded.
    func testARegisterWithoutAttributesStillLoads() async throws {
        let legacy = """
            [{"account": {"id": "a", "provider": "codex", "externalAccountID": "ext-a",
              "credentialReference": "ref-a", "createdAt": "2026-09-01T00:00:00Z"},
              "snapshot": null}]
            """
        try Data(legacy.utf8).write(to: directory.appendingPathComponent(AccountRepository.fileName))

        let accounts = await AccountRepository(directory: directory).accounts()

        XCTAssertEqual(accounts.map(\.credentialReference), ["ref-a"])
        XCTAssertEqual(accounts.first?.attributes, [:])
    }

}

extension AccountRepositoryTests {
    func testPartialResultKeepsWarningAndDoesNotRejuvenateAbsentWindows() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(40)), at: now)
        let partial = UsageResult(windows: [], status: .partial, errorMessage: "Partial billing update")
        try await repository.record(.success(accountID: "a", result: partial), at: now.addingTimeInterval(60))
        let value = await repository.usage().first?.snapshot
        XCTAssertEqual(value?.status, .partial)
        XCTAssertEqual(value?.errorMessage, "Partial billing update")
        XCTAssertTrue(value?.windows.isEmpty == true)
    }

    func testFailurePreservesMeasurementTimeAndRecordsAttemptTime() async throws {
        let repository = AccountRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(.success(accountID: "a", result: result(20)), at: now)
        let attempt = now.addingTimeInterval(300)
        try await repository.record(.failure(accountID: "a", message: "Network unavailable"), at: attempt)
        let value = await AccountRepository(directory: directory).usage().first?.snapshot
        XCTAssertEqual(value?.fetchedAt, now)
        XCTAssertEqual(value?.lastAttemptAt, attempt)
    }
}
