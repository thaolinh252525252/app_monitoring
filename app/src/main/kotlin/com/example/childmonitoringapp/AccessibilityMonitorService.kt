package com.example.childmonitoringapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
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
    //ngat
    // Thời gian chờ trước khi STOP (mặc định dài hơn để tránh cắt sớm)
    private val GRACE_MS = 15_000L              // 15 giây
    private val INTERLUDE_GRACE_MS = 12_000L    // cho các màn chen ngang

    // Các package chen ngang hay gặp khi vẫn còn ở trong app chat
    private val interludes = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.documentsui", "com.google.android.documentsui",
        "com.miui.securitycenter", "com.miui.gallery",
        "com.google.android.apps.photos",
        "com.android.camera", "com.sec.android.app.camera"
    )

    // Launcher/Home: rời app thật sự → có thể STOP nhanh hơn nếu muốn
    private val launchers = setOf(
        "com.miui.home",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher"
    )

    // Trạng thái
    private lateinit var am: AudioManager
    private var currentPkg: String? = null
    private var currentTargetPkg: String? = null
    private var voipActive = false

    // Grace cho STOP (screen &/or voip)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var graceRunnable: Runnable? = null
    private val graceMs = 2500L

    override fun onServiceConnected() {
        super.onServiceConnected()
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == currentPkg) return
        currentPkg = pkg

        val isTarget = pkg in targets
        Log.d(TAG, "Foreground: $pkg (isTarget=$isTarget)")

        if (isTarget) {
            cancelGrace()

            // 1) Nếu thiếu projection → mở SetupActivity để xin lại
            if (!hasProjection()) {
                Log.w(TAG, "Missing projection; launching SetupActivity to re-consent")
                startActivity(
                    Intent(this, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                currentTargetPkg = pkg
                return
            }

            // 2) START_SCREEN (kèm appTag), luôn dùng foreground service
            val appTag = toAppTag(pkg)
            val startIt = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_SCREEN
                putExtra("appTag", appTag)
            }
            ContextCompat.startForegroundService(this, startIt)
            Log.d(TAG, "START_SCREEN sent (appTag=$appTag)")
            currentTargetPkg = pkg

        } else {
            // Không phải target: nhưng nếu chỉ là màn chen ngang thì cho nới thời gian
            val inInterlude = currentTargetPkg != null && pkg in interludes
            val inLauncher  = pkg in launchers

            val delay = when {
                inLauncher -> 3_000L                 // về Home → dừng nhanh (3s)
                inInterlude -> INTERLUDE_GRACE_MS    // chen ngang → chờ lâu hơn
                else -> GRACE_MS                     // bình thường
            }
            scheduleGrace(delay)
        }
    }

    override fun onInterrupt() {}

    // ===== Helpers =====

    private fun scheduleGrace(delayMs: Long) {
        cancelGrace()
        graceRunnable = Runnable {
            startService(Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_STOP_SCREEN
            })
            Log.d(TAG, "STOP_SCREEN sent by grace ($delayMs ms)")
            currentTargetPkg = null
        }
        handler.postDelayed(graceRunnable!!, delayMs)
    }


    private fun cancelGrace() {
        graceRunnable?.let { handler.removeCallbacks(it) }
        graceRunnable = null
    }

    private fun hasProjection(): Boolean =
        getSharedPreferences("mprefs", MODE_PRIVATE).getBoolean("hasProjection", false)

    private fun toAppTag(pkg: String) = when (pkg) {
        "com.zing.zalo" -> "zalo"
        "com.facebook.orca" -> "messenger"
        "org.telegram.messenger" -> "telegram"
        "com.whatsapp" -> "whatsapp"
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "sms"
        else -> pkg
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelGrace()
    }
}
