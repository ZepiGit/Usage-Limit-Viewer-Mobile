import Foundation
import SwiftUI
import UsageLimitsKit

#if DEBUG
/// In-memory fixtures for real simulator screenshots. No account or credential is persisted.
enum MarketingDemo {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-marketing-demo")
    }

    /// Exercises the first-frame skeleton followed by loaded content, without opening storage.
    static var delaysLoad: Bool {
        ProcessInfo.processInfo.arguments.contains("-motion-delayed-demo")
    }

    // A fixed clock keeps relative and absolute reset labels consistent across every capture.
    static let now = Date(timeIntervalSince1970: 1_789_206_060) // 2026-09-12 09:41 UTC

    static let accounts: [AccountUsage] = {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-overview-empty-demo") { return [] }
        guard UsageLimitsApp.isUITesting && arguments.contains("-claude-mood-demo") else {
            return baseAccounts
        }
        return baseAccounts + [
            account(.claude, plan: "Max", windows: [
                window("5h limit", used: 5, duration: 18_000, resetIn: 600, exhausted: true)
            ], id: "demo-claude-exhausted"),
            account(.claude, plan: "Team", windows: [
                window("5h limit", used: 5, duration: 18_000, resetIn: 3_000)
            ], id: "demo-claude-dancing")
        ]
    }()

    private static let baseAccounts: [AccountUsage] = [
        account(.codex, plan: "Plus", windows: [
            window("5-hour", used: 28, duration: 18_000, resetIn: 8_100),
            window("Weekly", used: 56, duration: 604_800, resetIn: 273_600)
        ]),
        account(.claude, plan: "Pro", windows: [
            window("5-hour", used: 82, duration: 18_000, resetIn: 2_520),
            window("Weekly", used: 39, duration: 604_800, resetIn: 367_200)
        ]),
        account(.antigravity, plan: "Pro", windows: [
            window("5-hour", used: 14, duration: 18_000, resetIn: 11_400),
            window("Weekly", used: 47, duration: 604_800, resetIn: 201_600)
        ]),
        account(.xai, plan: "SuperGrok", windows: [
            window("Weekly", used: 33, duration: 604_800, resetIn: 108_000),
            window("Monthly", used: 18, duration: 2_592_000, resetIn: 1_555_200)
        ]),
        account(.kimi, plan: "Code", windows: [
            window("5-hour", used: 42, duration: 18_000, resetIn: 5_100),
            window("Weekly", used: 24, duration: 604_800, resetIn: 432_000)
        ])
    ]

    static let credits: [CreditEntry] =
        UsageLimitsApp.isUITesting && ProcessInfo.processInfo.arguments.contains("-overview-credits-demo")
        ? OverviewCreditSamples.entries(at: now) : []

    static var startsWithCompactCredits: Bool {
        UsageLimitsApp.isUITesting && ProcessInfo.processInfo.arguments.contains("-overview-compact-credits-demo")
    }

    private static func account(
        _ provider: ProviderID, plan: String, windows: [UsageWindow], id: String? = nil
    ) -> AccountUsage {
        let id = id ?? "demo-\(provider.rawValue)"
        let expired = provider == .codex && ProcessInfo.processInfo.arguments.contains("-overview-expired-demo")
        return AccountUsage(
            account: ProviderAccount(
                id: id, provider: provider, externalAccountID: id, email: nil,
                displayName: id == "demo-\(provider.rawValue)" ? provider.displayName : "\(provider.displayName) \(plan)", plan: plan,
                credentialReference: "", createdAt: now, lastSuccessfulSync: now),
            snapshot: UsageSnapshot(
                accountID: id, fetchedAt: now, status: expired ? .failed : .ok, windows: windows,
                errorMessage: expired ? NotificationEvaluator.signInExpiredMessage : nil,
                connectionStatus: expired ? .reconnectRequired : .connected))
    }

    private static func window(
        _ label: String, used: Double, duration: Int64, resetIn: TimeInterval, exhausted: Bool = false
    ) -> UsageWindow {
        UsageWindow(
            id: label, label: label, category: .from(periodSeconds: duration),
            usedPercent: used, periodSeconds: duration,
            resetAt: now.addingTimeInterval(resetIn), exhausted: exhausted)
    }
}

extension View {
    @ViewBuilder
    func claudeStillSheet() -> some View {
        if UsageLimitsApp.isUITesting && MarketingDemo.isEnabled &&
            ProcessInfo.processInfo.arguments.contains("-claude-still-sheet") {
            overlay { ClaudeStillSheet() }
        } else {
            self
        }
    }
}

private struct ClaudeStillSheet: View {
    // CH-3 slot sizes, side by side without scaling the renderer's output.
    private let slots = [16.0, 20.0, 28.0, 34.0, 36.0]
    private let moods = ClaudeCodeMood.allCases

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: "Claude Code · still poses")
                .font(.headline)
                .accessibilityIdentifier("claude-still-sheet")
            Text(verbatim: "Color · slot size in points")
                .font(.caption)
            HStack(spacing: 0) {
                Text(verbatim: "pt").frame(width: 24)
                ForEach(moods, id: \.self) { mood in
                    Text(verbatim: mood.rawValue.capitalized).frame(width: 49)
                }
            }
            .font(.system(size: 10))
            ForEach(slots, id: \.self) { size in
                HStack(spacing: 0) {
                    Text(verbatim: String(Int(size))).font(.caption2).frame(width: 24)
                    ForEach(moods, id: \.self) { mood in
                        ClaudeCodeStill(mood: mood, size: size, ink: UsageColors.data)
                            .frame(width: 49, height: 44)
                    }
                }
            }
        }
        .padding(16)
        .foregroundStyle(UsageColors.ink)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(UsageColors.surface)
    }
}
#endif

extension UsageStore {
    var isMarketingDemo: Bool {
        #if DEBUG
        MarketingDemo.isEnabled
        #else
        false
        #endif
    }
}

extension View {
    @ViewBuilder
    func marketingDemoLabel() -> some View {
        #if DEBUG
        if MarketingDemo.isEnabled {
            toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("Demo data")
                        .font(.caption)
                        .foregroundStyle(UsageColors.muted)
                        .accessibilityIdentifier("marketing-demo-label")
                }
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}
