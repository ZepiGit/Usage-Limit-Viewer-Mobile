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
            static let balance = Reply(body: #"{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"8.00"}]}"#)

            static func limited(_ finalHint: String?) -> [Reply] {
                let immediate = Reply(status: 429, body: "", headers: ["Retry-After": "0"])
                return [immediate, immediate, Reply(status: 429, body: "",
                    headers: finalHint.map { ["Retry-After": $0] } ?? [:])]
            }
        }

        private var replies: [String: [Reply]]
        private var attempts: [String: Int] = [:]
        private let hold: (token: String, attempt: Int)?
        private var held: CheckedContinuation<Void, Never>?
        private var awaitingHold: CheckedContinuation<Void, Never>?

        init(_ replies: [String: [Reply]], hold: (String, Int)? = nil) {
            self.replies = replies
            self.hold = hold
        }

        func count(_ token: String) -> Int { attempts[token, default: 0] }

        func waitForHeldReply() async {
            if held == nil { await withCheckedContinuation { awaitingHold = $0 } }
        }

        func releaseReply() {
            held?.resume()
            held = nil
        }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let token = String((request.value(forHTTPHeaderField: "Authorization") ?? "").dropFirst("Bearer ".count))
            attempts[token, default: 0] += 1
            let reply = replies[token]?.isEmpty == false ? replies[token]!.removeFirst()
                : Reply(status: 400, body: "Unexpected request")
            if let hold, hold.token == token, hold.attempt == attempts[token] {
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
        _ id: String, to subject: UsageLimitsContainer, credentials: InMemoryCredentialStore, createdAt: Date? = nil
    ) async throws {
        try await credentials.save(OAuthCredentials(accessToken: id, refreshToken: nil), reference: "ref-\(id)")
        try await subject.add(ProviderAccount(id: id, provider: .claude, externalAccountID: id,
            email: nil, displayName: nil, plan: nil, credentialReference: "ref-\(id)",
            createdAt: createdAt ?? start, lastSuccessfulSync: nil))
    }

    func testUsageHourCooldownSurvivesReopeningWithoutChangingLastReadingOrOtherAccounts() async throws {
        let credentials = InMemoryCredentialStore()
        let transport = Transport(["limited": [.usage] + Transport.Reply.limited("3600"), "healthy": [.usage, .usage, .usage, .usage]])
        let subject = container(transport, credentials: credentials, at: start)
        try await addUsage("limited", to: subject, credentials: credentials)
        try await addUsage("healthy", to: subject, credentials: credentials)
        try await subject.refresh(automatic: true)
        let failureTime = start.addingTimeInterval(60)
        let failed = try await container(transport, credentials: credentials, at: failureTime).refresh(automatic: true)
        let last = try XCTUnwrap(failed.first { $0.account.id == "limited" }?.snapshot)
        XCTAssertEqual(last.status, .failed)
        XCTAssertEqual(last.fetchedAt, start)
        XCTAssertEqual(last.lastAttemptAt, failureTime)
        XCTAssertEqual(last.errorMessage, "No usage could be read (claude usage is rate limiting this account).")
        XCTAssertEqual(last.windows.first?.usedPercent, 21)

        let next = Transport(["limited": [.usage], "healthy": [.usage, .usage]])
        let beforeExpiry = container(next, credentials: credentials, at: failureTime.addingTimeInterval(3_599))
        let skipped = try await beforeExpiry.refresh(automatic: true)
        let limitedCalls = await next.count("limited")
        let healthyCalls = await next.count("healthy")
        XCTAssertEqual(limitedCalls, 0, "the final Retry-After must survive reopening, uncapped by in-request waits")
        XCTAssertEqual(healthyCalls, 1, "another account at the same provider is not throttled")
        XCTAssertEqual(skipped.first { $0.account.id == "limited" }?.snapshot, last)

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
        XCTAssertEqual(last.error, "No balance could be read (deepseek balance is rate limiting this key).")
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
