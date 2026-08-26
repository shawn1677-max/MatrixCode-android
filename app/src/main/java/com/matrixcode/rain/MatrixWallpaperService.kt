package com.matrixcode.rain

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/** Same rain, running as a live wallpaper. Picks up settings changes immediately. */
class MatrixWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MatrixEngine()

    private inner class MatrixEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val renderer = MatrixRenderer(resources.displayMetrics.density)
        private var loop: RenderLoop? = null
        private var prefs: SharedPreferences? = null
        private val tilt =
            TiltSource(this@MatrixWallpaperService) { renderer.setTilt(it) }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            renderer.updateConfig(MatrixConfig.load(this@MatrixWallpaperService))
            prefs = MatrixConfig.prefs(this@MatrixWallpaperService).also {
                it.registerOnSharedPreferenceChangeListener(this)
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            val cfg = MatrixConfig.load(this@MatrixWallpaperService)
            renderer.updateConfig(cfg)
            if (isVisible && cfg.tiltEnabled) tilt.start() else if (!cfg.tiltEnabled) tilt.stop()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.resize(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                val cfg = MatrixConfig.load(this@MatrixWallpaperService)
                renderer.updateConfig(cfg)
                if (cfg.tiltEnabled) tilt.start()
                startLoop()
            } else {
                tilt.stop()
                stopLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            tilt.stop()
            stopLoop()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            renderer.release()
            super.onDestroy()
        }

        private fun startLoop() {
            if (loop != null) return
            // Wallpapers idle behind other apps; 40fps is plenty and easier on the battery.
            loop = RenderLoop(surfaceHolder, renderer, targetFps = 40).also { it.startLoop() }
        }

        private fun stopLoop() {
            loop?.stopLoop()
            loop = null
        }
    }
}
