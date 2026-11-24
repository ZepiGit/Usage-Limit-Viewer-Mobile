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

        /// How many requests have reached the gate, which is how many passes have begun.
        var arrivalCount: Int { arrivals }

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
        let coalescer = RefreshCoalescer { automatic in
            _ = try? await container.refresh(automatic: automatic)
            await announcements.record((try? await container.pendingNotifications()) ?? [])
        }
        return (container, coalescer, gate, announcements)
    }

    /// What the new account looks like from outside: its reading, the widget's figure for it,
    /// and whether an alert about it was claimed.
    private struct NewAccountState: Equatable {
        var usedPercent: Double?
        var widgetRemainingPercent: Double?
        var claimed: Bool
    }

    private func newAccountState(
        _ container: UsageLimitsContainer, _ announcements: Announcements
    ) async -> NewAccountState {
        let usage = await container.usage()
        let published = GlanceSnapshotCodec.read(fromDirectory: directory)
        return NewAccountState(
            usedPercent: usage.first { $0.account.id == "new" }?.snapshot?.windows.first?.usedPercent,
            widgetRemainingPercent: published.accounts.first { $0.id == "new" }?.rows.first?.remainingPercent,
            claimed: await announcements.events.contains { $0.accountId == "new" })
    }

    /// The new account after a sync: 95 % used, 5 % left on the widget, its alert claimed.
    private let synced = NewAccountState(usedPercent: 95, widgetRemainingPercent: 5, claimed: true)

    /// Watches one caller's wait, the way the caller itself would: it awaits the task `request()`
    /// returned and, the moment that returns, records what it can see — so a test reads what the
    /// caller would render, not what the world looks like after later passes.
    @MainActor
    private final class Caller {
        private(set) var sawOnRelease: NewAccountState?
        var isReleased: Bool { sawOnRelease != nil }

        init(_ wait: Task<Void, Never>, seeing look: @escaping @MainActor () async -> NewAccountState) {
            Task { @MainActor in
                await wait.value
                self.sawOnRelease = await look()
            }
        }
    }

    func testOnlyAnExplicitRequestMakesAQueuedPassManual() async throws {
        actor Origins {
            var automatic: [Bool] = []
            func append(_ value: Bool) { automatic.append(value) }
        }
        await SerialScheduling.run {
            let gate = Gate()
            let origins = Origins()
            let coalescer = RefreshCoalescer { automatic in
                await origins.append(automatic)
                await gate.hold()
            }

            let first = await coalescer.request(automatic: true)
            await SerialScheduling.settle()
            let pendingAutomatic = await coalescer.request(automatic: true)
            let pendingManual = await coalescer.request(automatic: false)
            let stillAutomatic = await coalescer.request(automatic: true)
            await SerialScheduling.settle()
            let firstOrigin = await origins.automatic
            XCTAssertEqual(firstOrigin, [true], "new requests must not change the running pass")

            await gate.release(1)
            await SerialScheduling.settle()
            let last = await coalescer.request(automatic: true)
            await gate.release()
            for request in [first, pendingAutomatic, pendingManual, stillAutomatic, last] {
                await request.value
            }
            let sequence = await origins.automatic
            XCTAssertEqual(sequence, [true, false, true],
                "a manual request must survive coalescing without promoting later automatic work")
        }
    }

    func testAnAccountAddedDuringARunningRefreshIsSyncedWithoutAnotherRequest() async throws {
        try await SerialScheduling.run {
            let (container, coalescer, gate, announcements) = world()
            let look = { await self.newAccountState(container, announcements) }
            try await container.add(account("old"))

            // A refresh is in flight and has already read its account list: it is waiting on
            // the provider for the only account it knows about.
            let launch = Caller(await coalescer.request(), seeing: look)
            await SerialScheduling.settle()
            let begun = await gate.arrivalCount
            XCTAssertEqual(begun, 1, "the first pass is at the provider")

            // Sign-in finishes, stores the new account, and asks for a refresh.
            try await container.add(account("new"))
            let signIn = Caller(await coalescer.request(), seeing: look)
            await SerialScheduling.settle()
            XCTAssertFalse(signIn.isReleased, "the sign-in waits while the first pass is held")
            XCTAssertFalse(launch.isReleased)

            await gate.release()
            await SerialScheduling.settle()
            XCTAssertTrue(launch.isReleased)
            XCTAssertEqual(signIn.sawOnRelease, synced, "what the sign-in sees when its wait ends")
        }
    }

    func testACallerIsReleasedByItsOwnPassNotByLaterOnes() async throws {
        // A sign-in that finishes during one refresh waits for the follow-up, and only for it: a
        // widget link tapped while that follow-up runs needs a third pass, and the sign-in sheet
        // must not stay open for it.
        try await SerialScheduling.run {
            let (container, coalescer, gate, announcements) = world()
            let look = { await self.newAccountState(container, announcements) }
            try await container.add(account("old"))

            let launch = Caller(await coalescer.request(), seeing: look)
            await SerialScheduling.settle()
            let first = await gate.arrivalCount
            XCTAssertEqual(first, 1, "pass 1 is held at the provider")

            try await container.add(account("new"))
            let signIn = Caller(await coalescer.request(), seeing: look)
            await SerialScheduling.settle()
            XCTAssertFalse(launch.isReleased, "pass 1 is still held")
            XCTAssertFalse(signIn.isReleased, "pass 1 is still held")

            // Pass 1 ends; pass 2, the sign-in's own, begins and is held in turn.
            await gate.release(1)
            await SerialScheduling.settle()
            let second = await gate.arrivalCount
            XCTAssertEqual(second, 2, "pass 2 is held at the provider")
            XCTAssertTrue(launch.isReleased, "the launch refresh is released by pass 1")
            XCTAssertFalse(signIn.isReleased, "the sign-in waits for pass 2, which is held")

            let link = Caller(await coalescer.request(), seeing: look)
            await SerialScheduling.settle()
            XCTAssertFalse(link.isReleased)

            // Pass 2 ends; pass 3, the link's, begins and stays held.
            await gate.release(2)
            await SerialScheduling.settle()
            let third = await gate.arrivalCount
            XCTAssertEqual(third, 3, "pass 3 is held at the provider")
            XCTAssertEqual(signIn.sawOnRelease, synced, "the sign-in is released by pass 2")
            XCTAssertFalse(link.isReleased, "the link asked during pass 2 waits for pass 3")

            await gate.release()
            await SerialScheduling.settle()
            XCTAssertTrue(link.isReleased)
            let passes = await announcements.passes
            XCTAssertEqual(passes, 3)
        }
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
