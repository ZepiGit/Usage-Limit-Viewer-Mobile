import XCTest

/// Reads transactions from the real modifiers, not a second implementation of their rules.
final class MotionUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-ui-testing", "-marketing-demo", "-motion-log",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"
        ] + arguments
        app.launch()
        XCTAssertTrue(app.staticTexts["motion-log"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["5/5 connected"].waitForExistence(timeout: 10))
        return app
    }

    private func waitUntil(_ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in condition() }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed)
    }

    private func tab(_ title: String, in app: XCUIApplication) {
        let matches = app.buttons.matching(identifier: title)
        let button = matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        button.tap()
        XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 10))
    }

    private func checkpoint(_ app: XCUIApplication) -> Int {
        let first = app.staticTexts["motion-log"].label.components(separatedBy: " | ")[0]
        return Int(first.dropFirst()) ?? 0
    }

    private func entries(_ app: XCUIApplication) -> [(number: Int, event: String)] {
        app.staticTexts["motion-log"].label.components(separatedBy: " | ").dropFirst().compactMap { entry in
            let parts = entry.split(separator: " ", maxSplits: 1)
            guard parts.count == 2, let number = Int(parts[0]) else { return nil }
            return (number, String(parts[1]))
        }
    }

    private func waitFor(_ event: String, after number: Int, in app: XCUIApplication) {
        waitUntil { self.entries(app).contains { $0.number > number && $0.event == event } }
    }

    private func filterToCodex(_ app: XCUIApplication) {
        tab("Accounts", in: app)
        let filter = app.buttons["OpenAI Codex"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10))
        filter.tap()
        waitUntil { filter.value as? String == "Selected" }
    }

    func testProviderFilterAnimatesInNormalMotion() {
        let app = launch()
        tab("Accounts", in: app)
        let before = checkpoint(app)
        let filter = app.buttons["OpenAI Codex"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10))
        filter.tap()
        waitFor("moveAfterExit animated", after: before, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "item fade" })
    }

    func testReducedMotionCutsFilterAndFigureChanges() {
        let app = launch(["-reduce-motion"])
        let beforeFilter = checkpoint(app)
        filterToCodex(app)
        waitFor("moveAfterExit none", after: beforeFilter, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "item identity" })
        XCTAssertFalse(entries(app).contains { $0.number > beforeFilter && $0.event.hasSuffix(" animated") })

        tab("Overview", in: app)
        let lead = app.descendants(matching: .any)["overview-lead"].firstMatch
        XCTAssertTrue(lead.waitForExistence(timeout: 10))
        let oldLabel = lead.label
        let beforeFigure = checkpoint(app)
        // A fixed inset stays inside the figure on both phone and iPad; the rest of the band
        // deliberately has no tap action. The overlay is above it and never takes touches.
        lead.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.45))
            .withOffset(CGVector(dx: 24, dy: 0)).tap()
        waitUntil { lead.label != oldLabel }
        waitFor("enterAfterExit none", after: beforeFigure, in: app)
        waitFor("move none", after: beforeFigure, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "figure identity" })
        XCTAssertFalse(entries(app).contains { $0.number > beforeFigure && $0.event.hasSuffix(" animated") })
    }

    func testDeepLinkCutsThroughAnActiveFilter() throws {
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIApplication.open requires iOS 16.4")
        }
        let app = launch()
        filterToCodex(app)
        waitFor("moveAfterExit animated", after: 0, in: app)
        let before = checkpoint(app)
        app.open(URL(string: "usagelimits://account/demo-kimi")!)
        let target = app.staticTexts["account-title-demo-kimi"]
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        waitUntil { target.isHittable }
        waitFor("moveAfterExit none", after: before, in: app)
        XCTAssertFalse(entries(app).contains { $0.number > before && $0.event == "moveAfterExit animated" })

        // The jump can scroll the filters out of a lazy stack. Read the selection after
        // returning to its top, without tapping a filter and thereby changing the result.
        let all = app.buttons["All"]
        let scroll = app.descendants(matching: .any)["accounts-scroll"].firstMatch
        for _ in 0..<8 {
            if all.exists && all.isHittable { break }
            scroll.swipeDown()
        }
        XCTAssertTrue(all.waitForExistence(timeout: 10))
        XCTAssertEqual(all.value as? String, "Selected")
    }

    func testReducedBusyMarkRetainsProgressSemantics() {
        let app = launch(["-reduce-motion"])
        // The credit form requires a real key/network request to become busy. The debug-only
        // overlay hosts the same two ActivityMark flavours, without creating an account or
        // faking a request. Nothing else on this screen is a progress control, and nothing
        // spins under Reduce Motion, so the marks are counted by what assistive technology
        // meets them as, not looked up by an identifier a replaced element might not carry.
        let dots = app.staticTexts["motion-log-busy-text"]
        XCTAssertTrue(dots.waitForExistence(timeout: 10))
        XCTAssertEqual(dots.label, "...")
        XCTAssertGreaterThanOrEqual(
            app.activityIndicators.count + app.progressIndicators.count, 1,
            "the still mark of the credit dialog is met as a progress control")
        XCTAssertEqual(
            app.staticTexts.matching(NSPredicate(format: "label == %@", "...")).count, 1,
            "and only the dots flavour is read out as text")
    }

    func testDelayedFirstContentDoesNotAnimateTheList() {
        let app = launch(["-motion-delayed-demo"])
        waitFor("move none", after: 0, in: app)
        XCTAssertFalse(entries(app).contains { $0.event == "move animated" || $0.event == "moveAfterExit animated" })
        // A later user change still animates: the initial cut must not leave motion disarmed.
        let before = checkpoint(app)
        filterToCodex(app)
        waitFor("moveAfterExit animated", after: before, in: app)
    }
}
