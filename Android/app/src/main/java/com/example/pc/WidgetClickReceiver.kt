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
        
        Log.d("WidgetClick", "Click received: type=$type, ip=$ip, action=$action")

        if (ip == null) return

        // Handle generic media action from notification or single button
        if (intent.action == "com.example.pc.MEDIA_ACTION") {
            if (action != null) sendToService(context, ip, "media", action)
            return
        }

        when (type) {
            WidgetType.ACTION_BUTTON.name -> {
                if (action != null) {
                    sendToService(context, ip, "run", action)
                }
            }
            WidgetType.MEDIA_PLAYER.name -> {
                if (action != null) {
                    sendToService(context, ip, "media", action)
                }
            }
        }
    }

    private fun sendToService(context: Context, ip: String, cmd: String, action: String? = null) {
        val intent = Intent(context, PCForegroundService::class.java).apply {
            this.action = PCForegroundService.ACTION_SEND_COMMAND
            putExtra("DEVICE_IP", ip)
            putExtra("CMD", cmd)
            putExtra("ACTION", action)
        }
        context.startService(intent)
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