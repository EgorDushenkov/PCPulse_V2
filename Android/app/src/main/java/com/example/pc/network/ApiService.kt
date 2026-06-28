package com.example.pc.network

import com.example.pc.*
import com.example.pc.data.*
import com.example.pc.ui.*
import com.example.pc.ui.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {
    @GET("/screenshot")
    fun getScreenshot(): Call<ResponseBody>
    
    @POST("/auth/pair")
    fun pair(@retrofit2.http.Body request: PairRequest): Call<PairResponse>

    @GET("/fs/list")
    fun listFs(@Query("path") path: String?): Call<List<FsItem>>

    @POST("/fs/copy")
    fun copyFs(@retrofit2.http.Body request: FsCopyRequest): Call<FsCopyResponse>

    @Streaming
    @GET("/fs/download")
    fun downloadFs(@Query("path") path: String): Call<ResponseBody>

    @Multipart
    @POST("/fs/upload")
    fun uploadFs(
        @Query("path") path: String,
        @Part file: MultipartBody.Part
    ): Call<ResponseBody>
}

data class FsItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val date: Long
)

data class FsCopyRequest(
    val sources: List<String>,
    val destination: String
)

data class FsCopyResponse(
    val status: String
)

data class PairRequest(val pin: String)
data class PairResponse(val token: String)
