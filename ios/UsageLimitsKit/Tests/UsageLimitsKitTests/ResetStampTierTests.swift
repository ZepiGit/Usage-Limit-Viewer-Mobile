import XCTest
@testable import UsageLimitsKit

final class ResetStampTierTests: XCTestCase {
    func testSharedLocalDateTiersUseNativeEnglishAndGermanFormats() throws {
        let file = try SharedVectors.parse(VectorFile.self,
            from: Data(contentsOf: SharedVectors.file("reset-stamp-tier.json")))
        XCTAssertFalse(file.vectors.isEmpty)
        let iso = ISO8601DateFormatter()
        for vector in file.vectors {
            let now = try XCTUnwrap(iso.date(from: vector.now))
            let instant = try XCTUnwrap(iso.date(from: vector.instant))
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = try XCTUnwrap(TimeZone(identifier: vector.zone))
            for language in ["en_US", "de_DE"] {
                let locale = Locale(identifier: language)
                let expected = DateFormatter()
                expected.calendar = calendar
                expected.timeZone = calendar.timeZone
                expected.locale = locale
                switch vector.tier {
                case "time": expected.timeStyle = .short
                case "weekday": expected.setLocalizedDateFormatFromTemplate("EEEjm")
                case "date":
                    expected.dateStyle = .short
                    expected.timeStyle = .short
                default: XCTFail("unknown tier: \(vector.tier)"); continue
                }
                XCTAssertEqual(Countdown.absolute(instant, now: now, calendar: calendar, locale: locale),
                    expected.string(from: instant), "\(vector.name), \(language)")
            }
        }
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }
    private struct Vector: Decodable {
        let name: String
        let now: String
        let instant: String
        let zone: String
        let tier: String
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "now", "instant", "zone", "tier"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            now = try c.decode(String.self, forKey: Keys("now"))
            instant = try c.decode(String.self, forKey: Keys("instant"))
            zone = try c.decode(String.self, forKey: Keys("zone"))
            tier = try c.decode(String.self, forKey: Keys("tier"))
        }
    }
}
