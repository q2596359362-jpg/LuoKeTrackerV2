package com.luoke.tracker

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class App : Application() {
    companion object {
        private const val TAG = "LuoKeTracker"
        var openCVLoaded = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
    }

    private fun initOpenCV() {
        val success = OpenCVLoader.initDebug()
        if (success) {
            openCVLoaded = true
            Log.d(TAG, "OpenCV initialized successfully")
        } else {
            openCVLoaded = false
            Log.e(TAG, "OpenCV initialization failed")
        }
    }
}