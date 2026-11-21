import Foundation

public struct PinnedAccountEntry: Sendable, Equatable, Identifiable {
    public let account: GlanceAccount
    public let presentation: WindowPresentation
    public let content: StatusIconContent
    public let identity: SameProviderIdentity
    public var id: String { account.id }

    public init(account: GlanceAccount, presentation: WindowPresentation, content: StatusIconContent,
                identity: SameProviderIdentity = .providerOnly) {
        self.account = account
        self.presentation = presentation
        self.content = content
        self.identity = identity
    }
}

/// Derived from one published snapshot, at the date WidgetKit will display the entry.
public struct PinnedAccountsCapture: Sendable, Equatable {
    public let date: Date
    public let accounts: [PinnedAccountEntry]
    public let outcome: ConfiguredSelectionOutcome

    /// When WidgetKit should rebuild the timeline. A snapshot unreadable before first unlock asks
    /// again after 15 minutes; anything else waits for the routine 4 hours or the next app
    /// publication, because only the app can change it.
    public var reloadInterval: TimeInterval {
        outcome == .sourceUnavailable ? 15 * 60 : 4 * 60 * 60
    }

    public static func capture(source: GlanceSnapshotCodec.Load, at date: Date) -> PinnedAccountsCapture {
        switch source {
        case .loaded(let snapshot): return capture(snapshot: snapshot, at: date)
        case .unreadable:
            return PinnedAccountsCapture(date: date, accounts: [], outcome: .sourceUnavailable)
        case .corrupt:
            return PinnedAccountsCapture(date: date, accounts: [], outcome: .corruptSource)
        case .empty, .missing:
            return PinnedAccountsCapture(date: date, accounts: [], outcome: .noAccounts)
        }
    }

    public static func capture(snapshot: GlanceSnapshot, at date: Date) -> PinnedAccountsCapture {
        let selected = selectedSnapshot(snapshot, at: date)
        let identities = OverviewProjection.sameProviderIdentities(in: selected.accounts.map {
            OverviewProjection.Entry(account: $0, headlineWindowID: nil)
        }, label: { $0.accountLabel ?? $0.subtitle })
        let entries = selected.accounts.map { account in
            let headline = account.representative(at: date)
            let presentation = account.presentation(of: headline, at: date,
                staleAfter: snapshot.staleAfter, mode: snapshot.percentDisplay)
            let content = StatusIconContent.of(headline: headline == nil ? nil : presentation,
                validity: account.readingValidity(of: headline, at: date, staleAfter: snapshot.staleAfter),
                needsReauthentication: account.connectionStatus == .reconnectRequired)
            return PinnedAccountEntry(account: account, presentation: presentation, content: content,
                identity: identities[account.id] ?? .providerOnly)
        }
        return PinnedAccountsCapture(date: date, accounts: entries,
            outcome: entries.isEmpty ? .noAccounts : .ready)
    }

    public static func entryDates(snapshot: GlanceSnapshot, after now: Date) -> [Date] {
        [now] + selectedSnapshot(snapshot, at: now).presentationBoundaries(after: now)
    }

    private static func selectedSnapshot(_ snapshot: GlanceSnapshot, at date: Date) -> GlanceSnapshot {
        let ids = PinnedAccounts.select(enabled: snapshot.statusIconsEnabled,
            pinnedIDs: snapshot.pinnedAccountIDs, usageAccountIDs: snapshot.accounts.map(\.id))
        return snapshot.selecting(scope: .custom, now: date, customAccountIDs: ids)
    }
}
