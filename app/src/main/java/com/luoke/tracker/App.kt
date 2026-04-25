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
            Log.d(TAG, "OpenCV 初始化成功: ${OpenCVLoader.getOpenCVVersion()}")
        } else {
            openCVLoaded = false
            Log.e(TAG, "OpenCV 初始化失败，将尝试异步加载")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_4_9_0, this)
        }
    }
}