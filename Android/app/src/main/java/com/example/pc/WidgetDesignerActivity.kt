package com.example.pc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import kotlin.math.roundToInt

class WidgetDesignerActivity : BaseActivity() {

    private lateinit var deviceSpinner: Spinner
    private lateinit var canvas: FrameLayout
    private lateinit var presetsContainer: LinearLayout
    private lateinit var btnAddElement: Button
    private lateinit var btnSave: Button
    private lateinit var btnToggleEdit: Button

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedDevice: String = ""
    private var currentLayout = DashboardLayout("New Widget", emptyList(), 4, 2)
    
    private val widgetViews = mutableMapOf<View, WidgetConfig>()
    private val resizeHandles = mutableMapOf<View, View>()
    private val deleteHandles = mutableMapOf<View, View>()

    private var cellWidth: Int = 0
    private var cellHeight: Int = 0
    private var dX = 0f
    private var dY = 0f
    private var startWidth = 0
    private var startHeight = 0
    private var isEditMode = true

    private var gridColumns = 4
    private var gridRows = 2

    private inner class GridDrawable : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint().apply {
            color = Color.WHITE
            alpha = 30
            strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
        }
        override fun draw(c: android.graphics.Canvas) {
            if (!isEditMode) return
            val cw = bounds.width() / gridColumns.toFloat()
            val ch = bounds.height() / gridRows.toFloat()
            for (i in 1 until gridColumns) c.drawLine(i * cw, 0f, i * cw, bounds.height().toFloat(), p)
            for (i in 1 until gridRows) c.drawLine(0f, i * ch, bounds.width().toFloat(), i * ch, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Handle Widget Configuration mode
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            val prefs = getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE)
            val json = prefs.getString("LAYOUT_JSON", null)
            if (json != null) {
                try {
                    currentLayout = gson.fromJson(json, DashboardLayout::class.java)
                    selectedDevice = prefs.getString("DEVICE_IP", "") ?: ""
                    gridColumns = currentLayout.gridWidth
                    gridRows = currentLayout.gridHeight
                } catch (e: Exception) {}
            }
        }

        setContentView(R.layout.activity_widget_designer)

        deviceSpinner = findViewById(R.id.deviceSpinner)
        canvas = findViewById(R.id.widget_canvas)
        presetsContainer = findViewById(R.id.presetsContainer)
        btnAddElement = findViewById(R.id.btnAddElement)
        btnSave = findViewById(R.id.btnSaveWidget)
        btnToggleEdit = findViewById(R.id.btnToggleEdit)

        setupDeviceSpinner()
        setupPresets()
        
        btnAddElement.setOnClickListener { showAddWidgetDialog() }
        btnSave.setOnClickListener { saveWidget() }
        btnToggleEdit.setOnClickListener { toggleEditMode() }

        canvas.post {
            updateCanvasSize()
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        btnToggleEdit.text = if (isEditMode) "✓" else "✎"
        refreshWidgets()
    }

    override fun onStatsUpdated(stats: PCStats) {
        // Update live data in designer
        runOnUiThread {
            widgetViews.keys.forEach { card ->
                val content = (card as? CardView)?.getChildAt(0)
                if (content is UpdatableWidget) {
                    content.updateData(stats)
                }
            }
        }
    }

    private fun setupDeviceSpinner() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val devices = (prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()).toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, devices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        
        val initialPos = devices.indexOf(selectedDevice).coerceAtLeast(0)
        deviceSpinner.setSelection(initialPos)

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                selectedDevice = devices[pos]
                
                // Connect to WebSocket for live preview
                webSocketManager?.disconnect()
                webSocketManager = WebSocketManager(gson, null) { stats ->
                    onStatsUpdated(stats)
                }
                webSocketManager?.connect("ws://$selectedDevice:5000/ws")

                refreshWidgets()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupPresets() {
        val presets = listOf(2 to 1, 2 to 2, 4 to 1, 4 to 2, 4 to 3, 4 to 4)
        presetsContainer.removeAllViews()
        presets.forEach { (w, h) ->
            val btn = TextView(this).apply {
                text = "${w}x${h}"
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.btn_preset_selector)
                val params = LinearLayout.LayoutParams(160, 100).apply {
                    setMargins(8, 8, 8, 8)
                }
                layoutParams = params
                isSelected = (w == currentLayout.gridWidth && h == currentLayout.gridHeight)
                setOnClickListener {
                    for (i in 0 until presetsContainer.childCount) {
                        presetsContainer.getChildAt(i).isSelected = false
                    }
                    isSelected = true
                    gridColumns = w
                    gridRows = h
                    currentLayout = currentLayout.copy(gridWidth = w, gridHeight = h)
                    updateCanvasSize()
                }
            }
            presetsContainer.addView(btn)
        }
    }

    private fun updateCanvasSize() {
        val wrapper = findViewById<View>(R.id.designer_canvas_wrapper)
        wrapper.post {
            val maxWidth = wrapper.width - wrapper.paddingLeft - wrapper.paddingRight
            val maxHeight = wrapper.height - wrapper.paddingTop - wrapper.paddingBottom
            
            gridColumns = currentLayout.gridWidth
            gridRows = currentLayout.gridHeight

            // Maintain aspect ratio based on grid cells (assuming square cells for simplicity or standard 1:1 ratio)
            // But usually widgets have a specific ratio. Let's use 3:2 as a base for 4x2 etc.
            // Or better, just calculate based on columns/rows
            val cellRatio = 1.0f 
            val ratio = (gridColumns * cellRatio) / gridRows
            
            var targetWidth = maxWidth
            var targetHeight = (targetWidth / ratio).toInt()
            
            if (targetHeight > maxHeight) {
                targetHeight = maxHeight
                targetWidth = (targetHeight * ratio).toInt()
            }
            
            val lp = canvas.layoutParams
            lp.width = targetWidth
            lp.height = targetHeight
            canvas.layoutParams = lp
            
            canvas.background = GridDrawable()
            
            canvas.post {
                cellWidth = canvas.width / gridColumns
                cellHeight = canvas.height / gridRows
                refreshWidgets()
            }
        }
    }

    private fun refreshWidgets() {
        canvas.removeAllViews()
        widgetViews.clear()
        resizeHandles.clear()
        deleteHandles.clear()
        
        currentLayout.widgets.forEach { config ->
            config.deviceIp = selectedDevice
            val card = createWidgetView(config)
            canvas.addView(card)
            widgetViews[card] = config
            
            if (isEditMode) {
                val rh = createHandle(card, R.drawable.ic_resize, Color.parseColor("#4285F4")) { handleResize(card, it) }
                canvas.addView(rh)
                resizeHandles[card] = rh
                
                val dh = createHandle(card, R.drawable.ic_delete, Color.parseColor("#EA4335")) { 
                    AlertDialog.Builder(this)
                        .setTitle("Удалить элемент?")
                        .setPositiveButton("Да") { _, _ ->
                            currentLayout = currentLayout.copy(widgets = currentLayout.widgets - config)
                            refreshWidgets()
                        }
                        .setNegativeButton("Нет", null)
                        .show()
                }
                canvas.addView(dh)
                deleteHandles[card] = dh
            }
            
            applyLayout(card, config)
        }
    }

    private fun createWidgetView(config: WidgetConfig): View {
        val view = WidgetFactory.create(config, this)
        
        val card = CardView(this).apply {
            radius = 12f * resources.displayMetrics.density
            cardElevation = 4f * resources.displayMetrics.density
            setCardBackgroundColor(Color.parseColor("#2D2D2D"))
            addView(view)
        }

        card.setOnTouchListener { v, event -> 
            if (isEditMode) {
                handleDrag(v, event)
                true
            } else false
        }
        
        card.setOnClickListener { 
            if (isEditMode) showWidgetSettingsDialog(config) 
        }
        
        return card
    }

    private fun showWidgetSettingsDialog(config: WidgetConfig) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_widget_settings, null)
        val etLabel = dialogView.findViewById<EditText>(R.id.etLabel)
        val etAction = dialogView.findViewById<EditText>(R.id.etAction)
        val cbUseIcon = dialogView.findViewById<CheckBox>(R.id.cbUseIcon)

        etLabel.setText(config.label)
        etAction.setText(config.action)
        cbUseIcon.isChecked = config.useIcon

        AlertDialog.Builder(this)
            .setTitle("Настройка элемента")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                config.label = etLabel.text.toString()
                config.action = etAction.text.toString()
                config.useIcon = cbUseIcon.isChecked
                refreshWidgets()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun createHandle(target: View, resId: Int, color: Int, onTouch: (MotionEvent) -> Unit): View {
        val size = (24 * resources.displayMetrics.density).toInt()
        return CardView(this).apply {
            radius = size / 2f
            setCardBackgroundColor(color)
            cardElevation = 6f * resources.displayMetrics.density
            val icon = ImageView(context).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(12, 12, 12, 12)
                setColorFilter(Color.WHITE)
            }
            addView(icon)
            layoutParams = FrameLayout.LayoutParams(size, size)
            setOnTouchListener { _, event -> 
                onTouch(event)
                true 
            }
        }
    }

    private fun handleDrag(view: View, event: MotionEvent) {
        val rh = resizeHandles[view] ?: return
        val dh = deleteHandles[view] ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                vibrate(5)
                dX = view.x - event.rawX
                dY = view.y - event.rawY
                view.bringToFront()
                rh.bringToFront()
                dh.bringToFront()
            }
            MotionEvent.ACTION_MOVE -> {
                view.x = event.rawX + dX
                view.y = event.rawY + dY
                updateHandles(view)
            }
            MotionEvent.ACTION_UP -> {
                vibrate(5)
                val cfg = widgetViews[view] ?: return
                cfg.x = (view.x / cellWidth).roundToInt().coerceIn(0, gridColumns - cfg.width)
                cfg.y = (view.y / cellHeight).roundToInt().coerceIn(0, gridRows - cfg.height)
                applyLayout(view, cfg)
            }
        }
    }

    private fun handleResize(view: View, event: MotionEvent) {
        val rh = resizeHandles[view] ?: return
        val dh = deleteHandles[view] ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                vibrate(5)
                dX = event.rawX
                dY = event.rawY
                startWidth = view.width
                startHeight = view.height
                view.bringToFront()
                rh.bringToFront()
                dh.bringToFront()
            }
            MotionEvent.ACTION_MOVE -> {
                view.layoutParams.width = (startWidth + (event.rawX - dX)).toInt().coerceAtLeast(cellWidth / 2)
                view.layoutParams.height = (startHeight + (event.rawY - dY)).toInt().coerceAtLeast(cellHeight / 2)
                view.requestLayout()
                updateHandles(view)
            }
            MotionEvent.ACTION_UP -> {
                vibrate(5)
                val cfg = widgetViews[view] ?: return
                cfg.width = ((view.width + 10) / cellWidth.toFloat()).roundToInt().coerceIn(1, gridColumns - cfg.x)
                cfg.height = ((view.height + 10) / cellHeight.toFloat()).roundToInt().coerceIn(1, gridRows - cfg.y)
                applyLayout(view, cfg)
            }
        }
    }

    private fun updateHandles(view: View) {
        val rh = resizeHandles[view] ?: return
        val dh = deleteHandles[view] ?: return
        rh.x = view.x + view.width - rh.width / 2f
        rh.y = view.y + view.height - rh.height / 2f
        dh.x = view.x - dh.width / 2f
        dh.y = view.y - dh.height / 2f
    }

    private fun applyLayout(view: View, config: WidgetConfig) {
        view.x = (config.x * cellWidth).toFloat()
        view.y = (config.y * cellHeight).toFloat()
        view.layoutParams.width = config.width * cellWidth
        view.layoutParams.height = config.height * cellHeight
        
        // Update the widget's internal layout logic (e.g. speedometers)
        val card = view as? CardView
        val content = card?.getChildAt(0)
        if (content is UpdatableWidget) {
            content.updateConfig(config)
        }

        view.requestLayout()
        updateHandles(view)
    }

    private fun showAddWidgetDialog() {
        val types = WidgetType.entries.toTypedArray()
        AlertDialog.Builder(this).setItems(types.map { it.name }.toTypedArray()) { _, i ->
            val type = types[i]
            val newConfig = WidgetConfig(type, 0, 0, 1, 1, deviceIp = selectedDevice)
            currentLayout = currentLayout.copy(widgets = currentLayout.widgets + newConfig)
            refreshWidgets()
            showWidgetSettingsDialog(newConfig)
        }.show()
    }

    private fun isControlButton(type: WidgetType): Boolean {
        return type == WidgetType.ACTION_BUTTON
    }

    private fun saveWidget() {
        if (selectedDevice.isEmpty()) {
            Toast.makeText(this, "Выберите устройство", Toast.LENGTH_SHORT).show()
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val layoutJson = gson.toJson(currentLayout)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Case 1: Configuration through system widget picker
            getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE).edit()
                .putString("DEVICE_IP", selectedDevice)
                .putString("LAYOUT_JSON", layoutJson)
                .apply()

            PCAppWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            // Case 2: Request Pin from inside the app
            val myProvider = ComponentName(this, PCAppWidgetProvider::class.java)
            
            // This is the extra data that will be received by onReceive when the widget is pinned
            val bundle = Bundle().apply {
                putString("DEVICE_IP", selectedDevice)
                putString("LAYOUT_JSON", layoutJson)
            }
            
            val intent = Intent(this, PCAppWidgetProvider::class.java).apply {
                action = "com.example.pc.WIDGET_PINNED_SUCCESS"
                putExtras(bundle)
            }
            
            val successCallback = PendingIntent.getBroadcast(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            appWidgetManager.requestPinAppWidget(myProvider, bundle, successCallback)
            Toast.makeText(this, "Разместите виджет на экране", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Используйте системное меню виджетов", Toast.LENGTH_LONG).show()
        }
    }
}