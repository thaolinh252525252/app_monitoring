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
import java.io.File

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

    companion object {
        const val ACTION_SET_PROJECTION = "SET_PROJECTION"
        const val ACTION_START_SCREEN   = "START_SCREEN"
        const val ACTION_STOP_SCREEN    = "STOP_SCREEN"
        const val ACTION_SPLIT_SCREEN   = "SPLIT_SCREEN"

        const val ACTION_AUDIO_ON       = "AUDIO_ON"
        const val ACTION_AUDIO_OFF      = "AUDIO_OFF"

        // ✅ key lưu trạng thái ghi âm (true: có mic, false: mute)
        private const val KEY_RECORD_AUDIO = "record_audio"
    }
    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "onCreate()")
        // ✅ QUAN TRỌNG: reset cờ vì service có thể vừa bị hệ thống restart
        prefs.edit().putBoolean("hasProjection", false).apply()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private val MAX_SEGMENT_MS = 5 * 60 * 1000L  // 2 phút (test). Prod: 10 * 60 * 1000L
    private var rollTask: Runnable? = null

    private fun startRolling() {
        cancelRolling()
        rollTask = Runnable {
            if (isRecording) {
                // cắt sang file mới
                stopRecording()
                handler.postDelayed({ startScreenRecording() }, 200)
                startRolling()
            }
        }
        handler.postDelayed(rollTask!!, MAX_SEGMENT_MS)
    }

    private fun cancelRolling() {
        rollTask?.let { handler.removeCallbacks(it) }
        rollTask = null
    }
    private val prefs by lazy { getSharedPreferences("mprefs", Context.MODE_PRIVATE) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "onStartCommand(): act=${intent?.action}")
        when (intent?.action) {
            ACTION_SET_PROJECTION -> {
                val rc = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val pd = intent.getParcelableExtra<Intent>("projectionData")
                if (rc == Activity.RESULT_OK && pd != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(rc, pd)
                    // ✅ bật cờ: đã có projection
                    prefs.edit().putBoolean("hasProjection", true).apply()

                    // ✅ nếu hệ thống revoke projection → tắt cờ + dừng quay
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection revoked by system")
                            prefs.edit().putBoolean("hasProjection", false).apply()
                            if (isRecording) stopRecording()
                            mediaProjection = null
                        }
                    }, handler)

                    Log.w(TAG, "MediaProjection READY")
                } else {
                    Log.e(TAG, "SET_PROJECTION missing extras")
                }
            }

            ACTION_START_SCREEN -> {
                if (mediaProjection == null) {
                    Log.e(TAG, "No MediaProjection")
                    return START_STICKY
                }
                if (!isRecording) startScreenRecording()
            }

            ACTION_STOP_SCREEN -> {
                if (isRecording) stopRecording()
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
            ACTION_AUDIO_ON -> {
                prefs.edit().putBoolean(KEY_RECORD_AUDIO, true).apply()
                Log.d(TAG, "Screen audio = ON")
            }
            ACTION_AUDIO_OFF -> {
                prefs.edit().putBoolean(KEY_RECORD_AUDIO, false).apply()
                Log.d(TAG, "Screen audio = OFF")
            }
        }
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }


    // ❗Align bội 16 tránh 720x1527
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

        val includeAudio = prefs.getBoolean(KEY_RECORD_AUDIO, false) // mặc định: mute

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (includeAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
            }

            setVideoSize(width, height)
            setVideoFrameRate(20)
            setVideoEncodingBitRate(2_500_000)
            setOutputFile(outFile.absolutePath)

            try {
                prepare()
                recorderSurface = this.surface
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorderSurface, null, null
                ) ?: throw IllegalStateException("VirtualDisplay null")

                start()
                isRecording = true
                Log.d(TAG, "Screen recording started (${width}x$height @20fps, audio=$includeAudio)")
                startRolling() // nếu bạn giữ tính năng cắt theo thời gian
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
                releaseRecorder()
                outFile.delete()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()

        prefs.edit().putBoolean("hasProjection", false).apply() // ✅ clear cờ
        stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
    }

//    private fun startScreenRecording() {
//        if (mediaProjection == null || isRecording) return
//        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
//        if (!moviesDir.exists()) moviesDir.mkdirs()
//
//        val outFile = File(moviesDir, "screen_record_${System.currentTimeMillis()}.mp4")
//        Log.d(TAG, "Saving to: ${outFile.absolutePath}")
//
//        val metrics = resources.displayMetrics
//        val width = metrics.widthPixels
//        val height = metrics.heightPixels
//        val densityDpi = metrics.densityDpi
//
//        mediaRecorder = MediaRecorder().apply {
//            setVideoSource(MediaRecorder.VideoSource.SURFACE)
//            setAudioSource(MediaRecorder.AudioSource.MIC)
//            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
//            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
//            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
//            setVideoSize(width, height)
//            setVideoFrameRate(30)
//            setVideoEncodingBitRate(5_000_000)
//            setOutputFile(outFile.absolutePath)
//            try {
//                prepare()
//                recorderSurface = this.surface
//                virtualDisplay = mediaProjection?.createVirtualDisplay(
//                    "ScreenCapture",
//                    width, height, densityDpi,
//                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
//                    recorderSurface, null, null          // ✅ dùng recorderSurface
//                ) ?: throw IllegalStateException("VirtualDisplay null")
//
//                start()
//                isRecording = true
//                Log.d(TAG, "Screen recording started (${width}x$height @30fps)")
//                startRolling()                          // ✅ bắt đầu rolling theo thời gian
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to start recording: ${e.message}", e)
//                releaseRecorder()
//                outFile.delete()
//            }
//        }
//    }

    private fun stopRecording() {
        cancelRolling()                                // ✅ dừng rolling
        mediaRecorder?.let {
            try { it.stop(); it.reset() } catch (e: Exception) { Log.e(TAG, "stop err: ${e.message}") }
            finally { it.release() }
        }
        mediaRecorder = null
        virtualDisplay?.release(); virtualDisplay = null
        recorderSurface?.release(); recorderSurface = null
        isRecording = false
        Log.d(TAG, "Recording stopped & resources released")
    }

//    private fun stopRecording() {
//        mediaRecorder?.let {
//            try {
//                it.stop()
//                it.reset()
//            } catch (e: Exception) {
//                Log.e(TAG, "Error stopping recorder: ${e.message}")
//            } finally {
//                it.release()
//            }
//        }
//        mediaRecorder = null
//        virtualDisplay?.release()
//        virtualDisplay = null
//        recorderSurface?.release()
//        recorderSurface = null
//        isRecording = false
//        Log.d(TAG, "Recording stopped & resources released")
//    }

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (_: Throwable) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { recorderSurface?.release() } catch (_: Throwable) {}
        recorderSurface = null
    }

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
            .setSmallIcon(R.mipmap.ic_launcher) // đổi icon nếu cần
            .setContentTitle("Đang chạy nền")
            .setContentText("Quay màn hình khi ứng dụng chat ở nền trước")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        handler.removeCallbacksAndMessages(null)
//        stopRecording()
//        mediaProjection?.stop()
//        mediaProjection = null
//    }

    override fun onBind(intent: Intent?): IBinder? = null
}
