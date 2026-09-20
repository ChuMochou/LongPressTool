package com.example.longpresstool.permission

import android.content.Context
import android.content.SharedPreferences
import com.example.longpresstool.model.SidebarPosition

/**
 * 用 SharedPreferences 保存少量简单配置。
 *
 * 目前只存悬浮侧边栏的位置。为什么存 dp 而不是 px：
 * px 在不同分辨率的手机上含义不同，dp 才能让位置在各种屏幕上看起来一致。
 *
 * 没有引入 DataStore，是因为这里只有一两个 int，
 * SharedPreferences 足够，而且对初学者更直观。
 */
object AppPreferences {

    private const val PREF_NAME = "long_press_tool_prefs"
    private const val KEY_SIDEBAR_X_DP = "sidebar_x_dp"
    private const val KEY_SIDEBAR_Y_DP = "sidebar_y_dp"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadSidebarPosition(context: Context): SidebarPosition {
        val p = prefs(context)
        return SidebarPosition(
            xDp = p.getInt(KEY_SIDEBAR_X_DP, SidebarPosition.DEFAULT.xDp),
            yDp = p.getInt(KEY_SIDEBAR_Y_DP, SidebarPosition.DEFAULT.yDp)
        )
    }

    fun saveSidebarPosition(context: Context, position: SidebarPosition) {
        prefs(context).edit()
            .putInt(KEY_SIDEBAR_X_DP, position.xDp)
            .putInt(KEY_SIDEBAR_Y_DP, position.yDp)
            .apply()
    }
}
