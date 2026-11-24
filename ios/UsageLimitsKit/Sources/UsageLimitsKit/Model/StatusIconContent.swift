import Foundation

/// The lossy shorthand beside a provider glyph, never the source of spoken percentages.
public struct StatusIconContent: Sendable, Equatable {
    public enum Content: Sendable, Equatable {
        case error
        case exhausted
        case unknown
        /// A figure of 100 on a window that is not exhausted: the glyph alone (Android: `None`).
        case glyphOnly
        case figure(Int)
    }

    public let content: Content
    public let outdated: Bool

    public init(content: Content, outdated: Bool) {
        self.content = content
        self.outdated = outdated
    }

    public static func of(headline: WindowPresentation?, validity: ReadingValidity,
        needsReauthentication: Bool) -> StatusIconContent {
        of(token: headline?.token, exhausted: headline?.exhausted ?? false,
            validity: validity, needsReauthentication: needsReauthentication)
    }

    /// Primitive presentation inputs also let both platforms read the exact shared vectors.
    static func of(token: String?, exhausted: Bool, validity: ReadingValidity,
        needsReauthentication: Bool) -> StatusIconContent {
        let content: Content
        if needsReauthentication || validity == .error { content = .error }
        else if exhausted { content = .exhausted }
        else {
            switch token {
            case "100": content = .glyphOnly
            case PercentToken.underOne: content = .figure(0)
            case PercentToken.overNinetyNine: content = .figure(99)
            default:
                if let token, let figure = Int(token), (0...99).contains(figure) {
                    content = .figure(figure)
                } else { content = .unknown }
            }
        }
        let aged = validity == .stale || validity == .resetDue
        let outdated: Bool
        switch content {
        case .figure, .glyphOnly: outdated = aged
        case .error, .exhausted, .unknown: outdated = false
        }
        return StatusIconContent(content: content, outdated: outdated)
    }
}
