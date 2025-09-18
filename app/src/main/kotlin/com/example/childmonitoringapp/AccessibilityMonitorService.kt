package com.example.childmonitoringapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.childmonitoringapp.service.MonitoringService
import android.app.Activity

class AccessibilityMonitorService : AccessibilityService() {
    private val TAG = "Accessibility"
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val targets = setOf(
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "com.zing.zalo",
            "com.facebook.orca",
            "org.telegram.messenger"
        )

        if (pkg in targets) {
            val app = application as App
            if (app.resultCode == Activity.RESULT_OK && app.projectionData != null) {
                val i = Intent(this, MonitoringService::class.java).apply {
                    action = "START_SCREEN"
                    putExtra("resultCode", app.resultCode)
                    putExtra("projectionData", app.projectionData)
                    putExtra("sourcePackage", pkg)
                }
                startService(i) // OK với normal Service (MonitoringService)
            } else {
                Log.e("Accessibility", "Chưa có projectionData (chạy SetupActivity trước)")
            }
        } else {
            val i = Intent(this, MonitoringService::class.java).apply {
                action = "STOP_SCREEN"
            }
            startService(i)
        }
    }

    override fun onInterrupt() {
        Log.d("Accessibility", "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Accessibility", "Accessibility service connected")
    }
}