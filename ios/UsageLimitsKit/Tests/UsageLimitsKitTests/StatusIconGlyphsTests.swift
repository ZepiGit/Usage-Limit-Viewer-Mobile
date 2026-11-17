import XCTest
@testable import UsageLimitsKit

/// The glyph file Android draws from is the one the widget draws from; these read it as shipped.
final class StatusIconGlyphsTests: XCTestCase {
    private func shipped() throws -> StatusIconGlyphs {
        let url = SharedVectors.file("status-icon-glyphs.json", directory: "shared/glyphs")
        return try StatusIconGlyphs.parse(Data(contentsOf: url))
    }

    func testEveryProviderHasAGlyphAndUnknownProvidersGetTheDisc() throws {
        let glyphs = try shipped()
        for provider in ProviderID.allCases {
            XCTAssertFalse(glyphs.glyph(for: provider.rawValue).isEmpty, provider.rawValue)
            XCTAssertNotEqual(glyphs.glyph(for: provider.rawValue), glyphs.fallback, provider.rawValue)
        }
        XCTAssertEqual(glyphs.glyph(for: "mistral"), glyphs.fallback)
        XCTAssertEqual(glyphs.glyph(for: nil), glyphs.fallback)
        XCTAssertEqual(Set(ProviderID.allCases.map(\.rawValue)).count,
            Set(ProviderID.allCases.map { glyphs.glyph(for: $0.rawValue) }).count, "no two providers share a glyph")
    }

    func testEveryPointStaysOnTheTwentyFourUnitSquare() throws {
        let glyphs = try shipped()
        for provider in ProviderID.allCases.map(\.rawValue) + [nil] {
            for point in glyphs.glyph(for: provider).flatMap(\.points) {
                XCTAssertTrue((0...24).contains(point.x) && (0...24).contains(point.y), "\(provider ?? "fallback") \(point)")
            }
        }
    }

    func testPathDataReadsAbsoluteCommandsIntoPoints() throws {
        let file = #"{"grid": 24, "glyphs": {"xai": "M2 2H8.5L22 22V20C1 2 3 4 5 6Z M1 1L2 2"}, "fallback": "M0 0H1Z"}"#
        let path = try StatusIconGlyphs.parse(Data(file.utf8), providers: ["xai"]).glyph(for: "xai")
        XCTAssertEqual(path, [
            .move(.init(x: 2, y: 2)), .line(.init(x: 8.5, y: 2)), .line(.init(x: 22, y: 22)),
            .line(.init(x: 22, y: 20)),
            .curve(.init(x: 1, y: 2), .init(x: 3, y: 4), .init(x: 5, y: 6)), .close,
            .move(.init(x: 1, y: 1)), .line(.init(x: 2, y: 2)),
        ])
    }

    func testAFileThatDoesNotMatchTheProvidersIsRejected() {
        let missing = #"{"grid": 24, "glyphs": {}, "fallback": "M0 0H1Z"}"#
        let extra = #"{"grid": 24, "glyphs": {"xai": "M0 0H1Z", "other": "M0 0H1Z"}, "fallback": "M0 0H1Z"}"#
        let relative = #"{"grid": 24, "glyphs": {"xai": "m0 0h1z"}, "fallback": "M0 0H1Z"}"#
        let grid = #"{"grid": 48, "glyphs": {"xai": "M0 0H1Z"}, "fallback": "M0 0H1Z"}"#
        let unknownKey = #"{"grid": 24, "glyphs": {"xai": "M0 0H1Z"}, "fallback": "M0 0H1Z", "x": 1}"#
        for file in [missing, extra, relative, grid, unknownKey] {
            XCTAssertThrowsError(try StatusIconGlyphs.parse(Data(file.utf8), providers: ["xai"]), file)
        }
    }
}
