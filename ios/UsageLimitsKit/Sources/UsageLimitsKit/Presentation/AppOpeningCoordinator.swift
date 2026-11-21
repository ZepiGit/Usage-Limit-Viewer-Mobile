import Foundation

/// One opening is initial active or active after background, never a transient inactive bounce.
@MainActor
public final class AppOpeningCoordinator {
    public enum Phase: Equatable, Sendable { case active, inactive, background }

    private let claim: @MainActor () async throws -> Set<String>
    private let present: @MainActor (Set<String>) -> Void
    private let refresh: @MainActor () async -> Void
    private let interrupt: @MainActor () -> Void
    private let failed: @MainActor (any Error) -> Void
    private var needsOpening = true
    private var generation = 0
    private var active = false
    public private(set) var task: Task<Void, Never>?

    public init(
        claim: @MainActor @escaping () async throws -> Set<String>,
        present: @MainActor @escaping (Set<String>) -> Void,
        refresh: @MainActor @escaping () async -> Void,
        interrupt: @MainActor @escaping () -> Void,
        failed: @MainActor @escaping (any Error) -> Void
    ) {
        self.claim = claim
        self.present = present
        self.refresh = refresh
        self.interrupt = interrupt
        self.failed = failed
    }

    public func sceneChanged(_ phase: Phase) {
        switch phase {
        case .background:
            needsOpening = true
            fallthrough
        case .inactive:
            active = false
            generation += 1
            interrupt()
        case .active:
            active = true
            guard needsOpening else { return }
            needsOpening = false
            generation += 1
            let current = generation
            let previous = task
            task = Task { [weak self, claim = self.claim, present = self.present,
                           refresh = self.refresh, failed = self.failed] in
                await previous?.value
                do {
                    let accounts = try await claim()
                    if self?.active == true && current == self?.generation && !Task.isCancelled { present(accounts) }
                    await refresh()
                } catch {
                    failed(error)
                }
            }
        }
    }
}
