import SwiftUI
import UsageLimitsKit

struct ProviderIconsScreen: View {
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
                                Text(choice.label).foregroundStyle(UsageColors.ink)
                                Spacer()
                                if selected { Image(systemName: "checkmark").foregroundStyle(UsageColors.ink) }
                            }.padding(.vertical, 6)
                        }.accessibilityLabel(choice.label).accessibilityValue(selected ? "Selected" : "Not selected")
                    }
                }
                .listRowBackground(UsageColors.surface)
            }
        }
        .ledgerList()
        .navigationTitle("Provider icons")
        .accessibilityIdentifier("provider-icons-list")
    }
}
