import SwiftUI
import UsageLimitsKit

struct ProviderIconsScreen: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @EnvironmentObject private var store: UsageStore

    var body: some View {
        List {
            ForEach(ProviderID.allCases, id: \.self) { provider in
                Section(header: LedgerHeader(provider.displayName)) {
                    ForEach(ProviderIconCatalog.choices(for: provider)) { choice in
                        let selected = ProviderIconCatalog.selected(for: provider, id: store.settings.providerIcons[provider.rawValue]).id == choice.id
                        Button {
                            store.settings.providerIcons[provider.rawValue] = choice.id
                        } label: {
                            HStack(spacing: 16) {
                                ProviderMark(assetName: choice.assetName).frame(width: 36, height: 36)
                                Text(verbatim: DisplayText.providerIconLabel(choice.label)).foregroundStyle(colors.ink)
                                Spacer()
                                if selected { Image(systemName: "checkmark").foregroundStyle(colors.ink) }
                            }.padding(.vertical, 6)
                        }.accessibilityLabel(DisplayText.providerIconLabel(choice.label)).accessibilityValue(DisplayText.selection(selected))
                    }
                }
                .listRowBackground(colors.surface)
            }
        }
        .ledgerList()
        .navigationTitle("Provider icons")
        .accessibilityIdentifier("provider-icons-list")
    }
}
