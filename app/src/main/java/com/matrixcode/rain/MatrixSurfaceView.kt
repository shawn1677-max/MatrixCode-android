package com.matrixcode.rain

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/** A [SurfaceView] that renders the Matrix rain. Use from an Activity or a preview. */
class MatrixSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val renderer = MatrixRenderer(resources.displayMetrics.density)
    private var loop: RenderLoop? = null
    private var config: MatrixConfig = MatrixConfig()
    private var surfaceReady = false
    private var resumed = true

    init {
        holder.addCallback(this)
    }

    fun setConfig(newConfig: MatrixConfig) {
        config = newConfig.copyOf()
        renderer.updateConfig(config)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderer.updateConfig(config)
        startLoopIfPossible()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
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
    }

    fun onPauseRendering() {
        resumed = false
        loop?.stopLoop()
        loop = null
    }

    fun release() {
        loop?.stopLoop()
        loop = null
        renderer.release()
    }
}
