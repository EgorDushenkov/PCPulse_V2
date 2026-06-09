package com.example.pc

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val clients = mutableMapOf<String, ApiService>()

    fun getClient(ip: String, token: String? = null): ApiService {
        val cleanIp = ip.trim()
        val cacheKey = "$cleanIp|${token ?: ""}"
        val baseUrl = if (cleanIp.startsWith("http")) {
            if (cleanIp.endsWith("/")) cleanIp else "$cleanIp/"
        } else {
            "http://$cleanIp:5000/"
        }

        return clients.getOrPut(cacheKey) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .dispatcher(Dispatcher().apply {
                    maxRequestsPerHost = 10
                })
            
            if (!token.isNullOrEmpty()) {
                builder.addInterceptor(Interceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(request)
                })
            }

            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(builder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}