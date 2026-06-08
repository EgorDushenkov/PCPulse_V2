package com.example.pc

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val gson: Gson, 
    private val token: String? = null,
    private val onStatusChanged: ((Boolean) -> Unit)? = null,
    private val onAuthFailed: (() -> Unit)? = null,
    private val onStatsReceived: (PCStats) -> Unit
) {

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
        // Append token as query parameter for authentication
        val authenticatedUrl = if (!token.isNullOrEmpty()) {
            val separator = if (url.contains("?")) "&" else "?"
            "$url${separator}token=$token"
        } else {
            url
        }
        
        val request = Request.Builder().url(authenticatedUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("WebSocket", "Connected to $url")
                onStatusChanged?.invoke(true)
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
                
                // Code 4001 means unauthorized — token is invalid
                if (code == 4001) {
                    Log.w("WebSocket", "Auth failed (4001), not reconnecting")
                    onStatusChanged?.invoke(false)
                    handler.post { onAuthFailed?.invoke() }
                    return
                }
                
                onStatusChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("WebSocket", "Failure: ${t.message}")
                onStatusChanged?.invoke(false)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("WebSocket", "Closed: $code / $reason")
                
                // Code 4001 means unauthorized — don't reconnect
                if (code == 4001) {
                    Log.w("WebSocket", "Auth failed (4001), not reconnecting")
                    handler.post { onAuthFailed?.invoke() }
                    return
                }
                
                onStatusChanged?.invoke(false)
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
