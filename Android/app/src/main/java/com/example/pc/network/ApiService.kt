package com.example.pc.network

import com.example.pc.*
import com.example.pc.data.*
import com.example.pc.network.*
import com.example.pc.ui.*

import okhttp3.ResponseBody
import com.example.pc.ui.*

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @GET("/screenshot")
    fun getScreenshot(): Call<ResponseBody>
    
    @POST("/auth/pair")
    fun pair(@retrofit2.http.Body request: PairRequest): Call<PairResponse>
}

data class PairRequest(val pin: String)
data class PairResponse(val token: String)
