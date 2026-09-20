package com.example.longpresstool.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.longpresstool.model.LongPressStateHolder

/**
 * 执行长按手势的无障碍服务（Phase 4 只搭骨架，Phase 5 才真正派发手势）。
 *
 * ===== 为什么"跨应用模拟触摸"必须走无障碍服务？ =====
 * 普通应用不能向其他应用注入触摸事件，这是 Android 的安全边界。
 * 唯一的官方途径是让用户主动开启一个无障碍服务，由系统把
 * dispatchGesture() 的能力授予它。也就是说：
 *
 *   - 这不违反 Android 安全机制，它**就是** Android 官方提供的机制；
 *   - 但用户必须知情并在系统设置里手动开启，App 无法自己给自己授权。
 *
 * ===== 本服务需要的能力 =====
 * 只有一项：派发手势（对应 res/xml/accessibility_service_config.xml 里的
 * canPerformGestures="true"）。我们**不**读取任何界面内容。
 *
 * ===== 生命周期（需求第十三节：用户关闭无障碍服务 / 系统回收） =====
 * onServiceConnected  -> 服务可用，写入全局状态
 * onUnbind            -> 用户在系统设置里关掉了，写入全局状态
 * onInterrupt         -> 系统要求中断，此时必须立刻结束正在执行的手势
 *
 * 特别注意：onUnbind 并不能覆盖所有情况（例如进程被系统杀掉时不会有任何回调），
 * 所以界面侧还会主动去系统里查询服务是否开启，见 permission/AccessibilityPermission.kt。
 * 两条路一起用才可靠。
 */
class LongPressAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LongPressStateHolder.setAccessibilityConnected(true)

        // Phase 5 会在这里：读取当前是否正在长按，并按需恢复长按状态。
    }

    /**
     * 收到界面事件。本服务不需要观察任何事件，直接忽略即可。
     * （eventTypes 是配置里的必填项，所以系统仍然会回调进来。）
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理。
    }

    /**
     * 系统要求中断当前操作（例如用户打开了无障碍快捷方式、或系统要执行自己的手势）。
     * Phase 5 会在这里立刻结束长按，避免"手指"一直按着不放。
     */
    override fun onInterrupt() {
        LongPressStateHolder.setPressing(false)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        LongPressStateHolder.setAccessibilityConnected(false)
        // 服务没了，正在执行的长按也不可能继续，一并复位。
        LongPressStateHolder.setPressing(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        LongPressStateHolder.setAccessibilityConnected(false)
        LongPressStateHolder.setPressing(false)
        super.onDestroy()
    }

    companion object {
        /**
         * 当前已连接的服务实例。
         *
         * 这是本项目里唯一一个"静态持有组件实例"的地方，值得解释清楚：
         * 无障碍服务由系统创建和管理，我们无法像普通 Service 那样自己 new 一个。
         * 而悬浮侧边栏需要让它去派发手势，所以必须拿到这个实例。
         *
         * 这样做安全的前提是：**必须在 onUnbind / onDestroy 里置回 null**，
         * 否则会泄漏一个已经失效的 Service 实例。
         *
         * @Volatile：可能被不同线程读写。
         */
        @Volatile
        private var instance: LongPressAccessibilityService? = null

        /** 服务实例是否真的可用（不是只看系统设置里是否勾选）。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 供悬浮侧边栏调用。Phase 5 会在这里实现真正的手势派发。
         *
         * @return 当前是否能执行手势
         */
        fun canPerformGestures(): Boolean = instance != null
    }
}
