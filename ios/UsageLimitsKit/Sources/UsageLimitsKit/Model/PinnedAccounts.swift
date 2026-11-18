import Foundation

public enum PinnedAccounts {
    public static let limit = 3

    /// Usage account order wins over pin order; credits never enter this input list.
    public static func select(enabled: Bool, pinnedIDs: [String], usageAccountIDs: [String]) -> [String] {
        guard enabled else { return [] }
        var wanted = Set(pinnedIDs)
        var selected: [String] = []
        for id in usageAccountIDs where wanted.remove(id) != nil {
            selected.append(id)
            if selected.count == limit { break }
        }
        return selected
    }

    /// The ids of the accounts whose current instance a stored pin names (`ProviderAccount.pinKey`).
    /// A pin for an instance that is gone selects nothing, as a pin for an absent id never did.
    public static func resolve(keys: [String], accounts: [ProviderAccount]) -> [String] {
        let wanted = Set(keys)
        return accounts.filter { wanted.contains($0.pinKey) }.map(\.id)
    }
}
