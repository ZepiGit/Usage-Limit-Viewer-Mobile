package com.usagelimits.statusicon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PERCENT_TOKEN_OVER_NINETY_NINE
import com.usagelimits.core.model.PERCENT_TOKEN_UNDER_ONE
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a shade row says for each state an icon can show, in English and German. The words use the
 * presentation's full figure, never the icon's shortened one: an icon showing 0 says "<1% left".
 * Without a headline window the line is the state alone, so these cases need no clock or zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class StatusIconTextsTest {

    private val texts get() = ResourceStatusIconTexts(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun presentation(token: String, side: PercentDisplay = PercentDisplay.LEFT, exhausted: Boolean = false) =
        WindowPresentation(token, side, fill = null, fillState = FillState.DATA, exhausted = exhausted)

    private fun line(icon: StatusIcon, presentation: WindowPresentation) = texts.reading(icon, presentation)

    private val current = { content: StatusIconContent -> StatusIcon(content, outdated = false) }

    @Test
    fun `the title carries the figure while the detail line carries only the window and reset`() {
        val window = UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
            usedPercent = 58.0, periodSeconds = 18_000, resetAt = 1L, exhausted = false)
        assertEquals("42% left", texts.reading(current(StatusIconContent.Figure(42)), presentation("42")))
        assertEquals("5h limit · Reset due", texts.windowDetails(window, nowMs = 2L))
        assertEquals("5h limit", texts.windowDetails(window.copy(resetAt = null), nowMs = 2L))
        assertEquals(null, texts.windowDetails(null, nowMs = 2L))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German separates the title from the window and reset too`() {
        val window = UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
            usedPercent = 58.0, periodSeconds = 18_000, resetAt = 1L, exhausted = false)
        assertEquals("42 % übrig", texts.reading(current(StatusIconContent.Figure(42)), presentation("42")))
        assertEquals("5‑Std.-Limit · Reset fällig", texts.windowDetails(window, nowMs = 2L))
    }

    @Test
    fun `the header names the provider and includes a supplied account label`() {
        assertEquals("OpenAI Codex", texts.subText(ProviderId.CODEX, accountLabel = null))
        assertEquals("Claude · Work", texts.subText(ProviderId.CLAUDE, accountLabel = "Work"))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German headers retain the same provider names and the account label`() {
        assertEquals("OpenAI Codex", texts.subText(ProviderId.CODEX, accountLabel = null))
        assertEquals("Claude · Geschäftlich", texts.subText(ProviderId.CLAUDE, accountLabel = "Geschäftlich"))
    }

    @Test
    fun `a figure is said in full with its side, not as the icon shortens it`() {
        assertEquals("42% left", line(current(StatusIconContent.Figure(42)), presentation("42")))
        assertEquals("<1% left", line(current(StatusIconContent.Figure(0)), presentation(PERCENT_TOKEN_UNDER_ONE)))
        assertEquals(">99% used", line(current(StatusIconContent.Figure(99)), presentation(PERCENT_TOKEN_OVER_NINETY_NINE, PercentDisplay.USED)))
        assertEquals("100% left", line(current(StatusIconContent.None), presentation("100")))
    }

    @Test
    fun `an underlined figure says that the reading may be out of date`() {
        assertEquals("42% left, may be out of date", line(StatusIcon(StatusIconContent.Figure(42), outdated = true), presentation("42")))
        assertEquals("100% left, may be out of date", line(StatusIcon(StatusIconContent.None, outdated = true), presentation("100")))
    }

    @Test
    fun `every sign that replaces the figure is named in words`() {
        assertEquals("Exhausted", line(current(StatusIconContent.Exhausted), presentation("0", exhausted = true)))
        assertEquals("No percentage reported", line(current(StatusIconContent.Unknown), WindowPresentation.absent(PercentDisplay.LEFT)))
        assertEquals("Reconnect required", texts.reconnectRequired())
        assertEquals("Refresh failed", texts.refreshFailed())
    }

    @Test
    @Config(qualifiers = "de")
    fun `German says every state in German`() {
        assertEquals("<1 % übrig", line(current(StatusIconContent.Figure(0)), presentation(PERCENT_TOKEN_UNDER_ONE)))
        assertEquals("42 % verbraucht, möglicherweise veraltet",
            line(StatusIcon(StatusIconContent.Figure(42), outdated = true), presentation("42", PercentDisplay.USED)))
        assertEquals("Aufgebraucht", line(current(StatusIconContent.Exhausted), presentation("0", exhausted = true)))
        assertEquals("Kein Prozentwert gemeldet", line(current(StatusIconContent.Unknown), WindowPresentation.absent(PercentDisplay.LEFT)))
        assertEquals("Neuverbindung erforderlich", texts.reconnectRequired())
        assertEquals("Aktualisierung fehlgeschlagen", texts.refreshFailed())
    }
}
