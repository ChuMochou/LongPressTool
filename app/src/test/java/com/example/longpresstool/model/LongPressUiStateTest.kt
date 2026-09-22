package com.example.longpresstool.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LongPressUiState] 的单元测试。
 *
 * 为什么只测这个类：它是整个 App 的**唯一数据源**，首页和悬浮侧边栏都根据它决定
 * "按钮能不能点、显示什么状态"。这里的判断一旦写错，界面就会出现
 * "按钮灰着却不知道为什么"这类很难排查的问题，所以值得用测试锁住。
 *
 * 这些测试都是纯 JVM 测试，不需要模拟器（`./gradlew :app:testDebugUnitTest` 即可运行）。
 */
class LongPressUiStateTest {

    /**
     * 「启动」按钮的可用性判断。
     *
     * 集中验证 [LongPressUiState.canStartLongPress] 的真值表：
     * 需要同时满足"准星位置已就绪"和"无障碍服务已开启"，并且当前没在长按。
     */
    @Test
    fun `准星位置未就绪时不能启动`() {
        val state = LongPressUiState(
            hasPosition = false,
            isAccessibilityEnabled = true,
            isPressing = false
        )
        assertFalse(state.canStartLongPress)
    }

    @Test
    fun `无障碍服务未开启时不能启动`() {
        val state = LongPressUiState(
            hasPosition = true,
            isAccessibilityEnabled = false,
            isPressing = false
        )
        assertFalse(state.canStartLongPress)
    }

    @Test
    fun `正在长按中不能再次启动`() {
        val state = LongPressUiState(
            hasPosition = true,
            isAccessibilityEnabled = true,
            isPressing = true
        )
        assertFalse(state.canStartLongPress)
    }

    @Test
    fun `位置就绪且无障碍已开启时可以启动`() {
        val state = LongPressUiState(
            hasPosition = true,
            isAccessibilityEnabled = true,
            isPressing = false
        )
        assertTrue(state.canStartLongPress)
    }

    @Test
    fun `默认状态不可启动`() {
        // 新建一个状态时什么都没准备好，按钮应该是灰的
        assertFalse(LongPressUiState().canStartLongPress)
    }

    /** 记录准星位置时，坐标和"位置已就绪"标记必须同时更新。 */
    @Test
    fun `记录位置会同时把 hasPosition 置为真`() {
        LongPressStateHolder.setTargetPosition(120, 340)

        val state = LongPressStateHolder.state.value
        assertTrue("记录位置后 hasPosition 应为 true", state.hasPosition)
        assertEquals(120, state.targetX)
        assertEquals(340, state.targetY)
    }

    /**
     * 关闭侧边栏（resetRunningState）后：
     * "运行中/长按中"要复位，但**用户选好的位置必须保留**（需求第九节）。
     */
    @Test
    fun `关闭侧边栏会复位运行状态但保留位置`() {
        LongPressStateHolder.setTargetPosition(200, 400)
        LongPressStateHolder.setServiceRunning(true)
        LongPressStateHolder.setPressing(true)

        LongPressStateHolder.resetRunningState()

        val state = LongPressStateHolder.state.value
        assertFalse("运行状态应复位", state.isServiceRunning)
        assertFalse("长按状态应复位", state.isPressing)
        assertTrue("位置应保留", state.hasPosition)
        assertEquals(200, state.targetX)
        assertEquals(400, state.targetY)
    }

    /** 长按阶段只有两种：就绪 / 长按中。 */
    @Test
    fun `阶段枚举只包含就绪与长按中`() {
        assertEquals(2, LongPressPhase.entries.size)
        assertEquals(LongPressPhase.READY, LongPressPhase.entries[0])
        assertEquals(LongPressPhase.PRESSING, LongPressPhase.entries[1])
    }

    /** 侧边栏默认位置应该是屏幕左上角稍靠内，不能是 (0,0)（那样会贴着状态栏）。 */
    @Test
    fun `侧边栏默认位置不为原点`() {
        val default = SidebarPosition.DEFAULT_SIDEBAR
        assertTrue("默认 x 应大于 0", default.xDp > 0)
        assertTrue("默认 y 应大于 0", default.yDp > 0)
    }
}
