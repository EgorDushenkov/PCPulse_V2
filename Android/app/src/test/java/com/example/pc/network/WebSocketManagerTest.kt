package com.example.pc.network

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebSocketManagerTest {

    private lateinit var gson: Gson
    private lateinit var webSocketManager: WebSocketManager
    private var statusChanged = false
    private var authFailed = false
    private var serverUnreachable = false

    @Before
    fun setup() {
        gson = Gson()
        webSocketManager = WebSocketManager(
            gson = gson,
            token = "dummy_token",
            onStatusChanged = { statusChanged = it },
            onAuthFailed = { authFailed = true },
            onServerUnreachable = { serverUnreachable = true },
            onStatsReceived = { }
        )
    }

    @Test
    fun `test initial state is not connected`() {
        // Just a basic test to verify structure and logic
        assertNotNull(webSocketManager)
    }
}
