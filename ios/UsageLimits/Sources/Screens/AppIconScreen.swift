import SwiftUI
import UIKit

enum AppIconChoice: CaseIterable {
    case current, gaugeCoin, aiGauge, aiHourglass, aiRefreshRing, aiBattery

    private var assetSuffix: String {
        switch self {
        case .current: "Current"
        case .gaugeCoin: "GaugeCoin"
        case .aiGauge: "AIGauge"
        case .aiHourglass: "AIHourglass"
        case .aiRefreshRing: "AIRefreshRing"
        case .aiBattery: "AIBattery"
        }
    }

    var iconName: String? { self == .current ? nil : "AppIcon-\(assetSuffix)" }
    var previewAsset: String { "AppIconPreview\(assetSuffix)" }

    var label: String {
        switch self {
        case .current: "Default"
        case .gaugeCoin: "Gauge Coin"
        case .aiGauge: "Gauge"
        case .aiHourglass: "Hourglass"
        case .aiRefreshRing: "Refresh Ring"
        case .aiBattery: "Battery"
        }
    }

    @MainActor
    static var active: AppIconChoice {
        allCases.first { $0.iconName == UIApplication.shared.alternateIconName } ?? .current
    }
}

struct AppIconScreen: View {
    @State private var selected = AppIconChoice.active
    @State private var applying = false
    @State private var showError = false
    @State private var errorMessage = ""

    var body: some View {
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 20) {
                ForEach(AppIconChoice.allCases, id: \.self) { choice in
                    Button {
                        guard UIApplication.shared.supportsAlternateIcons else { return }
                        applying = true
                        Task { @MainActor in
                            defer { applying = false }
                            do {
                                try await UIApplication.shared.setAlternateIconName(choice.iconName)
                                selected = AppIconChoice.active
                            } catch {
                                errorMessage = error.localizedDescription
                                showError = true
                            }
                        }
                    } label: {
                        VStack(spacing: 6) {
                            Image(choice.previewAsset)
                                .resizable()
                                .frame(width: 56, height: 56)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(selected == choice ? UsageColors.ink : .clear, lineWidth: 1.5)
                                }
                            Text(choice.label).usageFont(.small).foregroundStyle(UsageColors.ink)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.plain)
                    .disabled(applying || !UIApplication.shared.supportsAlternateIcons)
                    .accessibilityLabel(choice.label)
                    .accessibilityValue(selected == choice ? "Selected" : "Not selected")
                }
            }
            .padding(16)
        }
        .background(UsageColors.ground)
        .navigationTitle("App icon")
        .alert("Could not change icon", isPresented: $showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }
}
