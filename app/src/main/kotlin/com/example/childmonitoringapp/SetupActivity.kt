package com.example.childmonitoringapp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.childmonitoringapp.service.MonitoringService
import java.io.File

class SetupActivity : AppCompatActivity() {

    private val TAG = "SetupActivity"
    private val REQ_PERMS = 100
    private val REQ_CAPTURE = 101

    private lateinit var mpm: MediaProjectionManager

    // Poll kiểm tra trợ năng đã bật chưa
    private val accHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val accPoll = object : Runnable {
        override fun run() {
            if (isAccessibilityEnabled()) {
                Toast.makeText(this@SetupActivity, "Đã bật Trợ năng cho ứng dụng", Toast.LENGTH_SHORT).show()
                accHandler.removeCallbacks(this)
            } else {
                accHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Nút xin quyền (mic + phone state + screen capture)
        findViewById<Button>(R.id.btn_setup).setOnClickListener {
            checkAndRequestPermissions()
        }

        // Nút mở trang Trợ năng
        findViewById<Button>(R.id.btn_open_accessibility).setOnClickListener {
            openAccessibilitySettings()
            startAccPoll()
        }

        // Ghi probe để bạn test adb ls
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        dir.mkdirs()
        File(dir, "probe.txt").writeText("ok ${System.currentTimeMillis()}")
        Log.i("SRCHECK", "probe wrote: ${dir.absolutePath}")
    }

    private fun checkAndRequestPermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_PERMS) {
            if (res.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Vui lòng cấp đủ quyền để tiếp tục", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestScreenCapture() {
        try {
            val intent = mpm.createScreenCaptureIntent()
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "requestScreenCapture error: ${e.message}")
            Toast.makeText(this, "Lỗi khi yêu cầu quay màn hình", Toast.LENGTH_LONG).show()
        }
    }

    // 👉 GỬI TOKEN CHO SERVICE, để Service GIỮ MediaProjection
    override fun onActivityResult(req: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(req, resultCode, data)
        if (req == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Bơm projection vào Service
                val svc = Intent(this, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_SET_PROJECTION
                    putExtra("resultCode", resultCode)
                    putExtra("projectionData", data)
                }
                androidx.core.content.ContextCompat.startForegroundService(this, svc)

                Toast.makeText(
                    this,
                    "Đã xin quyền quay màn hình. Bật Trợ năng để bắt đầu theo app.",
                    Toast.LENGTH_LONG
                ).show()

                // Mở trang Trợ năng ngay cho người dùng bật
                openAccessibilitySettings()
                startAccPoll() // (nếu bạn đã có hàm poll này)
            } else {
                Toast.makeText(this, "Bạn đã từ chối quyền quay màn hình", Toast.LENGTH_LONG).show()
            }
        }
    }


    // Mở trang chi tiết nếu ROM hỗ trợ, không thì fallback trang Trợ năng chung
    private fun openAccessibilitySettings() {
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (detail.resolveActivity(packageManager) != null) {
                startActivity(detail)
            } else {
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "openAccessibilitySettings error: ${e.message}")
            Toast.makeText(this, "Không mở được Cài đặt Trợ năng", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val me = ComponentName(this, AccessibilityMonitorService::class.java).flattenToString()
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return enabled.split(':').any { it.equals(me, ignoreCase = true) } && am.isEnabled
    }

    private fun startAccPoll() {
        accHandler.removeCallbacks(accPoll)
        accHandler.postDelayed(accPoll, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        accHandler.removeCallbacks(accPoll)
    }
}
