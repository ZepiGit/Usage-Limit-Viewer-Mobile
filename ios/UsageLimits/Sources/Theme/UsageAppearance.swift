import SwiftUI
import UsageLimitsKit

struct UsageAppearance: Equatable, Sendable {
    let theme: AppearanceTheme
    let textFont: AppearanceTextFont
    let figureFont: AppearanceFigureFont?
    let isApp: Bool

    static let widget = UsageAppearance(theme: .warmDark, textFont: .system, figureFont: nil, isApp: false)

    init(settings: AppSettings) {
        theme = settings.appearanceTheme
        textFont = settings.appearanceTextFont
        figureFont = settings.appearanceFigureFont
        isApp = true
    }

    private init(theme: AppearanceTheme, textFont: AppearanceTextFont, figureFont: AppearanceFigureFont?, isApp: Bool) {
        self.theme = theme; self.textFont = textFont; self.figureFont = figureFont; self.isApp = isApp
    }

    var colors: UsagePalette { UsagePalette(theme: theme, appText: isApp) }
}

private struct UsageAppearanceKey: EnvironmentKey {
    static let defaultValue = UsageAppearance.widget
}

extension EnvironmentValues {
    var usageAppearance: UsageAppearance {
        get { self[UsageAppearanceKey.self] }
        set { self[UsageAppearanceKey.self] = newValue }
    }
}

struct UsagePalette {
    let theme: AppearanceTheme
    var appText = true
    private var palette: AppearancePalette { theme.palette }
    var ground: Color { Color(hex: palette.ground) }
    var surface: Color { Color(hex: palette.surface) }
    var raised: Color { Color(hex: palette.raised) }
    var rule: Color { Color(hex: palette.rule) }
    var control: Color { Color(hex: palette.control) }
    var ink: Color { Color(hex: palette.ink) }
    var muted: Color { Color(hex: palette.muted) }
    var faint: Color { Color(hex: appText ? palette.quietText : palette.faint) }
    var onInk: Color { ground }
    var data: Color { Color(hex: palette.data) }
    var dataText: Color { Color(hex: palette.dataText) }
    var dataTrack: Color { rule }
    var positive: Color { Color(hex: palette.positive) }
    var warning: Color { Color(hex: palette.warning) }
    var negative: Color { Color(hex: palette.negative) }
    var negativeText: Color { Color(hex: palette.negativeText) }
    var staleMark: Color { Color(hex: palette.faint) }

    func accent(_ severity: Severity) -> Color { Color(hex: palette.accent(severity)) }
    func figureColor(_ severity: Severity) -> Color { Color(hex: palette.figure(severity)) }
    func fill(_ state: FillState) -> Color? {
        switch state {
        case .data: return data
        case .stale: return staleMark
        case .noFill: return nil
        }
    }
}

private struct AppAppearanceModifier: ViewModifier {
    @ScaledMetric(relativeTo: .subheadline) private var bodySize: CGFloat = 14
    let settings: AppSettings

    func body(content: Content) -> some View {
        let appearance = UsageAppearance(settings: settings)
        content
            .environment(\.usageAppearance, appearance)
            .preferredColorScheme(appearance.theme.isLight ? .light : .dark)
            .font(UsageTypography.font(.body, size: bodySize, appearance: appearance))
            .tint(appearance.colors.ink)
    }
}

extension View {
    func appAppearance(_ settings: AppSettings) -> some View {
        modifier(AppAppearanceModifier(settings: settings))
    }
}
