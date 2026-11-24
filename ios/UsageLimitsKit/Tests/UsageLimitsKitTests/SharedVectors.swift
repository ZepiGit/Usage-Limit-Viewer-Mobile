import Foundation

enum SharedVectors {
    /// Test sources sit five directories below the repository root, on Linux and macOS.
    static func file(_ name: String, directory: String = "shared/test-vectors") -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // UsageLimitsKitTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // UsageLimitsKit
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // repository root
            .appendingPathComponent("\(directory)/\(name)")
    }

    static func parse<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let decoder = JSONDecoder()
        // Valid JSON encodes the non-finite presentation input as the string "NaN".
        decoder.nonConformingFloatDecodingStrategy = .convertFromString(
            positiveInfinity: "Infinity", negativeInfinity: "-Infinity", nan: "NaN")
        return try decoder.decode(type, from: data)
    }
}

struct Keys: CodingKey {
    let stringValue: String
    var intValue: Int? { nil }
    init(_ stringValue: String) { self.stringValue = stringValue }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

struct VectorFileError: Error, CustomStringConvertible {
    let description: String
}

/// Present-with-null counts as present: a key must be written out even when its value is null.
func requireExactKeys(_ decoder: Decoder, _ expected: Set<String>) throws {
    let found = Set(try decoder.container(keyedBy: Keys.self).allKeys.map(\.stringValue))
    guard found == expected else {
        throw VectorFileError(description:
            "keys \(found.sorted()) at \(decoder.codingPath.map(\.stringValue)) do not match \(expected.sorted())")
    }
}
