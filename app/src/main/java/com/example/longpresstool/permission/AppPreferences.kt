package com.example.longpresstool.permission

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.longpresstool.model.SidebarPosition

/**
 * 用 SharedPreferences 保存少量简单配置。
 *
 * 目前保存两个悬浮窗的位置。为什么存 dp 而不是 px：
 * px 在不同分辨率的手机上含义不同，dp 才能让位置在各种屏幕上看起来一致。
 *
 * 没有引入 DataStore，是因为这里只有两个坐标，
 * SharedPreferences 足够，而且对初学者更直观。
 */
object AppPreferences {

    private const val PREF_NAME = "long_press_tool_prefs"

    private const val KEY_SIDEBAR_X_DP = "sidebar_x_dp"
    private const val KEY_SIDEBAR_Y_DP = "sidebar_y_dp"
    private const val KEY_INDICATOR_X_DP = "indicator_x_dp"
    private const val KEY_INDICATOR_Y_DP = "indicator_y_dp"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- 侧边栏位置 ----

    fun loadSidebarPosition(context: Context): SidebarPosition {
        val p = prefs(context)
        return SidebarPosition(
            xDp = p.getInt(KEY_SIDEBAR_X_DP, SidebarPosition.DEFAULT_SIDEBAR.xDp),
            yDp = p.getInt(KEY_SIDEBAR_Y_DP, SidebarPosition.DEFAULT_SIDEBAR.yDp)
        )
    }

    fun saveSidebarPosition(context: Context, position: SidebarPosition) {
        // 用了 core-ktx 提供的 edit {} 扩展，它会自动 apply()。
        prefs(context).edit {
            putInt(KEY_SIDEBAR_X_DP, position.xDp)
            putInt(KEY_SIDEBAR_Y_DP, position.yDp)
        }
    }

    // ---- 圆形指示器位置 ----
    // 返回可空类型：null 表示"用户从来没选过位置"，
    // 此时应该把指示器放在屏幕中央，而不是用默认坐标。

    fun loadIndicatorPosition(context: Context): SidebarPosition? {
        val p = prefs(context)
        if (!p.contains(KEY_INDICATOR_X_DP) || !p.contains(KEY_INDICATOR_Y_DP)) return null
        return SidebarPosition(
            xDp = p.getInt(KEY_INDICATOR_X_DP, 0),
            yDp = p.getInt(KEY_INDICATOR_Y_DP, 0)
        )
    }

    fun saveIndicatorPosition(context: Context, position: SidebarPosition) {
        prefs(context).edit {
            putInt(KEY_INDICATOR_X_DP, position.xDp)
            putInt(KEY_INDICATOR_Y_DP, position.yDp)
        }
    }
}
