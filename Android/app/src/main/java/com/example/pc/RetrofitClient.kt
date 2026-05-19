package com.example.pc

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val clients = mutableMapOf<String, ApiService>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequestsPerHost = 10
        })
        .build()

    fun getClient(ip: String): ApiService {
        val cleanIp = ip.trim()
        val baseUrl = if (cleanIp.startsWith("http")) {
            if (cleanIp.endsWith("/")) cleanIp else "$cleanIp/"
        } else {
            "http://$cleanIp:5000/"
        }

        return clients.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}