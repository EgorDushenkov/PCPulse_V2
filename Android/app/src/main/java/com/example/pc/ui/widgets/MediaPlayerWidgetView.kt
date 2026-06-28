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


class MediaPlayerWidgetView @JvmOverloads constructor(
    context: Context, 
    private val isWidgetMode: Boolean = false
) : BaseWidgetView(context) {
    private val titleText: TextView
    private val artistText: TextView
    private val btnPrev: ImageButton
    private val btnPlayPause: ImageButton
    private val btnNext: ImageButton
    private var onCommand: ((String) -> Unit)? = null
    private var onVibrate: (() -> Unit)? = null
    
    private var currentStatus: Int = 0
    private var pendingStatus: Int? = null
    private var pendingStatusTime = 0L
    private val PENDING_TIMEOUT = 2500L
    private var currentConfig: WidgetConfig? = null

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

        val sideBtnSize = 44f.dpToPx(context).toInt()
        val mainBtnSize = 56f.dpToPx(context).toInt()
        val iconPadding = 12f.dpToPx(context).toInt()

        btnPrev = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sideBtnSize, sideBtnSize)
            setImageResource(R.drawable.ic_prev)
            background = createRoundedRipple()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        btnPlayPause = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(mainBtnSize, mainBtnSize).apply {
                setMargins(16f.dpToPx(context).toInt(), 0, 16f.dpToPx(context).toInt(), 0)
            }
            setImageResource(R.drawable.ic_pause)
            background = createRoundedRipple()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        btnNext = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sideBtnSize, sideBtnSize)
            setImageResource(R.drawable.ic_prev)
            rotation = 180f
            background = createRoundedRipple()
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
            pendingStatus = newState
            pendingStatusTime = System.currentTimeMillis()
            updatePlayPauseIcon(newState)
            onCommand?.invoke("play_pause") 
        }
        btnNext.setOnClickListener { onVibrate?.invoke(); onCommand?.invoke("next") }
    }

    private fun createRoundedRipple(): android.graphics.drawable.Drawable {
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
