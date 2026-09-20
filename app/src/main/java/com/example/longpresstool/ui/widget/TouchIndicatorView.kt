package com.example.longpresstool.ui.widget

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.longpresstool.R

/**
 * 长按位置指示器：一个圆形 + 十字准星。
 *
 * 为什么不用 XML drawable 拼：
 * 十字准星需要"对准圆心"，用 Canvas 直接画最直观，也方便后面做呼吸动画。
 *
 * 视觉状态（对应需求第八节"必须让用户一眼知道正在长按"）：
 * - 未运行：白色半透明填充 + 灰色边框，安静的样式；
 * - 长按中：红色填充 + 更粗的红边 + 一个跟随手指的缩放呼吸动画。
 *
 * 为什么还需要动画：只改颜色在深色背景上可能不够醒目，
 * 加一个轻微的缩放（呼吸）能在任何背景下都让人注意到。
 */
class TouchIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /** 是否正在长按。改变它就会切换配色并启停呼吸动画。 */
    private var isPressing = false

    private var breatheAnimator: ObjectAnimator? = null

    init {
        applyIdleStyle()
    }

    /** 切换到"未运行"样式，并停止动画。 */
    fun setPressing(pressing: Boolean) {
        if (isPressing == pressing) return
        isPressing = pressing

        if (pressing) {
            applyPressingStyle()
            startBreathing()
        } else {
            stopBreathing()
            applyIdleStyle()
        }
        invalidate()
    }

    private fun applyIdleStyle() {
        fillPaint.color = ContextCompat.getColor(context, R.color.indicator_idle_fill)
        strokePaint.color = ContextCompat.getColor(context, R.color.indicator_idle_stroke)
        strokePaint.strokeWidth = dpToPx(2f)
        crosshairPaint.color = ContextCompat.getColor(context, R.color.indicator_idle_stroke)
        crosshairPaint.strokeWidth = dpToPx(1.5f)
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
    }

    private fun applyPressingStyle() {
        fillPaint.color = ContextCompat.getColor(context, R.color.indicator_pressing_fill)
        strokePaint.color = ContextCompat.getColor(context, R.color.indicator_pressing_stroke)
        strokePaint.strokeWidth = dpToPx(4f)   // 边框更粗，和静止状态一眼区分
        crosshairPaint.color = Color.WHITE
        crosshairPaint.strokeWidth = dpToPx(2f)
    }

    /** 轻微缩放（0.94 ~ 1.0）来回播放，形成"呼吸"效果。 */
    private fun startBreathing() {
        stopBreathing()
        breatheAnimator = ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.94f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f)
        ).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopBreathing() {
        breatheAnimator?.cancel()
        breatheAnimator = null
    }

    override fun onDetachedFromWindow() {
        // 必须停掉无限循环的动画，否则 View 销毁后动画还在跑，造成内存泄漏。
        stopBreathing()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        // 减去边框宽度的一半，保证边框完整画在 View 内部，不会被裁掉。
        val radius = minOf(width, height) / 2f - strokePaint.strokeWidth / 2f

        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        // 十字准星的"缝"：正中央留一小段空白，方便精确对准长按点。
        val gap = radius * 0.28f
        val armLength = radius * 0.92f

        canvas.drawLine(centerX, centerY - gap, centerX, centerY - armLength, crosshairPaint)
        canvas.drawLine(centerX, centerY + gap, centerX, centerY + armLength, crosshairPaint)
        canvas.drawLine(centerX - gap, centerY, centerX - armLength, centerY, crosshairPaint)
        canvas.drawLine(centerX + gap, centerY, centerX + armLength, centerY, crosshairPaint)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
