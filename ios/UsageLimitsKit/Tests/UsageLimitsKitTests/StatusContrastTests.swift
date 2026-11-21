import XCTest
@testable import UsageLimitsKit

final class StatusContrastTests: XCTestCase {
    func testAllEffectiveTextAndMarksClearTheirFloorsOnEverySurface() {
        let severities: [Severity] = [.healthy, .medium, .low, .limited, .exhausted, .error, .stale, .unknown]
        for theme in AppearanceTheme.allCases {
            let palette = theme.palette
            for (name, background) in [("ground", palette.ground), ("surface", palette.surface), ("raised", palette.raised)] {
                for (role, text) in [("ink", palette.ink), ("muted", palette.muted), ("quiet", palette.quietText),
                                     ("warning", palette.warning), ("negative", palette.negativeText), ("data text", palette.dataText)] {
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(text, background), 4.5,
                        "\(theme) \(role) on \(name)")
                }
                for severity in severities {
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(palette.accent(severity), background), 3,
                        "\(theme) \(severity) mark on \(name)")
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(palette.figure(severity), background), 4.5,
                        "\(theme) \(severity) text on \(name)")
                }
                XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(palette.data, background), 3)
            }
            XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(palette.data, palette.rule), 3)
            XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(palette.ground, palette.ink), 4.5)
        }
    }

    func testPrescribedPalettesAndDerivedPaperMarks() {
        XCTAssertEqual(AppearanceTheme.allCases.map { $0.palette.ground }, [0x0F0F0E, 0x000000, 0x0E1013, 0xF3F0E8])
        let paper = AppearanceTheme.lightPaper.palette
        XCTAssertEqual(paper.accent(.low), 0x8A5A0A)
        XCTAssertEqual(paper.accent(.exhausted), 0xB03A31)
        XCTAssertEqual(paper.accent(.healthy), 0x437345)
        XCTAssertEqual(paper.data, 0xB8654A)
        XCTAssertEqual(paper.dataText, paper.ink)
        XCTAssertEqual(AppearanceTheme.warmDark.palette.quietText, AppearanceTheme.warmDark.palette.muted)
        XCTAssertTrue(AppearanceTheme.lightPaper.isLight)
        XCTAssertFalse(AppearanceTheme.pureBlack.isLight)
    }
}
