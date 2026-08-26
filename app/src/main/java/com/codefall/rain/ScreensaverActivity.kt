package com.codefall.rain

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.widget.TextView

/** Fullscreen, immersive rain. Tap anywhere (or press back) to leave. */
class ScreensaverActivity : Activity() {

    private lateinit var surface: RainSurfaceView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { fadeOutHint() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = RainConfig.load(this)
        if (config.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surface = RainSurfaceView(this)
        surface.setConfig(config)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hint = TextView(this).apply {
            text = getString(R.string.exit_hint)
            setTextColor(0xFF00FF41.toInt())
            textSize = 12f
            alpha = 0.7f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val hintLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hintLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        hintLp.bottomMargin = (48 * resources.displayMetrics.density).toInt()
        root.addView(hint, hintLp)

        root.setOnClickListener { finish() }
        root.isClickable = true

        setContentView(root)
        handler.postDelayed(hideHint, 3000)
    }

    private fun fadeOutHint() {
        ObjectAnimator.ofFloat(hint, View.ALPHA, hint.alpha, 0f).apply {
            duration = 900
            start()
        }
    }

    private fun goImmersive() {
        // Targeting SDK 35+ means edge-to-edge is enforced and the old
        // systemUiVisibility flags are ignored, so drive the bars through the
        // androidx controller instead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        // Settings may have changed while we were away.
        surface.setConfig(RainConfig.load(this))
        surface.onResumeRendering()
    }

    override fun onPause() {
        super.onPause()
        surface.onPauseRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(hideHint)
        surface.release()
    }
}
