package com.example.pc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.*
import java.io.IOException

class WidgetClickReceiver : BroadcastReceiver() {
    private val client = OkHttpClient()

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("WIDGET_TYPE")
        val ip = intent.getStringExtra("DEVICE_IP")
        val action = intent.getStringExtra("ACTION")
        val value = intent.getIntExtra("VALUE", -1)

        Log.d("WidgetClick", "Click received: type=$type, ip=$ip, action=$action")

        if (ip == null) return

        when (type) {
            WidgetType.ACTION_BUTTON.name -> {
                if (action != null) {
                    sendCommand(ip, "run", "path" to action)
                }
            }
            WidgetType.CONTROLS.name -> {
                when (action) {
                    "screenshot" -> sendCommand(ip, "screenshot")
                    "mic_mute" -> sendCommand(ip, "mute_mic")
                    "sleep" -> sendCommand(ip, "sleep")
                    "shutdown" -> sendCommand(ip, "shutdown")
                }
            }
            WidgetType.MEDIA_PLAYER.name -> {
                if (action != null) {
                    sendCommand(ip, "media", "command" to action)
                }
            }
        }
    }

    private fun sendCommand(ip: String, endpoint: String, vararg params: Pair<String, String>) {
        val urlBuilder = HttpUrl.Builder()
            .scheme("http")
            .host(ip)
            .port(5000)
            .addPathSegment(endpoint)
        
        params.forEach { urlBuilder.addQueryParameter(it.first, it.second) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("WidgetClick", "Failed to send command $endpoint", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}