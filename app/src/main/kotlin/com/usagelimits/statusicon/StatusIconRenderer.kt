package com.usagelimits.statusicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import kotlin.math.roundToInt

/**
 * Draws a status icon: the provider glyph on the left, the content on the right, in a 3:2 frame.
 *
 * The status bar keeps only a small icon's alpha and tints it with one system colour, so the
 * bitmap is white ink on transparency and carries nothing in its colour. Android fits whatever it
 * gets into a fixed square slot (`docs/research/statusbar-wide-icon-scaling.md` on the prototype
 * branch), so the 3:2 frame is drawn at about 15 × 10 dp beside the clock. The longer side is
 * authored at 48 dp for the device density, the size above which the system downscales anyway.
 *
 * The owner chose this layout after seeing the measured emulator pixels; it is smaller than the
 * stacked one. [draw] holds every position, so changing the layout changes one function and its
 * rendering tests.
 */
object StatusIconRenderer {

    /** The longer side, before the system fits the bitmap into its slot. */
    private const val WIDTH_DP = 48f
    private const val HEIGHT_DP = 32f

    /** The design grid [draw] lays out on: 36 × 24 units, the 3:2 frame. */
    private const val GRID_WIDTH = 36f
    private const val GRID_HEIGHT = 24f

    fun render(provider: ProviderId, icon: StatusIcon, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (WIDTH_DP * density).roundToInt(),
            (HEIGHT_DP * density).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / GRID_WIDTH, bitmap.height / GRID_HEIGHT)
        draw(canvas, provider, icon)
        return bitmap
    }

    /**
     * The layout, in grid units. Glyph on the left third, content centred in the remaining two
     * thirds, the underline along the bottom rows that nothing else touches. A full allowance has
     * no content and draws the glyph alone, centred and larger; its underline sits under the glyph.
     */
    private fun draw(canvas: Canvas, provider: ProviderId, icon: StatusIcon) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val glyph = RectF(1f, 5f, 15f, 19f)
        val content = RectF(17f, 2f, 35f, 21f)
        val underlineTop = 22f
        val underlineBottom = 23.5f
        val centre = content.centerX()

        when (val shown = icon.content) {
            StatusIconContent.None -> {
                val alone = RectF(GRID_WIDTH / 2 - 9f, 3f, GRID_WIDTH / 2 + 9f, 21f)
                drawGlyph(canvas, provider, alone, ink)
                if (icon.outdated) canvas.drawRect(alone.left, underlineTop, alone.right, underlineBottom, ink)
            }
            is StatusIconContent.Figure -> {
                drawGlyph(canvas, provider, glyph, ink)
                val drawn = drawDigits(canvas, shown.value.toString(), content, ink)
                if (icon.outdated) canvas.drawRect(drawn.left, underlineTop, drawn.right, underlineBottom, ink)
            }
            StatusIconContent.Exhausted -> {
                drawGlyph(canvas, provider, glyph, ink)
                // A crossed circle: nothing left, whichever side the figures count.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 2.6f
                canvas.drawCircle(centre, 11.5f, 8.2f, ink)
                canvas.drawLine(centre - 5.8f, 17.3f, centre + 5.8f, 5.7f, ink)
                ink.style = Paint.Style.FILL
            }
            StatusIconContent.Error -> {
                drawGlyph(canvas, provider, glyph, ink)
                // "!" as shapes rather than a font glyph, so its weight matches the digits'.
                canvas.drawRoundRect(RectF(centre - 1.6f, 2f, centre + 1.6f, 15f), 1f, 1f, ink)
                canvas.drawCircle(centre, 19f, 1.9f, ink)
            }
            StatusIconContent.Unknown -> {
                drawGlyph(canvas, provider, glyph, ink)
                canvas.drawRoundRect(RectF(centre - 6f, 10.2f, centre + 6f, 13.4f), 0.8f, 0.8f, ink)
            }
        }
    }

    /**
     * Bold condensed [text] filling [bounds]' height, centred, squeezed only if wider than [bounds].
     * Returns the box it covered, so an underline spans the digits rather than the empty slot.
     */
    private fun drawDigits(canvas: Canvas, text: String, bounds: RectF, ink: Paint): RectF {
        ink.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        ink.textSize = 24f
        ink.textAlign = Paint.Align.LEFT
        val box = Rect()
        ink.getTextBounds(text, 0, text.length, box)
        val scaleY = bounds.height() / box.height()
        val scaleX = minOf(scaleY, bounds.width() / box.width())
        val width = box.width() * scaleX
        val left = bounds.centerX() - width / 2
        canvas.save()
        canvas.translate(left, bounds.top)
        canvas.scale(scaleX, scaleY)
        canvas.drawText(text, -box.left.toFloat(), -box.top.toFloat(), ink)
        canvas.restore()
        return RectF(left, bounds.top, left + width, bounds.bottom)
    }

    /** The provider's monochrome glyph, authored on a 24-unit square, fitted into [bounds]. */
    private fun drawGlyph(canvas: Canvas, provider: ProviderId, bounds: RectF, ink: Paint) {
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(glyphs.getValue(provider), ink)
        canvas.restore()
    }

    /**
     * Claude has its real glyph here; every other provider shares the neutral fallback until its
     * own glyph is drawn. The fallback is a plain ring of a rounded square, which no provider mark
     * and no content sign resembles.
     */
    private val glyphs: Map<ProviderId, Path> by lazy {
        // Simplified from assets/providers/claude.svg (LobeHub icons, MIT, see
        // docs/provider-assets.md): the Claude Code mascot with its arms and legs thickened so
        // they survive the status bar's 15 dp, eyes cut out.
        val claude = PathParser.createPathFromPathData(
            "M3 5H21V11H24V14H21V17H19V20H17V17H7V20H5V17H3V14H0V11H3Z M6 8V11H8V8Z M16 8V11H18V8Z",
        ).apply { fillType = Path.FillType.EVEN_ODD }
        val fallback = Path().apply {
            addRoundRect(RectF(2f, 2f, 22f, 22f), 5f, 5f, Path.Direction.CW)
            addRoundRect(RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, Path.Direction.CCW)
        }
        ProviderId.entries.associateWith { if (it == ProviderId.CLAUDE) claude else fallback }
    }
}
