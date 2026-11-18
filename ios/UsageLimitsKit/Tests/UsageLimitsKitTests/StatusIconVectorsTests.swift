import XCTest
@testable import UsageLimitsKit

final class StatusIconVectorsTests: XCTestCase {
    private struct File: Decodable {
        let vectors: [Vector]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
            guard !vectors.isEmpty, Set(vectors.map(\.name)).count == vectors.count else {
                throw VectorFileError(description: "status icon vectors must be nonempty and uniquely named")
            }
        }
    }

    private struct Headline: Decodable {
        let token: String
        let exhausted: Bool
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["token", "side", "exhausted"])
            let c = try decoder.container(keyedBy: Keys.self)
            token = try c.decode(String.self, forKey: Keys("token"))
            switch try c.decode(String.self, forKey: Keys("side")) {
            case "left", "used": break
            case let other: throw VectorFileError(description: "unknown side \(other)")
            }
            exhausted = try c.decode(Bool.self, forKey: Keys("exhausted"))
            if !["—", "<1", ">99"].contains(token) {
                guard let n = Int(token), (0...100).contains(n), String(n) == token else {
                    throw VectorFileError(description: "invalid token \(token)")
                }
            }
        }
    }

    private struct Expected: Decodable {
        let value: StatusIconContent
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["content", "figure", "outdated"])
            let c = try decoder.container(keyedBy: Keys.self)
            let figure = try c.decodeIfPresent(Int.self, forKey: Keys("figure"))
            let content: StatusIconContent.Content
            switch try c.decode(String.self, forKey: Keys("content")) {
            case "error": content = .error
            case "exhausted": content = .exhausted
            case "unknown": content = .unknown
            case "none": content = .glyphOnly
            case "figure":
                guard let figure, (0...99).contains(figure) else {
                    throw VectorFileError(description: "figure must be in 0...99")
                }
                content = .figure(figure)
            case let other: throw VectorFileError(description: "unknown content \(other)")
            }
            if case .figure = content {} else if figure != nil {
                throw VectorFileError(description: "non-figure content must carry null")
            }
            value = StatusIconContent(content: content, outdated: try c.decode(Bool.self, forKey: Keys("outdated")))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let headline: Headline?
        let validity: ReadingValidity
        let needsReauthentication: Bool
        let expected: Expected
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "headline", "validity", "needsReauthentication", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            headline = try c.decodeIfPresent(Headline.self, forKey: Keys("headline"))
            switch try c.decode(String.self, forKey: Keys("validity")) {
            case "current": validity = .current
            case "stale": validity = .stale
            case "resetDue": validity = .resetDue
            case "error": validity = .error
            case let other: throw VectorFileError(description: "unknown validity \(other)")
            }
            needsReauthentication = try c.decode(Bool.self, forKey: Keys("needsReauthentication"))
            expected = try c.decode(Expected.self, forKey: Keys("expected"))
        }
    }

    func testMalformedVectorsFailRatherThanSilentlyDroppingCoverage() throws {
        let data = try Data(contentsOf: SharedVectors.file("status-icon.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])
        func rejected(_ cases: [[String: Any]]) throws {
            var changed = json
            changed["vectors"] = cases
            XCTAssertThrowsError(try SharedVectors.parse(File.self,
                from: JSONSerialization.data(withJSONObject: changed)))
        }
        try rejected([])
        try rejected([vectors[0], vectors[0]])
        var missing = vectors[0]
        missing.removeValue(forKey: "headline")
        try rejected([missing])
        var unknown = vectors[0]
        unknown["validity"] = "fresh-ish"
        try rejected([unknown])
        var nested = vectors[0]
        var headline = try XCTUnwrap(nested["headline"] as? [String: Any])
        headline["percentage"] = headline.removeValue(forKey: "token")
        nested["headline"] = headline
        try rejected([nested])
        var expected = try XCTUnwrap(vectors[0]["expected"] as? [String: Any])
        expected["figure"] = 100
        var invalid = vectors[0]
        invalid["expected"] = expected
        try rejected([invalid])
    }

    func testEverySharedVectorShowsTheExpectedContent() throws {
        let file = try SharedVectors.parse(File.self, from: Data(contentsOf: SharedVectors.file("status-icon.json")))
        for vector in file.vectors {
            XCTAssertEqual(StatusIconContent.of(token: vector.headline?.token, exhausted: vector.headline?.exhausted ?? false,
                validity: vector.validity, needsReauthentication: vector.needsReauthentication),
                vector.expected.value, vector.name)
        }
    }
}
