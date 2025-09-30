package com.example.childmonitoringapp.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.childmonitoringapp.Secrets
import com.example.childmonitoringapp.R
import com.example.childmonitoringapp.net.ApiClient
import com.example.childmonitoringapp.net.ProgressRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
//import com.arthenica.ffmpegkit.FFmpegKit
//import com.arthenica.ffmpegkit.ReturnCode

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private var lastPct = -1
    private var lastTs = 0L

    companion object {
        private const val CH_ID = "UploadChannel"
        private const val NOTI_ID = 4441
        private const val UNIQUE_QUEUE = "uploads-queue"
        fun enqueue(context: Context, path: String, app: String, note: String?, durationSec: Int?) {
            val data = workDataOf(
                "path" to path,
                "app" to app,
                "note" to (note ?: ""),
                "duration" to (durationSec ?: -1)
            )
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_QUEUE,
                ExistingWorkPolicy.APPEND,
                req
            )
        }
    }

//    private suspend fun splitMp4ByDurationCopy(
//        input: File,
//        maxBytes: Long
//    ): List<File>? = withContext(Dispatchers.IO) {
//        try {
//            // Lấy duration (ms)
//            val mmr = android.media.MediaMetadataRetriever()
//            mmr.setDataSource(input.absolutePath)
//            val durMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
//            mmr.release()
//
//            if (durMs <= 0) return@withContext null
//
//            val bitrateBps = (input.length() * 8.0) / (durMs / 1000.0) // ~bps
//            val targetSec = ((maxBytes * 8.0) / bitrateBps * 0.95).toInt().coerceIn(60, 900) // 1–15 phút
//            val outDir = input.parentFile!!
//            val base = input.nameWithoutExtension + "_part_%03d.mp4"
//            val outPattern = File(outDir, base).absolutePath
//
//            // FFmpeg command: copy streams, cut by time
//            val cmd = arrayOf(
//                "-y",
//                "-i", input.absolutePath,
//                "-c", "copy",
//                "-map", "0",
//                "-f", "segment",
//                "-segment_time", targetSec.toString(),
//                "-reset_timestamps", "1",
//                outPattern
//            )
//
//            val ses = FFmpegKit.execute(cmd.joinToString(" "))
//            if (!ReturnCode.isSuccess(ses.returnCode)) return@withContext null
//
//            // Thu thập output parts
//            val parts = outDir.listFiles { f ->
//                f.isFile && f.name.matches(Regex(Regex.escape(input.nameWithoutExtension) + "_part_\\d{3}\\.mp4"))
//            }?.sortedBy { it.name } ?: emptyList()
//
//            if (parts.isEmpty()) null else parts
//        } catch (_: Throwable) { null }
//    }


    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString("path") ?: return@withContext Result.failure()
        val app  = inputData.getString("app") ?: "unknown"
        val note = inputData.getString("note").takeUnless { it.isNullOrBlank() }
        val duration = inputData.getInt("duration", -1).takeIf { it > 0 }

//        ensureChannel()
//        setForeground(createForegroundInfo("Đang tải lên..."))

        val file = File(path)
        if (!file.exists() || file.length() <= 0) return@withContext Result.failure()

        // ⬇️ KHÓA NGƯỠNG THEO SERVER (controller đang 300 MB)
        val MAX_SERVER_BYTES = 300L * 1024 * 1024
        if (file.length() > MAX_SERVER_BYTES) {
            // File cũ lỡ quá cỡ: chuyển sang thư mục failed để không tốn mạng
            val failedDir = File(
                applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                "failed"
            ).apply { mkdirs() }
            file.copyTo(File(failedDir, file.name + ".too_large"), overwrite = true)
            file.delete()
            android.util.Log.e("UPLOAD", "Skip too large: ${file.length()} bytes")
            return@withContext Result.failure()
        }

        // MIME theo đuôi
        val contentType = when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else  -> "application/octet-stream"
        }

        // Progress body
        val body = ProgressRequestBody(file, contentType) { sent, total ->
            // UploadWorker.kt – trong lambda ProgressRequestBody(...)
            val pct = if (total > 0) ((100L * sent) / total).toInt() else 0
            val now = System.currentTimeMillis()
            if (pct >= lastPct + 10 || now - lastTs >= 1500) {  // trước là +5 và 500ms
                lastPct = pct; lastTs = now
                setProgressAsync(workDataOf("progress" to pct))
//                setForegroundAsync(createForegroundInfo("Tải lên: $pct%"))
            }

        }
        val part = MultipartBody.Part.createFormData("file", file.name, body)

        val textPlain = "text/plain".toMediaType()
        val rbApp: RequestBody = app.toRequestBody(textPlain)
        val rbNote: RequestBody? = note?.toRequestBody(textPlain)
        val rbTs: RequestBody = Instant.now().toString().toRequestBody(textPlain)
        val rbDur: RequestBody? = duration?.toString()?.toRequestBody(textPlain)

        val resp = ApiClient.api.uploadVideo(
            apiKey = Secrets.API_KEY,   // nhớ trùng với appsettings.json (ApiKey)
            file = part,
            app = rbApp,
            note = rbNote,
            ts = rbTs,
            duration = rbDur
        )

        if (resp.isSuccessful) {

            android.util.Log.i("UPLOAD", "success code=${resp.code()}")
            runCatching { file.delete() }
            Result.success()
        } else {

            android.util.Log.e("UPLOAD", "fail code=${resp.code()} ")
            if (resp.code() in 500..599) Result.retry() else Result.failure()
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) == null) {
            val ch = NotificationChannel(CH_ID, "Uploads", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
    }


    private fun createForegroundInfo(text: String): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Upload clip")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTI_ID, n)
    }
}
