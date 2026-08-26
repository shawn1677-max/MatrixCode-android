package com.codefall.rain

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The rain engine. Owns the column simulation and knows how to paint a frame onto a
 * [Canvas]. Deliberately free of any Android view/service plumbing so the fullscreen
 * screensaver, the daydream, the live wallpaper and the settings preview can all share it.
 *
 * Not thread safe: drive it from a single render thread.
 */
class RainRenderer(private val displayDensity: Float) {

    private class Column {
        var glyphs: CharArray = CharArray(0)
        var head = 0f
        var speed = 0f
        var trail = 0
        var dim = 1f
        var active = false
        var hue = 0f
        var flicker = 0f
        var respawnDelay = 0f
        var driftX = 0f
    }

    private var config: RainConfig = RainConfig()

    @Volatile
    private var pendingConfig: RainConfig? = null

    /** Surface size packed as (w shl 32) or h, published by whichever thread resizes. */
    @Volatile
    private var pendingSize: Long = 0

    private var width = 0
    private var height = 0
    private var cellW = 1f
    private var cellH = 1f
    private var cols = 0
    private var rows = 0
    private var columns: Array<Column> = emptyArray()
    private var elapsed = 0f

    /** Raw tilt from the sensor, -1 (left edge down) .. 1 (right edge down). */
    @Volatile
    private var targetTilt = 0f
    private var smoothedTilt = 0f

    // Message reveal state.
    private var messageTimer = 0f
    private var revealElapsed = -1f
    private var revealRow = 0

    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val messageGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val glowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
    }
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val scanlinePaint = Paint()
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val clockGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
    }

    private var bloomBitmap: Bitmap? = null
    private var scanlineShader: BitmapShader? = null
    private var scanlineBitmap: Bitmap? = null

    // CRT screen filters. Each is a cached shader or sprite so a frame costs one
    // full-screen draw rather than per-pixel work.
    private val aperturePaint = Paint()
    private var apertureBitmap: Bitmap? = null
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vignetteShader: RadialGradient? = null
    private val flickerPaint = Paint()
    private val rollPaint = Paint()
    private var rollShader: LinearGradient? = null
    private val rollMatrix = Matrix()
    private val noisePaint = Paint()
    private var noiseBitmap: Bitmap? = null
    private var noiseShader: BitmapShader? = null
    private val noiseMatrix = Matrix()
    private val colorFilters = HashMap<Int, PorterDuffColorFilter>()
    private val hsv = FloatArray(3)
    private val scratch = CharArray(1)
    private val bloomRect = android.graphics.RectF()

    /**
     * Feed in device tilt: -1 when the left edge is down, +1 when the right edge is
     * down. Safe to call from the sensor thread.
     */
    fun setTilt(x: Float) {
        targetTilt = x.coerceIn(-1f, 1f)
    }

    /** Swap in a new config; applied at the top of the next frame. */
    fun updateConfig(newConfig: RainConfig) {
        pendingConfig = newConfig.copyOf()
    }

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        pendingSize = (w.toLong() shl 32) or h.toLong()
    }

    private fun applyPendingSize() {
        val packed = pendingSize
        if (packed == 0L) return
        pendingSize = 0
        val w = (packed ushr 32).toInt()
        val h = (packed and 0xFFFFFFFFL).toInt()
        if (w == width && h == height) return
        width = w
        height = h
        rebuildGrid()
        rebuildScreenFilters()
    }

    private fun applyPendingConfig() {
        val pending = pendingConfig ?: return
        pendingConfig = null
        val gridChanged = pending.glyphSize != config.glyphSize
        val charsChanged = pending.glyphSet != config.glyphSet
        val trailChanged = pending.trailLength != config.trailLength
        val densityChanged = pending.density != config.density
        config = pending
        when {
            gridChanged -> rebuildGrid()
            charsChanged || trailChanged || densityChanged -> reseedColumns()
        }
        rebuildScanlines()
        rebuildScreenFilters()
    }

    private fun rebuildGrid() {
        if (width <= 0 || height <= 0) return
        val sizePx = max(8f, config.glyphSize * displayDensity)
        glyphPaint.textSize = sizePx
        glowStrokePaint.textSize = sizePx
        // Monospace: every glyph is the same advance, so one measurement describes the grid.
        cellW = max(1f, glyphPaint.measureText("M") * 1.06f)
        cellH = max(1f, sizePx * 1.14f)
        cols = max(1, ceil(width / cellW).toInt())
        rows = max(1, ceil(height / cellH).toInt() + 1)
        columns = Array(cols) { Column() }
        for (i in columns.indices) spawn(columns[i], i, initial = true)
        rebuildBloom(sizePx)
        rebuildScanlines()
        rebuildScreenFilters()
    }

    private fun reseedColumns() {
        for (i in columns.indices) {
            val c = columns[i]
            if (c.glyphs.size != rows + 2) c.glyphs = CharArray(rows + 2)
            fillGlyphs(c)
            c.trail = randomTrail()
            c.active = Random.nextFloat() < config.density
        }
    }

    private fun randomTrail(): Int {
        val base = rows * 0.42f * config.trailLength
        val jitter = base * Random.nextDouble(0.45, 1.25).toFloat()
        return max(3, min(rows + 1, jitter.toInt()))
    }

    private fun fillGlyphs(c: Column) {
        val chars = config.glyphSet.chars
        for (i in c.glyphs.indices) c.glyphs[i] = chars[Random.nextInt(chars.length)]
    }

    private fun spawn(c: Column, index: Int, initial: Boolean) {
        if (c.glyphs.size != rows + 2) c.glyphs = CharArray(rows + 2)
        fillGlyphs(c)
        c.trail = randomTrail()
        // Three depth planes: far columns are slower and dimmer, which reads as parallax.
        val depth = Random.nextInt(3)
        c.dim = when (depth) {
            0 -> 1.0f
            1 -> 0.72f
            else -> 0.55f
        }
        val depthSpeed = when (depth) {
            0 -> 1.0f
            1 -> 0.78f
            else -> 0.58f
        }
        // Rows per second, before the user's speed multiplier.
        c.speed = Random.nextDouble(6.0, 15.0).toFloat() * depthSpeed
        c.head = if (initial) {
            Random.nextDouble(-rows.toDouble(), rows.toDouble()).toFloat()
        } else {
            -Random.nextDouble(0.0, rows * 0.35).toFloat()
        }
        c.hue = (index * 360f / max(1, cols) + Random.nextDouble(-12.0, 12.0).toFloat())
        c.flicker = 0f
        c.driftX = 0f
        c.respawnDelay = if (initial) 0f else Random.nextDouble(0.0, 1.4).toFloat()
        c.active = Random.nextFloat() < config.density
    }

    private fun rebuildBloom(sizePx: Float) {
        bloomBitmap?.recycle()
        val d = max(16, (sizePx * 1.9f).toInt())
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = d / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            r, r, r,
            intArrayOf(0x88FFFFFF.toInt(), 0x30FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(r, r, r, p)
        bloomBitmap = bmp
    }

    private fun rebuildScanlines() {
        if (config.scanlines <= 0.01f) {
            scanlineShader = null
            return
        }
        val period = max(2, (2.5f * displayDensity).toInt())
        scanlineBitmap?.recycle()
        val bmp = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val darkness = (config.scanlines * 150f).toInt().coerceIn(0, 255)
        val p = Paint()
        p.color = Color.argb(darkness, 0, 0, 0)
        c.drawRect(0f, 0f, 1f, period / 2f, p)
        scanlineBitmap = bmp
        scanlineShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scanlinePaint.shader = scanlineShader
    }

    /** Builds the cached sprites and shaders behind the CRT screen filters. */
    private fun rebuildScreenFilters() {
        if (width <= 0 || height <= 0) return
        val cfg = config

        // Aperture grille: one tile of vertical phosphor stripes, tiled across.
        apertureBitmap?.recycle()
        apertureBitmap = null
        if (cfg.apertureGrille > 0.01f) {
            val stripe = max(1, displayDensity.toInt())
            val tile = Bitmap.createBitmap(stripe * 3, 1, Bitmap.Config.ARGB_8888)
            val c = Canvas(tile)
            val p = Paint()
            // A grille has to MULTIPLY, not draw over: each stripe passes its own
            // channel and holds the other two back. Drawing tinted stripes on top
            // would add light to the black background and brighten the picture,
            // which is the opposite of what a mask does.
            val low = (255 - cfg.apertureGrille * 190f).toInt().coerceIn(0, 255)
            p.color = Color.rgb(255, low, low)
            c.drawRect(0f, 0f, stripe.toFloat(), 1f, p)
            p.color = Color.rgb(low, 255, low)
            c.drawRect(stripe.toFloat(), 0f, stripe * 2f, 1f, p)
            p.color = Color.rgb(low, low, 255)
            c.drawRect(stripe * 2f, 0f, stripe * 3f, 1f, p)
            apertureBitmap = tile
            aperturePaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            aperturePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        } else {
            aperturePaint.shader = null
            aperturePaint.xfermode = null
        }

        // Vignette: a circular gradient squashed to the screen's aspect, so the
        // falloff follows the edges instead of bulging on a tall display.
        if (cfg.vignette > 0.01f) {
            val r = width / 2f
            val g = RadialGradient(
                width / 2f, height / 2f, max(1f, r),
                intArrayOf(0x00000000, 0x00000000, (cfg.vignette * 235f).toInt().coerceIn(0, 255) shl 24),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            val m = Matrix()
            m.setScale(1f, height.toFloat() / width, width / 2f, height / 2f)
            g.setLocalMatrix(m)
            vignetteShader = g
            vignettePaint.shader = g
        } else {
            vignetteShader = null
            vignettePaint.shader = null
        }

        // Rolling refresh bar: a soft band swept down the screen each cycle.
        if (cfg.crtFlicker > 0.01f) {
            val bandHeight = max(2f, height * 0.22f)
            val peak = (cfg.crtFlicker * 46f).toInt().coerceIn(0, 255)
            val g = LinearGradient(
                0f, 0f, 0f, bandHeight,
                intArrayOf(0x00FFFFFF, peak shl 24 or 0xFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            rollShader = g
            rollPaint.shader = g
        } else {
            rollShader = null
            rollPaint.shader = null
        }

        // Static: one noise tile, crawled by moving its matrix rather than redrawing.
        if (cfg.noise > 0.01f) {
            if (noiseBitmap == null) {
                val n = 96
                val px = IntArray(n * n)
                for (i in px.indices) {
                    val v = Random.nextInt(120, 256)
                    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
                noiseBitmap = bmp
                noiseShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
            noisePaint.shader = noiseShader
        } else {
            noisePaint.shader = null
        }
    }

    /** Paints every screen filter over the finished frame, in tube order. */
    private fun drawScreenFilters(canvas: Canvas) {
        val cfg = config
        val w = width.toFloat()
        val h = height.toFloat()

        scanlineShader?.let { canvas.drawRect(0f, 0f, w, h, scanlinePaint) }
        if (aperturePaint.shader != null) canvas.drawRect(0f, 0f, w, h, aperturePaint)
        if (vignettePaint.shader != null) canvas.drawRect(0f, 0f, w, h, vignettePaint)

        if (cfg.crtFlicker > 0.01f) {
            // Two loosely-related sine terms read as an unsteady supply rather than
            // a pulse, which a single sine always does.
            val wobble = (kotlin.math.sin(elapsed * 11.3f) * 0.5f +
                kotlin.math.sin(elapsed * 27.9f) * 0.5f)
            val dim = (cfg.crtFlicker * 26f * (0.5f + 0.5f * wobble)).toInt().coerceIn(0, 255)
            if (dim > 0) {
                flickerPaint.color = Color.argb(dim, 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, flickerPaint)
            }
            rollShader?.let { shader ->
                val period = 7f
                val y = ((elapsed % period) / period) * (h + h * 0.22f) - h * 0.22f
                rollMatrix.setTranslate(0f, y)
                shader.setLocalMatrix(rollMatrix)
                canvas.drawRect(0f, 0f, w, h, rollPaint)
            }
        }

        if (noisePaint.shader != null) {
            noiseMatrix.setTranslate(
                Random.nextInt(0, 96).toFloat(),
                Random.nextInt(0, 96).toFloat()
            )
            noiseShader?.setLocalMatrix(noiseMatrix)
            noisePaint.alpha = (cfg.noise * 60f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, noisePaint)
        }
    }

    private fun filterFor(color: Int): PorterDuffColorFilter =
        colorFilters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    /** Advance the simulation by [dt] seconds. */
    fun update(dt: Float) {
        applyPendingSize()
        applyPendingConfig()
        if (columns.isEmpty()) return
        val step = dt.coerceIn(0f, 0.1f)
        elapsed += step
        val chars = config.glyphSet.chars
        val speedMul = config.speed

        // Ease toward the sensor reading so the rain leans rather than snaps.
        val wantTilt =
            if (config.tiltEnabled) (targetTilt * config.tiltStrength).coerceIn(-1f, 1f) else 0f
        smoothedTilt += (wantTilt - smoothedTilt) * min(1f, step * 5f)

        advanceMessage(step)

        for (i in columns.indices) {
            val c = columns[i]

            if (!c.active) {
                c.respawnDelay -= step
                if (c.respawnDelay <= 0f) spawn(c, i, initial = false)
                continue
            }

            if (c.flicker > 0f) c.flicker -= step

            c.head += c.speed * speedMul * step

            // Tilt pulls each column sideways at its own fall rate, so the whole
            // field shears instead of sliding rigidly.
            if (smoothedTilt != 0f) {
                c.driftX += smoothedTilt * c.speed * speedMul * step * cellH * LEAN_PER_ROW
            }

            // In-place glyph mutation: the churn that makes the trail feel alive.
            if (config.mutationRate > 0f) {
                val mutations = config.mutationRate * c.trail * step * 9f
                var n = mutations.toInt()
                if (Random.nextFloat() < mutations - n) n++
                val headRow = c.head.toInt()
                repeat(n) {
                    val idx = if (config.settleTrail) {
                        // Bias churn hard toward the head: a cubed sample over the
                        // leading part of the trail keeps the boil at the bright end
                        // and lets glyphs freeze once they fall behind and fade.
                        val u = Random.nextFloat()
                        headRow - (u * u * u * c.trail * 0.6f).toInt()
                    } else {
                        Random.nextInt(c.glyphs.size)
                    }
                    if (idx >= 0 && idx < c.glyphs.size) {
                        c.glyphs[idx] = chars[Random.nextInt(chars.length)]
                    }
                }
            }

            // Glitch: an occasional column blinks out for a beat.
            if (config.glitch > 0f && Random.nextFloat() < config.glitch * step * 0.9f) {
                c.flicker = Random.nextDouble(0.04, 0.22).toFloat()
            }

            if (c.head - c.trail > rows) spawn(c, i, initial = false)
        }
    }

    private fun advanceMessage(step: Float) {
        if (config.message.isBlank()) {
            revealElapsed = -1f
            messageTimer = 0f
            return
        }
        if (revealElapsed >= 0f) {
            revealElapsed += step
            if (revealElapsed > REVEAL_DURATION) revealElapsed = -1f
            return
        }
        messageTimer += step
        if (messageTimer >= config.messageInterval.coerceAtLeast(1f)) {
            messageTimer = 0f
            revealElapsed = 0f
            // Drift the line around the middle third so it doesn't always land dead centre.
            val mid = rows / 2
            revealRow = (mid + Random.nextInt(-rows / 8, rows / 8 + 1)).coerceIn(1, rows - 2)
        }
    }

    /** Fade envelope for the reveal: in, hold, out. 0 when nothing is showing. */
    private fun revealAlpha(): Float {
        val t = revealElapsed
        if (t < 0f) return 0f
        return when {
            t < REVEAL_FADE_IN -> t / REVEAL_FADE_IN
            t < REVEAL_DURATION - REVEAL_FADE_OUT -> 1f
            else -> ((REVEAL_DURATION - t) / REVEAL_FADE_OUT).coerceIn(0f, 1f)
        }
    }

    /** Paint the current state. The canvas is expected to cover the full surface. */
    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (columns.isEmpty()) return

        val cfg = config
        val rainbow = cfg.theme == ColorTheme.RAINBOW
        val headColor = cfg.theme.head
        val bodyColor = cfg.theme.body
        val glow = cfg.glow
        val bloom = bloomBitmap
        val widthF = width.toFloat()

        glowStrokePaint.strokeWidth = max(0.6f, glow * cellH * 0.10f)

        // The film's glyphs were shot mirrored. Flipping the whole rain layer once is
        // far cheaper than flipping each glyph, and since column order is random the
        // reversed layout is indistinguishable. The clock and the message are drawn
        // after the flip is undone, so they stay readable.
        val mirror = cfg.mirrorGlyphs
        val restoreTo = canvas.save()
        if (mirror) canvas.scale(-1f, 1f, widthF * 0.5f, 0f)
        // A mirrored canvas also reverses apparent motion, so the lean is negated to
        // keep the rain falling toward whichever edge the user tipped down.
        val lean = if (mirror) -smoothedTilt else smoothedTilt

        for (i in columns.indices) {
            val c = columns[i]
            if (!c.active || c.flicker > 0f) continue

            val headRow = c.head.toInt()
            val colBody: Int
            val colHead: Int
            if (rainbow) {
                hsv[0] = ((c.hue + elapsed * 22f) % 360f + 360f) % 360f
                hsv[1] = 0.85f
                hsv[2] = 1f
                colBody = Color.HSVToColor(hsv)
                hsv[1] = 0.22f
                colHead = Color.HSVToColor(hsv)
            } else {
                colBody = bodyColor
                colHead = headColor
            }

            var columnX = i * cellW + (if (mirror) -c.driftX else c.driftX)
            if (widthF > 0f) {
                columnX %= widthF
                if (columnX < 0f) columnX += widthF
            }
            val trail = c.trail

            // Paint from the tail up so brighter glyphs land on top of dimmer ones.
            for (t in trail downTo 0) {
                val row = headRow - t
                if (row < 0 || row >= rows) continue

                val fade = if (trail == 0) 1f else 1f - (t.toFloat() / trail)
                // A steep curve keeps a bright head with a long, quickly-dimming tail.
                var intensity = fade.pow(1.5f) * c.dim
                if (t == 0) intensity = c.dim

                val alpha = (intensity * 255f).toInt().coerceIn(0, 255)
                if (alpha < 6) continue

                val y = (row + 1) * cellH - cellH * 0.18f
                // Trail glyphs mark where the head has been, so they lag behind the
                // lean by one cell per row.
                // Only the column's own position recirculates; trail glyphs are left
                // to run off the edge, since wrapping mid-streak would tear it in two.
                val x = columnX - lean * t * cellH * LEAN_PER_ROW
                scratch[0] = c.glyphs[row.coerceIn(0, c.glyphs.size - 1)]

                if (t == 0) {
                    // Bloom halo behind the leading glyph.
                    if (glow > 0.02f && bloom != null) {
                        bloomPaint.colorFilter = filterFor(colBody)
                        bloomPaint.alpha = (glow * 105f * c.dim).toInt().coerceIn(0, 255)
                        val bw = bloom.width.toFloat()
                        val cx = x + cellW * 0.5f
                        val cy = y - cellH * 0.32f
                        bloomRect.set(
                            cx - bw * 0.5f, cy - bw * 0.5f,
                            cx + bw * 0.5f, cy + bw * 0.5f
                        )
                        canvas.drawBitmap(bloom, null, bloomRect, bloomPaint)
                    }
                    drawFringed(canvas, x, y, alpha, mirror)
                    glyphPaint.color = colHead
                    glyphPaint.alpha = alpha
                    canvas.drawText(scratch, 0, 1, x, y, glyphPaint)
                } else {
                    if (glow > 0.02f && t <= 3) {
                        glowStrokePaint.color = colBody
                        glowStrokePaint.alpha =
                            (alpha * glow * 0.5f).toInt().coerceIn(0, 255)
                        canvas.drawText(scratch, 0, 1, x, y, glowStrokePaint)
                    }
                    glyphPaint.color = colBody
                    glyphPaint.alpha = alpha
                    canvas.drawText(scratch, 0, 1, x, y, glyphPaint)
                }
            }
        }

        canvas.restoreToCount(restoreTo)

        val overlayColor = if (rainbow) ColorTheme.MONO.head else cfg.theme.head
        if (revealElapsed >= 0f) drawMessage(canvas, overlayColor)
        if (cfg.showClock) drawClock(canvas, overlayColor)

        drawScreenFilters(canvas)
    }

    /**
     * How far the red and blue channels separate at this x. Real tubes fringe hardest
     * at the edges and not at all dead centre, so the split scales with distance from
     * the middle. Returns 0 when aberration is off.
     */
    private fun aberrationOffset(x: Float, mirrored: Boolean): Float {
        val amount = config.aberration
        if (amount <= 0.01f || width <= 0) return 0f
        val centre = width / 2f
        val radial = ((x - centre) / centre).coerceIn(-1f, 1f)
        val offset = amount * cellW * 0.30f * radial
        return if (mirrored) -offset else offset
    }

    /** Draws one glyph as separated red and blue copies either side of the original. */
    private fun drawFringed(
        canvas: Canvas,
        x: Float,
        y: Float,
        alpha: Int,
        mirrored: Boolean
    ) {
        val offset = aberrationOffset(x, mirrored)
        if (offset == 0f) return
        val a = (alpha * 0.45f).toInt().coerceIn(0, 255)
        fringePaint.textSize = glyphPaint.textSize
        fringePaint.color = Color.RED
        fringePaint.alpha = a
        canvas.drawText(scratch, 0, 1, x - offset, y, fringePaint)
        fringePaint.color = Color.CYAN
        fringePaint.alpha = a
        canvas.drawText(scratch, 0, 1, x + offset, y, fringePaint)
    }

    /**
     * The reveal: the rain briefly resolves into the user's text, holds, and dissolves.
     * Drawn after the mirror transform is undone so the message reads forwards.
     */
    private fun drawMessage(canvas: Canvas, color: Int) {
        val text = config.message.trim()
        if (text.isEmpty()) return
        val envelope = revealAlpha()
        if (envelope <= 0.01f) return

        val size = min(cellH * 2.4f, height * 0.11f)
        messagePaint.textSize = size
        val measured = messagePaint.measureText(text)
        val maxWidth = width * 0.9f
        if (measured > maxWidth && measured > 0f) {
            messagePaint.textSize = size * (maxWidth / measured)
        }
        messageGlowPaint.textSize = messagePaint.textSize
        messageGlowPaint.strokeWidth = max(1f, messagePaint.textSize * 0.025f)

        val cx = width / 2f
        val cy = (revealRow + 1) * cellH

        // A soft band knocks the rain back just enough for the text to read.
        val bandHeight = messagePaint.textSize * 1.7f
        scrimPaint.color = Color.BLACK
        scrimPaint.alpha = (envelope * 170f).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, cy - bandHeight * 0.78f, width.toFloat(), cy + bandHeight * 0.34f, scrimPaint)

        messageGlowPaint.color = color
        messageGlowPaint.alpha = (envelope * config.glow * 120f).toInt().coerceIn(0, 255)
        canvas.drawText(text, cx, cy, messageGlowPaint)

        messagePaint.color = color
        messagePaint.alpha = (envelope * 255f).toInt().coerceIn(0, 255)
        canvas.drawText(text, cx, cy, messagePaint)
    }

    private fun drawClock(canvas: Canvas, color: Int) {
        val cal = Calendar.getInstance()
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val hour = if (config.clock24h) hour24 else {
            val h = hour24 % 12
            if (h == 0) 12 else h
        }
        val minute = cal.get(Calendar.MINUTE)
        val text = buildString {
            if (config.clock24h && hour < 10) append('0')
            append(hour)
            append(':')
            if (minute < 10) append('0')
            append(minute)
            if (!config.clock24h) append(if (hour24 < 12) " AM" else " PM")
        }

        val size = min(width, height) * config.clockSize.coerceIn(0.05f, 0.35f)
        clockPaint.textSize = size
        clockGlowPaint.textSize = size
        clockGlowPaint.strokeWidth = max(1f, size * 0.02f)

        // clockX/clockY sweep the clock across the area where it still fits whole, so
        // dragging a slider to either end parks it against that edge rather than
        // pushing half the digits off screen.
        val margin = size * 0.22f
        val halfText = clockPaint.measureText(text) / 2f
        val minX = halfText + margin
        val maxX = width - halfText - margin
        val cx = if (minX >= maxX) width / 2f else minX + (maxX - minX) * config.clockX

        val fm = clockPaint.fontMetrics
        val minY = -fm.top + margin
        val maxY = height - fm.bottom - margin
        val cy = if (minY >= maxY) height / 2f else minY + (maxY - minY) * config.clockY

        val fringe = aberrationOffset(cx, mirrored = false)
        if (fringe != 0f) {
            clockPaint.color = Color.RED
            clockPaint.alpha = 90
            canvas.drawText(text, cx - fringe, cy, clockPaint)
            clockPaint.color = Color.CYAN
            clockPaint.alpha = 90
            canvas.drawText(text, cx + fringe, cy, clockPaint)
        }

        clockGlowPaint.color = color
        clockGlowPaint.alpha = (config.glow * 90f).toInt().coerceIn(20, 255)
        canvas.drawText(text, cx, cy, clockGlowPaint)

        clockPaint.color = color
        clockPaint.alpha = 210
        canvas.drawText(text, cx, cy, clockPaint)
    }

    companion object {
        /** Sideways pixels per row of fall at full lean: about a 17 degree slant. */
        private const val LEAN_PER_ROW = 0.30f
        private const val REVEAL_FADE_IN = 0.7f
        private const val REVEAL_FADE_OUT = 1.1f
        private const val REVEAL_DURATION = 4.0f
    }

    fun release() {
        bloomBitmap?.recycle()
        bloomBitmap = null
        scanlineBitmap?.recycle()
        scanlineBitmap = null
        scanlineShader = null
        colorFilters.clear()
    }
}
