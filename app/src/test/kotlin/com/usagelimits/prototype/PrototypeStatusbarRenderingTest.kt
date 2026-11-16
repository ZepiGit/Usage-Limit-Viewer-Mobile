package com.usagelimits.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Throwaway: four structural answers to the 15dp question; no behavioral tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrototypeStatusbarRenderingTest {
    private val output = File("build/reports/statusbar-prototype")
    private val providers = listOf("claude", "codex", "xai")
    private val states = listOf("5", "42", "87", "99", "stale", "exhausted", "error")
    private val variants = listOf("A digits", "B stacked", "C corner pip", "D ring")
    private val densities = listOf("mdpi" to 15, "hdpi" to 23, "xhdpi" to 30, "xxhdpi" to 45, "xxxhdpi" to 60)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(85, 101, 119)
        textSize = 18f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    @Test
    fun generatePrototypeContactSheets() {
        output.mkdirs()
        for ((variantIndex, variant) in variants.withIndex()) {
            val sheet = Bitmap.createBitmap(3400, 2600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(229, 234, 240))
            canvas.drawText("PROTOTYPE — $variant — alpha only, 192px source → bilinear target → nearest ×8", 24f, 32f, label)
            canvas.drawText("Each density: true size on dark / light, then ×8 on dark / light. Stale = 42 + dot; exhausted = crossed circle; error = !", 24f, 58f, label)
            canvas.drawText("provider / state", 24f, 95f, label)
            canvas.drawText("48dp ×4 authored (192px)", 190f, 95f, label)
            var columnX = 420
            for ((name, size) in densities) {
                canvas.drawText("$name: ${size}px (15dp)", columnX.toFloat(), 95f, label)
                columnX += size * 16 + 32
            }
            for ((providerIndex, provider) in providers.withIndex()) {
                for ((stateIndex, state) in states.withIndex()) {
                    val y = 110 + (providerIndex * states.size + stateIndex) * 114
                    val mask = mask(variantIndex, provider, state)
                    save(mask, "masks/${variant.first()}-$provider-$state.png")
                    canvas.drawText("$provider / $state", 24f, y + 32f, label)
                    // Authored source is kept as its own PNG; this preview is 96px so every row fits.
                    tinted(canvas, Bitmap.createScaledBitmap(mask, 96, 96, true), 198, y, false)
                    tinted(canvas, Bitmap.createScaledBitmap(mask, 96, 96, true), 302, y, true)
                    var x = 420
                    for ((_, size) in densities) {
                        val real = Bitmap.createScaledBitmap(mask, size, size, true)
                        tinted(canvas, real, x, y, false)
                        tinted(canvas, real, x + size + 8, y, true)
                        // Largest density rows use tall zooms: separate density sheets below.
                        val preview = Bitmap.createScaledBitmap(real, minOf(size * 8, 76), minOf(size * 8, 76), false)
                        tinted(canvas, preview, x, y + 30, false)
                        tinted(canvas, preview, x + 84, y + 30, true)
                        x += size * 16 + 32
                    }
                    mask.recycle()
                }
            }
            canvas.drawText("Overview zoom previews are capped; exact ×8 samples are in density contact sheets. Raw 192px alpha masks: masks/", 24f, 2538f, label)
            save(sheet, "overview-${variant.first()}.png")
            sheet.recycle()
        }
        val authored = Bitmap.createBitmap(3100, 2940, Bitmap.Config.ARGB_8888)
        val authoredCanvas = Canvas(authored)
        authoredCanvas.drawColor(Color.rgb(229, 234, 240))
        authoredCanvas.drawText("PROTOTYPE — authored 48dp ×4 = 192px, unscaled, dark / light pairs; raw transparent masks also saved", 18f, 28f, label)
        for ((index, state) in states.withIndex()) authoredCanvas.drawText(state, (190 + index * 412).toFloat(), 62f, label)
        for ((variantIndex, variant) in variants.withIndex()) for ((providerIndex, provider) in providers.withIndex()) {
            val y = 80 + (variantIndex * 3 + providerIndex) * 236
            authoredCanvas.drawText(variant, 12f, y + 28f, label)
            authoredCanvas.drawText(provider, 12f, y + 52f, label)
            for ((index, state) in states.withIndex()) {
                val source = mask(variantIndex, provider, state)
                tinted(authoredCanvas, source, 190 + index * 412, y, false)
                tinted(authoredCanvas, source, 394 + index * 412, y, true)
            }
        }
        save(authored, "source-authored-192px.png")
        authored.recycle()
        for ((density, size) in densities) {
            // One provider/variant per row, seven states per row. No resizing of the output.
            val cellW = size * 16 + 38
            val rowH = size * 8 + size + 70
            val sheet = Bitmap.createBitmap(cellW * states.size + 190, rowH * 12 + 90, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(229, 234, 240))
            canvas.drawText("PROTOTYPE $density — $size px true size + exact ×8 nearest; left white 90% on dark, right dark on light", 18f, 28f, label)
            for ((index, state) in states.withIndex()) canvas.drawText(state, (190 + index * cellW).toFloat(), 62f, label)
            for ((variantIndex, variant) in variants.withIndex()) for ((providerIndex, provider) in providers.withIndex()) {
                val y = 80 + (variantIndex * 3 + providerIndex) * rowH
                canvas.drawText(variant, 12f, y + 28f, label)
                canvas.drawText(provider, 12f, y + 52f, label)
                for ((index, state) in states.withIndex()) {
                    val x = 190 + index * cellW
                    val real = Bitmap.createScaledBitmap(mask(variantIndex, provider, state), size, size, true)
                    tinted(canvas, real, x, y, false)
                    tinted(canvas, real, x + size + 12, y, true)
                    val zoom = Bitmap.createScaledBitmap(real, size * 8, size * 8, false)
                    tinted(canvas, zoom, x, y + size + 16, false)
                    tinted(canvas, zoom, x + size * 8 + 12, y + size + 16, true)
                }
            }
            save(sheet, "density-$density.png")
            sheet.recycle()
        }
        val strip = Bitmap.createBitmap(1240, 940, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(Color.rgb(229, 234, 240))
        canvas.drawText("PROTOTYPE simulated xxhdpi status bar: 15dp drawing in 22dp slots (45px / 66px)", 18f, 30f, label)
        for ((i, variant) in variants.withIndex()) {
            canvas.drawText("$variant — Claude 5, Codex 42, xAI 87", 18f, 70f + i * 220f, label)
            for (light in listOf(false, true)) {
                val y = 90 + i * 220 + if (light) 80 else 0
                canvas.drawRect(18f, y.toFloat(), 1222f, y + 72f, Paint().apply { color = if (light) Color.rgb(245, 245, 245) else Color.rgb(38, 41, 46) })
                canvas.drawText("9:41", 48f, y + 51f, Paint(ink).apply { textAlign = Paint.Align.LEFT; textSize = 42f; color = if (light) Color.rgb(30, 30, 30) else Color.WHITE })
                for ((p, provider) in providers.withIndex()) tinted(canvas, Bitmap.createScaledBitmap(mask(i, provider, listOf("5", "42", "87")[p]), 45, 45, true), 196 + p * 66, y + 13, light)
                canvas.drawText("LTE   ▰  95%", 918f, y + 49f, Paint(ink).apply { textSize = 32f; color = if (light) Color.rgb(30, 30, 30) else Color.WHITE })
            }
        }
        save(strip, "statusbar-strips-xxhdpi.png")
    }

    private fun mask(variant: Int, provider: String, state: String): Bitmap {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(8f, 8f) // 24-unit path coordinates; authored at 48dp ×4.
        ink.color = Color.WHITE
        ink.style = Paint.Style.FILL
        val text = when (state) { "stale" -> "42"; "error" -> "!"; "exhausted" -> ""; else -> state.padStart(2, '0') }
        if (state == "exhausted") {
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = 2.6f
            canvas.drawCircle(12f, 12f, 8.5f, ink)
            canvas.drawLine(5.5f, 18.5f, 18.5f, 5.5f, ink)
            ink.style = Paint.Style.FILL
        } else when (variant) {
            0 -> digits(canvas, text, RectF(0.4f, 1.2f, 23.6f, 22.5f))
            1 -> { glyph(canvas, provider, RectF(7f, 0f, 17f, 9f)); digits(canvas, text, RectF(1f, 10f, 23f, 24f)) }
            2 -> { glyph(canvas, provider, RectF(0f, 0f, 8.5f, 8.5f)); digits(canvas, text, RectF(1f, 7f, 24f, 24f)) }
            3 -> {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 2.2f
                canvas.drawArc(RectF(1.6f, 1.6f, 22.4f, 22.4f), -90f, (state.toFloatOrNull() ?: 42f) * 3.6f, false, ink)
                ink.style = Paint.Style.FILL
                if (state == "error") digits(canvas, "!", RectF(7f, 5f, 17f, 19f)) else glyph(canvas, provider, RectF(6f, 6f, 18f, 18f))
            }
        }
        if (state == "stale") {
            ink.color = Color.TRANSPARENT
            ink.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawCircle(21.2f, 2.8f, 3.8f, ink)
            ink.xfermode = null
            ink.color = Color.WHITE
            canvas.drawCircle(21.2f, 2.8f, 2.2f, ink)
        }
        return bitmap
    }

    private fun digits(canvas: Canvas, text: String, bounds: RectF) {
        ink.textSize = 24f
        ink.textAlign = Paint.Align.CENTER
        val box = Rect()
        ink.getTextBounds(text, 0, text.length, box)
        canvas.save()
        canvas.translate(bounds.centerX(), bounds.top)
        val scaleY = bounds.height() / box.height()
        canvas.scale(if (text == "!") scaleY else bounds.width() / box.width(), scaleY)
        canvas.drawText(text, 0f, -box.top.toFloat(), ink)
        canvas.restore()
    }

    private fun glyph(canvas: Canvas, provider: String, bounds: RectF) {
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (provider) {
            // Simplified from assets/providers/claude.svg: Code mascot body, arms and legs.
            "claude" -> {
                val body = PathParser.createPathFromPathData("M3 5H21V11H24V14H21V17H19V20H17V17H7V20H5V17H3V14H0V11H3Z M6 8V11H8V8Z M16 8V11H18V8Z")!!
                body.fillType = Path.FillType.EVEN_ODD
                canvas.drawPath(body, ink)
            }
            // Simplified lobehub/openai.svg: six interleaved rounded loops, preserving the knot silhouette.
            "codex" -> {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 2.6f
                for (i in 0..5) {
                    canvas.save()
                    canvas.rotate(i * 60f, 12f, 12f)
                    canvas.drawRoundRect(RectF(8f, 1.5f, 18f, 15f), 4.8f, 4.8f, ink)
                    canvas.restore()
                }
                ink.style = Paint.Style.FILL
            }
            "xai" -> canvas.drawPath(PathParser.createPathFromPathData("M6.469 8.776L16.512 23h-4.464L2.005 8.776H6.47zm-.004 7.9l2.233 3.164L6.467 23H2l4.465-6.324zM22 2.582V23h-3.659V7.764L22 2.582zM22 1l-9.952 14.095-2.233-3.163L17.533 1H22z")!!, ink)
        }
        canvas.restore()
    }

    private fun tinted(canvas: Canvas, mask: Bitmap, x: Int, y: Int, light: Boolean) {
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + mask.width).toFloat(), (y + mask.height).toFloat(), Paint().apply { color = if (light) Color.rgb(245, 245, 245) else Color.rgb(38, 41, 46) })
        canvas.drawBitmap(mask, x.toFloat(), y.toFloat(), Paint().apply {
            isFilterBitmap = false
            colorFilter = PorterDuffColorFilter(if (light) Color.rgb(28, 28, 28) else Color.WHITE, PorterDuff.Mode.SRC_IN)
            alpha = 230
        })
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(output, name).apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
