import SwiftUI
import UsageLimitsKit

/// The same block order in Overview and its Settings preview.
struct OverviewBlocks<Usage: View, Credits: View>: View {
    let showCredits: Bool
    let showFirst: OverviewShowFirst
    @ViewBuilder var usage: Usage
    @ViewBuilder var credits: Credits

    var body: some View {
        if showCredits && showFirst == .creditAccounts { credits }
        usage
        if showCredits && showFirst == .usageProviders { credits }
    }
}

struct OverviewCredits: View {
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize

    let entries: [CreditEntry]
    let now: Date
    let staleAfter: TimeInterval
    let layout: OverviewCreditLayout
    var expanded = false
    var setExpanded: ((Bool) -> Void)?
    var add: (() -> Void)?
    var preview = false

    var body: some View {
        if layout == .compact && !entries.isEmpty && !expanded {
            let summary = OverviewCreditSummary(entries: entries, now: now, staleAfter: staleAfter)
            if let setExpanded {
                Button { setExpanded(true) } label: { compactRow(summary) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Credits · \(summary.count) balances"))
                    .accessibilityValue(summary.winner.map { String(localized: "Low balance: \(hint($0))") } ?? "")
                    .accessibilityHint("Expands the credit balances")
                    .accessibilityIdentifier("overview-credits-compact")
            } else {
                compactRow(summary)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("settings-credits-compact")
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ViewThatFits(in: .horizontal) {
                    HStack {
                        heading.fixedSize(horizontal: true, vertical: false)
                        Spacer(minLength: 8)
                        HStack { actions }.fixedSize(horizontal: true, vertical: false)
                    }
                    VStack(alignment: .leading, spacing: 4) { heading; actions }
                }
                if entries.isEmpty {
                    DashedBox {
                        Text("No prepaid balances tracked.").usageFont(.body).foregroundStyle(UsageColors.muted)
                    }
                } else {
                    ForEach(entries) { entry in
                        CreditCard(entry: entry, now: now, staleAfter: staleAfter, preview: preview)
                    }
                }
            }
        }
    }

    private var heading: some View {
        Text("Credits")
            .usageFont(preview ? .smallStrong : .section)
            .foregroundStyle(UsageColors.ink)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier(preview ? "settings-credits-heading" : "overview-credits-heading")
    }

    @ViewBuilder private var actions: some View {
        if expanded, let setExpanded {
            Button { setExpanded(false) } label: { Label("Collapse", systemImage: "chevron.up") }
                .buttonStyle(TextActionStyle())
                .accessibilityIdentifier("overview-credits-collapse")
        }
        if let add {
            Button(action: add) { Label("Add", systemImage: "plus") }
                .buttonStyle(TextActionStyle())
                .accessibilityLabel("Add a credit balance")
                .accessibilityIdentifier("overview-credits-add")
        }
    }

    private func compactRow(_ summary: OverviewCreditSummary) -> some View {
        HStack(alignment: .center, spacing: preview ? 8 : 12) {
            Image(systemName: "wallet.pass")
                .font(.system(size: preview ? 18 : 22))
                .foregroundStyle(UsageColors.muted)
                .accessibilityHidden(true)
            if typeSize >= .accessibility1 {
                stackedSummary(summary)
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        compactTitle(summary.count).fixedSize(horizontal: true, vertical: false)
                        Spacer(minLength: 0)
                        if let winner = summary.winner {
                            lowHint(winner).fixedSize(horizontal: true, vertical: false)
                        }
                    }
                    stackedSummary(summary)
                }
            }
            if !preview {
                Image(systemName: "chevron.right")
                    .usageFont(.bodyStrong)
                    .foregroundStyle(UsageColors.faint)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, preview ? 8 : 16)
        .padding(.vertical, preview ? 6 : 12)
        .frame(minHeight: preview ? nil : 44)
        .background(UsageColors.surface, in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
    }

    private func stackedSummary(_ summary: OverviewCreditSummary) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            compactTitle(summary.count)
            if let winner = summary.winner { lowHint(winner) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func compactTitle(_ count: Int) -> some View {
        Text("Credits · \(count) balances")
            .usageFont(preview ? .smallStrong : .body)
            .foregroundStyle(UsageColors.ink)
    }

    private func lowHint(_ winner: OverviewCreditSummary.Winner) -> some View {
        HStack(spacing: 6) {
            Circle().fill(UsageColors.warning).frame(width: preview ? 6 : 8, height: preview ? 6 : 8)
                .accessibilityHidden(true)
            Text(verbatim: hint(winner))
                .usageFont(preview ? .previewWindow : .small)
                .foregroundStyle(UsageColors.muted)
                .accessibilityLabel("Low balance: \(hint(winner))")
        }
    }

    private func hint(_ winner: OverviewCreditSummary.Winner) -> String {
        let balance = winner.balance
        return "\(winner.title) \(CreditFormat.string(balance.amount, currency: balance.currency, locale: locale))"
    }
}

/// Synthetic examples from the Overview hand-off, never the user's credit register.
enum OverviewCreditSamples {
    static func entries(at date: Date) -> [CreditEntry] {
        [entry(.deepseek, amount: "3.18", at: date),
         entry(.openrouter, amount: "12.40", at: date),
         entry(.moonshot, amount: "25.00", at: date.addingTimeInterval(-180))]
    }

    private static func entry(_ provider: CreditProviderID, amount: String, at date: Date) -> CreditEntry {
        let id = "demo-credit-\(provider.rawValue)"
        let account = CreditAccount(id: id, provider: provider, label: provider.displayName,
            credentialReference: "", createdAt: date)
        let balance = CreditBalance(currency: "USD", amount: Decimal(string: amount)!)
        return CreditEntry(account: account, snapshot: CreditSnapshot(accountID: id, balances: [balance], fetchedAt: date))
    }
}
