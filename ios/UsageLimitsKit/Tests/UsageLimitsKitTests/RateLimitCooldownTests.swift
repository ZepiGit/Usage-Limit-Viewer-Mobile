import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class RateLimitCooldownTests: XCTestCase {
    private struct VectorFile: Decodable {
        let cases: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["cases"])
            let values = try decoder.container(keyedBy: Keys.self)
            cases = try values.decode([Vector].self, forKey: Keys("cases"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let now: Int64
        let rateLimitedAt: Int64
        let retryAfter: Int64?
        let automatic: Bool
        let skip: Bool

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "now", "rateLimitedAt", "retryAfter", "automatic", "skip"])
            let values = try decoder.container(keyedBy: Keys.self)
            name = try values.decode(String.self, forKey: Keys("name"))
            now = try values.decode(Int64.self, forKey: Keys("now"))
            rateLimitedAt = try values.decode(Int64.self, forKey: Keys("rateLimitedAt"))
            retryAfter = try values.decodeIfPresent(Int64.self, forKey: Keys("retryAfter"))
            automatic = try values.decode(Bool.self, forKey: Keys("automatic"))
            skip = try values.decode(Bool.self, forKey: Keys("skip"))
        }
    }

    func testCooldownPolicyMatchesSharedVectors() throws {
        let data = try Data(contentsOf: SharedVectors.file("rate-limit-cooldown.json"))
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).cases
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            let deadline = RateLimitCooldown.deadline(
                from: Date(timeIntervalSince1970: TimeInterval(vector.rateLimitedAt)),
                retryAfter: vector.retryAfter.map { TimeInterval($0) })
            XCTAssertEqual(RateLimitCooldown.shouldSkip(automatic: vector.automatic, until: deadline,
                now: Date(timeIntervalSince1970: TimeInterval(vector.now))), vector.skip, vector.name)
        }
    }

    private let start = Date(timeIntervalSince1970: 1_700_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("cooldown-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    private actor Transport: HTTPTransport {
        struct Reply: Sendable {
            var status = 200
            var body: String
            var headers: [String: String] = [:]

            static let usage = Reply(body: #"{"five_hour":{"utilization":21}}"#)
            static let xaiBilling = Reply(body: #"{"config":{"monthlyLimit":{"val":100},"used":{"val":20}}}"#)
            static func codexUsage(usedPercent: Int) -> Reply {
                Reply(body: #"""
                    {"rate_limit":{"primary_window":{"used_percent":\#(usedPercent),"limit_window_seconds":18000}},
                     "rate_limit_reset_credits":{"available_count":2,"applicable_available_count":1,
                       "credits":[{"id":"embedded","status":"available"}]}}
                    """#)
            }
            static let antigravityUsage = Reply(body: #"""
                {"groups":[{"displayName":"Gemini Models","buckets":[
                  {"bucketId":"gemini-5h","window":"5h","remainingFraction":0.5}]}]}
                """#)
            static let balance = Reply(body: #"{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"8.00"}]}"#)

            static func limited(_ finalHint: String?) -> [Reply] {
                let immediate = Reply(status: 429, body: "", headers: ["Retry-After": "0"])
                return [immediate, immediate, Reply(status: 429, body: "",
                    headers: finalHint.map { ["Retry-After": $0] } ?? [:])]
            }
        }

        private var replies: [String: [Reply]]
        private var attempts: [String: Int] = [:]
        private let requestKey: @Sendable (URLRequest) -> String
        private let hold: (key: String, attempt: Int)?
        private var held: CheckedContinuation<Void, Never>?
        private var awaitingHold: CheckedContinuation<Void, Never>?

        init(
            _ replies: [String: [Reply]], hold: (String, Int)? = nil,
            requestKey: @escaping @Sendable (URLRequest) -> String = {
                String(($0.value(forHTTPHeaderField: "Authorization") ?? "").dropFirst("Bearer ".count))
            }
        ) {
            self.replies = replies
            self.hold = hold
            self.requestKey = requestKey
        }

        func count(_ key: String) -> Int { attempts[key, default: 0] }
        var totalCount: Int { attempts.values.reduce(0, +) }

        func waitForHeldReply() async {
            if held == nil { await withCheckedContinuation { awaitingHold = $0 } }
        }

        func releaseReply() {
            held?.resume()
            held = nil
        }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let key = requestKey(request)
            attempts[key, default: 0] += 1
            let reply = replies[key]?.isEmpty == false ? replies[key]!.removeFirst()
                : Reply(status: 400, body: "Unexpected request")
            if let hold, hold.key == key, hold.attempt == attempts[key] {
                await withCheckedContinuation {
                    held = $0
                    awaitingHold?.resume()
                    awaitingHold = nil
                }
            }
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status,
                httpVersion: "HTTP/1.1", headerFields: reply.headers)!
            return (Data(reply.body.utf8), response)
        }
    }

    private func container(
        _ transport: Transport, credentials: InMemoryCredentialStore, at time: Date
    ) -> UsageLimitsContainer {
        UsageLimitsContainer(directory: directory, credentials: credentials, transport: transport, now: { time })
    }

    private func addUsage(
        _ id: String, to subject: UsageLimitsContainer, credentials: InMemoryCredentialStore,
        createdAt: Date? = nil, provider: ProviderID = .claude, refreshToken: String? = nil,
        attributes: [String: String] = [:]
    ) async throws {
        try await credentials.save(OAuthCredentials(accessToken: id, refreshToken: refreshToken), reference: "ref-\(id)")
        try await subject.add(ProviderAccount(id: id, provider: provider, externalAccountID: id,
            email: nil, displayName: nil, plan: nil, credentialReference: "ref-\(id)",
            createdAt: createdAt ?? start, lastSuccessfulSync: nil, attributes: attributes))
    }

    func testXaiPartialReadingWithMissingRetryAfterPausesNextAutomaticRefresh() async throws {
        let data = try Data(contentsOf: SharedVectors.file("rate-limit-cooldown.json"))
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).cases
        let vector = try XCTUnwrap(vectors.first {
            $0.name == "a partial reading with a throttled endpoint and no header pauses the next five-minute pass"
        })
        let at = Date(timeIntervalSince1970: TimeInterval(vector.rateLimitedAt))
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["xai": Transport.Reply.limited(vector.retryAfter.map { String($0) }) + [.xaiBilling]])
        let subject = container(transport, credentials: credentials, at: at)
        try await addUsage("xai", to: subject, credentials: credentials, createdAt: at, provider: .xai)

        let reading = try await subject.refresh(automatic: true)
        let partial = try XCTUnwrap(reading.first?.snapshot)
        XCTAssertEqual(partial.status, .partial)
        XCTAssertEqual(partial.windows.first { $0.id == "xai-monthly" }?.usedPercent, 20)

        let next = Transport(["xai": [.xaiBilling, .xaiBilling]])
        let reopened = container(next, credentials: credentials, at: Date(timeIntervalSince1970: TimeInterval(vector.now)))
        let skipped = try await reopened.refresh(automatic: vector.automatic)
        let calls = await next.totalCount
        XCTAssertEqual(calls == 0, vector.skip)
        XCTAssertEqual(skipped.first?.snapshot, partial)

        let manual = try await reopened.refresh()
        let manualCalls = await next.totalCount
        XCTAssertEqual(manualCalls, 2, "an explicit refresh can still read both billing views")
        XCTAssertEqual(manual.first?.snapshot?.status, .ok)
    }

    func testPinnedPartialReadingKeepsItsFigureWhenItBecomesStaleDuringCooldown() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["xai": Transport.Reply.limited("7200") + [.xaiBilling]])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("xai", to: subject, credentials: credentials, provider: .xai)
        try await subject.save(settings: AppSettings(syncIntervalMinutes: 15,
            statusIconsEnabled: true, pinnedAccountKeys: await subject.pinKeys(["xai"])))

        let reading = try await subject.refresh(automatic: true)
        let partial = try XCTUnwrap(reading.first?.snapshot)
        XCTAssertEqual(partial.status, .partial)
        let published = GlanceSnapshotCodec.load(fromDirectory: directory)

        let staleAt = start.addingTimeInterval(45 * 60)
        let next = Transport([:])
        let reopened = container(next, credentials: credentials, at: staleAt)
        let skipped = try await reopened.refresh(automatic: true)
        let calls = await next.totalCount
        XCTAssertEqual(calls, 0, "the reading becomes stale before the two-hour pause ends")
        XCTAssertEqual(skipped.first?.snapshot, partial)

        for (date, outdated, inline) in [
            (staleAt.addingTimeInterval(-1), false, "80% left"),
            (staleAt, true, "80% left · Stale"),
        ] {
            let entries = PinnedAccountsCapture.capture(source: published, at: date).accounts
            let entry = try XCTUnwrap(entries.first)
            XCTAssertEqual(entry.content, .init(content: .figure(80), outdated: outdated))
            XCTAssertEqual(PinnedAccountText.cellText(entry), "80")
            XCTAssertEqual(PinnedAccountText.inlineRow(entries, texts: EnglishPinnedAccountTexts())?.text, inline)
        }
    }

    func testCodexCreditsThrottleKeepsEmbeddedCreditsAndDoesNotPauseUsage() async throws {
        let credentials = InMemoryCredentialStore()
        let creditsEndpoint = "rate-limit-reset-credits"
        func pass(usedPercent: Int) -> Transport {
            Transport([
                "usage": [.codexUsage(usedPercent: usedPercent)],
                creditsEndpoint: Transport.Reply.limited("3600"),
            ], requestKey: { $0.url?.lastPathComponent ?? "" })
        }
        let first = pass(usedPercent: 25)
        let subject = container(first, credentials: credentials, at: start)
        try await addUsage("codex", to: subject, credentials: credentials, provider: .codex)

        let initial = try await subject.refresh(automatic: true).first?.snapshot
        let firstCreditAttempts = await first.count(creditsEndpoint)
        XCTAssertEqual(firstCreditAttempts, 1, "the optional endpoint must not retry within the first pass")
        XCTAssertEqual(initial?.status, .ok)
        XCTAssertEqual(initial?.windows.first?.usedPercent, 25)
        XCTAssertEqual(initial?.resetCredits.map(\.id), ["embedded"], "a 429 must preserve embedded credit rows")
        XCTAssertEqual(initial?.resetCreditCount, 2, "a 429 must preserve the embedded credit balance")
        XCTAssertEqual(initial?.applicableResetCreditCount, 1, "a 429 must preserve the applicable credit count")
        XCTAssertNil(initial?.statusMessage, "throttling only the optional read must not show a pause notice")

        let next = pass(usedPercent: 35)
        let reopened = container(next, credentials: credentials, at: start.addingTimeInterval(300))
        let updated = try await reopened.refresh(automatic: true).first?.snapshot
        let nextUsageAttempts = await next.count("usage")
        let nextCreditAttempts = await next.count(creditsEndpoint)
        XCTAssertEqual(nextUsageAttempts, 1, "the next automatic pass must still read usage")
        XCTAssertEqual(nextCreditAttempts, 1, "the next pass must make exactly one optional request")
        XCTAssertEqual(updated?.windows.first?.usedPercent, 35, "the next reading must replace the old one")
    }

    func testAntigravityShowsPauseWithoutTurningAUsableReadingIntoAnError() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["antigravity": Transport.Reply.limited("1800") + [
            .antigravityUsage, .init(status: 429, body: "", headers: ["Retry-After": "3600"]),
        ]])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("antigravity", to: subject, credentials: credentials, provider: .antigravity,
            attributes: ["project_id": "synthetic-project"])

        try await subject.refresh(automatic: true)
        let glance = GlanceSnapshotCodec.read(fromDirectory: directory).accounts.first
        XCTAssertEqual(glance?.statusMessage.flatMap { StoredMessage.classify($0) },
            .rateLimited(trailingStatus: nil), "the published card must explain why refresh is paused")
        XCTAssertEqual(glance?.severity, .medium, "the notice must not turn a usable card into an error")
        XCTAssertEqual(glance?.rows.first?.remainingPercent, 50, "the usable window must remain visible")

        let retryTime = start.addingTimeInterval(3_599)
        let next = Transport(["antigravity": [
            .antigravityUsage, .init(body: "{}"), .init(status: 429, body: "", headers: ["Retry-After": "3600"]),
            .antigravityUsage, .init(body: "{}"), .init(body: "{}"),
        ]])
        let beforeExpiry = container(next, credentials: credentials, at: retryTime)
        let skipped = try await beforeExpiry.refresh(automatic: true)
        let skippedCalls = await next.totalCount
        XCTAssertEqual(skippedCalls, 0, "the longest throttle must survive reopening")
        XCTAssertEqual(skipped.first?.snapshot?.status, .ok)
        XCTAssertEqual(skipped.first?.snapshot?.fetchedAt, start, "the pause must not replace the last usable reading")

        let manual = try await beforeExpiry.refresh()
        XCTAssertEqual(manual.first?.snapshot?.status, .ok)
        XCTAssertEqual(manual.first?.snapshot?.fetchedAt, retryTime, "manual refresh must bypass the pause")
        let retriedGlance = GlanceSnapshotCodec.read(fromDirectory: directory).accounts.first
        XCTAssertEqual(retriedGlance?.statusMessage.flatMap { StoredMessage.classify($0) },
            .rateLimited(trailingStatus: nil), "a manual refresh that throttles again must keep explaining the pause")

        let oldExpiry = container(next, credentials: credentials, at: start.addingTimeInterval(3_600))
        try await oldExpiry.refresh(automatic: true)
        let rearmedCalls = await next.totalCount
        XCTAssertEqual(rearmedCalls, 3, "the manual pass must rearm the pause while a shard still throttles")

        let atExpiry = container(next, credentials: credentials, at: retryTime.addingTimeInterval(3_600))
        try await atExpiry.refresh(automatic: true)
        let calls = await next.totalCount
        XCTAssertEqual(calls, 6, "all three shards can be read at the new deadline")
    }

    func testXaiMixedAuthAndThrottlePausesWithoutRenewingAndCanRetryAuthAfterExpiry() async throws {
        let credentials = InMemoryCredentialStore()
        let rejected = Transport.Reply(status: 401, body: "")
        let transport = Transport(["xai": [rejected] + Transport.Reply.limited(nil)])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("xai", to: subject, credentials: credentials, provider: .xai, refreshToken: "renewable")

        let reading = try await subject.refresh(automatic: true)
        let limited = try XCTUnwrap(reading.first?.snapshot)
        let calls = await transport.totalCount
        XCTAssertEqual(limited.status, .failed)
        XCTAssertNotEqual(limited.connectionStatus, .reconnectRequired)
        XCTAssertEqual(calls, 4, "the 401 must not trigger token discovery or renewal while throttled")

        let next = Transport(["xai": [rejected, rejected], "": [
            .init(body: #"{"token_endpoint":"https://auth.x.ai/token"}"#), rejected,
        ]])
        let beforeExpiry = container(next, credentials: credentials, at: start.addingTimeInterval(300))
        let skipped = try await beforeExpiry.refresh(automatic: true)
        let skippedCalls = await next.totalCount
        XCTAssertEqual(skippedCalls, 0)
        XCTAssertEqual(skipped.first?.snapshot, limited)

        let atExpiry = container(next, credentials: credentials, at: start.addingTimeInterval(900))
        let resumed = try await atExpiry.refresh(automatic: true)
        let resumedCalls = await next.totalCount
        XCTAssertEqual(resumedCalls, 4, "401 reported again can trigger token discovery and exchange")
        XCTAssertEqual(resumed.first?.snapshot?.connectionStatus, .reconnectRequired)
    }

    func testUsageHourCooldownSurvivesReopeningWithoutChangingLastReadingOrOtherAccounts() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["limited": [.usage] + Transport.Reply.limited("3600"), "healthy": [.usage, .usage, .usage, .usage]])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("limited", to: subject, credentials: credentials)
        try await addUsage("healthy", to: subject, credentials: credentials)
        try await subject.save(settings: AppSettings(syncIntervalMinutes: 15,
            statusIconsEnabled: true, pinnedAccountKeys: await subject.pinKeys(["limited"])))
        try await subject.refresh(automatic: true)
        let failureTime = start.addingTimeInterval(60)
        let failed = try await container(transport, credentials: credentials, at: failureTime).refresh(automatic: true)
        let last = try XCTUnwrap(failed.first { $0.account.id == "limited" }?.snapshot)
        XCTAssertEqual(last.status, .failed)
        XCTAssertEqual(last.fetchedAt, start)
        XCTAssertEqual(last.lastAttemptAt, failureTime)
        XCTAssertEqual(last.errorMessage, "Rate limited. Automatic refresh is paused. Refresh manually to try now.")
        XCTAssertEqual(last.windows.first?.usedPercent, 21)
        let published = GlanceSnapshotCodec.load(fromDirectory: directory)

        let next = Transport(["limited": [.usage], "healthy": [.usage, .usage]])
        let beforeExpiryTime = failureTime.addingTimeInterval(3_599)
        let beforeExpiry = container(next, credentials: credentials, at: beforeExpiryTime)
        let skipped = try await beforeExpiry.refresh(automatic: true)
        let limitedCalls = await next.count("limited")
        let healthyCalls = await next.count("healthy")
        XCTAssertEqual(limitedCalls, 0, "the final Retry-After must survive reopening, uncapped by in-request waits")
        XCTAssertEqual(healthyCalls, 1, "another account at the same provider is not throttled")
        XCTAssertEqual(skipped.first { $0.account.id == "limited" }?.snapshot, last)

        for date in [failureTime, beforeExpiryTime] {
            let entries = PinnedAccountsCapture.capture(source: published, at: date).accounts
            let entry = try XCTUnwrap(entries.first)
            XCTAssertEqual(entry.content, .init(content: .error, outdated: false))
            XCTAssertEqual(PinnedAccountText.cellText(entry), "!")
            XCTAssertEqual(PinnedAccountText.inlineRow(entries, texts: EnglishPinnedAccountTexts())?.text, "Error")
        }

        let atExpiry = container(next, credentials: credentials, at: failureTime.addingTimeInterval(3_600))
        try await atExpiry.refresh(automatic: true)
        let expiryCalls = await next.count("limited")
        XCTAssertEqual(expiryCalls, 1, "the read resumes exactly at the deadline")
    }

    func testUsageManualRefreshBypassesAndClearsActiveCooldown() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["usage": Transport.Reply.limited("3600")])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("usage", to: subject, credentials: credentials)
        try await subject.refresh(automatic: true)

        let next = Transport(["usage": [.usage, .usage]])
        let beforeExpiry = container(next, credentials: credentials, at: start.addingTimeInterval(300))
        let manual = try await beforeExpiry.refresh(automatic: false)
        XCTAssertEqual(manual.first?.snapshot?.status, .ok, "a person can retry before the deadline")

        let reopened = container(next, credentials: credentials, at: start.addingTimeInterval(301))
        try await reopened.refresh(automatic: true)
        let calls = await next.count("usage")
        XCTAssertEqual(calls, 2, "success clears the stored cooldown")
    }

    func testUsageRemovalDropsCooldownWhenTheSameAccountIsAddedAgain() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["usage": Transport.Reply.limited("3600")])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("usage", to: subject, credentials: credentials)
        try await subject.refresh(automatic: true)
        try await subject.remove(id: "usage")

        let reopened = container(Transport(["usage": [.usage]]), credentials: credentials, at: start.addingTimeInterval(300))
        try await addUsage("usage", to: reopened, credentials: credentials)
        let usage = try await reopened.refresh(automatic: true)
        XCTAssertEqual(usage.first?.snapshot?.status, .ok)
    }

    func testCreditWithoutRetryAfterWaitsFifteenMinutesWithoutChangingItsSnapshot() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["credit": [.balance] + Transport.Reply.limited(nil)])
        let subject = container(transport, credentials: credentials, at: start)
        try await subject.addCreditAccount(provider: .deepseek, key: "credit", label: "Work")
        let failureTime = start.addingTimeInterval(60)
        let failing = container(transport, credentials: credentials, at: failureTime)
        try await failing.refresh(automatic: true)
        let failed = await failing.credits()
        let last = try XCTUnwrap(failed.first?.snapshot)
        XCTAssertEqual(last.status, .failed)
        XCTAssertEqual(last.fetchedAt, start)
        XCTAssertEqual(last.lastAttemptAt, failureTime)
        XCTAssertEqual(last.error, "Rate limited. Automatic refresh is paused. Refresh manually to try now.")
        XCTAssertEqual(last.balances.first?.amount, 8)

        let next = Transport(["credit": [.balance]])
        let beforeExpiry = container(next, credentials: credentials, at: failureTime.addingTimeInterval(899))
        try await beforeExpiry.refresh(automatic: true)
        let calls = await next.count("credit")
        let skipped = await beforeExpiry.credits()
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(skipped.first?.account.label, "Work")
        XCTAssertEqual(skipped.first?.snapshot, last)

        let atExpiry = container(next, credentials: credentials, at: failureTime.addingTimeInterval(900))
        try await atExpiry.refresh(automatic: true)
        let expiryCalls = await next.count("credit")
        XCTAssertEqual(expiryCalls, 1)
    }

    func testAddingTheSameCreditKeyBypassesAndClearsActiveCooldown() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["credit": [.balance] + Transport.Reply.limited("3600")])
        let subject = container(transport, credentials: credentials, at: start)
        let account = try await subject.addCreditAccount(provider: .deepseek, key: "credit")
        try await subject.refresh(automatic: true)

        let next = Transport(["credit": [.balance, .balance]])
        let beforeExpiry = container(next, credentials: credentials, at: start.addingTimeInterval(300))
        let added = try await beforeExpiry.addCreditAccount(provider: .deepseek, key: "credit")
        XCTAssertEqual(added.id, account.id)

        let reopened = container(next, credentials: credentials, at: start.addingTimeInterval(301))
        try await reopened.refresh(automatic: true)
        let calls = await next.count("credit")
        XCTAssertEqual(calls, 2, "the verified balance written by add clears the stored cooldown")
    }

    func testLateUsageThrottleDoesNotAffectAnAccountAddedUnderTheSameID() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["usage": Transport.Reply.limited("3600") + [.usage]], hold: ("usage", 3))
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("usage", to: subject, credentials: credentials)
        let inFlight = Task { try await subject.refresh(automatic: true) }
        await transport.waitForHeldReply()
        do {
            try await subject.remove(id: "usage")
            try await addUsage("usage", to: subject, credentials: credentials, createdAt: start.addingTimeInterval(1))
        } catch {
            await transport.releaseReply()
            throw error
        }
        await transport.releaseReply()
        let afterOldResponse = try await inFlight.value
        XCTAssertNil(afterOldResponse.first?.snapshot, "the discarded account's response must not survive removal")

        let refreshed = try await subject.refresh(automatic: true)
        XCTAssertEqual(refreshed.first?.snapshot?.status, .ok)
    }

    func testLateCreditThrottleDoesNotAffectAnAccountAddedUnderTheSameID() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["credit": [.balance] + Transport.Reply.limited("3600") + [.balance, .balance]],
            hold: ("credit", 4))
        let clock = MovableClock(start)
        let subject = UsageLimitsContainer(directory: directory, credentials: credentials, transport: transport,
            now: { clock.now })
        let original = try await subject.addCreditAccount(provider: .deepseek, key: "credit", label: "Old")
        let inFlight = Task { try await subject.refresh(automatic: true) }
        await transport.waitForHeldReply()
        let replacement: CreditAccount
        do {
            clock.advance(by: 1)
            try await subject.removeCreditAccount(id: original.id)
            replacement = try await subject.addCreditAccount(provider: .deepseek, key: "credit", label: "New")
        } catch {
            await transport.releaseReply()
            throw error
        }
        await transport.releaseReply()
        _ = try await inFlight.value
        let afterOldResponse = await subject.credits()
        XCTAssertEqual(replacement.id, original.id)
        XCTAssertEqual(afterOldResponse.first?.account.label, "New")
        XCTAssertEqual(afterOldResponse.first?.snapshot?.status, .ok)

        try await subject.refresh(automatic: true)
        let calls = await transport.count("credit")
        XCTAssertEqual(calls, 6, "the stale response must not pause the replacement's sync")
    }

    func testManualCreditRefreshDoesNotJoinAnAutomaticPassThatSkippedIt() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport([
            "limited": [.balance] + Transport.Reply.limited("3600") + [.balance],
            "healthy": [.balance, .balance, .balance],
        ], hold: ("healthy", 2))
        let subject = container(transport, credentials: credentials, at: start)
        try await subject.addCreditAccount(provider: .deepseek, key: "limited", label: "Limited")
        try await subject.refresh(automatic: true)
        try await subject.addCreditAccount(provider: .deepseek, key: "healthy", label: "Healthy")

        try await SerialScheduling.run {
            let automatic = Task { try await subject.refresh(automatic: true) }
            await transport.waitForHeldReply()
            let manual = Task { try await subject.refresh(automatic: false) }
            await SerialScheduling.settle()
            let pausedCalls = await transport.count("limited")
            XCTAssertEqual(pausedCalls, 4, "the running automatic pass leaves this account alone")

            await transport.releaseReply()
            _ = try await automatic.value
            _ = try await manual.value
            let manualCalls = await transport.count("limited")
            XCTAssertEqual(manualCalls, 5, "the waiting person gets a read in their own pass")
        }
    }
}
