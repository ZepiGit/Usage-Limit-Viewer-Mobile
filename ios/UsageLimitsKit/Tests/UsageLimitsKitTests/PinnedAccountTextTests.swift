import XCTest
@testable import UsageLimitsKit

final class PinnedAccountTextTests: XCTestCase {
    private struct German: PinnedAccountTexts {
        let error = "Fehler"
        let signInExpired = "Anmeldung abgelaufen"
        let exhausted = "Ausgeschöpft"
        let unknown = "Unbekannt"
        let outdated = "Veraltet"
        func withSign(_ token: String) -> String { "\(token) %" }
        func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
            mode == .used ? "\(figure) verbraucht" : "\(figure) übrig"
        }
        func sideWord(_ mode: PercentDisplay) -> String { mode == .used ? "verbraucht" : "übrig" }
        func account(number: Int) -> String { "Konto \(number)" }
    }

    private func entry(remaining: Double?, mode: PercentDisplay = .left,
        severity: Severity = .healthy, validity: ReadingValidity = .current,
        connectionStatus: ConnectionStatus = .unknown) -> PinnedAccountEntry {
        let p = WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
        return PinnedAccountEntry(account: GlanceAccount(id: "claude", title: "Claude", subtitle: nil,
            rows: [], severity: severity, connectionStatus: connectionStatus), presentation: p,
            content: .of(headline: p, validity: validity,
                needsReauthentication: connectionStatus == .reconnectRequired))
    }

    func testSpokenFigureKeepsFullTokensWhenIconShortensThem() {
        XCTAssertEqual(spoken(entry(remaining: 0.4)), "Claude, <1% remaining")
        XCTAssertEqual(spoken(entry(remaining: 99.6)), "Claude, >99% remaining")
        XCTAssertEqual(spoken(entry(remaining: 100)), "Claude, 100% remaining")
        XCTAssertEqual(spoken(entry(remaining: 58, mode: .used)), "Claude, 42% used")
        XCTAssertEqual(spoken(entry(remaining: 42, validity: .stale)), "Claude, 42% remaining, Stale")
    }

    func testGermanCompositionKeepsFullFigureAndNonNumericStates() {
        let texts = German()
        func german(_ entry: PinnedAccountEntry) -> String {
            PinnedAccountText.spoken(entry: entry, texts: texts)
        }
        XCTAssertEqual(german(entry(remaining: 0.4)), "Claude, <1 % übrig")
        XCTAssertEqual(german(entry(remaining: 58, mode: .used)), "Claude, 42 % verbraucht")
        XCTAssertEqual(german(entry(remaining: nil)), "Claude, Unbekannt")
        XCTAssertEqual(german(entry(remaining: 0, severity: .exhausted)), "Claude, Ausgeschöpft")
        XCTAssertEqual(german(entry(remaining: 42, validity: .error)), "Claude, Fehler")
        XCTAssertEqual(german(entry(remaining: 42, validity: .resetDue)), "Claude, 42 % übrig, Veraltet")
    }

    func testCellAndSpokenTextCoverEveryKindInEnglishAndGerman() {
        let cases: [(PinnedAccountEntry, String?, String, String)] = [
            (entry(remaining: 42), "42", "Claude, 42% remaining", "Claude, 42 % übrig"),
            (entry(remaining: 42, validity: .stale), "42", "Claude, 42% remaining, Stale", "Claude, 42 % übrig, Veraltet"),
            (entry(remaining: 100), nil, "Claude, 100% remaining", "Claude, 100 % übrig"),
            (entry(remaining: nil), "—", "Claude, Unknown", "Claude, Unbekannt"),
            (entry(remaining: 0, severity: .exhausted), "⊘", "Claude, Exhausted", "Claude, Ausgeschöpft"),
            (entry(remaining: 42, validity: .error), "!", "Claude, Error", "Claude, Fehler"),
            (entry(remaining: 42, connectionStatus: .reconnectRequired), "!", "Claude, Sign-in expired", "Claude, Anmeldung abgelaufen")
        ]
        for (entry, cell, english, german) in cases {
            XCTAssertEqual(PinnedAccountText.cellText(entry), cell)
            XCTAssertEqual(PinnedAccountText.spoken(entry: entry, texts: EnglishPinnedAccountTexts()), english)
            XCTAssertEqual(PinnedAccountText.spoken(entry: entry, texts: German()), german)
        }
    }

    func testPinnedCaptureSpeaksDistinctLabelsWithFullFiguresAndStaleSuffix() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func usage(_ id: String, label: String, used: Double) -> AccountUsage {
            let account = ProviderAccount(id: id, provider: .claude, externalAccountID: id,
                email: nil, displayName: label, plan: "Pro", credentialReference: "synthetic-\(id)",
                createdAt: now, lastSuccessfulSync: now)
            let window = UsageWindow(id: "weekly", label: "Weekly", category: .weekly,
                usedPercent: used, periodSeconds: nil, resetAt: nil, exhausted: false)
            return AccountUsage(account: account, snapshot: UsageSnapshot(accountID: id,
                fetchedAt: now.addingTimeInterval(-7200), status: .ok, windows: [window]))
        }
        let snapshot = GlanceModel.build([usage("work", label: "Work", used: 99.6),
                                          usage("personal", label: "Personal", used: 0.4)],
            now: now, scope: .allAccounts, staleAfter: 3600, statusIconsEnabled: true,
            pinnedAccountIDs: ["work", "personal"])
        let entries = PinnedAccountsCapture.capture(snapshot: snapshot, at: now).accounts
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(entries.map { PinnedAccountText.spoken(entry: $0, texts: EnglishPinnedAccountTexts()) },
            ["Claude Pro, Work, <1% remaining, Stale", "Claude Pro, Personal, >99% remaining, Stale"])
        XCTAssertEqual(entries.map { PinnedAccountText.spoken(entry: $0, texts: German()) },
            ["Claude Pro, Work, <1 % übrig, Veraltet", "Claude Pro, Personal, >99 % übrig, Veraltet"])
        XCTAssertEqual(PinnedAccountText.inlineRow(entries, texts: EnglishPinnedAccountTexts())?.text, "<1% left · Stale")
        let encoded = try JSONEncoder().encode(snapshot)
        let decoded = try JSONDecoder().decode(GlanceSnapshot.self, from: encoded)
        XCTAssertEqual(PinnedAccountsCapture.capture(snapshot: decoded, at: now),
            PinnedAccountsCapture.capture(snapshot: snapshot, at: now))
    }

    func testCollidingPinnedLabelsKeepTheirLabelAndAddStableLocalizedNumbers() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let row = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 42,
            resetAt: nil, severity: .healthy)
        let accounts = ["second", "first"].enumerated().map { index, id in
            GlanceAccount(id: id, title: "Claude Pro", subtitle: nil, rows: [row], severity: .healthy,
                fetchedAt: now, providerID: "claude", createdAt: now.addingTimeInterval(index == 0 ? 1 : 0),
                accountLabel: "Work")
        }
        let snapshot = GlanceSnapshot(accounts: accounts, accountCount: 2, updatedAt: now,
            nextResetAt: nil, overallSeverity: .healthy, summaryLineShort: nil, summaryLineLong: nil,
            percentDisplay: .used, statusIconsEnabled: true, pinnedAccountIDs: accounts.map(\.id))
        let entries = PinnedAccountsCapture.capture(snapshot: snapshot, at: now).accounts
        XCTAssertEqual(entries.map { PinnedAccountText.spoken(entry: $0, texts: EnglishPinnedAccountTexts()) },
            ["Claude Pro, Work, Account 2, 58% used", "Claude Pro, Work, Account 1, 58% used"])
        XCTAssertEqual(entries.map { PinnedAccountText.spoken(entry: $0, texts: German()) },
            ["Claude Pro, Work, Konto 2, 58 % verbraucht", "Claude Pro, Work, Konto 1, 58 % verbraucht"])
    }

    private func spoken(_ entry: PinnedAccountEntry) -> String {
        PinnedAccountText.spoken(entry: entry, texts: EnglishPinnedAccountTexts())
    }
}
