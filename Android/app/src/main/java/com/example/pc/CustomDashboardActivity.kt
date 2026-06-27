package com.example.pc

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class CustomDashboardActivity : BaseActivity() {

    private lateinit var canvas: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var btnEdit: Button
    private lateinit var btnAdd: Button
    private lateinit var addCard: CardView

    private var isEditMode = false
    private lateinit var testLayout: DashboardLayout
    private val widgetViews = mutableMapOf<View, WidgetConfig>()
    private val resizeHandles = mutableMapOf<View, View>()
    private val deleteHandles = mutableMapOf<View, View>()

    private val gridColumns = 12
    private val gridRows = 8
    private var cellWidth: Int = 0
    private var cellHeight: Int = 0
    private var dX = 0f
    private var dY = 0f
    private var startWidth = 0
    private var startHeight = 0

    private val prefsName = "DashboardPrefs"
    private val layoutKey = "dashboardLayout"

    private var currentStats: PCStats? = null
    private var deviceIp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_dashboard)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        deviceIp = intent.getStringExtra("DEVICE_IP") ?: ""
        hideSystemUI()

        canvas = findViewById(R.id.dashboard_canvas)
        controls = findViewById(R.id.control_panel)
        btnEdit = findViewById(R.id.edit_dashboard_button)
        btnAdd = findViewById(R.id.add_widget_button)
        addCard = findViewById(R.id.add_widget_card)

        btnEdit.setOnClickListener {
            vibrate()
            toggleEditMode() 
        }
        btnAdd.setOnClickListener { 
            vibrate()
            showAddWidgetDialog() 
        }

        canvas.post {
            cellWidth = canvas.width / gridColumns
            cellHeight = canvas.height / gridRows
            canvas.background = GridDrawable()
            testLayout = loadDashboardLayout()
            displayDashboard(testLayout)
        }
    }

    override fun onStatsUpdated(stats: PCStats) {
        currentStats = stats
        widgetViews.keys.forEach { (it as? UpdatableWidget)?.updateData(stats) }
    }

    override fun onStatusChanged(isOnline: Boolean) {
        if (!isOnline) {
            widgetViews.keys.forEach { (it as? UpdatableWidget)?.setOffline() }
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onStop() {
        super.onStop()
        saveDashboardLayout()
    }

    private fun saveDashboardLayout() {
        if (::testLayout.isInitialized) {
            val json = gson.toJson(testLayout)
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(layoutKey, json).apply()
        }
    }

    private fun loadDashboardLayout(): DashboardLayout {
        val json = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(layoutKey, null)
        return json?.let { gson.fromJson(it, DashboardLayout::class.java) } ?: DashboardLayout("My Dashboard", emptyList())
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (!isEditMode) saveDashboardLayout()
        btnEdit.text = if (isEditMode) "✓" else "✎"
        addCard.visibility = if (isEditMode) View.VISIBLE else View.GONE
        canvas.invalidate()
        resizeHandles.values.forEach { it.visibility = if (isEditMode) View.VISIBLE else View.GONE }
        deleteHandles.values.forEach { it.visibility = if (isEditMode) View.VISIBLE else View.GONE }
    }

    private fun displayDashboard(layout: DashboardLayout) {
        canvas.removeAllViews()
        widgetViews.clear()
        resizeHandles.clear()
        deleteHandles.clear()

        layout.widgets.forEach { config ->
            val widgetView = createWidget(config) ?: return@forEach
            widgetView.id = View.generateViewId()
            canvas.addView(widgetView, 0)
            widgetViews[widgetView] = config

            setupDragAndDrop(widgetView)

            val themeColor = try {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                typedValue.data
            } catch (_: Exception) { Color.BLUE }

            val rh = createHandle(widgetView, android.R.drawable.ic_menu_crop, themeColor, 0.5f) { e -> handleResize(widgetView, e) }
            canvas.addView(rh)
            resizeHandles[widgetView] = rh

            val dh = createHandle(widgetView, android.R.drawable.ic_menu_delete, Color.parseColor("#80FF4040"), 1f) { showDeleteDialog(widgetView) }
            canvas.addView(dh)
            deleteHandles[widgetView] = dh

            applyWidgetLayout(widgetView, config, false)
            currentStats?.let { (widgetView as? UpdatableWidget)?.updateData(it) }
        }
    }

    private fun createHandle(widgetView: View, resId: Int, bgColor: Int, alphaVal: Float, onTouch: (MotionEvent) -> Unit): View {
        return ImageView(this).apply {
            setImageResource(resId)
            setBackgroundColor(bgColor)
            alpha = alphaVal
            visibility = if (isEditMode) View.VISIBLE else View.GONE
            elevation = 10 * resources.displayMetrics.density
            setOnTouchListener { _, event -> onTouch(event); true }
        }
    }

    private fun showDeleteDialog(widgetView: View) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Удалить?" else "Delete?")
            .setMessage(if (isRussian) "Удалить виджет?" else "Delete widget?")
            .setPositiveButton(if (isRussian) "Да" else "Yes") { _, _ ->
                vibrate()
                widgetViews[widgetView]?.let { config ->
                    testLayout = testLayout.copy(widgets = testLayout.widgets - config)
                    displayDashboard(testLayout)
                }
            }
            .setNegativeButton(if (isRussian) "Нет" else "No", null)
            .show()
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
                    WidgetType.STORAGE -> "Хранилище"
                    WidgetType.NETWORK -> "Сеть"
                    WidgetType.COOLING -> "Охлаждение"
                    WidgetType.MEDIA_PLAYER -> "Медиаплеер"
                    WidgetType.AUDIO_MIXER -> "Микшер громкости"
                    WidgetType.CONTROLS -> "Управление"
                    WidgetType.TOP_PROCESSES -> "Топ процессов"
                    WidgetType.ACTION_BUTTON -> "Кнопка действия"
                    else -> type.name
                }
            } else {
                type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Добавить виджет" else "Add Widget")
            .setItems(names) { _, which ->
                vibrate()
                val type = types[which]
                if (type == WidgetType.ACTION_BUTTON) {
                    showActionButtonConfigDialog(null) { config ->
                        testLayout = testLayout.copy(widgets = testLayout.widgets + config)
                        displayDashboard(testLayout)
                    }
                } else {
                    showWidgetConfigDialog(type) { config ->
                        testLayout = testLayout.copy(widgets = testLayout.widgets + config)
                        displayDashboard(testLayout)
                    }
                }
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun showWidgetConfigDialog(type: WidgetType, onSave: (WidgetConfig) -> Unit) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val view = layoutInflater.inflate(R.layout.dialog_theme_selector, null)
        val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)
        val container = view as LinearLayout
        
        view.findViewById<TextView>(R.id.dialogTitle).text = if (isRussian) "Тема виджета:" else "Widget Theme:"

        var checkDefault: CheckBox? = null
        if (type == WidgetType.MEDIA_PLAYER) {
            val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
            val defaultMediaIp = prefs.getString("DEFAULT_MEDIA_IP", "")
            checkDefault = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = if (isRussian) "Использовать по умолчанию для шторки" else "Use as default for media shade"
                isChecked = deviceIp == defaultMediaIp
                setPadding(0, 24, 0, 0)
            }
            container.addView(checkDefault)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Настройка виджета" else "Widget Settings")
            .setView(view)
            .setPositiveButton(if (isRussian) "Добавить" else "Add") { _, _ ->
                vibrate()
                
                if (type == WidgetType.MEDIA_PLAYER && checkDefault?.isChecked == true) {
                    getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
                        .edit()
                        .putString("DEFAULT_MEDIA_IP", deviceIp)
                        .apply()
                    PCForegroundService.refresh(this@CustomDashboardActivity)
                }

                val selectedTheme = when (rgTheme.checkedRadioButtonId) {
                    R.id.rbTurquoise -> "TURQUOISE"
                    R.id.rbOrange -> "ORANGE"
                    R.id.rbGreen -> "GREEN"
                    R.id.rbPurple -> "PURPLE"
                    else -> null
                }

                val config = when(type) {
                    WidgetType.MEDIA_PLAYER -> WidgetConfig(type, 0, 0, 4, 2, theme = selectedTheme)
                    WidgetType.CONTROLS -> WidgetConfig(type, 0, 0, 2, 2, theme = selectedTheme)
                    else -> WidgetConfig(type, 0, 0, 3, 2, theme = selectedTheme)
                }
                onSave(config)
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun showActionButtonConfigDialog(existing: WidgetConfig?, onSave: (WidgetConfig) -> Unit) {
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
        (view.findViewById<CheckBox>(R.id.cbUseIcon)).text = if (isRussian) "Использовать иконку вместо текста" else "Use icon instead of text"

        val selectedKeys = mutableListOf<String>()

        // Заполнение при редактировании
        etLabel.setText(existing?.label ?: "")
        etAction.setText(existing?.action ?: "")
        cbUseIcon.isChecked = existing?.useIcon ?: false
        existing?.keys?.let { selectedKeys.addAll(it) }
        when (existing?.theme) {
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

        // Инициализация режима
        val isKeypress = existing?.actionMode == "keypress"
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
            .setPositiveButton(if (isRussian) "Добавить" else "Add") { _, _ ->
                vibrate()
                val selectedTheme = when (rgTheme.checkedRadioButtonId) {
                    R.id.rbTurquoise -> "TURQUOISE"
                    R.id.rbOrange -> "ORANGE"
                    R.id.rbGreen -> "GREEN"
                    R.id.rbPurple -> "PURPLE"
                    else -> null
                }
                val mode = if (rgActionMode.checkedRadioButtonId == R.id.rbKeypress) "keypress" else "launch"
                val config = WidgetConfig(
                    type = WidgetType.ACTION_BUTTON,
                    x = existing?.x ?: 0,
                    y = existing?.y ?: 0,
                    width = existing?.width ?: 2,
                    height = existing?.height ?: 2,
                    label = etLabel.text.toString(),
                    action = if (mode == "launch") etAction.text.toString() else null,
                    useIcon = if (mode == "launch") cbUseIcon.isChecked else false,
                    theme = selectedTheme,
                    actionMode = mode,
                    keys = if (mode == "keypress") selectedKeys.toList() else null
                )
                onSave(config)
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun getAvailableKeys(): List<Pair<String, String>> {
        return listOf(
            // Модификаторы
            "ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win",
            // Буквы
            "a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E",
            "f" to "F", "g" to "G", "h" to "H", "i" to "I", "j" to "J",
            "k" to "K", "l" to "L", "m" to "M", "n" to "N", "o" to "O",
            "p" to "P", "q" to "Q", "r" to "R", "s" to "S", "t" to "T",
            "u" to "U", "v" to "V", "w" to "W", "x" to "X", "y" to "Y", "z" to "Z",
            // Цифры
            "0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
            "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9",
            // F-клавиши
            "f1" to "F1", "f2" to "F2", "f3" to "F3", "f4" to "F4",
            "f5" to "F5", "f6" to "F6", "f7" to "F7", "f8" to "F8",
            "f9" to "F9", "f10" to "F10", "f11" to "F11", "f12" to "F12",
            // Стрелки
            "up" to "↑ Up", "down" to "↓ Down", "left" to "← Left", "right" to "→ Right",
            // Специальные
            "enter" to "Enter", "space" to "Space", "tab" to "Tab",
            "escape" to "Escape", "backspace" to "Backspace", "delete" to "Delete",
            "home" to "Home", "end" to "End", "pageup" to "Page Up", "pagedown" to "Page Down",
            "insert" to "Insert", "printscreen" to "Print Screen", "pause" to "Pause",
            // Мультимедиа
            "volumeup" to "Volume Up", "volumedown" to "Volume Down", "volumemute" to "Volume Mute",
            "playpause" to "Play/Pause", "nexttrack" to "Next Track", "prevtrack" to "Prev Track"
        )
    }

    private fun setupDragAndDrop(view: View) {
        view.setOnTouchListener { v, event -> if (isEditMode) { handleDragAndDrop(v, event); true } else false }
    }

    private fun handleDragAndDrop(view: View, event: MotionEvent) {
        val rh = resizeHandles[view]!!
        val dh = deleteHandles[view]!!
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                vibrate(5)
                dX = view.x - event.rawX
                dY = view.y - event.rawY
                view.bringToFront(); rh.bringToFront(); dh.bringToFront()
            }
            MotionEvent.ACTION_MOVE -> {
                val nx = event.rawX + dX; val ny = event.rawY + dY
                view.x = nx; view.y = ny
                rh.x = nx + view.width - rh.width; rh.y = ny + view.height - rh.height
                dh.x = nx; dh.y = ny
            }
            MotionEvent.ACTION_UP -> {
                vibrate(5)
                val cfg = widgetViews[view] ?: return
                val gx = (view.x / cellWidth).roundToInt().coerceIn(0, gridColumns - cfg.width)
                val gy = (view.y / cellHeight).roundToInt().coerceIn(0, gridRows - cfg.height)
                updateWidgetConfig(view, cfg.copy(x = gx, y = gy))
            }
        }
    }

    private fun handleResize(view: View, event: MotionEvent) {
        val lp = view.layoutParams
        val rh = resizeHandles[view]!!
        val dh = deleteHandles[view]!!
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                vibrate(5)
                dX = event.rawX; dY = event.rawY
                startWidth = lp.width; startHeight = lp.height
                view.bringToFront(); rh.bringToFront(); dh.bringToFront()
            }
            MotionEvent.ACTION_MOVE -> {
                lp.width = (startWidth + (event.rawX - dX)).toInt().coerceAtLeast(cellWidth / 2)
                lp.height = (startHeight + (event.rawY - dY)).toInt().coerceAtLeast(cellHeight / 2)
                view.layoutParams = lp
                rh.x = view.x + lp.width - rh.width; rh.y = view.y + lp.height - rh.height
                dh.x = view.x; dh.y = view.y
            }
            MotionEvent.ACTION_UP -> {
                vibrate(5)
                val cfg = widgetViews[view] ?: return
                val nw = ((lp.width + 16) / cellWidth.toFloat()).roundToInt().coerceIn(1, gridColumns - cfg.x)
                val nh = ((lp.height + 16) / cellHeight.toFloat()).roundToInt().coerceIn(1, gridRows - cfg.y)
                updateWidgetConfig(view, cfg.copy(width = nw, height = nh))
            }
        }
    }

    private fun updateWidgetConfig(view: View, new: WidgetConfig) {
        val list = testLayout.widgets.toMutableList()
        val idx = list.indexOf(widgetViews[view])
        if (idx != -1) {
            list[idx] = new
            testLayout = testLayout.copy(widgets = list)
            widgetViews[view] = new
            (view as? UpdatableWidget)?.updateConfig(new)
            applyWidgetLayout(view, new, true)
        }
    }

    private fun applyWidgetLayout(view: View, config: WidgetConfig, anim: Boolean) {
        val gap = (4 * resources.displayMetrics.density).toInt()
        
        val targetWidth = config.width * cellWidth - (gap * 2)
        val targetHeight = config.height * cellHeight - (gap * 2)
        val targetX = (config.x * cellWidth).toFloat() + gap
        val targetY = (config.y * cellHeight).toFloat() + gap

        view.layoutParams.width = targetWidth
        view.layoutParams.height = targetHeight
        
        val hSize = 60
        val rh = resizeHandles[view]
        val dh = deleteHandles[view]
        
        rh?.layoutParams?.width = hSize; rh?.layoutParams?.height = hSize
        dh?.layoutParams?.width = hSize; dh?.layoutParams?.height = hSize

        if (anim) {
            view.animate().x(targetX).y(targetY).setDuration(200).start()
            rh?.animate()?.x(targetX + targetWidth - hSize)?.y(targetY + targetHeight - hSize)?.setDuration(200)?.start()
            dh?.animate()?.x(targetX)?.y(targetY)?.setDuration(200)?.start()
        } else {
            view.x = targetX; view.y = targetY
            rh?.x = targetX + targetWidth - hSize; rh?.y = targetY + targetHeight - hSize
            dh?.x = targetX; dh?.y = targetY
        }
        view.requestLayout(); rh?.requestLayout(); dh?.requestLayout()
    }

    private fun createWidget(config: WidgetConfig): CardView? {
        return WidgetFactory.create(
            config = config,
            context = this,
            onVibrate = { vibrate() },
            onScreenshot = ::showScreenshotDialog,
            onMicMute = ::sendMicMute,
            onSleep = ::sendSleepCommand,
            onShutdown = ::sendShutdownCommand,
            onVolumeChange = ::sendMixerVolume,
            onKill = { pid -> killProcess(pid) },
            onRunCommand = { path -> sendRunCommand(path) },
            onMediaCommand = { cmd -> sendMediaCommand(cmd) },
            onMinimizeCommand = ::sendMinimizeCommand,
            onCloseCommand = ::sendCloseAppCommand,
            onKeyPressCommand = { keys -> sendKeyPressCommand(keys) }
        ) as? CardView
    }

    private fun sendRunCommand(path: String) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        webSocketManager?.sendCommand("run", mapOf("path" to path))
        Toast.makeText(this, if (isRussian) "Команда отправлена" else "Command sent", Toast.LENGTH_SHORT).show()
    }

    private fun sendKeyPressCommand(keys: List<String>) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        webSocketManager?.sendCommand("key_press", mapOf("keys" to keys))
        Toast.makeText(this, if (isRussian) "Нажатие отправлено" else "Keypress sent", Toast.LENGTH_SHORT).show()
    }

    private fun sendMinimizeCommand() {
        webSocketManager?.sendCommand("minimize_app", emptyMap())
    }

    private fun sendCloseAppCommand(appName: String) {
        webSocketManager?.sendCommand("close_app", mapOf("name" to appName))
    }

    private fun sendMediaCommand(cmd: String) {
        webSocketManager?.sendCommand("media_command", mapOf("cmd" to cmd))
    }

    private inner class GridDrawable : android.graphics.drawable.Drawable() {
        private val p = Paint().apply { 
            color = try {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                typedValue.data
            } catch (e: Exception) { Color.BLUE }
            alpha = 40
            strokeWidth = 2f
            style = Paint.Style.STROKE 
        }
        override fun draw(c: Canvas) {
            if (!isEditMode) return
            val cw = bounds.width() / gridColumns.toFloat(); val ch = bounds.height() / gridRows.toFloat()
            for (i in 1 until gridColumns) c.drawLine(i * cw, 0f, i * cw, bounds.height().toFloat(), p)
            for (i in 1 until gridRows) c.drawLine(0f, i * ch, bounds.width().toFloat(), i * ch, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
