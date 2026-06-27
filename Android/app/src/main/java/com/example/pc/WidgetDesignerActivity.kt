package com.example.pc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.cardview.widget.CardView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
                } catch (_: Exception) {
                    Log.w("Designer", "битый json в prefs, начнём с чистого")
                }
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
                
                val token = prefs.getString("TOKEN_$selectedDevice", null)
                
                webSocketManager?.disconnect()
                webSocketManager = WebSocketManager(
                    gson = gson,
                    token = token,
                    onStatsReceived = { stats ->
                        onStatsUpdated(stats)
                    }
                )
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
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
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
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(if (isRussian) "Удалить элемент?" else "Delete element?")
                        .setPositiveButton(if (isRussian) "Да" else "Yes") { _, _ ->
                            currentLayout = currentLayout.copy(widgets = currentLayout.widgets - config)
                            refreshWidgets()
                        }
                        .setNegativeButton(if (isRussian) "Нет" else "No", null)
                        .show()
                }
                canvas.addView(dh)
                deleteHandles[card] = dh
            }
            
            applyLayout(card, config)
        }
    }

    private fun createWidgetView(config: WidgetConfig): View {
        val view = WidgetFactory.create(
            config = config,
            context = this,
            isWidget = true,
            onVibrate = { vibrate() },
            onVolumeChange = { name, vol -> sendMixerVolume(name, vol) },
            onMediaCommand = { cmd -> sendMediaCommand(cmd) },
            onRunCommand = { path -> sendRunCommand(path) },
            onMinimizeCommand = { sendMinimizeCommand() },
            onCloseCommand = { name -> sendCloseAppCommand(name) },
            onKeyPressCommand = { keys -> sendKeyPressCommand(keys) }
        )
        
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

    private fun sendMediaCommand(cmd: String) {
        webSocketManager?.sendCommand("media_command", mapOf("cmd" to cmd))
    }

    private fun sendRunCommand(path: String) {
        webSocketManager?.sendCommand("run", mapOf("path" to path))
    }

    private fun sendMinimizeCommand() {
        webSocketManager?.sendCommand("minimize_app", emptyMap())
    }

    private fun sendCloseAppCommand(appName: String) {
        webSocketManager?.sendCommand("close_app", mapOf("name" to appName))
    }

    private fun sendKeyPressCommand(keys: List<String>) {
        webSocketManager?.sendCommand("key_press", mapOf("keys" to keys))
    }

    private fun showWidgetSettingsDialog(config: WidgetConfig) {
        if (config.type == WidgetType.ACTION_BUTTON) {
            showActionButtonConfigDialog(config) { updatedConfig ->
                config.label = updatedConfig.label
                config.action = updatedConfig.action
                config.useIcon = updatedConfig.useIcon
                config.theme = updatedConfig.theme
                config.actionMode = updatedConfig.actionMode
                config.keys = updatedConfig.keys
                refreshWidgets()
            }
            return
        }
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val view = layoutInflater.inflate(R.layout.dialog_theme_selector, null)
        val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)
        
        view.findViewById<TextView>(R.id.dialogTitle).text = if (isRussian) "Тема элемента:" else "Element Theme:"
        
        when (config.theme) {
            "TURQUOISE" -> rgTheme.check(R.id.rbTurquoise)
            "ORANGE" -> rgTheme.check(R.id.rbOrange)
            "GREEN" -> rgTheme.check(R.id.rbGreen)
            "PURPLE" -> rgTheme.check(R.id.rbPurple)
            else -> rgTheme.check(R.id.rbDefault)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Настройка элемента" else "Element Settings")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                config.theme = when (rgTheme.checkedRadioButtonId) {
                    R.id.rbTurquoise -> "TURQUOISE"
                    R.id.rbOrange -> "ORANGE"
                    R.id.rbGreen -> "GREEN"
                    R.id.rbPurple -> "PURPLE"
                    else -> null
                }
                refreshWidgets()
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
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
        
        val card = view as? CardView
        val content = card?.getChildAt(0)
        if (content is UpdatableWidget) {
            content.updateConfig(config)
        }

        view.requestLayout()
        updateHandles(view)
    }

    private fun showAddWidgetDialog() {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val types = WidgetType.entries.toTypedArray()
        
        val names = types.map { type ->
            if (isRussian) {
                when (type) {
                    WidgetType.CPU -> "Процессор (CPU)"
                    WidgetType.GPU -> "Видеокарта (GPU)"
                    WidgetType.RAM -> "Оперативная память"
                    WidgetType.STORAGE -> "Накопитель"
                    WidgetType.NETWORK -> "Сеть"
                    WidgetType.COOLING -> "Охлаждение"
                    WidgetType.TOP_PROCESSES -> "Топ процессов"
                    WidgetType.CONTROLS -> "Управление"
                    WidgetType.MEDIA_PLAYER -> "Медиаплеер"
                    WidgetType.AUDIO_MIXER -> "Микшер"
                    WidgetType.ACTION_BUTTON -> "Кнопка действия"
                    else -> type.name
                }
            } else {
                type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Добавить элемент" else "Add Element")
            .setItems(names) { _, i ->
                val type = types[i]
                if (type == WidgetType.ACTION_BUTTON) {
                    showActionButtonConfigDialog(null) { newConfig ->
                        newConfig.deviceIp = selectedDevice
                        currentLayout = currentLayout.copy(widgets = currentLayout.widgets + newConfig)
                        refreshWidgets()
                    }
                } else {
                    val newConfig = when (type) {
                        WidgetType.CONTROLS -> WidgetConfig(type, 0, 0, 2, 2, deviceIp = selectedDevice)
                        WidgetType.MEDIA_PLAYER -> WidgetConfig(type, 0, 0, 2, 2, deviceIp = selectedDevice)
                        WidgetType.AUDIO_MIXER -> WidgetConfig(type, 0, 0, 2, 2, deviceIp = selectedDevice)
                        else -> WidgetConfig(type, 0, 0, 1, 1, deviceIp = selectedDevice)
                    }
                    currentLayout = currentLayout.copy(widgets = currentLayout.widgets + newConfig)
                    refreshWidgets()
                    showWidgetSettingsDialog(newConfig)
                }
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun showActionButtonConfigDialog(config: WidgetConfig?, onSave: (WidgetConfig) -> Unit) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val view = layoutInflater.inflate(R.layout.dialog_widget_settings, null)
        
        val etLabel = view.findViewById<EditText>(R.id.etLabel)
        val etAction = view.findViewById<EditText>(R.id.etAction)
        val cbUseIcon = view.findViewById<CheckBox>(R.id.cbUseIcon)
        val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)
        val rgActionMode = view.findViewById<RadioGroup>(R.id.rgActionMode)
        val launchGroup = view.findViewById<LinearLayout>(R.id.launchGroup)
        val keypressGroup = view.findViewById<LinearLayout>(R.id.keypressGroup)
        val keysContainer = view.findViewById<LinearLayout>(R.id.keysContainer)
        val btnAddKey = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddKey)
        val tvModeTitle = view.findViewById<TextView>(R.id.tvModeTitle)
        val tvKeysTitle = view.findViewById<TextView>(R.id.tvKeysTitle)

        view.findViewById<android.widget.RadioButton>(R.id.rbLaunch).text = if (isRussian) "Запуск" else "Launch"
        view.findViewById<android.widget.RadioButton>(R.id.rbKeypress).text = if (isRussian) "Нажатие" else "Keypress"
        tvModeTitle.text = if (isRussian) "Режим:" else "Mode:"
        tvKeysTitle.text = if (isRussian) "Комбинация клавиш:" else "Key combination:"
        btnAddKey.text = if (isRussian) "+ Добавить клавишу" else "+ Add key"
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.labelInputLayout).hint = if (isRussian) "Название / Метка" else "Label"
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.actionInputLayout).hint = if (isRussian) "Путь / Действие" else "Path / Action"
        cbUseIcon.text = if (isRussian) "Использовать иконку вместо текста" else "Use icon instead of text"

        val selectedKeys = mutableListOf<String>()

        etLabel.setText(config?.label ?: "")
        etAction.setText(config?.action ?: "")
        cbUseIcon.isChecked = config?.useIcon ?: false
        config?.keys?.let { selectedKeys.addAll(it) }
        
        when (config?.theme) {
            "TURQUOISE" -> rgTheme.check(R.id.rbTurquoise)
            "ORANGE" -> rgTheme.check(R.id.rbOrange)
            "GREEN" -> rgTheme.check(R.id.rbGreen)
            "PURPLE" -> rgTheme.check(R.id.rbPurple)
            else -> rgTheme.check(R.id.rbDefault)
        }

        fun refreshKeysUI() {
            keysContainer.removeAllViews()
            selectedKeys.forEachIndexed { index, key ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 4)
                }
                val label = TextView(this).apply {
                    text = if (index > 0) " + $key" else key
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnRemove = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    text = "✕"
                    textSize = 14f
                    setOnClickListener {
                        selectedKeys.removeAt(index)
                        refreshKeysUI()
                    }
                }
                row.addView(label)
                row.addView(btnRemove)
                keysContainer.addView(row)
            }
        }

        fun switchMode(isKeypress: Boolean) {
            launchGroup.visibility = if (isKeypress) android.view.View.GONE else android.view.View.VISIBLE
            keypressGroup.visibility = if (isKeypress) android.view.View.VISIBLE else android.view.View.GONE
        }

        val isKeypress = config?.actionMode == "keypress"
        if (isKeypress) rgActionMode.check(R.id.rbKeypress) else rgActionMode.check(R.id.rbLaunch)
        switchMode(isKeypress)
        refreshKeysUI()

        rgActionMode.setOnCheckedChangeListener { _, checkedId ->
            switchMode(checkedId == R.id.rbKeypress)
        }

        btnAddKey.setOnClickListener {
            val keys = getAvailableKeys()
            val keyNames = keys.map { it.second }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(if (isRussian) "Выберите клавишу" else "Select key")
                .setItems(keyNames) { _, which ->
                    selectedKeys.add(keys[which].first)
                    refreshKeysUI()
                }
                .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
                .show()
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Настройка кнопки" else "Button Settings")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                val selectedTheme = when (rgTheme.checkedRadioButtonId) {
                    R.id.rbTurquoise -> "TURQUOISE"
                    R.id.rbOrange -> "ORANGE"
                    R.id.rbGreen -> "GREEN"
                    R.id.rbPurple -> "PURPLE"
                    else -> null
                }
                val mode = if (rgActionMode.checkedRadioButtonId == R.id.rbKeypress) "keypress" else "launch"
                val result = WidgetConfig(
                    type = WidgetType.ACTION_BUTTON,
                    x = config?.x ?: 0,
                    y = config?.y ?: 0,
                    width = config?.width ?: 1,
                    height = config?.height ?: 1,
                    label = etLabel.text.toString(),
                    action = if (mode == "launch") etAction.text.toString() else null,
                    useIcon = if (mode == "launch") cbUseIcon.isChecked else false,
                    theme = selectedTheme,
                    actionMode = mode,
                    keys = if (mode == "keypress") selectedKeys.toList() else null
                )
                onSave(result)
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun getAvailableKeys(): List<Pair<String, String>> {
        return listOf(
            "ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win",
            "a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E",
            "f" to "F", "g" to "G", "h" to "H", "i" to "I", "j" to "J",
            "k" to "K", "l" to "L", "m" to "M", "n" to "N", "o" to "O",
            "p" to "P", "q" to "Q", "r" to "R", "s" to "S", "t" to "T",
            "u" to "U", "v" to "V", "w" to "W", "x" to "X", "y" to "Y", "z" to "Z",
            "0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
            "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9",
            "f1" to "F1", "f2" to "F2", "f3" to "F3", "f4" to "F4",
            "f5" to "F5", "f6" to "F6", "f7" to "F7", "f8" to "F8",
            "f9" to "F9", "f10" to "F10", "f11" to "F11", "f12" to "F12",
            "up" to "↑ Up", "down" to "↓ Down", "left" to "← Left", "right" to "→ Right",
            "enter" to "Enter", "space" to "Space", "tab" to "Tab",
            "escape" to "Escape", "backspace" to "Backspace", "delete" to "Delete",
            "home" to "Home", "end" to "End", "pageup" to "Page Up", "pagedown" to "Page Down",
            "insert" to "Insert", "printscreen" to "Print Screen", "pause" to "Pause",
            "volumeup" to "Volume Up", "volumedown" to "Volume Down", "volumemute" to "Volume Mute",
            "playpause" to "Play/Pause", "nexttrack" to "Next Track", "prevtrack" to "Prev Track"
        )
    }


    private fun saveWidget() {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        if (selectedDevice.isEmpty()) {
            val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
            val devices = (prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()).toList()
            
            if (devices.isEmpty()) {
                Toast.makeText(this, if (isRussian) "Сначала добавьте устройство в главном меню" else "Add a device in the main menu first", Toast.LENGTH_LONG).show()
                return
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(if (isRussian) "Выберите устройство" else "Select Device")
                .setItems(devices.toTypedArray()) { _, which ->
                    selectedDevice = devices[which]
                    saveWidget() // Retry saving
                }
                .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
                .show()
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val layoutJson = gson.toJson(currentLayout)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            getSharedPreferences("WIDGET_PREFS_$appWidgetId", Context.MODE_PRIVATE).edit()
                .putString("DEVICE_IP", selectedDevice)
                .putString("LAYOUT_JSON", layoutJson)
                .apply()

            MainScope().launch {
                PCGlanceWidget().update(this@WidgetDesignerActivity, androidx.glance.appwidget.GlanceAppWidgetManager(this@WidgetDesignerActivity).getGlanceIdBy(appWidgetId))
            }

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            val myProvider = ComponentName(this, PCGlanceWidgetReceiver::class.java)
            
            val bundle = Bundle().apply {
                putString("DEVICE_IP", selectedDevice)
                putString("LAYOUT_JSON", layoutJson)
            }
            
            val intent = Intent(this, PCGlanceWidgetReceiver::class.java).apply {
                action = "com.example.pc.WIDGET_PINNED_SUCCESS"
                putExtras(bundle)
            }
            
            val successCallback = PendingIntent.getBroadcast(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            appWidgetManager.requestPinAppWidget(myProvider, bundle, successCallback)
            Toast.makeText(this, if (isRussian) "Разместите виджет на экране" else "Place the widget on the screen", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, if (isRussian) "Используйте системное меню виджетов" else "Use the system widgets menu", Toast.LENGTH_LONG).show()
        }
    }
}