package com.usagelimits.feature.overview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The figure's fade identity, from the reading and not from the display mode. A mode flip keeps the
 * key, so the figure fades with the chip; a reading that gains or loses its figure changes it, so
 * the figure is replaced at once.
 */
class FigureFadeTest {
    private fun key(hasMeasurement: Boolean, blocked: Boolean) = figureFade(hasMeasurement, blocked)

    @Test fun `a blocked window without a measurement keeps its key in both modes`() {
        assertEquals(FigureFade.BLOCKED, key(hasMeasurement = false, blocked = true))
    }

    @Test fun `a window's first measurement or a lost one changes the key, blocked or not`() {
        assertEquals(FigureFade.UNKNOWN, key(false, blocked = false))
        assertEquals(FigureFade.MEASURED, key(true, blocked = false))
        assertEquals(FigureFade.BLOCKED, key(false, blocked = true))
        assertEquals(FigureFade.MEASURED, key(true, blocked = true))
    }

    @Test fun `a block that comes or goes beside a figure keeps the key`() {
        assertEquals(FigureFade.MEASURED, key(true, blocked = false))
        assertEquals(FigureFade.MEASURED, key(true, blocked = true))
    }

    @Test fun `a block that comes or goes without a figure changes the key`() {
        assertEquals(FigureFade.UNKNOWN, key(false, blocked = false))
        assertEquals(FigureFade.BLOCKED, key(false, blocked = true))
    }

    @Test fun `a blocked window with a measurement keeps its key in both modes`() {
        assertEquals(FigureFade.MEASURED, key(hasMeasurement = true, blocked = true))
    }
}
