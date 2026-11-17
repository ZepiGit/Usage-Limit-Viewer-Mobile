package com.usagelimits.statusicon

import android.graphics.Bitmap
import android.graphics.Color
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
 * Coarse, human-meaningful properties of the status icon bitmap, plus PNGs for inspection under
 * `app/build/reports/status-icons/`. Pixel-exact goldens would pin font hinting, not behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun `providers without their own glyph share the neutral fallback, unlike Claude`() {
        val claude = render(figure(42), provider = ProviderId.CLAUDE).let { it.region(0, it.width / 3) }
        val fallbacks = ProviderId.entries.filter { it != ProviderId.CLAUDE }.map { provider ->
            render(figure(42), provider = provider).let { it.region(0, it.width / 3) }
        }
        assertEquals(1, fallbacks.toSet().size)
        assertFalse(fallbacks.first() == claude)
    }

    @Test
    fun `writes every content kind at every density for inspection`() {
        val directory = File("build/reports/status-icons").apply { mkdirs() }
        val cases = listOf(
            "42" to StatusIcon(figure(42), false), "41" to StatusIcon(figure(41), false),
            "5" to StatusIcon(figure(5), false), "0" to StatusIcon(figure(0), false),
            "99" to StatusIcon(figure(99), false), "42-outdated" to StatusIcon(figure(42), true),
            "none" to StatusIcon(StatusIconContent.None, false), "none-outdated" to StatusIcon(StatusIconContent.None, true),
            "exhausted" to StatusIcon(StatusIconContent.Exhausted, false), "error" to StatusIcon(StatusIconContent.Error, false),
            "unknown" to StatusIcon(StatusIconContent.Unknown, false),
        )
        for (provider in listOf(ProviderId.CLAUDE, ProviderId.CODEX)) for ((density, scale) in densities) for ((name, icon) in cases) {
            val file = File(directory, "${provider.id}-$name-$density.png")
            file.outputStream().use { StatusIconRenderer.render(provider, icon, scale).compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 0)
        }
    }
}
