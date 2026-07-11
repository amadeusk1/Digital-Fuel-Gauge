package com.fuelcheck

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Semi-circular fuel gauge. Level is 0f..100f (percent of tank).
 * Full (F) is on the left; Empty (E) is on the right.
 */
class GasGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val endLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arcBounds = RectF()

    private val colorTrack = ContextCompat.getColor(context, R.color.fuel_outline)
    private val colorOk = ContextCompat.getColor(context, R.color.fuel_primary)
    private val colorLow = ContextCompat.getColor(context, R.color.fuel_error)
    private val colorText = ContextCompat.getColor(context, R.color.fuel_text)
    private val colorMuted = ContextCompat.getColor(context, R.color.fuel_muted)

    private val gasStationIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_gas_station)?.mutate()

    private var displayedLevel = 0f
    private var hasReading = false
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setLevel(percent: Float, animate: Boolean = true) {
        val target = percent.coerceIn(0f, 100f)
        hasReading = true
        animator?.cancel()
        if (!animate) {
            displayedLevel = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayedLevel, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun clearLevel() {
        animator?.cancel()
        hasReading = false
        displayedLevel = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (width * 0.68f).toInt().coerceAtLeast(dp(160f).toInt())
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val stroke = dp(18f)
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        fillGlowPaint.strokeWidth = stroke + dp(8f)
        needlePaint.strokeWidth = dp(3.5f)
        tickPaint.strokeWidth = dp(2.5f)
        hubRingPaint.strokeWidth = dp(2f)
        labelPaint.textSize = sp(28f)
        endLabelPaint.textSize = sp(13f)

        val pad = stroke / 2f + dp(8f)
        val size = min(width.toFloat() - pad * 2f, (height.toFloat() - pad) * 2f)
        val left = (width - size) / 2f
        val top = pad
        arcBounds.set(left, top, left + size, top + size)

        val cx = arcBounds.centerX()
        val cy = arcBounds.centerY()
        val radius = size / 2f

        trackPaint.color = colorTrack
        canvas.drawArc(arcBounds, FULL_ANGLE, SWEEP_TO_EMPTY, false, trackPaint)

        for (i in 0..4) {
            val angle = Math.toRadians((FULL_ANGLE + SWEEP_TO_EMPTY * (i / 4f)).toDouble())
            val inner = radius - stroke / 2f - dp(4f)
            val outer = radius - stroke / 2f - dp(14f)
            tickPaint.color = colorMuted
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(),
                cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * outer).toFloat(),
                cy + (sin(angle) * outer).toFloat(),
                tickPaint
            )
        }

        endLabelPaint.color = colorMuted
        val labelR = radius - stroke / 2f - dp(28f)
        val fullRad = Math.toRadians(FULL_ANGLE.toDouble())
        val emptyRad = Math.toRadians(EMPTY_ANGLE.toDouble())
        canvas.drawText(
            "F",
            cx + (cos(fullRad) * labelR).toFloat(),
            cy + (sin(fullRad) * labelR).toFloat() + sp(4f),
            endLabelPaint
        )

        val eX = cx + (cos(emptyRad) * labelR).toFloat() - dp(10f)
        val eY = cy + (sin(emptyRad) * labelR).toFloat() + sp(4f)
        canvas.drawText("E", eX, eY, endLabelPaint)

        gasStationIcon?.let { icon ->
            val iconSize = dp(15f).toInt()
            val gap = dp(3f)
            val iconLeft = (eX + gap + sp(5f)).toInt()
            val iconTop = (eY - iconSize * 0.75f).toInt()
                .coerceIn(0, (height - iconSize).coerceAtLeast(0))
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            icon.setTint(colorMuted)
            icon.draw(canvas)
        }

        if (hasReading) {
            val levelFraction = (displayedLevel / 100f).coerceIn(0f, 1f)
            val needleDegrees = FULL_ANGLE + SWEEP_TO_EMPTY * (1f - levelFraction)
            val fillStart = needleDegrees
            val fillSweep = SWEEP_TO_EMPTY * levelFraction
            val isLow = displayedLevel <= LOW_THRESHOLD
            val fillColor = if (isLow) colorLow else colorOk

            if (fillSweep > 0.5f) {
                if (isLow) {
                    fillGlowPaint.color = fillColor
                    fillGlowPaint.alpha = 90
                    fillGlowPaint.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL)
                    canvas.drawArc(arcBounds, fillStart, fillSweep, false, fillGlowPaint)
                    fillGlowPaint.maskFilter = null
                }
                fillPaint.color = fillColor
                canvas.drawArc(arcBounds, fillStart, fillSweep, false, fillPaint)
            }

            val needleRad = Math.toRadians(needleDegrees.toDouble())
            needlePaint.color = colorText
            val needleLen = radius - stroke - dp(8f)
            canvas.drawLine(
                cx,
                cy,
                cx + (cos(needleRad) * needleLen).toFloat(),
                cy + (sin(needleRad) * needleLen).toFloat(),
                needlePaint
            )

            hubRingPaint.color = colorText
            canvas.drawCircle(cx, cy, dp(9f), hubRingPaint)
            hubPaint.color = fillColor
            canvas.drawCircle(cx, cy, dp(6.5f), hubPaint)

            labelPaint.color = colorText
            canvas.drawText(
                "${displayedLevel.toInt()}%",
                cx,
                cy + dp(42f),
                labelPaint
            )
        } else {
            hubPaint.color = colorTrack
            canvas.drawCircle(cx, cy, dp(7f), hubPaint)
            labelPaint.color = colorMuted
            labelPaint.textSize = sp(16f)
            canvas.drawText("—", cx, cy + dp(42f), labelPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        private const val FULL_ANGLE = 180f
        private const val EMPTY_ANGLE = 360f
        private const val SWEEP_TO_EMPTY = EMPTY_ANGLE - FULL_ANGLE
        private const val LOW_THRESHOLD = 15f
    }
}
