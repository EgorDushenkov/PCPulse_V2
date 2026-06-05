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
        if (intent.action == PCForegroundService.ACTION_STATS_UPDATE || 
            intent.action == "android.appwidget.action.APPWIDGET_PINNED" ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, PCAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            if (intent.action == "android.appwidget.action.APPWIDGET_PINNED") {
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

            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
            val layoutJson = prefs.getString("LAYOUT_JSON", null)
            val deviceIp = prefs.getString("DEVICE_IP", "") ?: ""
            
            val views = RemoteViews(context.packageName, R.layout.widget_layout_base)
            
            if (layoutJson == null) {
                // Initial state before configuration
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
            
            // Draw background
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

                // Click area
                try {
                    val clickArea = RemoteViews(context.packageName, R.layout.widget_click_area)
                    
                    val clickIntent = Intent(context, WidgetClickReceiver::class.java).apply {
                        putExtra("WIDGET_TYPE", config.type.name)
                        putExtra("DEVICE_IP", deviceIp)
                        putExtra("ACTION", config.action)
                    }
                    
                    val pendingIntent = PendingIntent.getBroadcast(
                        context, 
                        appWidgetId * 100 + index, 
                        clickIntent, 
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    clickArea.setOnClickPendingIntent(R.id.click_button, pendingIntent)
                    
                    // Position the click area in the grid
                    clickArea.setViewPadding(
                        R.id.click_root, 
                        config.x * cellPx, 
                        config.y * cellPx,
                        (widgetWidthPx - (config.x + config.width) * cellPx).coerceAtLeast(0),
                        (widgetHeightPx - (config.y + config.height) * cellPx).coerceAtLeast(0)
                    )
                    
                    views.addView(R.id.click_grid, clickArea)
                } catch (e: Exception) {}
            }

            views.setImageViewBitmap(R.id.widget_image, bitmap)
            
            // Add global click to re-configure on empty area? 
            // Better to keep it for elements only.

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}