package com.example.pc

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap

class PCForegroundService : Service() {

    private val gson = Gson()
    private val connections = ConcurrentHashMap<String, WebSocketManager>()
    private val deviceStats = ConcurrentHashMap<String, PCStats>()

    companion object {
        const val CHANNEL_ID = "PC_MONITOR_SERVICE"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"
        const val ACTION_STATS_UPDATE = "com.example.pc.STATS_UPDATE"

        fun startService(context: Context) {
            val intent = Intent(context, PCForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, PCForegroundService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PCForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_REFRESH -> updateConnections()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Monitoring PCs...")
        startForeground(NOTIFICATION_ID, notification)
        updateConnections()
    }

    private fun updateConnections() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val ipSet = prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()

        // Remove old connections
        val currentIps = connections.keys().toList()
        currentIps.forEach { ip ->
            if (ip !in ipSet) {
                connections[ip]?.disconnect()
                connections.remove(ip)
                deviceStats.remove(ip)
            }
        }

        // Add new connections or update current state for app
        ipSet.forEach { ip ->
            if (!connections.containsKey(ip)) {
                val manager = WebSocketManager(
                    gson = gson,
                    onStatusChanged = { isOnline ->
                        if (!isOnline) {
                            deviceStats.remove(ip)
                            broadcastStatus(ip, false)
                            updateNotification()
                        }
                    },
                    onStatsReceived = { stats ->
                        deviceStats[ip] = stats
                        broadcastStats(ip, stats)
                        updateNotification()
                    }
                )
                manager.connect("ws://$ip:5000/ws")
                connections[ip] = manager
            } else {
                // Если соединение уже есть, сразу отправляем текущие данные приложению
                deviceStats[ip]?.let { stats ->
                    broadcastStats(ip, stats)
                } ?: broadcastStatus(ip, true)
            }
        }
    }

    private fun broadcastStatus(ip: String, isOnline: Boolean) {
        val intent = Intent(ACTION_STATS_UPDATE).apply {
            setPackage(packageName)
            putExtra("DEVICE_IP", ip)
            putExtra("IS_ONLINE", isOnline)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStats(ip: String, stats: PCStats) {
        val statsJson = gson.toJson(stats)
        
        // Cache for widgets
        getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE)
            .edit().putString(ip, statsJson).apply()

        // Explicitly update widgets
        val updateIntent = Intent(this, PCAppWidgetProvider::class.java).apply {
            action = ACTION_STATS_UPDATE
        }
        sendBroadcast(updateIntent)

        val intent = Intent(ACTION_STATS_UPDATE).apply {
            setPackage(packageName)
            putExtra("DEVICE_IP", ip)
            putExtra("IS_ONLINE", true)
            putExtra("STATS_JSON", statsJson)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification() {
        val onlineCount = deviceStats.size
        val text = if (onlineCount > 0) {
            "Devices online: $onlineCount"
        } else {
            "Searching for devices..."
        }
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PC Pulse Background Service")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use a better icon if available
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "PC Monitor Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        super.onDestroy()
    }
}
