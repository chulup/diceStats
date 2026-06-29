package xyz.chulup.dicestats

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core

@HiltAndroidApp
class DiceStatsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the OpenCV native library (bytedeco/JavaCPP) used for pip counting. JavaCPP
        // extracts the bundled .so to a cache dir; point it at the app's writable cache.
        System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.absolutePath)
        runCatching { Loader.load(opencv_core::class.java) }
            .onFailure { Log.e(TAG, "OpenCV initialization failed", it) }
    }

    private companion object {
        const val TAG = "DiceStatsApp"
    }
}
