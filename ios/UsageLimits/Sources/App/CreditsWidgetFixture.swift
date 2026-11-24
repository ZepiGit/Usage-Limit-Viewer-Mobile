#if DEBUG
import Foundation
import SwiftUI
import UsageLimitsKit

struct CreditsWidgetFixture: View {
    private enum Reading: String, CaseIterable {
        case unread, unreadable, refreshFailed

        var title: String {
            switch self {
            case .unread: "No snapshot"
            case .unreadable: "Failed without balance"
            case .refreshFailed: "Failed with cached balance"
            }
        }
    }

    @State private var reading = Reading.unread

    private static let date = Date(timeIntervalSince1970: 1_789_206_060)
    private static let staleAfter: TimeInterval = 3600
    private static let detail = "The prepaid balance, its currency, and the account's billing details were missing from the provider response. The remaining fields did not identify a paid balance or provide a timestamp that could be used to verify when the remaining amount was reported."

    var body: some View {
        VStack(spacing: 16) {
            CreditsSmallContent(credit: credit, date: Self.date, staleAfter: Self.staleAfter, ink: .light)
                .frame(width: 158, height: 158)
                .background(UsageColors.ground)
                .environment(\.usageAppearance, .widget)
                .dynamicTypeSize(DynamicTypeSize.accessibility5)

            ForEach(Reading.allCases, id: \.self) { option in
                Button(option.title) { reading = option }
                    .accessibilityIdentifier("credits-fixture-\(option.rawValue)")
            }
        }
    }

    private var credit: GlanceCredit {
        let account = CreditAccount(id: "deepseek", provider: .deepseek, label: "DeepSeek",
            credentialReference: "unused", sortOrder: 0, createdAt: Self.date.addingTimeInterval(-10800))
        let snapshot: CreditSnapshot? = if reading == .unread {
            nil
        } else {
            CreditSnapshot(accountID: account.id,
                balances: reading == .refreshFailed ? [CreditBalance(currency: "USD", amount: Decimal(318) / 100, total: 20)] : [],
                fetchedAt: Self.date.addingTimeInterval(-7200), lastAttemptAt: Self.date, status: .failed,
                error: CreditError.malformed(Self.detail).errorDescription)
        }
        return GlanceCredit(entry: CreditEntry(account: account, snapshot: snapshot),
            now: Self.date, staleAfter: Self.staleAfter)
    }
}
#endif
