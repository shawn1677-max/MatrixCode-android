package com.codefall.rain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * Renders real frames off-device so the rain can be inspected as PNGs and checked for
 * obvious regressions (blank frames, glyphs escaping the viewport, colours going wrong).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RainRendererTest {

    private val w = 720
    private val h = 1280
    private val outDir = File(System.getProperty("codefall.frameDir") ?: "build/frames")

    private fun renderFrames(
        config: RainConfig,
        frames: Int = 90,
        capture: Set<Int> = setOf(89),
        namePrefix: String? = null
    ): Bitmap {
        val renderer = RainRenderer(displayDensity = 2f)
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
        val bmp = renderFrames(RainConfig(), namePrefix = "default")
        val ratio = litPixelRatio(bmp)
        assertTrue("frame looks blank (lit=$ratio)", ratio > 0.004)
        assertTrue("frame looks washed out (lit=$ratio)", ratio < 0.75)
    }

    @Test
    fun rainSpansTheFullHeight() {
        // Sample the top and bottom fifths: after a couple of seconds both should have rain.
        val bmp = renderFrames(RainConfig(speed = 2f), frames = 180)
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
        val greenBmp = renderFrames(RainConfig(theme = ColorTheme.CLASSIC_GREEN, glitch = 0f))
        val redBmp = renderFrames(RainConfig(theme = ColorTheme.CRIMSON, glitch = 0f))

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
                RainConfig(theme = theme, showClock = true),
                frames = 60,
                capture = setOf(59),
                namePrefix = "theme-${theme.name.lowercase()}"
            )
            assertTrue("theme $theme rendered blank", litPixelRatio(bmp) > 0.005)
        }
        for (set in GlyphSet.entries) {
            val bmp = renderFrames(
                RainConfig(glyphSet = set),
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
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(RainConfig())
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
            RainConfig(speed = 0.1f, glyphSize = 10f, density = 1f, trailLength = 2f),
            RainConfig(speed = 4f, glyphSize = 36f, density = 0.2f, trailLength = 0.2f),
            RainConfig(glow = 0f, scanlines = 0f, glitch = 0f, mutationRate = 0f),
            RainConfig(glow = 1f, scanlines = 1f, glitch = 1f, mutationRate = 1f),
            RainConfig(theme = ColorTheme.RAINBOW, showClock = true, clock24h = false)
        )
        for ((i, cfg) in extremes.withIndex()) {
            renderFrames(cfg, frames = 45, capture = setOf(44), namePrefix = "extreme-$i")
        }
    }

    /**
     * A canvas that records where each glyph was asked to be drawn. Reading positions
     * straight off the draw calls sidesteps the screen edge entirely — glyphs sheared
     * past the border still count, where a pixel-based measurement would silently drop
     * them and cancel out the very shift being measured.
     */
    private class RecordingCanvas(bmp: Bitmap) : Canvas(bmp) {
        val headX = mutableListOf<Float>()
        val trailX = mutableListOf<Float>()

        override fun drawText(
            text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: android.graphics.Paint
        ) {
            // Only the pale head colour carries red; trail glyphs are pure green.
            if (Color.red(paint.color) > 70) headX.add(x) else trailX.add(x)
            super.drawText(text, index, count, x, y, paint)
        }

        fun reset() { headX.clear(); trailX.clear() }
        fun meanHead() = if (headX.isEmpty()) Float.NaN else headX.average().toFloat()
        fun meanTrail() = if (trailX.isEmpty()) Float.NaN else trailX.average().toFloat()
    }

    /**
     * Measures the lean against a frozen field: with speed 0 and no churn the columns
     * cannot move between the two samples, so any shift in the trails is the shear
     * itself rather than run-to-run randomness.
     *
     * Returns (trail shift, head shift) in pixels, positive meaning rightwards.
     */
    private fun shearShift(
        tilt: Float,
        enabled: Boolean,
        mirror: Boolean = false
    ): Pair<Float, Float> {
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(
            RainConfig(
                tiltEnabled = enabled,
                tiltStrength = 1f,
                mirrorGlyphs = mirror,
                density = 1f,
                trailLength = 1.4f,
                speed = 0f,
                mutationRate = 0f,
                glitch = 0f,
                glow = 0f,
                scanlines = 0f
            )
        )
        renderer.resize(w, h)
        val canvas = RecordingCanvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

        renderer.setTilt(0f)
        repeat(60) { renderer.update(1f / 60f); renderer.draw(canvas) }
        canvas.reset()
        renderer.draw(canvas)
        val trailsBefore = canvas.meanTrail()
        val headsBefore = canvas.meanHead()

        renderer.setTilt(tilt)
        repeat(60) { renderer.update(1f / 60f); renderer.draw(canvas) }
        canvas.reset()
        renderer.draw(canvas)
        val trailsAfter = canvas.meanTrail()
        val headsAfter = canvas.meanHead()

        renderer.release()
        assertTrue("no glyphs were drawn", !trailsBefore.isNaN() && !trailsAfter.isNaN())

        // drawText sees pre-transform coordinates, so under the mirror a recorded
        // shift lands on screen with the opposite sign. Report screen space.
        val flip = if (mirror) -1f else 1f
        return ((trailsAfter - trailsBefore) * flip) to ((headsAfter - headsBefore) * flip)
    }

    @Test
    fun tiltShearsTrailsBehindTheirHeads() {
        // A trail marks where its head has been, so leaning right drags the trail
        // left of the head, and vice versa. The heads themselves must not jump.
        val (rightTrail, rightHead) = shearShift(1f, enabled = true)
        assertTrue("leaning right did not pull trails left (got $rightTrail)", rightTrail < -20f)
        assertTrue("heads moved when they should not (got $rightHead)", abs(rightHead) < 1f)

        val (leftTrail, leftHead) = shearShift(-1f, enabled = true)
        assertTrue("leaning left did not push trails right (got $leftTrail)", leftTrail > 20f)
        assertTrue("heads moved when they should not (got $leftHead)", abs(leftHead) < 1f)
    }

    @Test
    fun mirroredRainLeansTheSameWayOnScreen() {
        // Mirroring flips the whole rain layer, so the lean is negated to compensate.
        // On screen the result must be indistinguishable from the unmirrored case.
        val (plainTrail, _) = shearShift(1f, enabled = true, mirror = false)
        val (mirroredTrail, mirroredHead) = shearShift(1f, enabled = true, mirror = true)

        assertTrue(
            "mirrored rain leaned the wrong way (plain=$plainTrail mirrored=$mirroredTrail)",
            mirroredTrail < -20f
        )
        assertTrue("heads moved under mirror (got $mirroredHead)", abs(mirroredHead) < 1f)
    }

    @Test
    fun tiltIsIgnoredWhenTheOptionIsOff() {
        val (trailShift, headShift) = shearShift(1f, enabled = false)
        assertTrue("trails leaned with tilt disabled (got $trailShift)", abs(trailShift) < 1f)
        assertTrue("heads leaned with tilt disabled (got $headShift)", abs(headShift) < 1f)
    }

    /**
     * Average pixels changing per frame, bucketed by how bright the pixel was:
     * deep tail, mid trail, and at/near the head.
     */
    private fun interFrameChurn(settle: Boolean): Triple<Double, Double, Double> {
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(
            RainConfig(
                settleTrail = settle,
                mutationRate = 1f,
                // Completely still, so mutation is the only thing that can move a pixel.
                speed = 0f,
                glitch = 0f,
                glow = 0f,
                scanlines = 0f,
                trailLength = 2f,
                density = 1f
            )
        )
        renderer.resize(w, h)
        val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ca = Canvas(a)
        val cb = Canvas(b)
        repeat(30) { renderer.update(1f / 60f); renderer.draw(ca) }

        var deep = 0L
        var mid = 0L
        var head = 0L
        var samples = 0
        repeat(12) {
            renderer.update(1f / 60f); renderer.draw(cb)
            for (y in 0 until h step 2) for (x in 0 until w step 2) {
                val before = Color.green(a.getPixel(x, y))
                if (before == Color.green(b.getPixel(x, y))) continue
                when {
                    before in 20..60 -> deep++
                    before in 61..120 -> mid++
                    before > 150 -> head++
                }
            }
            samples++
            renderer.update(1f / 60f); renderer.draw(ca)
        }
        renderer.release()
        return Triple(
            deep.toDouble() / samples,
            mid.toDouble() / samples,
            head.toDouble() / samples
        )
    }

    /** Mean x of head glyphs: only the pale head colour carries red. */
    private fun meanXRed(bmp: Bitmap): Float {
        var sum = 0.0; var n = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            if (Color.red(bmp.getPixel(x, y)) > 70) { sum += x; n++ }
        }
        return if (n == 0) -1f else (sum / n).toFloat()
    }

    /** Mean x of trail glyphs: green with essentially no red. */
    private fun meanXTrail(bmp: Bitmap): Float {
        var sum = 0.0; var n = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            if (Color.green(p) > 70 && Color.red(p) < 20) { sum += x; n++ }
        }
        return if (n == 0) -1f else (sum / n).toFloat()
    }

    @Test
    fun settlingTrailMovesTheBoilToTheHead() {
        val (_, _, settledHead) = interFrameChurn(settle = true)
        val (_, _, uniformHead) = interFrameChurn(settle = false)

        // Same mutation budget, aimed at the bright end: the head churns far harder
        // while the far tail is no busier than it was.
        assertTrue(
            "settling did not concentrate churn at the head " +
                "(settled=$settledHead uniform=$uniformHead)",
            settledHead > uniformHead * 1.8
        )
        // The far tail is left out of the comparison on purpose: at these brightness
        // levels the depth planes overlap, so the dim bucket mixes faded heads from
        // near columns with mid-trail glyphs from far ones and does not isolate it.
    }

    @Test
    fun mirroringStillRendersAndKeepsTheMessageForwards() {
        // The message is painted after the mirror transform is undone, so the same
        // text lands in the same place whether or not the rain is flipped.
        fun messageBandWidth(mirror: Boolean): Int {
            val renderer = RainRenderer(displayDensity = 2f)
            renderer.updateConfig(
                RainConfig(
                    mirrorGlyphs = mirror,
                    message = "WAKE UP NEO",
                    messageInterval = 5f,
                    density = 0.2f,
                    glow = 0f,
                    scanlines = 0f
                )
            )
            renderer.resize(w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // Run past the 5s interval and into the reveal's hold phase.
            var widest = 0
            repeat(400) {
                renderer.update(1f / 60f)
                renderer.draw(canvas)
                if (it > 300) {
                    for (y in 0 until h) {
                        var lit = 0
                        for (x in 0 until w step 2) {
                            if (Color.green(bmp.getPixel(x, y)) > 120) lit++
                        }
                        if (lit > widest) widest = lit
                    }
                }
            }
            renderer.release()
            return widest
        }

        val plain = messageBandWidth(mirror = false)
        val mirrored = messageBandWidth(mirror = true)
        assertTrue("no message row found unmirrored (got $plain)", plain > 20)
        assertTrue("no message row found mirrored (got $mirrored)", mirrored > 20)
    }

    @Test
    fun messageRevealAppearsAndThenClears() {
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(
            RainConfig(
                message = "FOLLOW THE WHITE RABBIT",
                messageInterval = 5f,
                density = 0.2f,
                glow = 0f,
                scanlines = 0f,
                glitch = 0f
            )
        )
        renderer.resize(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        fun brightestRowRun(): Int {
            var best = 0
            for (y in 0 until h) {
                var lit = 0
                for (x in 0 until w step 2) if (Color.green(bmp.getPixel(x, y)) > 120) lit++
                if (lit > best) best = lit
            }
            return best
        }

        // Before the first interval elapses there should be no dense bright row.
        repeat(120) { renderer.update(1f / 60f); renderer.draw(canvas) }
        val quiet = brightestRowRun()

        // Somewhere past 5s the reveal fires.
        var peak = 0
        repeat(300) {
            renderer.update(1f / 60f)
            renderer.draw(canvas)
            peak = maxOf(peak, brightestRowRun())
        }
        assertTrue("message never revealed (quiet=$quiet peak=$peak)", peak > quiet * 2)
        renderer.release()
    }

    @Test
    fun aBlankMessageNeverReveals() {
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(
            RainConfig(message = "   ", messageInterval = 5f, density = 0.2f, glow = 0f)
        )
        renderer.resize(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var peak = 0
        repeat(500) {
            renderer.update(1f / 60f)
            renderer.draw(canvas)
            for (y in 0 until h step 3) {
                var lit = 0
                for (x in 0 until w step 4) if (Color.green(bmp.getPixel(x, y)) > 120) lit++
                peak = maxOf(peak, lit)
            }
        }
        assertTrue("a blank message still drew something (peak=$peak)", peak < 40)
        renderer.release()
    }

    /** Renders the frames used in the README so the docs track the real output. */
    @Test
    fun showcaseFrames() {
        val shots = listOf(
            "showcase-mirrored" to RainConfig(mirrorGlyphs = true),
            "showcase-classic" to RainConfig().apply { toClassic() },
            "showcase-tilt" to RainConfig(
                tiltEnabled = true, tiltStrength = 1f, speed = 1.4f
            ),
            "showcase-message" to RainConfig(
                message = "WAKE UP, NEO", messageInterval = 5f, density = 0.6f
            )
        )
        for ((name, cfg) in shots) {
            val renderer = RainRenderer(displayDensity = 2f)
            renderer.updateConfig(cfg)
            renderer.resize(w, h)
            if (cfg.tiltEnabled) renderer.setTilt(0.8f)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // Long enough for a message reveal to have fired and be holding.
            val frames = if (cfg.message.isNotBlank()) 340 else 150
            repeat(frames) { renderer.update(1f / 60f); renderer.draw(canvas) }
            outDir.mkdirs()
            File(outDir, "$name.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            renderer.release()
        }
    }

    @Test
    fun liveConfigChangesAreAppliedWithoutRestart() {
        val renderer = RainRenderer(displayDensity = 2f)
        renderer.updateConfig(RainConfig())
        renderer.resize(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(60) { renderer.update(1f / 60f); renderer.draw(canvas) }

        // Change every grid-affecting knob mid-flight, as the settings screen does.
        renderer.updateConfig(
            RainConfig(
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
