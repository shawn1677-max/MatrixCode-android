package com.matrixcode.rain

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** Character sets the rain can be built from. */
enum class GlyphSet(val label: String, val chars: String) {
    KATAKANA(
        "Katakana",
        "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789"
    ),
    BINARY("Binary", "01"),
    HEX("Hexadecimal", "0123456789ABCDEF"),
    ASCII("ASCII", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*+-/<>=?!"),
    SYMBOLS("Symbols", "¤¦§¨«¬®°±µ¶·»¿×÷ΔΘΛΞΠΣΦΨΩ†‡•‰‹›€™∂∆∏∑√∞∫≈≠≤≥⌂⌐■□▲►▼◄◊○●"),
    MIXED(
        "Mixed",
        "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789ABCDEF@#$%&*+-/<>=?!ΔΘΛΞΠΣΦΨΩ"
    );

    companion object {
        fun fromOrdinal(i: Int): GlyphSet = entries.getOrElse(i) { KATAKANA }
    }
}

/**
 * Colour themes. [head] is the colour of the leading glyph, [body] the colour the
 * trail fades through. RAINBOW is handled specially by the renderer (per-column hue).
 */
enum class ColorTheme(val label: String, val head: Int, val body: Int) {
    MATRIX_GREEN("Matrix Green", 0xFFCCFFCC.toInt(), 0xFF00FF41.toInt()),
    AMBER("Amber CRT", 0xFFFFF0C0.toInt(), 0xFFFFA500.toInt()),
    CYAN("Ice Cyan", 0xFFDDFFFF.toInt(), 0xFF00E5FF.toInt()),
    CRIMSON("Red Pill", 0xFFFFD0D0.toInt(), 0xFFFF1744.toInt()),
    PURPLE("Neon Violet", 0xFFF0D8FF.toInt(), 0xFFB14CFF.toInt()),
    GOLD("Gold", 0xFFFFF8DC.toInt(), 0xFFFFD700.toInt()),
    MONO("Ghost White", 0xFFFFFFFF.toInt(), 0xFFB0B8C0.toInt()),
    RAINBOW("Rainbow", 0xFFFFFFFF.toInt(), 0xFF00FF41.toInt());

    companion object {
        fun fromOrdinal(i: Int): ColorTheme = entries.getOrElse(i) { MATRIX_GREEN }
    }
}

/**
 * Every tunable knob of the rain, persisted in SharedPreferences so the settings
 * screen, the fullscreen screensaver, the daydream and the live wallpaper all agree.
 */
data class MatrixConfig(
    var theme: ColorTheme = ColorTheme.MATRIX_GREEN,
    var glyphSet: GlyphSet = GlyphSet.KATAKANA,
    /** Fall speed multiplier, 0.1x .. 4.0x. */
    var speed: Float = 1.0f,
    /** Glyph size in sp-ish density-independent pixels; drives column count. */
    var glyphSize: Float = 18f,
    /** Fraction of columns that are active at any time, 0.2 .. 1.0. */
    var density: Float = 0.85f,
    /** Trail length multiplier, 0.2 .. 2.0 (scaled against screen height). */
    var trailLength: Float = 1.0f,
    /** How often glyphs mutate in place, 0 .. 1. */
    var mutationRate: Float = 0.35f,
    /** Glow / bloom strength around glyphs, 0 .. 1. */
    var glow: Float = 0.6f,
    /** CRT scanline overlay strength, 0 .. 1. */
    var scanlines: Float = 0.25f,
    /** Chance per frame of a column flickering out, 0 .. 1. */
    var glitch: Float = 0.15f,
    /** Draw a clock over the rain. */
    var showClock: Boolean = false,
    /** 24-hour clock instead of 12-hour. */
    var clock24h: Boolean = true,
    /** Keep the screen on while the screensaver is showing. */
    var keepScreenOn: Boolean = true
) {
    fun copyOf(): MatrixConfig = copy()

    fun randomize() {
        theme = ColorTheme.entries.random()
        glyphSet = GlyphSet.entries.random()
        speed = Random.nextDouble(0.4, 2.6).toFloat()
        glyphSize = Random.nextDouble(12.0, 34.0).toFloat()
        density = Random.nextDouble(0.45, 1.0).toFloat()
        trailLength = Random.nextDouble(0.4, 1.8).toFloat()
        mutationRate = Random.nextDouble(0.0, 0.8).toFloat()
        glow = Random.nextDouble(0.0, 1.0).toFloat()
        scanlines = Random.nextDouble(0.0, 0.6).toFloat()
        glitch = Random.nextDouble(0.0, 0.5).toFloat()
    }

    companion object {
        private const val PREFS = "matrix_config"

        fun load(context: Context): MatrixConfig {
            val p = prefs(context)
            val d = MatrixConfig()
            return MatrixConfig(
                theme = ColorTheme.fromOrdinal(p.getInt("theme", d.theme.ordinal)),
                glyphSet = GlyphSet.fromOrdinal(p.getInt("glyphSet", d.glyphSet.ordinal)),
                speed = p.getFloat("speed", d.speed),
                glyphSize = p.getFloat("glyphSize", d.glyphSize),
                density = p.getFloat("density", d.density),
                trailLength = p.getFloat("trailLength", d.trailLength),
                mutationRate = p.getFloat("mutationRate", d.mutationRate),
                glow = p.getFloat("glow", d.glow),
                scanlines = p.getFloat("scanlines", d.scanlines),
                glitch = p.getFloat("glitch", d.glitch),
                showClock = p.getBoolean("showClock", d.showClock),
                clock24h = p.getBoolean("clock24h", d.clock24h),
                keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn)
            )
        }

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putInt("theme", theme.ordinal)
            .putInt("glyphSet", glyphSet.ordinal)
            .putFloat("speed", speed)
            .putFloat("glyphSize", glyphSize)
            .putFloat("density", density)
            .putFloat("trailLength", trailLength)
            .putFloat("mutationRate", mutationRate)
            .putFloat("glow", glow)
            .putFloat("scanlines", scanlines)
            .putFloat("glitch", glitch)
            .putBoolean("showClock", showClock)
            .putBoolean("clock24h", clock24h)
            .putBoolean("keepScreenOn", keepScreenOn)
            .apply()
    }
}
