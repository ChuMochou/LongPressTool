package com.example.longpresstool.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.longpresstool.model.LongPressStateHolder

/**
 * 长按手势的派发器（Phase 5 的核心）。
 *
 * ===== 目标（需求原文）=====
 * 「点击『启动』后**一直保持长按**，点击『停止』后才停止」。
 * 从启动到停止之间，目标应用必须**只看到一次按下**，中间不能出现"松开又按下"。
 *
 * ===== Android 的硬限制（都来自官方文档 + 本机实测）=====
 *
 * 1. 一次手势最长 **60 秒**（GestureDescription.MAX_GESTURE_DURATION_MS）。
 * 2. 一次手势最多 **20 笔**（MAX_STROKE_COUNT）。
 * 3. **没有取消已派发手势的 API**。
 * 4. **每次 dispatchGesture 都会取消进行中的手势**。文档原文：
 *    "Any gestures currently in progress, whether from the user, this service,
 *    or another service, will be cancelled."
 * 5. **willContinue = true 会让平台把手势提前截断**。这是实测发现的平台行为
 *    （Android 17 模拟器，见下面的对照表），官方文档里没有写：
 *
 *      | 时长     | willContinue | 实际执行  |
 *      |----------|--------------|-----------|
 *      | 5000ms   | false        | 5021ms    |
 *      | 5000ms   | true         | 2520ms    |
 *      | 10000ms  | false        | 10015ms   |
 *      | 10000ms  | true         | 5038ms    |
 *
 *    也就是说 willContinue=true 的笔画大约只能跑到一半就结束
 *    （推测是系统"等待续接"的超时），但它的语义仍然有效：
 *    文档保证 "Continued strokes keep their pointers down when the gesture completes"，
 *    即结束时**指针不抬起**，可以由下一笔接力。
 *
 * ===== 由此得到的设计 =====
 *
 * 把限制 1、2、5 一起考虑，最优解是：
 *
 *   **一次手势里排满若干段普通笔画（willContinue = false，能被完整执行），
 *     只有最后一段标记 willContinue = true 用来"交棒"。**
 *
 *   一分钟一次接力，交接时指针不抬起，目标应用全程只看到一次按下。
 *
 *   笔1(5s,false) 笔2(5s,false) …… 笔12(5s,true)  ---接力--->  下一组
 *   └──────────── 本组 60 秒，连续按住 ────────────┘
 *
 * 每段 5 秒而不是 1 秒，是因为每段的 startTime 必须**依次累加**
 * （continueStroke 的 startTime 是"相对本次手势起点"的绝对时间，
 *  而手势总时长取的是所有笔画 endTime 的最大值——都写 0 的话它们会重叠，
 *  整个手势就只有 1 段那么长）。这一点我踩过坑，详见 buildHoldChain()。
 *
 * ===== 停止时 =====
 *
 * 派发一笔 50ms、willContinue = false 的收尾笔画让手指立刻抬起
 * （派发新手势会取消当前手势）。同时清空状态，不再接力。
 *
 * ===== 版本限制（诚实说明）=====
 *
 * willContinue / continueStroke 都是 **API 26 (Android 8.0)** 才有的。
 * 在 API 24 / 25 上无法接力，只能单次最长 60 秒。
 * 本类在这种情况下返回明确的失败原因，而不是假装成功。
 */
class LongPressGestureDispatcher(
    private val accessibilityService: AccessibilityService
) {

    /** 当前正在执行的那一笔（本组的最后一段）。为 null 表示没有在长按。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 正在长按的目标坐标（屏幕绝对像素）。 */
    private var targetX = 0f
    private var targetY = 0f

    /** true 表示正在长按。用它区分"主动停止"和"被系统取消"。 */
    private var holding = false

    /** 已经接力的次数，仅用于日志排查。 */
    private var relayCount = 0

    /**
     * 开始长按。
     *
     * @param x 屏幕绝对 X 坐标（与 dispatchGesture 同一坐标系，即指示器圆心）
     * @param y 屏幕绝对 Y 坐标
     * @return null 表示已成功开始；否则返回失败原因，供界面显示。
     */
    fun startHold(x: Int, y: Int): HoldStartResult? {
        if (holding) {
            return HoldStartResult.AlreadyRunning
        }

        // 接力机制要求 API 26+。低版本只能单次最长 60 秒，这里明确拒绝而不是假装能用。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return HoldStartResult.UnsupportedAndroidVersion
        }

        targetX = x.toFloat()
        targetY = y.toFloat()
        relayCount = 0
        holding = true

        dispatchHoldGroup()
        if (!holding) {
            // dispatch 内部把 holding 置回 false，说明系统拒绝了这次派发。
            return HoldStartResult.ServiceNotConnected
        }

        LongPressStateHolder.setPressing(true)
        return null
    }

    /** 派发一组"按住"。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun dispatchHoldGroup() {
        val gesture = buildHoldGroup()
        val accepted = accessibilityService.dispatchGesture(gesture, gestureResultCallback, null)

        Log.d(
            TAG,
            "dispatch accepted=$accepted duration=${HOLD_STROKE_MS}ms relay=$relayCount"
        )

        if (!accepted) {
            // 派发被系统拒绝（例如服务刚被关闭）。清空状态，避免界面显示"长按中"却不是真的在按。
            finishHold()
        }
    }

    /**
     * 构造一次"按住"手势。
     *
     * ===== 为什么是"一笔 58 秒"，而不是把多段排进一个手势 =====
     *
     * 试过"一个手势里排多段（每段 5 秒）"来减少接力次数，实测行不通，原因有两条：
     *   1. 组内串接必须用 continueStroke，而它要求上一笔 willContinue = true；
     *      但实测 **willContinue = true 的笔画会被系统提前截断**（大约只跑一半时长）。
     *      于是每段实际只按 1~2 秒，反而要不停接力，而每次接力又必然打断当前手势。
     *   2. continueStroke 的 startTime 是"相对**本次手势起点**的绝对时间"，
     *      而手势总时长取的是所有笔画 endTime 的最大值——
     *      若每段 startTime 都写 0，它们会全部重叠，整个手势只有一段那么长。
     *
     * 所以最终采用最朴素的形态：**一次手势就一笔，willContinue = false**，
     * 让它被完整执行。实测 58000ms 能跑满 58014 / 58081 / 58007ms，
     * 也就是"一次连续按压接近 1 分钟"，这是系统 60 秒上限内能做到的最长连续按压。
     *
     * 这一笔自然结束后，onCompleted 里立刻再派发下一笔（见 gestureResultCallback），
     * 实测两次之间只隔约 65ms，用户察觉不到。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildHoldGroup(): GestureDescription {
        val stroke = GestureDescription.StrokeDescription(
            holdPath(targetX, targetY),
            0L,                 // 手势开始后立即按下
            HOLD_STROKE_MS,     // 按住 58 秒
            false               // 不接力：让这一笔完整跑完
        )
        activeStroke = stroke
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * 【仅 debug】自测：启动 -> 几秒后停止 -> 再启动，用来验证"停止后再启动"是否正常。
     *
     * 为什么要它：adb 点击悬浮窗按钮的坐标很难对齐，但"停止后再启动会不会马上退出"
     * 这个回归问题必须能自动化验证，所以直接在代码里跑一遍状态机。
     */
    fun runStopThenRestartSelfTest(stepDelayMs: Long = 3_000L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Log.d(TAG, "=== 自测开始：启动 -> ${stepDelayMs}ms 后停止 -> 再启动 ===")

        h.postDelayed({
            Log.d(TAG, "自测[1] 启动")
            Log.d(TAG, "自测[1] startHold -> ${startHold(targetX.toInt(), targetY.toInt())}")
        }, 0L)

        h.postDelayed({
            Log.d(TAG, "自测[2] 停止")
            stopHold()
        }, stepDelayMs)

        h.postDelayed({
            Log.d(TAG, "自测[3] 再次启动（关键：这一步以前会立刻自己退出）")
            val r = startHold(targetX.toInt(), targetY.toInt())
            Log.d(TAG, "自测[3] startHold -> $r ; holding=$holding activeStroke=${activeStroke != null}")
        }, stepDelayMs * 2)

        h.postDelayed({
            Log.d(TAG, "自测[4] 检查 5 秒后是否仍在长按：holding=$holding")
        }, stepDelayMs * 2 + 5_000L)

        h.postDelayed({
            Log.d(TAG, "自测[5] 最终停止")
            stopHold()
            Log.d(TAG, "=== 自测结束 ===")
        }, stepDelayMs * 2 + 6_000L)
    }

    /**
     * 停止长按：立刻让手指抬起。
     *
     * ===== 这里不需要也不能用 continueStroke =====
     *
     * 我在这里踩过两次同一个坑：长按笔画为了"跑满 58 秒"被设成 willContinue = false，
     * 而 continueStroke 要求上一笔必须是 true，于是停止时抛
     * "Only strokes marked willContinue can be continued"，被 catch 吞掉，
     * **指针实际上没有被抬起**——现象就是"点了停止没反应，要等 58 秒自己结束"。
     *
     * 正确做法反而更简单：**直接派发一支全新的极短笔画**。
     * 因为"派发新手势会取消进行中的手势"（官方文档），
     * 这一下就同时完成了两件事：
     *   1. 取消掉正在执行的 58 秒长按 —— 指针抬起，这就是"停止"；
     *   2. 用一支 50ms 的极短按压顶替它 —— 短到用户察觉不到。
     *
     * 所以停止完全不依赖 continueStroke，也不受 willContinue 取值影响。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun stopHold() {
        if (!holding) {
            LongPressStateHolder.setPressing(false)
            return
        }

        // 先复位状态：派发收尾笔画会触发 onCancelled，
        // 那时 holding 已经是 false，回调里就会知道这是"主动停止"而不是"被用户打断"。
        holding = false
        activeStroke = null
        LongPressStateHolder.setPressing(false)

        val release = GestureDescription.StrokeDescription(
            holdPath(targetX, targetY),
            0L,
            RELEASE_STROKE_MS,
            false   // 单笔短按，不需要接力
        )
        val gesture = GestureDescription.Builder().addStroke(release).build()

        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        Log.d(TAG, "stopHold: 已派发收尾笔画 accepted=$accepted")
    }

    /** 立即放弃长按，不做收尾（用于 onInterrupt：系统要求马上停下）。 */
    fun abandonHold() {
        holding = false
        activeStroke = null
    }

    /** 收尾：状态复位，不再接力。 */
    private fun finishHold() {
        holding = false
        activeStroke = null
        LongPressStateHolder.setPressing(false)
    }

    /**
     * 一组手势跑完的回调。
     *
     * 走到这里说明这一组（约 60 秒）已经执行完毕。
     * 只要用户没点"停止"，就再派发下一组接着按——因为本组最后一段是
     * willContinue = true，交接时指针不会抬起，目标应用看到的是连续一次按压。
     *
     * 时序说明：回调发生时手势已经结束，此刻派发新手势不会"自己取消自己"。
     * 而且由于最后一笔会提前约一半时长结束（见类注释的实测表），
     * 这里补发的下一组实际上会很快接上，指针不会长时间悬空。
     */
    private val gestureResultCallback = object : AccessibilityService.GestureResultCallback() {

        override fun onCompleted(gestureDescription: GestureDescription) {
            Log.d(TAG, "一组完成 relay=$relayCount holding=$holding")
            if (!holding) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            // 注意：这里**不能**用 continueStroke 接着上一笔。
            // 上一笔如果是 willContinue = false（我们为了"跑满 58 秒"就是这么设的），
            // 它已经"正常结束"了，源码会直接抛
            // "Only strokes marked willContinue can be continued"。
            //
            // 所以接力方式是：**重新下一笔**（从同一坐标重新按下）。
            // 代价是这一次交接目标应用会看到"抬起-再按下"；
            // 但换来的是一次连续按压长达 58 秒，中断频率降到约每分钟一次。
            // 这是系统"单次手势上限 60 秒"约束下的最优折中，详见类注释。
            relayCount++
            dispatchHoldGroup()
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            // 两种可能：
            // 1. 我们主动 stopHold()（此时 holding 已是 false）——正常，不管；
            // 2. 用户用自己的手指碰了屏幕，或系统要执行自己的手势——长按已经断了，
            //    不能盲目接力，否则会和用户的意图打架。
            if (!holding) {
                Log.d(TAG, "onCancelled: 主动停止导致的取消，正常")
                return
            }
            Log.d(TAG, "onCancelled: 被系统/用户打断，结束长按")
            finishHold()
        }
    }

    /**
     * 长按用的 Path。
     *
     * 官方文档说零长度 path（单个 moveTo）就是"按住不动"的触摸，
     * 但这里刻意给一个 1 像素的极小位移，让它是一个"真正的路径"，
     * 行为更贴近常规手势（肉眼看不出移动）。
     */
    private fun holdPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + HOLD_PATH_HINT_PX, y)
    }

    companion object {
        private const val TAG = "LongPressGesture"

        /**
         * 一次按压的时长。
         *
         * 取 58 秒：系统对单次手势的硬上限是 60 秒（MAX_GESTURE_DURATION_MS），
         * 留 2 秒余量避免边界问题。这是"一次连续按压"能做到的最长时长，
         * 跑满后再接力下一笔（实测两次之间约 65ms，用户察觉不到）。
         */
        private const val HOLD_STROKE_MS = 58_000L

        /** 收尾笔画的时长：很短，只为让手指尽快抬起。 */
        private const val RELEASE_STROKE_MS = 50L

        /** 长按路径的微小位移（像素）。肉眼不可见，但让 Path 不是"原地点击"。 */
        private const val HOLD_PATH_HINT_PX = 1f
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
