import Foundation

/// Runs refresh passes one at a time, and never loses a request that arrives during one.
///
/// A refresh reads its account list when it starts. A request made while one is running —
/// finishing a sign-in while the launch refresh or a pull-to-refresh is still waiting on a
/// provider — therefore cannot be answered by the pass already under way: the account it was
/// made for did not exist when that pass looked. Dropping such a request left a new account with
/// no reading, no widget data and no alert until something else happened to refresh (#43).
///
/// So a request during a pass queues exactly one more pass after it. Any number of requests
/// during the same pass share that one follow-up, because a single pass that starts after all of
/// them has seen everything they were asking about.
///
/// Each pass is its own task, and a caller gets the task of the pass that covers its request:
/// the one it started, or the follow-up it joined. A caller is therefore released as soon as
/// that pass has finished, so code that renders after `await` renders the result, and requests
/// that arrive during the follow-up queue a pass of their own instead of holding earlier callers
/// — a finished sign-in must not keep its sheet open for a widget link tapped later.
///
/// The passes run in tasks of their own rather than in the caller's, and a caller going away
/// does not cancel them: several callers may be waiting on the same pass, and the one whose
/// screen closed must not take the sync away from the one that is still asking.
public actor RefreshCoalescer {

    private let pass: @Sendable () async -> Void
    /// The pass in progress, or the one that just ended while its follow-up has yet to begin.
    private var running: Task<Void, Never>?
    /// The follow-up, joined by every request made while `running` has not ended. It becomes
    /// `running` when it begins, so a request after that queues the next one.
    private var queued: Task<Void, Never>?

    public init(_ pass: @escaping @Sendable () async -> Void) {
        self.pass = pass
    }

    /// Asks for a pass and returns the task of the pass that covers this request: it ends once a
    /// pass that started after the request has run in full. Await `value` to wait for it; the
    /// pass runs whether or not anybody does.
    @discardableResult
    public func request() -> Task<Void, Never> {
        if let queued { return queued }
        let previous = running
        let task = Task { await run(after: previous) }
        if previous == nil { running = task } else { queued = task }
        return task
    }

    /// One pass, begun only once the one before it has ended.
    ///
    /// Every state change happens on the actor with no suspension between the check and the
    /// write, so a request lands either before a pass begins and is covered by it, or after and
    /// gets a follow-up. There is no moment at which a request is accepted and then nobody runs
    /// it.
    private func run(after previous: Task<Void, Never>?) async {
        if let previous {
            await previous.value
            // Only one follow-up exists at a time, and it is never replaced before it begins, so
            // `queued` is this pass.
            running = queued
            queued = nil
        }
        await pass()
        // A follow-up that exists will take over `running` when it begins.
        if queued == nil { running = nil }
    }
}
