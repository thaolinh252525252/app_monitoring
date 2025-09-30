package com.example.childmonitoringapp.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.childmonitoringapp.service.MonitoringService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            // Khi máy khởi động xong (sau khi mở khóa)
            Intent.ACTION_BOOT_COMPLETED,
                // Khi máy khởi động nhưng còn ở trạng thái locked (Direct Boot) — Android 7.0+
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
                // Khi app được cập nhật/cài lại
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Dựng lại service nền (service tự startForeground trong onCreate)
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, MonitoringService::class.java)
                )
            }
        }
    }
}
