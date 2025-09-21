package com.example.childmonitoringapp.net

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Header
import retrofit2.http.Part
import okhttp3.ResponseBody
data class UploadResponse(val id: String?)

interface ApiService {
    @Multipart
    @POST("api/Videos/upload")
    suspend fun uploadVideo(
        @Part file: MultipartBody.Part,
        @Part("app") app: RequestBody,
        @Part("note") note: RequestBody?,
        @Part("ts") ts: RequestBody,
        @Part("durationSeconds") duration: RequestBody?
    ): Response<ResponseBody>

}