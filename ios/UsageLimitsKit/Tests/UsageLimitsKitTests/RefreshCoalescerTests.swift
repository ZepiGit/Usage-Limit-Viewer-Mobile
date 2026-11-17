import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A refresh asked for while another is running, which is what finishing a sign-in does when a
/// pull-to-refresh or the launch refresh is still under way (#43).
///
/// The running pass read its account list before the new account existed, so it cannot be the
/// pass that syncs it. These tests drive the real container through the coalescer the app's
/// store uses, with one account's usage request held open until the test lets it go — which is
/// what makes the race happen every time rather than on a slow network.
final class RefreshCoalescerTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("coalescer-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// 40 % used: nothing for the evaluator to say.
    private static let healthy = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": true, "limit_reached": false,
        "primary_window": { "used_percent": 40, "limit_window_seconds": 604800,
                            "reset_after_seconds": 469200, "reset_at": 1757469200 },
        "secondary_window": null } }
    """

    /// 95 % used of a 5-hour window: an edge the evaluator announces.
    private static let low = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": true, "limit_reached": false,
        "primary_window": { "used_percent": 95, "limit_window_seconds": 18000,
                            "reset_after_seconds": 3600, "reset_at": 1757003600 },
        "secondary_window": null } }
    """

    /// Holds every usage request for the token "held" until the test lets it through, and
    /// answers every other request at once (the reset-credit lookup that follows a usage read is
    /// not held). One usage request is made per pass, so the n-th
    /// arrival is the n-th pass reaching the provider, and releasing it one at a time is what
    /// steps the passes. The token decides the body, so each account reads its own figures.
    private actor Gate {
        private var arrivals = 0
        private var arrivalWaiters: [(count: Int, continuation: CheckedContinuation<Void, Never>)] = []
        private var released: Set<Int> = []
        private var releasedAll = false
        private var held: [Int: CheckedContinuation<Void, Never>] = [:]

        func hold() async {
            arrivals += 1
            let arrival = arrivals
            let ready = arrivalWaiters.filter { $0.count <= arrival }
            arrivalWaiters.removeAll { $0.count <= arrival }
            ready.forEach { $0.continuation.resume() }
            guard !releasedAll, !released.contains(arrival) else { return }
            await withCheckedContinuation { held[arrival] = $0 }
        }

        /// Returns once `count` requests have reached the gate.
        func waitForArrival(_ count: Int = 1) async {
            guard arrivals < count else { return }
            await withCheckedContinuation { arrivalWaiters.append((count, $0)) }
        }

        /// Lets the `arrival`-th request through, now or when it comes.
        func release(_ arrival: Int) {
            released.insert(arrival)
            held.removeValue(forKey: arrival)?.resume()
        }

        /// Lets every request through, held or still to come.
        func release() {
            releasedAll = true
            held.values.forEach { $0.resume() }
            held = [:]
        }
    }

    /// Set once a caller's wait is over, so a test can see that it has NOT ended yet.
    private actor Flag {
        private(set) var isSet = false
        func set() { isSet = true }
    }

    private struct GatedTransport: HTTPTransport {
        let gate: Gate
        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
            let body: String
            if request.url?.lastPathComponent != "usage" {
                body = #"{"credits": []}"#
            } else if auth.contains("held") {
                await gate.hold()
                body = RefreshCoalescerTests.healthy
            } else {
                body = RefreshCoalescerTests.low
            }
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(body.utf8), response)
        }
    }

    /// What every pass claimed, in order, and how many passes ran.
    private actor Announcements {
        private(set) var events: [NotificationEvaluator.Event] = []
        private(set) var passes = 0
        func record(_ claimed: [NotificationEvaluator.Event]) {
            passes += 1
            events += claimed
        }
    }

    private func account(_ id: String) -> ProviderAccount {
        ProviderAccount(
            id: id, provider: .codex, externalAccountID: "ext-\(id)", email: "\(id)@example.com",
            displayName: nil, plan: nil, credentialReference: "ref-\(id)",
            createdAt: Date(timeIntervalSince1970: 0), lastSuccessfulSync: nil)
    }

    /// The container, a coalescer whose pass is the store's (sync, then claim alerts), and the
    /// record of what each pass claimed.
    private func world() -> (UsageLimitsContainer, RefreshCoalescer, Gate, Announcements) {
        let gate = Gate()
        let container = UsageLimitsContainer(
            directory: directory,
            credentials: InMemoryCredentialStore(credentials: [
                "ref-old": OAuthCredentials(accessToken: "held", refreshToken: "r"),
                "ref-new": OAuthCredentials(accessToken: "fresh", refreshToken: "r"),
                "ref-low": OAuthCredentials(accessToken: "fresh", refreshToken: "r")]),
            transport: GatedTransport(gate: gate),
            now: { [now] in now })
        let announcements = Announcements()
        let coalescer = RefreshCoalescer {
            _ = try? await container.refresh()
            await announcements.record((try? await container.pendingNotifications()) ?? [])
        }
        return (container, coalescer, gate, announcements)
    }

    func testAnAccountAddedDuringARunningRefreshIsSyncedWithoutAnotherRequest() async throws {
        let (container, coalescer, gate, announcements) = world()
        try await container.add(account("old"))

        // A refresh is in flight and has already read its account list: it is waiting on the
        // provider for the only account it knows about.
        let running = await coalescer.request()
        await gate.waitForArrival()

        // Sign-in finishes, stores the new account, and asks for a refresh.
        try await container.add(account("new"))
        let requested = await coalescer.request()

        // The sign-in's wait must not end while the pass that predates it is still running.
        let endedEarly = expectation(description: "the sign-in's wait ended before any pass ran")
        endedEarly.isInverted = true
        let earlyWatch = Task { await requested.value; endedEarly.fulfill() }
        await fulfillment(of: [endedEarly], timeout: 0.3)

        // What the sign-in sees the moment its own wait ends, not after the whole run.
        await gate.release()
        await requested.value

        let usage = await container.usage()
        XCTAssertEqual(
            usage.first { $0.account.id == "new" }?.snapshot?.windows.first?.usedPercent, 95,
            "the new account has a reading")
        let published = GlanceSnapshotCodec.read(fromDirectory: directory)
        XCTAssertEqual(
            published.accounts.first { $0.id == "new" }?.rows.first?.remainingPercent, 5,
            "the widget data carries the new account's reading")
        let claimed = await announcements.events.filter { $0.accountId == "new" }
        XCTAssertFalse(claimed.isEmpty, "the new account's alert was claimed")

        await earlyWatch.value
        await running.value
    }

    func testACallerIsReleasedByItsOwnPassNotByLaterOnes() async throws {
        // A sign-in that finishes during one refresh waits for the follow-up, and only for it: a
        // widget link tapped while that follow-up runs needs a third pass, and the sign-in sheet
        // must not stay open for it.
        let (container, coalescer, gate, announcements) = world()
        try await container.add(account("old"))

        let running = await coalescer.request()
        await gate.waitForArrival(1)
        try await container.add(account("new"))
        let signIn = await coalescer.request()

        await gate.release(1)
        await gate.waitForArrival(2)
        let link = await coalescer.request()
        let linkDone = Flag()
        let linkWaiter = Task { await link.value; await linkDone.set() }
        await gate.release(2)

        // The third pass is now held at the provider. The sign-in's own pass is not.
        let signInDone = expectation(description: "the sign-in's wait ends after its own pass")
        let directory = directory!
        let signInWaiter = Task {
            await signIn.value
            let usage = await container.usage()
            XCTAssertEqual(
                usage.first { $0.account.id == "new" }?.snapshot?.windows.first?.usedPercent, 95,
                "the new account has a reading when the sign-in's wait ends")
            XCTAssertEqual(
                GlanceSnapshotCodec.read(fromDirectory: directory)
                    .accounts.first { $0.id == "new" }?.rows.first?.remainingPercent, 5,
                "and the widget data carries it")
            let claimed = await announcements.events.filter { $0.accountId == "new" }
            XCTAssertFalse(claimed.isEmpty, "and its alert was claimed")
            signInDone.fulfill()
        }
        await fulfillment(of: [signInDone], timeout: 5)

        await gate.waitForArrival(3)
        let isLinkDone = await linkDone.isSet
        XCTAssertFalse(isLinkDone, "the link asked during pass 2 waits for pass 3")

        await gate.release()
        await signInWaiter.value
        await linkWaiter.value
        await running.value
        let passes = await announcements.passes
        XCTAssertEqual(passes, 3)
    }

    func testRequestsDuringOnePassShareASingleFollowUp() async throws {
        let (container, coalescer, gate, announcements) = world()
        try await container.add(account("old"))

        let running = await coalescer.request()
        await gate.waitForArrival()
        // A sign-in, a pull-to-refresh and a widget link, all while the first pass waits.
        let waiting = [
            await coalescer.request(), await coalescer.request(), await coalescer.request()]

        await gate.release()
        for request in waiting { await request.value }
        await running.value

        let passes = await announcements.passes
        XCTAssertEqual(passes, 2, "the running pass, then one follow-up for all three requests")
    }

    func testTheFollowUpPassDoesNotAnnounceAnEdgeAgain() async throws {
        // The follow-up re-syncs every account, including one the running pass already found low
        // and announced. The ledger is what keeps that second look silent.
        let (container, coalescer, gate, announcements) = world()
        try await container.add(account("old"))
        try await container.add(account("low"))

        let running = await coalescer.request()
        await gate.waitForArrival()
        try await container.add(account("new"))
        let requested = await coalescer.request()

        await gate.release()
        await requested.value
        await running.value

        let events = await announcements.events
        XCTAssertEqual(
            events.filter { $0.accountId == "low" }.count, 1,
            "announced by the running pass, swallowed in the follow-up: \(events.map(\.key))")
        XCTAssertEqual(events.filter { $0.accountId == "new" }.count, 1)
        let passes = await announcements.passes
        XCTAssertEqual(passes, 2)
    }
}
