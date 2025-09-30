// com/example/childmonitoringapp/net/ApiService.kt
package com.example.childmonitoringapp.net

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @Multipart
    @POST("api/Videos/upload")
    suspend fun uploadVideo(
        @Header("x-api-key") apiKey: String,      // ⬅️ THÊM
        @Part file: MultipartBody.Part,
        @Part("app") app: RequestBody,
        @Part("note") note: RequestBody?,
        @Part("ts") ts: RequestBody,
        @Part("durationSeconds") duration: RequestBody?
    ): Response<ResponseBody>
}
