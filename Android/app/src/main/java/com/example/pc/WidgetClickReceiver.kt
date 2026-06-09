package com.example.pc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WidgetClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("WIDGET_TYPE")
        val ip = intent.getStringExtra("DEVICE_IP")
        val action = intent.getStringExtra("ACTION")
        
        Log.d("WidgetClick", "Click received: type=$type, ip=$ip, action=$action")

        if (ip == null) return

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
            WidgetType.CONTROLS.name -> {
                when (action) {
                    "set_mic_mute" -> {
                        val statsJson = context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE).getString(ip, null)
                        val stats = statsJson?.let { com.google.gson.Gson().fromJson(it, PCStats::class.java) }
                        val currentMuted = stats?.mic_muted ?: false
                        sendToService(context, ip, "set_mic_mute", if (currentMuted) "0" else "1")
                    }
                    "screenshot" -> sendToService(context, ip, "screenshot")
                    "sleep" -> sendToService(context, ip, "sleep")
                    "shutdown" -> sendToService(context, ip, "shutdown")
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
}