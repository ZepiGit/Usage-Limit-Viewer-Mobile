package com.usagelimits.ui

import com.usagelimits.core.model.PercentDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class PercentWordsTest {

    @Test
    fun `the visible side word follows the mode`() {
        assertEquals("left", percentSideWord(PercentDisplay.LEFT))
        assertEquals("used", percentSideWord(PercentDisplay.USED))
    }

    @Test
    fun `the caption form is the side word capitalised`() {
        assertEquals("Left", percentSideCaption(PercentDisplay.LEFT))
        assertEquals("Used", percentSideCaption(PercentDisplay.USED))
    }

    @Test
    fun `the spoken clause keeps the historical remaining for LEFT`() {
        assertEquals("remaining", percentSpokenClause(PercentDisplay.LEFT))
        assertEquals("used", percentSpokenClause(PercentDisplay.USED))
    }

    @Test
    fun `the flip label names the mode a tap switches to`() {
        assertEquals("Show percentage used", percentFlipLabel(PercentDisplay.LEFT))
        assertEquals("Show percentage left", percentFlipLabel(PercentDisplay.USED))
    }
}
