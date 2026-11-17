import Foundation

public enum PinnedAccountText {
    /// Keep the full presentation token even when the tiny icon omits or shortens it.
    public static func spoken(entry: PinnedAccountEntry, texts: any PercentTexts,
        error: String, exhausted: String, unknown: String, outdated: String) -> String {
        let figure: String
        switch entry.content.content {
        case .error: figure = error
        case .exhausted: figure = exhausted
        case .unknown: figure = unknown
        case .figure, .none:
            figure = texts.spoken(texts.figure(entry.presentation.token), entry.presentation.side)
        }
        return "\(entry.account.title), \(figure)" + (entry.content.outdated ? ", \(outdated)" : "")
    }
}
