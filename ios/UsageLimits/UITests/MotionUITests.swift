import XCTest

/// Checks real modifier decisions and reachable UI state, not applied transactions or rendered motion.
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

    private func waitUntil(_ what: String, in app: XCUIApplication, _ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in condition() }, object: nil)
        // Include the observed decisions when a simulator assertion fails.
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed,
                       "\(what); motion log: \(app.staticTexts["motion-log"].label)")
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
        waitUntil("\(event) after #\(number)", in: app) { self.entries(app).contains { $0.number > number && $0.event == event } }
    }

    private func filterToCodex(_ app: XCUIApplication) {
        tab("Accounts", in: app)
        let filter = app.buttons["OpenAI Codex"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10))
        filter.tap()
        waitUntil("Codex filter selected", in: app) { filter.value as? String == "Selected" }
    }

    func testProviderFilterLogsAnimatedDecisionInNormalMotion() {
        let app = launch()
        tab("Accounts", in: app)
        let before = checkpoint(app)
        let filter = app.buttons["OpenAI Codex"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10))
        filter.tap()
        waitFor("moveAfterExit animated", after: before, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "item fade" })
    }

    func testReducedMotionLogsNoAnimationForFilterAndFigureChanges() {
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
        waitUntil("the lead reads the other side", in: app) { lead.label != oldLabel }
        waitFor("enterAfterExit none", after: beforeFigure, in: app)
        waitFor("move none", after: beforeFigure, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "figure identity" })
        XCTAssertFalse(entries(app).contains { $0.number > beforeFigure && $0.event.hasSuffix(" animated") })
    }

    func testDeepLinkReachesTargetAndLogsUnanimatedFilterReset() throws {
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIDevice.system.open requires iOS 16.4")
        }
        let app = launch()
        filterToCodex(app)
        waitFor("moveAfterExit animated", after: 0, in: app)
        let before = checkpoint(app)
        // Through the system, as a widget's link arrives: `XCUIApplication.open` relaunches the
        // app (seen on CI), which would lose the filter this test is about.
        XCUIDevice.shared.system.open(URL(string: "usagelimits://account/demo-kimi")!)
        let confirm = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if confirm.waitForExistence(timeout: 3) { confirm.tap() }
        let target = app.staticTexts["account-title-demo-kimi"]
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(checkpoint(app), before, "the same process, not a relaunch")
        waitUntil("the target card is on screen", in: app) { target.isHittable }
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

    func testDelayedFirstContentLogsNoListAnimation() {
        let app = launch(["-motion-delayed-demo"])
        waitFor("move none", after: 0, in: app)
        XCTAssertFalse(entries(app).contains { $0.event == "move animated" || $0.event == "moveAfterExit animated" })
        // A later filter change must still resolve to an animation.
        let before = checkpoint(app)
        filterToCodex(app)
        waitFor("moveAfterExit animated", after: before, in: app)
    }
}
