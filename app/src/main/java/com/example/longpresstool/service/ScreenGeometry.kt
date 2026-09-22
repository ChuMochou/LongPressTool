package com.example.longpresstool.service

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 悬浮窗的屏幕几何计算。
 *
 * 把这一块单独抽出来，是因为它有两个特点：
 * 1. 全是**纯计算**，不持有任何 View / 窗口状态，所以很好读、也好单独验证；
 * 2. 它是本项目最容易写错的地方——坐标系一旦差了状态栏那几十像素，
 *    长按就会落在错误的位置，而且很难排查。
 *
 * 因此这里集中放"坐标与边界"相关的逻辑，Service 只负责调用，
 * 避免这些细节散落在上千行的窗口管理代码里。
 *
 * 注意它不是单例，而是每个 Service 实例持有一个（见 [FloatingWindowService.screenGeometry]），
 * 这样可以按需释放，也不会把 View 引用长期存进静态变量里。
 */
class ScreenGeometry(context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)

    /** 直接用 Resources 里的 DisplayMetrics，dp→px 换算才准确（含 scaledDensity 等）。 */
    private val metrics = context.resources.displayMetrics

    fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        metrics
    ).toInt()

    fun pxToDp(px: Int): Int = (px / metrics.density).toInt()

    /**
     * 当前可以安全放置悬浮窗的屏幕区域（**屏幕绝对坐标**）。
     *
     * 需求第六节要求处理状态栏、导航栏、刘海屏。做法分两步：
     *
     * 1. **起点用真实屏幕尺寸**（包含状态栏、刘海、导航栏），
     *    而不是 `resources.displayMetrics`——后者在部分版本/机型上不包含系统栏，
     *    会算出一个比真实屏幕小的坐标系，导致长按坐标整体偏上或偏左。
     *
     *    版本差异：
     *    - API 30+ ：`WindowManager.getMaximumWindowMetrics()`，官方推荐方式；
     *    - API 24~29：只能用已废弃的 `Display.getRealSize()`，但它的语义正是"真实屏幕尺寸"。
     *
     * 2. 再用 insets 把状态栏、刘海、导航栏所在的安全区扣掉，
     *    保证用户不会把准星拖到挖孔下面或导航栏里。
     *
     * @param insetsProvider 取 insets 用的 View（任意一个已添加的悬浮窗即可）。
     *        传 null 或还没拿到 insets 时，退回"整块屏幕"，只少一点安全边距，不会出错。
     */
    fun usableBounds(insetsProvider: View?): Rect {
        val fullScreen = fullScreenBounds()
        val insets = systemBarInsets(insetsProvider) ?: return fullScreen

        return Rect(
            fullScreen.left + insets.left,
            fullScreen.top + insets.top,
            fullScreen.right - insets.right,
            fullScreen.bottom - insets.bottom
        )
    }

    /** 真实屏幕尺寸（含系统栏与刘海区域）。 */
    private fun fullScreenBounds(): Rect {
        val rect = Rect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            rect.set(0, 0, bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            rect.set(0, 0, size.x, size.y)
        }
        return rect
    }

    /**
     * 系统栏 + 刘海所占的边距。
     *
     * 之所以要传一个 View 进来：insets 是挂在具体窗口上的，
     * 只有已经添加到 WindowManager 的悬浮窗才能读到。
     */
    private fun systemBarInsets(view: View?): Rect? {
        if (view == null) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = view.rootWindowInsets ?: return null
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Rect(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return null
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            Rect(bars.left, bars.top, bars.right, bars.bottom)
        }
    }

    companion object {
        /**
         * 把一个窗口的左上角坐标夹进可用区域内。
         *
         * 抽成静态方法是因为它对侧边栏和准星完全一样，只是传入的尺寸不同。
         */
        fun clampToBounds(
            params: WindowManager.LayoutParams,
            bounds: Rect,
            width: Int,
            height: Int
        ) {
            params.x = params.x.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left))
            params.y = params.y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
        }
    }
}
