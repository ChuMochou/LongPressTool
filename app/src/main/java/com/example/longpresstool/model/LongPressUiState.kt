package com.example.longpresstool.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 长按器的全部运行状态。
 *
 * 这是唯一的数据源：Activity 的首页、悬浮侧边栏都读它，谁都不自己存一份。
 *
 * 为什么用 StateFlow 而不是让界面各自 remember 一个变量：
 * 状态要在 Activity 和 Service 之间共享，而且 Service 可能比 Activity 活得更久。
 * 界面自己存变量必然会和真实状态不一致（比如侧边栏已经关了，首页还显示"已开启"）。
 *
 * 注意这里只放"数据"，不放业务逻辑，方便初学者一眼看清有哪些状态。
 */
data class LongPressUiState(
    /** 悬浮窗权限是否已经授予（由 Activity 每次回到前台时刷新）。 */
    val overlayPermissionGranted: Boolean = false,

    /** 无障碍服务是否已在系统设置里开启（决定能不能派发手势）。 */
    val isAccessibilityEnabled: Boolean = false,

    /** 无障碍服务对象是否真的已经连上（可以立刻派发手势）。 */
    val isAccessibilityConnected: Boolean = false,

    /** 悬浮侧边栏 Service 是否正在运行。 */
    val isServiceRunning: Boolean = false,

    /** 准星位置是否已经就绪（准星显示过一次就为 true）。 */
    val hasPosition: Boolean = false,

    /** 准星圆心当前的 X 坐标（屏幕绝对像素，与 dispatchGesture 同一坐标系）。 */
    val targetX: Int = 0,

    /** 准星圆心当前的 Y 坐标（屏幕绝对像素）。 */
    val targetY: Int = 0,

    /** 是否正在执行长按。 */
    val isPressing: Boolean = false
) {
    /**
     * 派生属性：什么时候才允许点「启动」。
     *
     * 现在只有两个条件：准星位置已就绪、且没在长按中。
     * （早期版本还要求"用户先点过选择位置"，改成准星常驻后这一步就不需要了。）
     * 集中放在这里而不是散落在界面里，避免多处判断不一致。
     */
    val canStartLongPress: Boolean
        get() = hasPosition && !isPressing && isAccessibilityEnabled
}

/**
 * 长按器的运行阶段。
 *
 * 只有两个：准星就绪可以启动、以及正在长按。
 * （早期版本的"未选择位置""正在选择位置"随着准星改成常驻已经不存在了。）
 */
enum class LongPressPhase {
    /** 准星就绪，随时可以点「启动」。 */
    READY,

    /** 正在长按。 */
    PRESSING
}

/**
 * 全局状态容器（单例）。
 *
 * 这是一个"够用就好"的做法：初学者用不着现在就引入 Hilt / DI 框架。
 * 等到项目真的变大、需要被测试替换时，再换成依赖注入也不迟。
 *
 * 内存里的状态在进程被杀后会丢失，这没关系：
 * 被杀 = 什么都没在运行，重置成默认值反而是正确的（见 Phase 7）。
 */
object LongPressStateHolder {

    private val _state = MutableStateFlow(LongPressUiState())

    /** 对外的只读状态流，界面订阅它即可。 */
    val state: StateFlow<LongPressUiState> = _state.asStateFlow()

    /** 界面只关心"整体处于哪一档"，用枚举表达，避免界面里写一堆 if-else。 */
    val phase = state.map { s ->
        if (s.isPressing) LongPressPhase.PRESSING else LongPressPhase.READY
    }

    // ---- 以下都是很小的、语义明确的更新方法，避免到处写 copy() ----

    fun setOverlayPermissionGranted(granted: Boolean) =
        _state.update { it.copy(overlayPermissionGranted = granted) }

    /**
     * 更新无障碍服务的状态。
     *
     * @param enabled   系统设置里是否已开启
     * @param connected 服务对象是否已经连上
     */
    fun setAccessibilityState(enabled: Boolean, connected: Boolean) =
        _state.update { it.copy(isAccessibilityEnabled = enabled, isAccessibilityConnected = connected) }

    fun setAccessibilityConnected(connected: Boolean) =
        _state.update { it.copy(isAccessibilityConnected = connected) }

    fun setServiceRunning(running: Boolean) =
        _state.update { it.copy(isServiceRunning = running) }

    /**
     * 记录准星圆心位置。
     *
     * 准星每次被拖动、以及刚显示出来时都会调用它，
     * 所以 [LongPressUiState.hasPosition] 在准星出现后必定为 true。
     */
    fun setTargetPosition(x: Int, y: Int) =
        _state.update { it.copy(hasPosition = true, targetX = x, targetY = y) }

    fun setPressing(pressing: Boolean) =
        _state.update { it.copy(isPressing = pressing) }

    /**
     * 关闭侧边栏时调用：把"运行中"相关的状态复位。
     *
     * 位置（targetX / targetY）会保留：准星下次打开还在原处，
     * 不需要用户重新对准一次（需求第九节要求停止后保留位置）。
     */
    fun resetRunningState() = _state.update {
        it.copy(
            isServiceRunning = false,
            isPressing = false
        )
    }
}
