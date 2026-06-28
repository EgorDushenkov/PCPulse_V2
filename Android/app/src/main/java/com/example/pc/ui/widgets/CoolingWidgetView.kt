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

    private var currentConfig: WidgetConfig? = null
    override fun updateConfig(config: WidgetConfig) {
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
