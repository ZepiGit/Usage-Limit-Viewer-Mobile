import SwiftUI
// `Severity` lives in the kit and is named throughout this file. Swift imports are per-file, so
// another file in the same target importing it buys this one nothing — which is how a file that
// looked fine failed to build in two targets at once.
import UsageLimitsKit

/// The fixed Warm dark widget palette. App views resolve a local `UsagePalette` from the environment.
///
/// Roles, not hues. Chrome (tabs, buttons, switches, links, focus) is `ink`. `data` is the one
/// hue that means "quota": a bar fill, a ring arc, the next-reset countdown. Severity is a small
/// signal on top — a dot, or the colour of one figure — never a bar, a border or a tinted pill.
enum UsageColors {

    static let ground = Color(hex: 0x0F0F0E)
    /// Cards: a tone step above the ground, no border.
    static let surface = Color(hex: 0x1A1918)
    /// A selected segment, a pressed row, a skeleton bar.
    static let raised = Color(hex: 0x242322)
    /// Hairlines inside a card, and the track under a bar or ring.
    static let rule = Color(hex: 0x2E2D2B)
    /// The 1pt border of an outlined control: brighter than `rule` because it is interactive.
    static let control = Color(hex: 0x4A4844)

    static let ink = Color(hex: 0xF0EEE6)
    static let muted = Color(hex: 0xB4B2A9)
    /// Stamps and meta on `ground` or `surface` only; on `raised` it falls under 4.5:1.
    static let faint = Color(hex: 0x8A887F)
    /// The label on an `ink` fill.
    static let onInk = Color(hex: 0x0F0F0E)

    /// Terracotta, the brand, as the quota hue.
    static let data = Color(hex: 0xD97757)
    static let dataTrack = rule

    static let positive = Color(hex: 0x6FBF73)
    static let warning = Color(hex: 0xE0A33E)
    static let negative = Color(hex: 0xD9584F)
    /// The red to write words in: `negative` is enough for a dot, not for 12pt text.
    static let negativeText = Color(hex: 0xE8756B)
    static let staleMark = Color(hex: 0x8A887F)
}

/// Maps a severity onto the palette.
///
/// Kept in one place so screens, widgets and notifications colour a status identically — the
/// same reason the Android side has a single `SeverityPalette`.
enum SeverityPalette {

    /// The colour of a status DOT.
    static func accent(_ severity: Severity) -> Color {
        switch severity {
        case .healthy: return UsageColors.positive
        case .medium, .low, .limited: return UsageColors.warning
        case .exhausted, .error: return UsageColors.negative
        case .stale, .unknown: return UsageColors.staleMark
        }
    }

    /// Which role a row's percentage is written in. Widgets resolve the roles against their
    /// own ink, so the decision is shared and the colours are not.
    enum FigureTone { case ink, warning, negative, faint }

    /// Plain ink while there is room; amber at 1-20 % left; red on a real zero; faint when the
    /// number is stale, failed or unknown and so describes something that is no longer true.
    static func figureTone(_ severity: Severity) -> FigureTone {
        switch severity {
        case .healthy, .medium: return .ink
        case .low, .limited: return .warning
        case .exhausted: return .negative
        case .stale, .unknown, .error: return .faint
        }
    }

    static func figureColor(_ severity: Severity) -> Color {
        switch figureTone(severity) {
        case .ink: return UsageColors.ink
        case .warning: return UsageColors.warning
        case .negative: return UsageColors.negativeText
        case .faint: return UsageColors.faint
        }
    }

    /// Bar or arc ink for how a presentation paints its fill: the data hue, the stale grey for a
    /// reading that is no longer to be trusted, and nil (the bare track) for one that draws no
    /// fill at all.
    ///
    /// Which of the three applies is the Kit's decision (`WindowPresentation.fillState`), taken
    /// from the reading's validity and its raw severity, so the palette holds colours only and a
    /// stale or failed account whose cached reading still sat at 100 % cannot paint a
    /// fresh-looking bar. Quota severity never recolours a bar: the figure and the dot say it.
    static func fill(_ state: FillState) -> Color? {
        switch state {
        case .data: return UsageColors.data
        case .stale: return UsageColors.staleMark
        case .noFill: return nil
        }
    }

    static func label(_ severity: Severity) -> String {
        switch severity {
        case .healthy: return String(localized: "Healthy")
        case .medium: return String(localized: "Moderate")
        case .low: return String(localized: "Low")
        case .limited: return String(localized: "Limited")
        case .unknown: return String(localized: "Unknown")
        case .exhausted: return String(localized: "Exhausted")
        case .stale: return String(localized: "Stale")
        case .error: return String(localized: "Error")
        }
    }
}

/// The words around a percentage figure, composed here so the app and the widget say the same
/// thing in the same mode.
///
/// The Kit hands over a token (no `%`) and this layer owns the wording, which is what keeps the
/// Kit free of UI strings.
enum PercentText {

    /// The localized figure used in the app and in speech; the unknown dash stays bare.
    ///
    /// The sign comes from the catalog's one template, `"%@%%"`, so a language that sets a space
    /// before it ("73 %") changes it in one place.
    static func figure(_ p: WindowPresentation) -> String {
        figure(token: p.token)
    }

    /// The same composition for a bare token, for a figure that has no `WindowPresentation`
    /// (a segment's share of the bar).
    static func figure(token: String) -> String {
        token == PercentToken.unknown ? token : String(localized: "\(token)%")
    }

    /// The caption a grid carries when its rings show no side word: "Left" or "Used".
    static func caption(_ mode: PercentDisplay) -> String {
        mode == .used ? String(localized: "Used") : String(localized: "Left")
    }

    /// The visible caption is the short "left"/"used" so it fits beside the figure; speech says
    /// "remaining" instead (see `spoken`) because that sentence was already shipped in LEFT.
    static func sideWord(_ mode: PercentDisplay) -> String {
        mode == .used ? String(localized: "used") : String(localized: "left")
    }

    /// "45% remaining" in LEFT — the sentence spoken today, kept byte-identical — and "55% used".
    static func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
        mode == .used ? String(localized: "\(figure) used") : String(localized: "\(figure) remaining")
    }
}

/// Corner radii: two values and a sheet. Nothing between 12 and 24 any more.
enum UsageRadius {
    static let card: CGFloat = 8
    static let control: CGFloat = 6
    static let sheet: CGFloat = 12
}

extension Color {
    /// `Color(hex: 0xD97757)`, so the palette above reads the same as the Kotlin one.
    ///
    /// RGB only: the top byte is NOT an alpha. `Color(hex: 0x14000000)` is opaque black, which
    /// is how a widget chip meant to be 8 % black once covered its tile. Write a translucent
    /// colour as `Color(hex:).opacity(_:)`.
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1)
    }
}

// MARK: - Type

/// The type scale: six sizes in the app (12/14/16/20/24/30), 11 in widgets and one 22 lead.
///
/// App faces come from the scoped appearance; the widget context keeps native fixed type.
/// A standalone percentage, amount or clock uses the figure face; prose keeps fixed-width digits.
/// 700 is for money only.
///
/// Sizes scale with Dynamic Type relative to the text style named beside each.
enum UsageFont {
    case title, lead, widgetLead, money, widgetMoney, rowMoney
    case section, body, bodyStrong, figure, figureLarge, ringFigure
    case small, smallStrong, micro, compactFigure, accountTitle, emptyTitle
    case dialFigure, previewTitle, previewFigure, displayPreviewFigure, previewWindow

    var spec: (size: CGFloat, weight: Font.Weight, design: Font.Design, style: Font.TextStyle) {
        switch self {
        case .title: return (24, .medium, .default, .title2)
        case .lead: return (30, .medium, .monospaced, .largeTitle)
        case .widgetLead: return (22, .medium, .monospaced, .title2)
        case .money: return (20, .bold, .monospaced, .title3)
        case .widgetMoney: return (22, .bold, .monospaced, .title2)
        case .rowMoney: return (14, .bold, .monospaced, .subheadline)
        case .section: return (16, .medium, .default, .headline)
        case .body: return (14, .regular, .default, .subheadline)
        case .bodyStrong: return (14, .medium, .default, .subheadline)
        case .figure: return (14, .medium, .monospaced, .subheadline)
        case .figureLarge: return (16, .medium, .monospaced, .headline)
        case .ringFigure: return (16, .semibold, .monospaced, .headline)
        case .compactFigure: return (13, .medium, .monospaced, .subheadline)
        case .accountTitle: return (15, .medium, .default, .headline)
        case .emptyTitle: return (20, .medium, .default, .title3)
        case .small: return (12, .regular, .default, .caption)
        case .smallStrong: return (12, .medium, .default, .caption)
        case .micro: return (11, .medium, .default, .caption2)
        case .dialFigure: return (22, .medium, .monospaced, .title2)
        case .previewTitle: return (13, .medium, .default, .subheadline)
        case .previewFigure: return (11, .medium, .monospaced, .caption2)
        case .displayPreviewFigure: return (18, .medium, .monospaced, .title3)
        case .previewWindow: return (11, .regular, .default, .caption2)
        }
    }
}

private struct UsageFontModifier: ViewModifier {
    @Environment(\.usageAppearance) private var appearance
    @ScaledMetric private var size: CGFloat
    private let font: UsageFont

    init(_ font: UsageFont) {
        self.font = font
        _size = ScaledMetric(wrappedValue: font.spec.size, relativeTo: font.spec.style)
    }

    func body(content: Content) -> some View {
        // Fixed-width digits on every token: the sentences that carry a countdown or a time
        // ("Resets in 2h 12m") must not shimmy as the digits change.
        content.font(UsageTypography.font(font, size: size, appearance: appearance))
    }
}

extension View {
    func usageFont(_ font: UsageFont) -> some View {
        modifier(UsageFontModifier(font))
    }
}

// MARK: - Surfaces

/// A panel on the app's dark ground: a tone step, no border, no shadow.
struct UsageCard<Content: View>: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    var padding: CGFloat = 16
    var spacing: CGFloat = 12
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: UsageRadius.card, style: .continuous))
    }
}

/// A 1pt hairline between the rows of one card, inset from the card's left edge.
struct LedgerDivider: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    var inset: CGFloat = 16

    var body: some View {
        colors.rule.frame(height: 1).padding(.leading, inset)
    }
}

/// An empty list: one sentence in a dashed box, where a card would be.
struct DashedBox<Content: View>: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.card, style: .continuous)
                    .stroke(colors.control, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
    }
}

/// A section title between groups of cards.
struct SectionTitle: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let text: String

    var body: some View {
        Text(text)
            .usageFont(.section)
            .foregroundStyle(colors.ink)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A placeholder bar at a real element's size, for a screen that has not loaded yet.
struct SkeletonBar: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    var width: CGFloat = 1
    var height: CGFloat = 14

    var body: some View {
        GeometryReader { geometry in
            RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                .fill(colors.raised)
                .frame(width: geometry.size.width * width, height: height)
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

/// An account card before its numbers exist: the real card's blocks at real size.
struct AccountSkeletonCard: View {
    var body: some View {
        UsageCard {
            SkeletonBar(width: 0.4, height: 16)
            SkeletonBar(width: 0.6, height: 12)
            ForEach(0..<3, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 6) {
                    SkeletonBar(width: 0.3, height: 14)
                    UsageBar(remainingPercent: nil, severity: .healthy)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Marks and states

/// A status as a dot and a word, with no fill. Drawn only when something is worth saying:
/// a healthy account is the default and needs no mark.
struct StatusMark: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let severity: Severity
    var label: String? = nil

    var body: some View {
        if severity != .healthy || label != nil {
            HStack(spacing: 6) {
                Circle().fill(colors.accent(severity)).frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                Text(label ?? SeverityPalette.label(severity))
                    .usageFont(.smallStrong)
                    .foregroundStyle(colors.muted)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// A dot and a sentence — and, for a failure, the way to try again.
struct NoticeLine: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let message: String
    var dot: Color? = nil
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(dot ?? colors.warning).frame(width: 6, height: 6).accessibilityHidden(true)
                // The kit's own failure sentences are stored in English; this reads one in the
                // current language and shows anything else exactly as it came.
                Text(verbatim: DisplayText.stored(message))
                    .usageFont(.body)
                    .foregroundStyle(retry == nil ? colors.muted : colors.ink)
            }
            if let retry {
                Button("Retry", action: retry).buttonStyle(TextActionStyle())
            }
        }
    }
}

// MARK: - Controls

/// The one filled button a screen gets: `ink` fill, dark label.
struct InkButtonStyle: ButtonStyle {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.isEnabled) private var isEnabled
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? colors.onInk : colors.muted)
            .padding(.horizontal, 16)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .fill(isEnabled ? colors.ink.opacity(configuration.isPressed ? 0.85 : 1) : colors.raised))
            .pressRelease(isPressed: configuration.isPressed)
    }
}

/// Secondary: a 1pt `control` border and an `ink` label. Also the destructive button, with a
/// `negativeText` label.
struct OutlineButtonStyle: ButtonStyle {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.isEnabled) private var isEnabled
    var label: Color? = nil
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? (label ?? colors.ink) : colors.muted)
            .padding(.horizontal, 16)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .fill(configuration.isPressed ? colors.raised : Color.clear))
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(colors.control, lineWidth: 1))
            .pressRelease(isPressed: configuration.isPressed)
    }
}

/// Tertiary: text only, 44pt to hit.
struct TextActionStyle: ButtonStyle {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.isEnabled) private var isEnabled
    var label: Color? = nil

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? (label ?? colors.ink) : colors.muted)
            .frame(minHeight: 44)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .pressRelease(isPressed: configuration.isPressed)
    }
}

/// A text field in the ledger's own skin: 48pt, `surface` fill, 1pt `control` border, 1.5pt
/// `ink` while focused. Replaces `.roundedBorder`, which paints a light field on a dark app.
private struct LedgerFieldModifier: ViewModifier {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .usageFont(.body)
            .foregroundStyle(colors.ink)
            .tint(colors.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(focused ? colors.ink : colors.control, lineWidth: focused ? 1.5 : 1))
    }
}

extension View {
    func ledgerField() -> some View {
        modifier(LedgerFieldModifier())
    }

    /// A stock `List`/`Form` re-skinned: ground behind, `surface` rows, `ink` switches and
    /// pickers. Section titles are `LedgerHeader`s.
    func ledgerList() -> some View {
        modifier(LedgerListModifier())
    }
}

private struct LedgerListModifier: ViewModifier {
    @Environment(\.usageAppearance) private var appearance

    func body(content: Content) -> some View {
        content.scrollContentBackground(.hidden)
            .background(appearance.colors.ground)
            .tint(appearance.colors.ink)
    }
}

/// A `Form` section header: sentence case, 14pt medium, `muted` — not the system's uppercase.
struct LedgerHeader: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).usageFont(.bodyStrong).foregroundStyle(colors.muted).textCase(nil)
    }
}

/// One option of a `SegmentedRow`.
struct SegmentOption: Identifiable {
    let id: String
    let title: String
    let selected: Bool
    let action: () -> Void
}

/// A row of 36pt cells in a `rule` frame, in place of capsule chips. Scrolls sideways when it
/// overflows and never wraps; each cell is padded to a 44pt touch target.
struct SegmentedRow: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let options: [SegmentOption]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 0) {
                ForEach(options) { option in
                    Button(action: option.action) {
                        Text(option.title)
                            .usageFont(option.selected ? .bodyStrong : .body)
                            .foregroundStyle(option.selected ? colors.ink : colors.muted)
                            .padding(.horizontal, 14)
                            .frame(height: 36)
                            .background(
                                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                                    .fill(option.selected ? colors.raised : Color.clear))
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(DisplayText.selection(option.selected))
                }
            }
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(colors.rule, lineWidth: 1)
                    .padding(.vertical, 4))
            // On the row, not on each cell: the selected label is heavier and so a little wider,
            // and its neighbours and the frame have to move with it while the fill changes hands.
            // Keyed on which options are selected, not on the options there are: a cell that
            // arrives with the first content, or with a new account, is not a selection changing.
            .motion(.stateChange, value: options.filter(\.selected).map(\.id))
        }
    }
}

// MARK: - Quota bar

/// The proportional bar every quota row is read from.
///
/// Drawn with a `GeometryReader` rather than `ProgressView` because the track, the fill colour
/// and the corner radius all have to match the Android rendering exactly, and the system style
/// gives up control of all three.
struct UsageBar: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    /// What the bar draws: its fill and how that fill is painted come from the Kit, so the bar
    /// cannot disagree with the figure beside it.
    let presentation: WindowPresentation
    var height: CGFloat = 6
    private let emphasis: Double

    init(presentation: WindowPresentation, height: CGFloat = 6, emphasis: Double = 1) {
        self.presentation = presentation
        self.height = height
        self.emphasis = emphasis
    }

    /// The LEFT-only bar for a skeleton and for money, which are out of scope for the percentage
    /// toggle: a current reading counted from what is left, with no raw-row verdict to carry.
    init(remainingPercent: Double?, severity: Severity, height: CGFloat = 6) {
        self.init(
            presentation: WindowPresentation(
                remaining: remainingPercent, severity: severity, validity: .current, mode: .left),
            height: height)
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2, style: .continuous).fill(colors.dataTrack)
                // Always drawn, in clear ink when the presentation paints no fill, so the rectangle
                // keeps its identity and its length: a bar that starts or stops being painted
                // (an exhausted window on a mode flip, a failed reading that recovers) grows from
                // or shrinks to its figure instead of being inserted or removed whole.
                RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                    .fill((colors.fill(presentation.fillState) ?? Color.clear).opacity(emphasis))
                    // An unknown percentage draws nothing rather than a full or empty bar:
                    // either would state a fact the provider did not report.
                    .frame(width: geometry.size.width * fraction)
                    .motion(.move, value: fraction)
            }
        }
        .frame(height: height)
    }

    /// `fill` is already within 0 to 1, or nil: a figure that is missing or out of range draws
    /// the bare track, never a full or an empty bar.
    private var fraction: CGFloat {
        CGFloat(presentation.fill ?? 0)
    }
}

/// Uses the same selectable provider marks as the account list and widgets.
struct ProviderBadge: View {
    @Environment(\.providerIconChoices) private var choices
    let provider: ProviderID
    var size: CGFloat = 32

    var body: some View {
        ProviderMark(assetName: ProviderIconCatalog.selected(for: provider, id: choices[provider.rawValue]).assetName)
            .padding(size == 32 ? 4 : 0)
            .frame(width: size, height: size)
            .accessibilityLabel(provider.displayName)
    }

}

/// A provider's bundled artwork.
///
/// `tint` renders the artwork as a single-colour silhouette: the white logos vanish on a light
/// wallpaper, so a widget on one asks for its ink.
struct ProviderMark: View {
    @Environment(\.usageAppearance) private var appearance
    let assetName: String
    var tint: Color? = nil

    private static let whiteArtwork: Set<String> = [
        "ProviderIconOpenai", "ProviderIconOpenaiText", "ProviderIconGrok", "ProviderIconGrokText",
        "ProviderIconKimi", "ProviderIconKimiText", "ProviderIconClaude", "ProviderIconClaudeText",
        "ProviderIconClaudecode", "ProviderIconClaudecodeText", "ProviderIconGemini", "ProviderIconGeminiText",
        "ProviderIconAntigravity", "ProviderIconAntigravityText", "ProviderIconDevin", "ProviderIconMeta",
        "ProviderIconZai", "ProviderIconZaiText", "ProviderIconOpencode", "ProviderIconOpencodeText",
        "ProviderIconOllama", "ProviderIconMinimax"
    ]

    var body: some View {
        if let tint = tint ?? (appearance.isApp && appearance.theme.isLight && Self.whiteArtwork.contains(assetName)
            ? appearance.colors.ink : nil) {
            Image(assetName).resizable().renderingMode(.template).scaledToFit().foregroundStyle(tint)
        } else {
            Image(assetName).resizable().scaledToFit()
        }
    }
}

/// Keeps quota rows legible in wide iPad and resizable windows while filling narrow panes.
extension View {
    func readableWidth() -> some View {
        frame(maxWidth: 760).frame(maxWidth: .infinity)
    }
}

private struct ProviderIconChoicesKey: EnvironmentKey {
    static let defaultValue: [String: String] = [:]
}

extension EnvironmentValues {
    var providerIconChoices: [String: String] {
        get { self[ProviderIconChoicesKey.self] }
        set { self[ProviderIconChoicesKey.self] = newValue }
    }
}

extension ProviderID {
    var assetName: String { ProviderIconCatalog.selected(for: self, id: nil).assetName }
}
