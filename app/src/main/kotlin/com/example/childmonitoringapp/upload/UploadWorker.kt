package com.example.childmonitoringapp.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.childmonitoringapp.BuildConfig
import com.example.childmonitoringapp.R
import com.example.childmonitoringapp.net.ApiClient
import com.example.childmonitoringapp.net.ProgressRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

import okhttp3.MediaType.Companion.toMediaType



class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private var lastPct = -1
    private var lastTs = 0L

    companion object {
        private const val CH_ID = "UploadChannel"
        private const val NOTI_ID = 4441

        fun enqueue(context: Context, path: String, app: String, note: String?, durationSec: Int?) {
            val data = workDataOf(
                "path" to path,
                "app" to app,
                "note" to (note ?: ""),
                "duration" to (durationSec ?: -1)
            )
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString("path") ?: return@withContext Result.failure()
        val app  = inputData.getString("app") ?: "unknown"
        val note = inputData.getString("note").takeUnless { it.isNullOrBlank() }
        val duration = inputData.getInt("duration", -1).takeIf { it > 0 }

        // Tạo channel + vào FG đúng chuẩn (gọi 1 lần)
        ensureChannel()
        setForeground(createForegroundInfo("Đang tải lên..."))

        val file = File(path)
        if (!file.exists() || file.length() <= 0) return@withContext Result.failure()

        android.util.Log.d("UPLOAD", "file=${file.absolutePath} size=${file.length()}")
        if (!file.exists() || file.length() <= 0) return@withContext Result.failure()

        if (BuildConfig.API_KEY.isBlank()) {
            android.util.Log.e("UPLOAD", "API_KEY trống → không thể upload")
            return@withContext Result.failure()
        }

        val contentType = if (file.extension.equals("m4a", true)) "audio/mp4" else "video/mp4"
        val body = ProgressRequestBody(file, contentType) { sent, total ->
            // throttle: chỉ cập nhật khi tăng ≥5% hoặc ≥500ms/lần
            val pct = if (total > 0) ((100L * sent) / total).toInt() else 0
            val now = System.currentTimeMillis()
            if (pct >= (lastPct + 5) || now - lastTs >= 500) {
                lastPct = pct; lastTs = now
                setProgressAsync(workDataOf("progress" to pct))
                setForegroundAsync(createForegroundInfo("Tải lên: $pct%"))
            }
        }
        val part = MultipartBody.Part.createFormData("file", file.name, body)

        val textPlain = "text/plain".toMediaType()
        val rbApp = app.toRequestBody(textPlain)

        val resp = ApiClient.api.uploadVideo(
            file = part,
            app = app.toRequestBody(textPlain),
            note = note?.toRequestBody(textPlain),
            ts = Instant.now().toString().toRequestBody(textPlain),
            duration = duration?.toString()?.toRequestBody(textPlain)
        )


        if (resp.isSuccessful) {
            val txt = resp.body()?.string() // OK vì body là ResponseBody
            android.util.Log.i("UPLOAD", "success code=${resp.code()} body=$txt")
            runCatching { file.delete() }
            Result.success()
        } else {
            val err = resp.errorBody()?.string()
            android.util.Log.e("UPLOAD", "fail code=${resp.code()} body=$err")
            if (resp.code() in 500..599) Result.retry() else Result.failure()
        }


    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Uploads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // dùng icon hệ thống sẵn có
            .setContentTitle("Upload clip")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTI_ID, n)
    }

    // Cập nhật tiến độ bằng notify() — không suspend, gọi được từ callback
    private fun updateProgressNotification(percent: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(applicationContext, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Upload clip")
            .setContentText("Tải lên: $percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
        nm.notify(NOTI_ID, n)
    }
}
