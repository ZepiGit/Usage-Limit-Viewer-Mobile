import SwiftUI
import UsageLimitsKit

struct AccountDetailScreen: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @EnvironmentObject private var store: UsageStore

    let accountID: String
    var unavailable = false
    /// Called once this account has been disconnected, so whoever presented the detail closes
    /// it rather than leaving it open on an account that is no longer there.
    var onDisconnected: @MainActor () -> Void = {}

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if !unavailable, let usage = store.accounts.first(where: { $0.account.id == accountID }) {
                    AccountSummaryCard(usage: usage, now: store.now, onDisconnected: onDisconnected)
                        .id(usage.account.id)
                } else if !store.hasLoaded {
                    AccountSkeletonCard()
                } else if store.registerUnreadable {
                    // Not "no longer on this device": the list is empty because the register
                    // could not be read, and the account may well still be in it. The pull this
                    // offers is the screen's own refresh, which reads the register again.
                    UnreadableAccountRegister(identifier: "account-register-unreadable")
                } else {
                    UsageCard {
                        Text("Account unavailable")
                            .usageFont(.section)
                            .foregroundStyle(colors.ink)
                        Text("This account is no longer on this device.")
                            .usageFont(.body)
                            .foregroundStyle(colors.muted)
                    }
                }
            }
            .padding(16)
            .readableWidth()
        }
        .hoorayViewport("detail")
        .background(colors.ground)
        .refreshable { await store.refresh() }
    }
}

struct UnreadableAccountRegister: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    let identifier: String

    var body: some View {
        UsageCard {
            Text("Can't read accounts")
                .usageFont(.section)
                .foregroundStyle(colors.ink)
                .accessibilityIdentifier(identifier)
            Text("The accounts stored on this device could not be read. Pull down to try again.")
                .usageFont(.body)
                .foregroundStyle(colors.muted)
        }
    }
}

private struct AccountSummaryCard: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }
    @State private var reconnecting = false

    @EnvironmentObject private var store: UsageStore
    @State private var isConfirmingRedeem = false
    @State private var isConfirmingRemove = false

    let usage: AccountUsage
    let now: Date
    let onDisconnected: @MainActor () -> Void

    private var snapshot: UsageSnapshot? { usage.snapshot }

    private var agedSeverity: Severity {
        snapshot?.severity(at: now, staleAfter: store.settings.staleAfter) ?? .stale
    }

    private var notificationsEnabled: Bool {
        store.notificationsEnabled(accountID: usage.account.id)
    }

    var body: some View {
        UsageCard {
            HStack {
                ClaudeProviderBadge(provider: usage.account.provider,
                    mood: ClaudeCodeMood.steady(for: snapshot?.headline(at: now)),
                    size: 36, staticSize: 32, figureSlotSize: 34, amplitude: 0.8, accountID: usage.account.id)
                #if DEBUG
                .environment(\.claudeAnimationDiagnosticID, "detail:\(usage.account.id)")
                #endif
                VStack(alignment: .leading, spacing: 2) {
                    Text(usage.account.label)
                        .usageFont(.section)
                        .foregroundStyle(colors.ink)
                        .accessibilityIdentifier("account-title-\(usage.account.id)")
                    // Through the shared `planLabel`, so "claude_max_20x" reads as "Claude Max
                    // 20×" here exactly as it does on Android, rather than as the raw value
                    // one platform happened to print.
                    if let subtitle = planLabel(usage.account.plan) ?? usage.account.maskedEmail {
                        Text(subtitle)
                            .usageFont(.small)
                            .foregroundStyle(colors.faint)
                    }
                }
                Spacer()
                // A muted account looks muted. Without this the only evidence of the choice is
                // inside a menu nobody opens twice, and silence then reads as the app failing
                // to notify rather than as the user having asked it not to.
                if !notificationsEnabled {
                    Image(systemName: "bell.slash.fill")
                        .foregroundStyle(colors.faint)
                        .accessibilityLabel("Notifications muted")
                }
                StatusMark(
                    severity: agedSeverity,
                    label: snapshot?.connectionStatus == .reconnectRequired ? String(localized: "Reconnect") : nil)
                Menu {
                    if snapshot?.connectionStatus == .reconnectRequired {
                        Button("Reconnect account") { reconnecting = true }
                    }
                    Button(
                        notificationsEnabled
                            ? String(localized: "Mute notifications") : String(localized: "Unmute notifications")
                    ) {
                        store.setNotifications(
                            enabled: !notificationsEnabled, accountID: usage.account.id)
                    }
                    Button("Disconnect account", role: .destructive) {
                        isConfirmingRemove = true
                    }
                    .disabled(store.isRemoving)
                } label: {
                    // 44pt to hit, however small the glyph.
                    Image(systemName: "ellipsis")
                        .foregroundStyle(colors.muted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Account actions")
            }
            // The wording promises only what this app can actually do. Removing an account
            // deletes what THIS DEVICE stores; it does not revoke the grant at the provider,
            // and saying otherwise would leave someone believing they had cut off access they
            // had not.
            .sheet(isPresented: $reconnecting) {
                AddAccountSheet(initialProvider: usage.account.provider)
            }
            .confirmationDialog(
                "Disconnect \(usage.account.label)?",
                isPresented: $isConfirmingRemove,
                titleVisibility: .visible
            ) {
                Button("Disconnect", role: .destructive) {
                    // A removal that failed leaves the detail open on the account, under the
                    // alert that says why, so it can be tried again.
                    Task {
                        if await store.removeAccount(accountID: usage.account.id) { onDisconnected() }
                    }
                }
                .disabled(store.isRemoving)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes the account from Usage Limits and deletes the sign-in stored on this device. It does not revoke access at the provider — do that in your account settings there.")
            }

            if let message = snapshot?.statusMessage ?? (snapshot?.connectionStatus == .reconnectRequired
                ? NotificationEvaluator.signInExpiredMessage : nil) {
                // The failure is shown on the account it belongs to, beside the numbers it
                // could not update — the one place a user can act on it. A sign-in that has
                // expired is answered from the menu, not by trying again.
                let isPausedReading: Bool = if snapshot?.status == .ok,
                    case .rateLimited? = StoredMessage.classify(message) { true } else { false }
                NoticeLine(
                    message: message, dot: isPausedReading ? colors.warning : colors.negative,
                    retry: isPausedReading || snapshot?.connectionStatus == .reconnectRequired
                        ? nil : { Task { await store.refresh() } })
            }

            ForEach(snapshot?.windows ?? [], id: \.id) { window in
                let aged = ReadingValidity.aged(agedSeverity)
                let validity: ReadingValidity = aged != .current ? aged
                    : (window.resetDue(fetchedAt: snapshot?.fetchedAt, at: now) ? .resetDue : .current)
                if let group = window.group, group != window.label {
                    Text(verbatim: group)
                        .usageFont(.small)
                        .foregroundStyle(colors.faint)
                }
                WindowRow(
                    row: GlanceRow(
                        label: window.label,
                        category: window.category,
                        remainingPercent: window.remainingPercent,
                        resetAt: window.resetAt,
                        severity: window.severity, scope: window.scope, segments: window.segments,
                        sourceWindowID: window.id),
                    now: now,
                    severity: validity == .error ? .error
                        : (validity == .stale || validity == .resetDue ? .stale : nil),
                    validity: validity,
                    mode: store.settings.percentDisplay)
            }

            resetCredits
        }
    }

    /// The credit balance, and the spend — but only when the provider says a credit applies.
    ///
    /// Held and applicable are different numbers and the distinction is the point: an account
    /// can hold three credits and be able to apply none of them, because none covers the limit
    /// currently in force. The balance is stated either way, so a user who has credits is not
    /// told they have none; the button appears only when one can actually be spent, because
    /// offering it otherwise takes a tap and gives back a refusal.
    @ViewBuilder
    private var resetCredits: some View {
        if let snapshot, snapshot.heldResetCredits > 0 {
            colors.rule.frame(height: 1)

            HStack {
                Text("\(snapshot.heldResetCredits) reset credits")
                    .usageFont(.body)
                    .foregroundStyle(colors.muted)
                Spacer()
                if snapshot.spendableResetCredits(at: now) > 0 {
                    Button("Reset limit") { isConfirmingRedeem = true }
                        .buttonStyle(OutlineButtonStyle())
                } else {
                    Text("None applies to this limit")
                        .usageFont(.small)
                        .foregroundStyle(colors.faint)
                }
            }
            // A confirmation, because this is the only irreversible thing the app can do. The
            // wording names the cost before the consequence: a user who reads three words and
            // taps should still have read the part that matters.
            .confirmationDialog(
                "Use a reset credit?",
                isPresented: $isConfirmingRedeem,
                titleVisibility: .visible
            ) {
                Button("Use credit", role: .destructive) {
                    Task { await store.redeemResetCredit(accountID: usage.account.id) }
                }
                // The store already ignores a second tap while one spend is in flight; the
                // button says so too, rather than looking willing and doing nothing.
                .disabled(store.isRedeeming)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This spends 1 reset credit and cannot be undone. The rate limit resets immediately.")
            }
        }
    }
}

