import Foundation

public enum OverviewDestination: Equatable, Sendable {
    case overview
    case refreshOverview
    case account(String)
    case unavailable(String)
}

/// A nil register means the cache has not answered, not that the account is missing.
public struct OverviewRouter: Sendable {
    private var pendingAccountID: String?

    public init() {}

    public mutating func open(_ url: URL, loadedAccountIDs: [String]?) -> OverviewDestination? {
        guard url.scheme == "usagelimits" else { return nil }
        switch url.host {
        case "glance":
            pendingAccountID = nil
            return .overview
        case "refresh":
            pendingAccountID = nil
            return .refreshOverview
        case "account":
            guard let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath,
                path.first == "/", let id = path.dropFirst().removingPercentEncoding,
                !id.isEmpty, !id.contains("/") else { return nil }
            return notification(accountID: id, loadedAccountIDs: loadedAccountIDs)
        default:
            return nil
        }
    }

    public mutating func notification(accountID: String, loadedAccountIDs: [String]?) -> OverviewDestination? {
        guard !accountID.isEmpty else { return nil }
        pendingAccountID = accountID
        guard let loadedAccountIDs else { return nil }
        return accountsLoaded(loadedAccountIDs)
    }

    public mutating func accountsLoaded(_ accountIDs: [String]) -> OverviewDestination? {
        guard let id = pendingAccountID else { return nil }
        pendingAccountID = nil
        return accountIDs.contains(id) ? .account(id) : .unavailable(id)
    }
}
