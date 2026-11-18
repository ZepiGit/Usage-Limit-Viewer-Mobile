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

    /// Credit passes do not overlap: a late balance could otherwise overwrite a newer one.
    private var running: (automatic: Bool, task: Task<Void, any Error>)?

    public init(
        providers: [CreditProviderID: any CreditProvider], credentials: any CredentialStore,
        repository: CreditRepository, now: @Sendable @escaping () -> Date = { Date() }
    ) {
        self.providers = providers
        self.credentials = credentials
        self.repository = repository
        self.now = now
    }

    public func sync(automatic: Bool = false) async throws {
        while let running {
            if automatic || !running.automatic { return try await running.task.value }
            // A manual request must get a pass that did not skip accounts on its behalf.
            _ = try? await running.task.value
            try Task.checkCancellation()
        }
        let task = Task {
            defer { running = nil }
            try await syncAll(automatic: automatic)
        }
        running = (automatic, task)
        try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }

    private func syncAll(automatic: Bool) async throws {
        try Task.checkCancellation()
        let accounts = await repository.accounts()
        try await withThrowingTaskGroup(of: Void.self) { group in
            for account in accounts {
                group.addTask { try await self.sync(account, automatic: automatic) }
            }
            try await group.waitForAll()
        }
    }

    private func sync(_ account: CreditAccount, automatic: Bool) async throws {
        let deadline = automatic ? await repository.automaticSyncNotBefore(accountID: account.id) : nil
        try Task.checkCancellation()
        if RateLimitCooldown.shouldSkip(automatic: automatic, until: deadline, now: now()) { return }

        // Best-effort, like the usage sink: an unwritable file must not fail the account.
        do {
            let balances = try await fetch(account)
            try? await repository.record(accountID: account.id, balances: balances, error: nil,
                at: now(), accountCreatedAt: account.createdAt)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            let time = now()
            let rateLimitedUntil: TimeInterval?
            if case .rateLimited(_, let retryAfter)? = error as? CreditError {
                rateLimitedUntil = RateLimitCooldown.deadline(from: time, retryAfter: retryAfter)
            } else if case .rateLimited(let retryAfter)? = error as? HTTPError {
                rateLimitedUntil = RateLimitCooldown.deadline(from: time, retryAfter: retryAfter)
            } else {
                rateLimitedUntil = nil
            }
            try? await repository.record(accountID: account.id, balances: nil, error: Self.message(for: error),
                at: time, rateLimitedUntil: rateLimitedUntil, accountCreatedAt: account.createdAt)
        }
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
