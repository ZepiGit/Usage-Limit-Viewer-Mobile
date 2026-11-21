import Foundation

/// A claimed opening has one placement opportunity and one shared elapsed presentation per account.
public struct HoorayPresentation: Equatable, Sendable {
    public static let duration: TimeInterval = 4
    public private(set) var opening = 0
    public private(set) var startedAt: [String: TimeInterval] = [:]
    private var pending = Set<String>()
    private var awaitingPlacement = false

    public init() {}

    public mutating func open(_ accounts: Set<String>) {
        stop()
        opening += 1
        pending = accounts
        awaitingPlacement = true
    }

    /// Called by the first ready view's actual layout, not by a timer or guessed frame count.
    public mutating func placed(visibleAccountIDs: Set<String>, at now: TimeInterval) {
        guard awaitingPlacement else { return }
        for id in pending where visibleAccountIDs.contains(id) { startedAt[id] = now }
        pending.removeAll()
        awaitingPlacement = false
    }

    public mutating func advance(to now: TimeInterval) {
        for (id, start) in startedAt where now - start >= Self.duration {
            startedAt.removeValue(forKey: id)
        }
    }

    public mutating func stop() {
        pending.removeAll()
        startedAt.removeAll()
        awaitingPlacement = false
    }
}
