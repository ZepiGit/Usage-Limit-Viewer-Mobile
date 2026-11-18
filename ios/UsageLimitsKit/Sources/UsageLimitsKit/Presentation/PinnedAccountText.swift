import Foundation

public enum PinnedAccountText {
    /// The mark beside the glyph, as a character. The rectangular and circular cells draw the
    /// exhausted sign as a shape and the rest as this text.
    public static func cellText(_ entry: PinnedAccountEntry) -> String? {
        switch entry.content.content {
        case .figure(let value): return String(value)
        case .none: return nil
        case .unknown: return PercentToken.unknown
        case .error: return "!"
        case .exhausted: return "⊘"
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

    /// The inline accessory's row: one glyph and one line of text, because that family shows a
    /// single row of text and at most one image (WidgetKit, `WidgetFamily.accessoryInline`).
    public struct InlineRow: Sendable, Equatable {
        /// The first pinned account's provider; nil or an id without a glyph draws the fallback disc.
        public let glyphProviderID: String?
        public let text: String

        public init(glyphProviderID: String?, text: String) {
            self.glyphProviderID = glyphProviderID
            self.text = text
        }
    }

    /// The inline row: the first pinned account only, or nil when nothing is pinned.
    ///
    /// The glyph names the account, so the text is its figure with the side ("42% left",
    /// "42 % übrig"), or the word for a state without a number ("Exhausted", "Error",
    /// "Sign-in expired", "Unknown"). The system sets this family in its own font and drops
    /// styling, so an outdated figure cannot be underlined; it says so in the word VoiceOver uses
    /// ("42% left · Stale"), which reads plainly where a sign such as "~" would read as "about".
    public static func inlineRow(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts) -> InlineRow? {
        guard let entry = entries.first else { return nil }
        let text: String
        switch entry.content.content {
        case .error:
            text = entry.account.connectionStatus == .reconnectRequired ? texts.signInExpired : texts.error
        case .exhausted: text = texts.exhausted
        case .unknown: text = texts.unknown
        case .figure, .none:
            let figure = "\(texts.figure(entry.presentation.token)) \(texts.sideWord(entry.presentation.side))"
            text = entry.content.outdated ? "\(figure) · \(texts.outdated)" : figure
        }
        return InlineRow(glyphProviderID: entry.account.providerID, text: text)
    }
}
