package com.usagelimits.core.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ResetCredit
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.ui.text.ResourceNotificationTexts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the evaluator says, per language (R4).
 *
 * [NotificationEvaluatorTest] pins the English strings against the default
 * [EnglishNotificationTexts]. These cases run the same inputs through the resource-backed texts:
 * under `en` they must agree with that default character for character, and under `de` they pin the
 * German wording, in both percent modes and across the plural forms.
 */
abstract class NotificationTextsFixture {

    protected val now = 1_757_000_000_000L
    private val minute = 60_000L

    protected val resourceTexts = ResourceNotificationTexts(
        ApplicationProvider.getApplicationContext<Context>().resources,
    )

    /** Every notification flag on, so each condition below speaks. */
    protected val speaking = AppSettings(
        notifyBelow20Percent = true,
        notifyBelow10Percent = true,
        notifyOnExhausted = true,
        notifyOnResetApproaching = true,
        notifyOnResetCreditExpiring = true,
        notifyOnResetCreditAvailable = true,
        notifyOnAuthExpired = true,
    )

    protected fun usage(
        remaining: Double?,
        exhausted: Boolean = false,
        resetAt: Long? = null,
        credits: List<ResetCredit> = emptyList(),
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
    ) = AccountUsage(
        account = ProviderAccount(
            localId = "acct",
            provider = ProviderId.CODEX,
            externalAccountId = "ext-acct",
            email = "acct@example.com",
            displayName = "Konto A",
            plan = "Plus",
            credentialReference = "ref-acct",
            createdAt = now,
            lastSuccessfulSync = now,
        ),
        snapshot = UsageSnapshot(
            accountId = "acct",
            fetchedAt = now,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "w",
                    label = "5h limit",
                    category = WindowCategory.FIVE_HOUR,
                    usedPercent = remaining?.let { 100.0 - it },
                    periodSeconds = 18_000,
                    resetAt = resetAt,
                    exhausted = exhausted,
                ),
            ),
            resetCredits = credits,
            connectionStatus = connection,
        ),
    )

    /** Every line one fresh evaluation produces: the new alerts, then the standing findings. */
    protected fun lines(
        usage: AccountUsage,
        mode: PercentDisplay,
        texts: NotificationTexts,
    ): List<String> {
        val outcome = NotificationEvaluator.evaluate(
            accounts = listOf(usage),
            settings = speaking.copy(percentDisplay = mode),
            states = emptyMap(),
            nowMs = now,
            texts = texts,
        )
        return outcome.events.map { it.line }.filter { it.isNotBlank() } + outcome.standingFindings
    }

    protected fun scenarios(): Map<String, Pair<AccountUsage, PercentDisplay>> {
        val oneCredit = listOf(ResetCredit("c1", now - 1000, now + 60 * minute, "available"))
        val twoCredits = oneCredit + ResetCredit("c2", now - 1000, now + 120 * minute, "available")
        return buildMap {
            for (mode in PercentDisplay.entries) {
                put("warning $mode", usage(15.0) to mode)
                put("critical $mode", usage(5.0) to mode)
                put("exhausted $mode", usage(0.0, exhausted = true) to mode)
            }
            put("reset in 10 minutes", usage(80.0, resetAt = now + 10 * minute) to PercentDisplay.LEFT)
            put("reset in 1 minute", usage(80.0, resetAt = now + 30_000L) to PercentDisplay.LEFT)
            put("one credit", usage(80.0, credits = oneCredit) to PercentDisplay.LEFT)
            put("two credits", usage(80.0, credits = twoCredits) to PercentDisplay.LEFT)
            put(
                "reconnect",
                usage(80.0, connection = ConnectionStatus.RECONNECT_REQUIRED) to PercentDisplay.LEFT,
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NotificationTextsEnglishTest : NotificationTextsFixture() {

    @Test
    fun `every line reads the same from resources as from the default`() {
        for ((name, scenario) in scenarios()) {
            val (usage, mode) = scenario
            assertEquals(
                name,
                lines(usage, mode, EnglishNotificationTexts),
                lines(usage, mode, resourceTexts),
            )
        }
    }

    @Test
    fun `the scenarios say what the English tests pin`() {
        // Guards the sweep above against comparing two empty lists: the default wording is the
        // one NotificationEvaluatorTest asserts, so a few anchors keep this file honest.
        val all = scenarios().mapValues { (_, s) -> lines(s.first, s.second, resourceTexts) }
        assertEquals(listOf("Konto A · 5h limit: less than 20% remaining"), all["warning LEFT"])
        assertEquals(listOf("Konto A · 5h limit: more than 90% used"), all["critical USED"])
        assertEquals(listOf("Konto A · 5h limit exhausted"), all["exhausted LEFT"])
        assertEquals(listOf("Konto A · 5h limit resets in about 10 minutes"), all["reset in 10 minutes"])
        assertEquals(listOf("Konto A · a reset credit expires in 1h", "Konto A · 1 reset credit available"), all["one credit"])
        assertEquals(
            listOf("Konto A · a reset credit expires in 1h", "Konto A · a reset credit expires in 2h", "Konto A · 2 reset credits available"),
            all["two credits"],
        )
        assertEquals(listOf("Konto A needs to be reconnected"), all["reconnect"])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class NotificationTextsGermanTest : NotificationTextsFixture() {

    private fun german(name: String): List<String> {
        val (usage, mode) = scenarios().getValue(name)
        return lines(usage, mode, resourceTexts)
    }

    @Test
    fun `the tiers follow the display mode and use the glossary`() {
        assertEquals(listOf("Konto A · 5h limit: weniger als 20 % übrig"), german("warning LEFT"))
        assertEquals(listOf("Konto A · 5h limit: mehr als 80 % verbraucht"), german("warning USED"))
        assertEquals(listOf("Konto A · 5h limit: weniger als 10 % übrig"), german("critical LEFT"))
        assertEquals(listOf("Konto A · 5h limit: mehr als 90 % verbraucht"), german("critical USED"))
    }

    @Test
    fun `an exhausted limit reads the same in both modes`() {
        assertEquals(listOf("Konto A · 5h limit aufgebraucht"), german("exhausted LEFT"))
        assertEquals(listOf("Konto A · 5h limit aufgebraucht"), german("exhausted USED"))
    }

    @Test
    fun `the reset heads-up pluralises its minutes`() {
        assertEquals(listOf("Konto A · 5h limit: Reset in etwa 10 Minuten"), german("reset in 10 minutes"))
        assertEquals(listOf("Konto A · 5h limit: Reset in etwa 1 Minute"), german("reset in 1 minute"))
    }

    @Test
    fun `a credit deadline uses the German countdown and the inventory pluralises`() {
        assertEquals(
            listOf("Konto A · ein Reset-Credit läuft in 1 Std. ab", "Konto A · 1 Reset-Credit verfügbar"),
            german("one credit"),
        )
        assertEquals(
            listOf(
                "Konto A · ein Reset-Credit läuft in 1 Std. ab",
                "Konto A · ein Reset-Credit läuft in 2 Std. ab",
                "Konto A · 2 Reset-Credits verfügbar",
            ),
            german("two credits"),
        )
    }

    @Test
    fun `a lapsed sign-in asks to reconnect`() {
        assertEquals(listOf("Konto A muss neu verbunden werden"), german("reconnect"))
    }
}
