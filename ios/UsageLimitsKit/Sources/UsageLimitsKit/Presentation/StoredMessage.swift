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

    // Sign-in. These are thrown rather than stored, but they reach the same `NoticeLine`s and
    // alerts as a stored status, so the display reads them the same way.

    /// `reason` is the provider's own error code or wording.
    case signInRefused(reason: String)
    case signInWithoutCode
    case signInUnreadable
    case signInCodeExpired
    case signInTimedOut
    case portInUse(port: Int)
    /// Antigravity's project lookup came back empty. Carried by a `malformedResponse` sentence
    /// whose detail is advice the user can act on, so the detail gets a case of its own.
    case antigravityProjectMissing

    // Credits.
    case noKeyEntered
    case keyRejected(status: Int)
    case noBalance(detail: String)
    case xaiNoPrepaidBalance
    case xaiTeamRequired
    case providerUnreachable
    case keyUnreadable

    // Spending a reset credit.
    case resetCreditsUnavailable
    case noResetCreditApplicable
    case resetAlreadyRequested

    // Faults the sync engine raises for one account.
    case accountNamesNoProvider
    case providerNotRegistered(id: String)

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
        case "The sign-in came back without an authorisation code.": return .signInWithoutCode
        case "The sign-in could not be read.": return .signInUnreadable
        case "The sign-in code expired before it was approved.": return .signInCodeExpired
        case "The sign-in was not completed in time.": return .signInTimedOut
        // Before the general malformed-response match below, which would also take it.
        case antigravityProjectMissingMessage: return .antigravityProjectMissing
        case "No key was entered.": return .noKeyEntered
        case "xAI has no prepaid balance for this team (HTTP 404). Check the team id, and that the team prepays.":
            return .xaiNoPrepaidBalance
        case "This xAI key is not tied to one team. Enter the team id from console.x.ai.":
            return .xaiTeamRequired
        case "The provider could not be reached.": return .providerUnreachable
        case "The key could not be read from the keychain.": return .keyUnreadable
        case "This account cannot use reset credits.": return .resetCreditsUnavailable
        case "No reset credit can be applied to this limit right now.": return .noResetCreditApplicable
        case "A reset is already being requested for this account.": return .resetAlreadyRequested
        case "The account does not name a provider.": return .accountNamesNoProvider
        default: break
        }

        if let reason = between(
            message, prefix: "The provider refused the sign-in (", suffix: ").")
        {
            return .signInRefused(reason: reason)
        }
        if let text = between(
            message,
            prefix: "Port ",
            suffix: " is in use, so the sign-in cannot be received. Close whatever is using it and try again."),
           let port = integer(text)
        {
            return .portInUse(port: port)
        }
        if let text = between(
            message,
            prefix: "The key was rejected (HTTP ",
            suffix: "). Check that it is the right kind of key."),
           let status = integer(text)
        {
            return .keyRejected(status: status)
        }
        if let detail = between(message, prefix: "No balance could be read (", suffix: ").") {
            return .noBalance(detail: detail)
        }
        if let id = between(
            message, prefix: "No provider is registered for provider id \"", suffix: "\".")
        {
            return .providerNotRegistered(id: id)
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

    /// `LoopbackLogin.resolveProject`'s failure, as `DeviceLoginError.malformedResponse` words it.
    private static let antigravityProjectMissingMessage =
        "The provider answered in a shape this app does not understand "
        + "(loadCodeAssist returned no GCP project id. If this account has never used "
        + "Antigravity, sign in to the desktop client once to provision it, then add it here.)."

    /// Digits only, so "+5" and " 5" are not a port or a status.
    private static func integer(_ text: String) -> Int? {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isWholeNumber }) else { return nil }
        return Int(text)
    }

    private static func between(_ message: String, prefix: String, suffix: String) -> String? {
        guard message.hasPrefix(prefix), message.hasSuffix(suffix),
              message.count >= prefix.count + suffix.count
        else { return nil }
        return String(message.dropFirst(prefix.count).dropLast(suffix.count))
    }
}
