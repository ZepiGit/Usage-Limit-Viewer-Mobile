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
}
