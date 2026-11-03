package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetMetricsTest {
    private val pixel = WidgetMetrics(70f, 131f, 132f, 66f)

    @Test fun `mini rings allocate height only to populated rows`() {
        val grid = WidgetLayout.Grid(columns = 1, rows = 6)
        assertEquals(1, WidgetLayout.miniVisibleRows(grid, 1))
        assertEquals(3, WidgetLayout.miniVisibleRows(grid, 3))
        assertEquals(6, WidgetLayout.miniVisibleRows(grid, 10))
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

    @Test fun `the ring caption reserves its 9sp line and 4dp inset, growing with the font scale`() {
        // The caption is real text, so the reserve must follow the user's font size or a large
        // setting would clip the first ring row it was meant to sit above.
        assertEquals("caption height at the default font scale", 13f, WidgetLayout.ringCaptionHeight(1f), 0.001f)
        assertEquals("caption height at 1.3x", 15.7f, WidgetLayout.ringCaptionHeight(1.3f), 0.001f)
    }

    @Test fun `a one-cell-tall mini rings widget gives its second ring to the mode caption`() {
        // The deliberate LEFT-mode capacity drop (PLAN section 3): the 56dp minimum is a real 1x1
        // cell, which held two rings before the caption and now holds one rather than clipping.
        val grid = WidgetLayout.miniGrid(56f, 56f)
        assertEquals("a 1x1 cell is a two-ring grid", 2, grid.capacity)
        assertEquals("two accounts showed two rows before the caption", 2, WidgetLayout.miniVisibleRows(grid, 2))
        assertEquals("the caption takes the second row at 56dp", 1, WidgetLayout.miniCaptionedRows(grid, 2, 56f, 1f))
        assertEquals("a larger font scale cannot bring the row back", 1, WidgetLayout.miniCaptionedRows(grid, 2, 56f, 1.3f))
    }

    @Test fun `a tall mini rings host keeps every populated row`() {
        val grid = WidgetLayout.miniGrid(62f, 254f, pixel)
        assertEquals("four accounts fill the four-row grid", 4, WidgetLayout.miniVisibleRows(grid, 4))
        assertEquals("room for the caption and all four rows", 4, WidgetLayout.miniCaptionedRows(grid, 4, 254f, 1f))
        assertEquals("the caption does not cost a ring at 1.3x either", 4, WidgetLayout.miniCaptionedRows(grid, 4, 254f, 1.3f))
        assertEquals("rows still follow the populated count, not the free height", 2, WidgetLayout.miniCaptionedRows(grid, 2, 254f, 1f))
    }

    @Test fun `the caption line is paid for in the font scale the user chose`() {
        // 61dp holds the caption (13dp) plus two 20dp rows at unit scale; at 1.3x the caption is
        // 2.7dp taller and only one row fits.
        val grid = WidgetLayout.Grid(columns = 1, rows = 6)
        assertEquals("two rows at the default scale", 2, WidgetLayout.miniCaptionedRows(grid, 3, 61f, 1f))
        assertEquals("one row at 1.3x", 1, WidgetLayout.miniCaptionedRows(grid, 3, 61f, 1.3f))
    }

    @Test fun `a host shorter than one row still shows one ring`() {
        val grid = WidgetLayout.Grid(columns = 1, rows = 2)
        for (height in listOf(30f, 8f, 0f)) {
            assertEquals("a ${height}dp host never yields zero or negative rows",
                1, WidgetLayout.miniCaptionedRows(grid, 2, height, 1f))
        }
    }
}
