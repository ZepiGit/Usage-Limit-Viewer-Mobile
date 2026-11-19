import XCTest

/// Runs the production SwiftUI screens with normalized, synthetic quota records.
final class MarketingScreenshotTests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    func testCaptureIPhoneScreens() {
        let app = launchDemo()
        let claude = app.buttons["overview-account-demo-claude"]
        let codex = app.buttons["overview-account-demo-codex"]
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
        XCTAssertTrue(codex.waitForExistence(timeout: 10))
        XCTAssertLessThan(claude.frame.minY, codex.frame.minY)
        XCTAssertFalse(app.navigationBars["Overview"].buttons["Reorder"].exists)
        capture("iphone-overview", in: app)
        reach(codex, in: app)
        codex.tap()
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.tabBars.buttons["Overview"].isHittable)
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        for screen in ["Resets", "Settings"] {
            let matches = app.buttons.matching(identifier: screen)
            let tab = matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
            XCTAssertTrue(tab.waitForExistence(timeout: 10))
            tab.tap()
            XCTAssertTrue(app.navigationBars[screen].waitForExistence(timeout: 10))
            capture("iphone-\(screen.lowercased())", in: app)
        }
        verifyCustomLayout(in: app)

        XCUIApplication().terminate()
        let expired = launchDemo(["-overview-expired-demo", "-reduce-motion"])
        let expiredCodex = expired.buttons["overview-account-demo-codex"]
        let expiredClaude = expired.buttons["overview-account-demo-claude"]
        XCTAssertTrue(expiredCodex.waitForExistence(timeout: 10))
        XCTAssertTrue(expiredClaude.waitForExistence(timeout: 10))
        XCTAssertTrue(expiredCodex.isHittable)
        XCTAssertLessThan(expiredCodex.frame.minY, expiredClaude.frame.minY)
        XCTAssertTrue(expiredCodex.label.contains("Sign-in expired · showing last known data"))
        capture("iphone-overview-order", in: expired)
        expiredCodex.tap()
        XCTAssertTrue(expired.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(expired.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertTrue(expired.staticTexts["Sign-in expired — reconnect this account"].exists)
        capture("iphone-overview-detail", in: expired, requiresDemoLabel: false)
        expired.terminate()

        let empty = launchDemo(["-overview-empty-demo"])
        XCTAssertTrue(empty.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        XCTAssertFalse(empty.buttons["overview-account-demo-codex"].exists)
        capture("iphone-overview-empty", in: empty)
    }

    func testCaptureIPadOverview() {
        let app = launchDemo()
        capture("ipad-overview", in: app)
    }

    private func launchDemo(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-ui-testing", "-marketing-demo",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"
        ] + arguments
        app.launch()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 15))
        if !arguments.contains("-overview-empty-demo") {
            XCTAssertTrue(app.buttons["overview-account-demo-codex"].waitForExistence(timeout: 10))
        }
        XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists)
        XCTAssertEqual(app.alerts.count, 0)
        return app
    }

    private func reach(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    private func verifyCustomLayout(in app: XCUIApplication) {
        let layouts = app.buttons["Widget layouts"]
        reach(layouts, in: app); layouts.tap()
        app.buttons["New custom layout"].tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10)); name.tap()
        if let value = name.value as? String { name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
        name.typeText("QA layout")
        app.buttons["Show OpenAI Codex"].tap()
        app.buttons["Show Claude"].tap()
        let handles = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Reorder")).allElementsBoundByIndex.filter { $0.isHittable }
        XCTAssertGreaterThanOrEqual(handles.count, 2)
        handles[1].press(forDuration: 0.3, thenDragTo: handles[0])
        capture("iphone-custom-layout", in: app, requiresDemoLabel: false)
        app.buttons["Save"].tap()
        XCTAssertTrue(app.navigationBars["Widget layouts"].waitForExistence(timeout: 10))
        app.terminate()
        let restored = launchDemo()
        restored.buttons.matching(identifier: "Settings").allElementsBoundByIndex.first { $0.isHittable }!.tap()
        let savedLayouts = restored.buttons["Widget layouts"]
        reach(savedLayouts, in: restored); savedLayouts.tap()
        let preset = restored.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "QA layout")).firstMatch
        XCTAssertTrue(preset.waitForExistence(timeout: 10)); preset.tap()
        XCTAssertEqual(restored.buttons["Show Claude"].value as? String, "Selected")
        XCTAssertEqual(restored.buttons["Show OpenAI Codex"].value as? String, "Selected")
        XCTAssertLessThan(restored.buttons["Show Claude"].frame.minY, restored.buttons["Show OpenAI Codex"].frame.minY)
        capture("iphone-custom-layout-restored", in: restored, requiresDemoLabel: false)
    }

    private func capture(_ name: String, in app: XCUIApplication, requiresDemoLabel: Bool = true) {
        if requiresDemoLabel { XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists) }
        XCTAssertEqual(app.alerts.count, 0)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
