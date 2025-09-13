package com.example.childmonitoringapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.childmonitoringapp.service.MonitoringService // Thêm import này

class AccessibilityMonitorService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            Log.d("Accessibility", "Foreground app: $packageName")
            if (packageName != null && setOf(
                    "com.zalo.zalo",
                    "com.facebook.orca",
                    "org.telegram.messenger",
                    "com.google.android.apps.messaging",
                    "com.whatsapp"
                ).contains(packageName)
            ) {
                val intent = Intent(this, MonitoringService::class.java)
                intent.putExtra("startRecording", true)
                startService(intent)
            } else {
                val intent = Intent(this, MonitoringService::class.java)
                intent.putExtra("stopRecording", true)
                startService(intent)
            }
        }
    }

    override fun onInterrupt() {
        Log.d("Accessibility", "Service interrupted")
    }
}