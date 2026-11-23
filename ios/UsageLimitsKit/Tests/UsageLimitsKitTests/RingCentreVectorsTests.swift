import XCTest
@testable import UsageLimitsKit

/// What a ring writes in its centre (#38), proven against the vector file
/// `RingCentreVectorsTest.kt` reads too. The loader is strict for the reason
/// `HeadlineVectorsTests` gives: every object must carry exactly the keys it declares.
final class RingCentreVectorsTests: XCTestCase {

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
        let token: String
        let side: PercentDisplay
        let arcDrawn: Bool
        let expected: RingCentre

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "token", "side", "arcDrawn", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            token = try c.decode(String.self, forKey: Keys("token"))
            switch try c.decode(String.self, forKey: Keys("side")) {
            case "left": side = .left
            case "used": side = .used
            case let other: throw VectorFileError(description: "unknown side \(other)")
            }
            arcDrawn = try c.decode(Bool.self, forKey: Keys("arcDrawn"))
            switch try c.decode(String.self, forKey: Keys("expected")) {
            case "signed": expected = .signed
            case "bare": expected = .bare
            case "nothing": expected = .nothing
            case let other: throw VectorFileError(description: "unknown expectation \(other)")
            }
        }
    }

    private static func parse(_ data: Data) throws -> [Vector] {
        let vectors = try JSONDecoder().decode(VectorFile.self, from: data).vectors
        guard !vectors.isEmpty else { throw VectorFileError(description: "the ring centre vector file holds no vectors") }
        return vectors
    }

    private static let file = SharedVectors.file("ring-centre.json")

    func testEverySharedVectorWritesTheExpectedCentre() throws {
        for vector in try Self.parse(Data(contentsOf: Self.file)) {
            XCTAssertEqual(RingCentre.of(token: vector.token, side: vector.side, arcDrawn: vector.arcDrawn),
                           vector.expected, vector.name)
        }
    }

    /// A floor, not a count to keep in step: a file truncated by a bad merge cannot pass.
    func testTheVectorFileCoversEveryCaseTheRuleNames() throws {
        XCTAssertGreaterThanOrEqual(try Self.parse(Data(contentsOf: Self.file)).count, 13)
    }

    func testARenamedKeyFailsTheSuiteInsteadOfSkippingTheVector() {
        let renamed = """
            {"description": "", "vectors": [{"name": "x", "token": "100", "side": "left",
              "arc": true, "expected": "nothing"}]}
            """
        XCTAssertThrowsError(try Self.parse(Data(renamed.utf8)))
    }

    func testAMissingKeyFailsTheSuite() {
        let missing = """
            {"description": "", "vectors": [{"name": "x", "token": "100", "side": "left", "expected": "nothing"}]}
            """
        XCTAssertThrowsError(try Self.parse(Data(missing.utf8)))
    }

    func testAnEmptyVectorListFailsTheSuite() {
        XCTAssertThrowsError(try Self.parse(Data(#"{"description": "", "vectors": []}"#.utf8)))
    }
}
