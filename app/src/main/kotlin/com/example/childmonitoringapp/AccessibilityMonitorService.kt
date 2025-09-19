package com.example.childmonitoringapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.childmonitoringapp.service.MonitoringService

class AccessibilityMonitorService : AccessibilityService() {

    private val TAG = "Accessibility"

    private val targets = setOf(
        "com.zing.zalo",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.whatsapp"
    )

    // Bỏ qua overlay/IME/System UI
    private val ignorePkgs = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.google.android.ext.services",
        "com.android.permissioncontroller",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home"
    )

    private var currentTargetPkg: String? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopGraceRunnable: Runnable? = null
    private val stopGraceMs = 2500L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""

        // Bỏ qua bàn phím/overlay
        if (shouldIgnoreOverlay(pkg, cls)) {
            Log.d(TAG, "Ignore overlay: $pkg / $cls")
            return
        }

        val isTarget = pkg in targets
        Log.d(TAG, "Foreground: $pkg (isTarget=$isTarget)")

        if (isTarget) {
            cancelStopWithGrace()

            if (!hasProjection()) {
                Log.w(TAG, "Missing projection; launching SetupActivity to re-consent")
                startActivity(Intent(this, SetupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            }

            when {
                currentTargetPkg == null -> {
                    sendAction(MonitoringService.ACTION_START_SCREEN)
                    Log.d(TAG, "START_SCREEN sent")
                }
                currentTargetPkg != pkg -> {
                    sendAction(MonitoringService.ACTION_SPLIT_SCREEN)
                    Log.d(TAG, "SPLIT_SCREEN sent (${currentTargetPkg} -> $pkg)")
                }
                else -> {
                    // vẫn cùng target → không làm gì
                }
            }
            currentTargetPkg = pkg
        } else {
            // Rời target: chỉ lên lịch STOP; KHÔNG đổi currentTargetPkg ở đây
            if (currentTargetPkg != null) scheduleStopWithGrace()
        }
    }

    private fun shouldIgnoreOverlay(pkg: String, cls: String): Boolean {
        if (pkg in ignorePkgs) return true
        val s = (pkg + ":" + cls).lowercase()
        return s.contains("inputmethod") || s.contains("keyboard")
    }

    private fun scheduleStopWithGrace() {
        cancelStopWithGrace()
        stopGraceRunnable = Runnable {
            sendAction(MonitoringService.ACTION_STOP_SCREEN)
            Log.d(TAG, "STOP_SCREEN sent by grace")
            currentTargetPkg = null // hết quay, reset target
        }
        handler.postDelayed(stopGraceRunnable!!, stopGraceMs)
    }

    private fun cancelStopWithGrace() {
        stopGraceRunnable?.let { handler.removeCallbacks(it) }
        stopGraceRunnable = null
    }

    private fun sendAction(action: String) {
        val it = Intent(this, MonitoringService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, it)
    }

    private fun hasProjection(): Boolean =
        getSharedPreferences("mprefs", MODE_PRIVATE).getBoolean("hasProjection", false)

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); cancelStopWithGrace() }
}

