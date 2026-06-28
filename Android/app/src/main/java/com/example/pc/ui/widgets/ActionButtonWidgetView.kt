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
            } catch (e: Exception) { android.util.Log.e("Widget", "Error", e) }
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
            } catch (e: Exception) { android.util.Log.e("Widget", "Error", e) }
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
