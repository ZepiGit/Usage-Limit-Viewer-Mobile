import Combine
import SwiftUI
import UIKit
import UsageLimitsKit

@main
struct UsageLimitsApp: App {

    @StateObject private var store = UsageStore()

    init() {
        #if DEBUG
        if MarketingDemo.isEnabled { return }
        #endif
        // Registration must happen before launch finishes; later is a programmer error the
        // system traps rather than reports, and the app then never refreshes in the background
        // again. The store's own container is handed over rather than a second one: two
        // containers mean two credential stores and two sets of in-flight refresh locks, and
        // that lock exists precisely so one rotating refresh token cannot be spent twice.
        if let container = UsageStore.sharedContainer {
            BackgroundRefresh.register(container: container)
        }
        // Before launch finishes, for the same reason as the background registration: the
        // system delivers a launch tap to whoever is the delegate AT THAT MOMENT, so installing
        // it later means the first notification a user acts on is the one that does not route.
        NotificationRouter.shared.install()
    }

    /// Whether the process was started by the UI test bundle.
    ///
    /// One flag, read in one place. On its own it suppresses a system permission prompt and
    /// nothing else — it must never change what the app computes or displays, or the tests
    /// would be checking a different app from the one that ships. A debug build's test
    /// fixtures ask for it as well as for their own flag, so none applies without it.
    ///
    /// `nonisolated`, because it reads the process arguments and nothing of this type, and the
    /// shared container is built outside the main actor.
    nonisolated static var isUITesting: Bool {
        ProcessInfo.processInfo.arguments.contains("-ui-testing")
    }

    /// Which tab is showing. Lifted out of `TabView`'s own state so a widget tap can select
    /// one: a deep link that opened the app on whatever tab was last used would make the
    /// tile's destination a coin toss.
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: RootView.Tab = .overview
    @State private var router = OverviewRouter()
    @State private var detail: DetailRoute?

    private struct DetailRoute: Identifiable {
        let id: String
        let unavailable: Bool
    }

    var body: some Scene {
        WindowGroup {
            RootView(tab: $tab, claudeAnimationVisible: detail == nil, openAccount: openAccount)
                .fullScreenCover(item: $detail) { route in
                    NavigationStack {
                        AccountDetailScreen(
                            accountID: route.id, unavailable: route.unavailable,
                            // Only the detail that asked closes. A removal can finish after the
                            // user has gone on to another account, and must not close that one.
                            onDisconnected: { if detail?.id == route.id { detail = nil } })
                            .navigationTitle("Account details")
                            .navigationBarTitleDisplayMode(.inline)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { detail = nil }
                                        .accessibilityIdentifier("overview-close-detail")
                                }
                            }
                    }
                    .environmentObject(store)
                    .environment(\.providerIconChoices, store.settings.providerIcons)
                    .environment(\.claudeAnimationVisible, true)
                    #if DEBUG
                    .motionLogOverlay()
                    #endif
                    .modifier(StoreErrorAlert(store: store))
                }
                .environmentObject(store)
                .environment(\.providerIconChoices, store.settings.providerIcons)
                #if DEBUG
                .environment(\.reduceMotionOverride,
                    ProcessInfo.processInfo.arguments.contains("-reduce-motion") ? true : nil)
                #endif
                .modifier(StoreErrorAlert(store: store, enabled: detail == nil))
                .onOpenURL { url in
                    apply(router.open(url, loadedAccountIDs: loadedAccountIDs))
                }
                .onChange(of: store.hasLoaded) { loaded in
                    if loaded { apply(router.accountsLoaded(store.accounts.map(\.account.id))) }
                }
                .onChange(of: scenePhase) { phase in
                    if phase == .active { store.updatePresentationClock() }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)
                    .receive(on: RunLoop.main)) { _ in
                    store.updatePresentationClock()
                }
                .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)
                    .receive(on: RunLoop.main)) { _ in
                    store.updatePresentationClock()
                }
                .task {
                    guard !store.isMarketingDemo else { return }
                    NotificationRouter.shared.onAccountTapped = openAccount
                }
                .preferredColorScheme(.dark)
                // Cached first, then the network. A cold launch that waited for the slowest
                // provider before drawing anything would read as "you have no accounts" for as
                // long as that took — which on a bad connection is indefinitely.
                .task {
                    await store.load()
                    // Asked once, and only after the first screen is up: a permission sheet in
                    // front of an empty app asks the user to approve something they have not
                    // seen the point of yet.
                    //
                    // Never asked under UI test. The permission prompt is a SYSTEM alert, which
                    // sits above the app's own window and fails every query behind it — so a
                    // test written to check the tab bar would fail on the alert instead, for a
                    // reason that reads as unrelated to what it was checking.
                    if !Self.isUITesting && !store.isMarketingDemo {
                        _ = await NotificationScheduler.requestAuthorization()
                    }
                    await store.refresh(automatic: true)
                }
        }
    }

    private var loadedAccountIDs: [String]? {
        store.hasLoaded ? store.accounts.map(\.account.id) : nil
    }

    private func openAccount(_ id: String) {
        apply(router.notification(accountID: id, loadedAccountIDs: loadedAccountIDs))
    }

    private func apply(_ destination: OverviewDestination?) {
        guard let destination else { return }
        tab = .overview
        switch destination {
        case .overview:
            detail = nil
        case .refreshOverview:
            detail = nil
            Task { await store.refresh() }
        case .account(let id):
            detail = DetailRoute(id: id, unavailable: false)
        case .unavailable(let id):
            // An empty list from a register that could not be read does not say the account is
            // gone. The detail then follows the store instead of being pinned to "unavailable":
            // the storage wording now, and the account itself once a later read finds it.
            detail = DetailRoute(id: id, unavailable: !store.registerUnreadable)
        }
    }
}

private struct StoreErrorAlert: ViewModifier {
    @ObservedObject var store: UsageStore
    var enabled = true

    func body(content: Content) -> some View {
        content.alert("Something went wrong", isPresented: Binding(
            get: { enabled && store.lastError != nil },
            set: { if enabled && !$0 { store.clearError() } }
        )) {
            Button("OK", role: .cancel) { store.clearError() }
        } message: {
            Text(verbatim: DisplayText.stored(store.lastError ?? ""))
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case overview, resets, settings }

    @Binding var tab: Tab
    var claudeAnimationVisible = true
    let openAccount: (String) -> Void

    var body: some View {
        TabView(selection: $tab) {
            OverviewScreen(isSelected: tab == .overview, openAccount: openAccount)
                .environment(\.claudeAnimationVisible, claudeAnimationVisible && tab == .overview)
                .tabItem { Label("Overview", systemImage: "gauge.with.dots.needle.33percent") }
                .tag(Tab.overview)
            ResetsScreen()
                .environment(\.claudeAnimationVisible, claudeAnimationVisible && tab == .resets)
                .tabItem { Label("Resets", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.resets)
            SettingsScreen()
                .environment(\.claudeAnimationVisible, claudeAnimationVisible && tab == .settings)
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .tint(UsageColors.ink)
        #if DEBUG
        .motionLogOverlay(visible: claudeAnimationVisible)
        .claudeStillSheet()
        #endif
    }
}

#if DEBUG
private extension View {
    func motionLogOverlay(visible: Bool = true) -> some View {
        overlay(alignment: .top) {
            if visible && MotionLog.isEnabled { MotionLogOverlay() }
        }
    }
}

/// Observed separately so publishing a note never re-evaluates the screens being logged.
private struct MotionLogOverlay: View {
    @ObservedObject private var log = MotionLog.shared

    var body: some View {
        Text(verbatim: log.text)
            .font(.caption2)
            .lineLimit(1)
            .accessibilityIdentifier("motion-log")
            .padding(4)
            .frame(maxWidth: .infinity)
            .background(UsageColors.ground)
            .allowsHitTesting(false)
    }
}
#endif
