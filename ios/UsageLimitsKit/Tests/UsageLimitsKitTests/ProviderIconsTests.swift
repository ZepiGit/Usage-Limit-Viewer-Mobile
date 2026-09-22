import XCTest
@testable import UsageLimitsKit

final class ProviderIconsTests: XCTestCase {
    func testDefaultsAndFamilies() {
        XCTAssertEqual(ProviderID.allCases.count, 7)
        XCTAssertEqual(ProviderIconCatalog.selected(for: .claude, id: nil).id, "claudecode-color")
        XCTAssertEqual(ProviderIconCatalog.selected(for: .antigravity, id: nil).id, "gemini-color")
        XCTAssertEqual(ProviderIconCatalog.choices(for: .claude).count, 6)
        XCTAssertEqual(ProviderIconCatalog.choices(for: .antigravity).count, 6)
        XCTAssertEqual(ProviderID.allCases.reduce(0) { $0 + ProviderIconCatalog.choices(for: $1).count }, 23)
        XCTAssertEqual(ProviderIconCatalog.choices(for: .devin).map(\.id), ["devin-color", "devin"])
        XCTAssertEqual(ProviderIconCatalog.choices(for: .meta).map(\.id), ["meta-color", "meta"])
        XCTAssertEqual(ProviderIconCatalog.selected(for: .devin, id: nil).id, "devin-color")
        XCTAssertEqual(ProviderIconCatalog.selected(for: .meta, id: nil).id, "meta-color")
    }

    func testChoicesSurviveSettingsEncodingAndLegacyFiles() throws {
        var settings = AppSettings()
        settings.providerIcons = ["claude": "claude-color", "antigravity": "antigravity-color"]
        let restored = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(restored.providerIcons, settings.providerIcons)
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8)).providerIcons, [:])
        XCTAssertEqual(ProviderIconCatalog.selected(for: .claude, id: "removed").id, "claudecode-color")
    }
}
