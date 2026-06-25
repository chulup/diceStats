package xyz.chulup.dicestats

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class DiceStatsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the OpenCV native library bundled in the AAR (used for pip counting).
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed")
        }
    }

    private companion object {
        const val TAG = "DiceStatsApp"
    }
}
