package com.usagelimits.providers.credits

import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Credits
import com.usagelimits.core.network.ProviderException
import kotlinx.serialization.json.JsonObject

/**
 * Reads one provider's balance with the key the user pasted. No token refresh: a key either
 * works or it does not. Deliberately not a [com.usagelimits.providers.UsageProvider]: there are
 * no windows and no credentials beyond the key.
 */
interface CreditProvider {
    val providerId: CreditProviderId

    /** [attributes] are the account's non-secret extras (region, team id). */
    suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance>
}

/** The clients, by provider. */
class CreditRegistry(http: HttpClient) {
    /** Kept by name too: the add flow asks it which team a management key belongs to. */
    val xai = XaiCreditClient(http)

    private val clients: Map<CreditProviderId, CreditProvider> = listOf(
        OpenRouterCreditClient(http), DeepSeekCreditClient(http), MoonshotCreditClient(http),
        xai, FalCreditClient(http), NovitaCreditClient(http),
    ).associateBy { it.providerId }

    fun forId(id: CreditProviderId): CreditProvider? = clients[id]
}

/**
 * The shared request path. [HttpClient] throws on a non-2xx, so the statuses a caller can act
 * on (401/403 wrong key, 404 wrong team) are translated here, once.
 */
internal object CreditHttp {

    suspend fun getJson(http: HttpClient, url: String, headers: Map<String, String>, endpoint: String): JsonObject {
        val response = try {
            http.request(url, headers = headers)
        } catch (e: ProviderException.Unauthorized) {
            throw CreditError.Rejected(401)
        } catch (e: ProviderException.Forbidden) {
            throw CreditError.Rejected(403)
        } catch (e: ProviderException.Unexpected) {
            when (val status = e.statusCode) {
                404 -> throw CreditError.NotFound("$endpoint answered HTTP 404")
                null -> throw CreditError.Unavailable("$endpoint could not be reached")
                else -> throw CreditError.Unavailable("$endpoint answered HTTP $status")
            }
        } catch (e: ProviderException.ServerError) {
            throw CreditError.Unavailable("$endpoint answered HTTP ${e.statusCode}")
        } catch (e: ProviderException.RateLimited) {
            throw CreditError.Unavailable("$endpoint is rate limiting this key")
        } catch (e: ProviderException.Offline) {
            throw CreditError.Unavailable("$endpoint could not be reached")
        }
        return try {
            JsonSupport.parseObject(response.body)
        } catch (e: ProviderException.MalformedPayload) {
            throw CreditError.Malformed("$endpoint did not return a JSON object")
        }
    }

    fun bearer(key: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
}

class OpenRouterCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.OPENROUTER

    /**
     * `/credits` needs a management key and answers 403 to any other; that 403 (and only that
     * one) falls back to `/key`, which any key can read for its own limit. A 401 is a bad key.
     */
    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> =
        try {
            OpenRouterCreditParser.parseCredits(
                CreditHttp.getJson(http, Credits.OPENROUTER_CREDITS, CreditHttp.bearer(key), "openrouter credits"),
            )
        } catch (e: CreditError.Rejected) {
            if (e.status != 403) throw e
            OpenRouterCreditParser.parseKey(
                CreditHttp.getJson(http, Credits.OPENROUTER_KEY, CreditHttp.bearer(key), "openrouter key"),
            )
        }
}

class DeepSeekCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.DEEPSEEK

    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> =
        DeepSeekCreditParser.parse(
            CreditHttp.getJson(http, Credits.DEEPSEEK_BALANCE, CreditHttp.bearer(key), "deepseek balance"),
        )
}

class MoonshotCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.MOONSHOT

    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> {
        val region = ProviderRegion.entries.firstOrNull { it.id == attributes[CreditAccount.REGION_KEY] }
            ?: ProviderRegion.INTL
        return MoonshotCreditParser.parse(
            CreditHttp.getJson(http, Credits.moonshotBalance(region), CreditHttp.bearer(key), "moonshot balance"),
            region,
        )
    }
}

class XaiCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.XAI

    /** The team the key is scoped to. Org-wide keys name no team; the user supplies it. */
    suspend fun resolveTeamId(key: String): String? =
        XaiCreditParser.parseTeamId(
            CreditHttp.getJson(http, Credits.XAI_VALIDATION, CreditHttp.bearer(key), "xai key validation"),
        )

    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> {
        val teamId = attributes[CreditAccount.TEAM_ID_KEY]?.takeIf { it.isNotEmpty() }
            ?: resolveTeamId(key)
            ?: throw CreditError.TeamIdRequired()
        try {
            return XaiCreditParser.parseBalance(
                CreditHttp.getJson(http, Credits.xaiBalance(teamId), CreditHttp.bearer(key), "xai prepaid balance"),
            )
        } catch (e: CreditError.NotFound) {
            // Never $0: a team with no prepaid ledger and a wrong team id look the same here.
            throw CreditError.NotFound(
                "xAI has no prepaid balance for this team (HTTP 404). Check the team id, and that the team prepays.",
            )
        }
    }
}

class FalCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.FAL

    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> =
        FalCreditParser.parse(
            CreditHttp.getJson(
                http, Credits.FAL_BILLING,
                // `Key`, not `Bearer`.
                mapOf("Authorization" to "Key $key", "Accept" to "application/json"),
                "fal billing",
            ),
        )
}

class NovitaCreditClient(private val http: HttpClient) : CreditProvider {
    override val providerId = CreditProviderId.NOVITA

    override suspend fun fetchBalances(key: String, attributes: Map<String, String>): List<CreditBalance> =
        NovitaCreditParser.parse(
            CreditHttp.getJson(http, Credits.NOVITA_BALANCE, CreditHttp.bearer(key), "novita balance"),
        )
}
