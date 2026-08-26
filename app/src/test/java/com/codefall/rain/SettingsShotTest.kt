package com.codefall.rain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Captures the settings screen for the store listing.
 *
 * The layout is drawn by the real Android view code, so this is the app's actual
 * UI rather than a mockup. The one thing that cannot draw itself here is the
 * SurfaceView preview — a SurfaceView renders through a separate surface and
 * leaves a hole in the view hierarchy's own draw pass — so the rain is rendered
 * separately and composited into exactly the rect the SurfaceView occupies,
 * which is what a device shows there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class SettingsShotTest {

    private val w = 1080
    private val h = 1920
    private val density = 3f
    private val outDir = File("build/store")

    private fun <T : View> find(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                find(root.getChildAt(i), type)?.let { return it }
            }
        }
        return null
    }

    /** Position of [view] relative to [root], after layout. */
    private fun offsetIn(root: View, view: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var v: View? = view
        while (v != null && v !== root) {
            x += v.left
            y += v.top
            v = v.parent as? View
        }
        return x to y
    }

    /** Collects every view of a type, in tree order. */
    private fun <T : View> collect(root: View, type: Class<T>, out: MutableList<T>) {
        if (type.isInstance(root)) out.add(type.cast(root)!!)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collect(root.getChildAt(i), type, out)
        }
    }

    /**
     * Scrolls so the named section heading sits at the top of the list. Picking a
     * pixel offset by hand lands mid-row and slices a label in half.
     */
    private fun scrollToSection(decor: View, heading: String) {
        val scroll = find(decor, ScrollView::class.java) ?: return
        val labels = mutableListOf<TextView>()
        collect(scroll, TextView::class.java, labels)
        val header = labels.firstOrNull { it.text?.toString() == heading } ?: return
        val (_, y) = offsetIn(scroll, header)
        // A little breathing room above the heading rather than flush to the edge.
        scroll.scrollTo(0, (y - 12 * density).toInt().coerceAtLeast(0))
    }

    private fun capture(name: String, section: String?) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, w, h)

        if (section != null) scrollToSection(decor, section)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        decor.draw(canvas)

        // Composite the rain into the preview panel's rect.
        val surface = find(decor, RainSurfaceView::class.java)
        if (surface != null && surface.width > 0 && surface.height > 0) {
            val (sx, sy) = offsetIn(decor, surface)
            val renderer = RainRenderer(displayDensity = density)
            renderer.updateConfig(RainConfig.load(activity))
            renderer.resize(surface.width, surface.height)
            val rain = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            val rc = Canvas(rain)
            repeat(150) { renderer.update(1f / 60f); renderer.draw(rc) }
            canvas.drawBitmap(rain, sx.toFloat(), sy.toFloat(), null)
            renderer.release()

            // The "LIVE PREVIEW" label sits above the surface, so put it back on top.
            val frame = surface.parent as? ViewGroup
            val label = frame?.let { find(it, TextView::class.java) }
            if (label != null) {
                val (lx, ly) = offsetIn(decor, label)
                val save = canvas.save()
                canvas.translate(lx.toFloat(), ly.toFloat())
                label.draw(canvas)
                canvas.restoreToCount(save)
            }
        }

        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("SHOT $name surface=${surface?.width}x${surface?.height}")
    }

    @Test
    fun captureSettingsScreens() {
        // Give the sliders interesting values rather than bare defaults.
        RainConfig(
            theme = ColorTheme.CLASSIC_GREEN,
            glyphSize = 16f,
            speed = 1.35f,
            density = 0.9f,
            trailLength = 1.2f,
            glow = 0.7f,
            scanlines = 0.3f,
            vignette = 0.45f,
            aberration = 0.35f,
            showClock = true,
            message = "WAKE UP, NEO"
        ).save(org.robolectric.RuntimeEnvironment.getApplication())

        capture("screen-6-settings", section = null)
        capture("screen-7-settings-crt", section = "CRT SCREEN")
    }
}
