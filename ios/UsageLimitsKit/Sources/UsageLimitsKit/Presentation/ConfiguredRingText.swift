import Foundation

/// The text of one ring in the iOS configured ring widgets: what an Account Rings centre writes,
/// and what VoiceOver hears for Account Rings and Mini Rings alike.
///
/// Composed here rather than in the widget view so the Linux tests reach the very strings the
/// widget shows and speaks; the widget passes its catalog-backed `PercentTexts`.
public enum ConfiguredRingText {

    /// Whether the ring paints its arc for `p`: it needs a fill, and a fill state that has ink.
    /// A failed reading or a provider-exhausted window in Left reads "no fill" and draws only the
    /// track. The widget's arc and its centre both follow this, so they cannot disagree.
    public static func arcDrawn(_ p: WindowPresentation) -> Bool {
        p.fill != nil && p.fillState != .noFill
    }

    /// The centre text, or nil when the ring writes nothing (`RingCentre`).
    public static func centre(_ p: WindowPresentation, texts: any PercentTexts) -> String? {
        switch RingCentre.of(token: p.token, side: p.side, arcDrawn: arcDrawn(p)) {
        case .signed: return texts.figure(p.token)
        case .bare: return p.token
        case .nothing: return nil
        }
    }

    /// What VoiceOver says for the ring: always the full figure, whatever the centre writes.
    ///
    /// Left is the sentence spoken before the mode existed, which reads the lowest figure across
    /// every window. Used speaks the presentation's own figure: an exhausted 5h window whose reset
    /// has passed, beside an open weekly at 60% left, draws "40% used" from the representative
    /// row, and a second derivation over all rows announced "100% used".
    public static func spoken(
        _ account: GlanceAccount, presentation p: WindowPresentation, texts: any PercentTexts
    ) -> String {
        let sentence: String
        if p.side == .used {
            sentence = texts.spoken(texts.figure(p.token), .used)
        } else {
            let lowest = account.rows.compactMap(\.remainingPercent).min()
            let token = UsageWindow.percentToken(lowest, exhausted: false, mode: .left)
            sentence = texts.spoken(texts.figure(token), .left)
        }
        return "\(account.title), \(account.subtitle ?? ""), \(sentence)"
    }
}
