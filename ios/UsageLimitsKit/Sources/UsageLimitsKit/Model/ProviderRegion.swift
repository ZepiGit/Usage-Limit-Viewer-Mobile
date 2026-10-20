import Foundation

/// Which deployment of a provider an account lives on, for the providers that run two.
///
/// Kimi Code and Z.ai each operate a China and an international service with separate accounts,
/// so a credential only works against the host it was issued by. The region is therefore stored
/// with the credential (`OAuthCredentials.providerData`, which a token refresh can read) and
/// mirrored in `ProviderAccount.attributes` for display. Raw values are persisted.
public enum ProviderRegion: String, CaseIterable, Sendable, Codable {
    case cn
    case intl

    /// Key under `providerData` and `attributes`.
    public static let key = "region"

    /// The regions a user can pick, in the order the picker lists them. Empty: no choice.
    public static func options(for provider: ProviderID) -> [ProviderRegion] {
        switch provider {
        case .kimi: return [.cn, .intl]
        case .zai: return [.intl, .cn]
        default: return []
        }
    }

    /// What an account with no stored region means. Kimi accounts predate the choice and were
    /// all China (kimi.com); Z.ai leads with its international host.
    public static func defaultRegion(for provider: ProviderID) -> ProviderRegion {
        provider == .zai ? .intl : .cn
    }

    /// The id an account is filed under. The two regions are separate user and key spaces, so the
    /// same id (or key digest) can come back from both; the non-default region is prefixed so it
    /// cannot overwrite the other's account. The default stays bare, which keeps every account
    /// stored before the choice existed where it is. Same format as Android.
    func scopedID(_ externalID: String, for provider: ProviderID) -> String {
        self == Self.defaultRegion(for: provider) ? externalID : "\(rawValue):\(externalID)"
    }

    public func label(for provider: ProviderID) -> String {
        switch (provider, self) {
        case (.kimi, .cn): return "China (kimi.com)"
        case (.kimi, .intl): return "International (kimi.ai)"
        case (.zai, .cn): return "China (bigmodel.cn)"
        case (.zai, .intl): return "International (z.ai)"
        default: return rawValue
        }
    }

    /// The region a stored credential belongs to: the credential's own record first, because a
    /// refresh sees nothing else, then the account's attributes, then the provider default.
    static func stored(
        provider: ProviderID, credentials: OAuthCredentials, attributes: [String: String] = [:]
    ) -> ProviderRegion {
        (credentials.providerData[key] ?? attributes[key]).flatMap(ProviderRegion.init(rawValue:))
            ?? defaultRegion(for: provider)
    }
}
