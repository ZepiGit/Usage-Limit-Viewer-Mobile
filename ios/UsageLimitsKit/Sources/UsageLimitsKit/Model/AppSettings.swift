import Foundation

public enum OverviewLayout: String, Codable, Sendable {
    case list, dials
}

/// Everything the user can change.
///
/// One type rather than several stores so a single value can be persisted, passed to the
/// evaluator, and bound to by the settings screen without three of them drifting apart.
public struct AppSettings: Sendable, Equatable, Codable {

    /// Requested background refresh interval, in minutes. iOS decides when refreshes run.
    public var syncIntervalMinutes: Int

    public var notifications: NotificationSettings

    /// Records whether the user has dragged an account to change the order.
    /// The overview uses the stored order regardless of this flag.
    public var accountsManuallyOrdered: Bool

    /// Show the plan tier — Plus, Pro, Max — beside the provider name.
    public var showSubscriptionTier: Bool

    /// Retained for saved settings compatibility. Both Overview layouts always show reset stamps.
    public var showRenewalTime: Bool
    public var overviewLayout: OverviewLayout
    public var providerIcons: [String: String]

    /// Whether quota figures count down what is left or up what has been used.
    public var percentDisplay: PercentDisplay
    public var statusIconsEnabled: Bool
    /// The pinned accounts, each by `ProviderAccount.pinKey`: the account instance the pin was
    /// made for, so a pin whose removal never reached this file cannot select the same account
    /// added again. `PinnedAccounts.resolve` turns them into account ids.
    public var pinnedAccountKeys: [String]

    public static let minimumSyncIntervalMinutes = 5
    public static let defaultSyncIntervalMinutes = 30

    public init(
        syncIntervalMinutes: Int = AppSettings.defaultSyncIntervalMinutes,
        notifications: NotificationSettings = NotificationSettings(),
        accountsManuallyOrdered: Bool = false,
        showSubscriptionTier: Bool = true,
        showRenewalTime: Bool = false,
        overviewLayout: OverviewLayout = .list,
        providerIcons: [String: String] = [:],
        percentDisplay: PercentDisplay = .left,
        statusIconsEnabled: Bool = false,
        pinnedAccountKeys: [String] = []
    ) {
        self.syncIntervalMinutes = max(syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
        self.notifications = notifications
        self.accountsManuallyOrdered = accountsManuallyOrdered
        self.showSubscriptionTier = showSubscriptionTier
        self.showRenewalTime = showRenewalTime
        self.overviewLayout = overviewLayout
        self.providerIcons = providerIcons
        self.percentDisplay = percentDisplay
        self.statusIconsEnabled = statusIconsEnabled
        self.pinnedAccountKeys = pinnedAccountKeys
    }

    /// Decoded leniently, for the same reason as `NotificationSettings`: a missing key is a
    /// version difference, not a corrupt file, and treating it as corrupt resets everything the
    /// user chose. The supported minimum is applied to decoded intervals too.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let minutes = try container.decodeIfPresent(Int.self, forKey: .syncIntervalMinutes)
            ?? AppSettings.defaultSyncIntervalMinutes
        self.init(
            syncIntervalMinutes: minutes,
            notifications: try container.decodeIfPresent(
                NotificationSettings.self, forKey: .notifications) ?? NotificationSettings(),
            accountsManuallyOrdered: try container.decodeIfPresent(
                Bool.self, forKey: .accountsManuallyOrdered) ?? false,
            showSubscriptionTier: try container.decodeIfPresent(
                Bool.self, forKey: .showSubscriptionTier) ?? true,
            showRenewalTime: try container.decodeIfPresent(
                Bool.self, forKey: .showRenewalTime) ?? false,
            overviewLayout: (try? container.decode(String.self, forKey: .overviewLayout))
                .flatMap(OverviewLayout.init(rawValue:)) ?? .list,
            providerIcons: try container.decodeIfPresent([String: String].self, forKey: .providerIcons) ?? [:],
            // Decoded through the raw String, so an unknown stored name reads as the default
            // instead of failing the whole decode and resetting every preference.
            percentDisplay: PercentDisplay.fromLoose(
                raw: try container.decodeIfPresent(String.self, forKey: .percentDisplay)),
            statusIconsEnabled: try container.decodeIfPresent(Bool.self, forKey: .statusIconsEnabled) ?? false,
            pinnedAccountKeys: try container.decodeIfPresent([String].self, forKey: .pinnedAccountKeys) ?? [])
    }

    /// Apply initializer rules to a copy so normalization preserves every other preference.
    public func normalised() -> AppSettings {
        var copy = self
        copy.syncIntervalMinutes = max(syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
        return copy
    }

    /// How old a snapshot may be before this app stops trusting it.
    ///
    /// Derived rather than fixed — see `Severity.staleAfter(syncIntervalMinutes:)` for the
    /// setting that made a constant wrong.
    public var staleAfter: TimeInterval {
        Severity.staleAfter(syncIntervalMinutes: syncIntervalMinutes)
    }
}
