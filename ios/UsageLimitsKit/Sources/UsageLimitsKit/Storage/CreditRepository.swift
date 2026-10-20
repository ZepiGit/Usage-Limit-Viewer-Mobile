import Foundation

/// The credit accounts and the last balance read for each, in `credits.json` beside
/// `accounts.json` in the shared container.
///
/// Same rules as `AccountRepository`: no key material (accounts name a keychain item), atomic
/// writes the widget process can race, a file that exists but will not read is a register this
/// build must not overwrite, and a failed sync keeps the previous balances.
public actor CreditRepository {

    public static let fileName = "credits.json"

    private struct Stored: Codable, Sendable {
        var account: CreditAccount
        var snapshot: CreditSnapshot?
    }

    private let fileURL: URL
    private var records: [String: Stored] = [:]
    private var isLoaded = false
    private var lastLoadFailure: AccountStoreError?

    /// Does no I/O; the first call that needs the file reads it on this actor.
    public init(directory: URL, fileName: String = CreditRepository.fileName) {
        fileURL = directory.appendingPathComponent(fileName)
    }

    private func ensureLoaded() {
        guard !isLoaded else { return }
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch {
            if FileManager.default.fileExists(atPath: fileURL.path) {
                lastLoadFailure = .unreadable
            } else {
                records = [:]
                isLoaded = true
                lastLoadFailure = nil
            }
            return
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let stored = try? decoder.decode([Stored].self, from: data) else {
            lastLoadFailure = .corrupt
            return
        }
        records = Dictionary(stored.map { ($0.account.id, $0) }, uniquingKeysWith: { _, later in later })
        isLoaded = true
        lastLoadFailure = nil
    }

    private func requireLoaded() throws {
        ensureLoaded()
        if let reason = lastLoadFailure { throw reason }
    }

    private func ordered() -> [Stored] {
        records.values.sorted {
            ($0.account.sortOrder, $0.account.createdAt, $0.account.id)
                < ($1.account.sortOrder, $1.account.createdAt, $1.account.id)
        }
    }

    // MARK: Reading

    public func entries() -> [CreditEntry] {
        ensureLoaded()
        return ordered().map { CreditEntry(account: $0.account, snapshot: $0.snapshot) }
    }

    public func accounts() -> [CreditAccount] {
        entries().map(\.account)
    }

    // MARK: Writing

    /// Adds or replaces an account. The snapshot survives a re-save unless one is given.
    public func upsert(_ account: CreditAccount, snapshot: CreditSnapshot? = nil) throws {
        try requireLoaded()
        var updated = records
        let sortOrder = updated[account.id]?.account.sortOrder ?? ((updated.values.map(\.account.sortOrder).max() ?? -1) + 1)
        let placed = CreditAccount(
            id: account.id, provider: account.provider, label: account.label,
            credentialReference: account.credentialReference, attributes: account.attributes,
            sortOrder: sortOrder, createdAt: updated[account.id]?.account.createdAt ?? account.createdAt)
        updated[account.id] = Stored(account: placed, snapshot: snapshot ?? updated[account.id]?.snapshot)
        try persist(updated)
    }

    public func remove(id: String) throws {
        try requireLoaded()
        var updated = records
        updated[id] = nil
        try persist(updated)
    }

    /// Records one sync. A failure keeps the previous balances and their original `fetchedAt`
    /// and only adds the error: yesterday's number marked stale beats an empty card.
    public func record(accountID: String, balances: [CreditBalance]?, error: String?, at time: Date) throws {
        try requireLoaded()
        var updated = records
        guard var stored = updated[accountID] else { return }
        if let balances {
            stored.snapshot = CreditSnapshot(accountID: accountID, balances: balances, fetchedAt: time)
        } else {
            let previous = stored.snapshot
            stored.snapshot = CreditSnapshot(
                accountID: accountID, balances: previous?.balances ?? [],
                fetchedAt: previous?.fetchedAt ?? time, lastAttemptAt: time,
                status: .failed, error: error)
        }
        updated[accountID] = stored
        try persist(updated)
    }

    /// Writes first, commits to memory after, so a failed write never leaves the UI ahead of disk.
    private func persist(_ updated: [String: Stored]) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        let list = updated.values.sorted {
            ($0.account.sortOrder, $0.account.createdAt, $0.account.id)
                < ($1.account.sortOrder, $1.account.createdAt, $1.account.id)
        }
        try encoder.encode(list).write(to: fileURL, options: ContainerFile.writingOptions)
        records = updated
    }
}
