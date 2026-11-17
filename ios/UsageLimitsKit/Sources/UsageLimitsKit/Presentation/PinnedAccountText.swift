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

    /// What VoiceOver reads for a family that shows several accounts as one element.
    public static func spokenList(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts) -> String {
        entries.map { spoken(entry: $0, texts: texts) }.joined(separator: "; ")
    }

    /// The inline accessory's row: one glyph and one line of text, because that family shows a
    /// single row of text and at most one image (WidgetKit, `WidgetFamily.accessoryInline`).
    public struct InlineRow: Sendable, Equatable {
        /// Whose glyph leads the row: the first pinned account's provider; nil or an id without a
        /// glyph draws the fallback disc.
        public let glyphProviderID: String?
        public let text: String

        public init(glyphProviderID: String?, text: String) {
            self.glyphProviderID = glyphProviderID
            self.text = text
        }
    }

    /// The longest row text, in characters, that keeps words for the states without a number.
    /// Above it the row uses the icon's signs ("—", "!", "⊘") so the figures stay on screen.
    public static let inlineWordBudget = 28

    /// The inline row, or nil when nothing is pinned.
    ///
    /// - The glyph stands for the first account, so its figure follows with no name: one account
    ///   reads [glyph] "42% left"; two or three read [glyph] "42 · Codex 87 · Kimi 100", the others
    ///   named because only one image fits. Tokens are the presentation's own, so "<1", ">99" and
    ///   "100" stay as they are.
    /// - States without a number are words ("Exhausted", "Error", "Unknown"); a text longer than
    ///   `inlineWordBudget` characters uses the signs instead.
    /// - The system draws this family in its own font and drops styling, so an outdated figure
    ///   cannot be underlined: it is written with a leading "~" ("~42").
    /// - Names after the glyph: the provider's short name, or the first word of the account title
    ///   for a provider this build does not know, cut to 10 characters for two accounts and 6 for
    ///   three; a cut name ends in "…" and is exactly that long.
    public static func inlineRow(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts) -> InlineRow? {
        guard let first = entries.first else { return nil }
        let worded = inlineText(entries, texts: texts, signs: false)
        let text = worded.count > inlineWordBudget ? inlineText(entries, texts: texts, signs: true) : worded
        return InlineRow(glyphProviderID: first.account.providerID, text: text)
    }

    private static func inlineText(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts,
        signs: Bool) -> String {
        let budget = entries.count == 2 ? 10 : 6
        return entries.enumerated().map { index, entry in
            let value: String
            switch entry.content.content {
            case .error: value = signs ? "!" : texts.error
            case .exhausted: value = signs ? "⊘" : texts.exhausted
            case .unknown: value = signs ? PercentToken.unknown : texts.unknown
            case .figure, .none:
                let token = entry.presentation.token
                let mark = entry.content.outdated ? "~" : ""
                value = entries.count == 1
                    ? "\(mark)\(texts.figure(token)) \(texts.sideWord(entry.presentation.side))" : mark + token
            }
            return index == 0 ? value : "\(shortened(inlineName(entry.account), to: budget)) \(value)"
        }.joined(separator: " · ")
    }

    private static func inlineName(_ account: GlanceAccount) -> String {
        switch account.providerID.flatMap(ProviderID.init(rawValue:)) {
        case .claude: return "Claude"
        case .codex: return "Codex"
        case .antigravity: return "Antigravity"
        case .xai: return "Grok"
        case .kimi: return "Kimi"
        case .devin: return "Devin"
        case .meta: return "Muse"
        case .zai: return "GLM"
        case .opencode: return "OpenCode"
        case nil: return account.title.split(separator: " ").first.map(String.init) ?? account.title
        }
    }

    private static func shortened(_ name: String, to budget: Int) -> String {
        name.count <= budget ? name : String(name.prefix(budget - 1)) + "…"
    }
}
