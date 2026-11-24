package com.usagelimits.statusicon

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Coarse, human-meaningful properties of the status icon bitmap, plus contact sheets for
 * inspection under `app/build/reports/status-icons/`. Pixel-exact goldens would pin font hinting,
 * not behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconRenderingTest {

    private val densities = listOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)

    private fun render(content: StatusIconContent, outdated: Boolean = false, provider: ProviderId = ProviderId.CLAUDE, density: Float = 3f) =
        StatusIconRenderer.render(provider, StatusIcon(content, outdated), density)

    private fun figure(n: Int) = StatusIconContent.Figure(n)

    /** Whether any pixel inside the column range [x0, x1) and row range [y0, y1) carries ink. */
    private fun Bitmap.inked(x0: Int = 0, x1: Int = width, y0: Int = 0, y1: Int = height): Boolean =
        (x0 until x1).any { x -> (y0 until y1).any { y -> Color.alpha(getPixel(x, y)) > 0 } }

    private fun Bitmap.region(x0: Int, x1: Int, y0: Int = 0, y1: Int = height): List<Int> =
        (y0 until y1).flatMap { y -> (x0 until x1).map { x -> getPixel(x, y) } }

    /**
     * The icon as the status bar draws it: fitted, with filtering, into the 15 dp slot, so its 3:2
     * frame lands at 15 × 10 dp (26 × 18 px at 280 dpi, measured about 24 × 14 px of ink).
     */
    private fun trueSize(bitmap: Bitmap, density: Float): Bitmap =
        Bitmap.createScaledBitmap(bitmap, Math.round(15 * density), Math.round(10 * density), true)

    /** The rows below the digits and the glyph, where only the underline may draw. */
    private fun Bitmap.underlineBand() = inked(y0 = height * 22 / 24, y1 = height)

    @Test
    fun `the bitmap is a 3 to 2 frame whose longer side is 48 dp at every density`() {
        for ((name, density) in densities) {
            val bitmap = render(figure(42), density = density)
            assertEquals(name, Math.round(48 * density), bitmap.width)
            assertEquals(name, Math.round(32 * density), bitmap.height)
        }
    }

    @Test
    fun `every content is an alpha-only mask`() {
        val contents = listOf(figure(5), figure(42), StatusIconContent.None, StatusIconContent.Exhausted,
            StatusIconContent.Error, StatusIconContent.Unknown)
        for (content in contents) {
            val bitmap = render(content, outdated = content is StatusIconContent.Figure)
            assertTrue("$content draws something", bitmap.inked())
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                // The system keeps only the alpha, so colour must carry nothing: ink is white.
                if (Color.alpha(pixel) > 0) assertEquals("$content at $x,$y", Color.WHITE, pixel or 0xFF000000.toInt())
            }
        }
    }

    @Test
    fun `the left third carries the glyph and the rest carries the figure`() {
        val five = render(figure(5))
        val fortyTwo = render(figure(42))
        val third = five.width / 3

        assertTrue(five.inked(x1 = third))
        assertEquals("the glyph does not depend on the figure", five.region(0, third), fortyTwo.region(0, third))
        assertFalse("5 and 42 differ where the digits are", five.region(third, five.width) == fortyTwo.region(third, five.width))
    }

    @Test
    fun `every other state keeps the glyph on the left beside its own mark`() {
        val glyph = render(figure(42)).let { it.region(0, it.width / 3) }
        val marks = listOf(StatusIconContent.Exhausted, StatusIconContent.Error, StatusIconContent.Unknown).map { content ->
            val bitmap = render(content)
            assertEquals("$content keeps the glyph", glyph, bitmap.region(0, bitmap.width / 3))
            assertTrue("$content draws its mark", bitmap.inked(x0 = bitmap.width / 2))
            bitmap.region(bitmap.width / 3, bitmap.width)
        }
        assertEquals("exhausted, error and unknown look different", 3, marks.toSet().size)
    }

    @Test
    fun `a full allowance draws the glyph alone, centred and larger, with no digits`() {
        val alone = render(StatusIconContent.None)
        val beside = render(figure(42))
        val inkColumns = (0 until alone.width).filter { x -> alone.inked(x0 = x, x1 = x + 1) }

        // Centred: the ink sits as far from the left edge as from the right, within a pixel or two.
        assertTrue(kotlin.math.abs(inkColumns.first() - (alone.width - 1 - inkColumns.last())) <= 2)
        // Nothing where the digits stand beside the glyph in the other layout.
        assertFalse(alone.inked(x0 = alone.width * 31 / 36))
        val glyphWidth = (0 until beside.width / 3).count { x -> beside.inked(x0 = x, x1 = x + 1) }
        assertTrue("larger than the glyph beside a figure", inkColumns.size > glyphWidth)
    }

    @Test
    fun `the underline appears only when the icon is outdated`() {
        for (content in listOf(figure(5), figure(42), StatusIconContent.None)) {
            assertFalse("$content current", render(content).underlineBand())
            assertTrue("$content outdated", render(content, outdated = true).underlineBand())
        }
        for (content in listOf(StatusIconContent.Exhausted, StatusIconContent.Error, StatusIconContent.Unknown)) {
            assertFalse("$content", render(content).underlineBand())
        }
    }

    /**
     * The glyph part of [provider]'s icon as the status bar shows it on a 280 dpi screen: the
     * bitmap fitted into the 15 dp slot (26 × 17 px), then reduced to "ink" or "no ink" per pixel.
     */
    private fun glyphAtStatusBarSize(provider: ProviderId): BooleanArray {
        val density = 1.75f
        val small = trueSize(StatusIconRenderer.render(provider, StatusIcon(figure(42), outdated = false), density), density)
        val columns = small.width * 16 / 36
        return BooleanArray(columns * small.height) { i -> Color.alpha(small.getPixel(i % columns, i / columns)) >= 128 }
    }

    @Test
    fun `every provider keeps a glyph of its own at status bar size`() {
        val glyphs = ProviderId.entries.associateWith(::glyphAtStatusBarSize)
        // Two glyphs a person can tell apart differ in a visible patch, not in a stray pixel or two:
        // the closest pairs (Codex and Devin, Codex and OpenCode) measured about 18 of 200 pixels
        // when this was written.
        val closest = glyphs.entries.toList().let { all ->
            all.indices.flatMap { i -> (i + 1 until all.size).map { j -> all[i] to all[j] } }
        }.map { (a, b) -> Triple(a.key, b.key, a.value.indices.count { a.value[it] != b.value[it] }) }
            .minBy { it.third }
        assertTrue("${closest.first} and ${closest.second} differ in only ${closest.third} pixels",
            closest.third >= 12)
    }

    /**
     * Per density a contact sheet (every provider × every content kind, at the bitmap's own size)
     * and a true-size sheet: each icon fitted into the status bar's 15 dp slot the way the system
     * does it, then magnified ×8 nearest-neighbour, beside a simulated three-account strip. The
     * true-size sheet is the one to judge the glyphs by.
     */
    @Test
    fun `writes a contact sheet and a true-size sheet per density for inspection`() {
        val directory = File("build/reports/status-icons").apply { mkdirs() }
        val kinds = listOf(
            "42" to StatusIcon(figure(42), false), "5" to StatusIcon(figure(5), false),
            "0" to StatusIcon(figure(0), false), "99" to StatusIcon(figure(99), false),
            "42 old" to StatusIcon(figure(42), true), "100" to StatusIcon(StatusIconContent.None, false),
            "100 old" to StatusIcon(StatusIconContent.None, true), "exhausted" to StatusIcon(StatusIconContent.Exhausted, false),
            "error" to StatusIcon(StatusIconContent.Error, false), "unknown" to StatusIcon(StatusIconContent.Unknown, false),
        )
        val providers = ProviderId.entries
        val strip = listOf(
            ProviderId.CLAUDE to StatusIcon(figure(42), false),
            ProviderId.CODEX to StatusIcon(figure(7), true),
            ProviderId.KIMI to StatusIcon(StatusIconContent.Exhausted, false),
        )
        for ((name, density) in densities) {
            val contact = sheet(providers, kinds) { provider, icon -> StatusIconRenderer.render(provider, icon, density) }
            val trueSize = sheet(providers, kinds, strip) { provider, icon ->
                magnified(trueSize(StatusIconRenderer.render(provider, icon, density), density))
            }
            for ((kind, bitmap) in listOf("contact" to contact, "true-size" to trueSize)) {
                val file = File(directory, "status-icons-$kind-$name.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(file.length() > 0)
            }
        }
    }

    /** Rows of providers, columns of content kinds, labelled, white ink on the dark status bar. */
    private fun sheet(
        providers: List<ProviderId>,
        kinds: List<Pair<String, StatusIcon>>,
        strip: List<Pair<ProviderId, StatusIcon>> = emptyList(),
        draw: (ProviderId, StatusIcon) -> Bitmap,
    ): Bitmap {
        val sample = draw(ProviderId.CLAUDE, kinds.first().second)
        val cellWidth = maxOf(sample.width, 90) + 16
        val cellHeight = sample.height + 34
        val labelWidth = 110
        val rows = providers.size + if (strip.isEmpty()) 0 else 1
        val sheet = createBitmap(labelWidth + cellWidth * kinds.size, cellHeight * (rows + 1) + 8)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(Color.rgb(0x1B, 0x1C, 0x20))
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 14f }
        for ((column, kind) in kinds.withIndex()) canvas.drawText(kind.first, labelWidth + column * cellWidth + 4f, 24f, label)
        for ((row, provider) in providers.withIndex()) {
            val top = cellHeight * (row + 1)
            canvas.drawText(provider.displayName, 6f, top + cellHeight / 2f, label)
            for ((column, kind) in kinds.withIndex()) {
                canvas.drawBitmap(draw(provider, kind.second), labelWidth + column * cellWidth + 4f, top + 4f, null)
            }
        }
        if (strip.isNotEmpty()) {
            val top = cellHeight * (providers.size + 1) + 8
            canvas.drawText("3 pinned", 6f, top + cellHeight / 2f, label)
            var left = labelWidth + 4f
            for ((provider, icon) in strip) {
                val bitmap = draw(provider, icon)
                canvas.drawBitmap(bitmap, left, top + 4f, null)
                left += bitmap.width
            }
        }
        return sheet
    }

    private fun magnified(bitmap: Bitmap): Bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width * 8, bitmap.height * 8, false)
}
