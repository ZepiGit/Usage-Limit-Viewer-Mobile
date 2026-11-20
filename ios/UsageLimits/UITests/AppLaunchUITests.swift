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

    private let diagnosticLaunchArguments = ["-ui-testing", "-settings-trace", "-settings-touch-trace"]

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

    /// Search both ways: a lazy Form may not expose an offscreen target's frame at all.
    private func scrollTo(
        _ element: XCUIElement, in app: XCUIApplication, towardStart: Bool? = nil,
        clearOfBottomTabs tabBarFrame: CGRect = .null
    ) -> Bool {
        func overlapsTabs() -> Bool {
            !tabBarFrame.isNull && element.frame.intersects(tabBarFrame)
        }
        func canTap() -> Bool {
            element.exists && element.isHittable && !overlapsTabs()
        }
        let exists = element.exists
        if canTap() { return true }
        let identifiers = ["provider-icons-list", "provider-picker-scroll", "settings-form", "overview-scroll"]
        let container = identifiers.lazy.map { app.descendants(matching: .any)[$0].firstMatch }
            .first { $0.exists && $0.isHittable } ?? app
        let bounds = container.frame
        let frame = bounds.intersection(app.frame)
        let firstUp: Bool
        if exists && overlapsTabs() {
            firstUp = false
        } else if let towardStart {
            firstUp = towardStart
        } else if exists, !element.frame.isEmpty {
            firstUp = element.frame.midY < frame.midY
        } else {
            // An absent target in a lazy Form has no frame. Near the top, keep looking down.
            firstUp = container.identifier == "settings-form"
                && !container.staticTexts["iOS decides when to refresh and may refresh less often."].exists
        }
        // Stay in the visible gutter so a slow drag doesn't activate a card.
        let firstButton = container.buttons.firstMatch
        let gutterX = firstButton.exists ? max(frame.minX + 4, firstButton.frame.minX - 8) : frame.minX + 16
        let x = (gutterX - bounds.minX) / bounds.width
        let high = (frame.minY + 0.30 * frame.height - bounds.minY) / bounds.height
        let low = (frame.minY + 0.75 * frame.height - bounds.minY) / bounds.height
        let top = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: high))
        let bottom = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: low))
        for towardStart in [firstUp, !firstUp] {
            let (start, end) = towardStart ? (top, bottom) : (bottom, top)
            for _ in 0..<12 {
                start.press(forDuration: 0.05, thenDragTo: end)
                if canTap() { return true }
            }
        }
        return element.waitForExistence(timeout: 2) && canTap()
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // Read by the app to skip the notification-permission prompt, which is a system alert
        // that would otherwise sit over the UI and fail every query behind it.
        app.launchArguments += diagnosticLaunchArguments + [
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"
        ] + arguments
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

    // iOS can report a Form control under the floating tab bar as hittable.
    private func bottomTabBarFrame(in app: XCUIApplication) -> CGRect {
        let tabBar = app.tabBars.firstMatch
        guard tabBar.exists else { return .null }
        let frame = tabBar.frame
        return frame.midY > app.frame.midY ? frame : .null
    }

    private func tapUptime(_ tap: () -> Void) -> String {
        let started = ProcessInfo.processInfo.systemUptime
        tap()
        return "\(started)–\(ProcessInfo.processInfo.systemUptime)"
    }

    private func reportControlFailure(_ message: String, control: XCUIElement, in app: XCUIApplication,
                                      tapFrame: CGRect, bottomTabs: CGRect, tapPoint: CGPoint? = nil,
                                      tapUptime: String? = nil) {
        attachSettingsTrace(in: app)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = message
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let point = tapPoint.map { ", tap point: \($0)" } ?? ""
        let uptime = tapUptime.map { "; tap uptime: \($0)" } ?? ""
        XCTFail("\(message)\(uptime); tap frame: \(tapFrame)\(point), bottom tabs: \(bottomTabs), app state: \(app.state), "
            + "Settings visible: \(app.navigationBars["Settings"].exists), control: \(control.debugDescription)")
    }

    private func settingsTrace(in app: XCUIApplication) -> String {
        let probe = app.descendants(matching: .any)["settings-trace"].firstMatch
        return probe.exists ? (probe.value as? String ?? "Settings trace has no accessibility value")
                            : "Settings trace probe not found"
    }

    private func attachSettingsTrace(in app: XCUIApplication) {
        let trace = XCTAttachment(string: settingsTrace(in: app))
        trace.name = "Settings trace"
        trace.lifetime = .keepAlways
        add(trace)
    }

    private func selectSegment(_ option: String, in identifier: String, app: XCUIApplication, towardStart: Bool? = nil) {
        let bottomTabs = bottomTabBarFrame(in: app)
        let control = app.segmentedControls[identifier]
        guard scrollTo(control, in: app, towardStart: towardStart, clearOfBottomTabs: bottomTabs) else {
            reportControlFailure("Could not reach \(identifier) clear of the tab bar",
                                 control: control, in: app, tapFrame: .null, bottomTabs: bottomTabs)
            return
        }
        let segment = control.buttons[option]
        guard scrollTo(segment, in: app, towardStart: towardStart, clearOfBottomTabs: bottomTabs) else {
            reportControlFailure("Could not reach \(option) in \(identifier) clear of the tab bar",
                                 control: control, in: app, tapFrame: .null, bottomTabs: bottomTabs)
            return
        }
        let tapFrame = segment.frame
        let uptime = tapUptime { segment.tap() }
        let onSettings = app.navigationBars["Settings"].exists
        if !onSettings || !segment.exists || !segment.isSelected {
            reportControlFailure("\(option) should be selected in \(identifier) without leaving Settings",
                                 control: control, in: app, tapFrame: tapFrame, bottomTabs: bottomTabs,
                                 tapUptime: uptime)
        }
    }

    private func setSwitch(_ identifier: String, to enabled: Bool, in app: XCUIApplication,
                           initially: Bool? = nil) {
        let toggle = app.switches[identifier]
        let bottomTabs = bottomTabBarFrame(in: app)
        guard scrollTo(toggle, in: app, towardStart: false, clearOfBottomTabs: bottomTabs) else {
            reportControlFailure("Could not reach switch \(identifier) clear of the tab bar",
                                 control: toggle, in: app, tapFrame: .null, bottomTabs: bottomTabs)
            return
        }
        let before = toggle.value as? String
        let tapFrame = toggle.frame
        if let initially, before != (initially ? "1" : "0") {
            reportControlFailure("\(identifier) must start \(initially ? "on" : "off")",
                                 control: toggle, in: app, tapFrame: tapFrame, bottomTabs: bottomTabs)
            return
        }
        let expected = enabled ? "1" : "0"
        guard before != expected else { return }
        // XCUI exposes the whole Form row. Tap inside the trailing switch, not at the row edge.
        let offset = CGVector(dx: 0.9, dy: 0.5)
        let point = CGPoint(x: tapFrame.minX + tapFrame.width * offset.dx,
                            y: tapFrame.minY + tapFrame.height * offset.dy)
        let coordinate = toggle.coordinate(withNormalizedOffset: offset)
        let uptime = tapUptime { coordinate.tap() }
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", expected), object: toggle)
        if XCTWaiter.wait(for: [changed], timeout: 10) != .completed {
            reportControlFailure("\(identifier) did not turn \(enabled ? "on" : "off")",
                                 control: toggle, in: app, tapFrame: tapFrame, bottomTabs: bottomTabs,
                                 tapPoint: point, tapUptime: uptime)
        }
    }

    func testTheAppLaunches() {
        let app = launch()

        XCTAssertEqual(app.state, .runningForeground, "the app should still be running")
    }

    func testProviderIconSelectionPersists() {
        var app = launch()
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        let providerIcons = app.buttons["settings-provider-icons"]
        XCTAssertTrue(scrollTo(providerIcons, in: app, towardStart: false))
        providerIcons.tap()
        let alternative = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(alternative, in: app, towardStart: false))
        alternative.tap()
        XCTAssertEqual(alternative.value as? String, "Selected")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Provider icon choice"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
        app = launch()
        tabButton("Settings", in: app).tap()
        let restoredProviderIcons = app.buttons["settings-provider-icons"]
        XCTAssertTrue(scrollTo(restoredProviderIcons, in: app, towardStart: false))
        restoredProviderIcons.tap()
        let restored = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(restored, in: app, towardStart: false))
        XCTAssertEqual(restored.value as? String, "Selected")
        let picker = app.navigationBars["Provider icons"]
        picker.buttons.firstMatch.tap()
        XCTAssertTrue(scrollTo(restoredProviderIcons, in: app, towardStart: false))
        let animation = app.switches["settings-animate-claude-code-icon"]
        XCTAssertFalse(animation.exists)
        restoredProviderIcons.tap()
        let defaultIcon = app.buttons["Claude Code · Color"]
        XCTAssertTrue(scrollTo(defaultIcon, in: app, towardStart: true))
        defaultIcon.tap()
        XCTAssertEqual(defaultIcon.value as? String, "Selected")
        picker.buttons.firstMatch.tap()
        XCTAssertTrue(scrollTo(animation, in: app, towardStart: false))
    }

    func testClaudeAnimationSwitchAndSettingsSamples() {
        let app = launch(["-marketing-demo", "-claude-mood-demo", "-motion-log"])
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        let toggle = app.switches["settings-animate-claude-code-icon"]
        XCTAssertTrue(scrollTo(toggle, in: app, towardStart: false))
        XCTAssertEqual(toggle.value as? String, "1")
        let log = app.staticTexts["motion-log"]
        func expectDecision(_ decision: String) {
            let observed = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "label CONTAINS %@", decision), object: log)
            XCTAssertEqual(XCTWaiter.wait(for: [observed], timeout: 10), .completed, "\(decision); \(log.label)")
        }
        expectDecision("claude:provider:provider:asleep animated")

        setSwitch("settings-animate-claude-code-icon", to: false, in: app, initially: true)
        expectDecision("claude:provider:provider:asleep still")
        let list = app.descendants(matching: .any)["settings-preview-list"].firstMatch
        XCTAssertTrue(scrollTo(list, in: app, towardStart: false))
        expectDecision("claude:preview-list:preview-claude:tired still")
        selectSegment("Dials", in: "settings-overview-layout", app: app, towardStart: false)
        let dial = app.descendants(matching: .any)["settings-preview-dial-preview-claude"].firstMatch
        XCTAssertTrue(scrollTo(dial, in: app, towardStart: true))
        expectDecision("claude:preview-dial:preview-claude:tired still")
        tabButton("Overview", in: app).tap()
        XCTAssertTrue(app.buttons["overview-dial-demo-claude-exhausted"].waitForExistence(timeout: 10))
        expectDecision("claude:dial:demo-claude-exhausted:asleep still")
    }

    func testSettingsSwitchUnderInjectedMainThreadStall() {
        let durations = [0, 30, 60, 90, 120]
        var attempts: [(stallIndex: Int, press: Bool)] = []
        for repetition in 0..<4 {
            for offset in durations.indices {
                let stallIndex = (offset + repetition) % durations.count
                for press in repetition.isMultiple(of: 2) ? [false, true] : [true, false] {
                    attempts.append((stallIndex: stallIndex, press: press))
                }
            }
        }
        let stalls = attempts.map { String(durations[$0.stallIndex]) }.joined(separator: ",")
        let app = launch(["-marketing-demo", "-reduce-motion", "-settings-stall-ms", stalls])
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        let toggle = app.switches["Limit exhausted"]
        let bottomTabs = bottomTabBarFrame(in: app)
        guard scrollTo(toggle, in: app, towardStart: false, clearOfBottomTabs: bottomTabs) else {
            reportControlFailure("Could not reach Limit exhausted clear of the tab bar",
                                 control: toggle, in: app, tapFrame: .null, bottomTabs: bottomTabs)
            return
        }
        guard let initial = toggle.value as? String, initial == "0" || initial == "1" else {
            attachSettingsTrace(in: app)
            XCTFail("Stall matrix invalid: initial switch AX value must be 0 or 1")
            return
        }
        var before = initial
        var changes = Array(repeating: [0, 0], count: durations.count)
        let coordinate = toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        for (index, attempt) in attempts.enumerated() {
            let gesture = attempt.press ? "press" : "tap"
            let uptime = tapUptime {
                if attempt.press {
                    coordinate.press(forDuration: 0.12)
                } else {
                    coordinate.tap()
                }
            }
            // XCUI returns after idle; a dropped tap must not start a change-expectation timeout.
            guard let after = toggle.value as? String, after == "0" || after == "1" else {
                attachSettingsTrace(in: app)
                XCTFail("Stall matrix invalid: switch AX value after attempt \(index + 1) must be 0 or 1")
                return
            }
            let changed = before != after
            if changed { changes[attempt.stallIndex][attempt.press ? 1 : 0] += 1 }
            print("[stall-matrix] attempt=\(index + 1) stall_ms=\(durations[attempt.stallIndex]) "
                + "gesture=\(gesture) before=\(before) after=\(after) changed=\(changed) uptime=\(uptime)")
            before = after
        }
        for stallIndex in durations.indices {
            for (gestureIndex, gesture) in ["tap", "press"].enumerated() {
                let changed = changes[stallIndex][gestureIndex]
                print("[stall-matrix] summary stall_ms=\(durations[stallIndex]) gesture=\(gesture) "
                    + "attempts=4 changed=\(changed) dropped=\(4 - changed)")
            }
        }
        let trace = settingsTrace(in: app)
        attachSettingsTrace(in: app)
        XCTAssertTrue(trace.contains("stall attempt=40 "),
                      "Stall matrix invalid: the hook must consume all 40 switch touches; see Settings trace")
    }

    func testMeasureSettingsPreviewMainThreadGaps() {
        executionTimeAllowance = 280
        for variant in ["animated", "still", "no-figure"] {
            let app = launch([
                "-marketing-demo", "-claude-mood-demo", "-motion-log", "-settings-gap-mode", variant
            ])
            defer { app.terminate() }
            tabButton("Settings", in: app).tap()
            XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
            let list = app.descendants(matching: .any)["settings-preview-list"].firstMatch
            XCTAssertTrue(scrollTo(list, in: app, towardStart: false))
            // Anchor the Claude heading, not the potentially taller-than-screen preview panel.
            let claude = list.staticTexts.matching(
                NSPredicate(format: "label == %@ OR label == %@", "Claude", "Claude Pro")).firstMatch
            XCTAssertTrue(scrollTo(claude, in: app, towardStart: true,
                                   clearOfBottomTabs: bottomTabBarFrame(in: app)))
            if variant != "no-figure" {
                let decision = "claude:preview-list:preview-claude:tired \(variant == "animated" ? "animated" : "still")"
                let log = app.staticTexts["motion-log"]
                let observed = XCTNSPredicateExpectation(
                    predicate: NSPredicate(format: "label CONTAINS %@", decision), object: log)
                XCTAssertEqual(XCTWaiter.wait(for: [observed], timeout: 10), .completed,
                               "\(variant) setup must expose the Claude list preview; \(log.label)")
            }

            // Let setup settle, then sleep only in the runner: AX queries would disturb the app.
            Thread.sleep(forTimeInterval: 2)
            let begin = ProcessInfo.processInfo.systemUptime
            print("[settings-gap-window] variant=\(variant) begin=\(begin)")
            Thread.sleep(forTimeInterval: 32)
            let end = ProcessInfo.processInfo.systemUptime
            print("[settings-gap-window] variant=\(variant) begin=\(begin) end=\(end)")

            let trace = settingsTrace(in: app)
            let attachment = XCTAttachment(string: trace)
            attachment.name = "Settings gaps \(variant)"
            attachment.lifetime = .keepAlways
            add(attachment)
            // A bucket covers [second, second + 1); exclude both boundary-straddling bins.
            let samples = trace.components(separatedBy: "\n").filter { line in
                guard line.contains("gap bucket variant=\(variant) "),
                      let field = line.split(separator: " ").first(where: { $0.hasPrefix("second=") }),
                      let second = Double(field.dropFirst("second=".count)) else { return false }
                return second >= begin && second + 1 <= end
            }
            XCTAssertFalse(samples.isEmpty, "\(variant) must record complete steady-state gap buckets")
            for sample in samples {
                print("[settings-gap-sample] \(sample)")
            }
        }
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

    func testDisplayAndOverviewPreviewsFollowNativeSettings() {
        let app = launch(["-marketing-demo", "-overview-expired-demo"])
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        selectSegment("Used", in: "settings-percent-display", app: app, towardStart: false)
        let displayPreview = app.descendants(matching: .any)["settings-display-preview"].firstMatch
        XCTAssertTrue(scrollTo(displayPreview, in: app, towardStart: false))
        XCTAssertTrue(displayPreview.staticTexts["82%"].waitForExistence(timeout: 10))
        XCTAssertTrue(displayPreview.staticTexts["used"].exists)
        XCTAssertFalse(displayPreview.staticTexts["18%"].exists)
        selectSegment("Left", in: "settings-percent-display", app: app, towardStart: true)
        XCTAssertTrue(scrollTo(displayPreview, in: app, towardStart: false))
        XCTAssertTrue(displayPreview.staticTexts["18%"].waitForExistence(timeout: 10))
        XCTAssertTrue(displayPreview.staticTexts["left"].exists)
        XCTAssertFalse(displayPreview.staticTexts["82%"].exists)
        selectSegment("Used", in: "settings-percent-display", app: app, towardStart: true)
        selectSegment("List", in: "settings-overview-layout", app: app, towardStart: false)
        let listPreview = app.descendants(matching: .any)["settings-preview-list"].firstMatch
        setSwitch("Show subscription tier", to: true, in: app)
        XCTAssertTrue(scrollTo(listPreview, in: app, towardStart: true))
        let tieredTitle = listPreview.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@", "Claude Pro")).firstMatch
        XCTAssertTrue(tieredTitle.waitForExistence(timeout: 10))
        setSwitch("Show subscription tier", to: false, in: app)
        XCTAssertTrue(scrollTo(listPreview, in: app, towardStart: true))
        XCTAssertTrue(listPreview.staticTexts["Claude"].exists)
        XCTAssertFalse(tieredTitle.exists)
        setSwitch("Show subscription tier", to: true, in: app)
        selectSegment("Dials", in: "settings-overview-layout", app: app, towardStart: true)
        let dialsPreview = app.descendants(matching: .any)["settings-preview-dials"].firstMatch
        XCTAssertTrue(scrollTo(dialsPreview, in: app, towardStart: true))
        let previewClaude = app.descendants(matching: .any)["settings-preview-dial-preview-claude"].firstMatch
        XCTAssertTrue(scrollTo(previewClaude, in: app))
        XCTAssertEqual(previewClaude.label, "Claude")
        XCTAssertTrue((previewClaude.value as? String)?.contains("82% used") == true)
        let previewCodex = app.descendants(matching: .any)["settings-preview-dial-preview-codex"].firstMatch
        XCTAssertTrue(scrollTo(previewCodex, in: app))
        XCTAssertEqual(previewCodex.label, "OpenAI Codex")
        XCTAssertTrue((previewCodex.value as? String)?.contains("56% used") == true)
        XCTAssertFalse(dialsPreview.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@", "Claude Pro")).firstMatch.exists)
        setSwitch("Show subscription tier", to: false, in: app)
        XCTAssertTrue(scrollTo(previewClaude, in: app, towardStart: true))
        XCTAssertEqual(previewClaude.label, "Claude")
        setSwitch("Show subscription tier", to: true, in: app)
    }

    func testDialsKeepAccountActionsAndReturnToUnfilteredList() {
        let app = launch(["-marketing-demo", "-overview-expired-demo", "-reduce-motion"])
        defer { app.terminate() }
        let codexRow = app.buttons["overview-account-demo-codex"]
        let claudeRow = app.buttons["overview-account-demo-claude"]
        XCTAssertTrue(codexRow.waitForExistence(timeout: 10))
        XCTAssertTrue(claudeRow.waitForExistence(timeout: 10))
        codexRow.tap()
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        let attention = app.buttons["overview-filter-needsAttention"]
        attention.tap()
        XCTAssertEqual(attention.value as? String, "Selected")
        let filtered = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: claudeRow)
        XCTAssertEqual(XCTWaiter.wait(for: [filtered], timeout: 10), .completed)

        tabButton("Settings", in: app).tap()
        selectSegment("Used", in: "settings-percent-display", app: app, towardStart: false)
        selectSegment("Dials", in: "settings-overview-layout", app: app, towardStart: false)
        tabButton("Overview", in: app).tap()
        XCTAssertFalse(app.buttons["overview-filter-all"].exists)
        XCTAssertFalse(app.buttons["overview-filter-needsAttention"].exists)
        XCTAssertFalse(app.buttons["overview-filter-resetsToday"].exists)
        let codex = app.buttons["overview-dial-demo-codex"]
        let claude = app.buttons["overview-dial-demo-claude"]
        XCTAssertTrue(scrollTo(codex, in: app))
        XCTAssertEqual(codex.label, "OpenAI Codex")
        XCTAssertTrue((codex.value as? String)?.contains("used") == true)
        XCTAssertTrue((codex.value as? String)?.contains("Reconnect") == true)
        XCTAssertTrue(scrollTo(claude, in: app))
        XCTAssertEqual(claude.label, "Claude")
        XCTAssertTrue((claude.value as? String)?.contains("82% used") == true)
        XCTAssertTrue(scrollTo(codex, in: app))
        codex.tap()
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-demo-codex"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Account actions"].exists)
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        let add = app.buttons["overview-add-account"]
        XCTAssertTrue(scrollTo(add, in: app, towardStart: false))
        add.tap()
        XCTAssertTrue(app.navigationBars["Add account"].waitForExistence(timeout: 10))
        let picker = app.scrollViews["provider-picker-scroll"]
        XCTAssertTrue(scrollTo(picker.staticTexts["OpenAI Codex"], in: app))
        app.buttons["Cancel"].tap()
        let caption = app.staticTexts["Each dial shows the account’s headline. Tap one for every window and its resets."]
        XCTAssertTrue(scrollTo(caption, in: app, towardStart: false))

        tabButton("Settings", in: app).tap()
        // Display is above the Layout picker reached before switching tabs.
        selectSegment("Left", in: "settings-percent-display", app: app, towardStart: true)
        tabButton("Overview", in: app).tap()
        XCTAssertTrue(scrollTo(claude, in: app))
        XCTAssertEqual(claude.label, "Claude")
        XCTAssertTrue((claude.value as? String)?.contains("18% remaining") == true)
        tabButton("Settings", in: app).tap()
        selectSegment("List", in: "settings-overview-layout", app: app, towardStart: false)
        tabButton("Overview", in: app).tap()
        let all = app.buttons["overview-filter-all"]
        XCTAssertTrue(scrollTo(all, in: app, towardStart: true), "List must expose its All filter after returning from Dials")
        XCTAssertEqual(all.value as? String, "Selected")
        XCTAssertTrue(scrollTo(codexRow, in: app))
        XCTAssertTrue(scrollTo(claudeRow, in: app))
    }

    func testOverviewLayoutPersistsWithoutDemoAccounts() {
        var app = launch()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        tabButton("Settings", in: app).tap()
        let layout = app.segmentedControls["settings-overview-layout"]
        XCTAssertTrue(scrollTo(layout, in: app, towardStart: false))
        XCTAssertTrue(layout.buttons["List"].isSelected)
        defer {
            selectSegment("List", in: "settings-overview-layout", app: app, towardStart: false)
            XCTAssertTrue(app.descendants(matching: .any)["settings-overview-layout-saved"].firstMatch.waitForExistence(timeout: 10))
            XCTAssertTrue(scrollTo(app.descendants(matching: .any)["settings-preview-list"].firstMatch, in: app, towardStart: true))
        }
        selectSegment("Dials", in: "settings-overview-layout", app: app)
        XCTAssertTrue(app.descendants(matching: .any)["settings-overview-layout-saved"].firstMatch.waitForExistence(timeout: 10))
        let preview = app.descendants(matching: .any)["settings-preview-dials"].firstMatch
        XCTAssertTrue(scrollTo(preview, in: app, towardStart: true))
        XCTAssertFalse(app.descendants(matching: .any)["settings-preview-list"].firstMatch.exists)
        app.terminate()

        app = launch()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        tabButton("Settings", in: app).tap()
        let restored = app.segmentedControls["settings-overview-layout"]
        XCTAssertTrue(scrollTo(restored, in: app, towardStart: false))
        XCTAssertTrue(restored.buttons["Dials"].isSelected)
        let restoredPreview = app.descendants(matching: .any)["settings-preview-dials"].firstMatch
        XCTAssertTrue(scrollTo(restoredPreview, in: app, towardStart: true))
        let claude = app.descendants(matching: .any)["settings-preview-dial-preview-claude"].firstMatch
        let codex = app.descendants(matching: .any)["settings-preview-dial-preview-codex"].firstMatch
        XCTAssertTrue(claude.waitForExistence(timeout: 10))
        XCTAssertTrue(codex.waitForExistence(timeout: 10))
        XCTAssertEqual(claude.label, "Claude")
        XCTAssertEqual(codex.label, "OpenAI Codex")
        XCTAssertFalse(app.buttons["overview-dial-demo-claude"].exists)
        XCTAssertFalse(app.buttons["overview-dial-demo-codex"].exists)
    }

    func testAccessibilityTextReflowsDialsIntoOneColumn() {
        let app = XCUIApplication()
        app.launchArguments += diagnosticLaunchArguments + [
            "-marketing-demo", "-overview-expired-demo",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        waitForOrientation(app, landscape: false)
        tabButton("Settings", in: app).tap()
        selectSegment("Dials", in: "settings-overview-layout", app: app, towardStart: false)
        tabButton("Overview", in: app).tap()
        defer {
            tabButton("Settings", in: app).tap()
            selectSegment("List", in: "settings-overview-layout", app: app)
        }

        let codex = app.buttons["overview-dial-demo-codex"]
        let claude = app.buttons["overview-dial-demo-claude"]
        XCTAssertTrue(scrollTo(codex, in: app))
        XCTAssertTrue(scrollTo(claude, in: app))
        let first = codex.frame
        let second = claude.frame
        XCTAssertGreaterThan(first.width, 0)
        XCTAssertGreaterThan(first.height, 0)
        XCTAssertGreaterThan(second.height, 0)
        XCTAssertEqual(first.minX, second.minX, accuracy: 1)
        XCTAssertEqual(first.width, second.width, accuracy: 1)
        XCTAssertGreaterThan(first.width, app.frame.width / 2)
        XCTAssertGreaterThanOrEqual(max(first.minY, second.minY), min(first.maxY, second.maxY),
                                   "large accessibility text should stack the real dial tiles, not place them side by side")
        XCTAssertTrue(scrollTo(app.buttons["overview-add-account"], in: app))
    }

    func testCreditsUsesBothLayoutsAndCollapsesOnReturn() {
        let app = launch(["-marketing-demo", "-overview-credits-demo", "-reduce-motion"])
        defer { app.terminate() }
        let row = app.buttons["overview-account-demo-claude"]
        let compact = app.buttons["overview-credits-compact"]
        let collapse = app.buttons["overview-credits-collapse"]
        let heading = app.staticTexts["overview-credits-heading"]
        let manage = app.buttons["overview-manage-credit-accounts"]
        XCTAssertTrue(scrollTo(heading, in: app, towardStart: false), "Normal must offer the full Credits block")
        XCTAssertLessThan(row.frame.minY, heading.frame.minY)
        XCTAssertTrue(scrollTo(manage, in: app, towardStart: false), "Normal must link to credit management")

        tabButton("Settings", in: app).tap()
        selectSegment("Compact", in: "settings-overview-credit-layout", app: app, towardStart: false)
        let preview = app.descendants(matching: .any)["settings-credits-compact"].firstMatch
        XCTAssertTrue(scrollTo(preview, in: app, towardStart: true), "the live preview must switch to Compact")
        let claude = app.staticTexts["Claude Pro"].firstMatch
        XCTAssertLessThan(claude.frame.minY, preview.frame.minY)
        selectMenuOption("Credit accounts", in: "settings-overview-show-first", app: app)
        XCTAssertTrue(scrollTo(preview, in: app, towardStart: true))
        XCTAssertLessThan(preview.frame.minY, claude.frame.minY)

        tabButton("Overview", in: app).tap()
        XCTAssertTrue(scrollTo(compact, in: app))
        XCTAssertLessThan(compact.frame.minY, row.frame.minY)
        XCTAssertEqual(compact.label, "Credits · 3 balances")
        XCTAssertTrue((compact.value as? String)?.contains("DeepSeek $3.18") == true)
        XCTAssertFalse(heading.exists)
        compact.tap()
        XCTAssertTrue(scrollTo(collapse, in: app))
        XCTAssertTrue(heading.exists)
        XCTAssertTrue(app.staticTexts["$3.18"].exists)
        XCTAssertTrue(scrollTo(manage, in: app, towardStart: false), "Expanded Credits must link to credit management")
        manage.tap()
        let management = app.navigationBars["Credits"]
        XCTAssertTrue(management.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["More"].firstMatch.isHittable, "Overview must open the editable credit cards")
        // The destination has no toolbar actions; the system owns the back button's label.
        management.buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        XCTAssertTrue(scrollTo(compact, in: app, towardStart: true), "Leaving to manage credits must reset expansion")
        compact.tap()
        XCTAssertTrue(scrollTo(collapse, in: app, towardStart: true))
        collapse.tap()
        XCTAssertTrue(scrollTo(compact, in: app, towardStart: true))
        compact.tap()
        XCTAssertTrue(scrollTo(collapse, in: app))
        tabButton("Resets", in: app).tap()
        tabButton("Overview", in: app).tap()
        XCTAssertTrue(scrollTo(compact, in: app))
        XCTAssertFalse(collapse.exists)

        tabButton("Settings", in: app).tap()
        selectSegment("Dials", in: "settings-overview-layout", app: app)
        tabButton("Overview", in: app).tap()
        XCTAssertTrue(scrollTo(compact, in: app))
        let dial = app.buttons["overview-dial-demo-claude"]
        XCTAssertTrue(dial.exists)
        XCTAssertLessThan(compact.frame.minY, dial.frame.minY)
    }

    func testHiddenCreditsStayManageableAndChoicesSurviveRelaunch() {
        let fixture = "-fixture-credits"
        var app = launch([fixture, "-fixture-credits-reset"])
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        selectSegment("Compact", in: "settings-overview-credit-layout", app: app, towardStart: false)
        selectMenuOption("Credit accounts", in: "settings-overview-show-first", app: app)
        setSwitch("settings-overview-show-credits", to: false, in: app, initially: true)
        XCTAssertFalse(app.segmentedControls["settings-overview-credit-layout"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["settings-overview-show-first"].firstMatch.exists)
        XCTAssertFalse(app.descendants(matching: .any)["settings-credits-compact"].firstMatch.exists)
        XCTAssertTrue(scrollTo(app.descendants(matching: .any)["settings-overview-credits-saved"].firstMatch,
                               in: app, towardStart: false))

        let manage = app.buttons["settings-manage-credit-accounts"]
        XCTAssertTrue(scrollTo(manage, in: app, towardStart: false))
        manage.tap()
        XCTAssertTrue(app.navigationBars["Credits"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["DeepSeek"].exists)
        app.buttons["More"].tap()
        app.buttons["Edit"].tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "DeepSeek".count) + "Tracked credits")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Tracked credits"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["$3.18"].exists)
        app.terminate()

        app = launch([fixture])
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["overview-credits-heading"].exists)
        XCTAssertFalse(app.buttons["overview-credits-compact"].exists)
        tabButton("Settings", in: app).tap()
        setSwitch("settings-overview-show-credits", to: true, in: app, initially: false)
        let layout = app.segmentedControls["settings-overview-credit-layout"]
        XCTAssertTrue(scrollTo(layout, in: app, towardStart: true))
        XCTAssertTrue(layout.buttons["Compact"].isSelected)
        tabButton("Overview", in: app).tap()
        let compact = app.buttons["overview-credits-compact"]
        XCTAssertTrue(scrollTo(compact, in: app))
        let usageEmpty = app.staticTexts["Nothing to monitor yet"]
        XCTAssertTrue(usageEmpty.exists)
        XCTAssertLessThan(compact.frame.minY, usageEmpty.frame.minY,
                          "Credit accounts must still be first after relaunch")
        XCTAssertFalse(app.staticTexts["No prepaid balances tracked."].exists)
    }

    func testCompactCreditsWithoutBalancesUsesTheNormalEmptyState() {
        let app = launch(["-marketing-demo", "-overview-empty-demo", "-reduce-motion"])
        defer { app.terminate() }
        tabButton("Settings", in: app).tap()
        selectSegment("Compact", in: "settings-overview-credit-layout", app: app, towardStart: false)
        tabButton("Overview", in: app).tap()
        XCTAssertTrue(scrollTo(app.staticTexts["No prepaid balances tracked."], in: app))
        XCTAssertTrue(app.staticTexts["overview-credits-heading"].exists)
        XCTAssertTrue(app.buttons["overview-credits-add"].exists)
        XCTAssertFalse(app.buttons["overview-credits-compact"].exists)
    }

    func testGermanCompactCreditsReflowsAtLargestTextSize() {
        let app = XCUIApplication()
        app.launchArguments += diagnosticLaunchArguments + [
            "-marketing-demo", "-overview-credits-demo", "-overview-compact-credits-demo", "-reduce-motion",
            "-AppleLanguages", "(de)", "-AppleLocale", "de_DE",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        defer { app.terminate() }
        let compact = app.buttons["overview-credits-compact"]
        XCTAssertTrue(scrollTo(compact, in: app))
        XCTAssertEqual(compact.label, "Guthaben · 3 Konten")
        XCTAssertTrue((compact.value as? String)?.contains("3,18") == true)
        XCTAssertGreaterThan(compact.frame.height, 88, "the German count and balance need separate lines")
        compact.tap()
        let collapse = app.buttons["overview-credits-collapse"]
        XCTAssertTrue(scrollTo(collapse, in: app))
        XCTAssertEqual(collapse.label, "Einklappen")
        collapse.tap()
        XCTAssertTrue(scrollTo(compact, in: app))
    }

    private func selectMenuOption(_ option: String, in identifier: String, app: XCUIApplication) {
        let control = app.descendants(matching: .any)[identifier].firstMatch
        let bottomTabs = bottomTabBarFrame(in: app)
        guard scrollTo(control, in: app, towardStart: false) else {
            reportControlFailure("Could not reach menu \(identifier)",
                                 control: control, in: app, tapFrame: .null, bottomTabs: bottomTabs)
            return
        }
        let menuFrame = control.frame
        let openedAt = tapUptime { control.tap() }
        let choice = app.buttons.matching(identifier: option).firstMatch
        guard choice.waitForExistence(timeout: 10) else {
            reportControlFailure("Missing menu option \(option) in \(identifier)",
                                 control: control, in: app, tapFrame: menuFrame, bottomTabs: bottomTabs,
                                 tapUptime: openedAt)
            return
        }
        let tapFrame = choice.frame
        let selectedAt = tapUptime { choice.tap() }
        let selection = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@", option),
            object: control
        )
        if XCTWaiter.wait(for: [selection], timeout: 10) != .completed {
            reportControlFailure("\(identifier) did not select \(option)",
                                 control: control, in: app, tapFrame: tapFrame, bottomTabs: bottomTabs,
                                 tapUptime: selectedAt)
        }
    }

    func testDisconnectClosesDetailAfterRemovingRealStoredAccount() {
        let app = launch(["-fixture-register"])
        let row = app.buttons["overview-account-codex:fixture"]
        XCTAssertTrue(scrollTo(row, in: app))
        row.tap()
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["account-title-codex:fixture"].waitForExistence(timeout: 10))
        app.buttons["Account actions"].tap()
        let remove = app.buttons["Disconnect account"]
        XCTAssertTrue(remove.waitForExistence(timeout: 10))
        remove.tap()
        let disconnect = app.buttons["Disconnect"]
        XCTAssertTrue(disconnect.waitForExistence(timeout: 10))
        disconnect.tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["overview-close-detail"].exists)
        XCTAssertFalse(row.exists)
    }

    func testUnreadableRegisterDetailDoesNotClaimAccountWasRemoved() throws {
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIDevice.system.open requires iOS 16.4")
        }
        let app = launch(["-fixture-register-unreadable"])
        defer { app.terminate() }
        let overviewError = app.staticTexts["overview-register-unreadable"]
        XCTAssertTrue(overviewError.waitForExistence(timeout: 10))
        XCTAssertEqual(overviewError.label, "Can't read accounts")
        XCTAssertTrue(app.staticTexts["The accounts stored on this device could not be read. Pull down to try again."].exists)
        XCTAssertFalse(app.staticTexts["No accounts yet"].exists)
        XCTAssertFalse(app.staticTexts["Nothing to monitor yet"].exists)
        XCUIDevice.shared.system.open(URL(string: "usagelimits://account/codex:fixture")!)
        let confirm = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if confirm.waitForExistence(timeout: 3) { confirm.tap() }
        XCTAssertTrue(app.navigationBars["Account details"].waitForExistence(timeout: 10))
        let unreadable = app.staticTexts["account-register-unreadable"]
        XCTAssertTrue(unreadable.waitForExistence(timeout: 10))
        XCTAssertEqual(unreadable.label, "Can't read accounts")
        XCTAssertTrue(app.staticTexts["The accounts stored on this device could not be read. Pull down to try again."].exists)
        XCTAssertFalse(app.staticTexts["This account is no longer on this device."].exists)
        app.buttons["overview-close-detail"].tap()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 10))
        let recover = app.buttons["overview-recover-register-fixture"]
        XCTAssertTrue(recover.waitForExistence(timeout: 10))
        recover.tap()
        XCTAssertTrue(app.staticTexts["No accounts yet"].waitForExistence(timeout: 10),
                      "a successful load must clear the register error without a refresh")
        XCTAssertFalse(overviewError.exists)
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

        XCTAssertTrue(scrollTo(app.switches["Show subscription tier"], in: app, towardStart: false))
        XCTAssertTrue(scrollTo(app.segmentedControls["settings-percent-display"], in: app, towardStart: true))
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
                scrollTo(app.staticTexts[provider], in: app, towardStart: false),
                "\(provider) should be offered")
        }
    }

    func testLandscapeAndLargeTextKeepTheProviderPickerReachable() {
        let app = XCUIApplication()
        app.launchArguments += diagnosticLaunchArguments + [
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"
        ]
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
