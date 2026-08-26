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

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            renderer.updateConfig(MatrixConfig.load(this@MatrixWallpaperService))
            prefs = MatrixConfig.prefs(this@MatrixWallpaperService).also {
                it.registerOnSharedPreferenceChangeListener(this)
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            renderer.updateConfig(MatrixConfig.load(this@MatrixWallpaperService))
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
                renderer.updateConfig(MatrixConfig.load(this@MatrixWallpaperService))
                startLoop()
            } else {
                stopLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
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
