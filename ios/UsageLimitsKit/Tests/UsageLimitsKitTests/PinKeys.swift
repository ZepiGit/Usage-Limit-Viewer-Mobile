@testable import UsageLimitsKit

extension UsageLimitsContainer {
    /// The settings value that pins `ids`: each account's current pin key, as the Settings
    /// screen stores it.
    func pinKeys(_ ids: [String]) async -> [String] {
        let accounts = await usage().map(\.account)
        return ids.map { id in accounts.first { $0.id == id }?.pinKey ?? id }
    }
}
