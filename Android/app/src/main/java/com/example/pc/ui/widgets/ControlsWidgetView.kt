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


class ControlsWidgetView(context: Context) : BaseWidgetView(context) {
    private val btnScreenshot: ImageButton
    private val btnMic: ImageButton
    private val btnSleep: ImageButton
    private val btnShutdown: ImageButton
    private var isMuted = false
    
    private var pendingMute: Boolean? = null
    private var pendingMuteTime = 0L
    private val PENDING_TIMEOUT = 2500L

    init {
        val v = LayoutInflater.from(context).inflate(R.layout.widget_controls, this, true)
        btnScreenshot = v.findViewById(R.id.screenshot_button)
        btnMic = v.findViewById(R.id.mic_button)
        btnSleep = v.findViewById(R.id.sleep_button)
        btnShutdown = v.findViewById(R.id.shutdown_button)
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
