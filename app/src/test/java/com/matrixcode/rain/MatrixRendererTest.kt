package com.matrixcode.rain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders real frames off-device so the rain can be inspected as PNGs and checked for
 * obvious regressions (blank frames, glyphs escaping the viewport, colours going wrong).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatrixRendererTest {

    private val w = 720
    private val h = 1280
    private val outDir = File(System.getProperty("matrix.frameDir") ?: "build/frames")

    private fun renderFrames(
        config: MatrixConfig,
        frames: Int = 90,
        capture: Set<Int> = setOf(89),
        namePrefix: String? = null
    ): Bitmap {
        val renderer = MatrixRenderer(displayDensity = 2f)
        renderer.updateConfig(config)
        renderer.resize(w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(frames) { i ->
            renderer.update(1f / 60f)
            renderer.draw(canvas)
            if (namePrefix != null && i in capture) {
                outDir.mkdirs()
                File(outDir, "$namePrefix-%03d.png".format(i)).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        renderer.release()
        return bmp
    }

    private fun litPixelRatio(bmp: Bitmap): Double {
        var lit = 0
        var total = 0
        for (y in 0 until bmp.height step 3) {
            for (x in 0 until bmp.width step 3) {
                total++
                val p = bmp.getPixel(x, y)
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 40) lit++
            }
        }
        return lit.toDouble() / total
    }

    @Test
    fun defaultRainProducesVisibleGlyphs() {
        val bmp = renderFrames(MatrixConfig(), namePrefix = "default")
        val ratio = litPixelRatio(bmp)
        assertTrue("frame looks blank (lit=$ratio)", ratio > 0.01)
        assertTrue("frame looks washed out (lit=$ratio)", ratio < 0.75)
    }

    @Test
    fun rainSpansTheFullHeight() {
        // Sample the top and bottom fifths: after a couple of seconds both should have rain.
        val bmp = renderFrames(MatrixConfig(speed = 2f), frames = 180)
        fun litInBand(y0: Int, y1: Int): Int {
            var lit = 0
            for (y in y0 until y1 step 2) for (x in 0 until w step 2) {
                val p = bmp.getPixel(x, y)
                if (Color.green(p) > 30) lit++
            }
            return lit
        }
        assertTrue("no rain near the top", litInBand(0, h / 5) > 50)
        assertTrue("no rain near the bottom", litInBand(h * 4 / 5, h) > 50)
    }

    @Test
    fun themesChangeTheDominantColor() {
        val greenBmp = renderFrames(MatrixConfig(theme = ColorTheme.MATRIX_GREEN, glitch = 0f))
        val redBmp = renderFrames(MatrixConfig(theme = ColorTheme.CRIMSON, glitch = 0f))

        fun channelTotals(b: Bitmap): Triple<Long, Long, Long> {
            var r = 0L; var g = 0L; var bl = 0L
            for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
                val p = b.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
            }
            return Triple(r, g, bl)
        }

        val (gr, gg, _) = channelTotals(greenBmp)
        val (rr, rg, _) = channelTotals(redBmp)
        assertTrue("green theme is not green-dominant", gg > gr)
        assertTrue("crimson theme is not red-dominant", rr > rg)
    }

    @Test
    fun everyThemeAndGlyphSetRendersAFrame() {
        for (theme in ColorTheme.entries) {
            val bmp = renderFrames(
                MatrixConfig(theme = theme, showClock = true),
                frames = 60,
                capture = setOf(59),
                namePrefix = "theme-${theme.name.lowercase()}"
            )
            assertTrue("theme $theme rendered blank", litPixelRatio(bmp) > 0.005)
        }
        for (set in GlyphSet.entries) {
            val bmp = renderFrames(
                MatrixConfig(glyphSet = set),
                frames = 60,
                capture = setOf(59),
                namePrefix = "glyphs-${set.name.lowercase()}"
            )
            assertTrue("glyph set $set rendered blank", litPixelRatio(bmp) > 0.005)
        }
    }

    @Test
    fun resizingMidFlightRebuildsTheGrid() {
        // What happens on rotation: the surface changes size under a running loop.
        val renderer = MatrixRenderer(displayDensity = 2f)
        renderer.updateConfig(MatrixConfig())
        renderer.resize(w, h)

        val portrait = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val portraitCanvas = Canvas(portrait)
        repeat(60) { renderer.update(1f / 60f); renderer.draw(portraitCanvas) }
        assertTrue("portrait blank", litPixelRatio(portrait) > 0.005)

        renderer.resize(h, w)
        val landscape = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
        val landscapeCanvas = Canvas(landscape)
        repeat(120) { renderer.update(1f / 60f); renderer.draw(landscapeCanvas) }
        assertTrue("landscape blank after resize", litPixelRatio(landscape) > 0.005)

        // Rain must reach the new right-hand edge, not just the old portrait width.
        var litBeyondOldWidth = 0
        for (y in 0 until w step 2) for (x in this.w until h step 2) {
            if (Color.green(landscape.getPixel(x, y)) > 30) litBeyondOldWidth++
        }
        assertTrue("grid did not widen on resize", litBeyondOldWidth > 20)
        renderer.release()
    }

    @Test
    fun extremeSettingsDoNotCrash() {
        val extremes = listOf(
            MatrixConfig(speed = 0.1f, glyphSize = 10f, density = 1f, trailLength = 2f),
            MatrixConfig(speed = 4f, glyphSize = 36f, density = 0.2f, trailLength = 0.2f),
            MatrixConfig(glow = 0f, scanlines = 0f, glitch = 0f, mutationRate = 0f),
            MatrixConfig(glow = 1f, scanlines = 1f, glitch = 1f, mutationRate = 1f),
            MatrixConfig(theme = ColorTheme.RAINBOW, showClock = true, clock24h = false)
        )
        for ((i, cfg) in extremes.withIndex()) {
            renderFrames(cfg, frames = 45, capture = setOf(44), namePrefix = "extreme-$i")
        }
    }

    @Test
    fun liveConfigChangesAreAppliedWithoutRestart() {
        val renderer = MatrixRenderer(displayDensity = 2f)
        renderer.updateConfig(MatrixConfig())
        renderer.resize(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(60) { renderer.update(1f / 60f); renderer.draw(canvas) }

        // Change every grid-affecting knob mid-flight, as the settings screen does.
        renderer.updateConfig(
            MatrixConfig(
                theme = ColorTheme.AMBER,
                glyphSet = GlyphSet.BINARY,
                glyphSize = 30f,
                density = 0.4f,
                trailLength = 1.8f
            )
        )
        repeat(60) { renderer.update(1f / 60f); renderer.draw(canvas) }
        assertTrue("frame blank after live reconfigure", litPixelRatio(bmp) > 0.003)
        renderer.release()
    }
}
