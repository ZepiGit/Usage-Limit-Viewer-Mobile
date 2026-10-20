package com.usagelimits.core.model

/**
 * Which deployment of a provider an account lives on, for the providers that run two.
 *
 * Kimi Code and Z.ai each operate a China and an international service with separate accounts,
 * so a credential only works against the host that issued it. The region is therefore stored
 * with the credential (`OAuthCredentials.providerData`, which a token refresh can read) and
 * mirrored in `ProviderAccount.attributes` for display. [id] is persisted.
 */
enum class ProviderRegion(val id: String) {
    CN("cn"),
    INTL("intl");

    fun label(provider: ProviderId): String = when (provider to this) {
        ProviderId.KIMI to CN -> "China (kimi.com)"
        ProviderId.KIMI to INTL -> "International (kimi.ai)"
        ProviderId.ZAI to CN -> "China (bigmodel.cn)"
        ProviderId.ZAI to INTL -> "International (z.ai)"
        else -> id
    }

    companion object {
        /** Key under `providerData` and `attributes`. */
        const val KEY = "region"

        /** The regions a user can pick, in picker order. Empty: no choice to make. */
        fun options(provider: ProviderId): List<ProviderRegion> = when (provider) {
            ProviderId.KIMI -> listOf(CN, INTL)
            ProviderId.ZAI -> listOf(INTL, CN)
            else -> emptyList()
        }

        /** What an account with no stored region means: Kimi predates the choice and was all China. */
        fun default(provider: ProviderId): ProviderRegion = if (provider == ProviderId.ZAI) INTL else CN

        /**
         * The region a stored credential belongs to: the credential's own record first, because
         * a refresh sees nothing else, then the account's attributes, then the provider default.
         */
        fun stored(
            provider: ProviderId,
            providerData: Map<String, String>,
            attributes: Map<String, String> = emptyMap(),
        ): ProviderRegion =
            from(providerData[KEY] ?: attributes[KEY], provider)

        /**
         * The id an account is filed under. The two regions are separate user and key spaces, so
         * the same id (or key digest) can come back from both; the non-default region is
         * prefixed so it cannot overwrite the other's account. The default stays bare, which
         * keeps every account stored before the choice existed where it is.
         */
        fun scopedId(provider: ProviderId, region: ProviderRegion, externalId: String): String =
            if (region == default(provider)) externalId else "${region.id}:$externalId"

        /** A persisted [id], or the provider's default for a missing or unknown one. */
        fun from(raw: String?, provider: ProviderId): ProviderRegion =
            entries.firstOrNull { it.id == raw } ?: default(provider)
    }
}
