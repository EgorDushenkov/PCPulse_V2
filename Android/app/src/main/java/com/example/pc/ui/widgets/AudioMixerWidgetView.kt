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


class AudioMixerWidgetView @JvmOverloads constructor(
    context: Context, 
    private val isWidgetMode: Boolean = false
) : BaseWidgetView(context) {
    private val container: LinearLayout
    private val titleText: TextView
    private var onVolumeChange: ((String, Int) -> Unit)? = null
    private var onVibrate: (() -> Unit)? = null
    private val activeSliders = mutableSetOf<String>()
    
    private val pendingVolumes = mutableMapOf<String, Pair<Int, Long>>()
    private val PENDING_TIMEOUT = 3000L

    init {
        val root = LinearLayout(context).apply { 
            orientation = LinearLayout.VERTICAL 
            layoutParams = LayoutParams(-1, -1)
        }
        titleText = TextView(context).apply {
            text = Localization.get(context, "AUDIO_MIXER")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 10f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 4f.dpToPx(context).toInt())
        }
        root.addView(titleText)
        
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        container = LinearLayout(context).apply { 
            orientation = LinearLayout.VERTICAL 
            layoutParams = LayoutParams(-1, -2)
        }
        scroll.addView(container)
        root.addView(scroll)
        addView(root)
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
