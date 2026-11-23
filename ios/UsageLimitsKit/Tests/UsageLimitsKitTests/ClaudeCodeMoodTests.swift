import XCTest
@testable import UsageLimitsKit

final class ClaudeCodeMoodTests: XCTestCase {
    func testEverySharedVectorKeepsItsHeadlineAndMoodInBothDisplayModes() throws {
        let vectors = try Self.parse(Data(contentsOf: SharedVectors.file("claude-code-mood.json")))
        for vector in vectors {
            let usage = vector.accountUsage
            for mode in [PercentDisplay.left, .used] {
                let context = "\(vector.name) (\(mode.rawValue))"
                let projection = GlanceModel.build([usage], now: vector.now, scope: .allAccounts, percentDisplay: mode)
                let account = try XCTUnwrap(projection.accounts.first, context)
                XCTAssertEqual(projection.percentDisplay, mode, context)
                assertReading(vector, on: account, staleAfter: projection.staleAfter, context: context)
                XCTAssertEqual(account.representative(at: vector.now)?.sourceWindowID, vector.expected.headlineID, context)
                XCTAssertEqual(account.claudeCodeMood(at: vector.now).rawValue, vector.expected.mood.rawValue, context)
            }

            let headline = usage.snapshot?.headline(at: vector.now)
            XCTAssertEqual(headline?.id, vector.expected.headlineID, vector.name)
            XCTAssertEqual(ClaudeCodeMood.steady(for: headline).rawValue, vector.expected.mood.rawValue, vector.name)
        }
    }

    func testAnInvalidRawFigureIsUnknownEvenFromAnUnsanitizedHeadline() {
        struct Candidate: HeadlineCandidate {
            let scope = UsageScope.global
            let resetAt: Date? = nil
            let severity = Severity.healthy
            let remainingPercent: Double?
        }

        for figure in [-Double.infinity, .nan, .infinity, -0.01, 100.01] {
            XCTAssertEqual(ClaudeCodeMood.steady(for: Candidate(remainingPercent: figure)), .idle, "\(figure)")
        }
    }

    func testReaderRejectsMalformedVectors() throws {
        let window = #"{"id":"main","scope":"global","remainingPercent":null,"exhausted":false,"resetAt":null}"#
        let vector = #"{"name":"sample","now":0,"fetchedAt":0,"reading":"current","windows":[\#(window)],"expected":{"headlineId":"main","mood":"idle"}}"#
        let valid = #"{"description":"description","schema":"schema","vectors":[\#(vector)]}"#
        XCTAssertEqual(try Self.parse(Data(valid.utf8)).count, 1)

        for (before, after) in [
            (#""schema":"schema","#, ""),
            (#""schema":"schema""#, #""schema":"schema","extra":true"#),
            (vector, ""),
            (vector, "\(vector),\(vector)"),
            (#""name":"sample""#, #""name":" ""#),
            (#""fetchedAt":0,"#, ""),
            (#""reading":"current""#, #""reading":"current","extra":true"#),
            (#""reading":"current""#, #""reading":"unknown""#),
            (window, "\(window),\(window)"),
            (#""id":"main""#, #""id":"""#),
            (#""remainingPercent":null,"#, ""),
            (#""remainingPercent":null"#, #""remainingPercent":"NaN""#),
            (#""resetAt":null"#, #""resetAt":null,"extra":true"#),
            (#""scope":"global""#, #""scope":"unrecognized""#),
            (#""headlineId":"main","#, ""),
            (#""headlineId":"main""#, #""headlineId":"other""#),
            (#""headlineId":"main""#, #""headlineId":null"#),
            (window, ""),
            (#""mood":"idle""#, #""mood":"idle","extra":true"#),
            (#""mood":"idle""#, #""mood":"unknown""#),
            (#""mood":"idle""#, #""mood":"hooray""#),
        ] {
            let invalid = valid.replacingOccurrences(of: before, with: after)
            XCTAssertThrowsError(try Self.parse(Data(invalid.utf8)), "replacing \(before) with \(after)")
        }
    }

    private func assertReading(
        _ vector: Vector, on account: GlanceAccount, staleAfter: TimeInterval, context: String
    ) {
        XCTAssertEqual(account.fetchedAt, vector.fetchedAt, context)
        let severity = account.severity(at: vector.now, staleAfter: staleAfter)
        switch vector.reading {
        case .current:
            XCTAssertNotEqual(severity, .stale, context)
            XCTAssertNotEqual(severity, .error, context)
        case .stale, .unread:
            XCTAssertEqual(severity, .stale, context)
        case .failed, .paused, .expired:
            XCTAssertEqual(severity, .error, context)
        }

        switch (vector.reading, vector.fetchedAt) {
        case (.expired, _):
            XCTAssertEqual(account.connectionStatus, .reconnectRequired, context)
        case (.unread, _), (.failed, nil):
            XCTAssertEqual(account.connectionStatus, .unknown, context)
        default:
            XCTAssertEqual(account.connectionStatus, .connected, context)
        }
        if vector.reading == .paused {
            XCTAssertEqual(account.statusMessage.flatMap(StoredMessage.classify), .rateLimited(trailingStatus: nil), context)
        }
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }

    private enum Reading: String, Decodable {
        case current, stale, failed, paused, expired, unread
    }

    private struct Vector: Decodable {
        let name: String
        let now: Date
        let fetchedAt: Date?
        let reading: Reading
        let windows: [Window]
        let expected: Expectation

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "now", "fetchedAt", "reading", "windows", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            now = millis(try c.decode(Int64.self, forKey: Keys("now")))
            fetchedAt = try c.decodeIfPresent(Int64.self, forKey: Keys("fetchedAt")).map(millis)
            reading = try c.decode(Reading.self, forKey: Keys("reading"))
            windows = try c.decode([Window].self, forKey: Keys("windows"))
            expected = try c.decode(Expectation.self, forKey: Keys("expected"))
        }

        var accountUsage: AccountUsage {
            let account = ProviderAccount(id: "claude", provider: .claude, externalAccountID: "sample",
                email: nil, displayName: nil, plan: nil, credentialReference: "sample",
                createdAt: .distantPast, lastSuccessfulSync: fetchedAt)
            guard reading != .unread else { return AccountUsage(account: account, snapshot: nil) }

            let failure: String?
            switch reading {
            case .failed: failure = "Connection lost"
            case .paused: failure = StoredMessage.rateLimitedPauseMessage
            case .expired: failure = NotificationEvaluator.signInExpiredMessage
            case .current, .stale, .unread: failure = nil
            }
            let snapshot = UsageSnapshot(accountID: account.id, fetchedAt: fetchedAt ?? now,
                status: failure == nil ? .ok : .failed, windows: windows.map(\.usageWindow), errorMessage: failure,
                connectionStatus: reading == .expired ? nil : fetchedAt.map { _ in .connected },
                lastAttemptAt: failure == nil ? fetchedAt : now)
            return AccountUsage(account: account, snapshot: snapshot)
        }
    }

    private struct Window: Decodable {
        let id: String
        let scope: UsageScope
        let remainingPercent: Double?
        let exhausted: Bool
        let resetAt: Date?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "scope", "remainingPercent", "exhausted", "resetAt"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            scope = try c.decode(UsageScope.self, forKey: Keys("scope"))
            remainingPercent = try c.decodeIfPresent(Double.self, forKey: Keys("remainingPercent"))
            exhausted = try c.decode(Bool.self, forKey: Keys("exhausted"))
            resetAt = try c.decodeIfPresent(Int64.self, forKey: Keys("resetAt")).map(millis)
            // Other shared vectors opt into non-finite strings; this schema takes only JSON numbers.
            if remainingPercent?.isFinite == false {
                throw VectorFileError(description: "remainingPercent for \(id) must be a JSON number or null")
            }
        }

        var usageWindow: UsageWindow {
            UsageWindow(id: id, label: "Window \(id)", category: .other, usedPercent: remainingPercent.map { 100 - $0 },
                        periodSeconds: nil, resetAt: resetAt, exhausted: exhausted, scope: scope)
        }
    }

    private struct Expectation: Decodable {
        enum Mood: String, Decodable {
            case asleep, tired, working, idle, dancing
        }

        let headlineID: String?
        let mood: Mood

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["headlineId", "mood"])
            let c = try decoder.container(keyedBy: Keys.self)
            headlineID = try c.decodeIfPresent(String.self, forKey: Keys("headlineId"))
            mood = try c.decode(Mood.self, forKey: Keys("mood"))
        }
    }

    private static func parse(_ data: Data) throws -> [Vector] {
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).vectors
        guard !vectors.isEmpty else { throw VectorFileError(description: "the mood vector file holds no vectors") }
        var names = Set<String>()
        for vector in vectors {
            guard !vector.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  names.insert(vector.name).inserted else {
                throw VectorFileError(description: "blank or duplicate case name: \(vector.name)")
            }
            var ids = Set<String>()
            for window in vector.windows {
                guard !window.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      ids.insert(window.id).inserted else {
                    throw VectorFileError(description: "blank or duplicate window id \(window.id) in \(vector.name)")
                }
            }
            guard vector.expected.headlineID.map(ids.contains) ?? ids.isEmpty else {
                throw VectorFileError(description: "invalid expected headline id in \(vector.name)")
            }
        }
        return vectors
    }
}

private func millis(_ value: Int64) -> Date {
    Date(timeIntervalSince1970: TimeInterval(value) / 1000)
}
