package com.example.childmonitoringapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.childmonitoringapp.service.MonitoringService

class AccessibilityMonitorService : AccessibilityService() {

    private val TAG = "Accessibility"

    // App mục tiêu cần theo dõi
    private val targets = setOf(
        "com.zing.zalo",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.whatsapp"
    )

    // Overlay / launcher / keyboard / in-call UI / permission UI...
    private val overlayPackages = setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.google.android.permissioncontroller",
        // In-call / dialer UIs:
        "com.android.incallui",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.miui.dialer",
        "com.miui.voip"
    )

    // Grace 5s cho overlay & rời app mục tiêu
    private val GRACE_MS = 5_000L

    private enum class State { TARGET, OVERLAY, AWAY }

    private var state: State = State.AWAY
    private var currentPkg: String? = null
    private var currentTargetPkg: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var graceRunnable: Runnable? = null
    private var leftAt: Long = 0L // mốc "vừa rời target"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Nhiều ROM báo qua WINDOWS_CHANGED khi kéo notification
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == currentPkg) return
        currentPkg = pkg

        val isTarget = pkg in targets
        val isOverlay = pkg in overlayPackages
        Log.d(TAG, "Foreground: $pkg (target=$isTarget, overlay=$isOverlay), state=$state")

        when {
            // 1) Vào app mục tiêu → start (tiếp tục phiên)
            isTarget -> {
                cancelGrace()
                sendService(MonitoringService.ACTION_START_SCREEN, appTagOf(pkg))
                Log.d(TAG, "START_SCREEN (appTag=${appTagOf(pkg)})")
                currentTargetPkg = pkg
                state = State.TARGET
            }

            // 2) Overlay (kéo thông báo/keyboard/dialer...) trong khi đang theo dõi 1 target
            isOverlay && currentTargetPkg != null -> {
                if (state != State.OVERLAY) {
                    leftAt = SystemClock.uptimeMillis()
                    Log.d(TAG, "Enter OVERLAY, leftAt=$leftAt")
                }
                state = State.OVERLAY
                // Đặt hẹn 5s: nếu vẫn chưa quay lại target → dừng & FLUSH
                scheduleStopFlush("overlay")
                val since = SystemClock.uptimeMillis() - leftAt
                Log.d(TAG, "Overlay $pkg (${since}ms since left target) → waiting grace")
            }

            // 3) Không phải target & không phải overlay → rời app mục tiêu
            else -> {
                if (state != State.AWAY) {
                    leftAt = SystemClock.uptimeMillis()
                    Log.d(TAG, "Enter AWAY, leftAt=$leftAt")
                }
                state = State.AWAY
                // Đặt hẹn 5s: nếu không quay lại target → dừng & FLUSH
                scheduleStopFlush("away")
                Log.d(TAG, "AWAY → schedule STOP_FLUSH after ${GRACE_MS}ms")
            }
        }
    }

    private fun scheduleStopFlush(reason: String) {
        cancelGrace()
        graceRunnable = Runnable {
            // sau 5s vẫn không quay lại target → kết thúc phiên
            sendService(MonitoringService.ACTION_STOP_FLUSH, null)
            Log.d(TAG, "STOP_FLUSH fired by grace ($reason)")
            currentTargetPkg = null
            state = State.AWAY
        }
        handler.postDelayed(graceRunnable!!, GRACE_MS)
    }

    private fun cancelGrace() {
        graceRunnable?.let { handler.removeCallbacks(it) }
        graceRunnable = null
    }

    private fun sendService(action: String, appTag: String?) {
        val it = Intent(this, MonitoringService::class.java).apply {
            this.action = action
            appTag?.let { putExtra("appTag", it) }
        }
        ContextCompat.startForegroundService(this, it)
    }

    private fun hasProjection(): Boolean =
        getSharedPreferences("mprefs", MODE_PRIVATE).getBoolean("hasProjection", false)

    private fun appTagOf(pkg: String) = when (pkg) {
        "com.zing.zalo" -> "zalo"
        "com.facebook.orca" -> "messenger"
        "org.telegram.messenger" -> "telegram"
        "com.whatsapp" -> "whatsapp"
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "sms"
        else -> "unknown"
    }

    override fun onInterrupt() {}
    override fun onDestroy() { cancelGrace(); super.onDestroy() }
}
