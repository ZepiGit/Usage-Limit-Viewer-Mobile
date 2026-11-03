package com.usagelimits.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.PERCENT_TOKEN_OVER_NINETY_NINE
import com.usagelimits.core.model.PERCENT_TOKEN_UNDER_ONE
import com.usagelimits.core.model.PERCENT_TOKEN_UNKNOWN
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.withPercentSign
import com.usagelimits.ui.percentFlipLabel
import com.usagelimits.ui.percentFlipLabelRes
import com.usagelimits.ui.percentSideCaption
import com.usagelimits.ui.percentSideCaptionRes
import com.usagelimits.ui.percentSideWord
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.percentSpokenClause
import com.usagelimits.ui.percentSpokenClauseRes
import com.usagelimits.ui.theme.SeverityPalette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The percent template, the side words and the severity labels, read from resources (R4).
 *
 * Under `en` each must equal the pure English function it twins, so moving the words did not change
 * them; under `de` the glossary decides: übrig / verbraucht, and the sign is separated by a space.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class PercentResourcesEnglishTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val tokens = listOf(
        "0", "1", "73", "99", "100",
        PERCENT_TOKEN_UNDER_ONE, PERCENT_TOKEN_OVER_NINETY_NINE, PERCENT_TOKEN_UNKNOWN,
    )

    @Test
    fun `the template keeps the sign attached and the unknown dash bare`() {
        for (token in tokens) {
            assertEquals(token, withPercentSign(token), percentFigure(token, resources))
        }
        assertEquals("73%", percentFigure("73", resources))
    }

    @Test
    fun `the side words equal the English functions in both modes`() {
        for (mode in PercentDisplay.entries) {
            assertEquals(percentSideWord(mode), resources.getString(percentSideWordRes(mode)))
            assertEquals(percentSideCaption(mode), resources.getString(percentSideCaptionRes(mode)))
            assertEquals(percentSpokenClause(mode), resources.getString(percentSpokenClauseRes(mode)))
            assertEquals(percentFlipLabel(mode), resources.getString(percentFlipLabelRes(mode)))
        }
    }

    @Test
    fun `the severity labels equal the English function for every severity`() {
        for (severity in Severity.entries) {
            assertEquals(
                SeverityPalette.label(severity),
                resources.getString(SeverityPalette.labelRes(severity)),
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class PercentResourcesGermanTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun `the template separates the sign and leaves the unknown dash bare`() {
        assertEquals("73 %", percentFigure("73", resources))
        assertEquals("<1 %", percentFigure(PERCENT_TOKEN_UNDER_ONE, resources))
        assertEquals(">99 %", percentFigure(PERCENT_TOKEN_OVER_NINETY_NINE, resources))
        assertEquals("—", percentFigure(PERCENT_TOKEN_UNKNOWN, resources))
    }

    @Test
    fun `the side words follow the glossary`() {
        assertEquals("übrig", text(percentSideWordRes(PercentDisplay.LEFT)))
        assertEquals("verbraucht", text(percentSideWordRes(PercentDisplay.USED)))
        assertEquals("Übrig", text(percentSideCaptionRes(PercentDisplay.LEFT)))
        assertEquals("Verbraucht", text(percentSideCaptionRes(PercentDisplay.USED)))
        assertEquals("übrig", text(percentSpokenClauseRes(PercentDisplay.LEFT)))
        assertEquals("verbraucht", text(percentSpokenClauseRes(PercentDisplay.USED)))
    }

    @Test
    fun `the flip label names the mode a tap switches to`() {
        assertEquals("Prozent verbraucht anzeigen", text(percentFlipLabelRes(PercentDisplay.LEFT)))
        assertEquals("Prozent übrig anzeigen", text(percentFlipLabelRes(PercentDisplay.USED)))
    }

    @Test
    fun `every severity has its own German label`() {
        val labels = Severity.entries.associateWith { text(SeverityPalette.labelRes(it)) }
        assertEquals("Gut", labels[Severity.HEALTHY])
        assertEquals("Mäßig", labels[Severity.MEDIUM])
        assertEquals("Niedrig", labels[Severity.LOW])
        assertEquals("Eingeschränkt", labels[Severity.LIMITED])
        assertEquals("Unbekannt", labels[Severity.UNKNOWN])
        assertEquals("Aufgebraucht", labels[Severity.EXHAUSTED])
        assertEquals("Veraltet", labels[Severity.STALE])
        assertEquals("Fehler", labels[Severity.ERROR])
        assertEquals(Severity.entries.size, labels.values.toSet().size)
    }
}
