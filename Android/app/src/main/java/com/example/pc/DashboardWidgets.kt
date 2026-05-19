package com.example.pc

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

fun Context.getThemeColor(attr: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
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

class ActionButtonWidgetView(context: Context) : BaseWidgetView(context) {
    private val button: Button
    private val iconView: ImageView
    private var config: WidgetConfig? = null
    private var appState = 0 // 0: not running, 1: background, 2: active
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }
    private val rectF = RectF()

    init {
        setWillNotDraw(false)
        
        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        addView(iconView)

        button = Button(context).apply {
            layoutParams = LayoutParams(-1, -1)
            background = null
            stateListAnimator = null 
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
        }
        addView(button)
    }

    fun setup(
        config: WidgetConfig,
        onVibrate: () -> Unit,
        onRun: (String) -> Unit,
        onMinimize: () -> Unit,
        onClose: (String) -> Unit
    ) {
        this.config = config
        updateUI()
        
        button.setOnClickListener {
            onVibrate()
            val path = config.action ?: return@setOnClickListener
            if (appState == 2) {
                onMinimize()
            } else {
                onRun(path)
            }
        }

        button.setOnLongClickListener {
            onVibrate()
            config.action?.let { path ->
                val fileName = path.split("\\", "/").last().lowercase()
                onClose(fileName)
            }
            true
        }
    }

    private fun updateUI() {
        val cfg = config ?: return
        if (cfg.useIcon && !cfg.action.isNullOrEmpty()) {
            button.text = ""
            iconView.visibility = View.VISIBLE
            
            val action = cfg.action ?: ""
            val iconUrl = if (action.startsWith("http://") || action.startsWith("https://")) {
                // Если это ссылка, берем иконку через Google Favicons
                val host = Uri.parse(action).host ?: action
                "https://www.google.com/s2/favicons?domain=$host&sz=128"
            } else {
                // Если это файл, берем иконку с нашего сервера
                val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
                val serverIp = prefs.getString("SERVER_IP", "192.168.1.100") ?: "192.168.1.100"
                val encodedPath = Uri.encode(action)
                "http://$serverIp:5000/icon?path=$encodedPath"
            }
            
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

    override fun updateConfig(config: WidgetConfig) {
        this.config = config
        updateUI()
    }

    override fun updateData(stats: PCStats) {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (appState == 0) return

        borderPaint.color = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
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

class ControlsWidgetView(context: Context) : BaseWidgetView(context) {
    private val btnScrenshot: ImageButton
    private val btnMic: ImageButton
    private val btnSleep: ImageButton
    private val btnShutdown: ImageButton
    private var isMuted = false
    
    private var optimisticMute: Boolean? = null
    private var optimisticMuteTime = 0L
    private val OPTIMISTIC_TIMEOUT = 2500L

    init {
        val v = LayoutInflater.from(context).inflate(R.layout.widget_controls, this, true)
        btnScrenshot = v.findViewById(R.id.screenshot_button)
        btnMic = v.findViewById(R.id.mic_button)
        btnSleep = v.findViewById(R.id.sleep_button)
        btnShutdown = v.findViewById(R.id.shutdown_button)
    }
    
    fun setCallbacks(onVibrate: () -> Unit, onScreenshot: () -> Unit, onMicMute: (Boolean) -> Unit, onSleep: () -> Unit, onShutdown: () -> Unit) {
        btnScrenshot.setOnClickListener { onVibrate(); onScreenshot() }
        btnMic.setOnClickListener { 
            onVibrate()
            val newState = !isMuted
            isMuted = newState
            optimisticMute = newState
            optimisticMuteTime = System.currentTimeMillis()
            updateMicUI(newState)
            onMicMute(newState) 
        }
        btnSleep.setOnClickListener { onVibrate(); onSleep() }
        btnShutdown.setOnClickListener { onVibrate(); onShutdown() }
    }

    private fun updateMicUI(muted: Boolean) {
        val themeColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        btnMic.setColorFilter(if (muted) Color.BLACK else themeColor)
    }

    override fun updateData(stats: PCStats) {
        val now = System.currentTimeMillis()
        if (optimisticMute != null && now - optimisticMuteTime < OPTIMISTIC_TIMEOUT) {
            isMuted = optimisticMute!!
            updateMicUI(isMuted)
            return
        }
        optimisticMute = null
        isMuted = stats.mic_muted
        updateMicUI(isMuted)
    }
}

class MediaPlayerWidgetView(context: Context) : BaseWidgetView(context) {
    private val titleText: TextView
    private val artistText: TextView
    private val btnPrev: ImageButton
    private val btnPlayPause: ImageButton
    private val btnNext: ImageButton
    private var onCommand: ((String) -> Unit)? = null
    private var onVibrate: (() -> Unit)? = null
    
    private var currentStatus: Int = 0
    private var optimisticStatus: Int? = null
    private var optimisticStatusTime = 0L
    private val OPTIMISTIC_TIMEOUT = 2500L

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(-1, -1)
        }

        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artistText = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 12f.dpToPx(context).toInt())
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnSize = 48f.dpToPx(context).toInt()
        val iconPadding = 8f.dpToPx(context).toInt()

        btnPrev = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setImageResource(R.drawable.ic_prev)
            setBackgroundResource(android.R.color.transparent)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        btnPlayPause = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                setMargins(24f.dpToPx(context).toInt(), 0, 24f.dpToPx(context).toInt(), 0)
            }
            setImageResource(R.drawable.ic_pause)
            setBackgroundResource(android.R.color.transparent)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        btnNext = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setImageResource(R.drawable.ic_prev)
            rotation = 180f
            setBackgroundResource(android.R.color.transparent)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        controls.addView(btnPrev)
        controls.addView(btnPlayPause)
        controls.addView(btnNext)

        root.addView(titleText)
        root.addView(artistText)
        root.addView(controls)
        addView(root)

        btnPrev.setOnClickListener { onVibrate?.invoke(); onCommand?.invoke("prev") }
        btnPlayPause.setOnClickListener { 
            onVibrate?.invoke()
            val newState = if (currentStatus == 4) 0 else 4 
            currentStatus = newState
            optimisticStatus = newState
            optimisticStatusTime = System.currentTimeMillis()
            updatePlayPauseIcon(newState)
            onCommand?.invoke("play_pause") 
        }
        btnNext.setOnClickListener { onVibrate?.invoke(); onCommand?.invoke("next") }
    }

    private fun updatePlayPauseIcon(status: Int) {
        btnPlayPause.setImageResource(if (status == 4) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun setCallbacks(onVibrate: () -> Unit, onCommand: (String) -> Unit) {
        this.onVibrate = onVibrate
        this.onCommand = onCommand
    }

    override fun updateData(stats: PCStats) {
        stats.media?.let { media ->
            titleText.text = media.title.ifEmpty { Localization.get(context, "NO_MEDIA") }
            artistText.text = media.artist
            
            val now = System.currentTimeMillis()
            if (optimisticStatus != null && now - optimisticStatusTime < OPTIMISTIC_TIMEOUT) {
                currentStatus = optimisticStatus!!
            } else {
                optimisticStatus = null
                currentStatus = media.status
            }
            updatePlayPauseIcon(currentStatus)
            visibility = View.VISIBLE
        } ?: run {
            titleText.text = Localization.get(context, "NO_MEDIA")
            artistText.text = ""
            btnPlayPause.setImageResource(R.drawable.ic_play)
        }
    }
}

class AudioMixerWidgetView(context: Context) : BaseWidgetView(context) {
    private val container: LinearLayout
    private val titleText: TextView
    private var onVolumeChange: ((String, Int) -> Unit)? = null
    private var onVibrate: (() -> Unit)? = null
    private val activeSliders = mutableSetOf<String>()
    
    private val optimisticVolumes = mutableMapOf<String, Pair<Int, Long>>()
    private val OPTIMISTIC_TIMEOUT = 3000L

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(context).apply {
            text = Localization.get(context, "AUDIO_MIXER")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 8f.dpToPx(context).toInt())
        }
        root.addView(titleText)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        root.addView(scroll)
        addView(root)
    }

    fun setCallbacks(onVibrate: () -> Unit, onVolumeChange: (String, Int) -> Unit) { 
        this.onVibrate = onVibrate
        this.onVolumeChange = onVolumeChange 
    }

    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "AUDIO_MIXER")
        val current = stats.audio_sessions.map { it.name }.toSet()
        val existing = (0 until container.childCount).map { container.getChildAt(it).tag as String }.toSet()

        if (current != existing) {
            container.removeAllViews()
            val themeColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            stats.audio_sessions.forEach { session ->
                val view = LayoutInflater.from(context).inflate(R.layout.item_mixer_app, container, false)
                view.tag = session.name
                val slider = view.findViewById<SeekBar>(R.id.appVolumeSlider)
                val text = view.findViewById<TextView>(R.id.appVolumePercentText)
                view.findViewById<TextView>(R.id.appNameText).text = session.name
                
                val volToShow = getVolToShow(session.name, session.volume)
                slider.progress = volToShow
                text.text = "$volToShow%"
                
                slider.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
                slider.thumbTintList = android.content.res.ColorStateList.valueOf(themeColor)
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
                        optimisticVolumes[session.name] = Pair(vol, System.currentTimeMillis())
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
    
    private fun getVolToShow(name: String, serverVol: Int): Int {
        val now = System.currentTimeMillis()
        val optimistic = optimisticVolumes[name]
        return if (optimistic != null && now - optimistic.second < OPTIMISTIC_TIMEOUT) {
            optimistic.first
        } else {
            optimisticVolumes.remove(name)
            serverVol
        }
    }
}

class StorageWidgetView(context: Context) : BaseWidgetView(context) {
    private val container: LinearLayout
    private val titleText: TextView
    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(context).apply {
            text = Localization.get(context, "STORAGE")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 4f.dpToPx(context).toInt())
        }
        root.addView(titleText)
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(-1, -1)
            isVerticalScrollBarEnabled = false
        }
        container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        root.addView(scroll)
        addView(root)
    }
    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "STORAGE")
        container.removeAllViews()
        val themeColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        stats.disks.forEach { disk ->
            val v = LayoutInflater.from(context).inflate(R.layout.item_widget_disk, container, false)
            v.findViewById<TextView>(R.id.disk_name).text = disk.dev.replace("\\", "")
            val usedValue = if (disk.used > 0.1) disk.used else (disk.total * (disk.percent / 100.0)).toFloat()
            v.findViewById<TextView>(R.id.disk_value).text = "${usedValue.toInt()} / ${disk.total.toInt()} GB"
            val pb = v.findViewById<ProgressBar>(R.id.disk_progress)
            pb.progress = disk.percent.toInt()
            pb.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
            container.addView(v)
        }
    }
}

class CoolingWidgetView(context: Context) : BaseWidgetView(context) {
    private val fansText: TextView
    private val titleText: TextView
    init {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(context).apply {
            text = Localization.get(context, "COOLING")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f; paint.isFakeBoldText = true
        }
        c.addView(titleText)
        fansText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f }
        c.addView(fansText)
        addView(c)
    }
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "COOLING")
        val info = stats.fans.joinToString("\n") { "${it.name}: ${it.rpm} RPM" }
        fansText.text = info.ifEmpty { Localization.get(context, "NO_FANS") }
    }
}

class TopProcessesWidgetView(context: Context) : BaseWidgetView(context) {
    private val container: LinearLayout
    private val titleText: TextView
    private var onKill: ((Int) -> Unit)? = null
    private var onVibrate: (() -> Unit)? = null
    init {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(context).apply {
            text = Localization.get(context, "PROCESSES")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f; paint.isFakeBoldText = true
        }
        c.addView(titleText)
        container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        c.addView(container); addView(c)
    }
    fun setCallbacks(onVibrate: () -> Unit, onKill: (Int) -> Unit) { 
        this.onVibrate = onVibrate
        this.onKill = onKill 
    }
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "PROCESSES")
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
    }

    private fun applyLayoutRules() {
        val config = currentConfig ?: return
        val isHorizontal = config.width > 2
        val showLabel = if (isHorizontal) config.height > 1 else config.height >= 3
        labelText.visibility = if (showLabel) View.VISIBLE else View.GONE
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

class CpuWidgetView(context: Context) : SpeedometerWidgetView(context) {
    override fun updateData(stats: PCStats) {
        val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val showNames = prefs.getBoolean("SHOW_DEVICE_NAMES", false)
        labelText.text = if (showNames) formatDeviceName(stats.cpu.name) else Localization.get(context, "CPU")
        speedometer.setValue(stats.cpu.usage.toFloat())
        detailText.text = "${stats.cpu.freq.toInt()} MHz | ${stats.cpu.temp}°C"
    }
}

class RamWidgetView(context: Context) : SpeedometerWidgetView(context) {
    override fun updateData(stats: PCStats) {
        labelText.text = Localization.get(context, "RAM")
        speedometer.setValue(stats.ram.usage.toFloat())
        detailText.text = "${stats.ram.used} / ${stats.ram.total} GB"
    }
}

class GpuWidgetView(context: Context) : SpeedometerWidgetView(context) {
    override fun updateData(stats: PCStats) {
        val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val showNames = prefs.getBoolean("SHOW_DEVICE_NAMES", false)
        stats.gpu.getOrNull(0)?.let { g ->
            labelText.text = if (showNames) formatDeviceName(g.name) else Localization.get(context, "GPU")
            speedometer.setValue(g.load.toFloat())
            detailText.text = "${g.temp}°C | VRAM: ${g.mem_p}%"
        }
    }
}

class NetworkWidgetView(context: Context) : BaseWidgetView(context) {
    private val downText: TextView
    private val upText: TextView
    private val titleText: TextView
    init {
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val themeColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        titleText = TextView(context).apply {
            text = Localization.get(context, "NETWORK")
            setTextColor(themeColor)
            textSize = 12f; paint.isFakeBoldText = true
        }
        l.addView(titleText)
        downText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; paint.isFakeBoldText = true }
        upText = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        l.addView(downText); l.addView(upText); addView(l)
    }
    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "NETWORK")
        downText.text = "↓ ${stats.network.down_kbps.toInt()} KB/s"
        upText.text = "↑ ${stats.network.up_kbps.toInt()} KB/s"
    }
}

object WidgetFactory {
    fun create(
        config: WidgetConfig,
        context: Context,
        onVibrate: () -> Unit = {},
        onScreenshot: (() -> Unit)? = null,
        onMicMute: ((Boolean) -> Unit)? = null,
        onSleep: (() -> Unit)? = null,
        onShutdown: (() -> Unit)? = null,
        onVolumeChange: ((String, Int) -> Unit)? = null,
        onKill: ((Int) -> Unit)? = null,
        onRunCommand: ((String) -> Unit)? = null,
        onMediaCommand: ((String) -> Unit)? = null,
        onMinimizeCommand: (() -> Unit)? = null,
        onCloseCommand: ((String) -> Unit)? = null
    ): View {
        return when (config.type) {
            WidgetType.CONTROLS -> ControlsWidgetView(context).apply {
                setCallbacks(onVibrate, onScreenshot ?: {}, onMicMute ?: {}, onSleep ?: {}, onShutdown ?: {})
            }
            WidgetType.AUDIO_MIXER -> AudioMixerWidgetView(context).apply {
                setCallbacks(onVibrate, onVolumeChange ?: { _, _ -> })
            }
            WidgetType.STORAGE -> StorageWidgetView(context)
            WidgetType.COOLING -> CoolingWidgetView(context)
            WidgetType.TOP_PROCESSES -> TopProcessesWidgetView(context).apply {
                setCallbacks(onVibrate, onKill ?: {})
            }
            WidgetType.CPU -> CpuWidgetView(context).apply { updateConfig(config) }
            WidgetType.RAM -> RamWidgetView(context).apply { updateConfig(config) }
            WidgetType.GPU -> GpuWidgetView(context).apply { updateConfig(config) }
            WidgetType.NETWORK -> NetworkWidgetView(context)
            WidgetType.ACTION_BUTTON -> ActionButtonWidgetView(context).apply {
                setup(config, onVibrate, onRunCommand ?: {}, onMinimizeCommand ?: {}, onCloseCommand ?: {})
            }
            WidgetType.MEDIA_PLAYER -> MediaPlayerWidgetView(context).apply {
                setCallbacks(onVibrate, onMediaCommand ?: {})
            }
        }
    }
}
