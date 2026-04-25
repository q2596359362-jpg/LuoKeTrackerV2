package com.luoke.tracker

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.luoke.tracker.databinding.ActivityMainBinding
import com.luoke.tracker.service.FloatingWindowService
import com.luoke.tracker.service.ScreenCaptureService
import com.luoke.tracker.util.ConfigManager
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isTracking = false

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestProjection()
        } else {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
            startFloatingWindow()
            setTrackingState(true)
        } else {
            Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleMapFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        loadSavedConfig()

        // 检查 OpenCV 加载状态
        if (!App.openCVLoaded) {
            binding.tvStatus.text = "⚠ OpenCV 加载中，请稍后重试"
            binding.tvStatus.setTextColor(0xFFFF9800.toInt())
        }
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } else {
                requestProjection()
            }
        }

        binding.btnStop.setOnClickListener {
            stopAll()
            setTrackingState(false)
        }

        binding.btnSelectMap.setOnClickListener {
            filePickerLauncher.launch("image/*")
        }

        binding.sbX.setOnSeekBarChangeListener(calibrationListener)
        binding.sbY.setOnSeekBarChangeListener(calibrationListener)
        binding.sbW.setOnSeekBarChangeListener(calibrationListener)
        binding.sbH.setOnSeekBarChangeListener(calibrationListener)
    }

    private val calibrationListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            saveCalibration()
            updateCalInfo()
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun loadSavedConfig() {
        val rect = ConfigManager.getMinimapRect(this)
        binding.sbX.progress = rect[0]
        binding.sbY.progress = rect[1]
        binding.sbW.progress = rect[2]
        binding.sbH.progress = rect[3]
        updateCalInfo()

        val mapPath = ConfigManager.getMapFilePath(this)
        if (mapPath.isNotEmpty()) {
            val file = File(mapPath)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(mapPath, opts)
                binding.tvMapInfo.text = "地图: ${opts.outWidth}x${opts.outHeight}"
            }
        }

        setTrackingState(false)
    }

    private fun requestProjection() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAll() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, FloatingWindowService::class.java))
    }

    private fun setTrackingState(running: Boolean) {
        isTracking = running
        if (running) {
            binding.tvStatus.text = "运行中"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
        } else {
            binding.tvStatus.text = "已停止"
            binding.tvStatus.setTextColor(0xFFF44336.toInt())
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
        }
    }

    private fun handleMapFileSelected(uri: Uri) {
        try {
            val destFile = File(getExternalFilesDir(null), "map_full.png")
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            ConfigManager.setMapFilePath(this, destFile.absolutePath)

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            binding.tvMapInfo.text = "地图: ${opts.outWidth}x${opts.outHeight}"
            Toast.makeText(this, "地图已加载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCalibration() {
        ConfigManager.setMinimapRect(
            this,
            binding.sbX.progress,
            binding.sbY.progress,
            binding.sbW.progress.coerceAtLeast(50),
            binding.sbH.progress.coerceAtLeast(50)
        )
    }

    private fun updateCalInfo() {
        binding.tvCalInfo.text = "小地图区域: (${binding.sbX.progress}, ${binding.sbY.progress}) " +
            "${binding.sbW.progress.coerceAtLeast(50)}x${binding.sbH.progress.coerceAtLeast(50)}"
    }
}