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
     * one shape that tells it apart. The exhaustive `when` makes a new provider a compile error
     * until it has a glyph.
     */
    private val glyphs: Map<ProviderId, Path> by lazy {
        ProviderId.entries.associateWith { provider ->
            PathParser.createPathFromPathData(
                when (provider) {
                    // The Claude Code mascot: body, arms, four legs, two eyes cut out.
                    ProviderId.CLAUDE ->
                        "M3 4H21V10H24V14H21V17H20V21H17V17H7V21H4V17H3V14H0V10H3Z M6 7.5V11H9V7.5Z M15 7.5V11H18V7.5Z"
                    // OpenAI's knot reduced to its outline: a hexagon with a hexagonal hole, a nut.
                    ProviderId.CODEX ->
                        "M22.39 18L12 24L1.61 18L1.61 6L12 0L22.39 6Z M15.81 9.8L12 7.6L8.19 9.8L8.19 14.2L12 16.4L15.81 14.2Z"
                    // Antigravity's arch with its two flared feet, straightened and thickened.
                    ProviderId.ANTIGRAVITY ->
                        "M0.5 22.5C5 19 6 1.5 12 1.5C18 1.5 19 19 23.5 22.5H16.5C15 17 14.2 12.5 12 12.5C9.8 12.5 9 17 7.5 22.5Z"
                    // xAI's crossed strokes as a bold, symmetric X.
                    ProviderId.XAI -> "M2 2H8.5L22 22H15.5Z M15.5 2H22L8.5 22H2Z"
                    // Kimi's K with the dot at its upper right.
                    ProviderId.KIMI ->
                        "M2 4H7.5V22H2Z M7.5 10.5L13.5 4H19.5L10.5 13.5Z M8.5 12.5L20 22H13L5.5 15.5Z " +
                            "M18.4 3C18.4 1.56 19.56 0.4 21 0.4C22.44 0.4 23.6 1.56 23.6 3C23.6 4.44 22.44 5.6 21 5.6C19.56 5.6 18.4 4.44 18.4 3Z"
                    // Devin's four linked cells as four solid hexagons in a diamond.
                    ProviderId.DEVIN ->
                        "M16.16 7.2L12 9.6L7.84 7.2L7.84 2.4L12 0L16.16 2.4Z M8.96 14.4L4.8 16.8L0.64 14.4L0.64 9.6L4.8 7.2L8.96 9.6Z " +
                            "M23.36 14.4L19.2 16.8L15.04 14.4L15.04 9.6L19.2 7.2L23.36 9.6Z M16.16 21.6L12 24L7.84 21.6L7.84 16.8L12 14.4L16.16 16.8Z"
                    // Meta's infinity loop as two rings side by side.
                    ProviderId.META ->
                        "M0.4 12C0.4 8.58 3.18 5.8 6.6 5.8C10.02 5.8 12.8 8.58 12.8 12C12.8 15.42 10.02 18.2 6.6 18.2C3.18 18.2 0.4 15.42 0.4 12Z " +
                            "M4.1 12C4.1 13.38 5.22 14.5 6.6 14.5C7.98 14.5 9.1 13.38 9.1 12C9.1 10.62 7.98 9.5 6.6 9.5C5.22 9.5 4.1 10.62 4.1 12Z " +
                            "M11.2 12C11.2 8.58 13.98 5.8 17.4 5.8C20.82 5.8 23.6 8.58 23.6 12C23.6 15.42 20.82 18.2 17.4 18.2C13.98 18.2 11.2 15.42 11.2 12Z " +
                            "M14.9 12C14.9 13.38 16.02 14.5 17.4 14.5C18.78 14.5 19.9 13.38 19.9 12C19.9 10.62 18.78 9.5 17.4 9.5C16.02 9.5 14.9 10.62 14.9 12Z"
                    // Z.ai's Z with its two offset bars joined into one bold letter.
                    ProviderId.ZAI -> "M3 2H22V6.5L9 17.5H22V22H2V17.5L15 6.5H3Z"
                    // OpenCode's mark as it is: an upright frame around a hollow.
                    ProviderId.OPENCODE -> "M15.5 6.5H8.5V17.5H15.5V6.5Z M19.5 22H4.5V2H19.5V22Z"
                },
            )
        }
    }

    /**
     * The neutral glyph for a provider without one: a plain disc, which no provider mark and no
     * content sign resembles.
     */
    private val fallback: Path by lazy {
        Path().apply { addCircle(12f, 12f, 8.5f, Path.Direction.CW) }
    }
}
