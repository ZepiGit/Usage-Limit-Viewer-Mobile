package com.usagelimits.widget

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ink follows the panel. A light panel with light text is a blank widget, and the old
 * fixed palette produced exactly that the moment a launcher or a user changed the panel.
 */
class WidgetStyleTest {

    @Test
    fun `the default is the app's dark panel at full opacity with light ink`() {
        val style = WidgetStyle()
        assertEquals(Color(0xFF0F0F0E), style.background.color)
        assertEquals(100, style.background.clampedOpacity)
        assertFalse(style.background.isTransparent)
        assertTrue(style.lightInk)
        assertEquals(WidgetBackground.DEFAULT_ARGB, WidgetStyle.CHOICES.first().argb)
    }

    @Test
    fun `a light panel gets dark ink and a dark panel light ink`() {
        val cream = WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt(), 100))
        val navy = WidgetStyle(WidgetBackground(0xFF16213A.toInt(), 100))
        assertFalse(cream.lightInk)
        assertTrue(navy.lightInk)
        // The words switch with the ink: amber is a figure tone only on the dark side, and is
        // pulled down to clear 4.5:1 on the light one. Healthy and moderate figures are plain ink.
        assertEquals(WidgetStyle.Amber, navy.textColor(Severity.LOW))
        assertTrue(cream.textColor(Severity.LOW) != WidgetStyle.Amber)
        assertEquals(navy.textPrimary, navy.textColor(Severity.MEDIUM))
        // The bar is the data hue on both, in the panel's own form of it.
        val row = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 40.0, null, Severity.MEDIUM)
        assertEquals(WidgetStyle.Terracotta, navy.bar(row))
        assertEquals(WidgetStyle.TerracottaOnLight, cream.bar(row))
    }

    @Test
    fun `opacity is folded into the panel colour and clamped`() {
        val half = WidgetBackground(0xFFFFFFFF.toInt(), 50)
        assertEquals(0.5f, half.color.alpha, 0.01f)
        assertEquals(1f, half.color.red, 0.001f)
        assertEquals(100, WidgetBackground(0, 140).clampedOpacity)
        assertEquals(0, WidgetBackground(0, -5).clampedOpacity)
        assertTrue(WidgetBackground(0xFFFFFFFF.toInt(), 0).isTransparent)
    }

    @Test
    fun `auto tone is continuous as opacity crosses zero`() {
        // WGT-003. The old rule special-cased full transparency and inverted its guess there,
        // so a white panel chose dark ink at 1% opacity and light ink at 0% — a deterministic
        // black/white flip from one nudge of the opacity slider. AUTO now reads the chosen
        // panel colour at every opacity; a see-through widget whose wallpaper defeats that
        // assumption is what the explicit LIGHT/DARK overrides are for.
        val white = WidgetBackground(0xFFFFFFFF.toInt(), 100)
        assertFalse(WidgetStyle(white).lightInk)
        assertFalse(WidgetStyle(white.copy(opacityPercent = 5)).lightInk)
        assertFalse(WidgetStyle(white.copy(opacityPercent = 0)).lightInk)
        val navy = WidgetBackground(0xFF16213A.toInt(), 0)
        assertTrue(WidgetStyle(navy).lightInk)
        // The overrides are untouched, at every opacity.
        assertTrue(WidgetStyle(white.copy(opacityPercent = 0), WidgetTextTone.LIGHT).lightInk)
        assertFalse(WidgetStyle(navy, WidgetTextTone.DARK).lightInk)
        assertTrue(WidgetStyle(white, WidgetTextTone.LIGHT).lightInk)
    }

    @Test
    fun `a stale full quota is grey, not the data hue`() {
        // WGT-006. Data validity outranks quota decoration: a stale reading is not one to be trusted.
        val full = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Terracotta, style.bar(full))
        assertEquals(WidgetStyle.Stale, style.bar(full, Severity.STALE))
        assertEquals(style.textColor(Severity.STALE), style.barText(full, Severity.STALE))
    }

    @Test
    fun `a row whose reset has passed is drawn stale, not in its cached colour`() {
        // The figure is the spent window's; the provider has opened a fresh one the cache
        // has not seen. The data hue may not vouch for it.
        val overtaken = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY, resetElapsed = true)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Stale, style.bar(overtaken))
        assertEquals(style.textColor(Severity.STALE), style.barText(overtaken))
        // An account-level failure still outranks it, and a failed reading draws no fill at all.
        assertNull(style.bar(overtaken, Severity.ERROR))
    }

    @Test
    fun `an error full quota draws the bare track`() {
        val full = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY)
        val style = WidgetStyle()
        assertNull(style.bar(full, Severity.ERROR))
        assertEquals(style.textColor(Severity.ERROR), style.barText(full, Severity.ERROR))
    }

    @Test
    fun `a healthy short window does not inherit an exhausted long window's treatment`() {
        // The other direction of WGT-006: validity is DATA validity only. Quota severity stays
        // per row, so an exhausted monthly window never recolours a healthy five-hour one.
        val short = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 40.0, null, Severity.MEDIUM)
        val style = WidgetStyle()
        assertEquals(WidgetStyle.Terracotta, style.bar(short))
        assertEquals(WidgetStyle.Terracotta, style.bar(short, validity = null))
        // And a depleted long horizon on the same account does not make the data untrusted.
        val exhaustedAccount = WidgetAccount("a", "A", null, emptyList(), Severity.EXHAUSTED)
        assertNull(exhaustedAccount.dataValidity)
    }

    @Test
    fun `the data hue falls back to ink where the panel would swallow it`() {
        // Terracotta on the Terracotta panel measures 2.9:1; the bar is ink there.
        val terracottaPanel = WidgetStyle(WidgetBackground(0xFF6E3A2B.toInt(), 100))
        assertEquals(terracottaPanel.textPrimary, terracottaPanel.data)
        assertEquals(WidgetStyle.Terracotta, WidgetStyle().data)
        // Words fall back to the primary ink where the quiet tone would measure under 4.5:1.
        assertEquals(terracottaPanel.textPrimary, terracottaPanel.textSecondary)
    }

    @Test
    fun `data validity is staleness, failure or reconnection — nothing else`() {
        assertNull(WidgetAccount("a", "A", null, emptyList(), Severity.HEALTHY).dataValidity)
        assertEquals(Severity.STALE, WidgetAccount("a", "A", null, emptyList(), Severity.STALE).dataValidity)
        assertEquals(Severity.ERROR, WidgetAccount("a", "A", null, emptyList(), Severity.ERROR).dataValidity)
        assertEquals(
            Severity.ERROR,
            WidgetAccount("a", "A", null, emptyList(), Severity.EXHAUSTED, requiresReauthentication = true).dataValidity,
        )
    }

    @Test
    fun `stored rows map onto a style and the old flag still wins`() {
        val fresh = WidgetStyle.fromStored(null, null, transparent = false, textTone = null)
        assertEquals(WidgetStyle(), fresh)

        val legacy = WidgetStyle.fromStored(null, 100, transparent = true, textTone = "nonsense")
        assertTrue(legacy.background.isTransparent)
        assertEquals(WidgetTextTone.AUTO, legacy.textTone)

        val chosen = WidgetStyle.fromStored(0xFF16213A.toInt(), 70, transparent = false, textTone = "DARK")
        assertEquals(70, chosen.background.clampedOpacity)
        assertEquals(WidgetTextTone.DARK, chosen.textTone)
        assertFalse(chosen.lightInk)
    }

    @Test
    fun `a chosen panel colour is painted as chosen, never as its ink`() {
        // White stays white and black stays black from the swatch, through the stored row,
        // to the colour the widget paints; only the ink flips between them.
        val white = WidgetStyle.CHOICES.first { it.id == "white" }
        val black = WidgetStyle.CHOICES.first { it.id == "black" }
        val whiteStyle = WidgetStyle.fromStored(white.argb, 100, transparent = false, textTone = "AUTO")
        val blackStyle = WidgetStyle.fromStored(black.argb, 100, transparent = false, textTone = "AUTO")
        assertEquals(Color.White, whiteStyle.background.color)
        assertEquals(Color.Black, blackStyle.background.color)
        assertFalse(whiteStyle.lightInk)
        assertTrue(blackStyle.lightInk)
        assertEquals(Color(0xFF1A1918), whiteStyle.textPrimary)
        assertEquals(Color(0xFFF0EEE6), blackStyle.textPrimary)
    }

    @Test
    fun `the widget view derives transparency from its style`() {
        val view = WidgetUpdater.WidgetView(WidgetSnapshot.Empty, WidgetStyle(WidgetBackground(0, 0)))
        assertTrue(view.transparent)
        assertFalse(WidgetUpdater.WidgetView(WidgetSnapshot.Empty).transparent)
    }

    @Test
    fun `an exhausted row draws no fill whatever percentage it still reports`() {
        for (style in listOf(WidgetStyle(), WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt(), 100)))) {
            val blockedFull = WidgetRow("Weekly", WindowCategory.WEEKLY, 100.0, null, Severity.EXHAUSTED)
            assertNull(style.bar(blockedFull))
            assertEquals(style.textColor(Severity.EXHAUSTED), style.barText(blockedFull))

            // Full is a full data bar now; there is no separate "untouched" colour.
            val healthyFull = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 100.0, null, Severity.HEALTHY)
            assertEquals(style.data, style.bar(healthyFull))
            val small = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, 0.4, null, Severity.LOW)
            assertEquals(style.data, style.bar(small))
            // The last fifth is told by the figure, not the fill.
            assertEquals(style.textColor(Severity.LOW), style.barText(small))

            // Data validity still outranks both.
            assertEquals(style.stale, style.bar(blockedFull, Severity.STALE))
            assertEquals(style.stale, style.bar(healthyFull, Severity.STALE))
            assertNull(style.bar(healthyFull, Severity.UNKNOWN))
            assertEquals(style.stale, style.bar(blockedFull.copy(resetElapsed = true)))
        }
    }
}
