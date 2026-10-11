package com.usagelimits.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF

/**
 * The ring the minimal widget is made of, drawn into a bitmap.
 *
 * Glance has no canvas: its building blocks are boxes, text and images. A box with a corner
 * radius can be a circle but not a PARTIAL one, so a ring that fills as quota is consumed has
 * to arrive as a picture. This draws that picture.
 */
object UsageRing {

    /** Degrees of arc for a given remaining fraction, starting at twelve o'clock. */
    fun sweepDegrees(remainingFraction: Float): Float =
        360f * remainingFraction.coerceIn(0f, 1f)

    /**
     * Remaining quota as a fraction, from the percentage a provider reported.
     *
     * Unknown values claim no available quota. [draw] distinguishes them from zero with a
     * dashed neutral track, without painting any foreground arc.
     */
    fun fractionFor(remainingPercent: Double?): Float =
        (remainingPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.div(100.0) ?: 0.0).toFloat()

    fun draw(
        sizePx: Int,
        remainingPercent: Double?,
        argb: Int,
        trackArgb: Int,
        strokePx: Float = sizePx * 0.11f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = strokePx / 2f
        val box = RectF(inset, inset, sizePx - inset, sizePx - inset)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
        }

        paint.color = trackArgb
        val known = remainingPercent != null && remainingPercent.isFinite() && remainingPercent in 0.0..100.0
        if (!known) paint.pathEffect = DashPathEffect(floatArrayOf(strokePx, strokePx), 0f)
        canvas.drawArc(box, 0f, 360f, false, paint)

        if (!known) return bitmap
        paint.color = argb
        // From twelve o'clock, clockwise, so a ring that is emptying reads the way a clock
        // face does rather than unwinding backwards.
        canvas.drawArc(box, -90f, sweepDegrees(fractionFor(remainingPercent)), false, paint)

        return bitmap
    }
}
