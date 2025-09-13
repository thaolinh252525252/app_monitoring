package com.example.childmonitoringapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.childmonitoringapp.service.MonitoringService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MonitoringService::class.java)
            context.startService(serviceIntent)
        }
    }
}