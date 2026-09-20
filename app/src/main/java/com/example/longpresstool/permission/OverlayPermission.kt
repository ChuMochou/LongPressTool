package com.example.longpresstool.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 悬浮窗权限（SYSTEM_ALERT_WINDOW）的检查与引导。
 *
 * 初学者最容易踩的坑：这个权限**不能**用 requestPermissions() 申请。
 * 它是特殊权限（special permission），必须跳到系统设置页让用户手动开关，
 * 所以流程一定是：
 *
 *   检查 -> 没有 -> 显示说明 -> 跳系统设置 -> 用户操作 -> 回到 App -> 重新检查
 *
 * 这个类的职责就是"检查"和"跳转"两件事，不掺业务逻辑。
 */
object OverlayPermission {

    /**
     * 是否已经获得悬浮窗权限。
     *
     * Settings.canDrawOverlays() 从 API 23 起可用，本应用 minSdk = 24，无需版本判断。
     * 注意：用户可能在设置里手动关掉，所以不能只记一次结果，
     * 每次回到前台都要重新调用本方法（见 MainActivity 的 ON_RESUME 处理）。
     */
    fun isGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 跳转到本应用的「显示在其他应用上层」设置页。
     *
     * 用 package: 限定到本应用，用户进去只需要拨一个开关，不用在应用列表里翻找。
     * 加 FLAG_ACTIVITY_NEW_TASK 是因为 Activity 之外（比如 Service）也可能调用。
     */
    fun buildSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            // toUri() 是 core-ktx 提供的扩展，等价于 Uri.parse()，但更不易写错。
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 直接尝试跳转；万一某些定制系统没有这个设置页，不要让 App 崩掉。
     *
     * @return true 表示已成功跳转，false 表示该机型不支持这个设置页。
     */
    fun openSettings(context: Context): Boolean {
        return try {
            context.startActivity(buildSettingsIntent(context))
            true
        } catch (e: Exception) {
            // 极少数定制 ROM 会缺少该 Activity，此时只能提示用户手动去设置里找。
            false
        }
    }
}
