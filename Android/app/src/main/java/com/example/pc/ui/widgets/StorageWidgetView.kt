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

    private var currentConfig: WidgetConfig? = null
    override fun updateConfig(config: WidgetConfig) {
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
