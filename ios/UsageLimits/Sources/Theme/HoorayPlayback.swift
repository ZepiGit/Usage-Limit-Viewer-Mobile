import Combine
import SwiftUI
import UsageLimitsKit

@MainActor
final class HoorayPlayback: ObservableObject {
    @Published private(set) var presentation = HoorayPresentation()
    private(set) var expiry: Task<Void, Never>?
    private let now: () -> TimeInterval
    private let waitForExpiry: () async throws -> Void

    init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         waitForExpiry: @escaping () async throws -> Void = {
             try await Task.sleep(for: .seconds(HoorayPresentation.duration))
         }) {
        self.now = now
        self.waitForExpiry = waitForExpiry
    }

    func open(_ accounts: Set<String>) {
        expiry?.cancel()
        expiry = nil
        presentation.open(accounts)
    }

    func placed(_ accounts: Set<String>) {
        presentation.placed(visibleAccountIDs: accounts, at: now())
        guard !presentation.startedAt.isEmpty, expiry == nil else { return }
        let opening = presentation.opening
        let waitForExpiry = waitForExpiry
        expiry = Task { [weak self] in
            do { try await waitForExpiry() }
            catch { return }
            guard !Task.isCancelled, let self, self.presentation.opening == opening else { return }
            self.presentation.advance(to: self.now())
            self.expiry = nil
        }
    }

    func stop() {
        expiry?.cancel()
        expiry = nil
        presentation.stop()
    }

    func startedAt(_ accountID: String?) -> TimeInterval? {
        accountID.flatMap { presentation.startedAt[$0] }
    }
}

private struct HoorayPlaybackKey: EnvironmentKey {
    static var defaultValue: HoorayPlayback? { nil }
}

private struct HoorayViewportKey: EnvironmentKey {
    static let defaultValue = "scene"
}

extension EnvironmentValues {
    var hoorayPlayback: HoorayPlayback? {
        get { self[HoorayPlaybackKey.self] }
        set { self[HoorayPlaybackKey.self] = newValue }
    }
    var hoorayViewport: String {
        get { self[HoorayViewportKey.self] }
        set { self[HoorayViewportKey.self] = newValue }
    }
}

private struct HooraySlot {
    let accountID: String
    let viewport: String
    let bounds: Anchor<CGRect>
}

private struct HoorayLayout {
    var slots: [HooraySlot] = []
    var viewports: [String: Anchor<CGRect>] = [:]
    var readyBounds: Anchor<CGRect>?
}

private struct HoorayLayoutKey: PreferenceKey {
    static var defaultValue: HoorayLayout { HoorayLayout() }
    static func reduce(value: inout HoorayLayout, nextValue: () -> HoorayLayout) {
        let next = nextValue()
        value.slots += next.slots
        value.viewports.merge(next.viewports) { _, new in new }
        if let bounds = next.readyBounds { value.readyBounds = bounds }
    }
}

extension View {
    func hooraySlot(accountID: String?, viewport: String, visible: Bool) -> some View {
        anchorPreference(key: HoorayLayoutKey.self, value: .bounds) { bounds in
            HoorayLayout(slots: visible ? accountID.map {
                [HooraySlot(accountID: $0, viewport: viewport, bounds: bounds)]
            } ?? [] : [])
        }
    }

    /// Names the actual scroll viewport so an off-screen row cannot spend the opening.
    func hoorayViewport(_ name: String) -> some View {
        environment(\.hoorayViewport, name)
            .transformAnchorPreference(key: HoorayLayoutKey.self, value: .bounds) { value, bounds in
                value.viewports[name] = bounds
            }
    }

    func hoorayOpening(playback: HoorayPlayback, ready: Bool) -> some View {
        modifier(HoorayOpeningModifier(playback: playback, ready: ready))
    }
}

private struct HoorayOpeningModifier: ViewModifier {
    @ObservedObject var playback: HoorayPlayback
    let ready: Bool

    func body(content: Content) -> some View {
        content.transformAnchorPreference(key: HoorayLayoutKey.self, value: .bounds) { value, bounds in
            value.readyBounds = ready ? bounds : nil
        }
        .overlayPreferenceValue(HoorayLayoutKey.self) { layout in
            GeometryReader { geometry in
                let bounds = CGRect(origin: .zero, size: geometry.size)
                let ids = Set(layout.slots.compactMap { slot -> String? in
                    let viewport = layout.viewports[slot.viewport].map { geometry[$0] } ?? bounds
                    let visible = geometry[slot.bounds].intersection(viewport).intersection(bounds)
                    return visible.isNull || visible.isEmpty ? nil : slot.accountID
                })
                let placement = HoorayPlacement(opening: playback.presentation.opening,
                    ready: ready && layout.readyBounds.map { !geometry[$0].isEmpty } == true, accounts: ids)
                Color.clear
                    .task(id: placement) {
                        guard placement.ready, !Task.isCancelled else { return }
                        playback.placed(placement.accounts)
                    }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

private struct HoorayPlacement: Hashable {
    let opening: Int
    let ready: Bool
    let accounts: Set<String>
}
