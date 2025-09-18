package com.example.childmonitoringapp.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.childmonitoringapp.SetupActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Minh bạch: mở lại SetupActivity để người dùng cấp lại MediaProjection sau reboot
            val i = Intent(context, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}
