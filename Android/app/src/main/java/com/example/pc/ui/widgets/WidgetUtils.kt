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

