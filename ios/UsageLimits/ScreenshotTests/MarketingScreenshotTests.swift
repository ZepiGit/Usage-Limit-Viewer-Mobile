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
        let dials = launchDemo()
        selectDials(in: dials, captureSettings: true)
        captureDials("iphone-overview-dials", in: dials)
        dials.terminate()
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

    func testCaptureIPhoneAppearance() {
        let demo = launchDemo(["-reduce-motion"])
        openAppearance(in: demo)
        chooseAppearance("settings-theme-lightPaper", in: demo)
        capture("iphone-appearance-light", in: demo)
        chooseAppearance("settings-theme-pureBlack", in: demo)
        capture("iphone-appearance-black", in: demo)
        demo.terminate()
    }

    func testCaptureIPhoneAppearanceFonts() {
        // No marketing fixture: this exercises the real settings store and first loaded state.
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-reduce-motion", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 15))
        openAppearance(in: app)
        chooseAppearance("settings-theme-warmDark", in: app)
        chooseAppearance("settings-text-font-system", in: app)
        chooseAppearance("settings-figure-font-robotoMono", in: app)
        chooseAppearance("settings-theme-lightPaper", in: app, towardStart: true)
        chooseAppearance("settings-text-font-robotoFlex", in: app)
        chooseAppearance("settings-figure-font-jetBrainsMono", in: app)
        let preview = app.descendants(matching: .any)["settings-appearance-preview"].firstMatch
        scrollToCreditControl(preview, in: app, towardStart: true, requireHittable: false)
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "saved"), object: preview)
        XCTAssertEqual(XCTWaiter.wait(for: [saved], timeout: 10), .completed)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 15))
        openAppearance(in: app)
        for identifier in ["settings-theme-lightPaper", "settings-text-font-robotoFlex", "settings-figure-font-jetBrainsMono"] {
            let choice = app.buttons[identifier]
            scrollToCreditControl(choice, in: app)
            XCTAssertEqual(choice.value as? String, "Selected")
        }
        scrollToCreditControl(preview, in: app, towardStart: true, requireHittable: false)
        assertAppearancePreview(in: app)
        capture("iphone-appearance-fonts", in: app, requiresDemoLabel: false)
        let licences = app.buttons["settings-font-licences"]
        scrollToCreditControl(licences, in: app)
        licences.tap()
        for family in ["roboto_flex", "ibm_plex_sans", "figtree", "roboto_mono", "jetbrains_mono", "ibm_plex_mono"] {
            let link = app.buttons["font-licence-link-\(family)"]
            reach(link, in: app)
            link.tap()
            let notice = app.staticTexts["font-licence-\(family)"]
            XCTAssertTrue(notice.waitForExistence(timeout: 10))
            XCTAssertTrue(notice.label.contains("SIL OPEN FONT LICENSE"))
            XCTAssertTrue(notice.label.contains("DISCLAIMER"))
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.terminate()
    }

    func testCaptureIPhoneAppearanceLargeGerman() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-reduce-motion", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        defer { app.terminate() }
        let settings = app.buttons.matching(identifier: "Einstellungen")
        let tab = settings.allElementsBoundByIndex.first { $0.isHittable } ?? settings.firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 15))
        tab.tap()
        for identifier in ["settings-theme-lightPaper", "settings-text-font-robotoFlex", "settings-figure-font-jetBrainsMono"] {
            let choice = app.buttons[identifier]
            scrollToCreditControl(choice, in: app)
            choice.tap()
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "Ausgewählt"), object: choice)
            XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
        }
        let preview = app.descendants(matching: .any)["settings-appearance-preview"].firstMatch
        scrollToCreditControl(preview, in: app, towardStart: true, requireHittable: false)
        assertAppearancePreview(in: app, german: true)
        capture("iphone-appearance-fonts-de-large", in: app, requiresDemoLabel: false)
    }

    private func assertAppearancePreview(in app: XCUIApplication, german: Bool = false) {
        func previewElements() -> [XCUIElement] {
            let preview = app.descendants(matching: .any)["settings-appearance-preview"].firstMatch
            let figure = preview.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@",
                german ? "18 %" : "18%", german ? "82 %" : "82%")).firstMatch
            let details = preview.staticTexts[german ? "23:31 · 1 Std. 18 Min. · 12,40 $" : "23:31 · 1h 18m · $12.40"]
            return [preview, preview.staticTexts["Claude Pro"], figure, details]
        }
        // A visible Form card can still report a stale accessibility child after scrolling.
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let viewport = self.captureViewport(in: app)
            return previewElements().allSatisfy { element in
                guard element.exists else { return false }
                let frame = element.frame
                return !frame.isEmpty && viewport.contains(frame)
            }
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed,
            "Appearance preview and all sample text must fit the capture viewport")
        let viewport = captureViewport(in: app)
        for element in previewElements() {
            XCTAssertTrue(element.exists, "Appearance preview content must be rendered: \(element)")
            let frame = element.frame
            XCTAssertFalse(frame.isEmpty, "Appearance preview content needs visible bounds: \(element)")
            XCTAssertTrue(viewport.contains(frame), "Appearance preview content must fit the capture viewport: \(element); \(viewport)")
        }
    }

    private func openAppearance(in app: XCUIApplication) {
        let tabs = app.buttons.matching(identifier: "Settings")
        let tab = tabs.allElementsBoundByIndex.first { $0.isHittable } ?? tabs.firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        scrollToCreditControl(app.buttons["settings-theme-pureBlack"], in: app)
    }

    private func chooseAppearance(_ identifier: String, in app: XCUIApplication, towardStart: Bool? = nil) {
        let choice = app.buttons[identifier]
        scrollToCreditControl(choice, in: app, towardStart: towardStart)
        choice.tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "Selected"), object: choice)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
    }

    func testCaptureIPhoneCredits() {
        let app = launchDemo(["-overview-credits-demo", "-settings-trace", "-settings-touch-trace",
                              "-settings-native-control-trace"])
        defer { app.terminate() }
        let settings = app.buttons.matching(identifier: "Settings")
        let settingsTab = settings.allElementsBoundByIndex.first { $0.isHittable } ?? settings.firstMatch
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 10))
        settingsTab.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))

        let showCredits = app.switches["settings-overview-show-credits"]
        scrollToCreditControl(showCredits, in: app)
        XCTAssertEqual(showCredits.value as? String, "1")
        let layout = app.segmentedControls["settings-overview-credit-layout"]
        let showFirst = app.descendants(matching: .any)["settings-overview-show-first"].firstMatch
        let manage = app.buttons["settings-manage-credit-accounts"]
        for enabled in [false, true] {
            scrollToCreditControl(showCredits, in: app)
            let frame = showCredits.frame
            let offset = CGVector(dx: 0.95, dy: 0.5)
            let tap = showCredits.coordinate(withNormalizedOffset: offset)
            let tapStarted = ProcessInfo.processInfo.systemUptime
            tap.tap()
            let tapEnded = ProcessInfo.processInfo.systemUptime
            let expected = enabled ? "1" : "0"
            let changed = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "value == %@", expected), object: showCredits)
            let result = XCTWaiter.wait(for: [changed], timeout: 10)
            if result != .completed {
                let probe = app.descendants(matching: .any)["settings-trace"].firstMatch
                let trace = XCTAttachment(string: (probe.value as? String ?? "Settings trace unavailable")
                    + "\n\n" + showCredits.debugDescription)
                trace.name = "Show credits input and accessibility trace"
                trace.lifetime = .keepAlways
                add(trace)
                let screenshot = XCTAttachment(screenshot: app.screenshot())
                screenshot.name = "Show credits after unsuccessful tap"
                screenshot.lifetime = .keepAlways
                add(screenshot)
            }
            XCTAssertEqual(result, .completed,
                "Show credits expected \(expected), got \(showCredits.value as? String ?? "unavailable"); "
                    + "tap uptime \(tapStarted)–\(tapEnded), frame \(frame), offset \(offset), "
                    + "viewport \(captureViewport(in: app))")
            if !enabled {
                XCTAssertFalse(layout.exists)
                XCTAssertFalse(showFirst.exists)
                scrollToCreditControl(manage, in: app)
            }
        }

        scrollToCreditControl(layout, in: app)
        XCTAssertTrue(layout.buttons["Normal"].isSelected)
        layout.buttons["Compact"].tap()
        XCTAssertTrue(layout.buttons["Compact"].isSelected)
        scrollToCreditControl(showFirst, in: app)
        XCTAssertTrue(captureViewport(in: app).contains(showFirst.frame))
        showFirst.tap()
        let creditsFirst = app.buttons["Credit accounts"]
        XCTAssertTrue(creditsFirst.waitForExistence(timeout: 10))
        XCTAssertTrue(creditsFirst.isHittable)
        creditsFirst.tap()
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", "Credit accounts"), object: showFirst)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
        scrollToCreditControl(showCredits, in: app)
        XCTAssertTrue(showCredits.isHittable)
        XCTAssertEqual(showCredits.value as? String, "1")
        capture("iphone-settings-credits-toggle", in: app)
        scrollToCreditControl(manage, in: app, alongside: [layout, showFirst])
        XCTAssertTrue(layout.isHittable && showFirst.isHittable && manage.isHittable)
        capture("iphone-settings-credits", in: app)

        let overview = app.buttons.matching(identifier: "Overview")
        let overviewTab = overview.allElementsBoundByIndex.first { $0.isHittable } ?? overview.firstMatch
        XCTAssertTrue(overviewTab.waitForExistence(timeout: 10))
        overviewTab.tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        let compact = app.buttons["overview-credits-compact"]
        XCTAssertTrue(compact.waitForExistence(timeout: 10))
        XCTAssertTrue(compact.isHittable)
        XCTAssertEqual(compact.label, "Credits · 3 balances")
        XCTAssertTrue((compact.value as? String)?.contains("DeepSeek $3.18") == true)
        let claude = app.buttons["overview-account-demo-claude"]
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
        XCTAssertLessThan(compact.frame.minY, claude.frame.minY)
        let heading = app.staticTexts["overview-credits-heading"]
        XCTAssertFalse(heading.exists)
        capture("iphone-credits-compact", in: app)

        compact.tap()
        let collapse = app.buttons["overview-credits-collapse"]
        XCTAssertTrue(collapse.waitForExistence(timeout: 10))
        XCTAssertTrue(heading.waitForExistence(timeout: 10))
        XCTAssertEqual(heading.label, "Credits")
        XCTAssertFalse(compact.exists)
        let deepseek = app.staticTexts["DeepSeek"]
        scrollToCreditControl(deepseek, in: app)
        XCTAssertTrue(app.staticTexts["$3.18"].isHittable)
        XCTAssertTrue(heading.isHittable && collapse.isHittable)
        capture("iphone-credits-expanded", in: app)
        collapse.tap()
        XCTAssertTrue(compact.waitForExistence(timeout: 10))
        XCTAssertTrue(compact.isHittable)
        XCTAssertFalse(collapse.exists)
        XCTAssertFalse(heading.exists)
    }

    func testCaptureIPhoneClaudeMood() {
        let app = launchDemo(["-claude-mood-demo", "-reduce-motion"])
        defer { app.terminate() }
        let asleep = app.buttons["overview-account-demo-claude-exhausted"]
        let tired = app.buttons["overview-account-demo-claude"]
        XCTAssertTrue(asleep.waitForExistence(timeout: 10))
        XCTAssertTrue(asleep.isHittable && tired.isHittable)
        XCTAssertLessThan(asleep.frame.minY, tired.frame.minY)
        capture("iphone-claude-mood", in: app)

        let resets = app.tabBars.buttons["Resets"]
        XCTAssertTrue(resets.isHittable)
        resets.tap()
        XCTAssertTrue(app.navigationBars["Resets"].waitForExistence(timeout: 10))
        let claudeReset = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Claude Max", "resets at")
        ).firstMatch
        XCTAssertTrue(claudeReset.waitForExistence(timeout: 10))
        capture("iphone-claude-resets-still", in: app)
    }

    func testCaptureIPhoneClaudeHooray() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing", "-fixture-hooray", "-reduce-motion", "-motion-log",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["overview-account-fixture-claude"].waitForExistence(timeout: 10))
        let log = app.staticTexts["motion-log"]
        let cheer = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            log.label.contains("claude:list:fixture-claude:hooray still")
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [cheer], timeout: 10), .completed)
        capture("iphone-claude-hooray", in: app, requiresDemoLabel: false)
        let expired = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let entries = log.label.components(separatedBy: " | ")
            guard let cheer = entries.lastIndex(where: { $0.contains("claude:list:fixture-claude:hooray still") }) else { return false }
            return entries.dropFirst(cheer + 1).contains { $0.contains("claude:list:fixture-claude:idle still") }
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expired], timeout: 10), .completed)
        app.tabBars.buttons["Resets"].tap()
        XCTAssertTrue(app.navigationBars["Resets"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Overview"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
    }

    func testCaptureIPhoneClaudeStillSheet() {
        let app = launchDemo(["-claude-still-sheet", "-reduce-motion"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["claude-still-sheet"].waitForExistence(timeout: 10))
        capture("iphone-claude-still-sheet", in: app, requiresDemoLabel: false)
    }

    func testCaptureIPhoneWidgetLayoutGuidance() {
        let app = launchDemo(["-reduce-motion"])
        defer { app.terminate() }
        let settings = app.buttons.matching(identifier: "Settings")
        let tab = settings.allElementsBoundByIndex.first { $0.isHittable } ?? settings.firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        let layouts = app.buttons["Widget layouts"]
        reach(layouts, in: app)
        layouts.tap()
        XCTAssertTrue(app.navigationBars["Widget layouts"].waitForExistence(timeout: 10))
        let guidance = app.staticTexts["widget-mode-guidance"]
        XCTAssertTrue(guidance.waitForExistence(timeout: 10))
        XCTAssertTrue(guidance.label.contains("Settings › Display"))
        XCTAssertTrue(guidance.label.contains("VoiceOver"))
        XCTAssertTrue(app.descendants(matching: .any)["widget-layout-static-artwork"].firstMatch.exists)
        capture("iphone-widget-layout-guidance", in: app, requiresDemoLabel: false)
    }

    func testCaptureIPadOverview() {
        let app = launchDemo()
        capture("ipad-overview", in: app)
        selectDials(in: app, captureSettings: false)
        captureDials("ipad-overview-dials", in: app)
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

    private func captureViewport(in app: XCUIApplication) -> CGRect {
        let container = ["settings-form", "overview-scroll"].lazy
            .map { app.descendants(matching: .any)[$0].firstMatch }
            .first { $0.exists && $0.isHittable }
        guard let container else { return .null }
        var frame = container.frame.intersection(app.frame)
        for bar in app.navigationBars.allElementsBoundByIndex + app.tabBars.allElementsBoundByIndex {
            guard bar.exists, bar.frame.intersects(frame) else { continue }
            if bar.frame.midY < app.frame.midY {
                let bottom = frame.maxY
                frame.origin.y = max(frame.minY, bar.frame.maxY)
                frame.size.height = max(0, bottom - frame.minY)
            } else {
                frame.size.height = max(0, min(frame.maxY, bar.frame.minY) - frame.minY)
            }
        }
        return frame
    }

    private func scrollToCreditControl(_ element: XCUIElement, in app: XCUIApplication,
                                       towardStart: Bool? = nil, requireHittable: Bool = true,
                                       alongside: [XCUIElement] = []) {
        let targets = [element] + alongside
        func found() -> Bool {
            let viewport = captureViewport(in: app)
            return targets.allSatisfy { target in
                guard target.exists else { return false }
                let frame = target.frame
                return !frame.isEmpty && !viewport.isEmpty && viewport.contains(frame)
                    && (!requireHittable || target.isHittable)
            }
        }
        if found() { return }
        let container = ["settings-form", "overview-scroll"].lazy
            .map { app.descendants(matching: .any)[$0].firstMatch }
            .first { $0.exists && $0.isHittable }
        guard let container else {
            XCTFail("Credit controls require a visible Settings or Overview scroller")
            return
        }
        let bounds = container.frame
        let frame = bounds.intersection(app.frame)
        let firstUp = towardStart ?? (element.exists && element.frame.maxY < frame.minY + 60)
        let firstButton = container.buttons.firstMatch
        let gutterX = firstButton.exists ? max(frame.minX + 4, firstButton.frame.minX - 8) : frame.minX + 16
        let x = (gutterX - bounds.minX) / bounds.width
        let high = (frame.minY + 0.30 * frame.height - bounds.minY) / bounds.height
        let low = (frame.minY + 0.75 * frame.height - bounds.minY) / bounds.height
        let top = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: high))
        let bottom = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: low))
        if alongside.isEmpty {
            for towardStart in [firstUp, !firstUp] {
                let (start, end) = towardStart ? (top, bottom) : (bottom, top)
                for _ in 0..<12 {
                    start.press(forDuration: 0.05, thenDragTo: end)
                    if found() { return }
                }
            }
        } else {
            for _ in 0..<12 {
                let viewport = captureViewport(in: app)
                let frames = targets.map { $0.exists ? $0.frame : .null }
                guard !viewport.isEmpty, frames.allSatisfy({ !$0.isEmpty }) else { break }
                let group = frames.reduce(CGRect.null) { $0.union($1) }
                guard group.height <= viewport.height else {
                    XCTFail("Capture controls cannot fit together: \(group); viewport: \(viewport)")
                    return
                }
                let delta = group.minY < viewport.minY ? viewport.minY - group.minY
                    : min(0, viewport.maxY - group.maxY)
                guard delta != 0 else { break }
                let startY = delta > 0 ? high : low
                let endY = min(low, max(high, startY + delta / bounds.height))
                let start = delta > 0 ? top : bottom
                let end = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: endY))
                start.press(forDuration: 0.05, thenDragTo: end)
                if found() { return }
            }
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Unreachable capture element \(element.identifier)"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        XCTAssertTrue(found(), "Capture controls must be visible: \(targets); viewport: \(captureViewport(in: app))")
    }

    private func selectDials(in app: XCUIApplication, captureSettings: Bool) {
        let settings = app.buttons.matching(identifier: "Settings")
        let tab = settings.allElementsBoundByIndex.first { $0.isHittable } ?? settings.firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))

        let display = app.segmentedControls["settings-percent-display"]
        scrollToCreditControl(display, in: app)
        display.buttons["Used"].tap()
        XCTAssertTrue(display.buttons["Used"].isSelected)
        let displayPreview = app.descendants(matching: .any)["settings-display-preview"].firstMatch
        scrollToCreditControl(displayPreview, in: app, requireHittable: false)
        XCTAssertTrue(displayPreview.waitForExistence(timeout: 10))
        XCTAssertTrue(displayPreview.staticTexts["82%"].waitForExistence(timeout: 10))
        XCTAssertTrue(displayPreview.staticTexts["used"].exists)

        let layout = app.segmentedControls["settings-overview-layout"]
        scrollToCreditControl(layout, in: app)
        XCTAssertTrue(layout.buttons["List"].isSelected)
        layout.buttons["Dials"].tap()
        XCTAssertTrue(layout.buttons["Dials"].isSelected)
        let dialsPreview = app.descendants(matching: .any)["settings-preview-dials"].firstMatch
        if captureSettings {
            XCTAssertTrue(layout.isHittable)
            capture("iphone-settings-layout", in: app)
            let claude = app.descendants(matching: .any)["settings-preview-dial-preview-claude"].firstMatch
            scrollToCreditControl(claude, in: app)
            XCTAssertTrue(dialsPreview.exists)
            XCTAssertTrue(claude.isHittable)
            capture("iphone-settings-layout-preview", in: app)
        }

        let overview = app.buttons.matching(identifier: "Overview")
        let overviewTab = overview.allElementsBoundByIndex.first { $0.isHittable } ?? overview.firstMatch
        overviewTab.tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["overview-display-mode"].value as? String, "Used")
    }

    private func captureDials(_ name: String, in app: XCUIApplication) {
        XCTAssertTrue(app.buttons["overview-dial-demo-claude"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["overview-dial-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["overview-account-demo-claude"].exists)
        capture(name, in: app)
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
        let codex = app.buttons["Reorder Show OpenAI Codex"]
        let claude = app.buttons["Reorder Show Claude"]
        XCTAssertTrue(codex.isHittable && claude.isHittable)
        // Drop in the upper part of the target row. Its midpoint can resolve to the slot
        // below it, leaving the order unchanged.
        claude.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.3, thenDragTo: codex.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)))
        let reordered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.buttons["Show Claude"].frame.minY < app.buttons["Show OpenAI Codex"].frame.minY
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [reordered], timeout: 10), .completed,
            "the drag must place Claude above Codex before saving")
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
