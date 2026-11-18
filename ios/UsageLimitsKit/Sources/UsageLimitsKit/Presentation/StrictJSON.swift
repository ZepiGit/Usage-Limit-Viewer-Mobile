import Foundation

/// Checks that bytes are strict JSON (RFC 8259) before `JSONSerialization` sees them, the same
/// check Android's `StrictJson` makes, so the shared glyph file is accepted or rejected alike on
/// both platforms. Rejects what a reader may let through: trailing commas, a byte-order mark,
/// duplicate keys in one object, number spellings outside the JSON grammar, comments, single
/// quotes, unquoted words and anything after the root value.
enum StrictJSON {
    struct Invalid: Error, CustomStringConvertible {
        let description: String
    }

    static func validate(_ data: Data) throws {
        // Checked on the bytes: a String decoder may drop a leading byte-order mark silently.
        guard !data.starts(with: [0xEF, 0xBB, 0xBF]) else { throw Invalid(description: "byte-order mark") }
        guard let text = String(data: data, encoding: .utf8) else { throw Invalid(description: "not UTF-8") }
        var reader = Reader(Array(text.unicodeScalars))
        reader.skipWhitespace()
        try reader.value()
        reader.skipWhitespace()
        guard reader.atEnd else { throw Invalid(description: "content after the root value at \(reader.index)") }
    }

    private struct Reader {
        let text: [Unicode.Scalar]
        var index = 0

        init(_ text: [Unicode.Scalar]) { self.text = text }

        var atEnd: Bool { index == text.count }

        private func peek() -> Unicode.Scalar? { index < text.count ? text[index] : nil }

        mutating func skipWhitespace() {
            while let c = peek(), c == " " || c == "\t" || c == "\n" || c == "\r" { index += 1 }
        }

        mutating func value() throws {
            guard let c = peek() else { throw Invalid(description: "a value is missing at \(index)") }
            switch c {
            case "{": try object()
            case "[": try array()
            case "\"": _ = try string()
            case "t": try literal("true")
            case "f": try literal("false")
            case "n": try literal("null")
            default: try number()
            }
        }

        private mutating func object() throws {
            index += 1
            var keys = Set<String>()
            skipWhitespace()
            if peek() == "}" { index += 1; return }
            while true {
                skipWhitespace()
                guard peek() == "\"" else { throw Invalid(description: "an object key must be a string at \(index)") }
                let key = try string()
                guard keys.insert(key).inserted else { throw Invalid(description: "duplicate key \"\(key)\"") }
                skipWhitespace()
                try expect(":")
                skipWhitespace()
                try value()
                skipWhitespace()
                switch peek() {
                case ",": index += 1
                case "}": index += 1; return
                default: throw Invalid(description: "expected ',' or '}' at \(index)")
                }
            }
        }

        private mutating func array() throws {
            index += 1
            skipWhitespace()
            if peek() == "]" { index += 1; return }
            while true {
                skipWhitespace()
                try value()
                skipWhitespace()
                switch peek() {
                case ",": index += 1
                case "]": index += 1; return
                default: throw Invalid(description: "expected ',' or ']' at \(index)")
                }
            }
        }

        /// Reads a string and returns its decoded content, so escaped keys compare as written.
        private mutating func string() throws -> String {
            try expect("\"")
            var out = String.UnicodeScalarView()
            while true {
                guard let c = peek() else { throw Invalid(description: "unterminated string") }
                index += 1
                if c == "\"" { return String(out) }
                if c.value < 0x20 { throw Invalid(description: "control character in a string at \(index - 1)") }
                guard c == "\\" else { out.append(c); continue }
                guard let e = peek() else { throw Invalid(description: "unterminated escape") }
                index += 1
                switch e {
                case "\"", "\\", "/": out.append(e)
                case "b": out.append("\u{8}")
                case "f": out.append("\u{C}")
                case "n": out.append("\n")
                case "r": out.append("\r")
                case "t": out.append("\t")
                case "u":
                    guard index + 4 <= text.count,
                          let code = UInt32(String(String.UnicodeScalarView(text[index..<index + 4])), radix: 16),
                          text[index..<index + 4].allSatisfy(\.properties.isASCIIHexDigit) else {
                        throw Invalid(description: "bad \\u escape")
                    }
                    // A lone surrogate half has no scalar; it still counts as one key character.
                    out.append(Unicode.Scalar(code) ?? "\u{FFFD}")
                    index += 4
                default: throw Invalid(description: "bad escape \\\(e)")
                }
            }
        }

        private mutating func number() throws {
            let start = index
            func digits() -> Int {
                let from = index
                while let c = peek(), ("0"..."9").contains(c) { index += 1 }
                return index - from
            }
            if peek() == "-" { index += 1 }
            if peek() == "0" { index += 1 } else if digits() == 0 { throw Invalid(description: "not a JSON value at \(start)") }
            if peek() == "." {
                index += 1
                guard digits() > 0 else { throw Invalid(description: "malformed number at \(start)") }
            }
            if peek() == "e" || peek() == "E" {
                index += 1
                if peek() == "+" || peek() == "-" { index += 1 }
                guard digits() > 0 else { throw Invalid(description: "malformed number at \(start)") }
            }
            // "24f", "024" and "24." leave a letter, digit or point right after the number.
            if let c = peek(), ("0"..."9").contains(c) || c == "." || c == "+" || c == "-" || c.properties.isAlphabetic {
                throw Invalid(description: "malformed number at \(start)")
            }
        }

        private mutating func literal(_ word: String) throws {
            for scalar in word.unicodeScalars {
                guard peek() == scalar else { throw Invalid(description: "not a JSON value at \(index)") }
                index += 1
            }
            if let c = peek(), c.properties.isAlphabetic || ("0"..."9").contains(c) {
                throw Invalid(description: "not a JSON value at \(index)")
            }
        }

        private mutating func expect(_ c: Unicode.Scalar) throws {
            guard peek() == c else { throw Invalid(description: "expected '\(c)' at \(index)") }
            index += 1
        }
    }
}
