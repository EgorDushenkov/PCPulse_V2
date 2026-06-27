package com.example.pc

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.content.ComponentName
import android.graphics.Bitmap
import com.google.gson.Gson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.glance.appwidget.updateAll
import java.util.concurrent.ConcurrentHashMap

class PCForegroundService : Service() {

    private val gson = Gson()
    private val connections = ConcurrentHashMap<String, WebSocketManager>()
    private val deviceStats = ConcurrentHashMap<String, PCStats>()
    private val mediaSessions = ConcurrentHashMap<String, MediaSessionCompat>()
    private var lastWidgetUpdateTime = 0L
    private val scope = MainScope()
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    companion object {
        const val CHANNEL_ID = "PC_MONITOR_SERVICE"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"
        const val ACTION_STATS_UPDATE = "com.example.pc.STATS_UPDATE"
        const val ACTION_SEND_COMMAND = "com.example.pc.ACTION_SEND_COMMAND"

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
        if (intent == null) {
            startForegroundService()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> startForegroundService()
            ACTION_REFRESH -> updateConnections()
            ACTION_STOP -> stopSelf()
            ACTION_SEND_COMMAND -> {
                val ip = intent.getStringExtra("DEVICE_IP")
                val cmd = intent.getStringExtra("CMD") ?: intent.getStringExtra("action_type")?.lowercase()
                val action = intent.getStringExtra("ACTION")
                if (ip != null && cmd != null) {
                    val socket = connections[ip]
                    when (cmd) {
                        "media" -> {
                            if (action != null) socket?.sendCommand("media_command", mapOf("cmd" to action))
                        }
                        "run" -> {
                            if (action != null) socket?.sendCommand("run", mapOf("path" to action))
                        }
                        "mute_mic", "set_mic_mute" -> {
                            val mute = if (action != null) action == "1" else true
                            socket?.sendCommand("set_mic_mute", mapOf("mute" to if (mute) 1 else 0))
                        }
                        "key_press" -> {
                            val keysString = intent.getStringExtra("KEYS")
                            if (keysString != null) {
                                val keysList = keysString.split("+")
                                socket?.sendCommand("key_press", mapOf("keys" to keysList))
                            }
                        }
                        "screenshot" -> socket?.sendCommand("screenshot")
                        "sleep" -> socket?.sendCommand("sleep")
                        "shutdown" -> socket?.sendCommand("shutdown")
                        "set_mixer_volume" -> {
                            val app = intent.getStringExtra("APP_NAME")
                            val vol = intent.getStringExtra("VOLUME")?.toIntOrNull()
                            if (ip != null && app != null && vol != null) {
                                val key = "$ip|$app"
                                debounceJobs[key]?.cancel()
                                debounceJobs[key] = scope.launch {
                                    delay(150)
                                    socket?.sendCommand("set_mixer_volume", mapOf("app" to app, "vol" to vol))
                                }
                            }
                        }
                        else -> {
                            socket?.sendCommand(cmd)
                        }
                    }
                }
            }
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

        val currentIps = connections.keys().toList()
        currentIps.forEach { ip ->
            if (ip !in ipSet) {
                connections[ip]?.disconnect()
                connections.remove(ip)
                deviceStats.remove(ip)
            }
        }

        ipSet.forEach { ip ->
            if (!connections.containsKey(ip)) {
                val token = prefs.getString("TOKEN_$ip", null)
                
                val manager = WebSocketManager(
                    gson = gson,
                    token = token,
                    onStatusChanged = { isOnline ->
                        if (!isOnline) {
                            deviceStats.remove(ip)
                            broadcastStatus(ip, false)
                            broadcastOfflineForWidget(ip)
                            updateNotification()
                        }
                    },
                    onAuthFailed = {
                        val intent = Intent(ACTION_STATS_UPDATE).apply {
                            setPackage(packageName)
                            putExtra("DEVICE_IP", ip)
                            putExtra("IS_ONLINE", false)
                            putExtra("AUTH_FAILED", true)
                        }
                        sendBroadcast(intent)
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

    private fun broadcastOfflineForWidget(ip: String) {
        val widgetIntent = Intent("com.example.pc.ACTION_STATS_UPDATE").apply {
            component = ComponentName(this@PCForegroundService, PCGlanceWidgetReceiver::class.java)
            putExtra("DEVICE_IP", ip)
            putExtra("IS_ONLINE", false)
        }
        sendBroadcast(widgetIntent)
    }

    private fun broadcastStats(ip: String, stats: PCStats) {
        val statsJson = gson.toJson(stats)
        
        getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE)
            .edit().putString(ip, statsJson).commit()

        val intent = Intent(ACTION_STATS_UPDATE).apply {
            setPackage(packageName)
            putExtra("DEVICE_IP", ip)
            putExtra("IS_ONLINE", true)
            putExtra("STATS_JSON", statsJson)
        }
        sendBroadcast(intent)

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastWidgetUpdateTime > 1000) {
            lastWidgetUpdateTime = currentTime
            Log.d("PC_WIDGET_DEBUG", "Сервис: Отправляю интент обновления для IP: $ip, время: $currentTime")
            val widgetIntent = Intent("com.example.pc.ACTION_STATS_UPDATE").apply {
                component = ComponentName(this@PCForegroundService, PCGlanceWidgetReceiver::class.java)
                putExtra("DEVICE_IP", ip)
                putExtra("DIRECT_STATS", statsJson)
            }
            sendBroadcast(widgetIntent)
        }
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val showMedia = prefs.getBoolean("MEDIA_NOTIF_ENABLED", true)
        val defaultMediaIp = prefs.getString("DEFAULT_MEDIA_IP", null)
        
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (showMedia) {
            val playingDevices = deviceStats.filter { it.value.media != null }
            val hasMultiplePlaying = playingDevices.size > 1
            val defaultIsPlaying = defaultMediaIp != null && playingDevices.containsKey(defaultMediaIp)

            deviceStats.forEach { (ip, stats) ->
                val isPlaying = stats.media != null
                val shouldShow = if (isPlaying) {
                    if (hasMultiplePlaying && defaultIsPlaying) {
                        ip == defaultMediaIp
                    } else {
                        true
                    }
                } else {
                    false
                }

                if (shouldShow) {
                    val session = getOrCreateMediaSession(ip, stats)
                    val mediaNotif = createMediaNotification(ip, stats, session)
                    notificationManager.notify(ip.hashCode(), mediaNotif)
                } else {
                    notificationManager.cancel(ip.hashCode())
                    mediaSessions[ip]?.isActive = false
                }
            }
        } else {
            deviceStats.keys.forEach { ip -> 
                notificationManager.cancel(ip.hashCode())
                mediaSessions[ip]?.isActive = false
            }
        }

        val onlineCount = deviceStats.size
        val text = if (onlineCount > 0) {
            "Devices online: $onlineCount"
        } else {
            "Searching for devices..."
        }
        val notification = createNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getOrCreateMediaSession(ip: String, stats: PCStats): MediaSessionCompat {
        return mediaSessions.getOrPut(ip) {
            MediaSessionCompat(this, "PCSession_$ip").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { sendMediaCommand(ip, "play_pause") }
                    override fun onPause() { sendMediaCommand(ip, "play_pause") }
                    override fun onSkipToNext() { sendMediaCommand(ip, "next") }
                    override fun onSkipToPrevious() { sendMediaCommand(ip, "prev") }
                })
            }
        }.apply {
            val media = stats.media ?: return@apply
            
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, media.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, stats.pc_name)
                .build()
            setMetadata(metadata)

            val state = if (media.status == 4) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build())
            
            isActive = true
        }
    }

    private fun sendMediaCommand(ip: String, command: String) {
        val intent = Intent(this, PCForegroundService::class.java).apply {
            action = ACTION_SEND_COMMAND
            putExtra("DEVICE_IP", ip)
            putExtra("CMD", "media")
            putExtra("ACTION", command)
        }
        startService(intent)
    }

    private fun createMediaNotification(ip: String, stats: PCStats, session: MediaSessionCompat): Notification {
        val media = stats.media ?: return createNotification("PC Online: ${stats.pc_name}")
        
        val reqCode = Math.abs(ip.hashCode())
        
        val prevIntent = Intent(this, WidgetClickReceiver::class.java).apply {
            action = "com.example.pc.MEDIA_ACTION"
            putExtra("DEVICE_IP", ip)
            putExtra("ACTION", "prev")
        }
        val playIntent = Intent(this, WidgetClickReceiver::class.java).apply {
            action = "com.example.pc.MEDIA_ACTION"
            putExtra("DEVICE_IP", ip)
            putExtra("ACTION", "play_pause")
        }
        val nextIntent = Intent(this, WidgetClickReceiver::class.java).apply {
            action = "com.example.pc.MEDIA_ACTION"
            putExtra("DEVICE_IP", ip)
            putExtra("ACTION", "next")
        }

        val pPrev = PendingIntent.getBroadcast(this, reqCode + 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pPlay = PendingIntent.getBroadcast(this, reqCode + 2, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pNext = PendingIntent.getBroadcast(this, reqCode + 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val playIcon = if (media.status == 4) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(media.title)
            .setContentText("${stats.pc_name} - ${media.artist}")
            .setLargeIcon(null as Bitmap?) 
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Prev", pPrev)
            .addAction(playIcon, "Play/Pause", pPlay)
            .addAction(android.R.drawable.ic_media_next, "Next", pNext)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            
        return builder.build()
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PC Pulse Background Service")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        scope.cancel()
        connections.values.forEach { it.disconnect() }
        connections.clear()
        mediaSessions.values.forEach { 
            it.isActive = false
            it.release() 
        }
        mediaSessions.clear()
        super.onDestroy()
    }
}
