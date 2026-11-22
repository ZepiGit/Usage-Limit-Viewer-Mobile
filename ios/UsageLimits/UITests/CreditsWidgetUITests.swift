import Vision
import XCTest

final class CreditsWidgetUITests: XCTestCase {
    private enum Language: String {
        case en, de

        var locale: String { self == .de ? "de_DE" : "en_US" }
        var unknown: String { self == .de ? "Unbekannt" : "Unknown" }
        var amount: String { self == .de ? "3,18" : "3.18" }

        var malformedStatus: String {
            let detail = "The prepaid balance, its currency, and the account's billing details were missing from the provider response. The remaining fields did not identify a paid balance or provide a timestamp that could be used to verify when the remaining amount was reported."
            return self == .de
                ? "Der Anbieter hat in einem Format geantwortet, das diese App nicht versteht (\(detail))."
                : "The provider answered in a shape this app does not understand (\(detail))."
        }
    }

    private struct CompactStatuses: Decodable {
        enum State: String, Decodable, CaseIterable { case unread, unreadable, refreshFailed }

        struct Vector: Decodable {
            let state: State
            let en: String
            let de: String
        }

        let vectors: [Vector]
    }

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    func testSmallCreditsExposeFullEnglishStatusesAtAX5() throws {
        try assertSmallCredits(in: .en)
    }

    func testSmallCreditsExposeFullGermanStatusesAtAX5() throws {
        try assertSmallCredits(in: .de)
    }

    private func assertSmallCredits(in language: Language) throws {
        let url = try XCTUnwrap(Bundle(for: Self.self)
            .url(forResource: "credit-widget-compact-status", withExtension: "json"))
        let vectors = try JSONDecoder().decode(CompactStatuses.self, from: Data(contentsOf: url)).vectors
        XCTAssertEqual(vectors.count, CompactStatuses.State.allCases.count)
        XCTAssertEqual(Set(vectors.map(\.state)), Set(CompactStatuses.State.allCases))

        let app = XCUIApplication()
        app.launchArguments += [
            "-ui-testing", "-fixture-credits-widget",
            "-AppleLanguages", "(\(language.rawValue))", "-AppleLocale", language.locale,
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"
        ]
        app.launch()
        defer { app.terminate() }

        let status = app.descendants(matching: .any)["credit-widget-status"]
        let title = app.staticTexts["DeepSeek"]
        for state in CompactStatuses.State.allCases {
            let vector = try XCTUnwrap(vectors.first { $0.state == state })
            if state != .unread {
                let button = app.buttons["credits-fixture-\(state.rawValue)"]
                XCTAssertTrue(button.waitForExistence(timeout: 10), "Missing fixture control for \(state)")
                button.tap()
            }

            let original = state == .unread ? language.unknown : language.malformedStatus
            let amount = state == .refreshFailed ? language.amount : "—"
            let balance = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", amount)).firstMatch
            let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                status.exists && status.label == original && title.exists && balance.exists
            }, object: nil)
            let result = XCTWaiter.wait(for: [settled], timeout: 10)

            let screenshot = app.screenshot()
            let attachment = XCTAttachment(screenshot: screenshot)
            attachment.name = "Credits-\(language.rawValue)-\(state.rawValue)-AX5"
            attachment.lifetime = .keepAlways
            add(attachment)

            XCTAssertEqual(result, .completed, "\(state): title, balance and full status must be accessible; status: \(status.exists ? status.label : "not exposed")")
            let visibleStatus = state == .unread ? language.unknown : (language == .de ? vector.de : vector.en)
            let visibleText = try recognizedText(in: screenshot)
            XCTAssertTrue(visibleText.localizedCaseInsensitiveContains(visibleStatus),
                          "\(state): expected visible \(visibleStatus); recognized \(visibleText)")
        }
    }

    private func recognizedText(in screenshot: XCUIScreenshot) throws -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        // The fixed-size tile occupies a small part of the full-screen capture, even at AX5.
        request.minimumTextHeight = 0
        request.recognitionLanguages = ["en-US", "de-DE"]
        request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: XCTUnwrap(screenshot.image.cgImage)).perform([request])
        return (request.results ?? []).sorted { $0.boundingBox.midY > $1.boundingBox.midY }
            .compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
    }
}
