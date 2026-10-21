import SwiftUI
// `Severity` lives in the kit and is named throughout this file. Swift imports are per-file, so
// another file in the same target importing it buys this one nothing — which is how a file that
// looked fine failed to build in two targets at once.
import UsageLimitsKit

/// The palette, kept numerically identical to the Android one.
///
/// Same product, same colours: a user with the app on both a phone and an iPad should not see
/// two different shades of "running low". The hex values are copied from
/// `app/src/main/kotlin/com/usagelimits/ui/theme/Color.kt`, and any change belongs in both.
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

    /// Bar or arc fill for a severity, mirroring Android's `SeverityPalette.barFill`: the data hue,
    /// the stale grey for an aged reading, and nil (the bare track) for an unknown, failed or
    /// exhausted one, whatever percentage it still reports.
    ///
    /// The callers pass the account's AGED severity, so a stale or failed account whose cached
    /// reading still sat at 100 % cannot paint a fresh-looking bar under an out-of-date verdict.
    /// Quota severity stays per row and never recolours a bar: the figure and the dot say it.
    static func barFill(_ severity: Severity) -> Color? {
        switch severity {
        case .stale: return UsageColors.staleMark
        case .unknown, .error, .exhausted: return nil
        default: return UsageColors.data
        }
    }

    static func label(_ severity: Severity) -> String {
        switch severity {
        case .healthy: return "Healthy"
        case .medium: return "Moderate"
        case .low: return "Low"
        case .limited: return "Limited"
        case .unknown: return "Unknown"
        case .exhausted: return "Exhausted"
        case .stale: return "Stale"
        case .error: return "Error"
        }
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
/// One text face and one figure face, both system. A figure is a number standing alone in its
/// slot (a %, money, a count, a clock time) and is set in the monospaced design so a column of
/// them lines up; a number inside a sentence stays in the text face with fixed-width digits.
/// 700 is for money only.
///
/// Sizes scale with Dynamic Type relative to the text style named beside each.
enum UsageFont {
    case title, lead, widgetLead, money, widgetMoney, rowMoney
    case section, body, bodyStrong, figure, figureLarge, ringFigure
    case small, smallStrong, micro

    fileprivate var spec: (size: CGFloat, weight: Font.Weight, design: Font.Design, style: Font.TextStyle) {
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
        case .small: return (12, .regular, .default, .caption)
        case .smallStrong: return (12, .medium, .default, .caption)
        case .micro: return (11, .medium, .default, .caption2)
        }
    }
}

private struct UsageFontModifier: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let font: UsageFont

    init(_ font: UsageFont) {
        self.font = font
        _size = ScaledMetric(wrappedValue: font.spec.size, relativeTo: font.spec.style)
    }

    func body(content: Content) -> some View {
        // Fixed-width digits on every token: the sentences that carry a countdown or a time
        // ("Resets in 2h 12m") must not shimmy as the digits change.
        content.font(Font.system(size: size, weight: font.spec.weight, design: font.spec.design).monospacedDigit())
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
    var padding: CGFloat = 16
    var spacing: CGFloat = 12
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(UsageColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: UsageRadius.card, style: .continuous))
    }
}

/// A 1pt hairline between the rows of one card, inset from the card's left edge.
struct LedgerDivider: View {
    var inset: CGFloat = 16

    var body: some View {
        UsageColors.rule.frame(height: 1).padding(.leading, inset)
    }
}

/// An empty list: one sentence in a dashed box, where a card would be.
struct DashedBox<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.card, style: .continuous)
                    .stroke(UsageColors.control, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
    }
}

/// A section title between groups of cards.
struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .usageFont(.section)
            .foregroundStyle(UsageColors.ink)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A screen's page title and the one line that dates its data.
struct ScreenHeader: View {
    var title: String? = nil
    let stamp: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let title {
                Text(title).usageFont(.title).foregroundStyle(UsageColors.ink)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(stamp).usageFont(.small).foregroundStyle(UsageColors.faint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A placeholder bar at a real element's size, for a screen that has not loaded yet.
struct SkeletonBar: View {
    var width: CGFloat = 1
    var height: CGFloat = 14

    var body: some View {
        GeometryReader { geometry in
            RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                .fill(UsageColors.raised)
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
    let severity: Severity
    var label: String? = nil

    var body: some View {
        if severity != .healthy || label != nil {
            HStack(spacing: 6) {
                Circle().fill(SeverityPalette.accent(severity)).frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                Text(label ?? SeverityPalette.label(severity))
                    .usageFont(.smallStrong)
                    .foregroundStyle(UsageColors.muted)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// A dot and a sentence — and, for a failure, the way to try again.
struct NoticeLine: View {
    let message: String
    var dot: Color = UsageColors.warning
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(dot).frame(width: 6, height: 6).accessibilityHidden(true)
                Text(verbatim: message)
                    .usageFont(.body)
                    .foregroundStyle(retry == nil ? UsageColors.muted : UsageColors.ink)
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
    @Environment(\.isEnabled) private var isEnabled
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? UsageColors.onInk : UsageColors.muted)
            .padding(.horizontal, 16)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .fill(isEnabled ? UsageColors.ink.opacity(configuration.isPressed ? 0.85 : 1) : UsageColors.raised))
    }
}

/// Secondary: a 1pt `control` border and an `ink` label. Also the destructive button, with a
/// `negativeText` label.
struct OutlineButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var label: Color = UsageColors.ink
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? label : UsageColors.muted)
            .padding(.horizontal, 16)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .fill(configuration.isPressed ? UsageColors.raised : Color.clear))
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(UsageColors.control, lineWidth: 1))
    }
}

/// Tertiary: text only, 44pt to hit.
struct TextActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var label: Color = UsageColors.ink

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .usageFont(.bodyStrong)
            .foregroundStyle(isEnabled ? label : UsageColors.muted)
            .frame(minHeight: 44)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A text field in the ledger's own skin: 48pt, `surface` fill, 1pt `control` border, 1.5pt
/// `ink` while focused. Replaces `.roundedBorder`, which paints a light field on a dark app.
private struct LedgerFieldModifier: ViewModifier {
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .usageFont(.body)
            .foregroundStyle(UsageColors.ink)
            .tint(UsageColors.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(UsageColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(focused ? UsageColors.ink : UsageColors.control, lineWidth: focused ? 1.5 : 1))
    }
}

extension View {
    func ledgerField() -> some View {
        modifier(LedgerFieldModifier())
    }

    /// A stock `List`/`Form` re-skinned: ground behind, `surface` rows, `ink` switches and
    /// pickers. Section titles are `LedgerHeader`s.
    func ledgerList() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(UsageColors.ground)
            .tint(UsageColors.ink)
    }
}

/// A `Form` section header: sentence case, 14pt medium, `muted` — not the system's uppercase.
struct LedgerHeader: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).usageFont(.bodyStrong).foregroundStyle(UsageColors.muted).textCase(nil)
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
    let options: [SegmentOption]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 0) {
                ForEach(options) { option in
                    Button(action: option.action) {
                        Text(option.title)
                            .usageFont(option.selected ? .bodyStrong : .body)
                            .foregroundStyle(option.selected ? UsageColors.ink : UsageColors.muted)
                            .padding(.horizontal, 14)
                            .frame(height: 36)
                            .background(
                                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                                    .fill(option.selected ? UsageColors.raised : Color.clear))
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(option.selected ? "Selected" : "Not selected")
                }
            }
            .background(
                RoundedRectangle(cornerRadius: UsageRadius.control, style: .continuous)
                    .stroke(UsageColors.rule, lineWidth: 1)
                    .padding(.vertical, 4))
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let remainingPercent: Double?
    let severity: Severity
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2, style: .continuous).fill(UsageColors.dataTrack)
                if let fill = SeverityPalette.barFill(severity) {
                    RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                        .fill(fill)
                        // An unknown percentage draws nothing rather than a full or empty bar:
                        // either would state a fact the provider did not report.
                        .frame(width: geometry.size.width * fraction)
                        // The fraction animates; the colour does not, since there is one fill.
                        .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: remainingPercent)
                }
            }
        }
        .frame(height: height)
    }

    private var fraction: CGFloat {
        guard let remaining = remainingPercent else { return 0 }
        return CGFloat(min(max(remaining, 0), 100) / 100)
    }
}

/// Uses the same selectable provider marks as the account list and widgets.
struct ProviderBadge: View {
    @Environment(\.providerIconChoices) private var choices
    let provider: ProviderID

    var body: some View {
        ProviderMark(assetName: ProviderIconCatalog.selected(for: provider, id: choices[provider.rawValue]).assetName)
            .padding(4)
            .frame(width: 32, height: 32)
            .accessibilityLabel(provider.displayName)
    }

}

/// A provider's bundled artwork.
///
/// `tint` renders the artwork as a single-colour silhouette: the white logos vanish on a light
/// wallpaper, so a widget on one asks for its ink.
struct ProviderMark: View {
    let assetName: String
    var tint: Color? = nil

    var body: some View {
        if let tint {
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
