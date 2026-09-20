package com.example.longpresstool.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.longpresstool.service.LongPressAccessibilityService

/**
 * 无障碍服务（AccessibilityService）的开启状态检查与引导。
 *
 * 和悬浮窗权限一样，这也是"特殊权限"：
 * 用户必须去 系统设置 -> 无障碍 -> 已下载的服务 里手动开启，App 不能自己授权。
 * 所以引导流程和 OverlayPermission 完全一致：
 *
 *   检查 -> 没有 -> 显示说明 -> 跳系统设置 -> 用户开启 -> 回到 App -> 重新检查
 */
object AccessibilityPermission {

    private const val TAG = "AccessibilityPermission"

    /**
     * 本应用的无障碍服务在系统设置里是否**已开启**。
     *
     * ===== 为什么不用 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES =====
     * 常见教程会读 Settings.Secure 里的字符串，再按 ':' 和 '/' 自己拆分比对。
     * 那种写法容易错，因为那个设置项是**无障碍框架内部格式**，
     * 把包名和类名里的 '.' 换成了 '/'，自己拼字符串很容易漏掉替换规则。
     *
     * 官方推荐做法是问 AccessibilityManager 要已启用的服务列表再比较，见下面的实现。
     */
    fun isEnabled(context: Context): Boolean = findOurService(context) != null

    /**
     * 服务不仅"被勾选"，而且**已经被系统连上**（可以派发手势了）。
     *
     * 这两个状态确实不同：用户刚在设置里打开开关的瞬间，
     * 服务可能还在启动中。判断方式不同——
     * - 是否勾选：看它在已启用列表里（本方法之外用 isEnabled）
     * - 是否连上：看 AccessibilityServiceInfo 是否有非 0 的 feedbackType，
     *   这是官方定义"服务可用"的判据，纯粹读取信息、不触发任何副作用。
     */
    fun isServiceReady(context: Context): Boolean {
        val info = findOurService(context) ?: return false
        return info.feedbackType != 0
    }

    /**
     * 在系统已启用的无障碍服务列表里找到我们自己那一个。
     * @return 找到则返回它的 AccessibilityServiceInfo，否则 null。
     */
    private fun findOurService(context: Context): AccessibilityServiceInfo? {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return null

        // 从 API 14 起可用，本应用 minSdk = 24，无需版本判断。
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        // 同时用两种方式比对，避免某一种格式判断失效导致误判"未开启"：
        // 1. 直接拿 AccessibilityServiceInfo.id 和 ComponentName.flattenToString() 比；
        // 2. 从 id 反解出 ComponentName，再比较包名和类名。
        // 注意：这里只能**宽松**匹配，绝不能"宽松地认定未开启"，
        // 否则会出现"服务明明开着，App 却一直提示去开启"的死循环。
        val targetPackage = context.packageName
        val targetClass = LongPressAccessibilityService::class.java.name
        val targetId = ComponentName(targetPackage, targetClass).flattenToString()

        val found = enabledServices.firstOrNull { info ->
            val id = info.id ?: return@firstOrNull false
            val byFlatten = id == targetId
            val byUnflatten = ComponentName.unflattenFromString(id)?.let {
                it.packageName == targetPackage && it.className == targetClass
            } ?: false
            byFlatten || byUnflatten
        }

        if (found == null) {
            Log.d(
                TAG,
                "未在已启用列表中找到本服务。期望: $targetId；" +
                    "已启用(过滤后): ${enabledServices.map { it.id }}；" +
                    "全量已安装: ${manager.getInstalledAccessibilityServiceList().map { it.id }}；" +
                    "无障碍总开关: ${manager.isEnabled}"
            )
        }
        return found
    }

    /**
     * 跳转到系统的无障碍设置页。
     *
     * ACTION_ACCESSIBILITY_SETTINGS 打开的是无障碍服务**列表**，
     * 用户需要自己找到本应用再开启。系统没有提供"直达某个服务开关"的公开 Intent，
     * 所以引导文案必须写清楚让用户找哪个名字。
     */
    fun buildSettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** @return true 表示已成功跳转，false 表示该机型没有这个设置页。 */
    fun openSettings(context: Context): Boolean {
        return try {
            context.startActivity(buildSettingsIntent())
            true
        } catch (e: Exception) {
            false
        }
    }
}
