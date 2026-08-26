package com.matrixcode.rain

import android.os.Build
import android.service.dreams.DreamService

/**
 * Registers the rain as a real Android daydream, so the system can start it while the
 * device is docked or charging. Available on API 17+; on older devices the manifest
 * entry is simply never resolved.
 */
class MatrixDreamService : DreamService() {

    private var surface: MatrixSurfaceView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            isScreenBright = true
        }

        val view = MatrixSurfaceView(this)
        view.setConfig(MatrixConfig.load(this))
        surface = view
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        surface?.setConfig(MatrixConfig.load(this))
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
