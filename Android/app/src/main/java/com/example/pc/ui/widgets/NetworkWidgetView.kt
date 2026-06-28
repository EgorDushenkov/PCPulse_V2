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


class NetworkWidgetView(context: Context) : BaseWidgetView(context) {
    private val downText: TextView
    private val upText: TextView
    private val titleText: TextView
    private var currentConfig: WidgetConfig? = null

    init {
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleText = TextView(context).apply {
            text = Localization.get(context, "NETWORK")
            setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f; paint.isFakeBoldText = true
        }
        l.addView(titleText)
        downText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; paint.isFakeBoldText = true }
        upText = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        l.addView(downText); l.addView(upText); addView(l)
    }

    override fun updateConfig(config: WidgetConfig) {
        this.currentConfig = config
        val color = context.getWidgetColor(config.theme)
        titleText.setTextColor(color)
    }

    @SuppressLint("SetTextI18n")
    override fun updateData(stats: PCStats) {
        titleText.text = Localization.get(context, "NETWORK")
        val color = context.getWidgetColor(currentConfig?.theme)
        titleText.setTextColor(color)
        downText.text = "↓ ${stats.network.down_kbps.toInt()} KB/s"
        upText.text = "↑ ${stats.network.up_kbps.toInt()} KB/s"
    }

    override fun setOffline() {
        downText.text = "↓ 0 KB/s"
        upText.text = "↑ 0 KB/s"
    }
}

object WidgetFactory {
    fun create(
        config: WidgetConfig,
        context: Context,
        isWidget: Boolean = false,
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
        onCloseCommand: ((String) -> Unit)? = null,
        onKeyPressCommand: ((List<String>) -> Unit)? = null
    ): View {
        return when (config.type) {
            WidgetType.COOLING -> CoolingWidgetView(context).apply { updateConfig(config) }
            WidgetType.TOP_PROCESSES -> TopProcessesWidgetView(context).apply {
                setCallbacks(onVibrate, onKill ?: {})
                updateConfig(config)
            }
            WidgetType.CPU -> CpuWidgetView(context).apply { updateConfig(config) }
            WidgetType.RAM -> RamWidgetView(context).apply { updateConfig(config) }
            WidgetType.GPU -> GpuWidgetView(context).apply { updateConfig(config) }
            WidgetType.NETWORK -> NetworkWidgetView(context).apply { updateConfig(config) }
            WidgetType.ACTION_BUTTON -> ActionButtonWidgetView(context).apply {
                setup(config, onVibrate, onRunCommand ?: {}, onMinimizeCommand ?: {}, onCloseCommand ?: {}, onKeyPressCommand)
                updateConfig(config)
            }
            WidgetType.MEDIA_PLAYER -> MediaPlayerWidgetView(context, isWidget).apply {
                setCallbacks(onVibrate, onMediaCommand ?: {})
                updateConfig(config)
            }
            WidgetType.AUDIO_MIXER -> AudioMixerWidgetView(context, isWidget).apply {
                setCallbacks(onVibrate, onVolumeChange ?: { _, _ -> })
                updateConfig(config)
            }
            WidgetType.CONTROLS -> ControlsWidgetView(context).apply {
                setCallbacks(
                    onVibrate = onVibrate,
                    onScreenshot = onScreenshot ?: {},
                    onMicMute = onMicMute ?: {},
                    onSleep = onSleep ?: {},
                    onShutdown = onShutdown ?: {}
                )
                updateConfig(config)
            }
            WidgetType.STORAGE -> StorageWidgetView(context).apply { updateConfig(config) }
            null -> View(context)
        }
    }
}
