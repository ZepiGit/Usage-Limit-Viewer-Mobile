import XCTest
@testable import UsageLimitsKit

final class AppearanceSettingsTests: XCTestCase {
    func testOldSettingsDefaultOnlyTheNewAppearanceKeys() throws {
        let settings = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"percentDisplay":"used","overviewLayout":"dials","syncIntervalMinutes":180,"showRenewalTime":true,"providerIcons":{"codex":"openai-text"},"pinnedAccountKeys":["b","a"]}"#.utf8))
        XCTAssertEqual(settings.appearanceTheme, .pureBlack)
        XCTAssertEqual(settings.appearanceTextFont, .system)
        XCTAssertEqual(settings.appearanceFigureFont, .robotoMono)
        XCTAssertEqual(settings.percentDisplay, .used)
        XCTAssertEqual(settings.overviewLayout, .dials)
        XCTAssertEqual(settings.syncIntervalMinutes, 180)
        XCTAssertTrue(settings.showRenewalTime)
        XCTAssertEqual(settings.providerIcons, ["codex": "openai-text"])
        XCTAssertEqual(settings.pinnedAccountKeys, ["b", "a"])
        XCTAssertEqual(AppSettings().appearanceTheme, .pureBlack)
    }

    func testEveryAppearanceCombinationSurvivesNormalisationAndReopening() async throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for theme in AppearanceTheme.allCases {
            for text in AppearanceTextFont.allCases {
                for figure in AppearanceFigureFont.allCases {
                    let settings = AppSettings(syncIntervalMinutes: 1, appearanceTheme: theme,
                        appearanceTextFont: text, appearanceFigureFont: figure, percentDisplay: .used,
                        statusIconsEnabled: true, pinnedAccountKeys: ["b", "a"])
                    let stored = try await SettingsStore(directory: directory).save(settings)
                    let reopened = await SettingsStore(directory: directory).settings()
                    XCTAssertEqual(reopened, stored)
                    XCTAssertEqual(reopened.appearanceTheme, theme)
                    XCTAssertEqual(reopened.appearanceTextFont, text)
                    XCTAssertEqual(reopened.appearanceFigureFont, figure)
                    XCTAssertEqual(reopened.percentDisplay, .used)
                    XCTAssertTrue(reopened.statusIconsEnabled)
                    XCTAssertEqual(reopened.pinnedAccountKeys, ["b", "a"])
                    let data = try JSONEncoder().encode(reopened)
                    let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                    XCTAssertEqual(json["appearanceTheme"] as? String, theme.rawValue)
                    XCTAssertEqual(json["appearanceTextFont"] as? String, text.rawValue)
                    XCTAssertEqual(json["appearanceFigureFont"] as? String, figure.rawValue)
                }
            }
        }
    }

    func testMissingUnrecognizedNullAndWrongTypedKeysFallBackIndependently() throws {
        let keys = ["appearanceTheme", "appearanceTextFont", "appearanceFigureFont"]
        let invalidValues: [Any?] = [nil, "unknown", NSNull(), 23, ["unexpected"]]
        for key in keys {
            for invalid in invalidValues {
                var json: [String: Any] = ["appearanceTheme": "lightPaper", "appearanceTextFont": "figtree",
                    "appearanceFigureFont": "ibmPlexMono", "percentDisplay": "used"]
                json[key] = invalid
                let settings = try JSONDecoder().decode(AppSettings.self, from: JSONSerialization.data(withJSONObject: json))
                XCTAssertEqual(settings.appearanceTheme, key == "appearanceTheme" ? .pureBlack : .lightPaper)
                XCTAssertEqual(settings.appearanceTextFont, key == "appearanceTextFont" ? .system : .figtree)
                XCTAssertEqual(settings.appearanceFigureFont, key == "appearanceFigureFont" ? .robotoMono : .ibmPlexMono)
                XCTAssertEqual(settings.percentDisplay, .used)
            }
        }
    }
}
