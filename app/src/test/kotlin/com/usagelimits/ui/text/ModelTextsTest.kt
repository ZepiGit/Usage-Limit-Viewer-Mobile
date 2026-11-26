package com.usagelimits.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.providers.KeyLoginCapable
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.sync.userMessage
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.providers.kimi.KimiProvider
import com.usagelimits.providers.opencode.OpenCodeProvider
import com.usagelimits.providers.scriptedHttp
import com.usagelimits.providers.zai.ZaiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * The sentences of the model and provider layers, read from resources (R4).
 *
 * Under `en` each resource must equal the English text its layer still carries, so moving the
 * words did not change them; under `de` the same ids read as German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ModelTextsEnglishTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `credit provider hints, region labels and reasons equal the model's text`() {
        for (provider in CreditProviderId.entries) {
            assertEquals(provider.keyHint, resources.getString(creditKeyHintRes(provider)))
        }
        for (region in CreditProviderId.regions) {
            assertEquals(CreditProviderId.regionLabel(region), resources.getString(creditRegionLabelRes(region)))
        }
        for (unavailable in CreditProviderId.notPossible) {
            assertEquals(unavailable.reason, resources.getString(creditUnavailableReasonRes(unavailable)!!))
        }
    }

    @Test
    fun `provider region labels equal the model's text and fall back where the label is an id`() {
        for (provider in listOf(ProviderId.KIMI, ProviderId.ZAI)) {
            for (region in ProviderRegion.options(provider)) {
                assertEquals(region.label(provider), resources.getString(providerRegionLabelRes(provider, region)!!))
            }
        }
        assertNull(providerRegionLabelRes(ProviderId.CLAUDE, ProviderRegion.CN))
    }

    @Test
    fun `the pasted-key hints equal what the providers write`() {
        val http = scriptedHttp(200 to "{}")
        val challenges: Map<ProviderId, KeyLoginCapable> = mapOf(
            ProviderId.ZAI to ZaiProvider(http),
            ProviderId.KIMI to KimiProvider(http, nowMs = { 0 }, version = "test"),
            ProviderId.OPENCODE to OpenCodeProvider(http),
        )
        for ((id, provider) in challenges) {
            val hint = provider.keyLoginChallenge().hint
            assertEquals(id.name, hint, resources.getString(apiKeyHintRes(id)!!))
        }
        assertNull(apiKeyHintRes(ProviderId.CLAUDE))
    }

    @Test
    fun `every stored balance label is translated, and the figure labels are formatted at the display`() {
        // Every fixed label the parsers write, as of the providers that ship one.
        val fixed = listOf(
            "Key limit", "Daily key limit", "Weekly key limit", "Monthly key limit",
            "No limit", "Unavailable", "Token Pass", "Prepaid balance",
        )
        for (label in fixed) assertEquals(label, resources.getString(creditBalanceLabelRes(label)!!))
        assertNull(creditBalanceLabelRes("Trial credit"))

        // A figure label is not a fixed sentence: it is formatted here in the reader's money.
        assertEquals(BigDecimal("4.50"), creditBalanceUsedAmount("Used: 4.50"))
        assertNull(creditBalanceUsedAmount("Key limit"))
        val spent = creditBalanceSpentAmounts("Spent: 80.25 top-up, 10.00 vouchers")
            ?: error("the spend label must be recognised")
        assertTrue(BigDecimal("80.25").compareTo(spent.first) == 0)
        assertTrue(BigDecimal("10.00").compareTo(spent.second) == 0)
        assertNull(creditBalanceSpentAmounts("Used: 4.50"))
        assertEquals(
            "Spent: $80.25 top-up, $10.00 vouchers",
            resources.getString(com.usagelimits.R.string.credit_balance_spent, "$80.25", "$10.00"),
        )
    }

    @Test
    fun `every credit error reads as its own message`() {
        val errors = listOf(
            CreditError.Rejected(401),
            CreditError.Malformed("openrouter key has no data"),
            CreditError.Unavailable("fal returned no credits"),
            CreditError.NotFound("https://example.invalid answered HTTP 404"),
            CreditError.NotFound("xAI has no prepaid balance for this team (HTTP 404). Check the team id, and that the team prepays."),
            CreditError.TeamIdRequired(),
            CreditError.NoKey(),
        )
        for (error in errors) assertEquals(error.message, storedMessageText(resources, error.message.orEmpty()))
    }

    @Test
    fun `every sync failure reads as the sentence it stores, and an unknown one is left alone`() {
        val failures = listOf(
            ProviderException.Unauthorized("x"), ProviderException.Forbidden("x"), ProviderException.RateLimited(null),
            ProviderException.ServerError(503, "x"), ProviderException.Offline(), ProviderException.MalformedPayload("x"),
            ProviderException.LoginCancelled("x"), ProviderException.Unexpected("x"),
        )
        for (failure in failures) assertEquals(failure.userMessage(), storedMessageText(resources, failure.userMessage()))
        val stored = listOf(
            "Unexpected error while refreshing",
            "Could not look up api.example.com. Check the connection, or a DNS filter that may block it.",
            "Could not connect to api.example.com.",
            "api.example.com did not answer in time.",
            "A secure connection to api.example.com could not be established.",
            "The connection to api.example.com was interrupted.",
            "Some quota values could not be read; available usage is shown.",
            "Reset credits could not be refreshed",
            "One billing view rejected authentication; some usage could not be refreshed.",
            "One billing view could not be refreshed; only returned usage is shown.",
            "Some billing values could not be read; available usage is shown.",
            "The key could not be read from the device.",
        )
        for (message in stored) assertEquals(message, storedMessageText(resources, message))
        assertEquals("Reset credits could not be refreshed later", storedMessageText(resources, "Reset credits could not be refreshed later"))
        assertEquals("boom", storedMessageText(resources, "boom"))
    }

    @Test
    fun `a legacy usage throttle invites a retry without claiming a pause`() {
        assertEquals("Rate limited. Try again later.",
            storedMessageText(resources, "Rate limited — try again shortly"))
    }

    @Test
    fun `only the app's own horizon names are translated, and only for a global window`() {
        assertEquals(com.usagelimits.R.string.window_label_five_hour, windowLabelRes("5h limit", WindowCategory.FIVE_HOUR, UsageScope.GLOBAL))
        assertEquals(com.usagelimits.R.string.window_label_weekly, windowLabelRes("Weekly", WindowCategory.WEEKLY, UsageScope.GLOBAL))
        assertEquals(com.usagelimits.R.string.window_label_monthly, windowLabelRes("Monthly", WindowCategory.MONTHLY, UsageScope.GLOBAL))
        assertNull(windowLabelRes("Weekly (Opus)", WindowCategory.WEEKLY, UsageScope.MODEL))
        assertNull(windowLabelRes("Weekly", WindowCategory.WEEKLY, UsageScope.MODEL))
        assertNull(windowLabelRes("Gemini Pro", WindowCategory.FIVE_HOUR, UsageScope.GLOBAL))
        assertNull(windowLabelRes("Weekly", WindowCategory.FIVE_HOUR, UsageScope.GLOBAL))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class ModelTextsGermanTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `credit and region sentences read German and keep their domains`() {
        assertEquals("DeepSeek-API-Schlüssel.", resources.getString(creditKeyHintRes(CreditProviderId.DEEPSEEK)))
        assertEquals("China (moonshot.cn, CNY)", resources.getString(creditRegionLabelRes(ProviderRegion.CN)))
        assertEquals("International (moonshot.ai, USD)", resources.getString(creditRegionLabelRes(ProviderRegion.INTL)))
        assertEquals("Kein Guthaben-Endpunkt für API-Schlüssel",
            resources.getString(creditUnavailableReasonRes(CreditProviderId.notPossible.first())!!))
        assertEquals("China (kimi.com)", resources.getString(providerRegionLabelRes(ProviderId.KIMI, ProviderRegion.CN)!!))
        assertEquals("International (z.ai)", resources.getString(providerRegionLabelRes(ProviderId.ZAI, ProviderRegion.INTL)!!))
    }

    @Test
    fun `the pasted-key hint speaks to the reader`() {
        assertEquals(
            "Erstelle in deiner Z.ai-Konsole einen Schlüssel und füge ihn hier ein. Er wird nur auf diesem Gerät " +
                "gespeichert, im selben verschlüsselten Speicher wie jedes andere Konto.",
            resources.getString(apiKeyHintRes(ProviderId.ZAI)!!),
        )
    }

    @Test
    fun `balance labels and credit errors read German, keeping the diagnostic detail as written`() {
        assertEquals("Kein Limit", resources.getString(creditBalanceLabelRes("No limit")!!))
        assertEquals(
            "Ausgegeben: 80,25 $ Aufladung, 10,00 $ Gutscheine",
            resources.getString(com.usagelimits.R.string.credit_balance_spent, "80,25 $", "10,00 $"),
        )
        assertEquals("Der Schlüssel wurde abgelehnt (HTTP 401). Prüfe, ob es die richtige Art von Schlüssel ist.",
            storedMessageText(resources, CreditError.Rejected(401).message.orEmpty()))
        assertEquals("Es konnte kein Guthaben gelesen werden (fal returned no credits).",
            storedMessageText(resources, CreditError.Unavailable("fal returned no credits").message.orEmpty()))
        assertEquals("Es wurde kein Schlüssel eingegeben.", storedMessageText(resources, CreditError.NoKey().message.orEmpty()))
        assertEquals("https://example.invalid answered HTTP 404",
            storedMessageText(resources, CreditError.NotFound("https://example.invalid answered HTTP 404").message.orEmpty()))
    }

    @Test
    fun `stored sync failures read German with their host and status code carried over`() {
        assertEquals("Anmeldung abgelaufen — verbinde dieses Konto neu", storedMessageText(resources, ProviderException.Unauthorized("x").userMessage()))
        assertEquals("Der Anbieter hat gerade Probleme (503)", storedMessageText(resources, ProviderException.ServerError(503, "x").userMessage()))
        assertEquals("Keine Netzwerkverbindung", storedMessageText(resources, ProviderException.Offline().userMessage()))
        assertEquals("Verbindung zu api.example.com nicht möglich.", storedMessageText(resources, "Could not connect to api.example.com."))
        assertEquals("api.example.com hat nicht rechtzeitig geantwortet.", storedMessageText(resources, "api.example.com did not answer in time."))
        assertEquals("Einige Kontingentwerte konnten nicht gelesen werden; die verfügbare Nutzung wird angezeigt.",
            storedMessageText(resources, "Some quota values could not be read; available usage is shown."))
        assertEquals("Eine Fehlermeldung, die die App nicht kennt", storedMessageText(resources, "Eine Fehlermeldung, die die App nicht kennt"))
    }
}
