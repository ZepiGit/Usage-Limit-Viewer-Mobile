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
        if !arguments.contains("-motion-delayed-demo") {
            XCTAssertTrue(app.buttons["overview-account-demo-codex"].waitForExistence(timeout: 10))
        }
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

    private func filterToAttention(_ app: XCUIApplication) {
        let filter = app.buttons["overview-filter-needsAttention"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10))
        filter.tap()
        waitUntil("Needs attention selected", in: app) { filter.value as? String == "Selected" }
    }

    func testOverviewFilterLogsAnimatedDecisionInNormalMotion() {
        let app = launch(["-overview-expired-demo"])
        let before = checkpoint(app)
        filterToAttention(app)
        waitFor("moveAfterExit animated", after: before, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "item fade" })
        XCTAssertTrue(app.buttons["overview-account-demo-codex"].waitForExistence(timeout: 10))
        waitUntil("Claude is filtered out", in: app) { !app.buttons["overview-account-demo-claude"].exists }
    }

    func testReducedMotionLogsNoAnimationForFilterAndFigureChanges() {
        let app = launch(["-reduce-motion", "-overview-expired-demo"])
        let beforeFilter = checkpoint(app)
        filterToAttention(app)
        waitFor("moveAfterExit none", after: beforeFilter, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "item identity" })
        XCTAssertFalse(entries(app).contains { $0.number > beforeFilter && $0.event.hasSuffix(" animated") })

        let card = app.buttons["overview-account-demo-codex"]
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        let mode = app.buttons["overview-display-mode"]
        XCTAssertTrue(mode.waitForExistence(timeout: 10))
        let oldValue = mode.value as? String
        XCTAssertTrue(oldValue == "Left" || oldValue == "Used")
        let beforeFigure = checkpoint(app)
        mode.tap()
        waitUntil("the display mode changes", in: app) { mode.value as? String != oldValue }
        XCTAssertEqual(mode.value as? String, oldValue == "Left" ? "Used" : "Left")
        waitFor("enterAfterExit none", after: beforeFigure, in: app)
        waitFor("move none", after: beforeFigure, in: app)
        XCTAssertTrue(entries(app).contains { $0.event == "figure identity" })
        XCTAssertFalse(entries(app).contains { $0.number > beforeFigure && $0.event.hasSuffix(" animated") })
    }

    func testWarmDeepLinkOpensExcludedAccountAndRestoresFilter() throws {
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIDevice.system.open requires iOS 16.4")
        }
        let app = launch()
        filterToAttention(app)
        waitUntil("Codex is filtered out", in: app) { !app.buttons["overview-account-demo-codex"].exists }
        let before = checkpoint(app)
        XCUIDevice.shared.system.open(URL(string: "usagelimits://account/demo-codex")!)
        let confirm = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if confirm.waitForExistence(timeout: 3) { confirm.tap() }
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(checkpoint(app), before, "the same process, not a relaunch")
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["overview-filter-needsAttention"].value as? String, "Selected")
        XCTAssertFalse(app.buttons["overview-account-demo-codex"].exists)
        XCTAssertFalse(app.buttons["overview-account-demo-claude"].exists)
        XCTAssertFalse(entries(app).contains { $0.number > before && $0.event == "moveAfterExit animated" })

        tab("Settings", in: app)
        tab("Overview", in: app)
        XCTAssertEqual(app.buttons["overview-filter-all"].value as? String, "Selected")
        XCTAssertTrue(app.buttons["overview-account-demo-claude"].waitForExistence(timeout: 10))
    }

    func testDeepLinkFromSettingsOpensDetailOnOverview() throws {
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIDevice.system.open requires iOS 16.4")
        }
        let app = launch()
        tab("Settings", in: app)
        XCUIDevice.shared.system.open(URL(string: "usagelimits://account/demo-codex")!)
        let confirm = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if confirm.waitForExistence(timeout: 3) { confirm.tap() }
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["overview-filter-all"].value as? String, "Selected")
    }

    func testDelayedFirstContentLogsNoListAnimation() {
        let app = launch(["-motion-delayed-demo", "-overview-expired-demo"])
        XCTAssertTrue(app.staticTexts["Loading usage"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["No accounts yet"].exists)
        XCTAssertFalse(app.buttons["overview-account-demo-codex"].exists)
        let beforeLoad = checkpoint(app)
        let release = app.buttons["overview-release-demo-load"]
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        release.tap()
        XCTAssertTrue(app.buttons["overview-account-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Loading usage"].exists)
        waitFor("moveAfterExit none", after: beforeLoad, in: app)
        XCTAssertFalse(entries(app).contains { $0.number > beforeLoad && ($0.event == "move animated" || $0.event == "moveAfterExit animated") })
        let before = checkpoint(app)
        filterToAttention(app)
        waitFor("moveAfterExit animated", after: before, in: app)
    }
}
