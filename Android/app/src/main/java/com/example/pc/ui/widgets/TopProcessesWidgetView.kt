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

    private var currentConfig: WidgetConfig? = null
    override fun updateConfig(config: WidgetConfig) {
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
