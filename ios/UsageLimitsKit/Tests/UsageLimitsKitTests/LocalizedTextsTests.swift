import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// MARK: - A second language, for tests only

/// German, in the wording of the Android `values-de` resources, as a stand-in for any
/// catalog-backed implementation the app supplies.
///
/// Test-only on purpose: the kit ships English and nothing else. This exists to prove that every
/// sentence the kit builds goes THROUGH the protocol, which an English-only run cannot show,
/// since a literal left in the kit would print the same words.
private struct GermanTestTexts: CountdownTexts {
    func now() -> String { "jetzt" }
    func daysHours(days: Int, hours: Int) -> String { "\(days) T. \(hours) Std." }
    func days(_ days: Int) -> String { "\(days) T." }
    func hoursMinutes(hours: Int, minutes: Int) -> String { "\(hours) Std. \(minutes) Min." }
    func hours(_ hours: Int) -> String { "\(hours) Std." }
    func minutes(_ minutes: Int) -> String { "\(minutes) Min." }
    func underOneMinute() -> String { "<1 Min." }
    func resetsIn(duration: String) -> String { "Reset in \(duration)" }
    func resetsAt(stamp: String) -> String { "Reset \(stamp)" }
    func asOf(stamp: String) -> String { "Stand \(stamp)" }
    func updatedJustNow() -> String { "Gerade aktualisiert" }
    func updatedAgo(duration: String) -> String { "Aktualisiert vor \(duration)" }
    // Android has no twin for these three: wording follows the same glossary.
    func resetPending() -> String { "Reset · Aktualisierung ausstehend" }
    func latestUpdate(stamp: String) -> String { "Letzte Aktualisierung \(stamp)" }
    func renews(label: String, duration: String) -> String { "\(label) erneuert sich in \(duration)" }
}

private struct GermanTestNotificationTexts: NotificationTexts {
    var countdown: any CountdownTexts { GermanTestTexts() }

    func warning(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return "\(name) · \(label): weniger als 20 % übrig"
        case .used: return "\(name) · \(label): mehr als 80 % verbraucht"
        }
    }

    func critical(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return "\(name) · \(label): weniger als 10 % übrig"
        case .used: return "\(name) · \(label): mehr als 90 % verbraucht"
        }
    }

    func exhausted(name: String, label: String) -> String { "\(name) · \(label) aufgebraucht" }

    func resetApproaching(name: String, label: String, minutes: Int) -> String {
        let unit = minutes == 1 ? "Minute" : "Minuten"
        return "\(name) · \(label): Reset in etwa \(minutes) \(unit)"
    }

    func creditExpiring(name: String, duration: String) -> String {
        "\(name) · ein Reset-Credit läuft in \(duration) ab"
    }

    func creditsAvailable(name: String, count: Int) -> String {
        let noun = count == 1 ? "Reset-Credit" : "Reset-Credits"
        return "\(name) · \(count) \(noun) verfügbar"
    }

    func needsReconnect(name: String) -> String { "\(name) muss neu verbunden werden" }
}

/// The words the kit speaks, in English by default and in whatever the caller injects.
///
/// Three things are pinned. The English defaults are the strings the app has always shown, so
/// nothing changes for a caller that passes no texts. A second language reaches every line, so
/// no sentence is built around the protocol. And the English sentences the kit STORES are
/// recognised as data at the display, so they can be shown in the current language without
/// being rewritten.
final class LocalizedTextsTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private let german = GermanTestTexts()

    /// Fixed, so a clock time does not depend on the machine running the test.
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    // MARK: - Countdown shapes

    func testTheEnglishDefaultSpeaksTheWordsTheAppAlwaysHad() {
        let table: [(Int, String)] = [
            (Int.min, "now"), (-120, "now"), (0, "now"),
            (1, "<1m"), (59, "<1m"), (60, "1m"), (8 * 60, "8m"), (59 * 60 + 59, "59m"),
            (3_600, "1h"), (3 * 3_600, "3h"), (3 * 3_600 + 12 * 60, "3h 12m"),
            (3 * 3_600 + 12 * 60 + 45, "3h 12m"),
            (86_400, "1d"), (2 * 86_400, "2d"), (2 * 86_400 + 4 * 3_600, "2d 4h"),
            (2 * 86_400 + 4 * 3_600 + 30 * 60, "2d 4h"), (90_061, "1d 1h"),
        ]
        for (seconds, expected) in table {
            XCTAssertEqual(Countdown.format(seconds: seconds), expected, "\(seconds) s")
            XCTAssertEqual(
                Countdown.format(seconds: seconds, texts: EnglishCountdownTexts()), expected,
                "\(seconds) s, explicit English")
        }
    }

    func testTheEnglishDefaultReachesTheDateForm() {
        XCTAssertEqual(Countdown.format(until: now.addingTimeInterval(3_600), from: now), "1h")
        XCTAssertEqual(Countdown.format(until: now.addingTimeInterval(-1), from: now), "now")
        let far = Date(timeIntervalSince1970: Double(Int.max))
        XCTAssertEqual(
            Countdown.format(until: far, from: Date(timeIntervalSince1970: 0)),
            Countdown.format(seconds: Int.max))
    }

    func testEveryCountdownShapeGoesThroughTheInjectedTexts() {
        let table: [(Int, String)] = [
            (Int.min, "jetzt"), (-120, "jetzt"), (0, "jetzt"),
            (1, "<1 Min."), (59, "<1 Min."), (60, "1 Min."), (8 * 60, "8 Min."),
            (3_600, "1 Std."), (3 * 3_600, "3 Std."), (3 * 3_600 + 12 * 60, "3 Std. 12 Min."),
            (86_400, "1 T."), (2 * 86_400, "2 T."), (2 * 86_400 + 4 * 3_600, "2 T. 4 Std."),
        ]
        for (seconds, expected) in table {
            XCTAssertEqual(Countdown.format(seconds: seconds, texts: german), expected, "\(seconds) s")
        }

        XCTAssertEqual(
            Countdown.format(until: now.addingTimeInterval(3 * 3_600 + 12 * 60), from: now, texts: german),
            "3 Std. 12 Min.")
        XCTAssertEqual(
            Countdown.format(until: now.addingTimeInterval(-1), from: now, texts: german), "jetzt")
        XCTAssertEqual(
            Countdown.format(until: Date(timeIntervalSince1970: .nan), from: now, texts: german),
            "jetzt")
        XCTAssertEqual(
            Countdown.format(until: .distantPast, from: .distantFuture, texts: german), "jetzt")
    }

    // MARK: - Lines built around a duration or a clock time

    func testTheEnglishLinesAreTheOnesTheScreensAlwaysComposed() {
        let reset = now.addingTimeInterval(3_600)
        let stamp = Countdown.absolute(reset, now: now, calendar: utc)

        XCTAssertEqual(Countdown.resetsInLine(until: now.addingTimeInterval(2 * 86_400 + 4 * 3_600), from: now), "Resets in 2d 4h")
        // A passed reset still names a duration, as the screens did before the line moved here.
        XCTAssertEqual(Countdown.resetsInLine(until: now.addingTimeInterval(-5), from: now), "Resets in now")

        XCTAssertNil(Countdown.resetLine(resetAt: nil, now: now))
        XCTAssertEqual(Countdown.resetLine(resetAt: reset, now: now, calendar: utc), "Resets \(stamp)")
        XCTAssertEqual(Countdown.resetLine(resetAt: now, now: now, calendar: utc), "Reset · refresh pending")
        XCTAssertEqual(
            Countdown.resetLine(resetAt: now.addingTimeInterval(-60), now: now, calendar: utc),
            "Reset · refresh pending")

        XCTAssertNil(Countdown.asOfLine(updatedAt: nil, now: now))
        XCTAssertEqual(Countdown.asOfLine(updatedAt: reset, now: now, calendar: utc), "As of \(stamp)")
        XCTAssertNil(Countdown.latestUpdateLine(updatedAt: nil, now: now))
        XCTAssertEqual(
            Countdown.latestUpdateLine(updatedAt: reset, now: now, calendar: utc),
            "Latest update \(stamp)")

        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 0), "Updated just now")
        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 59), "Updated just now")
        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 60), "Updated 1m ago")
        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 3 * 3_600), "Updated 3h ago")

        XCTAssertEqual(
            Countdown.renewsLine(label: "Weekly", until: now.addingTimeInterval(3 * 86_400), from: now),
            "Weekly renews 3d")
    }

    func testEveryLineGoesThroughTheInjectedTexts() {
        let reset = now.addingTimeInterval(3_600)
        let stamp = Countdown.absolute(reset, now: now, calendar: utc)

        XCTAssertEqual(
            Countdown.resetsInLine(until: now.addingTimeInterval(2 * 86_400 + 4 * 3_600), from: now, texts: german),
            "Reset in 2 T. 4 Std.")
        XCTAssertEqual(
            Countdown.resetsInLine(until: now.addingTimeInterval(-5), from: now, texts: german),
            "Reset in jetzt")

        XCTAssertNil(Countdown.resetLine(resetAt: nil, now: now, texts: german))
        XCTAssertEqual(
            Countdown.resetLine(resetAt: reset, now: now, calendar: utc, texts: german), "Reset \(stamp)")
        XCTAssertEqual(
            Countdown.resetLine(resetAt: now, now: now, calendar: utc, texts: german),
            "Reset · Aktualisierung ausstehend")

        XCTAssertEqual(
            Countdown.asOfLine(updatedAt: reset, now: now, calendar: utc, texts: german), "Stand \(stamp)")
        XCTAssertEqual(
            Countdown.latestUpdateLine(updatedAt: reset, now: now, calendar: utc, texts: german),
            "Letzte Aktualisierung \(stamp)")

        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 10, texts: german), "Gerade aktualisiert")
        XCTAssertEqual(Countdown.updatedLine(secondsAgo: 37 * 60, texts: german), "Aktualisiert vor 37 Min.")
        XCTAssertEqual(
            Countdown.updatedLine(secondsAgo: 3 * 3_600 + 5 * 60, texts: german),
            "Aktualisiert vor 3 Std. 5 Min.")

        XCTAssertEqual(
            Countdown.renewsLine(label: "Weekly", until: now.addingTimeInterval(3 * 86_400 + 2 * 3_600), from: now, texts: german),
            "Weekly erneuert sich in 3 T. 2 Std.")
    }

    // MARK: - Notification sentences

    private func summary(
        remaining: Double?,
        exhausted: Bool = false,
        resetAt: Date? = nil,
        credits: [ResetCredit] = [],
        failed: Bool = false,
        errorMessage: String? = nil
    ) -> AccountSummary {
        AccountSummary(
            accountId: "acct",
            label: "Account acct",
            snapshot: UsageSnapshot(
                accountID: "acct", fetchedAt: now, status: failed ? .failed : .ok,
                windows: [
                    UsageWindow(
                        id: "w", label: "5h limit", category: .fiveHour,
                        usedPercent: remaining.map { 100 - $0 },
                        periodSeconds: 18_000, resetAt: resetAt, exhausted: exhausted),
                ],
                resetCredits: credits,
                errorMessage: errorMessage))
    }

    private func credit(_ id: String, expiresIn seconds: TimeInterval) -> ResetCredit {
        ResetCredit(
            id: id, grantedAt: now.addingTimeInterval(-1_000),
            expiresAt: now.addingTimeInterval(seconds), status: "available")
    }

    /// One evaluation, flattened to what a user is told: the lines posted, then the standing facts.
    private func said(
        _ accounts: [AccountSummary],
        settings: NotificationSettings = NotificationSettings(),
        mode: PercentDisplay = .left,
        texts: (any NotificationTexts)? = nil
    ) -> [String] {
        let outcome: NotificationEvaluator.Outcome
        if let texts {
            outcome = NotificationEvaluator.evaluate(
                accounts: accounts, settings: settings, states: [:], now: now,
                percentDisplay: mode, texts: texts)
        } else {
            outcome = NotificationEvaluator.evaluate(
                accounts: accounts, settings: settings, states: [:], now: now,
                percentDisplay: mode)
        }
        return outcome.events.map(\.line).filter { !$0.isEmpty } + outcome.standingFindings
    }

    private struct NotificationCase {
        let name: String
        let accounts: [AccountSummary]
        let settings: NotificationSettings
        let mode: PercentDisplay
        let english: [String]
        let german: [String]
    }

    private var resetApproachingEnabled: NotificationSettings {
        var settings = NotificationSettings()
        settings.notifyOnResetApproaching = true
        return settings
    }

    private var notificationCases: [NotificationCase] {
        let plain = NotificationSettings()
        return [
            NotificationCase(
                name: "warning, left", accounts: [summary(remaining: 15)], settings: plain, mode: .left,
                english: ["Account acct · 5h limit: less than 20% remaining"],
                german: ["Account acct · 5h limit: weniger als 20 % übrig"]),
            NotificationCase(
                name: "warning, used", accounts: [summary(remaining: 15)], settings: plain, mode: .used,
                english: ["Account acct · 5h limit: more than 80% used"],
                german: ["Account acct · 5h limit: mehr als 80 % verbraucht"]),
            NotificationCase(
                name: "critical, left", accounts: [summary(remaining: 5)], settings: plain, mode: .left,
                english: ["Account acct · 5h limit: less than 10% remaining"],
                german: ["Account acct · 5h limit: weniger als 10 % übrig"]),
            NotificationCase(
                name: "critical, used", accounts: [summary(remaining: 5)], settings: plain, mode: .used,
                english: ["Account acct · 5h limit: more than 90% used"],
                german: ["Account acct · 5h limit: mehr als 90 % verbraucht"]),
            NotificationCase(
                name: "exhausted, left", accounts: [summary(remaining: 0, exhausted: true)],
                settings: plain, mode: .left,
                english: ["Account acct · 5h limit exhausted"],
                german: ["Account acct · 5h limit aufgebraucht"]),
            NotificationCase(
                name: "exhausted, used", accounts: [summary(remaining: 0, exhausted: true)],
                settings: plain, mode: .used,
                english: ["Account acct · 5h limit exhausted"],
                german: ["Account acct · 5h limit aufgebraucht"]),
            NotificationCase(
                name: "reset in under a minute",
                accounts: [summary(remaining: 80, resetAt: now.addingTimeInterval(30))],
                settings: resetApproachingEnabled, mode: .left,
                english: ["Account acct · 5h limit resets in about 1 minute"],
                german: ["Account acct · 5h limit: Reset in etwa 1 Minute"]),
            NotificationCase(
                name: "reset in ten minutes",
                accounts: [summary(remaining: 80, resetAt: now.addingTimeInterval(10 * 60))],
                settings: resetApproachingEnabled, mode: .left,
                english: ["Account acct · 5h limit resets in about 10 minutes"],
                german: ["Account acct · 5h limit: Reset in etwa 10 Minuten"]),
            NotificationCase(
                name: "one credit, expiring",
                accounts: [summary(remaining: 80, credits: [credit("c1", expiresIn: 3_600)])],
                settings: plain, mode: .left,
                english: [
                    "Account acct · a reset credit expires in 1h",
                    "Account acct · 1 reset credit available",
                ],
                german: [
                    "Account acct · ein Reset-Credit läuft in 1 Std. ab",
                    "Account acct · 1 Reset-Credit verfügbar",
                ]),
            NotificationCase(
                name: "two credits, expiring",
                accounts: [summary(remaining: 80, credits: [
                    credit("c1", expiresIn: 3_600), credit("c2", expiresIn: 2 * 3_600 + 5 * 60),
                ])],
                settings: plain, mode: .left,
                english: [
                    "Account acct · a reset credit expires in 1h",
                    "Account acct · a reset credit expires in 2h 5m",
                    "Account acct · 2 reset credits available",
                ],
                german: [
                    "Account acct · ein Reset-Credit läuft in 1 Std. ab",
                    "Account acct · ein Reset-Credit läuft in 2 Std. 5 Min. ab",
                    "Account acct · 2 Reset-Credits verfügbar",
                ]),
            NotificationCase(
                name: "sign-in expired",
                accounts: [summary(
                    remaining: nil, failed: true,
                    errorMessage: NotificationEvaluator.signInExpiredMessage)],
                settings: plain, mode: .left,
                english: ["Account acct needs to be reconnected"],
                german: ["Account acct muss neu verbunden werden"]),
        ]
    }

    func testTheEnglishNotificationDefaultsAreTheSentencesTheAppAlwaysSent() {
        for scenario in notificationCases {
            XCTAssertEqual(
                said(scenario.accounts, settings: scenario.settings, mode: scenario.mode),
                scenario.english, scenario.name)
            XCTAssertEqual(
                said(
                    scenario.accounts, settings: scenario.settings, mode: scenario.mode,
                    texts: EnglishNotificationTexts()),
                scenario.english, "\(scenario.name), explicit English")
        }
    }

    func testEveryNotificationSentenceGoesThroughTheInjectedTexts() {
        for scenario in notificationCases {
            XCTAssertEqual(
                said(
                    scenario.accounts, settings: scenario.settings, mode: scenario.mode,
                    texts: GermanTestNotificationTexts()),
                scenario.german, scenario.name)
        }
    }

    func testEveryNotificationKindAtOnceLeavesNoEnglishBehind() {
        // One pass over several accounts, so a sentence built outside the protocol cannot hide
        // behind the case that happens not to exercise it.
        var all = NotificationSettings()
        all.notifyOnResetApproaching = true
        let accounts = notificationCases.enumerated().map { index, scenario -> AccountSummary in
            let source = scenario.accounts[0]
            return AccountSummary(
                accountId: "acct\(index)", label: source.label, snapshot: source.snapshot)
        }

        let lines = said(accounts, settings: all, texts: GermanTestNotificationTexts())

        XCTAssertFalse(lines.isEmpty)
        for line in lines {
            for fragment in [
                "remaining", " used", "exhausted", "resets in", "minute", "reset credit",
                "reconnected", "available", "expires",
            ] {
                XCTAssertFalse(line.contains(fragment), "\"\(line)\" still says \"\(fragment)\"")
            }
        }
    }

    func testTheCreditDeadlineIsFormattedByTheNotificationTextsOwnCountdown() {
        // The duration inside the sentence is composed by `countdown`, not by the default
        // English one, so a sentence in one language never carries a duration in another.
        let lines = said(
            [summary(remaining: 80, credits: [credit("c1", expiresIn: 26 * 3_600)])],
            settings: {
                var settings = NotificationSettings()
                settings.resetCreditExpiryLeadMinutes = 2 * 1_440
                return settings
            }(),
            texts: GermanTestNotificationTexts())

        XCTAssertEqual(
            lines,
            [
                "Account acct · ein Reset-Credit läuft in 1 T. 2 Std. ab",
                "Account acct · 1 Reset-Credit verfügbar",
            ])
    }

    func testTheLedgerWordsAnAlertInTheTextsItIsGiven() async throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("texts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let ledger = NotificationLedger(directory: directory)

        let claimed = try await ledger.evaluateAndClaim(
            accounts: [summary(remaining: 15)], settings: NotificationSettings(),
            percentDisplay: .left, at: now, texts: GermanTestNotificationTexts())

        XCTAssertEqual(claimed.map(\.line), ["Account acct · 5h limit: weniger als 20 % übrig"])
    }

    func testTheLedgerSpeaksEnglishWhenGivenNoTexts() async throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("texts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let ledger = NotificationLedger(directory: directory)

        let claimed = try await ledger.evaluateAndClaim(
            accounts: [summary(remaining: 15)], settings: NotificationSettings(),
            percentDisplay: .left, at: now)

        XCTAssertEqual(claimed.map(\.line), ["Account acct · 5h limit: less than 20% remaining"])
    }

    // MARK: - The container hands its texts to the ledger

    /// Answers each request from a script in order, repeating the last reply once it runs out.
    private actor ScriptedTransport: HTTPTransport {
        private var replies: [(status: Int, body: String)]

        init(_ replies: [(status: Int, body: String)]) { self.replies = replies }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let reply = replies.count > 1 ? replies.removeFirst() : replies[0]
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(reply.body.utf8), response)
        }
    }

    /// A Codex payload whose 5-hour window is nearly spent, so the evaluator has something to say.
    private let nearlySpentCodex = """
    { "plan_type": "plus",
      "rate_limit": { "allowed": true, "limit_reached": false,
        "primary_window": { "used_percent": 95, "limit_window_seconds": 18000,
                            "reset_after_seconds": 3600, "reset_at": 1757003600 },
        "secondary_window": null } }
    """

    /// A container in a scratch directory holding one refreshed, nearly spent account.
    private func containerWithNearlySpentAccount(in directory: URL) async throws -> UsageLimitsContainer {
        let credentials = InMemoryCredentialStore(credentials: [
            "ref-a": OAuthCredentials(accessToken: "synthetic-access", refreshToken: "synthetic-refresh"),
        ])
        let subject = UsageLimitsContainer(
            directory: directory, credentials: credentials,
            transport: ScriptedTransport([(200, nearlySpentCodex), (200, #"{"credits": []}"#)]),
            now: { [now] in now })
        try await subject.add(ProviderAccount(
            id: "a", provider: .codex, externalAccountID: "ext-a", email: "a@example.com",
            displayName: nil, plan: nil, credentialReference: "ref-a",
            createdAt: Date(timeIntervalSince1970: 0), lastSuccessfulSync: nil))
        _ = try await subject.refresh()
        return subject
    }

    private func scratchDirectory() throws -> URL {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("texts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    func testTheContainerWordsItsNotificationsInTheTextsItIsGiven() async throws {
        // The app's scheduler and background refresh call the container, not the ledger, so this
        // is the hop that decides whether a German user is sent English alerts.
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let subject = try await containerWithNearlySpentAccount(in: directory)

        let pending = try await subject.pendingNotifications(texts: GermanTestNotificationTexts())

        XCTAssertFalse(pending.isEmpty)
        for event in pending {
            XCTAssertTrue(event.line.contains("übrig"), "\"\(event.line)\"")
            XCTAssertFalse(event.line.contains("remaining"), "\"\(event.line)\"")
        }
    }

    func testTheContainerSpeaksEnglishWhenGivenNoTexts() async throws {
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let subject = try await containerWithNearlySpentAccount(in: directory)

        let pending = try await subject.pendingNotifications()

        XCTAssertFalse(pending.isEmpty)
        for event in pending {
            XCTAssertTrue(event.line.contains("remaining"), "\"\(event.line)\"")
            XCTAssertFalse(event.line.contains("übrig"), "\"\(event.line)\"")
        }
    }

    func testTheEnglishNotificationTextsCarryTheEnglishCountdown() {
        XCTAssertEqual(
            Countdown.format(seconds: 2 * 86_400 + 4 * 3_600, texts: EnglishNotificationTexts().countdown),
            "2d 4h")
    }

    // MARK: - Stored sentences

    func testEverySentenceTheKitStoresIsRecognisedFromItsRealProducer() {
        XCTAssertEqual(
            StoredMessage.classify(NotificationEvaluator.signInExpiredMessage), .signInExpired)
        XCTAssertEqual(StoredMessage.classify(ProviderError.unauthorised.errorDescription ?? ""), .signInExpired)
        XCTAssertEqual(
            StoredMessage.classify(ProviderError.malformedPayload("claude usage").errorDescription ?? ""),
            .malformedResponse(detail: "claude usage"))
        XCTAssertEqual(
            StoredMessage.classify(ProviderError.noData("HTTP 500").errorDescription ?? ""),
            .noUsage(detail: "HTTP 500"))

        let restricted = UsageWindow(
            id: "fable", label: "Weekly (Fable)", category: .weekly, usedPercent: 100,
            periodSeconds: 604_800, resetAt: nil, exhausted: true, scope: .model)
        let blocked = UsageWindow(
            id: "block", label: "5h limit", category: .fiveHour, usedPercent: 20,
            periodSeconds: 18_000, resetAt: nil, exhausted: true)
        func snapshot(_ windows: [UsageWindow]) -> UsageSnapshot {
            UsageSnapshot(accountID: "a", fetchedAt: now, status: .ok, windows: windows)
        }
        XCTAssertEqual(
            StoredMessage.classify(snapshot([restricted]).statusMessage ?? ""),
            .restricted(labels: "Weekly (Fable)"))
        XCTAssertEqual(
            StoredMessage.classify(snapshot([blocked]).statusMessage ?? ""),
            .providerBlockDespiteQuota)
    }

    func testTheSentencesWithPrivateProducersAreRecognisedByTheirText() {
        // Written inline by the provider clients and the sync engine, which no test can reach
        // without a network; the literals here are the contract the display relies on.
        XCTAssertEqual(
            StoredMessage.classify("Some usage data could not be read"), .someUsageUnreadable)
        XCTAssertEqual(
            StoredMessage.classify("Some billing data could not be updated"), .someBillingNotUpdated)
        XCTAssertEqual(
            StoredMessage.classify(
                "One billing view rejected authentication; some usage could not be refreshed."),
            .billingRejectedAuthentication)
        XCTAssertEqual(
            StoredMessage.classify("No credentials are stored for the account."), .noCredentials)
    }

    func testTheWordingAnEarlierBuildStoredStillMeansSignInExpired() {
        // Snapshots are cached across the upgrade, so the old sentence is still on disk.
        XCTAssertEqual(
            StoredMessage.classify("This account needs signing in again."), .signInExpired)
    }

    func testAnythingElseIsPassedThroughUntouched() {
        for message in [
            "",
            "Sign-in expired",
            "refresh token expired",
            "No usage could be read",
            "No usage could be read (HTTP 500)",
            "Der Anbieter hat gerade Probleme",
            "Something the provider said",
            // Several messages joined into one status line cannot be split safely, because a
            // window label may itself contain the separator.
            "Restricted: Weekly (Fable) · Provider reports a block despite remaining quota",
            "Some usage data could not be read · Restricted: Weekly (Fable)",
        ] {
            XCTAssertNil(StoredMessage.classify(message), "\"\(message)\"")
        }
    }

    func testARestrictedListKeepsItsLabelsExactlyAsStored() {
        XCTAssertEqual(
            StoredMessage.classify("Restricted: Weekly (Opus), Weekly (Sonnet)"),
            .restricted(labels: "Weekly (Opus), Weekly (Sonnet)"))
        XCTAssertEqual(
            StoredMessage.classify("Restricted: Code review · Limit reached"),
            .restricted(labels: "Code review · Limit reached"))
    }

    func testTheDetailInsideASentenceIsCarriedOverAsWritten() {
        XCTAssertEqual(
            StoredMessage.classify("No usage could be read (HTTP 503 (retry))."),
            .noUsage(detail: "HTTP 503 (retry)"))
        XCTAssertEqual(
            StoredMessage.classify("No usage could be read ()."), .noUsage(detail: ""))
    }
}
