package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetMetricsTest {
    private val pixel = WidgetMetrics(70f, 131f, 132f, 66f)

    @Test fun `mini rings allocate height only to populated rows`() {
        val portrait = WidgetLayout.miniGrid(62f, 254f, pixel)
        assertEquals(1, WidgetLayout.miniRows(portrait, 1, 246f))
        assertEquals(3, WidgetLayout.miniRows(portrait, 3, 246f))
        assertEquals(4, WidgetLayout.miniRows(portrait, 10, 246f))
        val landscape = WidgetLayout.miniGrid(256f, 58f, pixel)
        assertEquals(1, WidgetLayout.miniRows(landscape, 1, 50f))
        assertEquals(1, WidgetLayout.miniRows(landscape, 4, 50f))
    }

    @Test fun `a mini host shorter than one row still shows one ring row`() {
        val grid = WidgetLayout.miniGrid(62f, 19f, pixel)
        assertEquals(1, WidgetLayout.miniRows(grid, 2, 11f))
    }

    @Test fun `large text requires a single labelled column at intermediate widths`() {
        assertEquals(1, WidgetLayout.accountColumns(330f, 516f, pixel, fontScale = 1.5f))
    }

    @Test fun `portrait and landscape placements both hold two accounts per cell`() {
        assertEquals(2, WidgetLayout.miniGrid(62.9f, 123.6f, pixel).capacity)
        assertEquals(2, WidgetLayout.miniGrid(124f, 58.2f, pixel).capacity)
        assertEquals(4, WidgetLayout.miniGrid(62f, 254f, pixel).capacity)
        assertEquals(8, WidgetLayout.miniGrid(132f, 254f, pixel).capacity)
        assertEquals(8, WidgetLayout.miniGrid(256f, 124f, pixel).capacity)
    }

    @Test fun `account ring columns require actual space for their labels`() {
        assertEquals(1, WidgetLayout.accountColumns(202f, 254f, pixel))
        assertEquals(1, WidgetLayout.accountColumns(202f, 516f, pixel))
        assertEquals(2, WidgetLayout.accountColumns(330f, 516f, pixel))
    }

    @Test fun `square launcher cells preserve the same capacity contract`() {
        val square = WidgetMetrics(88f, 88f, 88f, 88f)
        assertEquals(2, WidgetLayout.miniGrid(80f, 80f, square).capacity)
        assertEquals(4, WidgetLayout.miniGrid(80f, 168f, square).capacity)
        assertEquals(8, WidgetLayout.miniGrid(168f, 168f, square).capacity)
    }

}
