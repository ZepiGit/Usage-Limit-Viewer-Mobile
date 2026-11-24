import SwiftUI
import WidgetKit
import UsageLimitsKit

struct WidgetPresetsScreen: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @EnvironmentObject private var store: UsageStore
    @State private var presets: [WidgetPreset] = []
    @State private var editing: WidgetPreset?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Text("Choose Custom when editing a widget, then select one of these layouts. Each layout keeps its own account selection and order.")
                    .foregroundStyle(colors.muted)
                Text("Left or used follows Settings › Display, like Overview. Ring widgets keep the side in VoiceOver, not as a visible caption.")
                    .foregroundStyle(colors.muted)
                    .accessibilityIdentifier("widget-mode-guidance")
                WidgetLayoutArtwork(mode: store.settings.percentDisplay)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                Text("Static artwork. Add a widget from the Home Screen gallery; touch and hold it to edit its content. System tint, size and update timing are controlled by WidgetKit.")
                    .foregroundStyle(colors.muted)
            }
            .listRowBackground(colors.surface)
            Section(header: LedgerHeader(String(localized: "Custom layouts"))) {
                ForEach(presets) { preset in
                    Button { editing = preset } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(preset.name).foregroundStyle(colors.ink)
                                Text("\(preset.accountIDs.count) accounts").usageFont(.small).foregroundStyle(colors.muted)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(colors.faint)
                        }
                    }
                }
                Button("New custom layout") {
                    editing = WidgetPreset(name: String(localized: "Custom \(presets.count + 1)"), accountIDs: [])
                }
            }
            .listRowBackground(colors.surface)
            if let error { Section { NoticeLine(message: error, dot: colors.negative) }.listRowBackground(colors.surface) }
        }
        .usageFont(.body)
        .ledgerList().navigationTitle("Widget layouts")
        .task {
            do { presets = try await WidgetPresetStore.shared.load() }
            catch { self.error = String(localized: "Could not load your widget layouts.") }
        }
        .sheet(item: $editing) { preset in
            WidgetPresetEditor(preset: preset, accounts: store.accounts) { saved in
                var updated = presets.filter { $0.id != saved.id }
                if let index = presets.firstIndex(where: { $0.id == saved.id }) { updated.insert(saved, at: min(index, updated.count)) }
                else { updated.append(saved) }
                try await WidgetPresetStore.shared.save(updated)
                presets = updated
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
    }
}

private struct WidgetLayoutArtwork: View {
    let mode: PercentDisplay

    var body: some View {
        let presentation = WindowPresentation(remaining: 18, severity: .low, validity: .current, mode: mode)
        HStack(spacing: 16) {
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    ProviderMark(assetName: "ProviderIconClaudecodeColor").frame(width: 27, height: 27)
                    Text(verbatim: "Claude").font(.system(size: 13, weight: .medium))
                    Spacer(minLength: 0)
                }
                Text(verbatim: WidgetPercentText.figure(presentation.token))
                    .font(.system(size: 18, weight: .medium, design: .monospaced))
                Text(verbatim: PercentText.sideWord(mode)).font(.system(size: 11))
                Capsule().fill(UsageColors.data).frame(height: 4)
            }
            VStack(spacing: 8) {
                ZStack {
                    Circle().stroke(UsageColors.dataTrack, lineWidth: 4)
                    Circle().trim(from: 0, to: presentation.fill ?? 0).stroke(UsageColors.data,
                        style: StrokeStyle(lineWidth: 4, lineCap: .round)).rotationEffect(.degrees(-90))
                    ProviderMark(assetName: "ProviderIconClaudecodeColor").frame(width: 28, height: 28)
                }.frame(width: 48, height: 48)
                Text("Mini Rings").font(.system(size: 11, weight: .medium))
            }
        }
        .padding(16)
        .foregroundStyle(UsageColors.ink)
        .background(UsageColors.ground)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .environment(\.usageAppearance, .widget)
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Static Usage Bars and Mini Rings artwork")
        .accessibilityIdentifier("widget-layout-static-artwork")
    }
}

private struct WidgetPresetEditor: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @Environment(\.dismiss) private var dismiss
    let preset: WidgetPreset
    let accounts: [AccountUsage]
    let onSave: @MainActor (WidgetPreset) async throws -> Void
    @State private var name: String
    @State private var order: [String]
    @State private var selected: Set<String>
    @State private var error: String?
    @State private var saving = false
    @State private var editMode: EditMode = .active

    init(preset: WidgetPreset, accounts: [AccountUsage], onSave: @escaping @MainActor (WidgetPreset) async throws -> Void) {
        self.preset = preset; self.accounts = accounts; self.onSave = onSave
        _name = State(initialValue: preset.name)
        _selected = State(initialValue: Set(preset.accountIDs))
        _order = State(initialValue: preset.accountIDs.filter { id in accounts.contains { $0.account.id == id } } +
            accounts.map { $0.account.id }.filter { !preset.accountIDs.contains($0) })
    }

    var body: some View {
        NavigationStack {
            List {
                Section(header: LedgerHeader(String(localized: "Layout name"))) { TextField("Name", text: $name) }
                    .listRowBackground(colors.surface)
                Section(header: LedgerHeader(String(localized: "Preview"))) {
                    HStack(spacing: 12) {
                        ForEach(order.filter { selected.contains($0) }.prefix(5), id: \.self) { id in
                            if let account = accounts.first(where: { $0.account.id == id }) {
                                ProviderBadge(provider: account.account.provider)
                            }
                        }
                        if selected.isEmpty { Text("Select accounts below").foregroundStyle(colors.muted) }
                    }
                    .environment(\.usageAppearance, .widget)
                }
                .listRowBackground(colors.surface)
                Section(header: LedgerHeader(String(localized: "Accounts · drag to reorder"))) {
                    ForEach(order, id: \.self) { id in
                        if let usage = accounts.first(where: { $0.account.id == id }) {
                            HStack(spacing: 12) {
                                Button {
                                    if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
                                } label: {
                                    Image(systemName: selected.contains(id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(selected.contains(id) ? colors.ink : colors.faint)
                                }.buttonStyle(.plain).accessibilityLabel("Show \(usage.account.label)")
                                    .accessibilityValue(DisplayText.selection(selected.contains(id)))
                                ProviderBadge(provider: usage.account.provider)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(usage.account.label).foregroundStyle(colors.ink)
                                    Text(usage.account.provider.displayName).usageFont(.small).foregroundStyle(colors.muted)
                                }
                            }
                        }
                    }.onMove { offsets, destination in
                        #if DEBUG
                        if MarketingDemo.isEnabled {
                            SettingsTrace.record("custom layout onMove before source=\(Array(offsets)) "
                                + "destination=\(destination) count=\(order.count) order=\(order)")
                        }
                        #endif
                        order.move(fromOffsets: offsets, toOffset: destination)
                        #if DEBUG
                        if MarketingDemo.isEnabled {
                            SettingsTrace.record("custom layout onMove after order=\(order)")
                        }
                        #endif
                    }
                }
                .listRowBackground(colors.surface)
                if let error { NoticeLine(message: error, dot: colors.negative).listRowBackground(colors.surface) }
            }
            .environment(\.editMode, $editMode)
            .usageFont(.body)
            .ledgerList().navigationTitle("Custom layout")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? String(localized: "Saving…") : String(localized: "Save")) {
                        saving = true
                        Task {
                            do {
                                try await onSave(WidgetPreset(id: preset.id, name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                    accountIDs: order.filter { selected.contains($0) }))
                                dismiss()
                            } catch { self.error = String(localized: "Could not save this layout. Try again."); saving = false }
                        }
                    }.disabled(saving || selected.isEmpty || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
