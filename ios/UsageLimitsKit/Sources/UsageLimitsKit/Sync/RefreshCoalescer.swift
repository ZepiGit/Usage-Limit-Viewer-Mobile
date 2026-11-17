import Foundation

/// Runs refresh passes one at a time, and never loses a request that arrives during one.
///
/// A refresh reads its account list when it starts. A request made while one is running —
/// finishing a sign-in while the launch refresh or a pull-to-refresh is still waiting on a
/// provider — therefore cannot be answered by the pass already under way: the account it was
/// made for did not exist when that pass looked. Dropping such a request left a new account with
/// no reading, no widget data and no alert until something else happened to refresh (#43).
///
/// So a request during a pass schedules exactly one more pass after it. Any number of requests
/// during the same pass share that one follow-up, because a single pass that starts after all of
/// them has seen everything they were asking about. Every caller waits until the passes that
/// cover its request have finished, so code that renders after `await` renders the result.
///
/// The passes run in a task of their own rather than in the caller's, and a caller going away
/// does not cancel them: several callers may be waiting on the same pass, and the one whose
/// screen closed must not take the sync away from the one that is still asking.
public actor RefreshCoalescer {

    private let pass: @Sendable () async -> Void
    /// The passes in progress, joined by every request made while it runs.
    private var running: Task<Void, Never>?
    /// Whether a request arrived that the pass now running may not have seen.
    private var isRequested = false

    public init(_ pass: @escaping @Sendable () async -> Void) {
        self.pass = pass
    }

    /// Asks for a pass and returns the task that ends once one has run in full after this
    /// request. Await `value` to wait for it; the pass runs whether or not anybody does.
    @discardableResult
    public func request() -> Task<Void, Never> {
        isRequested = true
        if let running { return running }
        let task = Task { await drain() }
        running = task
        return task
    }

    /// Runs passes until one finishes with no request outstanding.
    ///
    /// The check and the reset of `running` happen with no suspension between them, so a request
    /// either lands before the check and gets another pass, or after the reset and starts a new
    /// task. There is no gap in which it is recorded and then nobody runs it.
    private func drain() async {
        while isRequested {
            isRequested = false
            await pass()
        }
        running = nil
    }
}
