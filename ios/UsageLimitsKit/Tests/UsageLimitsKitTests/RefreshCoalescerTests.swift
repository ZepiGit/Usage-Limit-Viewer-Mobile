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

    /// Holds the first usage request for the token "held" until `release()`, and answers every
    /// other request at once. The token decides the body, so each account reads its own figures.
    private actor Gate {
        private var arrived = false
        private var arrivalWaiters: [CheckedContinuation<Void, Never>] = []
        private var released = false
        private var releaseWaiters: [CheckedContinuation<Void, Never>] = []

        func hold() async {
            arrived = true
            arrivalWaiters.forEach { $0.resume() }
            arrivalWaiters = []
            guard !released else { return }
            await withCheckedContinuation { releaseWaiters.append($0) }
        }

        func waitForArrival() async {
            guard !arrived else { return }
            await withCheckedContinuation { arrivalWaiters.append($0) }
        }

        func release() {
            released = true
            releaseWaiters.forEach { $0.resume() }
            releaseWaiters = []
        }
    }

    private struct GatedTransport: HTTPTransport {
        let gate: Gate
        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
            let body: String
            if request.value(forHTTPHeaderField: "OpenAI-Beta") != nil {
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

        await gate.release()
        await running.value
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
        await running.value
        for request in waiting { await request.value }

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
        await running.value
        await requested.value

        let events = await announcements.events
        XCTAssertEqual(
            events.filter { $0.accountId == "low" }.count, 1,
            "announced by the running pass, swallowed in the follow-up: \(events.map(\.key))")
        XCTAssertEqual(events.filter { $0.accountId == "new" }.count, 1)
        let passes = await announcements.passes
        XCTAssertEqual(passes, 2)
    }
}
