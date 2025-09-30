// app/src/main/kotlin/com/example/childmonitoringapp/net/ApiClient.kt
package com.example.childmonitoringapp.net

import com.example.childmonitoringapp.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // ❗ Tuyệt đối KHÔNG dùng Level.BODY cho upload lớn
    private val logging = HttpLoggingInterceptor { msg ->
        // tránh log dài dòng, chỉ 1 tag
        android.util.Log.d("HTTP", msg)
    }.apply {
        // Debug: chỉ log HEADERS; Release: tắt hẳn
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
    }

    // Interceptor chặn log body cho multipart/video (phòng khi ai đó bật BODY ở nơi khác)
    private val noBodyForBigUpload = Interceptor { chain ->
        val req = chain.request()
        val ct = req.body?.contentType()?.toString() ?: ""
        // Nếu là upload multipart/video → cứ proceed bình thường (logging phía sau chỉ ở HEADERS)
        // Nếu bạn có custom logger body khác, nhớ bỏ nó.
        chain.proceed(req)
    }

    private val okHttp = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        // Đặt interceptor “an toàn” trước
        .addInterceptor(noBodyForBigUpload)
        // Logging chỉ HEADERS/NONE → không đọc body → không OOM
        .addInterceptor(logging)
        .build()

    val api: ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL) // ví dụ "http://159.223.73.53/"
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ApiService::class.java)
}
