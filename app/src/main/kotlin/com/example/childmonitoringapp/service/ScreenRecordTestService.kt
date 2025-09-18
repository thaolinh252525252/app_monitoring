package com.example.childmonitoringapp

import android.app.*
import android.content.*
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Environment
import java.io.File

class ScreenRecordTestService : Service() {
    override fun onBind(intent: Intent?) = null

    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private val TAG = "SRTEST"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNotif())

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "no projection data")
            stopSelf(); return START_NOT_STICKY
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)

        val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        outDir.mkdirs()
        val outFile = File(outDir, "last_record.mp4")
        Log.i(TAG, "Output: ${outFile.absolutePath}")

        val dm = resources.displayMetrics
        val densityDpi = dm.densityDpi

        recorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(720, 1280)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5_000_000)
            setOutputFile(outFile.absolutePath)
            try { Log.i(TAG,"prepare()"); prepare() } catch(e:Exception){ Log.e(TAG,"prepare fail",e); stopSelf(); return START_NOT_STICKY }
        }

        Log.i(TAG,"createVirtualDisplay($densityDpi dpi)")
        vDisplay = projection!!.createVirtualDisplay(
            "SRTest", 720, 1280, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder!!.surface, null, null
        )
        if (vDisplay == null) { Log.e(TAG,"vDisplay null"); stopSelf(); return START_NOT_STICKY }

        try { recorder!!.start(); Log.i(TAG,"Recording started") }
        catch(e:Exception){ Log.e(TAG,"start fail", e); stopSelf(); return START_NOT_STICKY }

        Handler(Looper.getMainLooper()).postDelayed({
            try { recorder?.stop(); Log.i(TAG,"Recording stopped") }
            catch(e:Exception){ Log.e(TAG,"stop fail",e) }
            finally {
                recorder?.release(); vDisplay?.release(); projection?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                Log.i(TAG,"Saved to: ${outFile.absolutePath}")
            }
        }, 5000)

        return START_NOT_STICKY
    }

    private fun buildNotif(): Notification {
        val chId = "sr_test"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(chId,"SR Test",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("SR test").build()
    }
}
