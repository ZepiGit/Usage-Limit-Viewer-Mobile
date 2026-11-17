import Foundation

public enum PinnedAccountText {
    /// The mark beside the glyph, as a character, for the places that cannot draw one: the
    /// compact inline row. The rectangular and circular cells draw the same marks as shapes.
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

    /// One account of the inline row: a short name and what follows it.
    public struct InlinePart: Sendable, Equatable {
        public let name: String
        public let value: String
        /// Drawn as an underline under `value`, as the icon underlines its figure.
        public let outdated: Bool
    }

    /// The longest inline row, in characters, that keeps words for the states without a number.
    /// Above it the row uses the icon's signs ("—", "!", "⊘") so the figures stay on screen.
    public static let inlineWordBudget = 32

    /// The inline accessory's single text row.
    ///
    /// - One account: its name, the full figure with its sign and side ("Claude 42% left").
    /// - Two or three: name and bare token each, joined by " · " ("Claude 42 · Codex 87"). The token
    ///   is the presentation's own, so "<1", ">99" and "100" stay as they are.
    /// - States without a number are words ("Exhausted", "Error", "Unknown"); a row longer than
    ///   `inlineWordBudget` characters uses the signs instead.
    /// - Names: the provider's short name, or the first word of the account title for a provider
    ///   this build does not know, cut to 16 characters for one account, 10 for two and 6 for
    ///   three; a cut name ends in "…" and is exactly that long.
    public static func inline(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts) -> String {
        inlineParts(entries, texts: texts).map { "\($0.name) \($0.value)" }.joined(separator: " · ")
    }

    public static func inlineParts(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts) -> [InlinePart] {
        let worded = inlineParts(entries, texts: texts, signs: false)
        let row = worded.map { "\($0.name) \($0.value)" }.joined(separator: " · ")
        return row.count > inlineWordBudget ? inlineParts(entries, texts: texts, signs: true) : worded
    }

    private static func inlineParts(_ entries: [PinnedAccountEntry], texts: any PinnedAccountTexts,
        signs: Bool) -> [InlinePart] {
        let budget = entries.count <= 1 ? 16 : entries.count == 2 ? 10 : 6
        return entries.map { entry in
            let value: String
            switch entry.content.content {
            case .error: value = signs ? "!" : texts.error
            case .exhausted: value = signs ? "⊘" : texts.exhausted
            case .unknown: value = signs ? PercentToken.unknown : texts.unknown
            case .figure, .none:
                let token = entry.presentation.token
                value = entries.count == 1
                    ? "\(texts.figure(token)) \(texts.sideWord(entry.presentation.side))" : token
            }
            return InlinePart(name: shortened(inlineName(entry.account), to: budget), value: value,
                outdated: entry.content.outdated)
        }
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
