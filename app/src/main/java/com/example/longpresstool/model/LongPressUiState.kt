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
 * Phase 2 开始，状态要在 Activity 和 Service 之间共享，而且 Service 可能比 Activity
 * 活得更久。界面自己存变量必然会和真实状态不一致（比如侧边栏已经关了，首页还显示"已开启"）。
 *
 * 注意这里只放"数据"，不放业务逻辑，方便初学者一眼看清有哪些状态。
 */
data class LongPressUiState(
    /** 悬浮窗权限是否已经授予（由 Activity 每次回到前台时刷新）。 */
    val overlayPermissionGranted: Boolean = false,

    /** 悬浮侧边栏 Service 是否正在运行。 */
    val isServiceRunning: Boolean = false,

    /** 是否正在"选择位置"模式（屏幕上显示可拖动的圆形指示器）。 */
    val isSelectingPosition: Boolean = false,

    /** 是否已经选定过长按位置。 */
    val hasSelectedPosition: Boolean = false,

    /** 所选位置的 X 坐标（屏幕绝对像素，与 dispatchGesture 同一坐标系）。 */
    val targetX: Int = 0,

    /** 所选位置的 Y 坐标（屏幕绝对像素）。 */
    val targetY: Int = 0,

    /** 是否正在执行长按。 */
    val isPressing: Boolean = false
) {
    /**
     * 便捷派生属性：什么时候才允许点「启动」。
     * 放在这里而不是散落在界面里，避免两处判断不一致。
     *
     * 注意：Phase 5 还会加上"无障碍服务是否已开启"这一条。
     */
    val canStartLongPress: Boolean
        get() = hasSelectedPosition && !isPressing
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

    /**
     * 界面只关心"整体处于哪一档"，用一个枚举表达，避免界面里写一堆 if-else。
     */
    val phase = state.map { s ->
        when {
            s.isPressing -> LongPressPhase.PRESSING
            s.isSelectingPosition -> LongPressPhase.SELECTING_POSITION
            s.hasSelectedPosition -> LongPressPhase.POSITION_SELECTED
            else -> LongPressPhase.NO_POSITION
        }
    }

    // ---- 以下都是很小的、语义明确的更新方法，避免到处写 copy() ----

    fun setOverlayPermissionGranted(granted: Boolean) =
        _state.update { it.copy(overlayPermissionGranted = granted) }

    fun setServiceRunning(running: Boolean) =
        _state.update { it.copy(isServiceRunning = running) }

    fun setSelectingPosition(selecting: Boolean) =
        _state.update { it.copy(isSelectingPosition = selecting) }

    fun setTargetPosition(x: Int, y: Int) =
        _state.update { it.copy(hasSelectedPosition = true, targetX = x, targetY = y) }

    fun setPressing(pressing: Boolean) =
        _state.update { it.copy(isPressing = pressing) }

    /**
     * 关闭侧边栏时调用：把"运行中"相关的状态全部复位，但**保留已选位置**，
     * 这样用户下次打开还能接着用上次的位置（需求第九节明确要求保留位置）。
     */
    fun resetRunningState() = _state.update {
        it.copy(
            isServiceRunning = false,
            isSelectingPosition = false,
            isPressing = false
        )
    }
}

/** 长按器的四个阶段，供界面显示不同文案和样式。 */
enum class LongPressPhase {
    /** 还没有选位置 */
    NO_POSITION,

    /** 正在选择位置 */
    SELECTING_POSITION,

    /** 位置已确定，可以启动 */
    POSITION_SELECTED,

    /** 正在长按 */
    PRESSING
}
