import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Runs a test with every concurrency job on the main actor's one serial queue, so "has this
/// task finished yet?" has an answer that does not depend on how the machine scheduled it.
///
/// On the shared thread pool, a task that is still waiting and a task that has finished but whose
/// waiter has not run yet look the same from outside, and a test asserting "not finished yet" can
/// only pick a time window and hope. Here every job the runtime would hand to that pool is queued
/// on the main actor instead, in order. `settle()` then lets everything runnable run until it
/// waits on something, so a task that is still unfinished afterwards is genuinely waiting.
///
/// Done through the runtime's global-enqueue hook, the same one Point-Free's
/// `withMainSerialExecutor` (swift-concurrency-extras) uses, written out here rather than added
/// as a dependency for one test file. The hook is process-wide; `run` restores the previous hook
/// when it ends. XCTest runs one test at a time in a process, so no other test's
/// jobs are rerouted while it is set. Only the immediate enqueue is rerouted: a `Task.sleep`
/// would still wake on the pool, and nothing the tests drive through this sleeps.
enum SerialScheduling {
    private typealias Original = @convention(thin) (UnownedJob) -> Void
    private typealias Hook = @convention(thin) (UnownedJob, Original) -> Void

    nonisolated(unsafe) private static let hook = dlsym(dlopen(nil, 0), "swift_task_enqueueGlobal_hook")!
        .assumingMemoryBound(to: Hook?.self)

    @MainActor
    static func run(_ body: @MainActor () async throws -> Void) async rethrows {
        let previous = hook.pointee
        hook.pointee = { job, _ in MainActor.shared.enqueue(job) }
        defer { hook.pointee = previous }
        try await body()
    }

    /// Lets every runnable job, and every job those make runnable, run to its next wait.
    ///
    /// Each yield puts the caller behind everything already queued, so every round runs all of it
    /// and moves each chain of work on by at least one step. A refresh pass through the container
    /// is a few dozen such steps; the bound is a number of turns, not an amount of time, and the
    /// same turns happen in the same order on every run.
    @MainActor
    static func settle() async {
        for _ in 0..<1_000 { await Task.yield() }
    }
}
