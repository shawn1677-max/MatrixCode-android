package com.codefall.rain

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders the Play Store listing artwork from the real renderer, so the screenshots
 * are the actual product rather than a mockup that can drift away from it.
 *
 * Output lands in build/store/ and is post-processed into store/ by tools/store_assets.py.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreAssetsTest {

    private val outDir = File("build/store")

    private fun render(
        name: String,
        width: Int,
        height: Int,
        density: Float,
        frames: Int,
        config: RainConfig,
        tilt: Float = 0f
    ) {
        val renderer = RainRenderer(displayDensity = density)
        renderer.updateConfig(config)
        renderer.resize(width, height)
        if (tilt != 0f) renderer.setTilt(tilt)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(frames) { renderer.update(1f / 60f); renderer.draw(canvas) }
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        renderer.release()
    }

    @Test
    fun renderStoreAssets() {
        val w = 1080
        val h = 1920
        val d = 3f

        render(
            "screen-1-classic", w, h, d, 220,
            RainConfig(theme = ColorTheme.CLASSIC_GREEN, glyphSize = 16f, density = 0.9f)
        )
        render(
            "screen-2-message", w, h, d, 340,
            RainConfig(
                theme = ColorTheme.CLASSIC_GREEN, glyphSize = 16f, density = 0.7f,
                message = "WAKE UP", messageInterval = 5f
            )
        )
        render(
            "screen-3-amber-clock", w, h, d, 220,
            RainConfig(
                theme = ColorTheme.AMBER, glyphSize = 17f, showClock = true,
                scanlines = 0.4f
            )
        )
        render(
            "screen-4-rainbow", w, h, d, 220,
            RainConfig(theme = ColorTheme.RAINBOW, glyphSize = 15f, density = 0.95f)
        )
        render(
            "screen-5-tilt", w, h, d, 200,
            RainConfig(
                theme = ColorTheme.CYAN, glyphSize = 16f, speed = 1.4f,
                tiltEnabled = true, tiltStrength = 1f
            ),
            tilt = 0.75f
        )

        // Feature graphic: the wordmark drawn by the app's own message reveal, so the
        // banner uses exactly the typography the product uses.
        render(
            "feature-raw", 1024, 500, 2f, 330,
            RainConfig(
                theme = ColorTheme.CLASSIC_GREEN, glyphSize = 13f, density = 0.85f,
                message = "CODEFALL", messageInterval = 5f, glow = 0.8f
            )
        )
    }
}
