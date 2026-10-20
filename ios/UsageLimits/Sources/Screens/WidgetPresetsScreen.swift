import SwiftUI
import WidgetKit
import UsageLimitsKit

struct WidgetPresetsScreen: View {
    @EnvironmentObject private var store: UsageStore
    @State private var presets: [WidgetPreset] = []
    @State private var editing: WidgetPreset?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Text("Choose Custom when editing a widget, then select one of these layouts. Each layout keeps its own account selection and order.")
                    .foregroundStyle(UsageColors.muted)
            }
            .listRowBackground(UsageColors.surface)
            Section(header: LedgerHeader("Custom layouts")) {
                ForEach(presets) { preset in
                    Button { editing = preset } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(preset.name).foregroundStyle(UsageColors.ink)
                                Text("\(preset.accountIDs.count) accounts").usageFont(.small).foregroundStyle(UsageColors.muted)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(UsageColors.faint)
                        }
                    }
                }
                Button("New custom layout") {
                    editing = WidgetPreset(name: "Custom \(presets.count + 1)", accountIDs: [])
                }
            }
            .listRowBackground(UsageColors.surface)
            if let error { Section { NoticeLine(message: error, dot: UsageColors.negative) }.listRowBackground(UsageColors.surface) }
        }
        .usageFont(.body)
        .ledgerList().navigationTitle("Widget layouts")
        .task {
            do { presets = try await WidgetPresetStore.shared.load() }
            catch { self.error = "Could not load your widget layouts." }
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

private struct WidgetPresetEditor: View {
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
                Section(header: LedgerHeader("Layout name")) { TextField("Name", text: $name) }
                    .listRowBackground(UsageColors.surface)
                Section(header: LedgerHeader("Preview")) {
                    HStack(spacing: 12) {
                        ForEach(order.filter { selected.contains($0) }.prefix(5), id: \.self) { id in
                            if let account = accounts.first(where: { $0.account.id == id }) {
                                ProviderBadge(provider: account.account.provider)
                            }
                        }
                        if selected.isEmpty { Text("Select accounts below").foregroundStyle(UsageColors.muted) }
                    }
                }
                .listRowBackground(UsageColors.surface)
                Section(header: LedgerHeader("Accounts · drag to reorder")) {
                    ForEach(order, id: \.self) { id in
                        if let usage = accounts.first(where: { $0.account.id == id }) {
                            HStack(spacing: 12) {
                                Button {
                                    if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
                                } label: {
                                    Image(systemName: selected.contains(id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(selected.contains(id) ? UsageColors.ink : UsageColors.faint)
                                }.buttonStyle(.plain).accessibilityLabel("Show \(usage.account.label)")
                                    .accessibilityValue(selected.contains(id) ? "Selected" : "Not selected")
                                ProviderBadge(provider: usage.account.provider)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(usage.account.label).foregroundStyle(UsageColors.ink)
                                    Text(usage.account.provider.displayName).usageFont(.small).foregroundStyle(UsageColors.muted)
                                }
                            }
                        }
                    }.onMove { offsets, destination in order.move(fromOffsets: offsets, toOffset: destination) }
                }
                .listRowBackground(UsageColors.surface)
                if let error { NoticeLine(message: error, dot: UsageColors.negative).listRowBackground(UsageColors.surface) }
            }
            .environment(\.editMode, $editMode)
            .usageFont(.body)
            .ledgerList().navigationTitle("Custom layout")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save") {
                        saving = true
                        Task {
                            do {
                                try await onSave(WidgetPreset(id: preset.id, name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                    accountIDs: order.filter { selected.contains($0) }))
                                dismiss()
                            } catch { self.error = "Could not save this layout. Try again."; saving = false }
                        }
                    }.disabled(saving || selected.isEmpty || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
