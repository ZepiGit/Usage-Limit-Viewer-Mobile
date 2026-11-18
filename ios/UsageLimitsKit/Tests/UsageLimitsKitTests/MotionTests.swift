import Foundation
import UsageLimitsKit
import XCTest

final class MotionTests: XCTestCase {
    private struct VectorFile: Decodable {
        let motions: [Vector]

        init(from decoder: any Decoder) throws {
            try requireExactKeys(decoder, ["motions"])
            let container = try decoder.container(keyedBy: Keys.self)
            motions = try container.decode([Vector].self, forKey: Keys("motions"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let durationMs: Int
        let delayMs: Int
        let platforms: [String]

        init(from decoder: any Decoder) throws {
            try requireExactKeys(decoder, ["name", "durationMs", "delayMs", "platforms"])
            let container = try decoder.container(keyedBy: Keys.self)
            name = try container.decode(String.self, forKey: Keys("name"))
            durationMs = try container.decode(Int.self, forKey: Keys("durationMs"))
            delayMs = try container.decode(Int.self, forKey: Keys("delayMs"))
            platforms = try container.decode([String].self, forKey: Keys("platforms"))
        }
    }

    private func iosVectors() throws -> [Vector] {
        let data = try Data(contentsOf: SharedVectors.file("motion.json"))
        return try SharedVectors.parse(VectorFile.self, from: data).motions
            .filter { $0.platforms.contains("ios") }
    }

    /// Seconds as whole milliseconds: 0.24 - 0.09 is not exactly 0.15 in binary floating point.
    private func milliseconds(_ seconds: TimeInterval) -> Int {
        Int((seconds * 1000).rounded())
    }

    func testVocabularyIsExactlyTheSharedIosMotions() throws {
        let names = try iosVectors().map(\.name)
        XCTAssertFalse(names.isEmpty)
        XCTAssertEqual(Set(names).count, names.count, "a motion is listed twice")
        XCTAssertEqual(Set(Motion.allCases.map(\.rawValue)), Set(names))
    }

    func testTimingsMatchSharedVectors() throws {
        for vector in try iosVectors() {
            let motion = try XCTUnwrap(Motion(rawValue: vector.name), vector.name)
            let timing = try XCTUnwrap(motion.timing(reduceMotion: false), vector.name)
            XCTAssertEqual(milliseconds(timing.duration), vector.durationMs, vector.name)
            XCTAssertEqual(milliseconds(timing.delay), vector.delayMs, vector.name)
        }
    }

    func testReduceMotionAppliesEveryChangeAtOnce() {
        for motion in Motion.allCases {
            XCTAssertNil(motion.timing(reduceMotion: true), motion.rawValue)
        }
    }
}
