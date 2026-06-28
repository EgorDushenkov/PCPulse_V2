package com.example.pc.data

import com.example.pc.network.ApiService
import com.example.pc.network.PairRequest
import com.example.pc.network.RetrofitClient

interface PCRepository {
    fun pairDevice(ip: String, pin: String, onSuccess: (String) -> Unit, onError: (String) -> Unit)
}

class PCRepositoryImpl : PCRepository {
    override fun pairDevice(ip: String, pin: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val client = RetrofitClient.getClient(ip)
        client.pair(PairRequest(pin)).enqueue(object : retrofit2.Callback<com.example.pc.network.PairResponse> {
            override fun onResponse(call: retrofit2.Call<com.example.pc.network.PairResponse>, response: retrofit2.Response<com.example.pc.network.PairResponse>) {
                if (response.isSuccessful) {
                    val token = response.body()?.token
                    if (token != null) {
                        onSuccess(token)
                    } else {
                        onError("Token is null")
                    }
                } else if (response.code() == 401) {
                    onError("Wrong PIN")
                } else {
                    onError("Server error: ${response.code()}")
                }
            }

            override fun onFailure(call: retrofit2.Call<com.example.pc.network.PairResponse>, t: Throwable) {
                onError("Failed to connect: ${t.message}")
            }
        })
    }
}
