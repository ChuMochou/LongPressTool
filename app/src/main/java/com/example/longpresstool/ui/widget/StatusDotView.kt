package com.example.longpresstool.ui.widget

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.longpresstool.R

/**
 * 侧边栏上的状态指示灯（Phase 6）。
 *
 * ===== 为什么自己画，而不用带 tint 的 View =====
 * 之前是给一个 View 设 shape 背景 + [setBackgroundTintList]。
 * 但那样只能"硬切"颜色，而且没法在指示灯外面加动画光圈。
 * 自己画之后可以做到两件事：
 *
 * 1. 颜色**平滑过渡**（换状态时不是忽然变色，而是插值过去）；
 * 2. 长按中在灯外面加一圈**持续扩散淡出的光圈**——
 *    即使侧边栏在屏幕边缘、用户余光扫到，也能察觉"正在长按"。
 *
 * ===== 无障碍 =====
 * 与 TouchIndicatorView 一样，[animationsEnabled] 为 false 时完全不播动画，
 * 只呈现静态颜色（这是系统"动画时长缩放/移除动画"设置的要求）。
 */
class StatusDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val argbEvaluator = ArgbEvaluator()

    /** 当前显示的颜色（过渡中的中间值）。 */
    private var currentColor: Int = ContextCompat.getColor(context, R.color.status_idle)

    /** 目标颜色。 */
    private var targetColor: Int = currentColor

    /** 是否处于"正在长按"，决定要不要画扩散光圈。 */
    private var isPressing = false

    private var colorAnimator: ValueAnimator? = null
    private var haloAnimator: ValueAnimator? = null

    /** 光圈扩散进度 0f..1f。 */
    private var haloProgress = 0f

    /** 设置指示灯颜色（带过渡）。 */
    fun setDotColor(color: Int, animate: Boolean = true) {
        if (targetColor == color) return
        val from = currentColor
        targetColor = color

        colorAnimator?.cancel()
        if (!animate || !animationsEnabled()) {
            currentColor = color
            invalidate()
            return
        }

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_TRANSITION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                currentColor = argbEvaluator.evaluate(f, from, color) as Int
                invalidate()
            }
            start()
        }
    }

    /** 是否正在长按：控制扩散光圈的启停。 */
    fun setPressing(pressing: Boolean) {
        if (isPressing == pressing) return
        isPressing = pressing

        haloAnimator?.cancel()
        haloAnimator = null

        if (pressing && animationsEnabled()) {
            haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HALO_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    haloProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            // 不播动画时留一个固定的静态光圈，保证"长按中"仍有明显区别
            haloProgress = if (pressing) HALO_STATIC_PROGRESS else 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        // 无限循环动画必须停掉，否则 View 销毁后还在跑，造成内存泄漏。
        colorAnimator?.cancel()
        colorAnimator = null
        haloAnimator?.cancel()
        haloAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        // 指示灯本体只占中间的 62%，剩下的空间留给外圈光圈
        val dotRadius = minOf(width, height) / 2f * 0.62f

        if (haloProgress > 0f) {
            val haloRadius = dotRadius + (minOf(width, height) / 2f - dotRadius) * haloProgress
            haloPaint.color = currentColor
            haloPaint.alpha = ((1f - haloProgress) * HALO_MAX_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, haloRadius, haloPaint)
        }

        dotPaint.color = currentColor
        dotPaint.alpha = 255
        canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)
    }

    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    } catch (e: Exception) {
        true
    }

    companion object {
        private const val COLOR_TRANSITION_MS = 240L
        private const val HALO_MS = 1_300L
        private const val HALO_MAX_ALPHA = 140f
        private const val HALO_STATIC_PROGRESS = 0.55f
    }
}
