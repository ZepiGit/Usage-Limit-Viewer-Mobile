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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import kotlin.math.roundToInt

/**
 * Draws a status icon: the provider glyph on the left, the content on the right, in a 3:2 frame.
 *
 * The status bar keeps only a small icon's alpha and tints it with one system colour, so the
 * bitmap is white ink on transparency and carries nothing in its colour. Android fits whatever it
 * gets into a fixed square slot (`docs/research/statusbar-icons-platform-facts.md`, section 1,
 * "Small icon rules"), so the 3:2 frame is drawn at about 15 × 10 dp beside the clock. The longer side is
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

    /** [provider] null is a provider this build does not know: it gets the neutral glyph. */
    fun render(provider: ProviderId?, icon: StatusIcon, density: Float): Bitmap {
        val bitmap = createBitmap((WIDTH_DP * density).roundToInt(), (HEIGHT_DP * density).roundToInt())
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / GRID_WIDTH, bitmap.height / GRID_HEIGHT)
        draw(canvas, glyphs[provider] ?: fallback, icon)
        return bitmap
    }

    /**
     * The layout, in grid units. Glyph on the left, content centred in the rest, the underline
     * along the bottom rows that nothing else touches. A full allowance has no content and draws
     * the glyph alone, centred and larger; its underline sits under the glyph.
     */
    private fun draw(canvas: Canvas, glyph: Path, icon: StatusIcon) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Two units between glyph and digits: at status bar size that is the one pixel of gap
        // that keeps a wide glyph (Claude's arms, Meta's rings) from touching the first digit.
        val glyphBox = RectF(0.5f, 4.5f, 15.5f, 19.5f)
        val content = RectF(17.5f, 2f, 35.5f, 21f)
        val underlineTop = 22f
        val underlineBottom = 23.5f
        val centre = content.centerX()

        when (val shown = icon.content) {
            StatusIconContent.None -> {
                val alone = RectF(GRID_WIDTH / 2 - 9f, 3f, GRID_WIDTH / 2 + 9f, 21f)
                drawGlyph(canvas, glyph, alone, ink)
                if (icon.outdated) canvas.drawRect(alone.left, underlineTop, alone.right, underlineBottom, ink)
            }
            is StatusIconContent.Figure -> {
                drawGlyph(canvas, glyph, glyphBox, ink)
                val drawn = drawDigits(canvas, shown.value.toString(), content, ink)
                if (icon.outdated) canvas.drawRect(drawn.left, underlineTop, drawn.right, underlineBottom, ink)
            }
            StatusIconContent.Exhausted -> {
                drawGlyph(canvas, glyph, glyphBox, ink)
                // A crossed circle: nothing left, whichever side the figures count.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 2.6f
                canvas.drawCircle(centre, 11.5f, 8.2f, ink)
                canvas.drawLine(centre - 5.8f, 17.3f, centre + 5.8f, 5.7f, ink)
                ink.style = Paint.Style.FILL
            }
            StatusIconContent.Error -> {
                drawGlyph(canvas, glyph, glyphBox, ink)
                // "!" as shapes rather than a font glyph, so its weight matches the digits'.
                canvas.drawRoundRect(RectF(centre - 1.6f, 2f, centre + 1.6f, 15f), 1f, 1f, ink)
                canvas.drawCircle(centre, 19f, 1.9f, ink)
            }
            StatusIconContent.Unknown -> {
                drawGlyph(canvas, glyph, glyphBox, ink)
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
        canvas.withTranslation(left, bounds.top) {
            scale(scaleX, scaleY)
            drawText(text, -box.left.toFloat(), -box.top.toFloat(), ink)
        }
        return RectF(left, bounds.top, left + width, bounds.bottom)
    }

    /** A glyph authored on a 24-unit square, fitted into [bounds]. */
    private fun drawGlyph(canvas: Canvas, glyph: Path, bounds: RectF, ink: Paint) {
        canvas.withSave {
            translate(bounds.left, bounds.top)
            scale(bounds.width() / 24f, bounds.height() / 24f)
            drawPath(glyph, ink)
        }
    }

    /**
     * One bold silhouette per provider on a 24-unit square, simplified from the marks in
     * `assets/providers/` (sources and licences in `docs/provider-assets.md`). They are judged at
     * the size the status bar draws them, about 10 px high on a 280 dpi screen: thin strokes,
     * small counters and fine detail of the real marks vanish there, so each glyph keeps only the
     * one shape that tells it apart. The path data lives in `shared/glyphs/status-icon-glyphs.json`,
     * which the iOS widget draws too.
     */
    private val glyphs: Map<ProviderId, Path> by lazy {
        StatusIconGlyphs.shipped.paths.mapValues { (_, data) -> PathParser.createPathFromPathData(data) }
    }

    /**
     * The neutral glyph for a provider without one: a plain disc, which no provider mark and no
     * content sign resembles.
     */
    private val fallback: Path by lazy { PathParser.createPathFromPathData(StatusIconGlyphs.shipped.fallback) }
}
