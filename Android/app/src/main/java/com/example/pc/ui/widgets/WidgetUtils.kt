package com.example.pc.ui.widgets

import com.example.pc.*
import com.example.pc.data.*
import com.example.pc.network.*
import com.example.pc.ui.*
import com.example.pc.ui.widgets.*

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable


fun Context.getThemeColor(attr: Int): Int {
    val typedValue = TypedValue()
    if (theme.resolveAttribute(attr, typedValue, true)) {
        return typedValue.data
    }
    return Color.parseColor("#BB86FC")
}


fun Context.getWidgetColor(themeName: String?): Int {
    return when (themeName) {
        "TURQUOISE" -> ContextCompat.getColor(this, R.color.turquoise)
        "ORANGE" -> ContextCompat.getColor(this, R.color.neon_orange)
        "GREEN" -> ContextCompat.getColor(this, R.color.matrix_green)
        "PURPLE" -> ContextCompat.getColor(this, R.color.purple)
        else -> getThemeColor(androidx.appcompat.R.attr.colorPrimary)
    }
}


fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}


fun formatDeviceName(name: String): String {
    return name
        .replace("with Radeon Graphics", "", ignoreCase = true)
        .replace("Processor", "", ignoreCase = true)
        .replace("Graphics", "", ignoreCase = true)
        .replace("Core(TM)", "", ignoreCase = true)
        .replace("AMD", "", ignoreCase = true)
        .replace("NVIDIA", "", ignoreCase = true)
        .replace("Intel(R)", "", ignoreCase = true)
        .replace("  ", " ")
        .trim()
}

abstract class BaseWidgetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr), UpdatableWidget {
    init {
        radius = 16f.dpToPx(context)
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
        elevation = 4f.dpToPx(context)
        val p = 8f.dpToPx(context).toInt()
        setContentPadding(p, p, p, p)
    }

    protected fun Float.dpToPx(context: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, context.resources.displayMetrics)
}


fun setup(
        config: WidgetConfig,
        onVibrate: () -> Unit,
        onRun: (String) -> Unit,
        onMinimize: () -> Unit,
        onClose: (String) -> Unit,
        onKeyPress: ((List<String>) -> Unit)? = null
    ) {
        this.config = config
        updateUI()
        
        button.setOnClickListener {
            onVibrate()
            if (config.actionMode == "keypress") {
                val keys = config.keys
                if (!keys.isNullOrEmpty()) {
                    onKeyPress?.invoke(keys)
                }
            } else {
                val path = config.action ?: return@setOnClickListener
                if (appState == 2) {
                    onMinimize()
                } else {
                    onRun(path)
                }
            }
        }

        button.setOnLongClickListener {
            if (config.actionMode != "keypress") {
                onVibrate()
                config.action?.let { path ->
                    val fileName = path.split("\\", "/").last().lowercase()
                    onClose(fileName)
                }
            }
            true
        }
    }

    fun setIconBitmap(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null) {
            iconView.setImageBitmap(bitmap)
            iconView.visibility = View.VISIBLE
            button.text = ""
        }
    }

    private fun updateUI() {
        val cfg = config ?: return
        if (cfg.actionMode == "keypress") {
            // В режиме нажатия показываем метку или комбинацию клавиш
            iconView.visibility = View.GONE
            try {
                Glide.with(context.applicationContext).clear(iconView)
            } catch (e: Exception) {}
            val keysText = cfg.keys?.joinToString(" + ") { it.uppercase() }
            button.text = cfg.label?.takeIf { it.isNotEmpty() } ?: keysText ?: "Key"
        } else if (cfg.useIcon && !cfg.action.isNullOrEmpty()) {
            button.text = ""
            iconView.visibility = View.VISIBLE
            
            val iconUrl = getIconUrl(context, cfg)
            
            Log.d("ActionButton", "Loading icon: $iconUrl")

            try {
                Glide.with(context.applicationContext)
                    .load(iconUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(iconView)
            } catch (e: Exception) {
                Log.e("ActionButton", "Glide error", e)
            }
        } else {
            button.text = cfg.label ?: "Action"
            iconView.visibility = View.GONE
            try {
                Glide.with(context.applicationContext).clear(iconView)
            } catch (e: Exception) {}
        }
    }

    companion object {
        fun getIconUrl(context: Context, cfg: WidgetConfig): String {
            val action = cfg.action ?: ""
            return if (action.startsWith("http://") || action.startsWith("https://")) {
                val host = Uri.parse(action).host ?: action
                "https://www.google.com/s2/favicons?domain=$host&sz=128"
            } else {
                val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
                val deviceIp = cfg.deviceIp ?: prefs.getString("SERVER_IP", "192.168.1.100") ?: "192.168.1.100"
                val encodedPath = Uri.encode(action)
                "http://$deviceIp:5000/icon?path=$encodedPath"
            }
        }
    }

    override fun updateConfig(config: WidgetConfig) {
        this.config = config
        updateUI()
    }

    override fun updateData(stats: PCStats) {
        // В режиме keypress нет привязки к процессу
        if (config?.actionMode == "keypress") return

        val path = config?.action ?: return
        val fileName = path.split("\\", "/").last().lowercase()

        val newState = when {
            stats.active_app?.lowercase() == fileName -> 2
            stats.running_apps.any { it.lowercase() == fileName } -> 1
            else -> 0
        }

        if (newState != appState) {
            appState = newState
            invalidate()
        }
    }

    override fun setOffline() {
        if (appState != 0) {
            appState = 0
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (appState == 0) return
        // В режиме keypress не рисуем бордюр
        if (config?.actionMode == "keypress") return

        borderPaint.color = context.getWidgetColor(config?.theme)
        val margin = borderPaint.strokeWidth / 2f
        rectF.set(margin, margin, width.toFloat() - margin, height.toFloat() - margin)
        val r = (radius - margin).coerceAtLeast(0f)

        if (appState == 1) {
            canvas.save()
            canvas.clipRect(0f, height / 2f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rectF, r, r, borderPaint)
            canvas.restore()
        } else if (appState == 2) {
            canvas.drawRoundRect(rectF, r, r, borderPaint)
        }
    }
}


fun setCallbacks(onVibrate: () -> Unit, onScreenshot: () -> Unit, onMicMute: (Boolean) -> Unit, onSleep: () -> Unit, onShutdown: () -> Unit) {
        btnScreenshot.setOnClickListener { onVibrate(); onScreenshot() }
        btnMic.setOnClickListener { 
            onVibrate()
            val newState = !isMuted
            isMuted = newState
            pendingMute = newState
            pendingMuteTime = System.currentTimeMillis()
            updateMicUI(newState)
            onMicMute(newState) 
        }
        btnSleep.setOnClickListener { onVibrate(); onSleep() }
        btnShutdown.setOnClickListener { onVibrate(); onShutdown() }
    }

    private var currentConfig: WidgetConfig? = null

    override fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        updateMicUI(isMuted)
    }

    private fun updateMicUI(muted: Boolean) {
        val color = context.getWidgetColor(currentConfig?.theme)
        btnMic.setColorFilter(if (muted) Color.BLACK else color)
    }

    override fun updateData(stats: PCStats) {
        val now = System.currentTimeMillis()
        if (pendingMute != null && now - pendingMuteTime < PENDING_TIMEOUT) {
            isMuted = pendingMute!!
            updateMicUI(isMuted)
            return
        }
        pendingMute = null
        isMuted = stats.mic_muted
        updateMicUI(isMuted)
    }

    override fun setOffline() {
        isMuted = false
        updateMicUI(false)
        btnMic.setColorFilter(Color.GRAY)
    }
}


fun createRoundedRipple(): android.graphics.drawable.Drawable {
        val r = 14f.dpToPx(context)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.parseColor("#1AFFFFFF"))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.WHITE),
            content,
            mask
        )
    }

    private fun updatePlayPauseIcon(status: Int) {
        btnPlayPause.setImageResource(if (status == 4) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun setCallbacks(onVibrate: () -> Unit, onCommand: (String) -> Unit) {
        this.onVibrate = onVibrate
        this.onCommand = onCommand
    }

    override fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        btnPrev.setColorFilter(color)
        btnPlayPause.setColorFilter(color)
        btnNext.setColorFilter(color)
    }

    override fun updateData(stats: PCStats) {
        stats.media?.let { media ->
            titleText.text = media.title.ifEmpty { Localization.get(context, "NO_MEDIA") }
            artistText.text = media.artist
            
            val now = System.currentTimeMillis()
            if (pendingStatus != null && now - pendingStatusTime < PENDING_TIMEOUT) {
                currentStatus = pendingStatus!!
            } else {
                pendingStatus = null
                currentStatus = media.status
            }
            updatePlayPauseIcon(currentStatus)
            
            val color = context.getWidgetColor(currentConfig?.theme)
            btnPrev.setColorFilter(color)
            btnPlayPause.setColorFilter(color)
            btnNext.setColorFilter(color)

            visibility = View.VISIBLE
        } ?: run {
            titleText.text = Localization.get(context, "NO_MEDIA")
            artistText.text = ""
            btnPlayPause.setImageResource(R.drawable.ic_play)
            val gray = Color.GRAY
            btnPrev.setColorFilter(gray)
            btnPlayPause.setColorFilter(gray)
            btnNext.setColorFilter(gray)
        }
    }

    override fun setOffline() {
        titleText.text = "OFFLINE"
        artistText.text = ""
        btnPlayPause.setImageResource(R.drawable.ic_play)
        val gray = Color.GRAY
        btnPrev.setColorFilter(gray)
        btnPlayPause.setColorFilter(gray)
        btnNext.setColorFilter(gray)
    }
}


fun setCallbacks(onVibrate: () -> Unit, onVolumeChange: (String, Int) -> Unit) { 
        this.onVibrate = onVibrate
        this.onVolumeChange = onVolumeChange 
    }

    private var currentConfig: WidgetConfig? = null
    override fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        titleText.setTextColor(color)
        invalidate() 
    }

    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "AUDIO_MIXER")
        val color = context.getWidgetColor(currentConfig?.theme)
        titleText.setTextColor(color)
        
        if (isWidgetMode) {
            container.removeAllViews()
            stats.audio_sessions.take(3).forEach { session -> // Take top 3 to fit in widget
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 8f.dpToPx(context).toInt())
                }
                
                val nameText = TextView(context).apply {
                    text = session.name
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                item.addView(nameText)
                
                val buttonsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, 24f.dpToPx(context).toInt())
                }
                
                val volToShow = getVolToShow(session.name, session.volume)
                val volumes = listOf(0, 25, 50, 75, 100)
                
                volumes.forEach { vol ->
                    val isSelected = Math.abs(volToShow - vol) < 12 // Closest one
                    val btn = TextView(context).apply {
                        text = "$vol"
                        setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                        setBackgroundResource(R.drawable.mixer_btn_bg)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            if (isSelected) color else Color.parseColor("#3D3D3D")
                        )
                        gravity = Gravity.CENTER
                        textSize = 9f
                        layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                            setMargins(2, 0, 2, 0)
                        }
                        setOnClickListener {
                            onVolumeChange?.invoke(session.name, vol)
                            onVibrate?.invoke()
                        }
                    }
                    buttonsRow.addView(btn)
                }
                item.addView(buttonsRow)
                container.addView(item)
            }
            return
        }

        val current = stats.audio_sessions.map { it.name }.toSet()
        val existing = (0 until container.childCount).map { container.getChildAt(it).tag as String }.toSet()

        if (current != existing) {
            container.removeAllViews()
            stats.audio_sessions.forEach { session ->
                val view = LayoutInflater.from(context).inflate(R.layout.item_mixer_app, container, false)
                view.tag = session.name
                val slider = view.findViewById<SeekBar>(R.id.appVolumeSlider)
                val text = view.findViewById<TextView>(R.id.appVolumePercentText)
                view.findViewById<TextView>(R.id.appNameText).text = session.name
                
                val volToShow = getVolToShow(session.name, session.volume)
                slider.progress = volToShow
                text.text = "$volToShow%"
                
                slider.progressTintList = android.content.res.ColorStateList.valueOf(color)
                slider.thumbTintList = android.content.res.ColorStateList.valueOf(color)
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { 
                        if (f) {
                            text.text = "$p%"
                            if (p % 2 == 0) onVibrate?.invoke() 
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) { activeSliders.add(session.name) }
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        activeSliders.remove(session.name)
                        val vol = slider.progress
                        pendingVolumes[session.name] = Pair(vol, System.currentTimeMillis())
                        onVolumeChange?.invoke(session.name, vol)
                        onVibrate?.invoke()
                    }
                })
                container.addView(view)
            }
        } else {
            for (i in 0 until container.childCount) {
                val view = container.getChildAt(i)
                val name = view.tag as String
                if (name !in activeSliders) {
                    stats.audio_sessions.find { it.name == name }?.let { session ->
                        val volToShow = getVolToShow(name, session.volume)
                        view.findViewById<SeekBar>(R.id.appVolumeSlider).progress = volToShow
                        view.findViewById<TextView>(R.id.appVolumePercentText).text = "$volToShow%"
                    }
                }
            }
        }
    }

    override fun setOffline() {
        container.removeAllViews()
        val offlineText = TextView(context).apply {
            text = "OFFLINE"
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        container.addView(offlineText)
    }
    
    private fun getVolToShow(name: String, serverVol: Int): Int {
        val now = System.currentTimeMillis()
        val cached = pendingVolumes[name]
        return if (cached != null && now - cached.second < PENDING_TIMEOUT) {
            cached.first
        } else {
            pendingVolumes.remove(name)
            serverVol
        }
    }
}


fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        titleText.setTextColor(color)
    }

    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "STORAGE")
        val color = context.getWidgetColor(currentConfig?.theme)
        titleText.setTextColor(color)
        container.removeAllViews()
        stats.disks.forEach { disk ->
            val v = LayoutInflater.from(context).inflate(R.layout.item_widget_disk, container, false)
            v.findViewById<TextView>(R.id.disk_name).text = disk.dev.replace("\\", "")
            val usedValue = if (disk.used > 0.1) disk.used else (disk.total * (disk.percent / 100.0)).toFloat()
            v.findViewById<TextView>(R.id.disk_value).text = "${usedValue.toInt()} / ${disk.total.toInt()} GB"
            val pb = v.findViewById<ProgressBar>(R.id.disk_progress)
            pb.progress = disk.percent.toInt()
            pb.progressTintList = android.content.res.ColorStateList.valueOf(color)
            container.addView(v)
        }
    }

    override fun setOffline() {
        container.removeAllViews()
        val offlineText = TextView(context).apply {
            text = "OFFLINE"
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        container.addView(offlineText)
    }
}


fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        titleText.setTextColor(color)
    }

    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "COOLING")
        val color = context.getWidgetColor(currentConfig?.theme)
        titleText.setTextColor(color)
        val info = stats.fans.joinToString("\n") { "${it.name}: ${it.rpm} RPM" }
        fansText.text = info.ifEmpty { Localization.get(context, "NO_FANS") }
    }

    override fun setOffline() {
        fansText.text = "OFFLINE"
    }
}


fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        titleText.setTextColor(color)
    }

    fun setCallbacks(onVibrate: () -> Unit, onKill: (Int) -> Unit) { 
        this.onVibrate = onVibrate
        this.onKill = onKill
    }
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "PROCESSES")
        val color = context.getWidgetColor(currentConfig?.theme)
        titleText.setTextColor(color)
        container.removeAllViews()
        stats.procs.take(5).forEach { proc ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(context).apply {
                text = "${proc.name} (${proc.cpu}%)"
                setTextColor(Color.WHITE); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(context).apply {
                text = "✕"
                setTextColor(Color.RED)
                textSize = 16f
                setPadding(8f.dpToPx(context).toInt(), 0, 4f.dpToPx(context).toInt(), 0)
                setOnClickListener { onVibrate?.invoke(); onKill?.invoke(proc.pid) }
            })
            container.addView(row)
        }
    }

    override fun setOffline() {
        container.removeAllViews()
    }
}

abstract class SpeedometerWidgetView(context: Context) : BaseWidgetView(context) {
    protected val speedometer: SpeedometerView
    protected val detailText: TextView
    protected val labelText: TextView
    protected val rootLayout: LinearLayout
    protected val textContainer: LinearLayout
    protected var currentConfig: WidgetConfig? = null

    init {
        rootLayout = LinearLayout(context).apply { 
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(-1, -1)
        }
        
        labelText = TextView(context).apply {
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            paint.isFakeBoldText = true
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setPadding(0, 0, 0, 4f.dpToPx(context).toInt()) }
        }

        speedometer = SpeedometerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(120f.dpToPx(context).toInt(), 0, 1f)
        }
        
        detailText = TextView(context).apply { 
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 8f.dpToPx(context).toInt(), 0, 0) 
        }

        textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        
        rootLayout.addView(labelText)
        rootLayout.addView(speedometer)
        rootLayout.addView(detailText)
        addView(rootLayout)
    }

    override fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        applyLayoutRules()
        val color = context.getWidgetColor(config.theme)
        labelText.setTextColor(color)
        speedometer.setMainColor(color)
    }

    private fun applyLayoutRules() {
        val config = currentConfig ?: return
        val isHorizontal = config.width > 2
        labelText.visibility = View.VISIBLE
        (labelText.parent as? ViewGroup)?.removeView(labelText)
        (speedometer.parent as? ViewGroup)?.removeView(speedometer)
        (detailText.parent as? ViewGroup)?.removeView(detailText)
        (textContainer.parent as? ViewGroup)?.removeView(textContainer)
        rootLayout.removeAllViews()
        textContainer.removeAllViews()

        if (isHorizontal) {
            rootLayout.orientation = LinearLayout.HORIZONTAL
            rootLayout.gravity = Gravity.CENTER_VERTICAL
            speedometer.layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(0, 0, 16f.dpToPx(context).toInt(), 0)
            }
            textContainer.addView(labelText)
            textContainer.addView(detailText)
            rootLayout.addView(speedometer)
            rootLayout.addView(textContainer)
            labelText.layoutParams = LinearLayout.LayoutParams(-1, -2)
            detailText.layoutParams = LinearLayout.LayoutParams(-1, -2)
            detailText.gravity = Gravity.START
            detailText.setPadding(0, 4f.dpToPx(context).toInt(), 0, 0)
        } else {
            rootLayout.orientation = LinearLayout.VERTICAL
            rootLayout.gravity = Gravity.CENTER
            speedometer.layoutParams = LinearLayout.LayoutParams(120f.dpToPx(context).toInt(), 0, 1f).apply {
                setMargins(0, 0, 0, 0)
            }
            rootLayout.addView(labelText)
            rootLayout.addView(speedometer)
            rootLayout.addView(detailText)
            labelText.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { 
                setPadding(0, 0, 0, 4f.dpToPx(context).toInt()) 
            }
            detailText.gravity = Gravity.CENTER
            detailText.setPadding(0, 8f.dpToPx(context).toInt(), 0, 0)
        }
        rootLayout.requestLayout()
    }
}


fun updateData(stats: PCStats) {
        val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val showNames = prefs.getBoolean("SHOW_DEVICE_NAMES", false)
        labelText.text = if (showNames) formatDeviceName(stats.cpu.name) else Localization.get(context, "CPU")
        speedometer.setValue(stats.cpu.usage.toFloat())
        detailText.text = "${stats.cpu.freq.toInt()} MHz | ${stats.cpu.temp}°C"
    }

    override fun setOffline() {
        speedometer.setValue(0f)
        detailText.text = "--- MHz | --°C"
    }
}


fun updateData(stats: PCStats) {
        labelText.text = Localization.get(context, "RAM")
        speedometer.setValue(stats.ram.usage.toFloat())
        detailText.text = "${stats.ram.used} / ${stats.ram.total} GB"
    }

    override fun setOffline() {
        speedometer.setValue(0f)
        detailText.text = "- / - GB"
    }
}


fun updateData(stats: PCStats) {
        val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val showNames = prefs.getBoolean("SHOW_DEVICE_NAMES", false)
        stats.gpu.getOrNull(0)?.let { g ->
            labelText.text = if (showNames) formatDeviceName(g.name) else Localization.get(context, "GPU")
            speedometer.setValue(g.load.toFloat())
            detailText.text = "${g.temp}°C | VRAM: ${g.mem_p}%"
        }
    }

    override fun setOffline() {
        speedometer.setValue(0f)
        detailText.text = "--°C | VRAM: --%"
    }
}
