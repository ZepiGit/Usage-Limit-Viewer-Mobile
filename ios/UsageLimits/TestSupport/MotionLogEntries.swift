import Foundation

func motionLogEntries(_ text: String) -> [(number: Int, event: String)] {
    text.components(separatedBy: " | ").dropFirst().compactMap { entry in
        let parts = entry.split(separator: " ", maxSplits: 1)
        guard parts.count == 2, let number = Int(parts[0]) else { return nil }
        return (number, String(parts[1]))
    }
}
