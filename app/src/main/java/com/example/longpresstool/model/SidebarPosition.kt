package com.example.longpresstool.model

/**
 * 悬浮窗在屏幕上的锚点位置（不是长按位置）。
 *
 * 悬浮侧边栏的位置用「距离屏幕左上角的 dp 偏移」表示，因为 WindowManager 的
 * Gravity.TOP or Gravity.START 就是按这个含义解释 x / y 的。
 * 用 dp 而不是 px 存储，是为了换手机、换分辨率后位置依然合理。
 */
data class SidebarPosition(
    val xDp: Int,
    val yDp: Int
) {
    companion object {
        /** 默认出现在屏幕左上角稍靠内的位置。 */
        val DEFAULT = SidebarPosition(xDp = 12, yDp = 96)
    }
}
