import Foundation

/// Kimi and Z.ai share this byte-exact identity contract with their Kotlin parsers.
struct StableWindowIDs {
    private var occurrences: [String: Int] = [:]

    mutating func next(_ base: String) -> String {
        // Equal stable fields cannot identify individual rows; preserve them with unique occurrence IDs.
        let occurrence = (occurrences[base] ?? 0) + 1
        occurrences[base] = occurrence
        return occurrence == 1 ? base : "\(base):\(occurrence)"
    }

    // ':' is outside Base64URL, so text, duration and occurrence cannot collide at a boundary.
    static func text(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
