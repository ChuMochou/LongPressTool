package com.example.longpresstool.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.longpresstool.model.LongPressPhase
import com.example.longpresstool.model.LongPressStateHolder
import com.example.longpresstool.model.LongPressUiState
import com.example.longpresstool.permission.AccessibilityPermission
import com.example.longpresstool.permission.OverlayPermission
import com.example.longpresstool.service.FloatingWindowService
import com.example.longpresstool.service.LongPressAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 首页的状态持有者。
 *
 * 为什么需要 ViewModel：
 * 屏幕旋转、切到后台再回来都会重建 Activity。如果状态还放在 Composable 的 remember 里，
 * 界面和真实的 Service 状态就会脱节。ViewModel 跟着 Activity 的"逻辑生命周期"存活，
 * 并在这里集中处理"检查权限 / 启动 Service"这类动作，
 * Composable 只负责画界面 —— 这是官方推荐的单向数据流写法。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * 界面看到的全部状态。
     *
     * LongPressStateHolder.state 是全局唯一数据源（Service 也在写它），
     * 这里只是把它转成"界面生命周期内有效"的 StateFlow。
     */
    val uiState: StateFlow<LongPressUiState> = LongPressStateHolder.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LongPressStateHolder.state.value
        )

    /** 当前阶段（未选位置 / 选择中 / 已就绪 / 长按中），供界面显示不同文案。 */
    val phase: StateFlow<LongPressPhase> = LongPressStateHolder.phase
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LongPressPhase.NO_POSITION
        )

    init {
        // 冷启动先刷新一次，避免首页一开始显示错误状态。
        refreshPermissions()
        startAccessibilityMonitoring()
    }

    /**
     * 重新检查两个特殊权限。
     *
     * 必须在这些时机调用：
     * 1. Activity onResume（用户从系统设置页返回时）；
     * 2. 用户主动点"重新检查"时。
     * 因为权限可以在任何时刻被用户改掉，缓存结果一定会过期。
     */
    fun refreshPermissions() {
        val context = getApplication<Application>()

        LongPressStateHolder.setOverlayPermissionGranted(OverlayPermission.isGranted(context))
        LongPressStateHolder.setAccessibilityState(
            enabled = AccessibilityPermission.isEnabled(context),
            connected = LongPressAccessibilityService.isConnected()
        )
    }

    /**
     * 定期检查无障碍服务是否还活着。
     *
     * 为什么需要"轮询"这么笨的办法（需求第十三节：用户关闭 AccessibilityService）：
     * 无障碍服务被关闭时，**系统不会给悬浮侧边栏任何通知**。
     * 无障碍服务自己的 onUnbind 只在进程还活着时才可靠，
     * 如果整个进程被系统回收再重启，那边根本没有机会回调。
     * 所以界面侧主动查询是必须的。
     *
     * 用 1 秒的间隔：用户从系统设置返回时几乎立刻就能看到状态更新，
     * 而一次查询只是读系统里的一个列表，开销极小。
     */
    private fun startAccessibilityMonitoring() {
        viewModelScope.launch {
            while (isActive) {
                delay(ACCESSIBILITY_POLL_INTERVAL_MS)
                val context = getApplication<Application>()
                LongPressStateHolder.setAccessibilityState(
                    enabled = AccessibilityPermission.isEnabled(context),
                    connected = LongPressAccessibilityService.isConnected()
                )
            }
        }
    }

    /**
     * 用户点了"启动长按器"。
     *
     * @return true 表示已经成功启动悬浮侧边栏；false 表示缺权限，界面需要显示权限引导。
     */
    fun startLongPressMode(): Boolean {
        val context = getApplication<Application>()

        // 每次动手前都重新检查，不要相信上次的结果。
        if (!OverlayPermission.isGranted(context)) {
            LongPressStateHolder.setOverlayPermissionGranted(false)
            return false
        }

        LongPressStateHolder.setOverlayPermissionGranted(true)
        FloatingWindowService.start(context)
        return true
    }

    /** 用户点了"关闭长按器"。 */
    fun stopLongPressMode() {
        FloatingWindowService.stop(getApplication())
    }

    /** 跳转到系统设置里的"显示在其他应用上层"页面。 */
    fun openOverlaySettings(): Boolean =
        OverlayPermission.openSettings(getApplication())

    /** 跳转到系统设置里的无障碍服务页面。 */
    fun openAccessibilitySettings(): Boolean =
        AccessibilityPermission.openSettings(getApplication())

    companion object {
        private const val ACCESSIBILITY_POLL_INTERVAL_MS = 1_000L
    }
}
