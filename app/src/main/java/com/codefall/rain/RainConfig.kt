package com.codefall.rain

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
    CLASSIC_GREEN("Classic Green", 0xFFCCFFCC.toInt(), 0xFF00FF41.toInt()),
    AMBER("Amber CRT", 0xFFFFF0C0.toInt(), 0xFFFFA500.toInt()),
    CYAN("Ice Cyan", 0xFFDDFFFF.toInt(), 0xFF00E5FF.toInt()),
    CRIMSON("Red Pill", 0xFFFFD0D0.toInt(), 0xFFFF1744.toInt()),
    PURPLE("Neon Violet", 0xFFF0D8FF.toInt(), 0xFFB14CFF.toInt()),
    GOLD("Gold", 0xFFFFF8DC.toInt(), 0xFFFFD700.toInt()),
    MONO("Ghost White", 0xFFFFFFFF.toInt(), 0xFFB0B8C0.toInt()),
    RAINBOW("Rainbow", 0xFFFFFFFF.toInt(), 0xFF00FF41.toInt());

    companion object {
        fun fromOrdinal(i: Int): ColorTheme = entries.getOrElse(i) { CLASSIC_GREEN }
    }
}

/**
 * Every tunable knob of the rain, persisted in SharedPreferences so the settings
 * screen, the fullscreen screensaver, the daydream and the live wallpaper all agree.
 */
data class RainConfig(
    var theme: ColorTheme = ColorTheme.CLASSIC_GREEN,
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
    /** Vertical phosphor stripe mask, the Trinitron look, 0 .. 1. */
    var apertureGrille: Float = 0f,
    /** Darkening toward the corners, as a curved tube would, 0 .. 1. */
    var vignette: Float = 0f,
    /** Brightness wobble plus a slow rolling refresh bar, 0 .. 1. */
    var crtFlicker: Float = 0f,
    /** Analog static crawling over the picture, 0 .. 1. */
    var noise: Float = 0f,
    /** RGB fringing, strongest toward the edges as on a real tube, 0 .. 1. */
    var aberration: Float = 0f,
    /** Chance per frame of a column flickering out, 0 .. 1. */
    var glitch: Float = 0.15f,
    /** Draw a clock over the rain. */
    var showClock: Boolean = false,
    /** 24-hour clock instead of 12-hour. */
    var clock24h: Boolean = true,
    /** Clock position across the screen, 0 = left edge, 1 = right edge. */
    var clockX: Float = 0.5f,
    /** Clock position down the screen, 0 = top edge, 1 = bottom edge. */
    var clockY: Float = 0.5f,
    /** Clock height as a fraction of the screen's shorter side, 0.05 .. 0.35. */
    var clockSize: Float = 0.17f,
    /** Keep the screen on while the screensaver is showing. */
    var keepScreenOn: Boolean = true,
    /** Flip glyphs horizontally, the way the film's code was shot. */
    var mirrorGlyphs: Boolean = true,
    /** Concentrate glyph churn near the head so the tail settles as it fades. */
    var settleTrail: Boolean = true,
    /** Let device tilt steer which way the rain falls. */
    var tiltEnabled: Boolean = false,
    /** How hard tilt pulls the rain sideways, 0 .. 2. */
    var tiltStrength: Float = 1.0f,
    /** Text the rain periodically resolves into. Blank disables the reveal. */
    var message: String = "",
    /** Seconds between message reveals, 5 .. 120. */
    var messageInterval: Float = 25f
) {
    fun copyOf(): RainConfig = copy()

    /**
     * The exact look v1.0 shipped with: stock defaults, every later effect off.
     * Screensaver behaviour (clock, screen-awake) and the message are left alone.
     */
    fun toClassic() {
        val d = RainConfig()
        theme = d.theme
        glyphSet = d.glyphSet
        speed = d.speed
        glyphSize = d.glyphSize
        density = d.density
        trailLength = d.trailLength
        mutationRate = d.mutationRate
        glow = d.glow
        scanlines = d.scanlines
        glitch = d.glitch
        mirrorGlyphs = false
        settleTrail = false
        tiltEnabled = false
        apertureGrille = 0f
        vignette = 0f
        crtFlicker = 0f
        noise = 0f
        aberration = 0f
    }

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
        mirrorGlyphs = Random.nextBoolean()
        settleTrail = Random.nextBoolean()
        // Screen filters read as grime, so keep them sparse and gentle when rolled.
        apertureGrille = if (Random.nextFloat() < 0.4f) Random.nextDouble(0.1, 0.7).toFloat() else 0f
        vignette = if (Random.nextFloat() < 0.5f) Random.nextDouble(0.1, 0.8).toFloat() else 0f
        crtFlicker = if (Random.nextFloat() < 0.35f) Random.nextDouble(0.1, 0.6).toFloat() else 0f
        noise = if (Random.nextFloat() < 0.35f) Random.nextDouble(0.05, 0.45).toFloat() else 0f
        aberration = if (Random.nextFloat() < 0.4f) Random.nextDouble(0.1, 0.7).toFloat() else 0f
    }

    companion object {
        private const val PREFS = "codefall_config"

        fun load(context: Context): RainConfig {
            val p = prefs(context)
            val d = RainConfig()
            return RainConfig(
                theme = ColorTheme.fromOrdinal(p.getInt("theme", d.theme.ordinal)),
                glyphSet = GlyphSet.fromOrdinal(p.getInt("glyphSet", d.glyphSet.ordinal)),
                speed = p.getFloat("speed", d.speed),
                glyphSize = p.getFloat("glyphSize", d.glyphSize),
                density = p.getFloat("density", d.density),
                trailLength = p.getFloat("trailLength", d.trailLength),
                mutationRate = p.getFloat("mutationRate", d.mutationRate),
                glow = p.getFloat("glow", d.glow),
                scanlines = p.getFloat("scanlines", d.scanlines),
                apertureGrille = p.getFloat("apertureGrille", d.apertureGrille),
                vignette = p.getFloat("vignette", d.vignette),
                crtFlicker = p.getFloat("crtFlicker", d.crtFlicker),
                noise = p.getFloat("noise", d.noise),
                aberration = p.getFloat("aberration", d.aberration),
                glitch = p.getFloat("glitch", d.glitch),
                showClock = p.getBoolean("showClock", d.showClock),
                clock24h = p.getBoolean("clock24h", d.clock24h),
                clockX = p.getFloat("clockX", d.clockX),
                clockY = p.getFloat("clockY", d.clockY),
                clockSize = p.getFloat("clockSize", d.clockSize),
                keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
                mirrorGlyphs = p.getBoolean("mirrorGlyphs", d.mirrorGlyphs),
                settleTrail = p.getBoolean("settleTrail", d.settleTrail),
                tiltEnabled = p.getBoolean("tiltEnabled", d.tiltEnabled),
                tiltStrength = p.getFloat("tiltStrength", d.tiltStrength),
                message = p.getString("message", d.message) ?: d.message,
                messageInterval = p.getFloat("messageInterval", d.messageInterval)
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
            .putFloat("apertureGrille", apertureGrille)
            .putFloat("vignette", vignette)
            .putFloat("crtFlicker", crtFlicker)
            .putFloat("noise", noise)
            .putFloat("aberration", aberration)
            .putFloat("glitch", glitch)
            .putBoolean("showClock", showClock)
            .putBoolean("clock24h", clock24h)
            .putFloat("clockX", clockX)
            .putFloat("clockY", clockY)
            .putFloat("clockSize", clockSize)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putBoolean("mirrorGlyphs", mirrorGlyphs)
            .putBoolean("settleTrail", settleTrail)
            .putBoolean("tiltEnabled", tiltEnabled)
            .putFloat("tiltStrength", tiltStrength)
            .putString("message", message)
            .putFloat("messageInterval", messageInterval)
            .apply()
    }
}
