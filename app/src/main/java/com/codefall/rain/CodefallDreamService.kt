package com.codefall.rain

import android.os.Build
import android.service.dreams.DreamService

/**
 * Registers the rain as a real Android daydream, so the system can start it while the
 * device is docked or charging. Available on API 17+; on older devices the manifest
 * entry is simply never resolved.
 */
class CodefallDreamService : DreamService() {

    private var surface: RainSurfaceView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            isScreenBright = true
        }

        val view = RainSurfaceView(this)
        view.setConfig(RainConfig.load(this))
        surface = view
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        surface?.setConfig(RainConfig.load(this))
        surface?.onResumeRendering()
    }

    override fun onDreamingStopped() {
        surface?.onPauseRendering()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        surface?.release()
        surface = null
        super.onDetachedFromWindow()
    }
}
