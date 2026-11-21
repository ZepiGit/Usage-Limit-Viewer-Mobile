import SwiftUI
import UsageLimitsKit

/// Only app slots replace the selected Claude Code image; the picker and widgets remain static.
struct ClaudeProviderBadge: View {
    @EnvironmentObject private var store: UsageStore
    @Environment(\.providerIconChoices) private var choices

    let provider: ProviderID
    let mood: ClaudeCodeMood
    let size: CGFloat
    var staticSize: CGFloat? = nil
    var figureSlotSize: CGFloat? = nil
    var amplitude: Double = 0.7
    var accountID: String? = nil

    static func isClaudeCode(_ choice: ProviderIconChoice) -> Bool {
        choice.id == "claudecode-color" || choice.id == "claudecode"
    }

    var body: some View {
        let choice = ProviderIconCatalog.selected(for: provider, id: choices[provider.rawValue])
        Group {
            if provider == .claude && Self.isClaudeCode(choice) {
                ClaudeCodeFigure(mood: mood, size: size,
                    ink: choice.id == "claudecode-color" ? UsageColors.data : UsageColors.ink,
                    animate: store.settings.animateClaudeCodeIcon, amplitude: amplitude, accountID: accountID)
                    .frame(width: figureSlotSize ?? size, height: figureSlotSize ?? size)
            } else {
                ProviderBadge(provider: provider, size: staticSize ?? size)
            }
        }
        .accessibilityHidden(true)
    }
}
