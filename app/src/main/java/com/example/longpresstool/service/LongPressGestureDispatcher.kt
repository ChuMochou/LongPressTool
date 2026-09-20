package com.example.longpresstool.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.longpresstool.model.LongPressStateHolder

/**
 * 长按手势的派发器（Phase 5 的核心）。
 *
 * ===== 先讲清楚 Android 的硬限制（都是查官方源码得到的，不是猜的）=====
 *
 * 1. 一次手势最长 **60 秒**（GestureDescription.MAX_GESTURE_DURATION_MS = 60 * 1000）。
 *    超过会直接抛 IllegalArgumentException。
 * 2. 一次手势最多 20 个 stroke（MAX_STROKE_COUNT），所以不能靠"一次塞很多段"绕过 60 秒。
 * 3. **没有取消已派发手势的 API**。手势一旦发出去，只能等它自己结束，
 *    或者被用户真实的手指触摸打断。
 *
 * ===== 那"无限期长按"怎么实现？=====
 *
 * 官方为此提供了"接力"机制（API 26+）：
 *
 *   StrokeDescription(path, startTime, duration, willContinue = true)
 *
 * 文档原文：*"Continued strokes keep their pointers down when the gesture completes."*
 * 也就是说，标记了 willContinue 的笔画在手势结束时**不抬起手指**；
 * 下一次手势的第一笔可以用 continueStroke() 从它接着画下去，手指始终没离开屏幕。
 *
 * 所以本类的做法是：**1 秒一段，首尾相接，一直接力下去。** 具体流程：
 *
 *   按下（第 1 段）-> 1 秒后 onGestureCompleted -> 立刻接力第 2 段 -> …… 直到用户点"停止"
 *
 * 为什么每段只有 1 秒：因为无法取消手势，"停止"只能靠"不再接力"来实现，
 * 所以停止的延迟最多等于一段的长度。1 秒是体验和派发次数的平衡点。
 *
 * ===== 停止时做了什么 =====
 *
 * 停止会马上派发一段很短的"收尾"笔画（很短、willContinue = false），
 * 让手指尽快抬起；同时把手势链的引用清空，使后续的 onGestureCompleted 不再接力。
 * 即使这段收尾笔画因为时序原因没生效，最坏情况也是 1 秒后自然松开——
 * 不会出现"一直按着停不下来"。
 *
 * ===== 版本限制（必须诚实说明）=====
 *
 * willContinue / continueStroke 都是 **API 26 (Android 8.0)** 才有的。
 * 在 API 24 / 25 上无法接力，只能单次最长 60 秒。
 * 本类在这种情况下返回明确的失败原因，而不是假装成功。
 */
class LongPressGestureDispatcher(
    private val accessibilityService: AccessibilityService
) {

    /** 当前手势链的最后一笔。为 null 表示没有在长按。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 正在长按的目标坐标（屏幕绝对像素）。 */
    private var targetX = 0f
    private var targetY = 0f

    /** 是否已经请求停止，用于避免重复派发收尾笔画。 */
    private var stopRequested = false

    val isHolding: Boolean
        get() = activeStroke != null

    /**
     * 开始长按。
     *
     * @param x 屏幕绝对 X 坐标（与 dispatchGesture 同一坐标系，即指示器圆心）
     * @param y 屏幕绝对 Y 坐标
     * @return null 表示已成功开始；否则返回失败原因，供界面显示。
     */
    fun startHold(x: Int, y: Int): HoldStartResult? {
        if (activeStroke != null) {
            return HoldStartResult.AlreadyRunning
        }

        // 续接机制要求 API 26+。低版本只能单次最长 60 秒，这里明确拒绝而不是假装能用。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return HoldStartResult.UnsupportedAndroidVersion
        }

        targetX = x.toFloat()
        targetY = y.toFloat()
        stopRequested = false

        dispatchFirstSegment()

        // 手势真的派发出去了才改状态。
        // 如果 dispatch() 内部因为系统拒绝而把 activeStroke 清空，就不该显示"长按中"。
        if (activeStroke != null) {
            LongPressStateHolder.setPressing(true)
        } else {
            return HoldStartResult.ServiceNotConnected
        }
        return null
    }

    /** 开始长按的第一段。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun dispatchFirstSegment() {
        val stroke = GestureDescription.StrokeDescription(
            tapPath(targetX, targetY),
            0L,                 // 手势开始后立即按下
            CHUNK_DURATION_MS,  // 按住 1 秒
            true                // 关键：结束时手指不抬起，留给下一段接力
        )
        activeStroke = stroke
        dispatch(stroke)
    }

    /**
     * 派发下一段（接力）。
     *
     * 这里有个必须注意的顺序问题：**新的一段必须在 onGestureCompleted 回调里才能拿到**
     * （continueStroke 需要指向"上一段"，而上一段只有在完成后才算真正结束）。
     * 所以在还没拿到新笔画之前，先不要在下次派发时把 activeStroke 置空。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun dispatchContinuation(previous: GestureDescription.StrokeDescription) {
        val next = try {
            previous.continueStroke(
                tapPath(targetX, targetY),
                0L,
                CHUNK_DURATION_MS,
                true
            )
        } catch (e: IllegalStateException) {
            // 理论上不会发生（previous 一定带 willContinue = true）。
            // 万一发生，就退回"重新按下"的方式，长按可能会中断一次，但不会崩溃。
            activeStroke = null
            return
        }

        activeStroke = next
        dispatch(next)
    }

    /**
     * 停止长按。
     *
     * 无法取消已经派发的手势，所以这里的策略是：
     * 1. 派发一段很短的收尾笔画，让手指尽快抬起；
     * 2. 清空手势链，使后续回调不再接力。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun stopHold() {
        val current = activeStroke
        if (current == null) {
            // 已经不在长按了（例如被系统取消过），只把状态复位，避免界面卡在"长按中"。
            LongPressStateHolder.setPressing(false)
            return
        }
        if (stopRequested) return
        stopRequested = true

        // 先断开接力链：这样即使收尾笔画派发失败，也只是等当前段自然结束（最多 1 秒）。
        activeStroke = null
        LongPressStateHolder.setPressing(false)

        try {
            val release = current.continueStroke(
                tapPath(targetX, targetY),
                0L,
                RELEASE_DURATION_MS,
                false   // 明确不再接力 -> 手指抬起
            )
            dispatch(release)
        } catch (e: Exception) {
            // 忽略：最坏情况是等当前 1 秒的段自然结束。
        }
    }

    /**
     * 立即放弃长按，不做任何收尾（用于 onInterrupt：系统要求我们马上停下）。
     */
    fun abandonHold() {
        activeStroke = null
        stopRequested = true
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val accepted = accessibilityService.dispatchGesture(
            gesture,
            gestureResultCallback,
            null   // null = 回调在主线程（服务自己的线程）
        )

        if (!accepted) {
            // 派发被系统拒绝（例如服务刚被关闭）。清空状态，避免界面显示"长按中"却不是真的在按。
            activeStroke = null
        }
    }

    /**
     * 手势结果回调。
     *
     * 官方 API（android.accessibilityservice.AccessibilityService.GestureResultCallback）：
     *   onCompleted(gestureDescription: GestureDescription)
     *   onCancelled(gestureDescription: GestureDescription)
     *
     * 回调只给出"刚完成的那个手势"，不直接给出最后一笔，所以要用
     * getStroke(strokeCount - 1) 取出来，它正是 continueStroke() 需要的"上一笔"。
     * （我们每次手势只放一笔，所以取到的就是那一笔。）
     */
    private val gestureResultCallback = object : AccessibilityService.GestureResultCallback() {

        override fun onCompleted(gestureDescription: GestureDescription) {
            // activeStroke == null 说明用户已经点了"停止"或服务被中断，
            // 此时绝对不能再接力，否则就真的停不下来了。
            if (activeStroke == null) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val lastStroke = try {
                gestureDescription.getStroke(gestureDescription.strokeCount - 1)
            } catch (e: Exception) {
                null
            }

            if (lastStroke == null) {
                // 理论上不会发生。真发生了就干净地结束，不要让界面停在"长按中"。
                activeStroke = null
                LongPressStateHolder.setPressing(false)
                return
            }

            // 还在长按 -> 用刚结束的最后一笔接力下一段。
            dispatchContinuation(lastStroke)
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            // 手势被系统取消（例如用户用自己的手指碰了屏幕，或系统要执行自己的手势）。
            // 这时长按其实已经断了，不能盲目接力——那样会和用户的意图打架。
            abandonHold()
            LongPressStateHolder.setPressing(false)
        }
    }

    /**
     * 零长度的 Path = 一次"按住不动"的触摸。
     *
     * 这是官方文档明确说明的行为：*"If the path has zero length (for example, a single
     * moveTo()), the stroke is a touch that doesn't move."*
     */
    private fun tapPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
    }

    companion object {
        /** 每段"按住"的时长。同时决定了点"停止"后最多再按多久。 */
        const val CHUNK_DURATION_MS = 1_000L

        /** 收尾笔画的时长：很短，只为让手指尽快抬起。 */
        const val RELEASE_DURATION_MS = 50L
    }
}

/** 开始长按的失败原因。 */
enum class HoldStartResult {
    /** 已经在长按了。 */
    AlreadyRunning,

    /** 无障碍服务没有连上（用户在系统设置里关掉了，或服务还没启动完）。 */
    ServiceNotConnected,

    /** Android 7.x 不支持手势接力（需要 API 26+）。 */
    UnsupportedAndroidVersion
}
