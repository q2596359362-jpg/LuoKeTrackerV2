package com.luoke.tracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.luoke.tracker.App
import com.luoke.tracker.cv.MapMatcher
import com.luoke.tracker.util.ConfigManager
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {
    companion object {
        const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "map_tracker_capture"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val MATCH_INTERVAL_MS = 200L

        const val ACTION_MATCH_RESULT = "com.luoke.tracker.MATCH_RESULT"
        const val EXTRA_POS_X = "pos_x"
        const val EXTRA_POS_Y = "pos_y"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_ROTATION = "rotation"
        const val ACTION_STATUS = "com.luoke.tracker.STATUS"
        const val EXTRA_STATUS_MSG = "status_msg"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val matcher = MapMatcher()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var isCapturing = false
    private var mapLoaded = false

    private var lastKnownX = 0.0
    private var lastKnownY = 0.0
    private var consecutiveFails = 0

    private var mapBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
        fun loadMap(bitmap: Bitmap) {
            this@ScreenCaptureService.loadMapFromBitmap(bitmap)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("正在初始化..."))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(projectionCallback, null)

            setupCapture()
            loadMap()
            startLoop()
        }

        return START_NOT_STICKY
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LuoKeTracker",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        Log.d(TAG, "屏幕捕获启动: ${screenWidth}x${screenHeight}")
        broadcastStatus("屏幕捕获已启动")
    }

    fun loadMapFromBitmap(bitmap: Bitmap) {
        mapBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        matcher.loadFullMap(bitmap)
        mapLoaded = true
        showNotification("地图已加载: ${bitmap.width}x${bitmap.height}")
        broadcastStatus("地图加载成功: ${bitmap.width}x${bitmap.height}")
    }

    private fun loadMap() {
        serviceScope.launch {
            try {
                val path = ConfigManager.getMapFilePath(this@ScreenCaptureService)
                if (path.isNotEmpty()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        loadMapFromBitmap(bitmap)
                    } else {
                        broadcastStatus("地图加载失败: 文件无效")
                    }
                } else {
                    broadcastStatus("请先导入地图文件")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载地图异常", e)
                broadcastStatus("地图加载失败: ${e.message}")
            }
        }
    }

    private fun startLoop() {
        if (!App.openCVLoaded) {
            broadcastStatus("OpenCV 未正确加载，请重启应用")
            return
        }

        isCapturing = true
        captureJob = serviceScope.launch {
            while (isActive && isCapturing) {
                if (!mapLoaded) {
                    delay(1000)
                    continue
                }

                val frame = captureFrame()
                if (frame != null) {
                    processFrame(frame)
                }

                delay(MATCH_INTERVAL_MS)
            }
        }
    }

    private fun processFrame(frame: Bitmap) {
        try {
            val result = matcher.match(frame)
            frame.recycle()

            if (result != null && result.confidence >= ConfigManager.getConfidenceThreshold(this)) {
                lastKnownX = result.x
                lastKnownY = result.y
                consecutiveFails = 0
                broadcastResult(result)
                showNotification("📍 (${result.x.toInt()}, ${result.y.toInt()}) ${(result.confidence * 100).toInt()}%")
            } else {
                handleMatchFail()
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理帧异常", e)
            frame.recycle()
        }
    }

    private fun handleMatchFail() {
        consecutiveFails++
        when (consecutiveFails) {
            in 1..2 -> broadcastResult(MapMatcher.MatchResult(lastKnownX, lastKnownY, 0.1f, 0.0))
            in 3..7 -> showNotification("位置暂时丢失... ($consecutiveFails)")
            else -> if (consecutiveFails % 10 == 0) {
                showNotification("持续丢失，等待恢复...")
                broadcastStatus("位置丢失中，可能需要调整小地图校准")
            }
        }
    }

    private fun captureFrame(): Bitmap? {
        val image: Image = imageReader?.acquireLatestImage() ?: return null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val rect = ConfigManager.getMinimapRect(this)
            val x = rect[0].coerceIn(0, fullBitmap.width - 1)
            val y = rect[1].coerceIn(0, fullBitmap.height - 1)
            val w = rect[2].coerceAtMost(fullBitmap.width - x)
            val h = rect[3].coerceAtMost(fullBitmap.height - y)

            if (w <= 0 || h <= 0) {
                fullBitmap.recycle()
                return null
            }

            val miniMap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()
            return miniMap
        } catch (e: Exception) {
            Log.e(TAG, "截取失败", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun broadcastResult(result: MapMatcher.MatchResult) {
        sendBroadcast(Intent(ACTION_MATCH_RESULT).apply {
            putExtra(EXTRA_POS_X, result.x)
            putExtra(EXTRA_POS_Y, result.y)
            putExtra(EXTRA_CONFIDENCE, result.confidence)
            putExtra(EXTRA_ROTATION, result.rotation)
        })
    }

    private fun broadcastStatus(msg: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
        })
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection 停止")
            stopCapture()
        }
    }

    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        matcher.release()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "地图追踪",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "游戏地图实时追踪"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("洛克王国地图追踪")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()
    }

    private fun showNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        serviceScope.cancel()
        mapBitmap?.recycle()
    }
}