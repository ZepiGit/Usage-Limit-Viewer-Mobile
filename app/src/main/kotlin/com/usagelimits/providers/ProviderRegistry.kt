package com.usagelimits.providers

import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.oauth.EnglishLoopbackPageTexts
import com.usagelimits.core.oauth.LoopbackPageTexts
import com.usagelimits.providers.antigravity.AntigravityProvider
import com.usagelimits.providers.claude.ClaudeProvider
import com.usagelimits.providers.codex.CodexProvider
import com.usagelimits.providers.devin.DevinProvider
import com.usagelimits.providers.kimi.KimiProvider
import com.usagelimits.providers.meta.MetaProvider
import com.usagelimits.providers.opencode.OpenCodeProvider
import com.usagelimits.providers.xai.XaiProvider
import com.usagelimits.providers.zai.ZaiProvider

/**
 * Resolves a [ProviderId] to its implementation.
 *
 * The rest of the app only ever asks for a provider by id, so adding a provider means adding
 * one entry here and nothing else — no screen, widget, or sync code changes.
 */
class ProviderRegistry(private val providers: Map<ProviderId, UsageProvider>) {

    /** [loginPageTexts] is the language of the page the browser shows after a redirect sign-in. */
    constructor(http: HttpClient, loginPageTexts: LoopbackPageTexts = EnglishLoopbackPageTexts) : this(
        buildMap {
            put(ProviderId.CODEX, CodexProvider(http, loginPageTexts = loginPageTexts))
            put(ProviderId.CLAUDE, ClaudeProvider(http, loginPageTexts = loginPageTexts))
            put(ProviderId.ANTIGRAVITY, AntigravityProvider(http, loginPageTexts = loginPageTexts))
            put(ProviderId.XAI, XaiProvider(http))
            put(ProviderId.KIMI, KimiProvider(http))
            put(ProviderId.DEVIN, DevinProvider(http, loginPageTexts = loginPageTexts))
            put(ProviderId.META, MetaProvider(http))
            put(ProviderId.ZAI, ZaiProvider(http))
            put(ProviderId.OPENCODE, OpenCodeProvider(http))
        },
    )

    fun forId(id: ProviderId): UsageProvider? = providers[id]

    /** Providers that can currently be added, in the order the picker shows them. */
    fun available(): List<UsageProvider> =
        ProviderId.entries.mapNotNull { providers[it] }.filter { it.isLoginAvailable }

    fun all(): List<UsageProvider> = ProviderId.entries.mapNotNull { providers[it] }
}
