package com.usagelimits.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.usagelimits.core.model.ClaudeCodeMood

/** The brand fill of the "Claude Code · Color" icon, as in `claudecode-color.svg`. */
internal val ClaudeCodeBrand = Color(0xFFD97757)

// The sweat drop and the sparkles are the figure's own art colours (CH-3 appendix), mid tones that
// read on light and dark grounds alike. The "z" is muted text, so its colour is passed in.
private val SweatColor = Color(0xFF9CC3E6)
private val SparkColor = Color(0xFFE6C24A)

/** The "z" of the appendix (#B4B2A9, the muted text of the dark palette): the still export's default. */
private const val APPENDIX_SLEEP = 0xFFB4B2A9.toInt()

/** The time a figure is given when it must stand still: the mood's still pose, not a frame of its loop. */
internal const val STILL = -1L

/**
 * The still of [mood] for a placed widget, drawn into a square bitmap of [bitmapPx] with the
 * figure, [figurePx] wide, in its centre. [fill] is the ink and [sleepInk] the Asleep "z"; the eyes
 * are cut out, so whatever the widget draws behind the bitmap shows through them. Nothing here
 * reads the app's appearance.
 *
 * Some still particles sit outside the figure's own box (the notes up to 8 % to the side, the
 * sparkles up to 14 % above), so a bitmap about 1.3 times the figure keeps all of them.
 */
fun claudeCodeStillBitmap(
    mood: ClaudeCodeMood,
    @ColorInt fill: Int,
    figurePx: Int,
    bitmapPx: Int = figurePx,
    @ColorInt sleepInk: Int = APPENDIX_SLEEP,
): Bitmap {
    require(bitmapPx >= figurePx) { "the bitmap must hold the figure" }
    val bitmap = ImageBitmap(bitmapPx, bitmapPx)
    val inset = (bitmapPx - figurePx) / 2f
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap),
        Size(bitmapPx.toFloat(), bitmapPx.toFloat())) {
        ClaudeCodeArt(mood, figurePx.toFloat())
            .draw(this, Color(fill), Color(sleepInk), Offset(inset, inset), STILL, amplitude = 0f)
    }
    return bitmap.asAndroidBitmap()
}

/**
 * One figure in one mood at one size: everything it draws with is built here, once, so a frame
 * only computes numbers and draws. Geometry, keyframes, easings and still poses are the CH-3
 * animation appendix's, on the 24-unit grid of `claudecode-color.svg`.
 */
internal class ClaudeCodeArt(private val mood: ClaudeCodeMood, sizePx: Float) {
    private val unit = sizePx / GRID
    private val pose = Pose()

    // The eyes are cleared out of a layer that holds the body, so they show what lies behind the
    // figure on every ground, the same in the app and in a widget bitmap.
    private val layerBounds = Rect(-sizePx / 2f, -sizePx / 2f, sizePx * 1.5f, sizePx * 1.5f)
    private val layerPaint = Paint()

    private val sleep: Array<Glyph> = if (mood == ClaudeCodeMood.ASLEEP) {
        val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        Array(3) { glyph("z", sizePx * (0.16f + 0.05f * it), bold) }
    } else emptyArray()
    private val note: Glyph? = if (mood == ClaudeCodeMood.DANCING) glyph("\u266A", sizePx * 0.24f, Typeface.DEFAULT) else null
    private val sweat: Path? = if (mood == ClaudeCodeMood.TIRED) Path().apply {
        val width = sizePx * 0.09f
        val height = sizePx * 0.13f
        // CSS border-radius 50% 50% 50% 50% / 62% 62% 38% 38%: a drop with a narrower top.
        val top = CornerRadius(width * 0.5f, height * 0.62f)
        val bottom = CornerRadius(width * 0.5f, height * 0.38f)
        addRoundRect(RoundRect(Rect(0f, 0f, width, height), top, top, bottom, bottom))
    } else null
    private val sparkSize = sizePx * 0.07f
    private val figurePx = sizePx

    /**
     * Draws the figure with its box's top left at [origin]. [timeNanos] is the time since its loop
     * started, or [STILL]; [amplitude] scales the whole-figure movement for the slot.
     */
    fun draw(scope: DrawScope, fill: Color, sleepInk: Color, origin: Offset, timeNanos: Long, amplitude: Float) = with(scope) {
        pose.update(mood, timeNanos, amplitude)
        translate(origin.x, origin.y) {
            drawFigure(fill)
            drawParticles(fill, sleepInk, timeNanos)
        }
    }

    private fun DrawScope.drawFigure(fill: Color) {
        val canvas = drawContext.canvas
        canvas.saveLayer(layerBounds, layerPaint)
        val pivot = Offset(PIVOT_X * unit, PIVOT_Y * unit)
        withTransform({
            rotate(pose.rotation, pivot)
            translate(0f, pose.offsetY * unit)
            scale(1f, pose.scaleY, pivot)
        }) {
            unitRect(fill, BODY_X, BODY_Y, BODY_W, BODY_H)
            // Limbs reach JOIN units under the body: two anti-aliased edges meeting on a fractional
            // pixel would otherwise leave a faint seam where they touch.
            unitRect(fill, 0f, ARM_Y + pose.leftArm, ARM_W + JOIN, ARM_H)
            unitRect(fill, RIGHT_ARM_X - JOIN, ARM_Y + pose.rightArm, ARM_W + JOIN, ARM_H)
            for (i in LEG_X.indices) {
                unitRect(fill, LEG_X[i], LEG_Y - JOIN + if (i % 2 == 0) pose.evenLeg else pose.oddLeg, LEG_W, LEG_H + JOIN)
            }
            for (i in 0..1) {
                val x: Float; val y: Float; val w: Float; val h: Float
                when (mood) {
                    ClaudeCodeMood.ASLEEP -> { x = if (i == 0) 5.6f else 16.2f; y = 9.9f; w = 2.2f; h = 0.6f }
                    ClaudeCodeMood.TIRED -> { x = EYE_X[i]; y = 9.6f; w = EYE_W; h = 1.35f }
                    else -> {
                        x = EYE_X[i]; w = EYE_W; h = EYE_H
                        y = EYE_Y + if (mood == ClaudeCodeMood.WORKING) EYE_H * 0.25f else 0f
                    }
                }
                val shown = h * pose.eyeScaleY
                unitRect(Color.Black, x + pose.eyeOffsetX, y + (h - shown) / 2f, w, shown, BlendMode.Clear)
            }
        }
        canvas.restore()
    }

    private fun DrawScope.drawParticles(fill: Color, sleepInk: Color, time: Long) {
        val still = time == STILL
        when (mood) {
            ClaudeCodeMood.ASLEEP -> for (i in sleep.indices) {
                val glyph = sleep[i]
                val x = figurePx * (0.64f + 0.09f * i)
                val y = figurePx * (0.06f - 0.06f * i)
                if (still) {
                    // Only the first "z" shows, at rest where its rise begins.
                    if (i == 0) translateTo(x, y, glyph, 0f, 0f, 1f, 1f, sleepInk)
                    continue
                }
                val p = phase(time, 2_700_000_000L, 900_000_000L * i)
                val opacity = if (p < 0.2f) segment(p, 0f, 0.2f, 0f, 1f, EaseOut) else segment(p, 0.2f, 1f, 1f, 0f, EaseOut)
                val travel = EaseOut.transform(p)
                translateTo(x, y, glyph, 0.7f * travel, -1.7f * travel, 0.6f + 0.4f * travel, opacity, sleepInk)
            }
            ClaudeCodeMood.TIRED -> {
                val drop = sweat ?: return
                val x = figurePx * 0.8f
                val y = figurePx * 0.04f
                if (still) {
                    translate(x, y) { drawPath(drop, SweatColor) }
                    return
                }
                val p = phase(time, 1_800_000_000L)
                val opacity = when {
                    p < 0.15f -> segment(p, 0f, 0.15f, 0f, 1f, EaseIn)
                    p <= 0.75f -> 1f
                    else -> segment(p, 0.75f, 1f, 1f, 0f, EaseIn)
                }
                translate(x, y + 1.4f * figurePx * 0.13f * EaseIn.transform(p)) { drawPath(drop, SweatColor, alpha = opacity) }
            }
            ClaudeCodeMood.DANCING -> {
                val glyph = note ?: return
                for (i in 0..1) {
                    val x = figurePx * if (i == 0) -0.08f else 0.92f
                    val y = figurePx * if (i == 0) 0.02f else -0.04f
                    if (still) {
                        translateTo(x, y, glyph, 0f, 0f, 1f, 1f, fill)
                        continue
                    }
                    val p = phase(time, 1_200_000_000L, 600_000_000L * i)
                    val opacity = if (p < 0.3f) segment(p, 0f, 0.3f, 0f, 1f, EaseOut) else segment(p, 0.3f, 1f, 1f, 0f, EaseOut)
                    translateTo(x, y, glyph, 0f, 0.2f - 1.5f * EaseOut.transform(p), 1f, opacity, fill)
                }
            }
            ClaudeCodeMood.HOORAY -> for (i in 0..2) {
                val x = figurePx * SPARK_X[i]
                val y = figurePx * SPARK_Y[i]
                val twinkle = if (still) 1f else pulse(phase(time, 900_000_000L, 300_000_000L * i))
                if (twinkle <= 0f) continue
                val half = sparkSize / 2f
                scale(0.3f + 0.7f * twinkle, pivot = Offset(x + half, y + half)) {
                    drawRect(SparkColor, Offset(x, y), Size(sparkSize, sparkSize), alpha = twinkle)
                }
            }
            ClaudeCodeMood.WORKING, ClaudeCodeMood.IDLE -> Unit
        }
    }

    /**
     * A glyph whose box sits at [x],[y], moved by [dx],[dy] of its own box and scaled about its
     * centre, as a CSS `translate(…%) scale(…)` with the default origin moves it.
     */
    private fun DrawScope.translateTo(x: Float, y: Float, glyph: Glyph, dx: Float, dy: Float, factor: Float, alpha: Float, color: Color) {
        if (alpha <= 0f) return
        translate(x + dx * glyph.width, y + dy * glyph.height) {
            scale(factor, pivot = Offset(glyph.width / 2f, glyph.height / 2f)) {
                drawPath(glyph.path, color, alpha = alpha)
            }
        }
    }

    private fun DrawScope.unitRect(color: Color, x: Float, y: Float, w: Float, h: Float, blendMode: BlendMode = DrawScope.DefaultBlendMode) {
        drawRect(color, Offset(x * unit, y * unit), Size(w * unit, h * unit), blendMode = blendMode)
    }

    /**
     * The whole-figure transform, limb offsets and eye changes of one frame, in grid units. One per
     * figure, filled again for every frame.
     */
    private class Pose {
        var offsetY = 0f
        var scaleY = 1f
        var rotation = 0f
        var leftArm = 0f
        var rightArm = 0f
        var evenLeg = 0f
        var oddLeg = 0f
        var eyeScaleY = 1f
        var eyeOffsetX = 0f

        fun update(mood: ClaudeCodeMood, time: Long, amplitude: Float) {
            offsetY = 0f; scaleY = 1f; rotation = 0f
            leftArm = 0f; rightArm = 0f; evenLeg = 0f; oddLeg = 0f
            eyeScaleY = 1f; eyeOffsetX = 0f
            if (time == STILL) {
                // The still Hooray has its arms up; every other still is the figure at rest.
                if (mood == ClaudeCodeMood.HOORAY) { leftArm = -ARM_H * 1.5f; rightArm = leftArm }
                return
            }
            when (mood) {
                ClaudeCodeMood.ASLEEP, ClaudeCodeMood.TIRED, ClaudeCodeMood.IDLE -> {
                    val period = when (mood) {
                        ClaudeCodeMood.ASLEEP -> 4_400_000_000L
                        ClaudeCodeMood.TIRED -> 2_200_000_000L
                        else -> 3_200_000_000L
                    }
                    scaleY = 1f - 0.045f * amplitude * pulse(phase(time, period))
                }
                ClaudeCodeMood.WORKING -> {
                    offsetY = -GRID * 0.03f * amplitude * pulse(phase(time, 440_000_000L))
                    // The right arm half a cycle (0.12 s of 0.24 s) behind the left.
                    leftArm = -ARM_H * 0.35f * pulse(phase(time, 240_000_000L))
                    rightArm = -ARM_H * 0.35f * pulse(phase(time, 240_000_000L, 120_000_000L))
                }
                ClaudeCodeMood.DANCING -> {
                    val hop = phase(time, 500_000_000L)
                    when {
                        hop < 0.12f -> scaleY = segment(hop, 0f, 0.12f, 1f, 1f - 0.09f * amplitude)
                        hop < 0.45f -> {
                            scaleY = segment(hop, 0.12f, 0.45f, 1f - 0.09f * amplitude, 1f)
                            offsetY = segment(hop, 0.12f, 0.45f, 0f, -GRID * 0.13f * amplitude)
                        }
                        hop < 0.78f -> offsetY = segment(hop, 0.45f, 0.78f, -GRID * 0.13f * amplitude, 0f)
                    }
                    rotation = -6f * amplitude + 12f * amplitude * pulse(phase(time, 1_000_000_000L))
                    val left = pulse(hop)
                    val right = pulse(phase(time, 500_000_000L, 250_000_000L))
                    leftArm = -ARM_H * 1.5f * left
                    rightArm = -ARM_H * 1.5f * right
                    evenLeg = -LEG_H * 0.4f * left
                    oddLeg = -LEG_H * 0.4f * right
                }
                ClaudeCodeMood.HOORAY -> {
                    // Raised once in 0.3 s, then held.
                    leftArm = -ARM_H * 1.5f * EaseOut.transform((time / 300_000_000f).coerceAtMost(1f))
                    rightArm = leftArm
                    val jump = phase(time, 800_000_000L)
                    offsetY = when {
                        jump < 0.35f -> segment(jump, 0f, 0.35f, 0f, -GRID * 0.16f * amplitude, EaseOut)
                        jump < 0.65f -> segment(jump, 0.35f, 0.65f, -GRID * 0.16f * amplitude, 0f, EaseOut)
                        else -> 0f
                    }
                }
            }
            val blinkPeriod = when (mood) {
                ClaudeCodeMood.IDLE -> 4_600_000_000L
                ClaudeCodeMood.WORKING -> 3_400_000_000L
                ClaudeCodeMood.DANCING, ClaudeCodeMood.HOORAY -> 2_600_000_000L
                else -> 0L
            }
            if (blinkPeriod > 0L) {
                val blink = phase(time, blinkPeriod)
                eyeScaleY = when {
                    blink < 0.93f -> 1f
                    blink < 0.96f -> segment(blink, 0.93f, 0.96f, 1f, 0.1f, Ease)
                    else -> segment(blink, 0.96f, 1f, 0.1f, 1f, Ease)
                }
            }
            if (mood == ClaudeCodeMood.IDLE) {
                val look = phase(time, 7_500_000_000L)
                val distance = EYE_W * 0.45f
                eyeOffsetX = when {
                    look < 0.28f -> 0f
                    look < 0.36f -> segment(look, 0.28f, 0.36f, 0f, -distance)
                    look < 0.58f -> -distance
                    look < 0.66f -> segment(look, 0.58f, 0.66f, -distance, distance)
                    look < 0.88f -> distance
                    else -> segment(look, 0.88f, 1f, distance, 0f)
                }
            }
        }
    }

    private class Glyph(val path: Path, val width: Float, val height: Float)

    private companion object {
        const val GRID = 24f
        // Whole-figure pivot: 50 % across, 85 % down.
        const val PIVOT_X = 12f
        const val PIVOT_Y = 20.4f
        const val BODY_X = 3f
        const val BODY_Y = 5f
        const val BODY_W = 18f
        const val BODY_H = 12.079f
        const val ARM_Y = 10.95f
        const val ARM_W = 3f
        const val ARM_H = 3.1f
        const val RIGHT_ARM_X = 21f
        const val JOIN = 0.5f
        val LEG_X = floatArrayOf(4.487f, 7.488f, 15f, 18f)
        const val LEG_Y = 17.079f
        const val LEG_W = 1.513f
        const val LEG_H = 2.921f
        val EYE_X = floatArrayOf(6f, 16.51f)
        const val EYE_Y = 8.102f
        const val EYE_W = 1.488f
        const val EYE_H = 2.847f
        val SPARK_X = floatArrayOf(0.02f, 0.86f, 0.46f)
        val SPARK_Y = floatArrayOf(0f, 0.06f, -0.14f)

        /**
         * A text glyph as a path in a box one font size high, its baseline where a CSS line box of
         * `line-height: 1` puts it, so the appendix's positions and percentages apply unchanged.
         */
        fun glyph(text: String, fontSize: Float, typeface: Typeface): Glyph {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = fontSize
            }
            val metrics = paint.fontMetrics
            val baseline = fontSize / 2f + (-metrics.ascent - metrics.descent) / 2f
            val path = android.graphics.Path()
            paint.getTextPath(text, 0, text.length, 0f, baseline, path)
            return Glyph(path.asComposePath(), paint.measureText(text), fontSize)
        }
    }
}

/** Where [time] is in a loop of [period] that started [lead] earlier (a CSS negative delay), 0 to 1. */
private fun phase(time: Long, period: Long, lead: Long = 0L): Float = ((time + lead) % period).toFloat() / period

/** Up and back down over one loop, eased at both ends: the appendix's 0 / 50 / 100 % keyframes. */
private fun pulse(phase: Float): Float =
    if (phase < 0.5f) segment(phase, 0f, 0.5f, 0f, 1f) else segment(phase, 0.5f, 1f, 1f, 0f)

/** The value between two keyframes, with the CSS timing function of the first. */
private fun segment(phase: Float, start: Float, end: Float, from: Float, to: Float, easing: Easing = EaseInOut): Float =
    from + (to - from) * easing.transform(((phase - start) / (end - start)).coerceIn(0f, 1f))
