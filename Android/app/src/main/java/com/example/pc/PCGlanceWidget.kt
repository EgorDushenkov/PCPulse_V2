package com.example.pc

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.layout.*
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.ActionCallback
import com.google.gson.Gson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.bumptech.glide.Glide
import java.util.concurrent.TimeUnit

class PCGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    companion object {
        val DATA_KEY = stringPreferencesKey("pc_stats_json")
        val LAST_UPDATE_KEY = longPreferencesKey("last_optimistic_update")
        val IS_ONLINE_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("is_online")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceContent(id)
        }
    }

    @Composable
    private fun GlanceContent(id: GlanceId) {
        val context = LocalContext.current
        val state = currentState<Preferences>()
        
        val appWidgetId = getAppWidgetId(context, id)
        
        val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
        val deviceIp = prefs.getString("DEVICE_IP", "") ?: ""
        val layoutJson = prefs.getString("LAYOUT_JSON", null)
        
        var statsJson = state[DATA_KEY]
        val isOnline = state[IS_ONLINE_KEY] ?: true // Default to true if not set
        
        if (statsJson == null) {
            statsJson = context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE).getString(deviceIp, null)
        }
        
        Log.d("PC_WIDGET_DEBUG", "Glance UI: Метод Content() вызван! JSON: ${statsJson?.take(50)}..., Online: $isOnline")
        
        val stats = statsJson?.let { Gson().fromJson(it, PCStats::class.java) }
        val layout = layoutJson?.let { Gson().fromJson(it, DashboardLayout::class.java) }

        if (layout == null) {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xE6121212)).cornerRadius(16.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.glance.text.Text(
                    "Configure Widget",
                    style = androidx.glance.text.TextStyle(color = ColorProvider(Color.White))
                )
            }
            return
        }

        val fullBitmapState = produceState<Bitmap?>(initialValue = null, layout, stats, isOnline) {
            value = withContext(Dispatchers.IO) {
                renderLayoutToBitmap(context, layout, stats, isOnline)
            }
        }
        val fullBitmap = fullBitmapState.value

        val gridWidth = layout.gridWidth
        val gridHeight = layout.gridHeight

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)) {
            if (fullBitmap != null) {
                Image(
                    provider = ImageProvider(fullBitmap),
                    contentDescription = "Background",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }

            Column(modifier = GlanceModifier.fillMaxSize()) {
                for (row in 0 until gridHeight) {
                    Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                        var col = 0
                        while (col < gridWidth) {
                            val widget = layout.widgets.find { col >= it.x && col < it.x + it.width && row >= it.y && row < it.y + it.height }
                            
                            if (widget != null && col == widget.x) {
                                val widgetWidth = LocalSize.current.width * (widget.width.toFloat() / gridWidth)
                                Box(modifier = GlanceModifier.width(widgetWidth).fillMaxHeight()) {
                                    if (widget.type == WidgetType.CONTROLS) {
                                        Row(modifier = GlanceModifier.fillMaxSize()) {
                                            for (i in 0 until widget.width) {
                                                val cellCol = widget.x + i
                                                val relX = cellCol - widget.x
                                                val relY = row - widget.y
                                                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                                                    val command = when {
                                                        relX == 0 && relY == 0 -> "SCREENSHOT"
                                                        relX == 1 && relY == 0 -> "MUTE_MIC"
                                                        relX == 0 && relY == 1 -> "SLEEP"
                                                        relX == 1 && relY == 1 -> "SHUTDOWN"
                                                        else -> null
                                                    }
                                                    if (command != null) {
                                                        val actionValue = if (command == "MUTE_MIC") (if (stats?.mic_muted == true) "0" else "1") else null
                                                        val clickAction = if (command == "SCREENSHOT") {
                                                            actionStartActivity(
                                                                Intent(context, ScreenshotActivity::class.java).apply {
                                                                    putExtra("DEVICE_IP", deviceIp)
                                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                                }
                                                            )
                                                        } else {
                                                            createAction(context, deviceIp, command, actionValue)
                                                        }
                                                        Box(modifier = GlanceModifier.fillMaxSize().clickable(clickAction)) {}
                                                    }
                                                }
                                            }
                                        }
                                    } else if (widget.type == WidgetType.AUDIO_MIXER) {
                                        val relY = row - widget.y
                                        val session = stats?.audio_sessions?.getOrNull(relY)
                                        if (session != null) {
                                            val topPadding = if (relY == 0) 18.dp else 0.dp
                                            Row(modifier = GlanceModifier.fillMaxSize()
                                                .padding(horizontal = 8.dp)
                                                .padding(top = topPadding)) {
                                                listOf(0, 25, 50, 75, 100).forEach { vol ->
                                                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(
                                                        actionRunCallback<OptimisticWidgetAction>(
                                                            actionParametersOf(
                                                                OptimisticWidgetAction.ipKey to deviceIp,
                                                                OptimisticWidgetAction.actionTypeKey to "set_mixer_volume",
                                                                OptimisticWidgetAction.appNameKey to session.name,
                                                                OptimisticWidgetAction.volumeKey to vol.toString()
                                                            )
                                                        )
                                                    )) {}
                                                }
                                            }
                                        }
                                    } else if (widget.type == WidgetType.MEDIA_PLAYER) {
                                        val relRow = row - widget.y
                                        Column(modifier = GlanceModifier.fillMaxSize()) {
                                            if (widget.height > 1 && relRow == 0) {
                                                // В верхней строке высокого виджета (2х2, 4х2) кнопок нет
                                                Spacer(modifier = GlanceModifier.fillMaxSize())
                                            } else {
                                                // Если виджет высокий, кнопки в верхней половине нижней ячейки
                                                // Если виджет 1-строчный, кнопки в нижней половине
                                                val buttonsAtTop = widget.height > 1
                                                if (!buttonsAtTop) Spacer(modifier = GlanceModifier.defaultWeight())

                                                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                                                    listOf("prev", "play_pause", "next").forEach { cmd ->
                                                        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(
                                                            actionRunCallback<OptimisticWidgetAction>(
                                                                actionParametersOf(
                                                                    OptimisticWidgetAction.ipKey to deviceIp,
                                                                    OptimisticWidgetAction.actionTypeKey to "media",
                                                                    OptimisticWidgetAction.actionValueKey to cmd
                                                                )
                                                            )
                                                        )) {}
                                                    }
                                                }

                                                if (buttonsAtTop) Spacer(modifier = GlanceModifier.defaultWeight())
                                            }
                                        }
                                    } else if (widget.type == WidgetType.ACTION_BUTTON) {
                                        Box(modifier = GlanceModifier.fillMaxSize().clickable(
                                            actionRunCallback<OptimisticWidgetAction>(
                                                actionParametersOf(
                                                    OptimisticWidgetAction.ipKey to deviceIp,
                                                    OptimisticWidgetAction.actionTypeKey to "action_button",
                                                    OptimisticWidgetAction.actionValueKey to (widget.action ?: "")
                                                )
                                            )
                                        )) {}
                                    } else if (!widget.action.isNullOrEmpty()) {
                                        Box(modifier = GlanceModifier.fillMaxSize().clickable(
                                            createAction(context, deviceIp, "run", widget.action)
                                        )) {}
                                    }
                                }
                                col += widget.width
                            } else {
                                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {}
                                col++
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createAction(context: Context, ip: String, command: String, actionValue: String?) = actionRunCallback<OptimisticWidgetAction>(
        actionParametersOf(
            OptimisticWidgetAction.ipKey to ip,
            OptimisticWidgetAction.actionTypeKey to command,
            OptimisticWidgetAction.actionValueKey to (actionValue ?: "")
        )
    )

    private fun getAppWidgetId(context: Context, glanceId: GlanceId): Int {
        if (glanceId.toString().contains("AppWidgetId(")) {
            val id = glanceId.toString().substringAfter("AppWidgetId(").substringBefore(")").toIntOrNull()
            if (id != null) return id
        }
        return try {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        } catch (e: Exception) {
            -1
        }
    }

    private suspend fun renderLayoutToBitmap(context: Context, layout: DashboardLayout, stats: PCStats?, isOnline: Boolean): Bitmap? {
        val density = context.resources.displayMetrics.density
        // чем больше cellPx — тем чётче картинка на виджете
        val cellPx = 200 // Higher resolution for better quality
        val widthPx = layout.gridWidth * cellPx
        val heightPx = layout.gridHeight * cellPx
        
        if (widthPx <= 0 || heightPx <= 0) return null
        
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#E6121212") }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)

        layout.widgets.forEach { config ->
            val view = WidgetFactory.create(config, context, isWidget = true)
            
            if (view is ActionButtonWidgetView && config.useIcon && !config.action.isNullOrEmpty()) {
                val url = ActionButtonWidgetView.getIconUrl(context, config)
                try {
                    val iconBitmap = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(url)
                        .submit()
                        .get(3, TimeUnit.SECONDS)
                    view.setIconBitmap(iconBitmap)
                } catch (e: Exception) {
                    Log.e("PC_WIDGET_DEBUG", "Failed to load icon for widget bitmap: $url", e)
                }
            }

            if (view is UpdatableWidget) {
                if (isOnline && stats != null) {
                    view.updateData(stats)
                } else {
                    view.setOffline()
                }
            }
            
            val gap = 8
            val viewWidth = config.width * cellPx - (gap * 2)
            val viewHeight = config.height * cellPx - (gap * 2)
            
            view.measure(
                View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, viewWidth, viewHeight)
            
            canvas.save()
            canvas.translate((config.x * cellPx + gap).toFloat(), (config.y * cellPx + gap).toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }
}

class OptimisticWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ip = parameters[ipKey] ?: return
        val actionType = parameters[actionTypeKey] ?: return
        val actionValue = parameters[actionValueKey]
        val appName = parameters[appNameKey]
        val volumeValue = parameters[volumeKey]

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[PCGlanceWidget.LAST_UPDATE_KEY] = System.currentTimeMillis()
            val statsJson = prefs[PCGlanceWidget.DATA_KEY]
            if (statsJson != null) {
                try {
                    val stats = Gson().fromJson(statsJson, PCStats::class.java)
                    var updated = false
                    val newStats = when (actionType.lowercase()) {
                        "mute_mic", "set_mic_mute" -> {
                            updated = true
                            stats.copy(mic_muted = actionValue == "1")
                        }
                        "set_mixer_volume" -> {
                            if (appName != null && volumeValue != null) {
                                val vol = volumeValue.toIntOrNull() ?: 0
                                val newSessions = stats.audio_sessions.map {
                                    if (it.name == appName) it.copy(volume = vol) else it
                                }
                                updated = true
                                stats.copy(audio_sessions = newSessions)
                            } else stats
                        }
                        "media" -> {
                            if (actionValue == "play_pause") {
                                val currentStatus = stats.media?.status ?: 0
                                val newStatus = if (currentStatus == 4) 0 else 4
                                updated = true
                                stats.copy(media = stats.media?.copy(status = newStatus))
                            } else stats
                        }
                        "action_button" -> stats
                        else -> stats
                    }
                    if (updated) {
                        prefs[PCGlanceWidget.DATA_KEY] = Gson().toJson(newStats)
                    }
                } catch (e: Exception) {
                    Log.e("PC_WIDGET_DEBUG", "Optimistic update failed", e)
                }
            }
        }
        PCGlanceWidget().update(context, glanceId)

        val intent = Intent(context, PCForegroundService::class.java).apply {
            action = PCForegroundService.ACTION_SEND_COMMAND
            putExtra("DEVICE_IP", ip)
            
            if (actionType == "action_button") {
                val statsJson = context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE).getString(ip, null)
                val stats = statsJson?.let { try { Gson().fromJson(statsJson, PCStats::class.java) } catch(e:Exception) { null } }
                val fileName = actionValue?.split("\\", "/")?.last()?.lowercase()
                
                if (stats != null && fileName != null && stats.active_app?.lowercase() == fileName) {
                    putExtra("action_type", "minimize_app")
                } else {
                    putExtra("action_type", "run")
                    putExtra("ACTION", actionValue)
                }
            } else {
                putExtra("action_type", actionType)
                if (!actionValue.isNullOrEmpty()) putExtra("ACTION", actionValue)
            }

            if (!appName.isNullOrEmpty()) putExtra("APP_NAME", appName)
            if (!volumeValue.isNullOrEmpty()) putExtra("VOLUME", volumeValue)
        }
        context.startService(intent)
    }

    companion object {
        val ipKey = ActionParameters.Key<String>("device_ip")
        val actionTypeKey = ActionParameters.Key<String>("action_type")
        val actionValueKey = ActionParameters.Key<String>("action_value")
        val appNameKey = ActionParameters.Key<String>("app_name")
        val volumeKey = ActionParameters.Key<String>("volume_value")
    }
}

class PCGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PCGlanceWidget()
    private val scope = MainScope()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        val action = intent.action
        if (action == "com.example.pc.ACTION_STATS_UPDATE") {
            val ip = intent.getStringExtra("DEVICE_IP")
            val statsJson = intent.getStringExtra("DIRECT_STATS")
            val isOnline = intent.getBooleanExtra("IS_ONLINE", true)
            
            if (ip != null) {
                if (statsJson != null) {
                    context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE)
                        .edit().putString(ip, statsJson).commit()
                }
                
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val ids = manager.getGlanceIds(PCGlanceWidget::class.java)
                        
                        ids.forEach { id ->
                            val appWidgetId = try { manager.getAppWidgetId(id) } catch (e: Exception) { -1 }
                            val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
                            val widgetIp = prefs.getString("DEVICE_IP", "")
                            
                            if (widgetIp == ip) {
                                updateAppWidgetState(context, id) { statePrefs ->
                                    statePrefs[PCGlanceWidget.IS_ONLINE_KEY] = isOnline
                                    if (isOnline && statsJson != null) {
                                        val lastOptimistic = statePrefs[PCGlanceWidget.LAST_UPDATE_KEY] ?: 0L
                                        if (System.currentTimeMillis() - lastOptimistic > 1000L) {
                                            statePrefs[PCGlanceWidget.DATA_KEY] = statsJson
                                        }
                                    }
                                }
                                glanceAppWidget.update(context, id)
                            }
                        }
                        Log.d("PC_WIDGET_DEBUG", "Ресивер: Состояние обновлено для IP: $ip, Online: $isOnline")
                    } catch (e: Exception) {
                        Log.e("PC_WIDGET_DEBUG", "Ресивер: Ошибка", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        } else if (action == "com.example.pc.WIDGET_PINNED_SUCCESS") {
            val pendingResult = goAsync()
            scope.launch {
                try {
                    glanceAppWidget.updateAll(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
