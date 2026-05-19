package com.example.pc

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(private val gson: Gson, private val onStatsReceived: (PCStats) -> Unit) {

    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentUrl: String? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect(currentUrl ?: "") }

    fun connect(url: String) {
        currentUrl = url
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("WebSocket", "Connected to $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val stats = gson.fromJson(text, PCStats::class.java)
                    handler.post { onStatsReceived(stats) }
                } catch (e: Exception) {
                    Log.e("WebSocket", "Error parsing stats", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected = false
                Log.d("WebSocket", "Closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("WebSocket", "Failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("WebSocket", "Closed: $code / $reason")
            }
        })
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 5000)
    }

    fun sendCommand(action: String, params: Map<String, Any?> = emptyMap()) {
        if (!isConnected) return
        val command = mutableMapOf<String, Any?>("action" to action)
        command.putAll(params)
        val json = gson.toJson(command)
        webSocket?.send(json)
    }

    fun disconnect() {
        handler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "App closed")
        isConnected = false
    }
}
