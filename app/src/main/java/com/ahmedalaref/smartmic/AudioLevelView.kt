package com.ahmedalaref.smartmic

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * AudioLevelView — animated VU-meter bar array
 *
 * Draws [BAR_COUNT] rounded bars that light up from left to right
 * in a green→yellow→red gradient based on the current audio level.
 * Inactive bars are shown as dim dark rectangles.
 *
 * Call [setLevel] with a value in [0, 1] to animate the meter.
 */
class AudioLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT  = 32
        private const val BAR_GAP    = 3f
        private const val CORNER_R   = 4f
        private val GRADIENT_COLORS  = intArrayOf(
            Color.parseColor("#00E676"),   // vivid green
            Color.parseColor("#FFEA00"),   // yellow
            Color.parseColor("#FF1744")    // red
        )
        private val INACTIVE_COLOR   = Color.parseColor("#1E1E2E")
    }

    private val paint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    // ─── smoothed level – exponential moving average ──────────────────────────
    private var displayLevel = 0f
    private var targetLevel  = 0f

    /** Set the current audio level (0.0 – 1.0). */
    fun setLevel(value: Float) {
        targetLevel  = value.coerceIn(0f, 1f)
        displayLevel = displayLevel * 0.6f + targetLevel * 0.4f   // smooth decay
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val totalGap = BAR_GAP * (BAR_COUNT + 1)
        val barW     = (width - totalGap) / BAR_COUNT
        val activeCt = (displayLevel * BAR_COUNT).roundToInt()

        for (i in 0 until BAR_COUNT) {
            val left  = BAR_GAP + i * (barW + BAR_GAP)
            val right = left + barW

            // Vary bar height: center bars slightly taller → wave effect
            val heightFrac = 0.55f + 0.45f * sin(PI.toFloat() * i / (BAR_COUNT - 1))
            val barH  = height * heightFrac
            val top   = (height - barH) / 2f

            barRect.set(left, top, right, top + barH)

            paint.color = if (i < activeCt) {
                lerpColors(GRADIENT_COLORS, i.toFloat() / BAR_COUNT)
            } else {
                INACTIVE_COLOR
            }
            canvas.drawRoundRect(barRect, CORNER_R, CORNER_R, paint)
        }
    }

    // ─── colour interpolation ─────────────────────────────────────────────────

    private fun lerpColors(colors: IntArray, frac: Float): Int {
        val scaled = frac * (colors.size - 1)
        val lo     = scaled.toInt().coerceIn(0, colors.size - 2)
        val t      = scaled - lo
        return lerpColor(colors[lo], colors[lo + 1], t)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a)   + (Color.red(b)   - Color.red(a))   * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * t).toInt()
    )
}
