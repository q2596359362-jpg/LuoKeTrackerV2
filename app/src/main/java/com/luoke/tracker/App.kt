package com.luoke.tracker

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class App : Application() {
    companion object {
        private const val TAG = "LuoKeTracker"
        var openCVLoaded = false
            private set
        var loadError = ""
            private set
    }

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
    }

    private fun initOpenCV() {
        try {
            val success = OpenCVLoader.initDebug()
            if (success) {
                openCVLoaded = true
                Log.d(TAG, "OpenCV initialized successfully")
            } else {
                loadError = "OpenCVLoader.initDebug() returned false"
                Log.e(TAG, loadError)
            }
        } catch (e: Exception) {
            loadError = e.javaClass.simpleName.toString() + ": " + e.message
            Log.e(TAG, "OpenCV load failed", e)
        }
    }
}
