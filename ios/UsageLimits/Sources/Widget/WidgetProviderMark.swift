import SwiftUI
import UsageLimitsKit

/// Widget-owned ink and a steady pose; no app settings, animation clock or pending reset state.
struct WidgetProviderMark: View {
    let account: GlanceAccount
    let date: Date
    let size: CGFloat
    var ink: WidgetInk = .light

    var body: some View {
        Group {
            if let provider = ProviderID(rawValue: account.providerID ?? "") {
                let asset = account.iconAssetName ?? provider.assetName
                if provider == .claude && (asset == "ProviderIconClaudecodeColor" || asset == "ProviderIconClaudecode") {
                    ClaudeCodeStill(mood: account.claudeCodeMood(at: date), size: size,
                        ink: asset == "ProviderIconClaudecodeColor" ? UsageColors.data : ink.primary)
                } else {
                    ProviderMark(assetName: asset, tint: ink.tintsLogos ? ink.primary : nil)
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
