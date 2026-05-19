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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class CustomDashboardActivity : BaseActivity() {

    private lateinit var dashboardCanvas: FrameLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var editDashboardButton: Button
    private lateinit var addWidgetButton: Button
    private lateinit var addWidgetCard: CardView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_dashboard)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        hideSystemUI()

        dashboardCanvas = findViewById(R.id.dashboard_canvas)
        controlPanel = findViewById(R.id.control_panel)
        editDashboardButton = findViewById(R.id.edit_dashboard_button)
        addWidgetButton = findViewById(R.id.add_widget_button)
        addWidgetCard = findViewById(R.id.add_widget_card)

        editDashboardButton.setOnClickListener {
            vibrate()
            toggleEditMode() 
        }
        addWidgetButton.setOnClickListener { 
            vibrate()
            showAddWidgetDialog() 
        }

        dashboardCanvas.post {
            cellWidth = dashboardCanvas.width / gridColumns
            cellHeight = dashboardCanvas.height / gridRows
            dashboardCanvas.background = GridDrawable()
            testLayout = loadDashboardLayout()
            displayDashboard(testLayout)
        }
    }

    override fun onStatsUpdated(stats: PCStats) {
        currentStats = stats
        widgetViews.keys.forEach { (it as? UpdatableWidget)?.updateData(stats) }
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
        editDashboardButton.text = if (isEditMode) "✓" else "✎"
        addWidgetCard.visibility = if (isEditMode) View.VISIBLE else View.GONE
        dashboardCanvas.invalidate()
        resizeHandles.values.forEach { it.visibility = if (isEditMode) View.VISIBLE else View.GONE }
        deleteHandles.values.forEach { it.visibility = if (isEditMode) View.VISIBLE else View.GONE }
    }

    private fun displayDashboard(layout: DashboardLayout) {
        dashboardCanvas.removeAllViews()
        widgetViews.clear()
        resizeHandles.clear()
        deleteHandles.clear()

        layout.widgets.forEach { config ->
            val widgetView = createWidget(config) ?: return@forEach
            widgetView.id = View.generateViewId()
            dashboardCanvas.addView(widgetView, 0)
            widgetViews[widgetView] = config

            setupDragAndDrop(widgetView)

            val themeColor = try {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                typedValue.data
            } catch (e: Exception) { Color.BLUE }

            val rh = createHandle(widgetView, android.R.drawable.ic_menu_crop, themeColor, 0.5f) { e -> handleResize(widgetView, e) }
            dashboardCanvas.addView(rh)
            resizeHandles[widgetView] = rh

            val dh = createHandle(widgetView, android.R.drawable.ic_menu_delete, Color.parseColor("#80FF4040"), 1f) { showDeleteDialog(widgetView) }
            dashboardCanvas.addView(dh)
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
        AlertDialog.Builder(this).setTitle("Удалить?").setMessage("Удалить виджет?").setPositiveButton("Да") { _, _ ->
            vibrate()
            widgetViews[widgetView]?.let { config ->
                testLayout = testLayout.copy(widgets = testLayout.widgets - config)
                displayDashboard(testLayout)
            }
        }.setNegativeButton("Нет", null).show()
    }

    private fun showAddWidgetDialog() {
        val types = WidgetType.entries.toTypedArray()
        val names = types.map { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Добавить виджет").setItems(names) { _, which ->
            vibrate()
            val type = types[which]
            if (type == WidgetType.ACTION_BUTTON) {
                showActionButtonConfigDialog { label, path, useIcon ->
                    val new = WidgetConfig(type, 0, 0, 2, 2, label, path, useIcon)
                    testLayout = testLayout.copy(widgets = testLayout.widgets + new)
                    displayDashboard(testLayout)
                }
            } else {
                val new = when(type) {
                    WidgetType.MEDIA_PLAYER -> WidgetConfig(type, 0, 0, 4, 2)
                    else -> WidgetConfig(type, 0, 0, 3, 2)
                }
                testLayout = testLayout.copy(widgets = testLayout.widgets + new)
                displayDashboard(testLayout)
            }
        }.setNegativeButton("Отмена", null).show()
    }

    private fun showActionButtonConfigDialog(onSave: (String, String, Boolean) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val editLabel = EditText(this).apply { hint = "Название кнопки (например, Steam)" }
        val editPath = EditText(this).apply { hint = "Путь к файлу или URL" }
        val checkUseIcon = CheckBox(this).apply { 
            text = "Иконка вместо названия"
            setPadding(0, 24, 0, 0)
        }
        
        layout.addView(editLabel)
        layout.addView(editPath)
        layout.addView(checkUseIcon)

        AlertDialog.Builder(this)
            .setTitle("Настройка кнопки")
            .setView(layout)
            .setPositiveButton("Добавить") { _, _ ->
                vibrate()
                onSave(editLabel.text.toString(), editPath.text.toString(), checkUseIcon.isChecked)
            }
            .setNegativeButton("Отмена", null)
            .show()
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
            onCloseCommand = ::sendCloseAppCommand
        ) as? CardView
    }

    private fun sendRunCommand(path: String) {
        webSocketManager?.sendCommand("run", mapOf("path" to path))
        Toast.makeText(this, "Команда отправлена", Toast.LENGTH_SHORT).show()
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
