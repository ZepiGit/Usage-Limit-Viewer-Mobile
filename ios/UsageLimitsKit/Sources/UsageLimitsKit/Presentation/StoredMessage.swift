import Foundation

/// The failure and status sentences the kit writes in English and stores with a snapshot.
///
/// They are data: `UsageSnapshot.errorMessage` and `GlanceAccount.statusMessage` carry them, the
/// notification evaluator matches one of them by equality, and a cached file outlives a language
/// change. So the stored text is never translated or rewritten. A surface instead asks what a
/// stored sentence IS here and renders that case in the current language, which reads an old
/// row in the new language and leaves the data untouched.
///
/// Only whole sentences the kit itself produces are recognised, and anything else — a
/// provider's own error text, a sentence from a later build, several messages joined into one
/// status line — is `nil`: the caller shows it exactly as stored. Associated values carry what
/// the sentence interpolates (a window list, a diagnostic detail), which is data as well.
///
/// The Android twin is `storedMessageText`, which matches the same sentences by regular
/// expression. A test feeds every producer's real output through `classify`, so rewording a
/// producer without this list fails there instead of silently showing English.
public enum StoredMessage: Sendable, Equatable {
    case signInExpired
    case someUsageUnreadable
    case someBillingNotUpdated
    case billingRejectedAuthentication
    case providerBlockDespiteQuota
    /// `labels` is the comma-joined list of window labels as stored.
    case restricted(labels: String)
    case malformedResponse(detail: String)
    case noUsage(detail: String)
    case noCredentials

    /// What `message` is, or `nil` when it is not one of the kit's own sentences.
    public static func classify(_ message: String) -> StoredMessage? {
        // `meansSignInExpired` is deliberately not used: it also accepts any text containing
        // "expired", which is right for deciding to notify and wrong for deciding to translate.
        if message == NotificationEvaluator.signInExpiredMessage
            || message == legacySignInMessage
        {
            return .signInExpired
        }

        switch message {
        case "Some usage data could not be read": return .someUsageUnreadable
        case "Some billing data could not be updated": return .someBillingNotUpdated
        case "One billing view rejected authentication; some usage could not be refreshed.":
            return .billingRejectedAuthentication
        case "Provider reports a block despite remaining quota": return .providerBlockDespiteQuota
        case "No credentials are stored for the account.": return .noCredentials
        default: break
        }

        if let detail = between(
            message,
            prefix: "The provider answered in a shape this app does not understand (",
            suffix: ").")
        {
            return .malformedResponse(detail: detail)
        }
        if let detail = between(message, prefix: "No usage could be read (", suffix: ").") {
            return .noUsage(detail: detail)
        }
        // A restricted list followed by the block sentence is one joined status line, and a
        // window label may itself contain " · ", so the labels cannot be split back out of it.
        let restrictedPrefix = "Restricted: "
        if message.hasPrefix(restrictedPrefix) {
            let labels = String(message.dropFirst(restrictedPrefix.count))
            if !labels.contains(" · Provider reports a block despite remaining quota") {
                return .restricted(labels: labels)
            }
        }
        return nil
    }

    /// The wording an earlier build stored for the same condition as `signInExpired`.
    private static let legacySignInMessage = "This account needs signing in again."

    private static func between(_ message: String, prefix: String, suffix: String) -> String? {
        guard message.hasPrefix(prefix), message.hasSuffix(suffix),
              message.count >= prefix.count + suffix.count
        else { return nil }
        return String(message.dropFirst(prefix.count).dropLast(suffix.count))
    }
}
