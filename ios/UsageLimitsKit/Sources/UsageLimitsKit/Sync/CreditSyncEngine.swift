import Foundation

/// Refreshes every credit account. Runs inside `UsageLimitsContainer.refresh()`, so a
/// background refresh updates balances with the usage windows.
///
/// Each account is isolated: one bad key becomes that account's error line and nothing else's.
/// There is no token refresh, a pasted key either works or it does not. Cancellation is the
/// only error that escapes, as in `SyncEngine`.
public actor CreditSyncEngine {

    private let providers: [CreditProviderID: any CreditProvider]
    private let credentials: any CredentialStore
    private let repository: CreditRepository
    private let now: @Sendable () -> Date

    /// A second refresh asked for while one runs joins it. Two overlapping runs could land out
    /// of order and let an older balance overwrite a newer one.
    private var running: Task<Void, any Error>?

    public init(
        providers: [CreditProviderID: any CreditProvider], credentials: any CredentialStore,
        repository: CreditRepository, now: @Sendable @escaping () -> Date = { Date() }
    ) {
        self.providers = providers
        self.credentials = credentials
        self.repository = repository
        self.now = now
    }

    public func sync() async throws {
        if let running { return try await running.value }
        let task = Task { try await self.syncAll() }
        running = task
        defer { running = nil }
        try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }

    private func syncAll() async throws {
        try Task.checkCancellation()
        let accounts = await repository.accounts()
        try await withThrowingTaskGroup(of: Void.self) { group in
            for account in accounts {
                group.addTask { try await self.sync(account) }
            }
            try await group.waitForAll()
        }
    }

    private func sync(_ account: CreditAccount) async throws {
        let balances: [CreditBalance]?
        let message: String?
        do {
            balances = try await fetch(account)
            message = nil
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            balances = nil
            message = Self.message(for: error)
        }
        // Best-effort, like the usage sink: an unwritable file must not fail the account.
        try? await repository.record(accountID: account.id, balances: balances, error: message, at: now())
    }

    /// Fetches with the stored key. Also what the add flow calls to prove a key first.
    private func fetch(_ account: CreditAccount) async throws -> [CreditBalance] {
        guard let provider = providers[account.provider] else {
            throw CreditError.unavailable("no client for \(account.provider.rawValue)")
        }
        guard let stored = try await credentials.load(reference: account.credentialReference) else {
            throw CreditError.unavailable("no key is stored for this account")
        }
        return try await provider.fetchBalances(key: stored.accessToken, attributes: account.attributes)
    }

    /// Only text the app built: never the key, a header or a payload.
    private static func message(for error: any Error) -> String {
        if let described = (error as? LocalizedError)?.errorDescription, !described.isEmpty { return described }
        if error is HTTPError { return "The provider could not be reached." }
        return "The key could not be read from the keychain."
    }
}
