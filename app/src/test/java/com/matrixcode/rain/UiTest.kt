package com.matrixcode.rain

import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTest {

    private fun controlsOf(activity: MainActivity): LinearLayout =
        activity.findViewById(R.id.controls)

    private fun <T> collect(root: android.view.ViewGroup, type: Class<T>): List<T> {
        val out = mutableListOf<T>()
        fun walk(v: android.view.View) {
            if (type.isInstance(v)) out.add(type.cast(v)!!)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun settingsScreenInflatesWithEveryControl() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val controls = controlsOf(activity)

        // Two spinners (colour, character set), eight sliders, three switches.
        assertEquals(2, collect(controls, Spinner::class.java).size)
        assertEquals(8, collect(controls, SeekBar::class.java).size)
        assertEquals(3, collect(controls, Switch::class.java).size)

        assertNotNull(activity.findViewById<MatrixSurfaceView>(R.id.preview))
        assertNotNull(activity.findViewById<Button>(R.id.btnStart))
        assertNotNull(activity.findViewById<Button>(R.id.btnRandomize))
        assertNotNull(activity.findViewById<Button>(R.id.btnReset))
    }

    @Test
    fun draggingASliderPersistsTheNewValue() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Slider order: glyph size, density, fall speed, trail, churn, glow, scanlines, glitch.
        val speed = collect(controlsOf(activity), SeekBar::class.java)[2]

        // A programmatic setProgress isn't flagged as user-driven, so drive the
        // listener the way a real drag does.
        val listener = org.robolectric.Shadows.shadowOf(speed).onSeekBarChangeListener
        assertNotNull("speed slider has no listener", listener)
        listener!!.onProgressChanged(speed, 750, true)

        val saved = MatrixConfig.load(activity)
        assertEquals(0.1f + (4f - 0.1f) * 0.75f, saved.speed, 0.01f)
    }

    @Test
    fun randomizeChangesSettingsAndKeepsThemInRange() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val before = MatrixConfig.load(activity)

        var differed = false
        repeat(8) {
            activity.findViewById<Button>(R.id.btnRandomize).performClick()
            val after = MatrixConfig.load(activity)
            if (after != before) differed = true
            assertTrue("speed out of range: ${after.speed}", after.speed in 0.1f..4f)
            assertTrue("glyph size out of range", after.glyphSize in 10f..36f)
            assertTrue("density out of range", after.density in 0.2f..1f)
            assertTrue("trail out of range", after.trailLength in 0.2f..2f)
            assertTrue("glow out of range", after.glow in 0f..1f)
        }
        assertTrue("randomize never changed anything", differed)
    }

    @Test
    fun resetRestoresVisualDefaultsButKeepsScreensaverPrefs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        MatrixConfig(theme = ColorTheme.GOLD, speed = 3.5f, showClock = true, clock24h = false)
            .save(activity)

        val fresh = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fresh.findViewById<Button>(R.id.btnReset).performClick()

        val after = MatrixConfig.load(fresh)
        assertEquals(MatrixConfig().theme, after.theme)
        assertEquals(MatrixConfig().speed, after.speed, 0.001f)
        assertTrue("clock preference was clobbered by reset", after.showClock)
        assertEquals(false, after.clock24h)
    }

    @Test
    fun startButtonLaunchesTheScreensaver() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnStart).performClick()

        val next = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(
            ScreensaverActivity::class.java.name,
            next.component?.className
        )
    }

    @Test
    fun screensaverActivityStartsAndShowsAnExitHint() {
        val controller = Robolectric.buildActivity(ScreensaverActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView as android.view.ViewGroup

        assertTrue(collect(root, MatrixSurfaceView::class.java).isNotEmpty())
        val hint = collect(root, TextView::class.java)
            .firstOrNull { it.text == activity.getString(R.string.exit_hint) }
        assertNotNull("no exit hint shown", hint)

        controller.pause().resume().destroy()
    }

    @Test
    fun daydreamAndWallpaperServicesAreDeclared() {
        val pm = org.robolectric.RuntimeEnvironment.getApplication().packageManager
        val info = pm.getPackageInfo(
            "com.matrixcode.rain",
            android.content.pm.PackageManager.GET_SERVICES
        )
        val names = info.services?.map { it.name }.orEmpty()
        assertTrue("dream service missing", names.any { it.endsWith("MatrixDreamService") })
        assertTrue("wallpaper service missing", names.any { it.endsWith("MatrixWallpaperService") })
    }

    @Test
    fun configSurvivesASaveLoadRoundTrip() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val cfg = MatrixConfig(
            theme = ColorTheme.PURPLE,
            glyphSet = GlyphSet.HEX,
            speed = 2.25f,
            glyphSize = 27f,
            density = 0.44f,
            trailLength = 1.6f,
            mutationRate = 0.7f,
            glow = 0.33f,
            scanlines = 0.9f,
            glitch = 0.11f,
            showClock = true,
            clock24h = false,
            keepScreenOn = false
        )
        cfg.save(ctx)
        assertEquals(cfg, MatrixConfig.load(ctx))
    }
}
