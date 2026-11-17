import Foundation

public enum PinnedAccountText {
    public static func cellText(_ entry: PinnedAccountEntry) -> String? {
        switch entry.content.content {
        case .figure(let value): return String(value)
        case .none: return nil
        case .unknown: return PercentToken.unknown
        case .error: return "!"
        case .exhausted: return "⊗"
        }
    }

    /// Keep the full presentation token even when the tiny icon omits or shortens it.
    public static func spoken(entry: PinnedAccountEntry, texts: any PinnedAccountTexts) -> String {
        let figure: String
        switch entry.content.content {
        case .error:
            figure = entry.account.connectionStatus == .reconnectRequired ? texts.signInExpired : texts.error
        case .exhausted: figure = texts.exhausted
        case .unknown: figure = texts.unknown
        case .figure, .none:
            figure = texts.spoken(texts.figure(entry.presentation.token), entry.presentation.side)
        }
        return "\(entry.account.title), \(figure)" + (entry.content.outdated ? ", \(texts.outdated)" : "")
    }
}
