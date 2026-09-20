package com.example.longpresstool.ui.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.longpresstool.R

/**
 * 长按位置指示器：一个圆形 + 十字准星。
 *
 * 为什么不用 XML drawable 拼：
 * 十字准星要"对准圆心"，用 Canvas 直接画最直观，也方便做动画。
 *
 * ===== 两种视觉状态（需求第八节：必须让人一眼看出正在长按）=====
 *
 * 未运行（安静样式）：
 *   - 白色半透明填充 + 灰色边框 + 灰色十字准星
 *
 * 长按中（刻意做得极度醒目，叠了三重效果）：
 *   1. 填充变红、边框加粗到 4dp、十字准星变白；
 *   2. 外圈多出一道**旋转的红色虚线环**——连静态截图都能一眼看出不一样；
 *   3. 整体做 0.94 ↔ 1.0 的"呼吸"缩放。
 *
 * 只靠变色是不够的：在深色或有花纹的背景上，单凭颜色差异容易看不出来，
 * 所以额外加了"旋转虚线环"这种形状层面的区别。
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

    /** 长按中才画的旋转虚线环。 */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 用于画虚线环的矩形，复用对象避免每次 onDraw 都新建。 */
    private val ringBounds = RectF()

    /** 虚线环的旋转角度，由动画持续更新。 */
    private var ringRotation = 0f

    /** 是否正在长按。改变它就会切换配色并启停动画。 */
    private var isPressing = false

    private var breatheAnimatorX: ObjectAnimator? = null
    private var breatheAnimatorY: ObjectAnimator? = null
    private var ringAnimator: ValueAnimator? = null

    init {
        applyIdleStyle()
    }

    /** 切换到"长按中"/"未运行"样式，并启停动画。 */
    fun setPressing(pressing: Boolean) {
        if (isPressing == pressing) return
        isPressing = pressing

        if (pressing) {
            applyPressingStyle()
            startAnimations()
        } else {
            stopAnimations()
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
        ringRotation = 0f
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

        ringPaint.color = ContextCompat.getColor(context, R.color.indicator_pressing_stroke)
        ringPaint.strokeWidth = dpToPx(2.5f)
        // 虚线：实线段 + 间隔，旋转起来会明显"在动"
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(dpToPx(7f), dpToPx(5f)), 0f)
    }

    private fun startAnimations() {
        stopAnimations()

        // 动画 1：整体轻微缩放，形成"呼吸"。X / Y 必须一起动，否则圆会被拉扁。
        breatheAnimatorX = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, BREATHE_MIN_SCALE).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            duration = BREATHE_DURATION_MS
            start()
        }
        breatheAnimatorY = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, BREATHE_MIN_SCALE).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            duration = BREATHE_DURATION_MS
            start()
        }

        // 动画 2：外圈虚线环匀速旋转。这是"形状层面"的变化，最容易看见。
        ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = RING_ROTATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                ringRotation = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 记录真实状态，便于排查"动画没生效"：
        // 如果 animatorScale 是 0，说明系统关掉了动画（开发者选项 → 动画时长缩放 / 移除动画），
        // 那就不是代码的问题。
        Log.d(
            TAG,
            "长按动画已启动: breathe=${breatheAnimatorX?.duration}ms ring=${ringAnimator?.duration}ms " +
                "animatorScale=${animatorDurationScale()}"
        )
    }

    private fun stopAnimations() {
        breatheAnimatorX?.cancel()
        breatheAnimatorX = null
        breatheAnimatorY?.cancel()
        breatheAnimatorY = null
        ringAnimator?.cancel()
        ringAnimator = null
    }

    /** 读取系统的"动画时长缩放"，0 表示用户/系统关闭了动画。 */
    private fun animatorDurationScale(): Float = try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    } catch (e: Exception) {
        1f
    }

    override fun onDetachedFromWindow() {
        // 必须停掉无限循环的动画，否则 View 销毁后动画还在跑，造成内存泄漏。
        stopAnimations()
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

        // 十字准星：正中央留一小段空白，方便精确对准长按点。
        val gap = radius * 0.28f
        val armLength = radius * 0.92f

        canvas.drawLine(centerX, centerY - gap, centerX, centerY - armLength, crosshairPaint)
        canvas.drawLine(centerX, centerY + gap, centerX, centerY + armLength, crosshairPaint)
        canvas.drawLine(centerX - gap, centerY, centerX - armLength, centerY, crosshairPaint)
        canvas.drawLine(centerX + gap, centerY, centerX + armLength, centerY, crosshairPaint)

        // 长按中：在圆外侧画一圈旋转的红色虚线环。
        if (isPressing) {
            val ringRadius = radius + dpToPx(5f)
            ringBounds.set(
                centerX - ringRadius,
                centerY - ringRadius,
                centerX + ringRadius,
                centerY + ringRadius
            )
            // 旋转画布来实现"虚线在转"
            canvas.save()
            canvas.rotate(ringRotation, centerX, centerY)
            canvas.drawArc(ringBounds, 0f, 360f, false, ringPaint)
            canvas.restore()
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    companion object {
        private const val TAG = "TouchIndicatorView"

        private const val BREATHE_DURATION_MS = 700L
        private const val BREATHE_MIN_SCALE = 0.9f
        private const val RING_ROTATION_DURATION_MS = 2_000L
    }
}
