import Foundation
import UsageLimitsKit

// Lives in Theme because the widget target compiles that folder too, and a widget says the same
// words as the app. Both targets therefore carry `Localizable.xcstrings`; see project.yml.
//
// Keys are the English text. A built sentence is looked up whole, with its pieces as
// placeholders, so a language can move them: `"\(name) · \(label): ..."` is one key, not a
// concatenation of words.

/// `Countdown`'s words from the catalog. The kit supplies the arithmetic; see `CountdownTexts`.
struct LocalizedCountdownTexts: CountdownTexts {

    func now() -> String { String(localized: "now") }
    func daysHours(days: Int, hours: Int) -> String { String(localized: "\(days)d \(hours)h") }
    func days(_ days: Int) -> String { String(localized: "\(days)d") }
    func hoursMinutes(hours: Int, minutes: Int) -> String { String(localized: "\(hours)h \(minutes)m") }
    func hours(_ hours: Int) -> String { String(localized: "\(hours)h") }
    func minutes(_ minutes: Int) -> String { String(localized: "\(minutes)m") }
    func underOneMinute() -> String { String(localized: "<1m") }
    func resetsIn(duration: String) -> String { String(localized: "Resets in \(duration)") }
    func resetPending() -> String { String(localized: "Reset · refresh pending") }
    func resetsAt(stamp: String) -> String { String(localized: "Resets \(stamp)") }
    func asOf(stamp: String) -> String { String(localized: "As of \(stamp)") }
    func latestUpdate(stamp: String) -> String { String(localized: "Latest update \(stamp)") }
    func updatedJustNow() -> String { String(localized: "Updated just now") }
    func updatedAgo(duration: String) -> String { String(localized: "Updated \(duration) ago") }
    func renews(label: String, duration: String) -> String { String(localized: "\(label) renews \(duration)") }
}

/// The notification sentences from the catalog. The evaluator decides what is true; see
/// `NotificationTexts`.
///
/// The thresholds in the wording ("20%") are the evaluator's constants and are part of the
/// sentence, as on Android.
struct LocalizedNotificationTexts: NotificationTexts {

    var countdown: any CountdownTexts { LocalizedCountdownTexts() }

    func warning(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return String(localized: "\(name) · \(label): less than 20% remaining")
        case .used: return String(localized: "\(name) · \(label): more than 80% used")
        }
    }

    func critical(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return String(localized: "\(name) · \(label): less than 10% remaining")
        case .used: return String(localized: "\(name) · \(label): more than 90% used")
        }
    }

    func exhausted(name: String, label: String) -> String {
        String(localized: "\(name) · \(label) exhausted")
    }

    func resetApproaching(name: String, label: String, minutes: Int) -> String {
        // The plural lives in its own key and the sentence takes it as a piece, because a key
        // with two kinds of argument cannot vary by the count alone without substitutions.
        let amount = String(localized: "about \(minutes) minutes")
        return String(localized: "\(name) · \(label) resets in \(amount)")
    }

    func creditExpiring(name: String, duration: String) -> String {
        String(localized: "\(name) · a reset credit expires in \(duration)")
    }

    func creditsAvailable(name: String, count: Int) -> String {
        let amount = String(localized: "\(count) reset credits available")
        return String(localized: "\(name) · \(amount)")
    }

    func needsReconnect(name: String) -> String {
        String(localized: "\(name) needs to be reconnected")
    }
}

/// Words for what the kit stores or names in English, read at the display.
///
/// Every function matches the English text exactly and returns anything it does not recognise
/// untouched, so a provider's own wording, a label from a later build and text that is already
/// translated all pass through. The stored text itself is never rewritten.
enum DisplayText {

    /// A failure or status sentence the kit wrote, in the current language.
    static func stored(_ message: String) -> String {
        guard let known = StoredMessage.classify(message) else { return message }
        switch known {
        case .signInExpired:
            return String(localized: "Sign-in expired — reconnect this account")
        case .someUsageUnreadable:
            return String(localized: "Some usage data could not be read")
        case .someBillingNotUpdated:
            return String(localized: "Some billing data could not be updated")
        case .billingRejectedAuthentication:
            return String(localized: "One billing view rejected authentication; some usage could not be refreshed.")
        case .providerBlockDespiteQuota:
            return String(localized: "Provider reports a block despite remaining quota")
        case .restricted(let labels):
            return String(localized: "Restricted: \(labels)")
        case .malformedResponse(let detail):
            return String(localized: "The provider answered in a shape this app does not understand (\(detail)).")
        case .noUsage(let detail):
            return String(localized: "No usage could be read (\(detail)).")
        case .noCredentials:
            return String(localized: "No credentials are stored for the account.")
        }
    }

    /// The state of a choice, for VoiceOver.
    static func selection(_ selected: Bool) -> String {
        selected ? String(localized: "Selected") : String(localized: "Not selected")
    }

    /// The three horizons the app itself names. A model-scoped window ("Weekly (Opus)") and any
    /// other label is the provider's and stays as it is, as on Android.
    static func windowLabel(_ label: String, category: WindowCategory, scope: UsageScope = .global) -> String {
        guard scope == .global else { return label }
        switch (category, label) {
        case (.fiveHour, "5h limit"): return String(localized: "5h limit")
        case (.weekly, "Weekly"): return String(localized: "Weekly")
        case (.monthly, "Monthly"): return String(localized: "Monthly")
        default: return label
        }
    }

    /// The three balance labels the credit parsers write.
    static func creditBalanceLabel(_ label: String) -> String {
        switch label {
        case "Key limit": return String(localized: "Key limit")
        case "No limit": return String(localized: "No limit")
        case "Unavailable": return String(localized: "Unavailable")
        default: return label
        }
    }

    /// "Claude · Color": the brand is data and stays, the style word is translated.
    static func providerIconLabel(_ label: String) -> String {
        guard let separator = label.range(of: " · ", options: .backwards) else { return label }
        let brand = String(label[..<separator.lowerBound])
        let style: String
        switch String(label[separator.upperBound...]) {
        case "Color": style = String(localized: "Color")
        case "Monochrome": style = String(localized: "Monochrome")
        case "Wordmark": style = String(localized: "Wordmark")
        default: return label
        }
        return String(localized: "\(brand) · \(style)")
    }

    /// Kimi's and Z.ai's two services.
    static func regionLabel(_ region: ProviderRegion, for provider: ProviderID) -> String {
        switch (provider, region) {
        case (.kimi, .cn): return String(localized: "China (kimi.com)")
        case (.kimi, .intl): return String(localized: "International (kimi.ai)")
        case (.zai, .cn): return String(localized: "China (bigmodel.cn)")
        case (.zai, .intl): return String(localized: "International (z.ai)")
        default: return region.label(for: provider)
        }
    }

    /// Moonshot's two services, as the credit sheet's picker names them.
    static func creditRegionLabel(_ region: ProviderRegion) -> String {
        region == .cn ? String(localized: "China (moonshot.cn, CNY)") : String(localized: "International (moonshot.ai, USD)")
    }

    /// What to paste for a credit provider. Domains and scope names inside are data.
    static func creditKeyHint(_ provider: CreditProviderID) -> String {
        switch provider {
        case .openrouter:
            return String(localized: "Management key for the account balance, or any API key for that key's own limit.")
        case .deepseek: return String(localized: "DeepSeek API key.")
        case .moonshot: return String(localized: "Moonshot platform API key (not a Kimi Code key).")
        case .xai:
            return String(localized: "xAI management key (console.x.ai, Settings, Management Keys), not an inference key.")
        case .fal:
            return String(localized: "fal key with the billing:usage:read scope (the BILLING preset), not an inference key.")
        case .novita: return String(localized: "Novita API key.")
        }
    }

    /// Why a credit provider cannot be added.
    static func creditUnavailableReason(_ reason: String) -> String {
        reason == "No balance endpoint for API keys"
            ? String(localized: "No balance endpoint for API keys") : reason
    }
}
