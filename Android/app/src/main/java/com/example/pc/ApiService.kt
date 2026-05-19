package com.example.pc

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @GET("/screenshot")
    fun getScreenshot(): Call<ResponseBody>
}
