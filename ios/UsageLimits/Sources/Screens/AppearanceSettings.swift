import SwiftUI
import UsageLimitsKit

struct AppearanceSettings: View {
    @Binding var settings: AppSettings
    @EnvironmentObject private var store: UsageStore
    @Environment(\.usageAppearance) private var appearance
    @Environment(\.dynamicTypeSize) private var typeSize
    private var colors: UsagePalette { appearance.colors }

    var body: some View {
        Section(header: LedgerHeader(String(localized: "Appearance"))) {
            preview
            Text("Theme").usageFont(.bodyStrong)
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(AppearanceTheme.allCases, id: \.self) { theme in
                    Button { settings.appearanceTheme = theme } label: {
                        HStack(spacing: 8) {
                            swatch(theme)
                            Text(theme.label).usageFont(.body)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.horizontal, 8)
                    }
                    .buttonStyle(AppearanceChoiceStyle(selected: settings.appearanceTheme == theme))
                    .accessibilityValue(DisplayText.selection(settings.appearanceTheme == theme))
                    .accessibilityIdentifier("settings-theme-\(theme.rawValue)")
                }
            }
            Text("Colours of the app. Widgets keep their own panel style.")
                .usageFont(.small).foregroundStyle(colors.muted)
            Text("Text font").usageFont(.bodyStrong)
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(AppearanceTextFont.allCases, id: \.self) { font in
                    Button { settings.appearanceTextFont = font } label: {
                        Text(font.label).usageFont(.body)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding(.horizontal, 8)
                    }
                    .environment(\.usageAppearance, sample(text: font))
                    .buttonStyle(AppearanceChoiceStyle(selected: settings.appearanceTextFont == font))
                    .accessibilityValue(DisplayText.selection(settings.appearanceTextFont == font))
                    .accessibilityIdentifier("settings-text-font-\(font.rawValue)")
                }
            }
            Text("Used for all text in the app.").usageFont(.small).foregroundStyle(colors.muted)
            Text("Figure font").usageFont(.bodyStrong)
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(AppearanceFigureFont.allCases, id: \.self) { font in
                    Button { settings.appearanceFigureFont = font } label: {
                        VStack(spacing: 4) {
                            Text(verbatim: PercentText.figure(token: "61")).usageFont(.figureLarge)
                            Text(verbatim: font.label).usageFont(.small)
                                .foregroundStyle(colors.muted)
                        }
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .padding(8)
                    }
                    .environment(\.usageAppearance, sample(figure: font))
                    .buttonStyle(AppearanceChoiceStyle(selected: settings.appearanceFigureFont == font))
                    .accessibilityValue(DisplayText.selection(settings.appearanceFigureFont == font))
                    .accessibilityIdentifier("settings-figure-font-\(font.rawValue)")
                }
            }
            Text("Used for percentages, times and amounts.").usageFont(.small).foregroundStyle(colors.muted)
            NavigationLink("Font licences") { FontLicencesScreen() }
                .accessibilityIdentifier("settings-font-licences")
        }
        .listRowBackground(colors.surface)
    }

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(minimum: 100)), count: typeSize >= .accessibility1 ? 1 : 2)
    }

    private func sample(text: AppearanceTextFont? = nil, figure: AppearanceFigureFont? = nil) -> UsageAppearance {
        var copy = settings
        if let text { copy.appearanceTextFont = text }
        if let figure { copy.appearanceFigureFont = figure }
        return UsageAppearance(settings: copy)
    }

    private func swatch(_ theme: AppearanceTheme) -> some View {
        let palette = UsagePalette(theme: theme)
        return VStack(alignment: .leading, spacing: 4) {
            palette.ink.frame(width: 20, height: 3)
            palette.data.frame(width: 14, height: 3)
        }
        .padding(6)
        .frame(width: 36, height: 24)
        .background(palette.ground, in: RoundedRectangle(cornerRadius: 4))
        .accessibilityHidden(true)
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: String(localized: "Preview").localizedUppercase)
                .usageFont(.micro).tracking(0.66).foregroundStyle(colors.faint)
            Text(verbatim: "Claude Pro").usageFont(.accountTitle).foregroundStyle(colors.ink)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(verbatim: PercentText.figure(token: settings.percentDisplay == .left ? "18" : "82"))
                    .usageFont(.displayPreviewFigure).foregroundStyle(colors.warning)
                Text(verbatim: PercentText.sideWord(settings.percentDisplay))
                    .usageFont(.small).foregroundStyle(colors.muted)
            }
            UsageBar(remainingPercent: settings.percentDisplay == .left ? 18 : 82, severity: .low)
            Text(verbatim: String(localized: "23:31 · 1h 18m · $12.40"))
                .usageFont(.previewFigure).foregroundStyle(colors.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(colors.ground, in: RoundedRectangle(cornerRadius: UsageRadius.card))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settings-appearance-preview")
        #if DEBUG
        .accessibilityValue(UsageLimitsApp.isUITesting && store.uiTestSavedSettings == settings ? "saved" : "preview")
        #endif
    }
}

private struct AppearanceChoiceStyle: ButtonStyle {
    @Environment(\.usageAppearance) private var appearance
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(appearance.colors.ink)
            .background(configuration.isPressed || selected ? appearance.colors.raised : appearance.colors.surface,
                        in: RoundedRectangle(cornerRadius: UsageRadius.control))
            .overlay(RoundedRectangle(cornerRadius: UsageRadius.control)
                .stroke(selected ? appearance.colors.ink : appearance.colors.rule, lineWidth: selected ? 2 : 1))
            .pressRelease(isPressed: configuration.isPressed)
    }
}

extension AppearanceTheme {
    var label: String {
        switch self {
        case .warmDark: return String(localized: "Warm dark")
        case .pureBlack: return String(localized: "Pure black")
        case .coolGraphite: return String(localized: "Cool graphite")
        case .lightPaper: return String(localized: "Light paper")
        }
    }
}

extension AppearanceTextFont {
    var label: String {
        switch self {
        case .system: return String(localized: "System")
        case .robotoFlex: return "Roboto Flex"
        case .ibmPlexSans: return "IBM Plex Sans"
        case .figtree: return "Figtree"
        }
    }
}

extension AppearanceFigureFont {
    var label: String {
        switch self {
        case .robotoMono: return "Roboto Mono"
        case .jetBrainsMono: return "JetBrains Mono"
        case .ibmPlexMono: return "IBM Plex Mono"
        }
    }
}

private struct FontNoticeScreen: View {
    @Environment(\.usageAppearance) private var appearance
    let name: String
    let file: String

    var body: some View {
        ScrollView {
            if let url = Bundle.main.url(forResource: "font_license_\(file)", withExtension: "txt",
                                          subdirectory: "android-res/raw"),
               let notice = try? String(contentsOf: url, encoding: .utf8) {
                Text(verbatim: notice).usageFont(.body).textSelection(.enabled)
                    .accessibilityIdentifier("font-licence-\(file)")
                    .padding(16)
            } else {
                Text("The bundled font notice could not be read.").padding(16)
            }
        }
        .foregroundStyle(appearance.colors.ink)
        .background(appearance.colors.ground)
        .navigationTitle(name)
    }
}

private struct FontLicencesScreen: View {
    @Environment(\.usageAppearance) private var appearance

    private let notices = [
        ("Roboto Flex", "roboto_flex"), ("IBM Plex Sans", "ibm_plex_sans"), ("Figtree", "figtree"),
        ("Roboto Mono", "roboto_mono"), ("JetBrains Mono", "jetbrains_mono"), ("IBM Plex Mono", "ibm_plex_mono")
    ]

    var body: some View {
        List {
            ForEach(notices, id: \.0) { name, file in
                NavigationLink {
                    FontNoticeScreen(name: name, file: file)
                } label: {
                    Text(verbatim: name)
                }
                .accessibilityIdentifier("font-licence-link-\(file)")
                .listRowBackground(appearance.colors.surface)
            }
        }
        .foregroundStyle(appearance.colors.ink)
        .ledgerList()
        .navigationTitle("Font licences")
    }
}
