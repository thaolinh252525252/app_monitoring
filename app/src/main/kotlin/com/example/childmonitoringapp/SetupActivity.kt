package com.example.childmonitoringapp

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.childmonitoringapp.service.MonitoringService
import android.os.Environment
class SetupActivity : AppCompatActivity() {
    private val REQUEST_PERMISSIONS = 100
    private val REQUEST_SCREEN_CAPTURE = 101
    private val REQUEST_DEVICE_ADMIN = 102
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val TAG = "SetupActivity"
    private var isServiceStarted = false // Thêm biến kiểm tra trạng thái service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val btnSetup = findViewById<Button>(R.id.btn_setup)
        if (btnSetup != null) {
            btnSetup.setOnClickListener {
                if (!isServiceStarted) {
                    checkAndRequestPermissions()
                } else {
                    Log.w(TAG, "Service already started, skipping")
                    Toast.makeText(this, "Dịch vụ đã khởi động", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.e(TAG, "btn_setup not found in layout: R.layout.activity_setup")
            Toast.makeText(this, "Lỗi giao diện, kiểm tra layout", Toast.LENGTH_LONG).show()
        }
        val btnOpenAccessibility = findViewById<Button>(R.id.btn_open_accessibility)
        btnOpenAccessibility.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được Cài đặt Trợ năng", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error opening accessibility settings: ${e.message}")
            }
        }

        // Xóa dòng checkAndRequestPermissions() tự động để tránh gọi lặp
        // Ghi 1 file text vào thư mục Movies của app để test quyền ghi
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        dir.mkdirs()
        val probe = java.io.File(dir, "probe.txt")
        probe.writeText("ok ${System.currentTimeMillis()}")
        Log.i("SRCHECK", "probe wrote: ${probe.absolutePath} size=${probe.length()}")

    }

    private fun checkAndRequestPermissions() {
        val basePermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val permissionsToRequest = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestScreenCapture()
            } else {
                Log.w(TAG, "Some permissions denied: ${permissions.joinToString()}")
                Toast.makeText(this, "Vui lòng cấp tất cả quyền để tiếp tục", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun requestScreenCapture() {
        try {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(intent, REQUEST_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting screen capture: ${e.message}")
            Toast.makeText(this, "Lỗi khi yêu cầu quay màn hình", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDeviceAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@SetupActivity, AdminReceiver::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ứng dụng cần quyền quản trị để chạy ngầm"
                )
            }
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting device admin: ${e.message}")
            Toast.makeText(this, "Lỗi cấu hình quản trị", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val app = application as App
                app.resultCode = resultCode
                app.projectionData = data

                Log.d(TAG, "Saved projection: resultCode=$resultCode, data=$data")
                Toast.makeText(
                    this,
                    "Đã xin quyền quay màn hình. Vào Cài đặt > Trợ năng bật \"ChildMonitoringApp\" để bật ghi theo app.",
                    Toast.LENGTH_LONG
                ).show()

                // ❌ KHÔNG start MonitoringService ở đây
                // ❌ KHÔNG start AccessibilityService (Android tự quản lý khi người dùng bật)
                isServiceStarted = true
            } else {
                Log.e(TAG, "Screen capture denied or data null: resultCode=$resultCode")
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Device admin permission granted")
            } else {
                Log.w(TAG, "Device admin permission denied")
                Toast.makeText(this, "Quyền quản trị bị từ chối", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
    }

}