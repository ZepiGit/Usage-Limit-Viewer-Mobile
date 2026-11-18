import Foundation

/// The provider silhouettes of the status icons, read from `shared/glyphs/status-icon-glyphs.json`.
///
/// Android's renderer draws from the same file, so both platforms show the same shapes; this type
/// only turns the path data into points on the 24-unit square, and each platform draws them.
/// Holes (Claude's eyes, the Codex nut) are subpaths wound the other way, so the shapes are
/// filled with the non-zero rule, which is Android's default and SwiftUI's.
public struct StatusIconGlyphs: Sendable, Equatable {
    public struct Point: Sendable, Hashable {
        public let x: Double
        public let y: Double
        public init(x: Double, y: Double) { self.x = x; self.y = y }
    }

    public enum Element: Sendable, Hashable {
        case move(Point)
        case line(Point)
        case curve(Point, Point, Point)
        case close

        public var points: [Point] {
            switch self {
            case .move(let p), .line(let p): return [p]
            case .curve(let a, let b, let c): return [a, b, c]
            case .close: return []
            }
        }
    }

    public static let grid = 24.0

    private let glyphs: [String: [Element]]
    public let fallback: [Element]

    /// The silhouette for a provider id; an id this build has no glyph for gets the plain disc.
    public func glyph(for providerID: String?) -> [Element] {
        providerID.flatMap { glyphs[$0] } ?? fallback
    }

    public struct FileError: Error, CustomStringConvertible {
        public let description: String
    }

    /// Strict, and the same as Android's reader: strict JSON, an object with exactly `grid` (the
    /// number 24), `glyphs` (naming exactly `providers`) and `fallback`, every path a string in the
    /// shared grammar. `shared/test-vectors/glyph-path-grammar.json` and `glyph-file-raw.json` hold
    /// the files both must accept or reject.
    public static func parse(_ data: Data,
        providers: [String] = ProviderID.allCases.map(\.rawValue)) throws -> StatusIconGlyphs {
        // Strict JSON first: JSONSerialization lets a trailing comma, a byte-order mark and
        // duplicate keys through, which Android's reader rejects or reads differently.
        try StrictJSON.validate(data)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(root.keys) == ["grid", "glyphs", "fallback"],
              (root["grid"] as? NSNumber)?.doubleValue == grid,
              let named = root["glyphs"] as? [String: String],
              let fallback = root["fallback"] as? String else {
            throw FileError(description: "expected {grid: 24, glyphs: {provider: path}, fallback: path}")
        }
        guard Set(named.keys) == Set(providers) else {
            throw FileError(description: "glyphs \(named.keys.sorted()) do not match providers \(providers.sorted())")
        }
        return StatusIconGlyphs(glyphs: try named.mapValues(path), fallback: try path(fallback))
    }

    /// SVG path data with the absolute commands M, L, H, V, C and Z, the ones the glyphs use.
    /// A command repeats while numbers follow it; numbers after M continue as L, as in SVG.
    static func path(_ data: String) throws -> [Element] {
        var scanner = PathScanner(data)
        var elements: [Element] = []
        var current = Point(x: 0, y: 0)
        var start = current
        var command: Character?
        while !scanner.atEnd {
            if let letter = scanner.letter() {
                command = letter
            } else if command == "M" {
                command = "L"
            } else if command == nil || command == "Z" {
                throw FileError(description: "path data must start each run with a command: \(data)")
            }
            switch command {
            case "M":
                current = try scanner.point()
                start = current
                elements.append(.move(current))
            case "L":
                current = try scanner.point()
                elements.append(.line(current))
            case "H":
                current = Point(x: try scanner.number(), y: current.y)
                elements.append(.line(current))
            case "V":
                current = Point(x: current.x, y: try scanner.number())
                elements.append(.line(current))
            case "C":
                let a = try scanner.point(), b = try scanner.point(), c = try scanner.point()
                elements.append(.curve(a, b, c))
                current = c
            case "Z":
                elements.append(.close)
                current = start
            default:
                throw FileError(description: "unsupported path command \(command.map(String.init) ?? "") in \(data)")
            }
        }
        guard case .move = elements.first else { throw FileError(description: "empty path data") }
        return elements
    }
}

private struct PathScanner {
    private let characters: [Character]
    private var index = 0

    init(_ text: String) { characters = Array(text) }

    var atEnd: Bool {
        mutating get { skipSeparators(); return index == characters.count }
    }

    /// Consumes and returns the command letter at the cursor, if there is one.
    mutating func letter() -> Character? {
        skipSeparators()
        guard index < characters.count, characters[index].isLetter else { return nil }
        defer { index += 1 }
        return characters[index]
    }

    mutating func number() throws -> Double {
        skipSeparators()
        let begin = index
        if index < characters.count, characters[index] == "-" { index += 1 }
        while index < characters.count, characters[index].isNumber || characters[index] == "." { index += 1 }
        guard let value = Double(String(characters[begin..<index])) else {
            throw StatusIconGlyphs.FileError(description: "expected a number at \(begin)")
        }
        return value
    }

    mutating func point() throws -> StatusIconGlyphs.Point {
        StatusIconGlyphs.Point(x: try number(), y: try number())
    }

    private mutating func skipSeparators() {
        while index < characters.count, characters[index] == " " || characters[index] == "," { index += 1 }
    }
}
