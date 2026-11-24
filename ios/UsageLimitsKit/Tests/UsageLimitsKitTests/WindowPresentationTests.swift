import XCTest
@testable import UsageLimitsKit

/// Runs the same hand-written presentation and fill-state vectors as the Kotlin suite.
final class WindowPresentationTests: XCTestCase {

    private struct VectorFile: Decodable {
        let description: String
        let presentations: [Row]
        let fillStates: [FillCase]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "presentations", "fillStates"])
            let c = try decoder.container(keyedBy: Keys.self)
            description = try c.decode(String.self, forKey: Keys("description"))
            presentations = try c.decode([Row].self, forKey: Keys("presentations"))
            fillStates = try c.decode([FillCase].self, forKey: Keys("fillStates"))
        }
    }

    private struct Row: Decodable {
        let name: String
        let remaining: Double?
        let severity: Severity
        let validity: ReadingValidity
        let mode: PercentDisplay
        let token: String
        let fill: Double?
        let fillState: FillState
        let exhausted: Bool

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, [
                "name", "remaining", "severity", "validity", "mode", "token", "fill", "fillState", "exhausted",
            ])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            remaining = try c.decodeIfPresent(Double.self, forKey: Keys("remaining"))
            severity = try c.decode(VectorSeverity.self, forKey: Keys("severity")).model
            validity = try c.decode(VectorValidity.self, forKey: Keys("validity")).model
            mode = try c.decode(PercentDisplay.self, forKey: Keys("mode"))
            token = try c.decode(String.self, forKey: Keys("token"))
            fill = try c.decodeIfPresent(Double.self, forKey: Keys("fill"))
            fillState = try c.decode(VectorFillState.self, forKey: Keys("fillState")).model
            exhausted = try c.decode(Bool.self, forKey: Keys("exhausted"))
        }
    }

    private struct FillCase: Decodable {
        let name: String
        let validity: ReadingValidity
        let severity: Severity
        let fill: Double?
        let mode: PercentDisplay
        let expected: FillState

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "validity", "severity", "fill", "mode", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            validity = try c.decode(VectorValidity.self, forKey: Keys("validity")).model
            severity = try c.decode(VectorSeverity.self, forKey: Keys("severity")).model
            fill = try c.decodeIfPresent(Double.self, forKey: Keys("fill"))
            mode = try c.decode(PercentDisplay.self, forKey: Keys("mode"))
            expected = try c.decode(VectorFillState.self, forKey: Keys("expected")).model
        }
    }

    private enum VectorSeverity: String, Decodable {
        case healthy, medium, low, limited, exhausted, unknown, stale, error

        var model: Severity {
            switch self {
            case .healthy: return .healthy
            case .medium: return .medium
            case .low: return .low
            case .limited: return .limited
            case .exhausted: return .exhausted
            case .unknown: return .unknown
            case .stale: return .stale
            case .error: return .error
            }
        }
    }

    private enum VectorValidity: String, Decodable {
        case current, stale, resetDue, error

        var model: ReadingValidity {
            switch self {
            case .current: return .current
            case .stale: return .stale
            case .resetDue: return .resetDue
            case .error: return .error
            }
        }
    }

    private enum VectorFillState: String, Decodable {
        case data, stale, noFill

        var model: FillState {
            switch self {
            case .data: return .data
            case .stale: return .stale
            case .noFill: return .noFill
            }
        }
    }

    private static func parse(_ data: Data) throws -> VectorFile {
        let vectors = try SharedVectors.parse(VectorFile.self, from: data)
        guard !vectors.presentations.isEmpty else {
            throw VectorFileError(description: "the presentation vector file holds no presentations")
        }
        guard !vectors.fillStates.isEmpty else {
            throw VectorFileError(description: "the presentation vector file holds no fill states")
        }
        return vectors
    }

    private static let file = SharedVectors.file("window-presentation.json")

    private static func vectors() throws -> VectorFile {
        try parse(Data(contentsOf: file))
    }

    private static func changedDocument(
        table: String? = nil, change: (inout [String: Any]) -> Void
    ) throws -> Data {
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        if let table {
            var rows = try XCTUnwrap(document[table] as? [[String: Any]])
            change(&rows[0])
            document[table] = rows
        } else {
            change(&document)
        }
        return try JSONSerialization.data(withJSONObject: document)
    }

    func testUnknownFieldsFailAtEverySchemaLevel() throws {
        for table: String? in [nil, "presentations", "fillStates"] {
            let unknown = try Self.changedDocument(table: table) { $0["unexpected"] = true }
            XCTAssertThrowsError(try Self.parse(unknown), "unknown field in \(String(describing: table))")
        }
    }

    func testMissingFieldsFailIncludingNullableValues() throws {
        let fields: [(String?, String)] = [(nil, "description"), ("presentations", "remaining"), ("fillStates", "fill")]
        for (table, key) in fields {
            let missing = try Self.changedDocument(table: table) { $0.removeValue(forKey: key) }
            XCTAssertThrowsError(try Self.parse(missing), "missing \(key) in \(String(describing: table))")
        }
    }

    func testEmptyDataFailsTheSuite() {
        XCTAssertThrowsError(try Self.parse(Data()))
    }

    func testAnEmptyFillStateTableFailsTheSuite() throws {
        let empty = try Self.changedDocument { $0["fillStates"] = [] }
        XCTAssertThrowsError(try Self.parse(empty))
    }

    func testAnEmptyPresentationTableFailsTheSuite() throws {
        let empty = try Self.changedDocument { $0["presentations"] = [] }
        XCTAssertThrowsError(try Self.parse(empty))
    }

    func testThePresentationTableHoldsRowForRow() throws {
        for r in try Self.vectors().presentations {
            let p = WindowPresentation(
                remaining: r.remaining, severity: r.severity, validity: r.validity, mode: r.mode)
            let at = "\(r.name) (\(String(describing: r.remaining)), \(r.severity), \(r.validity), \(r.mode))"
            XCTAssertEqual(r.token, p.token, "\(at) token")
            XCTAssertEqual(r.mode, p.side, "\(at) side")
            XCTAssertEqual(r.fillState, p.fillState, "\(at) fillState")
            XCTAssertEqual(r.exhausted, p.exhausted, "\(at) exhausted")
            if let expected = r.fill {
                let actual = try XCTUnwrap(p.fill, "\(at) fill")
                XCTAssertEqual(expected, actual, accuracy: 1e-9, "\(at) fill")
            } else {
                XCTAssertNil(p.fill, "\(at) fill")
            }
        }
    }

    func testTheFigureExistsExactlyWhenTheTokenIsNotTheDash() throws {
        for r in try Self.vectors().presentations {
            let p = WindowPresentation(
                remaining: r.remaining, severity: r.severity, validity: r.validity, mode: r.mode)
            XCTAssertEqual(r.token != "—", p.hasFigure, r.name)
        }
    }

    func testTheSideIsTheModeForEveryInput() {
        let severities: [Severity] = [.healthy, .medium, .low, .limited, .exhausted, .unknown, .stale, .error]
        let validities: [ReadingValidity] = [.current, .stale, .resetDue, .error]
        let remainings: [Double?] = [nil, 0.0, 15.0, 73.0, 100.0, 150.0, -5.0, Double.nan]
        for severity in severities {
            for validity in validities {
                for mode in [PercentDisplay.left, .used] {
                    for remaining in remainings {
                        let p = WindowPresentation(
                            remaining: remaining, severity: severity, validity: validity, mode: mode)
                        XCTAssertEqual(
                            mode, p.side,
                            "\(severity) \(validity) \(mode) \(String(describing: remaining))")
                    }
                }
            }
        }
    }

    func testTheFillStateTableHoldsRowForRow() throws {
        for c in try Self.vectors().fillStates {
            XCTAssertEqual(
                c.expected,
                WindowPresentation.fillState(
                    validity: c.validity, severity: c.severity, fill: c.fill, mode: c.mode),
                "\(c.name) (\(c.validity), \(c.severity), \(String(describing: c.fill)), \(c.mode))")
        }
    }

    // MARK: - No window, and a window

    func testNoWindowPresentsTheDashWithNoFillInBothModes() {
        for mode in [PercentDisplay.left, .used] {
            let p = WindowPresentation.absent(mode)
            XCTAssertEqual("—", p.token)
            XCTAssertEqual(mode, p.side)
            XCTAssertNil(p.fill)
            XCTAssertEqual(FillState.noFill, p.fillState)
            XCTAssertFalse(p.exhausted)
            XCTAssertFalse(p.hasFigure)
        }
    }

    func testAbsentIsExactlyWhatThePrimitivesGiveForAMissingWindow() {
        for mode in [PercentDisplay.left, .used] {
            let p = WindowPresentation.absent(mode)
            XCTAssertEqual(UsageWindow.percentToken(nil, exhausted: false, mode: mode), p.token)
            XCTAssertNil(UsageWindow.fillFraction(nil, exhausted: false, mode: mode))
            XCTAssertNil(p.fill)
        }
    }

    private func window(usedPercent: Double?, exhausted: Bool = false, resetAt: Date? = nil) -> UsageWindow {
        UsageWindow(
            id: "w", label: "5h limit", category: .fiveHour, usedPercent: usedPercent,
            periodSeconds: nil, resetAt: resetAt, exhausted: exhausted)
    }

    func testAWindowPresentsFromItsRemainderAndItsRawSeverity() {
        let healthy = window(usedPercent: 27.0)
        let exhaustedWithRemainder = window(usedPercent: 85.0, exhausted: true)
        let noFigure = window(usedPercent: nil, exhausted: true)

        XCTAssertEqual(
            WindowPresentation(remaining: 73.0, severity: .healthy, validity: .current, mode: .used),
            healthy.presentation(validity: .current, mode: .used))
        XCTAssertEqual(
            WindowPresentation(remaining: 15.0, severity: .exhausted, validity: .stale, mode: .used),
            exhaustedWithRemainder.presentation(validity: .stale, mode: .used))
        XCTAssertEqual(
            WindowPresentation(remaining: nil, severity: .exhausted, validity: .current, mode: .used),
            noFigure.presentation(validity: .current, mode: .used))

        let p = exhaustedWithRemainder.presentation(validity: .stale, mode: .left)
        XCTAssertEqual("15", p.token)
        XCTAssertTrue(p.exhausted)
        XCTAssertEqual(FillState.stale, p.fillState)
    }

    func testExhaustedFollowsTheRawSeverityNotTheStoredFlag() {
        // Used up to 100 with no flag: `Severity.from` still calls it exhausted, and the module
        // reads that, where the instance `percentToken` and `fillFraction` read the flag.
        let spent = window(usedPercent: 100.0, exhausted: false)
        XCTAssertFalse(spent.exhausted)
        let p = spent.presentation(validity: .current, mode: .used)
        XCTAssertTrue(p.exhausted)
        XCTAssertEqual("100", p.token)
        XCTAssertEqual(FillState.data, p.fillState)
    }

    // MARK: - ReadingValidity.aged

    func testAgedMapsEachOfTheEightSeverities() {
        let table: [(Severity, ReadingValidity)] = [
            (.healthy, .current), (.medium, .current), (.low, .current), (.limited, .current),
            (.exhausted, .current), (.unknown, .current), (.stale, .stale), (.error, .error),
        ]
        XCTAssertEqual(8, Set(table.map { $0.0.rawValue }).count, "the table must name all eight severities")
        for (severity, expected) in table {
            XCTAssertEqual(expected, ReadingValidity.aged(severity), "\(severity)")
        }
    }

    // MARK: - GlanceAccount.readingValidity

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private let staleAfter: TimeInterval = 3_600

    private func glanceRow(
        resetAt: Date?, severity: Severity = .healthy, remaining: Double? = 73.0
    ) -> GlanceRow {
        GlanceRow(
            label: "5h limit", category: .fiveHour, remainingPercent: remaining,
            resetAt: resetAt, severity: severity)
    }

    private func glanceAccount(baseSeverity: Severity = .healthy, fetchedAt: Date?) -> GlanceAccount {
        GlanceAccount(
            id: "a", title: "A", subtitle: nil, rows: [], severity: baseSeverity,
            baseSeverity: baseSeverity, fetchedAt: fetchedAt)
    }

    func testAnAgedErrorIsAnErrorWhateverTheRowSays() {
        let failed = glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-60))
        let resetBehindIt = glanceRow(resetAt: now.addingTimeInterval(-30))
        let open = glanceRow(resetAt: now.addingTimeInterval(600))
        XCTAssertEqual(.error, failed.readingValidity(of: resetBehindIt, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.error, failed.readingValidity(of: open, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.error, failed.readingValidity(of: nil, at: now, staleAfter: staleAfter))
        // An error stays an error however old: it does not decay into merely stale.
        let failedLongAgo = glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-86_400))
        XCTAssertEqual(.error, failedLongAgo.readingValidity(of: open, at: now, staleAfter: staleAfter))
    }

    func testAnAgedStaleReadingIsStaleAndOutranksAPassedReset() {
        let old = glanceAccount(fetchedAt: now.addingTimeInterval(-2 * staleAfter))
        let resetBehindIt = glanceRow(resetAt: now.addingTimeInterval(-30))
        let open = glanceRow(resetAt: now.addingTimeInterval(600))
        XCTAssertEqual(.stale, old.readingValidity(of: open, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.stale, old.readingValidity(of: resetBehindIt, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.stale, old.readingValidity(of: nil, at: now, staleAfter: staleAfter))
    }

    func testTheStaleBoundaryIsTheThresholdItself() {
        let exactly = glanceAccount(fetchedAt: now.addingTimeInterval(-staleAfter))
        let justUnder = glanceAccount(fetchedAt: now.addingTimeInterval(-(staleAfter - 1)))
        let open = glanceRow(resetAt: now.addingTimeInterval(600))
        XCTAssertEqual(.stale, exactly.readingValidity(of: open, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.current, justUnder.readingValidity(of: open, at: now, staleAfter: staleAfter))
    }

    func testAResetPassedSinceTheFetchIsResetDue() {
        let fetched = glanceAccount(fetchedAt: now.addingTimeInterval(-600))
        let passed = glanceRow(resetAt: now.addingTimeInterval(-60))
        XCTAssertEqual(.resetDue, fetched.readingValidity(of: passed, at: now, staleAfter: staleAfter))
        // The reset instant itself already counts as passed.
        let dueNow = glanceRow(resetAt: now)
        XCTAssertEqual(.resetDue, fetched.readingValidity(of: dueNow, at: now, staleAfter: staleAfter))
    }

    func testAResetFetchedAfterItIsConfirmedNotPending() {
        let passed = glanceRow(resetAt: now.addingTimeInterval(-600))
        let fetchedAfter = glanceAccount(fetchedAt: now.addingTimeInterval(-60))
        XCTAssertEqual(.current, fetchedAfter.readingValidity(of: passed, at: now, staleAfter: staleAfter))
        // Fetched at the reset instant: the cache already describes the new window.
        let fetchedAtReset = glanceAccount(fetchedAt: now.addingTimeInterval(-600))
        XCTAssertEqual(.current, fetchedAtReset.readingValidity(of: passed, at: now, staleAfter: staleAfter))
    }

    func testAPassedResetWithoutAFetchInstantIsResetDue() {
        // No fetch instant: nothing ages the stored severity and nothing confirms the reset.
        let unfetched = glanceAccount(fetchedAt: nil)
        let passed = glanceRow(resetAt: now.addingTimeInterval(-60))
        let open = glanceRow(resetAt: now.addingTimeInterval(600))
        XCTAssertEqual(.resetDue, unfetched.readingValidity(of: passed, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.current, unfetched.readingValidity(of: open, at: now, staleAfter: staleAfter))
        // The stored verdict stands in for the aged one.
        let storedStale = glanceAccount(baseSeverity: .stale, fetchedAt: nil)
        XCTAssertEqual(.stale, storedStale.readingValidity(of: open, at: now, staleAfter: staleAfter))
        let storedError = glanceAccount(baseSeverity: .error, fetchedAt: nil)
        XCTAssertEqual(.error, storedError.readingValidity(of: open, at: now, staleAfter: staleAfter))
    }

    func testAFreshAccountWithAnOpenOrUndatedRowIsCurrent() {
        let fresh = glanceAccount(fetchedAt: now.addingTimeInterval(-60))
        let open = glanceRow(resetAt: now.addingTimeInterval(600))
        let undated = glanceRow(resetAt: nil)
        XCTAssertEqual(.current, fresh.readingValidity(of: open, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.current, fresh.readingValidity(of: undated, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.current, fresh.readingValidity(of: nil, at: now, staleAfter: staleAfter))
        // A quota verdict is not a validity: an exhausted or unknown row is still a current reading.
        let exhausted = glanceRow(resetAt: now.addingTimeInterval(600), severity: .exhausted, remaining: 0.0)
        let unknown = glanceRow(resetAt: nil, severity: .unknown, remaining: nil)
        XCTAssertEqual(.current, fresh.readingValidity(of: exhausted, at: now, staleAfter: staleAfter))
        XCTAssertEqual(.current, fresh.readingValidity(of: unknown, at: now, staleAfter: staleAfter))
    }

    // MARK: - GlanceAccount.presentation

    func testNoRowPresentsAsAbsentWhateverTheAccountSays() {
        let accounts = [
            glanceAccount(fetchedAt: now.addingTimeInterval(-60)),
            glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-60)),
            glanceAccount(fetchedAt: now.addingTimeInterval(-2 * staleAfter)),
        ]
        for account in accounts {
            for mode in [PercentDisplay.left, .used] {
                XCTAssertEqual(
                    WindowPresentation.absent(mode),
                    account.presentation(of: nil, at: now, staleAfter: staleAfter, mode: mode))
            }
        }
    }

    func testARowPresentsFromItsRawSeverityAndTheAccountsValidity() {
        let row = glanceRow(resetAt: now.addingTimeInterval(600), severity: .exhausted, remaining: 15.0)
        let fresh = glanceAccount(fetchedAt: now.addingTimeInterval(-60))
        let old = glanceAccount(fetchedAt: now.addingTimeInterval(-2 * staleAfter))
        let failed = glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-60))

        XCTAssertEqual(
            WindowPresentation(remaining: 15.0, severity: .exhausted, validity: .current, mode: .used),
            fresh.presentation(of: row, at: now, staleAfter: staleAfter, mode: .used))
        XCTAssertEqual(
            WindowPresentation(remaining: 15.0, severity: .exhausted, validity: .stale, mode: .used),
            old.presentation(of: row, at: now, staleAfter: staleAfter, mode: .used))
        XCTAssertEqual(
            WindowPresentation(remaining: 15.0, severity: .exhausted, validity: .error, mode: .used),
            failed.presentation(of: row, at: now, staleAfter: staleAfter, mode: .used))

        let reset = glanceRow(resetAt: now.addingTimeInterval(-60), severity: .medium, remaining: 40.0)
        let fetched = glanceAccount(fetchedAt: now.addingTimeInterval(-600))
        let p = fetched.presentation(of: reset, at: now, staleAfter: staleAfter, mode: .used)
        XCTAssertEqual("60", p.token)
        XCTAssertEqual(FillState.stale, p.fillState)
    }

    // MARK: - Equivalence with the reference ink rule

    // An independent copy of the bar's ink rule, so a mistake in the module's rule cannot also hide
    // in its oracle. The two colours are named by what they stand for: `.stale` and `.data`.
    private func referenceBarFill(_ severity: Severity) -> FillState? {
        switch severity {
        case .stale: return .stale
        case .unknown, .error, .exhausted: return nil
        default: return .data
        }
    }

    // The ink for a severity and fraction in a mode.
    private func referenceInk(_ severity: Severity, fraction: Double?, mode: PercentDisplay) -> FillState? {
        if let ink = referenceBarFill(severity) { return ink }
        guard mode.usedFillOverridesMissingInk(fraction) else { return nil }
        return .data
    }

    // A fraction clamped into 0 to 1, with a non-finite one at 0.
    private func referenceClampedFill(_ fraction: Double) -> Double {
        guard fraction.isFinite else { return 0 }
        return min(max(fraction, 0), 1)
    }

    // The capsule is drawn only for a shown fraction AND an ink, at the clamped width.
    private func referencePainted(_ ink: FillState?, fraction: Double?) -> Double {
        guard let fraction, ink != nil else { return 0 }
        return referenceClampedFill(fraction)
    }

    private func modulePainted(_ p: WindowPresentation) -> Double {
        p.fillState == .noFill ? 0 : (p.fill ?? 0)
    }

    private func paintedInk(_ state: FillState) -> FillState? {
        switch state {
        case .noFill: return nil
        case .data, .stale: return state
        }
    }

    private enum Aging: CaseIterable {
        case current, stale, error
    }

    private struct Cell {
        let at: String
        let aging: Aging
        let resetPending: Bool
        let displaySeverity: Severity
        let mode: PercentDisplay
        let fraction: Double?
        let reference: FillState?
        let validity: ReadingValidity
        let module: WindowPresentation
    }

    /// Every row severity x aged state x reset pending x mode x remaining, each cell holding what
    /// the reference rule gives and what the module answers.
    private func sweep() -> [Cell] {
        let severities: [Severity] = [.healthy, .medium, .low, .limited, .exhausted, .unknown, .stale, .error]
        let remainings: [Double?] = [nil, 0.0, 15.0, 73.0, 100.0]
        var cells: [Cell] = []
        for severity in severities {
            for aging in Aging.allCases {
                for resetPending in [false, true] {
                    for mode in [PercentDisplay.left, .used] {
                        for remaining in remainings {
                            let row = glanceRow(
                                resetAt: now.addingTimeInterval(resetPending ? -60 : 3_600),
                                severity: severity, remaining: remaining)
                            let account: GlanceAccount
                            switch aging {
                            case .current:
                                account = glanceAccount(fetchedAt: now.addingTimeInterval(-600))
                            case .stale:
                                account = glanceAccount(fetchedAt: now.addingTimeInterval(-2 * staleAfter))
                            case .error:
                                account = glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-600))
                            }
                            let at = "row \(severity), \(aging), resetPending=\(resetPending), \(mode), "
                                + "remaining=\(String(describing: remaining))"
                            XCTAssertEqual(
                                resetPending, row.resetPending(at: now, fetchedAt: account.fetchedAt),
                                "\(at) precondition")
                            let display = account.displaySeverity(of: row, at: now, staleAfter: staleAfter)
                            let fraction = UsageWindow.fillFraction(
                                row.remainingPercent, exhausted: row.severity == .exhausted, mode: mode)
                            cells.append(Cell(
                                at: at,
                                aging: aging,
                                resetPending: resetPending,
                                displaySeverity: display,
                                mode: mode,
                                fraction: fraction,
                                reference: referenceInk(display, fraction: fraction, mode: mode),
                                validity: account.readingValidity(of: row, at: now, staleAfter: staleAfter),
                                module: account.presentation(of: row, at: now, staleAfter: staleAfter, mode: mode)))
                        }
                    }
                }
            }
        }
        return cells
    }

    /// The one case where the module differs from the reference: a failed or unknown reading with a
    /// positive fraction in USED draws nothing, as it does in LEFT, where the reference gives the
    /// data hue.
    private func differsFromReference(_ cell: Cell) -> Bool {
        (cell.displaySeverity == .error || cell.displaySeverity == .unknown)
            && cell.mode == .used && (cell.fraction ?? 0) > 0
    }

    func testTheSweepReachesEveryAgedStateAndEveryValidity() {
        let cells = sweep()
        XCTAssertEqual(8 * 3 * 2 * 2 * 5, cells.count)
        for validity: ReadingValidity in [.current, .stale, .resetDue, .error] {
            XCTAssertTrue(cells.contains { $0.validity == validity }, "the sweep never reached \(validity)")
        }
    }

    func testTheSweepsReadingValidityIsTheAccountsAgingThenTheRowsReset() {
        for cell in sweep() {
            let expected: ReadingValidity
            switch cell.aging {
            case .error: expected = .error
            case .stale: expected = .stale
            case .current: expected = cell.resetPending ? .resetDue : .current
            }
            XCTAssertEqual(expected, cell.validity, cell.at)
        }
    }

    func testTheFillStateIsTheReferenceInkExceptWhereAFailedReadingHasAFraction() {
        var differing = 0
        for cell in sweep() {
            let module = paintedInk(cell.module.fillState)
            if differsFromReference(cell) {
                XCTAssertEqual(FillState.data, cell.reference, "\(cell.at) reference")
                XCTAssertNil(module, "\(cell.at) module")
                differing += 1
            } else {
                XCTAssertEqual(cell.reference, module, cell.at)
            }
        }
        XCTAssertGreaterThan(differing, 0, "the sweep never reached a differing cell")
    }

    func testThePaintedLengthIsTheReferenceOneExceptWhereAFailedReadingHasAFraction() {
        for cell in sweep() where !differsFromReference(cell) {
            XCTAssertEqual(
                referencePainted(cell.reference, fraction: cell.fraction),
                modulePainted(cell.module),
                accuracy: 1e-9,
                cell.at)
        }
    }

    func testAFailedReadingInUsedPaintsNothingWhereTheReferencePaintsItsFraction() {
        // A failed account's cached 15 % left is 0.85 used.
        let row = glanceRow(resetAt: now.addingTimeInterval(3_600), severity: .low, remaining: 15.0)
        let failed = glanceAccount(baseSeverity: .error, fetchedAt: now.addingTimeInterval(-600))
        let display = failed.displaySeverity(of: row, at: now, staleAfter: staleAfter)
        XCTAssertEqual(.error, display)
        let fraction = UsageWindow.fillFraction(15.0, exhausted: false, mode: .used)
        XCTAssertEqual(
            0.85, referencePainted(referenceInk(display, fraction: fraction, mode: .used), fraction: fraction),
            accuracy: 1e-9)
        let p = failed.presentation(of: row, at: now, staleAfter: staleAfter, mode: .used)
        XCTAssertEqual(FillState.noFill, p.fillState)
        XCTAssertEqual(0, modulePainted(p), accuracy: 1e-9)
        // LEFT never paints a failed reading, so the reference and the module agree there.
        let leftFraction = UsageWindow.fillFraction(15.0, exhausted: false, mode: .left)
        XCTAssertEqual(
            0, referencePainted(referenceInk(display, fraction: leftFraction, mode: .left), fraction: leftFraction),
            accuracy: 1e-9)
    }

    func testACorruptCachedFigurePaintsNoBarWhereTheReferenceClampsItToFull() {
        // A figure out of range reads as a dash, so its bar stays empty beside it.
        let fresh = glanceAccount(fetchedAt: now.addingTimeInterval(-60))
        let corrupt: [(Double, PercentDisplay)] = [(150.0, .left), (-5.0, .used)]
        for (remaining, mode) in corrupt {
            let row = glanceRow(resetAt: now.addingTimeInterval(3_600), severity: .healthy, remaining: remaining)
            let display = fresh.displaySeverity(of: row, at: now, staleAfter: staleAfter)
            let fraction = UsageWindow.fillFraction(remaining, exhausted: false, mode: mode)
            XCTAssertEqual(
                1.0, referencePainted(referenceInk(display, fraction: fraction, mode: mode), fraction: fraction),
                accuracy: 1e-9, "\(remaining) \(mode) reference")
            let p = fresh.presentation(of: row, at: now, staleAfter: staleAfter, mode: mode)
            XCTAssertEqual("—", p.token, "\(remaining) \(mode) token")
            XCTAssertNil(p.fill, "\(remaining) \(mode) fill")
            XCTAssertEqual(0, modulePainted(p), accuracy: 1e-9, "\(remaining) \(mode) module")
        }
    }
}
