package com.example.pc

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.layout.*
import androidx.glance.unit.ColorProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import com.google.gson.Gson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class PCGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    companion object {
        val DATA_KEY = stringPreferencesKey("pc_stats_json")
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
        
        // Use a more reliable way to get appWidgetId or fallback to scanning
        val appWidgetId = getAppWidgetId(context, id)
        
        val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
        val deviceIp = prefs.getString("DEVICE_IP", "") ?: ""
        val layoutJson = prefs.getString("LAYOUT_JSON", null)
        
        // Try to get stats from Glance State first (it's the most reactive way)
        var statsJson = state[DATA_KEY]
        
        // If state is empty or belongs to another IP (in case of multiple widgets), fallback to cache
        // Actually, for multiple widgets we should store a Map in the state or use per-widget state
        // For now, let's see if this forces the update
        if (statsJson == null) {
            statsJson = context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE).getString(deviceIp, null)
        }
        
        Log.d("PC_WIDGET_DEBUG", "Glance UI: Метод Content() вызван! JSON: ${statsJson?.take(50)}...")
        
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

        // Layout parameters
        val gridWidth = layout.gridWidth
        val gridHeight = layout.gridHeight

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)) {
            // 1. Pixel-perfect background
            val fullBitmap = renderLayoutToBitmap(context, layout, stats)
            if (fullBitmap != null) {
                Image(
                    provider = ImageProvider(fullBitmap),
                    contentDescription = "Background",
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }

            // 2. Clickable layer using Box offsets
            // We use a Column/Row structure that matches the grid to ensure clicks are captured
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
                                                        Box(modifier = GlanceModifier.fillMaxSize().clickable(
                                                            createAction(context, deviceIp, command, actionValue)
                                                        )) {}
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
                                                        actionStartService(
                                                            Intent(context, PCForegroundService::class.java).apply {
                                                                action = PCForegroundService.ACTION_SEND_COMMAND
                                                                putExtra("DEVICE_IP", deviceIp)
                                                                putExtra("action_type", "set_mixer_volume")
                                                                putExtra("APP_NAME", session.name)
                                                                putExtra("VOLUME", vol.toString())
                                                            }
                                                        )
                                                    )) {}
                                                }
                                            }
                                        }
                                    } else if (widget.type == WidgetType.MEDIA_PLAYER) {
                                        Column(modifier = GlanceModifier.fillMaxSize()) {
                                            Spacer(modifier = GlanceModifier.defaultWeight())
                                            Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                                                listOf("prev", "play_pause", "next").forEach { cmd ->
                                                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(
                                                        actionStartService(
                                                            Intent(context, PCForegroundService::class.java).apply {
                                                                action = PCForegroundService.ACTION_SEND_COMMAND
                                                                putExtra("DEVICE_IP", deviceIp)
                                                                putExtra("action_type", "media")
                                                                putExtra("ACTION", cmd)
                                                            }
                                                        )
                                                    )) {}
                                                }
                                            }
                                        }
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

    private fun createAction(context: Context, ip: String, command: String, actionValue: String?) = actionStartService(
        Intent(context, PCForegroundService::class.java).apply {
            action = PCForegroundService.ACTION_SEND_COMMAND
            putExtra("DEVICE_IP", ip)
            putExtra("action_type", command)
            if (actionValue != null) putExtra("ACTION", actionValue)
        }
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

    private fun renderLayoutToBitmap(context: Context, layout: DashboardLayout, stats: PCStats?): Bitmap? {
        val density = context.resources.displayMetrics.density
        // Use a fixed virtual size for the bitmap to ensure consistency
        val cellPx = 200 // Higher resolution for better quality
        val widthPx = layout.gridWidth * cellPx
        val heightPx = layout.gridHeight * cellPx
        
        if (widthPx <= 0 || heightPx <= 0) return null
        
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Draw background
        val bgPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#E6121212") }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)

        layout.widgets.forEach { config ->
            val view = WidgetFactory.create(config, context, isWidget = true)
            if (stats != null && view is UpdatableWidget) {
                view.updateData(stats)
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

class PCGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PCGlanceWidget()
    private val scope = MainScope()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        val action = intent.action
        if (action == "com.example.pc.ACTION_STATS_UPDATE") {
            val ip = intent.getStringExtra("DEVICE_IP")
            val statsJson = intent.getStringExtra("DIRECT_STATS")
            
            if (ip != null && statsJson != null) {
                context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE)
                    .edit().putString(ip, statsJson).commit()
                
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val ids = manager.getGlanceIds(PCGlanceWidget::class.java)
                        
                        ids.forEach { id ->
                            val appWidgetId = try { manager.getAppWidgetId(id) } catch (e: Exception) { -1 }
                            val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
                            val widgetIp = prefs.getString("DEVICE_IP", "")
                            
                            // Update state only for widgets matching this IP
                            if (widgetIp == ip) {
                                updateAppWidgetState(context, id) { prefs ->
                                    prefs[PCGlanceWidget.DATA_KEY] = statsJson
                                }
                                glanceAppWidget.update(context, id)
                            }
                        }
                        Log.d("PC_WIDGET_DEBUG", "Ресивер: Состояние обновлено для IP: $ip")
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
