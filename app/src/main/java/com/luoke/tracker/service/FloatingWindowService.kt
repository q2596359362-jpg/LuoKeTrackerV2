package com.luoke.tracker.service

import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import com.luoke.tracker.util.ConfigManager

class FloatingWindowService : Service() {
    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "map_tracker_overlay"
        private const val NOTIFICATION_ID = 2
    }

    private lateinit var windowManager: WindowManager
    private var rootView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var screenDensity: Float = 1f

    private var expandedContainer: LinearLayout? = null
    private var miniContainer: FrameLayout? = null
    private var mapImageView: ImageView? = null
    private var positionText: TextView? = null

    private var fullMapBitmap: Bitmap? = null
    private var isExpanded = true

    private var currentX = 0.0
    private var currentY = 0.0
    private var currentConfidence = 0f
    private var currentRotation = 0.0

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var lastClickTime = 0L

    private val matchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_MATCH_RESULT) {
                currentX = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_X, 0.0)
                currentY = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_Y, 0.0)
                currentConfidence = intent.getFloatExtra(ScreenCaptureService.EXTRA_CONFIDENCE, 0f)
                currentRotation = intent.getDoubleExtra(ScreenCaptureService.EXTRA_ROTATION, 0.0)
                updateOverlay()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("悬浮窗已启动"))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm: DisplayMetrics = applicationContext.resources.displayMetrics
        screenDensity = dm.density

        try {
            val filter = IntentFilter(ScreenCaptureService.ACTION_MATCH_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(matchReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(matchReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }

        fullMapBitmap = loadMapSync()
        isExpanded = ConfigManager.isFloatingExpanded(this)

        createViews()
        showCurrentState()
    }

    private fun loadMapSync(): Bitmap? {
        val path = ConfigManager.getMapFilePath(this)
        if (path.isNotEmpty()) {
            return BitmapFactory.decodeFile(path)
        }
        return null
    }

    private fun dpToPx(dp: Int): Int = (dp.toFloat() * screenDensity).toInt()

    private fun createViews() {
        val expandedSize = dpToPx(280)
        val miniSize = dpToPx(60)
        val headerH = dpToPx(32)
        val footerH = dpToPx(28)
        val pad4 = dpToPx(4)
        val pad8 = dpToPx(8)

        rootView = FrameLayout(this)

        // 展开视图
        expandedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 15, 15, 30))
            setPadding(pad4, pad4, pad4, pad4)
        }

        // 标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(pad8, 0, pad4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, headerH
            )
        }
        header.addView(TextView(this).apply {
            text = "地图追踪"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
            setOnClickListener { collapse() }
        })

        // 地图视图
        mapImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(expandedSize, expandedSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(20, 20, 40))
        }

        // 底部信息
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(pad8, pad4, pad8, pad4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, footerH
            )
        }
        positionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            text = "等待定位..."
        }
        footer.addView(positionText)

        expandedContainer!!.addView(header)
        expandedContainer!!.addView(mapImageView)
        expandedContainer!!.addView(footer)

        // 迷你视图
        miniContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(210, 15, 15, 35))
            setPadding(pad4, pad4, pad4, pad4)
        }
        miniContainer!!.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setColorFilter(Color.WHITE)
            alpha = 0.9f
            layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = Gravity.CENTER
            }
        })
        miniContainer!!.addView(View(this).apply {
            setBackgroundColor(Color.RED)
            tag = "dot"
            layoutParams = FrameLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = pad4
                rightMargin = pad4
            }
        })
        miniContainer!!.addView(TextView(this).apply {
            setTextColor(Color.rgb(180, 200, 255))
            textSize = 7f
            gravity = Gravity.CENTER
            text = "..."
            tag = "coord"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = dpToPx(2) }
        })

        rootView!!.addView(expandedContainer)
        rootView!!.addView(miniContainer)

        rootParams = WindowManager.LayoutParams(
            expandedSize + dpToPx(8),
            expandedSize + headerH + footerH + dpToPx(8),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(8)
            y = dpToPx(80)
        }

        setupTouch()
    }

    private fun setupTouch() {
        rootView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = rootParams?.x ?: 0
                    dragStartY = rootParams?.y ?: 0
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 300) {
                        toggleExpand()
                        lastClickTime = 0
                        return@setOnTouchListener true
                    }
                    lastClickTime = now
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isDragging = true
                        lastClickTime = 0
                    }
                    if (isDragging) {
                        rootParams?.let {
                            it.x = dragStartX + dx.toInt()
                            it.y = dragStartY + dy.toInt()
                            try { windowManager.updateViewLayout(view, it) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showCurrentState() {
        if (isExpanded) showExpanded() else showMini()
    }

    private fun showExpanded() {
        val expandedSize = dpToPx(280)
        val headerH = dpToPx(32)
        val footerH = dpToPx(28)

        expandedContainer?.visibility = View.VISIBLE
        miniContainer?.visibility = View.GONE

        rootParams?.width = expandedSize + dpToPx(8)
        rootParams?.height = expandedSize + headerH + footerH + dpToPx(8)

        try { rootView?.let { windowManager.updateViewLayout(it, rootParams) } } catch (_: Exception) {}
        isExpanded = true
        ConfigManager.setFloatingExpanded(this, true)

        if (rootView?.parent == null) {
            try { windowManager.addView(rootView, rootParams) } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
            }
        }
        updateOverlay()
    }

    private fun showMini() {
        val miniSize = dpToPx(60)

        expandedContainer?.visibility = View.GONE
        miniContainer?.visibility = View.VISIBLE

        rootParams?.width = miniSize
        rootParams?.height = miniSize

        try { rootView?.let { windowManager.updateViewLayout(it, rootParams) } } catch (_: Exception) {}
        isExpanded = false
        ConfigManager.setFloatingExpanded(this, false)

        if (rootView?.parent == null) {
            try { windowManager.addView(rootView, rootParams) } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
            }
        }
        updateMiniView()
    }

    private fun expand() = showExpanded()
    private fun collapse() = showMini()
    private fun toggleExpand() { if (isExpanded) collapse() else expand() }

    private fun updateOverlay() {
        if (isExpanded) updateExpandedView() else updateMiniView()
    }

    private fun updateExpandedView() {
        val map = fullMapBitmap ?: run {
            positionText?.text = "未加载地图"
            return
        }

        val viewSize = dpToPx(280)
        val cx = currentX.toInt().coerceIn(viewSize / 2, map.width - viewSize / 2)
        val cy = currentY.toInt().coerceIn(viewSize / 2, map.height - viewSize / 2)
        val srcX = (cx - viewSize / 2).coerceAtLeast(0)
        val srcY = (cy - viewSize / 2).coerceAtLeast(0)
        val cropW = viewSize.coerceAtMost(map.width - srcX)
        val cropH = viewSize.coerceAtMost(map.height - srcY)
        if (cropW <= 0 || cropH <= 0) return

        val cropped = try {
            val region = Bitmap.createBitmap(map, srcX, srcY, cropW, cropH)
            val copy = region.copy(Bitmap.Config.ARGB_8888, true)
            region.recycle()
            copy
        } catch (e: Exception) {
            Log.e(TAG, "crop failed", e)
            return
        }

        val canvas = Canvas(cropped)
        drawPlayerMarker(canvas, currentX - srcX, currentY - srcY)

        mapImageView?.setImageBitmap(cropped)
        positionText?.text = "(${currentX.toInt()}, ${currentY.toInt()}) ${(currentConfidence * 100).toInt()}%"
    }

    private fun updateMiniView() {
        miniContainer?.findViewWithTag<TextView>("coord")?.text =
            "(${currentX.toInt()},${currentY.toInt()})"
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            currentConfidence > 0.1f -> Color.RED
            else -> Color.GRAY
        }
        miniContainer?.findViewWithTag<View>("dot")?.setBackgroundColor(color)
    }

    private fun drawPlayerMarker(canvas: Canvas, mx: Double, my: Double) {
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            else -> Color.RED
        }

        val mxf = mx.toFloat()
        val myf = my.toFloat()

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 60 }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 220 }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        canvas.drawCircle(mxf, myf, 24f, glowPaint)
        canvas.drawCircle(mxf, myf, 8f, fillPaint)
        canvas.drawCircle(mxf, myf, 8f, borderPaint)

        if (currentConfidence > 0.3f) {
            val rad = Math.toRadians(currentRotation)
            val len = 28f
            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(
                mxf, myf,
                mxf + (len * kotlin.math.sin(rad)).toFloat(),
                myf - (len * kotlin.math.cos(rad)).toFloat(),
                arrowPaint
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "悬浮窗地图",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "地图悬浮窗"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setContentTitle("洛克王国地图追踪")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(matchReceiver) } catch (_: Exception) {}
        rootView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        fullMapBitmap?.recycle()
    }
}