package com.usagelimits.widget

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * The widget words, read from resources (R4).
 *
 * Under `en` each resource must equal the English text its pure twin still produces, and the
 * picker labels the manifest points at must resolve to the names the widgets always had; under
 * `de` the credit status lines and the uncapped amount read German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class WidgetTextsEnglishTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resources: Resources = context.resources

    private fun row(
        severity: Severity = Severity.HEALTHY,
        fetchedAt: Long? = System.currentTimeMillis(),
        balances: List<CreditBalance> = listOf(CreditBalance("USD", BigDecimal(5))),
        statusMessage: String? = null,
    ) = CreditWidgetRow("a", "A", "fal.ai", balances, severity, fetchedAt, statusMessage)

    @Test
    fun `credit status lines and amounts read the same from resources as from the English twin`() {
        val texts = ResourceCreditWidgetTexts(resources)
        val rows = listOf(
            row(Severity.UNKNOWN, fetchedAt = null, balances = emptyList()),
            row(Severity.ERROR, balances = emptyList()),
            row(Severity.ERROR, balances = emptyList(), statusMessage = "rejected"),
            row(statusMessage = "rejected"),
            row(Severity.STALE),
            row(),
            row(balances = listOf(CreditBalance("USD", BigDecimal("12.5"), unlimited = true))),
        )
        for (row in rows) {
            assertEquals(statusText(row, EnglishCreditWidgetTexts), statusText(row, texts))
            assertEquals(amountText(row, EnglishCreditWidgetTexts), amountText(row, texts))
        }
        assertEquals("Could not be read", statusText(rows[1], texts))
        assertEquals("Not read yet", statusText(rows[0], texts))
    }

    @Test
    fun `an empty selection and the status tile read the same from resources as from the English twin`() {
        val none = WidgetSnapshot.Empty.copy(emptyProvider = ProviderId.CLAUDE)
        assertEquals(none.emptyMessage, none.emptyText(resources))
        assertNull(WidgetSnapshot.Empty.emptyText(resources))
        for (count in listOf(0, 1, 2, 6)) {
            val snapshot = WidgetSnapshot.Empty.copy(accountCount = count)
            assertEquals("$count", snapshot.selectionStatusLabel, snapshot.statusLabel(resources))
        }
    }

    @Test
    fun `a status word exists for every severity and an app horizon name resolves to itself`() {
        assertEquals(listOf("OK", "Fair", "Low", "Out", "Stale", "Error", "Unknown", "Limited"),
            listOf(Severity.HEALTHY, Severity.MEDIUM, Severity.LOW, Severity.EXHAUSTED, Severity.STALE,
                Severity.ERROR, Severity.UNKNOWN, Severity.LIMITED).map { resources.getString(statusWordRes(it)) })
        for ((label, category) in listOf("5h limit" to WindowCategory.FIVE_HOUR, "Weekly" to WindowCategory.WEEKLY,
            "Monthly" to WindowCategory.MONTHLY)) {
            assertEquals(label, WidgetRow(label, category, 50.0, null, Severity.HEALTHY).labelText(resources))
        }
        assertEquals("Weekly (Opus)", WidgetRow("Weekly (Opus)", WindowCategory.WEEKLY, 50.0, null, Severity.HEALTHY,
            scope = UsageScope.MODEL).labelText(resources))
        assertEquals("Gemini Pro", WidgetRow("Gemini Pro", WindowCategory.FIVE_HOUR, 50.0, null, Severity.HEALTHY).labelText(resources))
    }

    @Test
    fun `the picker labels keep the names the widgets always had`() {
        val packageManager = context.packageManager
        fun label(receiver: Class<*>) = packageManager.getReceiverInfo(ComponentName(context, receiver), 0).loadLabel(packageManager).toString()
        assertEquals("Usage Summary", label(CompactUsageWidgetReceiver::class.java))
        assertEquals("Usage Bars", label(DetailedUsageWidgetReceiver::class.java))
        assertEquals("Account Rings", label(MinimalUsageWidgetReceiver::class.java))
        assertEquals("Mini Rings", label(MiniRingsWidgetReceiver::class.java))
        assertEquals("Credits", label(CreditsWidgetReceiver::class.java))
    }

    @Test
    fun `the configuration title follows the kind and the colour names are the English ones`() {
        for ((kind, title) in listOf("Usage Summary" to "Usage Summary", "Usage Bars" to "Usage Bars",
            "Account Rings" to "Account Rings", "Mini Rings" to "Mini Rings")) {
            assertEquals(title, resources.getString(widgetKindTitleRes(kind)))
        }
        assertEquals(
            listOf("Charcoal", "Black", "Graphite", "Navy", "Plum", "Forest", "Terracotta", "Cream", "Light grey", "White"),
            WidgetStyle.CHOICES.map { resources.getString(it.labelRes) },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class WidgetTextsGermanTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun row(
        severity: Severity = Severity.HEALTHY,
        fetchedAt: Long? = System.currentTimeMillis(),
        balances: List<CreditBalance> = listOf(CreditBalance("USD", BigDecimal(5))),
        statusMessage: String? = null,
    ) = CreditWidgetRow("a", "A", "fal.ai", balances, severity, fetchedAt, statusMessage)

    @Test
    fun `credit status lines and the uncapped amount read German`() {
        val texts = ResourceCreditWidgetTexts(resources)
        assertEquals("Noch nicht gelesen", statusText(row(Severity.UNKNOWN, fetchedAt = null, balances = emptyList()), texts))
        assertEquals("Konnte nicht gelesen werden", statusText(row(Severity.ERROR, balances = emptyList()), texts))
        val failed = statusText(row(statusMessage = "abgelehnt"), texts)
        assertTrue(failed, failed.startsWith("Aktualisierung fehlgeschlagen: abgelehnt · Stand "))
        val stale = statusText(row(Severity.STALE), texts)
        assertTrue(stale, stale.startsWith("Veraltet · Stand "))
        val fine = statusText(row(), texts)
        assertTrue(fine, fine.startsWith("fal.ai · "))
        val unlimited = amountText(row(balances = listOf(CreditBalance("USD", BigDecimal("12.5"), unlimited = true))), texts)
        assertTrue(unlimited, unlimited.endsWith("diesen Monat ausgegeben"))
    }

    @Test
    fun `the tile labels and empty states read German`() {
        assertEquals("Kein Claude-Konto verbunden. Füge in der App eines hinzu.",
            WidgetSnapshot.Empty.copy(emptyProvider = ProviderId.CLAUDE).emptyText(resources))
        assertEquals("Konto", WidgetSnapshot.Empty.copy(accountCount = 1).statusLabel(resources))
        assertEquals("3 Konten", WidgetSnapshot.Empty.copy(accountCount = 3).statusLabel(resources))
        assertEquals("5‑Std.-Limit", WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 50.0, null, Severity.HEALTHY).labelText(resources))
        assertEquals("Weekly (Opus)", WidgetRow("Weekly (Opus)", WindowCategory.WEEKLY, 50.0, null, Severity.HEALTHY,
            scope = UsageScope.MODEL).labelText(resources))
        assertEquals("Mäßig", resources.getString(statusWordRes(Severity.MEDIUM)))
    }
}
