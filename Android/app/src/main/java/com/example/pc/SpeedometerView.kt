package com.example.pc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min

class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var speedometerColor: Int = Color.parseColor("#BB86FC")
    private var speedometerBackgroundColor = Color.parseColor("#33FFFFFF")
    private var titleTextColor = Color.GRAY
    private var valueTextColor = Color.WHITE
    private var titleText = ""
    private var value = 0f
    private var maxValue = 100f

    private val backgroundPaint: Paint
    private val progressPaint: Paint
    private val titleTextPaint: Paint
    private val valueTextPaint: Paint

    private val oval = RectF()
    private var centerX = 0f
    private var centerY = 0f

    init {
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)) {
            speedometerColor = typedValue.data
        }

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SpeedometerView)
        speedometerColor = typedArray.getColor(R.styleable.SpeedometerView_speedometerColor, speedometerColor)
        speedometerBackgroundColor = typedArray.getColor(R.styleable.SpeedometerView_speedometerBackgroundColor, speedometerBackgroundColor)
        titleTextColor = typedArray.getColor(R.styleable.SpeedometerView_titleTextColor, titleTextColor)
        valueTextColor = typedArray.getColor(R.styleable.SpeedometerView_textColor, speedometerColor)
        titleText = typedArray.getString(R.styleable.SpeedometerView_titleText) ?: ""
        value = typedArray.getFloat(R.styleable.SpeedometerView_value, 0f)
        maxValue = typedArray.getFloat(R.styleable.SpeedometerView_maxValue, 100f)
        typedArray.recycle()

        backgroundPaint = Paint().apply {
            color = speedometerBackgroundColor
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        progressPaint = Paint().apply {
            color = speedometerColor
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        titleTextPaint = Paint().apply {
            color = titleTextColor
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        valueTextPaint = Paint().apply {
            color = valueTextColor
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val radius = min(w, h) / 2f - backgroundPaint.strokeWidth / 2f
        oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val startAngle = -225f
        val sweepAngle = 270f

        canvas.drawArc(oval, startAngle, sweepAngle, false, backgroundPaint)
        val progressSweep = (value / maxValue) * sweepAngle
        canvas.drawArc(oval, startAngle, progressSweep, false, progressPaint)

        val titleY = centerY - 15
        val valueY = centerY + 30
        canvas.drawText(titleText, centerX, titleY, titleTextPaint)
        
        canvas.drawText("${value.toInt()}%", centerX, valueY, valueTextPaint)
    }

    fun setValue(newValue: Float) {
        value = if (newValue > maxValue) maxValue else if (newValue < 0) 0f else newValue
        invalidate()
    }

    fun setMainColor(color: Int) {
        speedometerColor = color
        valueTextColor = color
        progressPaint.color = color
        valueTextPaint.color = color
        invalidate()
    }
}