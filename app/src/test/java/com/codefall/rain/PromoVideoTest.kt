package com.codefall.rain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the promo video frame by frame from the real engine, so every second of
 * it is the app rather than a mockup. Frames land in build/promo/ and are encoded
 * by tools/build_promo.py.
 *
 * Excluded from the normal suite by its name — see the `test` filter in
 * app/build.gradle.kts — because it writes ~750 full-size frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromoVideoTest {

    private val w = 1080
    private val h = 1920
    private val fps = 30
    private val density = 3f
    private val outDir = File("build/promo")

    /** Scene boundaries in seconds. Each caption fades in and out inside its slot. */
    private val titleEnd = 3.6f
    private val themesEnd = 7.2f
    private val glyphsEnd = 10.2f
    private val tiltEnd = 13.2f
    private val messageEnd = 18.4f
    private val crtEnd = 21.4f
    private val runtime = 25.0f

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.22f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.16f
    }
    private val titleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.16f
        style = Paint.Style.STROKE
    }

    /** Ramps 0..1 in, holds, and back out across [start]..[end]. */
    private fun envelope(t: Float, start: Float, end: Float, ramp: Float = 0.45f): Float {
        if (t < start || t > end) return 0f
        val into = (t - start) / ramp
        val outOf = (end - t) / ramp
        return min(1f, min(into, outOf)).coerceIn(0f, 1f)
    }

    /** Shrinks [paint] until [text] fits inside [maxWidth]. */
    private fun fitText(paint: Paint, text: String, startSize: Float, maxWidth: Float) {
        paint.textSize = startSize
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = startSize * (maxWidth / measured)
        }
    }

    private fun drawCaption(canvas: Canvas, text: String, alpha: Float) {
        if (alpha <= 0.01f) return
        fitText(captionPaint, text, w * 0.042f, w * 0.84f)
        captionPaint.color = 0xFFCCFFCC.toInt()
        captionPaint.alpha = (alpha * 235f).toInt().coerceIn(0, 255)
        canvas.drawText(text, w / 2f, h * 0.88f, captionPaint)
    }

    private fun drawTitle(canvas: Canvas, text: String, sub: String?, alpha: Float) {
        if (alpha <= 0.01f) return
        fitText(titlePaint, text, w * 0.135f, w * 0.80f)
        val size = titlePaint.textSize
        titleGlowPaint.textSize = size
        titleGlowPaint.strokeWidth = max(1f, size * 0.03f)
        val y = h * 0.47f

        titleGlowPaint.color = 0xFF00FF41.toInt()
        titleGlowPaint.alpha = (alpha * 110f).toInt().coerceIn(0, 255)
        canvas.drawText(text, w / 2f, y, titleGlowPaint)

        titlePaint.color = 0xFFEFFFF2.toInt()
        titlePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawText(text, w / 2f, y, titlePaint)

        if (sub != null) {
            fitText(captionPaint, sub, w * 0.034f, w * 0.80f)
            captionPaint.color = 0xFF00FF41.toInt()
            captionPaint.alpha = (alpha * 220f).toInt().coerceIn(0, 255)
            canvas.drawText(sub, w / 2f, y + size * 0.85f, captionPaint)
        }
    }

    /** The config for a given moment, plus the tilt to feed the renderer. */
    private fun configAt(t: Float): Pair<RainConfig, Float> {
        val base = RainConfig(
            theme = ColorTheme.CLASSIC_GREEN,
            glyphSet = GlyphSet.KATAKANA,
            glyphSize = 15f,
            density = 0.95f,
            speed = 1.15f,
            trailLength = 1.15f,
            mutationRate = 0.4f,
            glow = 0.75f,
            scanlines = 0.18f,
            glitch = 0.1f,
            mirrorGlyphs = true,
            settleTrail = true
        )
        var tilt = 0f

        // Ordered by time: each branch owns the slot between the previous
        // boundary and its own.
        when {
            t < titleEnd -> {
                // Opening rain, untouched defaults.
            }
            t < themesEnd -> {
                // One theme every 0.45s, in the order they appear in the app.
                val i = ((t - titleEnd) / 0.45f).toInt()
                base.theme = ColorTheme.entries[i % ColorTheme.entries.size]
            }
            t < glyphsEnd -> {
                val i = ((t - themesEnd) / 0.5f).toInt()
                base.glyphSet = GlyphSet.entries[i % GlyphSet.entries.size]
            }
            t < tiltEnd -> {
                base.tiltEnabled = true
                base.tiltStrength = 1f
                // A slow lean each way, so the shear reads clearly.
                tilt = sin((t - glyphsEnd) * 1.5f).toFloat()
            }
            t < messageEnd -> {
                base.message = "WAKE UP, NEO"
                base.messageInterval = 1f
                base.density = 0.75f
            }
            t < crtEnd -> {
                // Filters ramp in together rather than snapping on.
                val k = ((t - messageEnd) / (crtEnd - messageEnd)).coerceIn(0f, 1f)
                base.theme = ColorTheme.AMBER
                base.glow = 0.9f
                base.scanlines = 0.18f + 0.12f * k
                base.apertureGrille = 0.3f * k
                base.vignette = 0.4f * k
                base.aberration = 0.5f * k
                base.crtFlicker = 0.25f * k
                base.noise = 0.1f * k
            }
            else -> {
                base.density = 0.8f
            }
        }
        return base to tilt
    }

    @Test
    fun renderPromoFrames() {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val renderer = RainRenderer(displayDensity = density)
        renderer.updateConfig(configAt(0f).first)
        renderer.resize(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val total = (runtime * fps).toInt()
        val dt = 1f / fps

        // A second of rain before frame one, so the screen opens already full.
        repeat(90) { renderer.update(dt); renderer.draw(canvas) }

        for (frame in 0 until total) {
            val t = frame * dt
            val (cfg, tilt) = configAt(t)
            renderer.updateConfig(cfg)
            renderer.setTilt(tilt)
            renderer.update(dt)
            renderer.draw(canvas)

            when {
                t < titleEnd ->
                    drawTitle(canvas, "CODEFALL", null, envelope(t, 0.8f, titleEnd, 0.7f))
                t < themesEnd -> drawCaption(canvas, "EIGHT COLOUR THEMES", envelope(t, titleEnd, themesEnd))
                t < glyphsEnd -> drawCaption(canvas, "SIX CHARACTER SETS", envelope(t, themesEnd, glyphsEnd))
                t < tiltEnd -> drawCaption(canvas, "TILT TO STEER THE RAIN", envelope(t, glyphsEnd, tiltEnd))
                t < messageEnd -> drawCaption(canvas, "HIDE A MESSAGE IN THE CODE", envelope(t, tiltEnd, messageEnd))
                t < crtEnd -> drawCaption(canvas, "CRT SCREEN FILTERS", envelope(t, messageEnd, crtEnd))
                else -> drawTitle(
                    canvas, "CODEFALL", "NO ADS  NO TRACKING  NO PERMISSIONS",
                    envelope(t, crtEnd, runtime, 0.8f)
                )
            }

            File(outDir, "f%05d.png".format(frame)).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        renderer.release()
        println("PROMO rendered $total frames at ${w}x$h ${fps}fps")
    }
}
