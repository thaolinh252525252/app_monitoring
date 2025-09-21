// ProgressRequestBody.kt
package com.example.childmonitoringapp.net

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream

class ProgressRequestBody(
    private val file: File,
    private val contentType: String,
    private val onProgress: ((sent: Long, total: Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = contentType.toMediaTypeOrNull()
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            var sent = 0L
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                sink.write(buf, 0, read)
                sent += read
                onProgress?.invoke(sent, total)
            }
        }
    }
}
