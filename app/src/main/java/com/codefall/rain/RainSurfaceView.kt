package com.codefall.rain

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/** A [SurfaceView] that renders the falling-code rain. Use from an Activity or a preview. */
class RainSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val renderer = RainRenderer(resources.displayMetrics.density)
    private var loop: RenderLoop? = null
    private var config: RainConfig = RainConfig()
    private var surfaceReady = false
    private var resumed = true
    private val tilt = TiltSource(context) { renderer.setTilt(it) }

    init {
        holder.addCallback(this)
    }

    fun setConfig(newConfig: RainConfig) {
        config = newConfig.copyOf()
        renderer.updateConfig(config)
        syncTilt()
    }

    private fun syncTilt() {
        if (resumed && surfaceReady && config.tiltEnabled) tilt.start() else tilt.stop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderer.updateConfig(config)
        startLoopIfPossible()
        syncTilt()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        tilt.stop()
        loop?.stopLoop()
        loop = null
    }

    private fun startLoopIfPossible() {
        if (!surfaceReady || !resumed || loop != null) return
        loop = RenderLoop(holder, renderer).also { it.startLoop() }
    }

    fun onResumeRendering() {
        resumed = true
        startLoopIfPossible()
        loop?.resumeLoop()
        syncTilt()
    }

    fun onPauseRendering() {
        resumed = false
        tilt.stop()
        loop?.stopLoop()
        loop = null
    }

    fun release() {
        tilt.stop()
        loop?.stopLoop()
        loop = null
        renderer.release()
    }
}
