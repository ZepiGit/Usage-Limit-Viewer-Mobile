#if DEBUG
import Foundation
import UsageLimitsKit
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Synthetic screenshot data goes through the production register, dated writer and scene claim.
enum HoorayOpeningFixture {
    static var isEnabled: Bool {
        UsageLimitsApp.isUITesting && ProcessInfo.processInfo.arguments.contains("-fixture-hooray")
    }

    static func container() -> UsageLimitsContainer? {
        guard isEnabled else { return nil }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("hooray-opening-fixture")
        return UsageLimitsContainer(directory: directory, credentials: InMemoryCredentialStore(), transport: NoNetwork())
    }

    @MainActor private static var seeded = false
    @MainActor static func prepare(_ container: UsageLimitsContainer) async throws {
        guard isEnabled, !seeded else { return }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("hooray-opening-fixture")
        try? FileManager.default.removeItem(at: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let now = Date()
        let account = ProviderAccount(id: "fixture-claude", provider: .claude, externalAccountID: "synthetic",
            email: nil, displayName: "Claude", plan: "Pro", credentialReference: "synthetic-no-key",
            createdAt: now.addingTimeInterval(-3_600), lastSuccessfulSync: nil)
        try await container.repository.upsert(account)
        for (fetched, reset, used) in [(-120.0, -60.0, 95.0), (0.0, 18_000.0, 30.0)] {
            let window = UsageWindow(id: "five-hour", label: "5h limit", category: .fiveHour,
                usedPercent: used, periodSeconds: 18_000, resetAt: now.addingTimeInterval(reset), exhausted: false)
            try await container.repository.record(.success(accountID: account.id, result: UsageResult(windows: [window])),
                at: now.addingTimeInterval(fetched))
        }
        var settings = AppSettings()
        settings.providerIcons[ProviderID.claude.rawValue] = "claudecode-color"
        settings.animateClaudeCodeIcon = !ProcessInfo.processInfo.arguments.contains("-hooray-animation-off")
        _ = try await container.save(settings: settings)
        seeded = true
    }

    private struct NoNetwork: HTTPTransport {
        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            throw URLError(.notConnectedToInternet)
        }
    }
}
#endif
