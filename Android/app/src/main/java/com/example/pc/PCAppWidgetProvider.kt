package com.example.pc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews

class PCAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == PCForegroundService.ACTION_STATS_UPDATE || 
            action == "com.example.pc.WIDGET_PINNED_SUCCESS" ||
            action == "android.appwidget.action.APPWIDGET_PINNED" ||
            action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            
            if (action == "com.example.pc.WIDGET_PINNED_SUCCESS" || action == "android.appwidget.action.APPWIDGET_PINNED") {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val ip = intent.getStringExtra("DEVICE_IP")
                val layoutJson = intent.getStringExtra("LAYOUT_JSON")
                
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && ip != null && layoutJson != null) {
                    context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE).edit()
                        .putString("DEVICE_IP", ip)
                        .putString("LAYOUT_JSON", layoutJson)
                        .apply()
                }
            }

            val componentName = ComponentName(context, PCAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
            val layoutJson = prefs.getString("LAYOUT_JSON", null)
            val deviceIp = prefs.getString("DEVICE_IP", "") ?: ""
            
            val views = RemoteViews(context.packageName, R.layout.widget_layout_base)
            
            if (layoutJson == null) {
                views.setImageViewResource(R.id.widget_image, android.R.drawable.ic_menu_edit)
                views.setViewVisibility(R.id.click_grid, View.GONE)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val layout = com.google.gson.Gson().fromJson(layoutJson, DashboardLayout::class.java)
            val statsJson = context.getSharedPreferences("PC_STATS_CACHE", Context.MODE_PRIVATE).getString(deviceIp, null)
            val stats = statsJson?.let { com.google.gson.Gson().fromJson(it, PCStats::class.java) }

            val density = context.resources.displayMetrics.density
            val cellPx = (100 * density).toInt() 
            val widgetWidthPx = layout.gridWidth * cellPx
            val widgetHeightPx = layout.gridHeight * cellPx
            
            if (widgetWidthPx <= 0 || widgetHeightPx <= 0) return

            val bitmap = Bitmap.createBitmap(widgetWidthPx, widgetHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            val bgPaint = Paint().apply {
                color = Color.parseColor("#E6121212")
                isAntiAlias = true
            }
            val cornerRadius = 24f * density
            canvas.drawRoundRect(0f, 0f, widgetWidthPx.toFloat(), widgetHeightPx.toFloat(), cornerRadius, cornerRadius, bgPaint)
            
            views.setViewVisibility(R.id.click_grid, View.VISIBLE)
            views.removeAllViews(R.id.click_grid)
            
            layout.widgets.forEachIndexed { index, config ->
                config.deviceIp = deviceIp
                val widgetView = WidgetFactory.create(config, context)
                if (stats != null && widgetView is UpdatableWidget) {
                    widgetView.updateData(stats)
                }
                
                val gap = (6 * density).toInt()
                val viewWidth = config.width * cellPx - (gap * 2)
                val viewHeight = config.height * cellPx - (gap * 2)
                
                widgetView.measure(
                    View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewHeight, View.MeasureSpec.EXACTLY)
                )
                widgetView.layout(0, 0, viewWidth, viewHeight)
                
                canvas.save()
                canvas.translate((config.x * cellPx + gap).toFloat(), (config.y * cellPx + gap).toFloat())
                widgetView.draw(canvas)
                canvas.restore()

                // Click areas for sub-elements
                val clickConfigs = mutableListOf<Triple<String?, Int, Int>>() // Action, sub-index, total-subs
                
                when (config.type) {
                    WidgetType.MEDIA_PLAYER -> {
                        clickConfigs.add(Triple("prev", 0, 3))
                        clickConfigs.add(Triple("play_pause", 1, 3))
                        clickConfigs.add(Triple("next", 2, 3))
                    }
                    WidgetType.CONTROLS -> {
                        clickConfigs.add(Triple("screenshot", 0, 4))
                        clickConfigs.add(Triple("set_mic_mute", 1, 4))
                        clickConfigs.add(Triple("sleep", 2, 4))
                        clickConfigs.add(Triple("shutdown", 3, 4))
                    }
                    else -> {
                        clickConfigs.add(Triple(config.action, 0, 1))
                    }
                }

                clickConfigs.forEach { (subAction, subIdx, total) ->
                    val clickArea = RemoteViews(context.packageName, R.layout.widget_click_area)
                    val clickIntent = Intent(context, WidgetClickReceiver::class.java).apply {
                        putExtra("WIDGET_TYPE", config.type?.name ?: "")
                        putExtra("DEVICE_IP", deviceIp)
                        putExtra("ACTION", subAction)
                    }
                    
                    val pendingIntent = PendingIntent.getBroadcast(
                        context, 
                        appWidgetId * 1000 + index * 10 + subIdx, 
                        clickIntent, 
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    clickArea.setOnClickPendingIntent(R.id.click_button, pendingIntent)

                    // Calculate bounds
                    var l = (config.x * cellPx).toInt()
                    var t = (config.y * cellPx).toInt()
                    var r = (widgetWidthPx - (config.x + config.width) * cellPx).toInt()
                    var b = (widgetHeightPx - (config.y + config.height) * cellPx).toInt()
                    
                    val w = (config.width * cellPx).toInt()
                    val h = (config.height * cellPx).toInt()

                    if (total == 4) { // Controls 2x2
                        val hw = w / 2
                        val hh = h / 2
                        when (subIdx) {
                            0 -> { r += hw; b += hh } // top-left (screenshot)
                            1 -> { l += hw; b += hh } // top-right (mic)
                            2 -> { r += hw; t += hh } // bottom-left (sleep)
                            3 -> { l += hw; t += hh } // bottom-right (shutdown)
                        }
                    } else if (total == 3) { // Media 1x3 (bottom half)
                        val bw = w / 3
                        val bh = h / 2
                        t += bh
                        l += subIdx * bw
                        r += (2 - subIdx) * bw
                    } else if (total == 1) {
                        // Keep full size
                    }

                    clickArea.setViewPadding(R.id.click_root, l, t, r, b)
                    views.addView(R.id.click_grid, clickArea)
                }
            }

            views.setImageViewBitmap(R.id.widget_image, bitmap)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}