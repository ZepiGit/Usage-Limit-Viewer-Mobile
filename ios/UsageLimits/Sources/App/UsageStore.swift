import Combine
import Foundation
import UsageLimitsKit

/// The app's single source of truth for what the screens render.
///
/// `@MainActor` on the whole type rather than on individual mutations: every property here is
/// read by SwiftUI during layout, so anything that publishes from a background thread is a
/// crash waiting for a slow network. The fetching itself is `async` and hops off the main
/// actor inside the provider clients.
@MainActor
final class UsageStore: ObservableObject {

    @Published private(set) var accounts: [AccountUsage] = [] {
        didSet { if !isMarketingDemo { updatePresentationClock() } }
    }
    /// Prepaid balances, kept apart from the usage accounts: no windows, no resets.
    @Published private(set) var credits: [CreditEntry] = []
    private var settingsWrite: Task<Void, Never>?
    #if DEBUG
    /// Lets UI tests terminate only after the normal settings save has finished.
    @Published private(set) var uiTestSavedSettings: AppSettings?
    @Published private(set) var uiTestSettingsLoadStage: String?
    private var settingsLoadGate: CheckedContinuation<Void, Never>?

    func releaseSettingsLoadGate() {
        uiTestSettingsLoadStage = nil
        settingsLoadGate?.resume()
        settingsLoadGate = nil
    }

    private func holdSettingsLoad(at stage: String) async {
        let arguments = ProcessInfo.processInfo.arguments
        guard UsageLimitsApp.isUITesting,
              (stage == "cached-usage" ? arguments.contains("-fixture-retained-scoped")
                : arguments.contains("-fixture-settings-gates")) else { return }
        await withCheckedContinuation { continuation in
            settingsLoadGate = continuation
            uiTestSettingsLoadStage = stage
            SettingsTrace.record("load gate held stage=\(stage)")
        }
    }
    private var settingsTraceSource = "control edit"
    private var settingsTraceSaveID = 0
    #endif

    /// Writable, because the settings screen binds straight to it.
    ///
    /// Saved on every change rather than on leaving the screen: a toggle the user flips and then
    /// force-quits over must still be the setting they get back. `didSet` fires for a write
    /// through the binding chain too, since mutating a nested struct rewrites the whole value.
    @Published var settings = AppSettings() {
        didSet {
            guard settingsLoaded || isLoadingSettings else {
                settings = oldValue
                #if DEBUG
                SettingsTrace.record("settings edit rejected reason=not-loaded")
                #endif
                return
            }
            #if DEBUG
            SettingsTrace.record(
                "settings didSet source=\(settingsTraceSource) loading=\(isLoadingSettings) "
                    + "changed={\(Self.traceSettings(settings, changedFrom: oldValue))}")
            #endif
            if settings.staleAfter != oldValue.staleAfter { updatePresentationClock() }
            guard settings != oldValue, !isLoadingSettings else {
                #if DEBUG
                SettingsTrace.record("settings save skipped reason=\(isLoadingSettings ? "apply" : "unchanged")")
                #endif
                return
            }
            let previous = settingsWrite
            #if DEBUG
            settingsTraceSaveID += 1
            let saveID = settingsTraceSaveID
            SettingsTrace.record("settings save queued id=\(saveID)")
            #endif
            // Queued saves also adopt pins removed while they were waiting.
            settingsWrite = Task { [weak self, container] in
                #if DEBUG
                SettingsTrace.record("settings save task start id=\(saveID)")
                #endif
                await previous?.value
                guard let container, let value = self?.settings else {
                    #if DEBUG
                    SettingsTrace.record("settings save finish id=\(saveID) result=skipped missing=container-or-store")
                    #endif
                    return
                }
                #if DEBUG
                SettingsTrace.record("settings save value read id=\(saveID) value={\(Self.traceSettings(value))}")
                #endif
                // Reflect the persisted value, which may clamp the sync interval, only if no newer
                // edit has landed. An out-of-order save acknowledgement must not revert later edits.
                if let stored = try? await container.save(settings: value) {
                    if self?.settings == value {
                        #if DEBUG
                        self?.settingsTraceSource = "acknowledgement apply"
                        SettingsTrace.record("settings save acknowledgement apply id=\(saveID)")
                        #endif
                        self?.applyLoaded(stored)
                        #if DEBUG
                        self?.settingsTraceSource = "control edit"
                        #endif
                    } else {
                        #if DEBUG
                        SettingsTrace.record("settings save acknowledgement skipped id=\(saveID) reason=newer-edit-or-store-gone")
                        #endif
                    }
                    #if DEBUG
                    if UsageLimitsApp.isUITesting { self?.uiTestSavedSettings = stored }
                    SettingsTrace.record("settings save persisted id=\(saveID) value={\(Self.traceSettings(stored))}")
                    #endif
                } else {
                    #if DEBUG
                    SettingsTrace.record("settings save failed id=\(saveID)")
                    #endif
                }
                BackgroundRefresh.schedule(after: value.syncIntervalMinutes)
                #if DEBUG
                SettingsTrace.record("settings save task finish id=\(saveID)")
                #endif
            }
        }
    }

    /// Suppresses the save that would otherwise fire when the stored settings are read back in.
    private var isLoadingSettings = false

    /// Dismisses whatever `lastError` is currently reporting.
    ///
    /// Needed because the alert that shows it has to be able to close: a binding derived from a
    /// non-nil check needs somewhere to write `false` back to.
    func clearError() { lastError = nil }
    @Published private(set) var isRefreshing = false
    /// Whether the cache has been read once. Until then an empty account list means "nothing has
    /// asked yet", not "no accounts", and the screens draw a skeleton instead of an empty state.
    @Published private(set) var hasLoaded = false
    @Published private(set) var settingsLoaded = false
    @Published private(set) var lastError: String?
    /// Whether the account register is on this device and could not be read.
    ///
    /// Apart from `lastError`, because it is a state and not a report: the empty account list
    /// that comes with it does not mean the accounts are gone, the screen that would otherwise
    /// say so words it itself, and a later read that succeeds ends it without anyone
    /// dismissing anything.
    @Published private(set) var registerUnreadable = false

    @Published private(set) var now = Date()
    private(set) var timeZone = TimeZone.current
    var presentationCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }
    private var clock: Task<Void, Never>?
    let hoorayPlayback = HoorayPlayback()
    @Published private(set) var openingClaimed = false
    private lazy var openings = AppOpeningCoordinator(
        claim: { [weak self] in
            guard let self else { return [] }
            #if DEBUG
            if let container = self.container { try await HoorayOpeningFixture.prepare(container) }
            #endif
            await self.load()
            guard let container = self.container else { return [] }
            return try await container.claimPendingResetConfirmations()
        },
        present: { [weak self] in
            self?.hoorayPlayback.open($0)
            self?.openingClaimed = true
        },
        refresh: { [weak self] in await self?.refresh(automatic: true) },
        interrupt: { [weak self] in
            self?.openingClaimed = false
            self?.hoorayPlayback.stop()
        },
        failed: { [weak self] in self?.lastError = $0.localizedDescription })

    func waitForOpening() async { await openings.task?.value }

    func sceneChanged(_ phase: AppOpeningCoordinator.Phase) {
        openings.sceneChanged(phase)
        if phase == .active { updatePresentationClock() }
    }

    /// Built lazily and kept, because it owns the credential store and the account cache: a
    /// second one would be a second set of in-flight refresh locks, and the whole point of that
    /// lock is that there is exactly one per credential reference.
    private let container: UsageLimitsContainer?

    init(container: UsageLimitsContainer? = UsageStore.sharedContainer) {
        self.container = container

        #if DEBUG
        SettingsTrace.record("settings init source=defaults value={\(Self.traceSettings(settings))} container=\(container == nil ? "nil" : "present")")
        if MarketingDemo.isEnabled {
            SettingsTrace.record("demo init delayedLoad=\(MarketingDemo.delaysLoad) compactCredits=\(MarketingDemo.startsWithCompactCredits)")
            now = MarketingDemo.now
            timeZone = TimeZone(secondsFromGMT: 0)!
            if MarketingDemo.startsWithCompactCredits || SettingsTrace.gapVariant == "still" {
                var sampleSettings = settings
                if MarketingDemo.startsWithCompactCredits {
                    sampleSettings.overviewCreditLayout = .compact
                    sampleSettings.overviewShowFirst = .creditAccounts
                }
                if SettingsTrace.gapVariant == "still" {
                    sampleSettings.animateClaudeCodeIcon = false
                }
                settingsTraceSource = "demo initialization"
                applyLoaded(sampleSettings)
                settingsTraceSource = "control edit"
            }
            settingsLoaded = true
            if !MarketingDemo.delaysLoad { finishDemoLoad() }
            return
        }
        #endif

        updatePresentationClock()
    }

    deinit { clock?.cancel() }

    func updatePresentationClock() {
        guard !isMarketingDemo else {
            #if DEBUG
            SettingsTrace.record("presentation clock rearm skipped reason=demo")
            #endif
            return
        }
        clock?.cancel()
        timeZone = .current
        let date = Date()
        now = date
        let boundary = glance.nextOverviewUpdate(after: date, in: timeZone)
        // Countdown text still ticks between presentation boundaries.
        let delay = min(30, max(0, boundary.timeIntervalSinceNow))
        #if DEBUG
        SettingsTrace.record("presentation clock rearm delay=\(delay) now=\(date.timeIntervalSince1970) boundary=\(boundary.timeIntervalSince1970)")
        #endif
        clock = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) }
            catch { return }
            #if DEBUG
            SettingsTrace.record("presentation clock scheduled tick store=\(self == nil ? "gone" : "present")")
            #endif
            self?.updatePresentationClock()
        }
    }

    #if DEBUG
    var hasUnreadableRegisterFixture: Bool { RegisterFixture.isUnreadable }

    func recoverUnreadableRegisterFixture() async {
        guard RegisterFixture.isUnreadable else { return }
        do {
            try RegisterFixture.replaceWithEmptyRegister()
            await load()
        } catch {
            lastError = error.localizedDescription
        }
    }

    func finishDemoLoad() {
        guard MarketingDemo.isEnabled, !hasLoaded else {
            SettingsTrace.record("demo load skipped enabled=\(MarketingDemo.isEnabled) hasLoaded=\(hasLoaded)")
            return
        }
        SettingsTrace.record("demo load begin")
        accounts = MarketingDemo.accounts
        credits = MarketingDemo.credits
        hasLoaded = true
        SettingsTrace.record("demo load hasLoaded=true")
    }
    #endif

    /// The in-memory snapshot in register order. Overview projects its rank separately.
    var glance: GlanceSnapshot {
        GlanceModel.build(
            accounts, now: now, scope: .allAccounts, staleAfter: settings.staleAfter, providerIcons: settings.providerIcons,
            percentDisplay: settings.percentDisplay,
            statusIconsEnabled: settings.statusIconsEnabled,
            pinnedAccountIDs: PinnedAccounts.resolve(keys: settings.pinnedAccountKeys, accounts: accounts.map(\.account)))
    }

    func notificationsEnabled(accountID: String) -> Bool {
        !settings.notifications.mutedAccountIDs.contains(accountID)
    }

    func setNotifications(enabled: Bool, accountID: String) {
        // Assigning the whole struct, because `settings`' `didSet` is what saves: mutating the
        // nested set through a binding rewrites the whole value anyway, and doing it explicitly
        // keeps the one save path visible.
        var updated = settings
        if enabled {
            updated.notifications.mutedAccountIDs.remove(accountID)
        } else {
            updated.notifications.mutedAccountIDs.insert(accountID)
        }
        settings = updated
    }

    /// Every window that has a reset time, soonest first — the Resets screen.
    var upcomingResets: [(account: ProviderAccount, window: UsageWindow)] {
        accounts
            .flatMap { usage in
                (usage.snapshot?.windows ?? [])
                    .filter { $0.resetAt != nil }
                    .map { (usage.account, $0) }
            }
            .sorted { ($0.1.resetAt ?? .distantFuture) < ($1.1.resetAt ?? .distantFuture) }
    }

    /// Shared with the widget extension, which names the same group.
    ///
    /// The widget is a separate process with no access to the app's memory, so a file in this
    /// container is the entirety of what the home screen knows. `UsageLimitsContainer` writes it
    /// from the same `GlanceModel` call this screen renders — one derivation, one answer, rather
    /// than the app and the widget each deciding what "most critical" means.
    nonisolated static let appGroupID = "group.com.usagelimits.shared"

    /// Loads whatever the last run left behind, before any network is touched.
    ///
    /// A cold launch that showed an empty screen until a refresh came back would look like "you
    /// have no accounts" for as long as the slowest provider took — on a bad connection, for
    /// ever. The cache is what the app knew when it was last open, which is the right thing to
    /// render while the real answer is on its way.
    func load() async {
        #if DEBUG
        SettingsTrace.record("load begin demo=\(MarketingDemo.isEnabled) hasLoaded=\(hasLoaded)")
        if MarketingDemo.isEnabled {
            SettingsTrace.record("load skipped reason=demo hasLoaded=\(hasLoaded)")
            return
        }
        #endif
        defer {
            hasLoaded = true
            #if DEBUG
            SettingsTrace.record("load hasLoaded=true")
            #endif
        }
        guard let container else {
            settingsLoaded = true
            #if DEBUG
            SettingsTrace.record("load skipped reason=nil-container")
            #endif
            // Said here as well as in `refresh`, because a cold launch renders this screen before
            // anything is refreshed: without it the user sees an empty account list and the
            // explanation exists only behind a pull they have no reason to perform.
            lastError = String(localized: "This build cannot reach its shared storage.")
            return
        }
        // Only the first load installs preferences; subsequent cache loads cannot replace edits.
        if !settingsLoaded {
            #if DEBUG
            await holdSettingsLoad(at: "before-read")
            SettingsTrace.record("load settings read begin")
            #endif
            let loadedSettings = await container.settings()
            #if DEBUG
            SettingsTrace.record("load settings read returned value={\(Self.traceSettings(loadedSettings))}")
            await holdSettingsLoad(at: "before-apply")
            settingsTraceSource = "load apply"
            #endif
            if !settingsLoaded {
                applyLoaded(loadedSettings)
                settingsLoaded = true
            }
            #if DEBUG
            SettingsTrace.record("load settings installed")
            settingsTraceSource = "control edit"
            #endif
        }
        // Preparation can suspend on credentials. Preferences must already be installed so an
        // edit cannot save defaults over a previous run's choices while that work is pending.
        #if DEBUG
        SettingsTrace.record("load prepare begin")
        #endif
        try? await container.prepareForUse()

        credits = await container.credits()
        // The checked read, because a link opened on a cold launch is resolved against this
        // list: read as empty, an unreadable register would send it to "this account is no
        // longer on this device". No alert is raised for it. The detail says what happened
        // where the link landed, and an alert over that would say it twice.
        // A refresh can own the account list while this read still tells us whether storage is readable.
        do {
            let cached = try await container.checkedUsage()
            if !isRefreshing { accounts = cached }
            registerUnreadable = false
        } catch {
            if !isRefreshing { accounts = [] }
            registerUnreadable = true
        }
        #if DEBUG
        if UsageLimitsApp.isUITesting, ProcessInfo.processInfo.arguments.contains("-fixture-retained-scoped") {
            hasLoaded = true
            await holdSettingsLoad(at: "cached-usage")
        }
        #endif
    }

    /// Installs settings that came from storage, without treating the assignment as a change the
    /// user made — which would save them straight back and, worse, reschedule on every launch.
    private func applyLoaded(_ stored: AppSettings) {
        #if DEBUG
        SettingsTrace.record("settings apply source=\(settingsTraceSource) value={\(Self.traceSettings(stored))}")
        #endif
        isLoadingSettings = true
        settings = stored
        isLoadingSettings = false
    }

    #if DEBUG
    private static func traceSettings(_ value: AppSettings, changedFrom previous: AppSettings? = nil) -> String {
        var fields: [String] = []
        func field<Value: Equatable>(
            _ name: String, _ key: KeyPath<AppSettings, Value>,
            render: (Value) -> String = { String(describing: $0) }
        ) {
            if let previous, value[keyPath: key] == previous[keyPath: key] { return }
            fields.append("\(name)=\(render(value[keyPath: key]))")
        }
        field("syncIntervalMinutes", \.syncIntervalMinutes)
        field("accountsManuallyOrdered", \.accountsManuallyOrdered)
        field("showSubscriptionTier", \.showSubscriptionTier)
        field("showRenewalTime", \.showRenewalTime)
        field("overviewLayout", \.overviewLayout)
        field("overviewCreditLayout", \.overviewCreditLayout)
        field("overviewShowFirst", \.overviewShowFirst)
        field("overviewShowCredits", \.overviewShowCredits)
        field("providerIcons.count", \.providerIcons) { String($0.count) }
        field("animateClaudeCodeIcon", \.animateClaudeCodeIcon)
        field("percentDisplay", \.percentDisplay)
        field("statusIconsEnabled", \.statusIconsEnabled)
        field("pinnedAccountKeys.count", \.pinnedAccountKeys) { String($0.count) }
        field("notifications.notifyBelow20Percent", \.notifications.notifyBelow20Percent)
        field("notifications.notifyBelow10Percent", \.notifications.notifyBelow10Percent)
        field("notifications.notifyOnExhausted", \.notifications.notifyOnExhausted)
        field("notifications.notifyOnResetCreditAvailable", \.notifications.notifyOnResetCreditAvailable)
        field("notifications.notifyOnAuthExpired", \.notifications.notifyOnAuthExpired)
        field("notifications.notifyOnResetApproaching", \.notifications.notifyOnResetApproaching)
        field("notifications.notifyOnResetCreditExpiring", \.notifications.notifyOnResetCreditExpiring)
        field("notifications.resetApproachingMinutes", \.notifications.resetApproachingMinutes)
        field("notifications.resetCreditExpiryLeadMinutes", \.notifications.resetCreditExpiryLeadMinutes)
        field("notifications.mutedAccountIDs.count", \.notifications.mutedAccountIDs) { String($0.count) }
        return fields.isEmpty ? "none" : fields.joined(separator: " ")
    }
    #endif

    /// Serialises the passes below. A request made while one is running gets one more pass after
    /// it rather than being dropped: the running pass read its account list when it started, so
    /// an account signed in since then is synced, published and evaluated for alerts only by a
    /// pass that starts later (#43). Lazy, because the pass captures `self`.
    private lazy var refreshes = RefreshCoalescer { [weak self] automatic in
        await self?.refreshPass(automatic: automatic)
    }

    /// Syncs every eligible account and waits for a pass that started after this call.
    func refresh(automatic: Bool = false) async {
        #if DEBUG
        if MarketingDemo.isEnabled { return }
        #endif
        guard container != nil else {
            // No shared container means the app group is missing from the entitlements, which is
            // a build fault rather than anything the user did — but it must still say so, since
            // the alternative is a screen that stays empty and explains nothing.
            lastError = String(localized: "This build cannot reach its shared storage.")
            return
        }
        await refreshes.request(automatic: automatic).value
    }

    /// One sync, publish and alert claim. Only `refreshes` calls this, so two never overlap.
    private func refreshPass(automatic: Bool) async {
        guard let container else { return }

        isRefreshing = true
        defer { isRefreshing = false }

        now = Date()
        #if DEBUG
        SettingsTrace.record("presentation clock refresh now=\(now.timeIntervalSince1970) automatic=\(automatic)")
        #endif
        do {
            // The container returns the usage as it stands after the sync, rather than leaving
            // this to re-read it: re-reading races the write that just happened.
            accounts = try await container.refresh(automatic: automatic)
            credits = await container.credits()
            lastError = nil

            // Posted from the foreground too, not only from the background task. A user who
            // opens the app and pulls to refresh should hear about a limit that has just run
            // out; the ledger is what stops them hearing it twice when the background task
            // reaches the same conclusion later.
            await NotificationScheduler.post(try await container.pendingNotifications(texts: LocalizedNotificationTexts()))
        } catch is CancellationError {
            // A closed screen no longer cancels a pass, since others may be waiting on it, but a
            // cancellation from below is still not a failure and not something to paint red.
        } catch {
            // Per-account failures never reach here — the engine records those on the accounts
            // themselves, which is where a user can act on them. This is the whole run failing.
            accounts = await container.usage()
            credits = await container.credits()
            lastError = error.localizedDescription
        }

        // The repository reads the register again on every call until one succeeds, so a pass
        // is where an unreadable register comes back, for instance once the device is unlocked.
        // The accounts are taken from the read that proved it, so the state never clears over a
        // list that an earlier, failed read left empty.
        if registerUnreadable, let recovered = try? await container.checkedUsage() {
            accounts = recovered
            registerUnreadable = false
        }

        // The container publishes the widget snapshot itself as part of a refresh, so the app
        // does not write the same file a second time from a second process-shared path.

        // Requested after every refresh, foreground ones included: a submitted request is
        // consumed when it runs, so an app that only asks from inside the background task gets
        // exactly one background refresh in its life.
        BackgroundRefresh.schedule(after: settings.syncIntervalMinutes)
    }

    /// Spends one Codex reset credit and republishes what the spend bought.
    ///
    /// Reported through `lastError` rather than thrown, because the only caller is a button: a
    /// spend the provider refuses has to say so on the screen the user is looking at.
    @Published private(set) var isRedeeming = false

    func redeemResetCredit(accountID: String) async {
        guard let container, !isRedeeming else { return }
        isRedeeming = true
        defer { isRedeeming = false }
        do {
            accounts = try await container.redeemResetCredit(accountID: accountID)
            lastError = nil
        } catch is CancellationError {
            // The screen was closed mid-spend. Whatever the provider answered will be reflected
            // by the next refresh.
        } catch {
            lastError = error.localizedDescription
        }
    }


    /// Disconnects an account and deletes the credentials this device holds for it.
    ///
    /// The container has done this, and been tested doing it, since before there was a screen
    /// that could ask — which meant a user could connect an account and then had no way to
    /// disconnect it. Deleting the app was the only route, and that leaves keychain items
    /// behind, so it was not even a good one.
    ///
    /// Reported through `lastError` rather than thrown: the caller is a button, and an account
    /// that could not be removed has to stay on screen saying so, so the user can try again.
    /// This removes what is stored HERE; it is not an OAuth revocation at the provider, and the
    /// confirmation text says so rather than promising something this app cannot do.
    ///
    /// Returns whether the account was removed, so the detail that asked can close instead of
    /// staying open on an account that is no longer there.
    @Published private(set) var isRemoving = false

    func removeAccount(accountID: String) async -> Bool {
        guard let container, !isRemoving else { return false }
        isRemoving = true
        defer { isRemoving = false }
        let previous = settingsWrite
        let removal = Task { @MainActor [weak self] () -> Bool in
            await previous?.value
            guard let self else { return false }
            let pinKey = self.accounts.first { $0.account.id == accountID }?.account.pinKey
            do {
                try await container.remove(id: accountID)
                var updated = self.settings
                updated.pinnedAccountKeys.removeAll { $0 == pinKey }
                #if DEBUG
                self.settingsTraceSource = "removal apply"
                #endif
                self.applyLoaded(updated)
                #if DEBUG
                self.settingsTraceSource = "control edit"
                #endif
                self.accounts = await container.usage()
                self.lastError = nil
                return true
            } catch {
                // Keep the account visible when removal fails.
                self.lastError = error.localizedDescription
                return false
            }
        }
        // The queue of settings writes waits for the removal, not for its answer.
        settingsWrite = Task { _ = await removal.value }
        return await removal.value
    }

    // MARK: - Credits

    /// The credit accounts as the widget draws them, aged at the store's clock.
    var creditGlance: [GlanceCredit] {
        GlanceModel.buildCredits(credits, now: now, staleAfter: settings.staleAfter)
    }

    /// Proves the key, then stores it. Throws so the sheet can show why it was refused.
    func addCreditAccount(
        provider: CreditProviderID, key: String, label: String, region: ProviderRegion?,
        teamID: String?, lowThreshold: Decimal?
    ) async throws {
        // The detail of "No balance could be read (…)", which the display translates whole
        // (`StoredMessage.noBalance`); only the detail is worded here.
        guard let container else {
            throw CreditError.unavailable(String(localized: "shared storage is missing"))
        }
        try await container.addCreditAccount(
            provider: provider, key: key, label: label, region: region, teamID: teamID, lowThreshold: lowThreshold)
        credits = await container.credits()
        BackgroundRefresh.schedule(after: settings.syncIntervalMinutes)
    }

    func updateCreditAccount(id: String, label: String, lowThreshold: Decimal?) async {
        guard let container else { return }
        do {
            try await container.updateCreditAccount(id: id, label: label, lowThreshold: lowThreshold)
            credits = await container.credits()
        } catch {
            lastError = error.localizedDescription
        }
    }

    func removeCreditAccount(id: String) async {
        guard let container else { return }
        do {
            try await container.removeCreditAccount(id: id)
            credits = await container.credits()
        } catch {
            // The row stays: the key is deleted first, so a failure leaves it retryable.
            lastError = error.localizedDescription
        }
    }

    /// The one container this process uses, or nil when the App Group entitlement is missing
    /// and there is nowhere shared to write.
    ///
    /// A single shared instance, because the app builds one at launch for the background task
    /// and the store takes one as a default argument — and two would mean two credential stores
    /// and two sets of in-flight refresh locks. That lock exists precisely so one rotating
    /// refresh token cannot be spent twice; duplicating the object that holds it defeats it.
    ///
    /// `nonisolated`, because a default argument is evaluated at the call site and the call site
    /// is a `@StateObject` initialiser whose isolation is not this type's to assume. The
    /// container is an actor, so it is safe to reach from anywhere.
    nonisolated static let sharedContainer: UsageLimitsContainer? = {
        #if DEBUG
        // Screenshot launches must never open the keychain, cached accounts or saved settings.
        if MarketingDemo.isEnabled { return nil }
        if let fixture = HoorayOpeningFixture.container() { return fixture }
        if let fixture = RegisterFixture.container() { return fixture }
        if let fixture = CreditRegisterFixture.container() { return fixture }
        if let fixture = SettingsFixture.container() { return fixture }
        #endif
        guard let directory = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID) else { return nil }
        return UsageLimitsContainer(
            directory: directory, credentials: KeychainCredentialStore())
    }()
}

#if DEBUG
/// A register file of the UI tests' own, read and written by the container the app ships.
///
/// Disconnect and an unreadable register are storage behaviour, so the tests that follow them
/// to the screen run the real `UsageLimitsContainer` rather than a store that pretends: over a
/// directory in the app's temporary folder, with credentials held in memory. Nothing here opens
/// the app group or the keychain. No credential is stored for the account, so its sync stops
/// at `credentialsAbsent` before there is a request to send, and the row reads as failed.
/// Only under `-ui-testing`, so a debug build started by hand cannot end up here.
private enum RegisterFixture {
    /// One connected account, `codex:fixture`, labelled "Codex".
    private static let seeded = "-fixture-register"
    /// A register that is present and does not decode.
    private static let unreadable = "-fixture-register-unreadable"
    private static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("register-fixture")
    private static let registerFile = directory.appendingPathComponent(AccountRepository.fileName)

    static var isUnreadable: Bool {
        UsageLimitsApp.isUITesting && ProcessInfo.processInfo.arguments.contains(unreadable)
    }

    static func replaceWithEmptyRegister() throws {
        try Data("[]".utf8).write(to: registerFile, options: .atomic)
    }

    static func container() -> UsageLimitsContainer? {
        let arguments = ProcessInfo.processInfo.arguments
        let retained = arguments.contains("-fixture-retained-scoped")
        guard UsageLimitsApp.isUITesting, isUnreadable || arguments.contains(seeded) || retained else { return nil }

        // One fixed place, emptied on every launch: each test starts from the register written
        // below, whatever the test before it removed, and no directory is left behind per run.
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        // Written as the file the repository reads, before anything reads it. The row is the
        // oldest shape the register has had, which the Kit's tests keep loadable.
        let quotaCase = arguments.firstIndex(of: "-fixture-retained-quota-case").flatMap {
            arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil
        }
        let globalUsed: String = switch quotaCase {
        case "medium-global": "70"
        case "low-global": "90"
        case "exhausted-global": "100"
        case "unknown-global": "null"
        default: "37"
        }
        let modelUsed: Int = switch quotaCase {
        case "medium-model": 70
        case "low-global", "exhausted-global": 37
        case nil: 100
        default: 90
        }
        let reading = retained ? """
            ,"snapshot":{"accountID":"codex:fixture","fetchedAt":"2026-09-01T00:00:00Z",
              "status":"failed","windows":[
                {"id":"global","label":"Weekly","category":"weekly","usedPercent":\(globalUsed),"exhausted":false,"scope":"global"},
                {"id":"model","label":"Model","category":"weekly","usedPercent":\(modelUsed),"exhausted":\(modelUsed == 100),"scope":"model"}]}
            """ : ""
        let register = isUnreadable ? "not json" : """
            [{"account": {"id": "codex:fixture", "provider": "codex", "externalAccountID": "fixture",
              "displayName": "Codex", "credentialReference": "codex:fixture",
              "createdAt": "2026-09-01T00:00:00Z"}\(reading)}]
            """
        try? Data(register.utf8).write(to: registerFile)

        return UsageLimitsContainer(directory: directory, credentials: InMemoryCredentialStore())
    }
}

private enum SettingsFixture {
    static func container() -> UsageLimitsContainer? {
        let arguments = ProcessInfo.processInfo.arguments
        guard UsageLimitsApp.isUITesting, arguments.contains("-fixture-settings") else { return nil }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("settings-fixture")
        if arguments.contains("-fixture-settings-reset") {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let settings = """
                {"syncIntervalMinutes":180,"showSubscriptionTier":false,"overviewLayout":"dials",
                 "overviewCreditLayout":"compact","overviewShowFirst":"creditAccounts",
                 "providerIcons":{"claude":"color"},"percentDisplay":"used",
                 "statusIconsEnabled":true,"pinnedAccountKeys":["preserved-pin"],
                 "notifications":{"notifyBelow20Percent":false,"mutedAccountIDs":["preserved-mute"]}}
                """
            try? Data(settings.utf8).write(to: directory.appendingPathComponent("settings.json"), options: .atomic)
        }
        return UsageLimitsContainer(directory: directory, credentials: InMemoryCredentialStore())
    }
}

private enum CreditRegisterFixture {
    static func container() -> UsageLimitsContainer? {
        let arguments = ProcessInfo.processInfo.arguments
        guard UsageLimitsApp.isUITesting, arguments.contains("-fixture-credits") else { return nil }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("credit-register-fixture")
        if arguments.contains("-fixture-credits-reset") {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let register = """
                [{"account":{"id":"credit:fixture","provider":"deepseek","label":"DeepSeek",
                  "credentialReference":"credit:fixture","attributes":{},"sortOrder":0,
                  "createdAt":"2026-09-01T00:00:00Z"},
                  "snapshot":{"accountID":"credit:fixture","fetchedAt":"2026-09-01T00:00:00Z",
                  "lastAttemptAt":"2026-09-01T00:00:00Z","status":"ok",
                  "balances":[{"currency":"USD","amount":"3.18"}]}}]
                """
            try? Data(register.utf8).write(to: directory.appendingPathComponent(CreditRepository.fileName))
        }
        return UsageLimitsContainer(directory: directory, credentials: InMemoryCredentialStore())
    }
}
#endif
