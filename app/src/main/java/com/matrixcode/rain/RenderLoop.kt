package com.matrixcode.rain

import android.graphics.Canvas
import android.os.Build
import android.view.SurfaceHolder

/**
 * A plain render thread that drives a [MatrixRenderer] onto a [SurfaceHolder] at a
 * target frame rate. Shared by the fullscreen screensaver, the daydream, the live
 * wallpaper and the settings preview.
 */
class RenderLoop(
    private val holder: SurfaceHolder,
    private val renderer: MatrixRenderer,
    private val targetFps: Int = 60
) : Thread("MatrixRenderLoop") {

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private val pauseLock = Object()

    fun startLoop() {
        if (running) return
        running = true
        start()
    }

    fun stopLoop() {
        running = false
        resumeLoop()
        interrupt()
        try {
            join(1000)
        } catch (_: InterruptedException) {
            currentThread().interrupt()
        }
    }

    fun pauseLoop() {
        paused = true
    }

    fun resumeLoop() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    override fun run() {
        val frameNanos = 1_000_000_000L / targetFps
        var last = System.nanoTime()

        while (running) {
            if (paused) {
                synchronized(pauseLock) {
                    while (paused && running) {
                        try {
                            pauseLock.wait(200)
                        } catch (_: InterruptedException) {
                            currentThread().interrupt()
                            return
                        }
                    }
                }
                last = System.nanoTime()
                continue
            }

            val frameStart = System.nanoTime()
            val dt = (frameStart - last) / 1_000_000_000f
            last = frameStart

            renderer.update(dt)

            var canvas: Canvas? = null
            try {
                canvas = lockCanvas()
                if (canvas != null) renderer.draw(canvas)
            } catch (_: IllegalArgumentException) {
                // Surface went away mid-frame; the next loop pass will pick up the new one.
            } catch (_: IllegalStateException) {
                // Same: surface not ready or already destroyed.
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalArgumentException) {
                    } catch (_: IllegalStateException) {
                    }
                }
            }

            val spent = System.nanoTime() - frameStart
            val sleepNanos = frameNanos - spent
            if (sleepNanos > 0) {
                try {
                    sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun lockCanvas(): Canvas? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Hardware canvas keeps the per-glyph draw calls cheap on modern devices.
            holder.lockHardwareCanvas()
        } else {
            holder.lockCanvas()
        }
}
