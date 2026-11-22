import XCTest

/// Checks motion decisions and the app's public accessibility tree. Hosted rendering tests check frames.
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

    private func launchSignInFixture(reduced: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-fixture-sign-in-motion", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if reduced { app.launchArguments.append("-reduce-motion") }
        app.launch()
        return app
    }

    private func kimiButton(in picker: XCUIElement) -> XCUIElement {
        picker.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Kimi")).firstMatch
    }

    func testLeavingSignInChooserRejectsAXAndTouchThenRestoresItsAction() {
        let app = launchSignInFixture()
        defer { app.terminate() }
        let picker = app.scrollViews["provider-picker-scroll"]
        let kimi = kimiButton(in: picker)
        let key = picker.staticTexts["Paste your OpenCode Go key"]
        let region = picker.staticTexts["Kimi region"]
        let request = app.buttons["Request key step"]
        let release = app.buttons["Release exit"]
        let retained = app.buttons["Activate retained Kimi"]
        let restore = app.buttons["Restore chooser"]
        XCTAssertTrue(kimi.waitForExistence(timeout: 5))
        XCTAssertTrue(kimi.isEnabled)
        XCTAssertTrue(kimi.isHittable)
        let oldFrame = kimi.frame
        let oldPoint = app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: oldFrame.midX - app.frame.minX, dy: oldFrame.midY - app.frame.minY))
        XCTAssertTrue(request.waitForExistence(timeout: 5), "the real Kimi button action has been retained")
        request.tap()
        XCTAssertTrue(release.waitForExistence(timeout: 5))
        XCTAssertTrue(retained.exists)
        XCTAssertTrue(picker.exists, "the outgoing picker is still mounted")
        XCTAssertFalse(kimi.exists, "the still-visible outgoing card is already hidden from AX")
        XCTAssertFalse(key.exists, "the destination has not yet replaced the outgoing card")
        XCTAssertTrue(app.buttons["Cancel"].exists, "AX is active outside the outgoing card")
        let held = XCTAttachment(screenshot: app.screenshot())
        held.name = "sign-in-opaque-outgoing-picker"
        held.lifetime = .keepAlways
        add(held)
        oldPoint.tap()
        retained.tap()
        release.tap()
        XCTAssertTrue(key.waitForExistence(timeout: 5), "neither old action may replace the requested destination")
        XCTAssertFalse(region.exists)
        XCTAssertFalse(kimi.exists)
        XCTAssertFalse(picker.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Shows a code")).firstMatch.exists)

        restore.tap()
        XCTAssertTrue(kimi.waitForExistence(timeout: 5))
        XCTAssertTrue(kimi.isEnabled)
        kimi.tap()
        XCTAssertTrue(region.waitForExistence(timeout: 5), "the restored real button opens the Kimi region choice")
    }

    func testInterruptedSignInRestoresTheRealChooserAction() {
        let app = launchSignInFixture()
        defer { app.terminate() }
        let picker = app.scrollViews["provider-picker-scroll"]
        let kimi = kimiButton(in: picker)
        XCTAssertTrue(kimi.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Request key step"].waitForExistence(timeout: 5))
        let reverse = app.buttons["Reverse at intermediate"]
        XCTAssertTrue(reverse.isEnabled)
        reverse.tap()
        XCTAssertTrue(app.buttons["Reversal settled"].waitForExistence(timeout: 5),
                      "a real exit and resumed entrance must finish before testing the restored action")
        XCTAssertFalse(picker.staticTexts["Paste your OpenCode Go key"].exists)
        XCTAssertTrue(kimi.exists)
        XCTAssertTrue(kimi.isEnabled)
        kimi.tap()
        XCTAssertTrue(picker.staticTexts["Kimi region"].waitForExistence(timeout: 5))
    }

    func testReducedSignInPublishesOnlyTheNewStepToAccessibility() {
        let app = launchSignInFixture(reduced: true)
        defer { app.terminate() }
        let picker = app.scrollViews["provider-picker-scroll"]
        let kimi = kimiButton(in: picker)
        let key = picker.staticTexts["Paste your OpenCode Go key"]
        XCTAssertTrue(kimi.waitForExistence(timeout: 5))
        XCTAssertFalse(key.exists)
        let request = app.buttons["Request key step"]
        XCTAssertTrue(request.waitForExistence(timeout: 5))
        request.tap()
        XCTAssertTrue(key.waitForExistence(timeout: 5), "the new step is exposed without releasing the normal-motion hold")
        XCTAssertFalse(kimi.exists)
        XCTAssertTrue(app.buttons["Cancel"].exists)
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

    func testClaudeMoodsPauseWhenCoveredOrDeselected() {
        let app = launch(["-claude-mood-demo"])
        defer { app.terminate() }
        waitFor("claude:list:demo-claude-exhausted:asleep animated", after: 0, in: app)
        waitFor("claude:list:demo-claude:tired animated", after: 0, in: app)

        let beforeDetail = checkpoint(app)
        app.buttons["overview-account-demo-claude-exhausted"].tap()
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        waitFor("claude:list:demo-claude-exhausted:asleep paused", after: beforeDetail, in: app)
        waitFor("claude:detail:demo-claude-exhausted:asleep animated", after: beforeDetail, in: app)
        let beforeClose = checkpoint(app)
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        waitFor("claude:list:demo-claude-exhausted:asleep animated", after: beforeClose, in: app)

        let beforeResets = checkpoint(app)
        tab("Resets", in: app)
        waitFor("claude:list:demo-claude-exhausted:asleep paused", after: beforeResets, in: app)
        waitFor("claude:reset:demo-claude-exhausted:hooray animated", after: beforeResets, in: app)
        let beforeReturn = checkpoint(app)
        tab("Overview", in: app)
        waitFor("claude:reset:demo-claude-exhausted:hooray paused", after: beforeReturn, in: app)
        waitFor("claude:list:demo-claude-exhausted:asleep animated", after: beforeReturn, in: app)

        let beforeHome = checkpoint(app)
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10))
        app.activate()
        waitFor("claude:list:demo-claude-exhausted:asleep paused", after: beforeHome, in: app)
        waitFor("claude:list:demo-claude-exhausted:asleep animated", after: beforeHome, in: app)
    }

    func testReduceMotionUsesTheSameMoodsAsStills() {
        let app = launch(["-claude-mood-demo", "-reduce-motion"])
        defer { app.terminate() }
        waitFor("claude:list:demo-claude-exhausted:asleep still", after: 0, in: app)
        waitFor("claude:list:demo-claude:tired still", after: 0, in: app)
        let beforeResets = checkpoint(app)
        tab("Resets", in: app)
        waitFor("claude:reset:demo-claude-exhausted:hooray still", after: beforeResets, in: app)
        XCTAssertFalse(entries(app).contains { $0.event.hasPrefix("claude:") && $0.event.hasSuffix(" animated") })
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
