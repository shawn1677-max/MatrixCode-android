package com.matrixcode.rain

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
class MatrixRenderer(private val displayDensity: Float) {

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
    }

    @Volatile
    private var config: MatrixConfig = MatrixConfig()
    private var pendingConfig: MatrixConfig? = null

    private var width = 0
    private var height = 0
    private var cellW = 1f
    private var cellH = 1f
    private var cols = 0
    private var rows = 0
    private var columns: Array<Column> = emptyArray()
    private var elapsed = 0f

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
    private val colorFilters = HashMap<Int, PorterDuffColorFilter>()
    private val hsv = FloatArray(3)
    private val scratch = CharArray(1)
    private val bloomRect = android.graphics.RectF()

    /** Swap in a new config; applied at the top of the next frame. */
    fun updateConfig(newConfig: MatrixConfig) {
        pendingConfig = newConfig.copyOf()
    }

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w
        height = h
        rebuildGrid()
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

    private fun filterFor(color: Int): PorterDuffColorFilter =
        colorFilters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    /** Advance the simulation by [dt] seconds. */
    fun update(dt: Float) {
        applyPendingConfig()
        if (columns.isEmpty()) return
        val step = dt.coerceIn(0f, 0.1f)
        elapsed += step
        val chars = config.glyphSet.chars
        val speedMul = config.speed

        for (i in columns.indices) {
            val c = columns[i]

            if (!c.active) {
                c.respawnDelay -= step
                if (c.respawnDelay <= 0f) spawn(c, i, initial = false)
                continue
            }

            if (c.flicker > 0f) c.flicker -= step

            c.head += c.speed * speedMul * step

            // In-place glyph mutation: the churn that makes the trail feel alive.
            if (config.mutationRate > 0f) {
                val mutations = config.mutationRate * c.trail * step * 9f
                var n = mutations.toInt()
                if (Random.nextFloat() < mutations - n) n++
                repeat(n) {
                    val idx = Random.nextInt(c.glyphs.size)
                    c.glyphs[idx] = chars[Random.nextInt(chars.length)]
                }
            }

            // Glitch: an occasional column blinks out for a beat.
            if (config.glitch > 0f && Random.nextFloat() < config.glitch * step * 0.9f) {
                c.flicker = Random.nextDouble(0.04, 0.22).toFloat()
            }

            if (c.head - c.trail > rows) spawn(c, i, initial = false)
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

        glowStrokePaint.strokeWidth = max(0.6f, glow * cellH * 0.10f)

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

            val x = i * cellW
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

        if (cfg.showClock) drawClock(canvas, if (rainbow) ColorTheme.MONO.head else cfg.theme.head)

        scanlineShader?.let {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scanlinePaint)
        }
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

        val size = min(width, height) * 0.17f
        clockPaint.textSize = size
        clockGlowPaint.textSize = size
        clockGlowPaint.strokeWidth = max(1f, size * 0.02f)

        val cx = width / 2f
        val cy = height / 2f + size * 0.34f

        clockGlowPaint.color = color
        clockGlowPaint.alpha = (config.glow * 90f).toInt().coerceIn(20, 255)
        canvas.drawText(text, cx, cy, clockGlowPaint)

        clockPaint.color = color
        clockPaint.alpha = 210
        canvas.drawText(text, cx, cy, clockPaint)
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
