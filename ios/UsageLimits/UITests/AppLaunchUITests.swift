import XCTest

/// Does the app open?
///
/// Everything else in this repository is verified without ever starting the app: the kit's tests
/// run on Linux, and the macOS job compiled the SwiftUI and WidgetKit code without executing a
/// line of it. Compiling proves the types line up. It does not prove the app launches, that the
/// tab bar appears, or that the first screen renders rather than trapping on the first optional —
/// and those are the failures a user meets before any of the others.
///
/// This is the only test in the project that runs the real binary, on a real simulator, through
/// the real launch path.
final class AppLaunchUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        // A failing assertion should stop the test at the point of failure rather than carrying
        // on and reporting a cascade of consequences.
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    /// Swipes until the element materialises, or gives up rather than swiping for ever.
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let identifiers = ["provider-icons-list", "provider-picker-scroll", "settings-form", "overview-scroll"]
        let container = identifiers.map { app.descendants(matching: .any)[$0].firstMatch }
            .first { $0.exists && $0.isHittable } ?? app
        for _ in 0..<12 {
            if element.isHittable { return true }
            let frame = container.frame.intersection(app.frame)
            let reverse = element.exists && element.frame.maxY < frame.minY + 60
            // The failed movie shows a drag over Claude activating the card. Use the
            // visible gutter before the first card, inside the landscape safe area.
            let firstButton = container.buttons.firstMatch
            let gutterX = firstButton.exists ? max(frame.minX + 4, firstButton.frame.minX - 8) : frame.minX + 16
            let x = (gutterX - container.frame.minX) / container.frame.width
            let start = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: reverse ? 0.3 : 0.75))
            let end = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: reverse ? 0.75 : 0.3))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        return element.waitForExistence(timeout: 2) && element.isHittable
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // Read by the app to skip the notification-permission prompt, which is a system alert
        // that would otherwise sit over the UI and fail every query behind it.
        app.launchArguments += ["-ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"] + arguments
        app.launch()
        waitForOrientation(app, landscape: false)
        return app
    }

    private func waitForOrientation(_ app: XCUIApplication, landscape: Bool) {
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            landscape ? app.frame.width > app.frame.height : app.frame.height > app.frame.width
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed)
    }

    private func tabButton(_ label: String, in app: XCUIApplication) -> XCUIElement {
        // iPadOS exposes the floating tab cell and its child with the same label.
        // Select the hittable match, then assert the destination after tapping it.
        let matches = app.buttons.matching(identifier: label)
        return matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
    }

    func testTheAppLaunches() {
        let app = launch()

        XCTAssertEqual(app.state, .runningForeground, "the app should still be running")
    }

    func testProviderIconSelectionPersists() {
        var app = launch()
        tabButton("Settings", in: app).tap()
        XCTAssertTrue(scrollTo(app.buttons["Provider icons"], in: app))
        app.buttons["Provider icons"].tap()
        let alternative = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(alternative, in: app))
        alternative.tap()
        XCTAssertEqual(alternative.value as? String, "Selected")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Provider icon choice"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
        app = launch()
        tabButton("Settings", in: app).tap()
        XCTAssertTrue(scrollTo(app.buttons["Provider icons"], in: app))
        app.buttons["Provider icons"].tap()
        let restored = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(restored, in: app))
        XCTAssertEqual(restored.value as? String, "Selected")
        let defaultIcon = app.buttons["Claude Code · Color"]
        XCTAssertTrue(scrollTo(defaultIcon, in: app))
        defaultIcon.tap()
    }

    func testAllThreeDestinationsExist() {
        // The shell from the reference design. If navigation failed to build, the tab bar is the
        // first thing missing, and every other query would fail for a reason that reads as
        // unrelated.
        let app = launch()

        for tab in ["Overview", "Resets", "Settings"] {
            XCTAssertTrue(
                tabButton(tab, in: app).waitForExistence(timeout: 10),
                "the \(tab) tab should be reachable")
        }
        XCTAssertFalse(app.tabBars.buttons["Accounts"].exists)
    }

    func testEachScreenOpensWithNoAccountsConnected() {
        // The state every user sees first, on a fresh install with nothing signed in — and the
        // one most likely to divide by a count of zero or unwrap an empty list.
        let app = launch()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        XCTAssertTrue(scrollTo(app.buttons["overview-add-account"], in: app))

        for tab in ["Resets", "Settings", "Overview"] {
            let button = tabButton(tab, in: app)
            XCTAssertTrue(button.waitForExistence(timeout: 10), "\(tab) should exist")
            button.tap()
            XCTAssertTrue(app.navigationBars[tab].waitForExistence(timeout: 10))
            XCTAssertEqual(
                app.state, .runningForeground, "the app should survive opening \(tab)")
        }
    }

    func testOverviewFiltersShowMatchingAccounts() {
        let app = launch(["-marketing-demo", "-overview-expired-demo"])
        let codex = app.buttons["overview-account-demo-codex"]
        let claude = app.buttons["overview-account-demo-claude"]
        XCTAssertTrue(codex.waitForExistence(timeout: 10))
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
        let attention = app.buttons["overview-filter-needsAttention"]
        attention.tap()
        XCTAssertEqual(attention.value as? String, "Selected")
        XCTAssertTrue(codex.waitForExistence(timeout: 10))
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: claude)
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 10), .completed)
        let resets = app.buttons["overview-filter-resetsToday"]
        resets.tap()
        XCTAssertEqual(resets.value as? String, "Selected")
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
        let all = app.buttons["overview-filter-all"]
        all.tap()
        XCTAssertEqual(all.value as? String, "Selected")
        XCTAssertTrue(codex.waitForExistence(timeout: 10))
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
    }

    func testOverviewOpensAddCredit() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        XCTAssertTrue(scrollTo(app.buttons["overview-add-account"], in: app))
        let add = app.buttons["overview-add-credit"]
        XCTAssertTrue(scrollTo(add, in: app))
        add.tap()
        XCTAssertTrue(app.navigationBars["Add credit balance"].waitForExistence(timeout: 10))
    }

    func testTheSettingsScreenOffersTheDisplaySwitches() {
        // The tier switch stays. Reset stamps are now always available in Overview.
        let app = launch()

        let settings = tabButton("Settings", in: app)
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()

        XCTAssertTrue(scrollTo(app.switches["Show subscription tier"], in: app))
        XCTAssertTrue(scrollTo(app.segmentedControls.firstMatch, in: app))
        XCTAssertFalse(app.switches["Show renewal time"].exists)
    }

    func testTheAddAccountSheetOffersEveryProvider() {
        // Overview must still reach every provider's sign-in path.
        let app = launch()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))

        let add = app.buttons["overview-add-account"]
        XCTAssertTrue(scrollTo(add, in: app), "the add-account button should be there")
        add.tap()

        XCTAssertTrue(
            app.navigationBars["Add account"].waitForExistence(timeout: 10),
            "the add-account sheet should open")

        for provider in ["OpenAI Codex", "Claude", "Antigravity", "Grok", "Kimi", "Devin", "Meta Muse", "Z.ai GLM", "OpenCode Go"] {
            XCTAssertTrue(
                scrollTo(app.staticTexts[provider], in: app),
                "\(provider) should be offered")
        }
    }

    func testLandscapeAndLargeTextKeepTheProviderPickerReachable() {
        let app = XCUIApplication()
        app.launchArguments += ["-ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForOrientation(app, landscape: true)
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        let add = app.buttons["overview-add-account"]
        XCTAssertTrue(scrollTo(add, in: app))
        add.tap()
        XCTAssertTrue(app.navigationBars["Add account"].waitForExistence(timeout: 10))
        XCTAssertTrue(scrollTo(app.staticTexts["Kimi"], in: app))
        XCTAssertTrue(app.buttons["Cancel"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Landscape provider picker with large text"
        screenshot.lifetime = .keepAlways
        self.add(screenshot)
        app.buttons["Cancel"].tap()
        XCTAssertEqual(app.state, .runningForeground)
    }
}
