package com.example.longpresstool.ui.widget

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.longpresstool.R

/**
 * 长按位置指示器：一个圆形 + 十字准星（Phase 6：三态动画）。
 *
 * ===== 为什么不用 XML drawable 拼 =====
 * 十字准星要"对准圆心"，而且状态之间要做**渐变过渡**（颜色插值、边框变粗），
 * 用 Canvas 直接画最直观，也最好控制动画。
 *
 * ===== 三种视觉状态（对应需求第八节）=====
 *
 * 1. 未运行（"普通样式"）
 *    - 白色半透明填充 + 灰色边框 + 灰色十字准星
 *    - 加一个**很轻微**的透明度呼吸（1.0 ↔ 0.72，1.6 秒一次），
 *      用来暗示"这个圆是可以拖动的"；幅度刻意做小，避免和"长按中"混淆。
 *
 * 2. 正在选择位置
 *    - 和"未运行"一样，因为这时它就是普通的可拖动目标。
 *      （"选择中"的提示交给侧边栏的状态灯和提示文字。）
 *
 * 3. 长按中（**必须一眼看出来**，所以叠了四重效果）
 *    - a) 填充/边框/十字颜色**平滑过渡**到红色（而不是硬切）
 *    - b) 边框由 2dp 变粗到 5dp（同样是过渡）
 *    - c) 外圈一圈**旋转的红色虚线环**——形状层面的差异，静止截图也能分辨
 *    - d) 一圈向外扩散并淡出的**红色波纹**——表示"正在持续按压"
 *
 * 停止后：所有动画停止，样式平滑回到"未运行"。
 *
 * ===== 无障碍：必须尊重系统的动画设置 =====
 * 如果用户在开发者选项里把"动画时长缩放"设为 0（或开启了"移除动画"），
 * 那么**所有动画都不应该播放**（连续闪烁/缩放可能诱发光敏不适）。
 * 本类通过 [animationsEnabled] 统一判断，关闭时直接呈现最终静态样子。
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

    /** 长按中向外扩散的波纹。 */
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /** 复用对象，避免每次 onDraw 都新建。 */
    private val ringBounds = RectF()
    private val pulseBounds = RectF()

    /** 虚线环的旋转角度，由动画持续更新。 */
    private var ringRotation = 0f

    /** 波纹的扩散进度 0f..1f。 */
    private var pulseProgress = 0f

    /** 0f = 未运行样式，1f = 长按中样式。颜色和边框宽度都按它插值。 */
    private var pressProgress = 0f

    private val argbEvaluator = ArgbEvaluator()

    // 未运行样式
    private val idleFill: Int = ContextCompat.getColor(context, R.color.indicator_idle_fill)
    private val idleStroke: Int = ContextCompat.getColor(context, R.color.indicator_idle_stroke)
    // 长按样式
    private val pressingFill: Int = ContextCompat.getColor(context, R.color.indicator_pressing_fill)
    private val pressingStroke: Int = ContextCompat.getColor(context, R.color.indicator_pressing_stroke)
    private val pulseColor: Int = ContextCompat.getColor(context, R.color.indicator_pulse)

    private var isPressing = false

    private var pressTransition: ValueAnimator? = null
    private var breatheAnimator: ObjectAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        applyStyleForProgress(0f)
        // 未运行时也有一个很轻的透明度呼吸，提示"可以拖动"
        startIdleBreathing()
    }

    /**
     * 切换状态。true = 长按中。
     *
     * 注意这里是**平滑过渡**：颜色与边框宽度按 pressProgress 从 0 动画到 1（或反向），
     * 而不是直接换值，避免状态忽然"闪"一下。
     */
    fun setPressing(pressing: Boolean) {
        if (isPressing == pressing) return
        isPressing = pressing
        Log.d(TAG, "setPressing($pressing) animationsEnabled=${animationsEnabled()}")

        if (pressing) {
            startIdleBreathingStop()
            animatePressProgress(1f)
            startPressingAnimations()
        } else {
            animatePressProgress(0f)
            stopPressingAnimations()
        }
    }

    // ==================== 状态过渡 ====================

    private fun animatePressProgress(target: Float) {
        pressTransition?.cancel()

        if (!animationsEnabled()) {
            // 系统关闭了动画：直接呈现最终样子（这是无障碍要求，不能硬播动画）
            pressProgress = target
            applyStyleForProgress(target)
            invalidate()
            return
        }

        pressTransition = ValueAnimator.ofFloat(pressProgress, target).apply {
            duration = PRESS_TRANSITION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                applyStyleForProgress(pressProgress)
                invalidate()
            }
            start()
        }
    }

    /** 按过渡进度 0f..1f 计算颜色与边框宽度。 */
    private fun applyStyleForProgress(p: Float) {
        fillPaint.color = argbEvaluator.evaluate(p, idleFill, pressingFill) as Int
        strokePaint.color = argbEvaluator.evaluate(p, idleStroke, pressingStroke) as Int
        // 边框从 2dp 过渡到 5dp，让"变粗"这件事也可感知
        strokePaint.strokeWidth = dpToPx(2f + 3f * p)

        // 十字准星：灰色 -> 白色
        crosshairPaint.color = argbEvaluator.evaluate(p, idleStroke, Color.WHITE) as Int
        crosshairPaint.strokeWidth = dpToPx(1.5f + 0.5f * p)

        // 虚线环配色（环本身只在长按时才画，见 onDraw）
        ringPaint.color = pressingStroke
        ringPaint.strokeWidth = dpToPx(2.5f)
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(dpToPx(7f), dpToPx(5f)), 0f)

        pulsePaint.color = pulseColor
        pulsePaint.strokeWidth = dpToPx(2f)
    }

    // ==================== 未运行：轻微呼吸 ====================

    private fun startIdleBreathing() {
        if (!animationsEnabled()) return
        breatheAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, IDLE_MIN_ALPHA).apply {
            duration = IDLE_BREATHE_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun startIdleBreathingStop() {
        breatheAnimator?.cancel()
        breatheAnimator = null
        alpha = 1f
    }

    // ==================== 长按中：旋转虚线环 + 扩散波纹 ====================

    private fun startPressingAnimations() {
        stopPressingAnimations()

        // 旋转虚线环：匀速转动，形状层面的变化最容易被看见
        if (animationsEnabled()) {
            ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = RING_ROTATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    ringRotation = it.animatedValue as Float
                    invalidate()
                }
                start()
            }

            // 扩散波纹：0 -> 1 反复播放，波纹边扩散边淡出
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PULSE_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulseProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            // 关闭动画时也要有静态差异：画一圈固定的波纹
            ringRotation = 0f
            pulseProgress = PULSE_STATIC_PROGRESS
            invalidate()
        }
    }

    private fun stopPressingAnimations() {
        ringAnimator?.cancel()
        ringAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        ringRotation = 0f
        pulseProgress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // 必须停掉无限循环的动画，否则 View 销毁后动画还在跑，造成内存泄漏。
        pressTransition?.cancel()
        pressTransition = null
        breatheAnimator?.cancel()
        breatheAnimator = null
        ringAnimator?.cancel()
        ringAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - strokePaint.strokeWidth / 2f

        // ---- 长按中：先画最外层的扩散波纹（在圆下面，像涟漪）----
        if (isPressing && pulseProgress > 0f) {
            val maxExtra = dpToPx(14f)
            val pulseRadius = radius + maxExtra * pulseProgress
            // 越扩散越透明
            pulsePaint.alpha = ((1f - pulseProgress) * PULSE_MAX_ALPHA).toInt().coerceIn(0, 255)
            pulseBounds.set(
                centerX - pulseRadius,
                centerY - pulseRadius,
                centerX + pulseRadius,
                centerY + pulseRadius
            )
            canvas.drawArc(pulseBounds, 0f, 360f, false, pulsePaint)
        }

        // ---- 圆本体 ----
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        // ---- 十字准星（正中央留一小段空白，方便精确对准长按点）----
        val gap = radius * 0.28f
        val armLength = radius * 0.92f
        canvas.drawLine(centerX, centerY - gap, centerX, centerY - armLength, crosshairPaint)
        canvas.drawLine(centerX, centerY + gap, centerX, centerY + armLength, crosshairPaint)
        canvas.drawLine(centerX - gap, centerY, centerX - armLength, centerY, crosshairPaint)
        canvas.drawLine(centerX + gap, centerY, centerX + armLength, centerY, crosshairPaint)

        // ---- 长按中：圆外侧的旋转红色虚线环 ----
        if (isPressing) {
            val ringRadius = radius + dpToPx(5f)
            ringBounds.set(
                centerX - ringRadius,
                centerY - ringRadius,
                centerX + ringRadius,
                centerY + ringRadius
            )
            canvas.save()
            canvas.rotate(ringRotation, centerX, centerY)
            canvas.drawArc(ringBounds, 0f, 360f, false, ringPaint)
            canvas.restore()
        }
    }

    // ==================== 工具 ====================

    /**
     * 系统是否允许播放动画。
     *
     * 用户在开发者选项里把"动画时长缩放"设为 0（或某些省电/无障碍场景）时，
     * 我们不应该再播放旋转、扩散这类动画——那既无意义，也可能让部分用户不适。
     * 读取失败时按"允许"处理（宁可动画正常，也不要因为读不到设置就完全不动）。
     */
    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    } catch (e: Exception) {
        true
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    companion object {
        private const val TAG = "TouchIndicatorView"

        /** 状态切换的过渡时长。太快会显得生硬，太慢会让人以为没响应。 */
        private const val PRESS_TRANSITION_MS = 220L

        /** 未运行时透明度呼吸的周期与最低透明度（幅度刻意做小）。 */
        private const val IDLE_BREATHE_MS = 1_600L
        private const val IDLE_MIN_ALPHA = 0.72f

        /** 旋转虚线环转一圈的时间。 */
        private const val RING_ROTATION_MS = 1_800L

        /** 扩散波纹的周期。 */
        private const val PULSE_MS = 1_400L

        /** 波纹最亮时的透明度（0..255）。 */
        private const val PULSE_MAX_ALPHA = 150f

        /** 关闭动画时波纹停在的进度，保证仍有静态可见的差异。 */
        private const val PULSE_STATIC_PROGRESS = 0.5f
    }
}
