package com.example.longpresstool.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.example.longpresstool.model.LongPressStateHolder

/**
 * 执行长按手势的无障碍服务。
 *
 * ===== 为什么"跨应用模拟触摸"必须走无障碍服务？ =====
 * 普通应用不能向其他应用注入触摸事件，这是 Android 的安全边界。
 * 唯一的官方途径是让用户主动开启一个无障碍服务，由系统把 dispatchGesture() 的能力授予它。
 * 也就是说：
 *
 *   - 这不违反 Android 安全机制，它**就是** Android 官方提供的机制；
 *   - 但用户必须知情并在系统设置里手动开启，App 无法自己给自己授权。
 *
 * ===== 本服务需要的能力 =====
 * 只有一项：派发手势（res/xml/accessibility_service_config.xml 里的 canPerformGestures="true"）。
 * 我们**不**读取任何界面内容。
 *
 * ===== 本类的职责 =====
 * 它自己不做重活，只负责：
 * 1. 维护"服务是否可用"的状态；
 * 2. 持有 [LongPressGestureDispatcher]（真正派发手势的地方）；
 * 3. 在系统要求中断时立刻放手。
 *
 * ===== 生命周期（需求第十三节） =====
 * onServiceConnected -> 服务可用，创建派发器
 * onUnbind            -> 用户在系统设置里关掉了
 * onInterrupt         -> 系统要求中断，此时必须立刻结束正在执行的手势
 * onDestroy           -> 服务销毁
 *
 * 特别注意：这些回调**不能覆盖所有情况**。如果进程被系统直接回收，
 * 一个回调都不会有。所以界面侧还会主动去系统里查询服务是否开启，
 * 见 permission/AccessibilityPermission.kt。两条路一起用才可靠。
 */
class LongPressAccessibilityService : AccessibilityService() {

    /**
     * 手势派发器。只在服务连接期间存在。
     * 用 lateinit 不行（可能在未连接时被访问），所以用可空类型 + 判空。
     */
    private var gestureDispatcher: LongPressGestureDispatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        gestureDispatcher = LongPressGestureDispatcher(this)
        LongPressStateHolder.setAccessibilityState(enabled = true, connected = true)
    }

    /**
     * 收到界面事件。本服务不需要观察任何事件，直接忽略即可。
     * （eventTypes 是配置里的必填项，所以系统仍然会回调进来。）
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理。
    }

    /**
     * 系统要求中断当前操作（例如用户触发了系统手势、或另一个无障碍服务要求独占）。
     * 这里必须**立刻放手**，否则手指会一直按在屏幕上。
     */
    override fun onInterrupt() {
        gestureDispatcher?.abandonHold()
        LongPressStateHolder.setPressing(false)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseEverything()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseEverything()
        super.onDestroy()
    }

    private fun releaseEverything() {
        gestureDispatcher?.abandonHold()
        gestureDispatcher = null
        instance = null
        LongPressStateHolder.setAccessibilityState(enabled = false, connected = false)
        LongPressStateHolder.setPressing(false)
    }

    companion object {
        /**
         * 当前已连接的服务实例。
         *
         * 这是本项目里唯一一个"静态持有组件实例"的地方，值得解释清楚：
         * 无障碍服务由**系统**创建和管理，我们无法像普通 Service 那样自己 new 一个；
         * 而悬浮侧边栏需要让它去派发手势，所以必须通过这个入口拿到它。
         *
         * 这样做安全的前提是：**必须在 onUnbind / onDestroy 里置回 null**，
         * 否则会泄漏一个已经失效的 Service 实例。
         *
         * @Volatile：可能被不同线程读写。
         */
        @Volatile
        private var instance: LongPressAccessibilityService? = null

        /**
         * 开始长按。
         *
         * @return null 表示已成功开始；否则返回失败原因（供界面给出明确提示）。
         */
        fun startHold(x: Int, y: Int): HoldStartResult? {
            val dispatcher = instance?.gestureDispatcher
                ?: return HoldStartResult.ServiceNotConnected
            return dispatcher.startHold(x, y)
        }

        /** 停止长按。会尽快让手指抬起，见 [LongPressGestureDispatcher.stopHold]。 */
        fun stopHold() {
            // 这个版本判断在逻辑上是"永远为真"的：stopHold 只可能在 startHold 成功之后被调用，
            // 而 startHold 已经拒绝过 API < 26 的情况。
            // 显式写出来有两个作用：
            // 1. 让静态检查（lint 的 NewApi）和读代码的人都能直接看出这是安全的；
            // 2. 万一以后有人在低版本上直接调用，也只是什么都不做，不会崩。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            instance?.gestureDispatcher?.stopHold()
        }

        /** 服务实例是否真的可用（不是只看系统设置里是否勾选）。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 【仅 debug】自测"停止后再启动"，见 [LongPressGestureDispatcher.runStopThenRestartSelfTest]。
         */
        fun runSelfTest() {
            instance?.gestureDispatcher?.runStopThenRestartSelfTest()
        }
    }
}
