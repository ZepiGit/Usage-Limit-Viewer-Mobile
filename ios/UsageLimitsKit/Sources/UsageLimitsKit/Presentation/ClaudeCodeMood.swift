import Foundation

public enum ClaudeCodeMood: String, CaseIterable, Sendable {
    case asleep, tired, working, idle, dancing, hooray

    public static func steady(for headline: (any HeadlineCandidate)?) -> ClaudeCodeMood {
        guard let headline else { return .idle }
        if headline.severity == .exhausted { return .asleep }
        guard let remaining = headline.remainingPercent,
              remaining.isFinite, (0...100).contains(remaining) else { return .idle }
        if remaining == 0 { return .asleep }
        if remaining <= 20 { return .tired }
        if remaining <= 50 { return .working }
        if remaining <= 90 { return .idle }
        return .dancing
    }
}

public extension GlanceAccount {
    func claudeCodeMood(at date: Date) -> ClaudeCodeMood {
        ClaudeCodeMood.steady(for: representative(at: date))
    }
}
