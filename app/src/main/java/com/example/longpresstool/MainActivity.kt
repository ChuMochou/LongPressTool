package com.example.longpresstool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.longpresstool.ui.MainScreen
import com.example.longpresstool.ui.MainViewModel
import com.example.longpresstool.ui.theme.LongPressToolTheme

/**
 * App 唯一的一个 Activity（单 Activity 架构）。
 *
 * 它的职责非常少：
 * 1. 打开「边到边」显示，把界面交给 Compose；
 * 2. 处理"回到前台时重新检查权限"这件事。
 *
 * 注意：Activity 只是普通界面，它无法显示在别的 App 上面。
 * 悬浮侧边栏由 service/FloatingWindowService.kt 负责，不在这个文件里。
 */
class MainActivity : ComponentActivity() {

    // by viewModels()：Activity 重建（如旋转屏幕）时复用同一个 ViewModel，
    // 状态不会因为界面重建而丢失。
    private val viewModel: MainViewModel by viewModels()

    /**
     * Android 13 (API 33) 起，显示通知需要用户授权。
     * 前台 Service 的常驻通知也受这个限制，所以需要申请。
     * 被拒绝也不影响核心功能（悬浮窗和长按照常工作），所以不做强制拦截。
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论用户同意还是拒绝，都不需要额外处理：授权了通知会显示，没授权就没有。
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让内容延伸到状态栏、导航栏区域，具体留白由 Compose 的 WindowInsets 处理。
        enableEdgeToEdge()

        setContent {
            LongPressToolTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        askNotificationPermissionIfNeeded()
    }

    /**
     * 每次回到前台都重新检查悬浮窗权限。
     *
     * 用户去系统设置里拨完开关返回时，只有重新读一次系统值才知道结果——
     * 权限随时可能被改，绝不能缓存"上次已经授权过"这个结论。
     *
     * 这里直接在 onResume 里调用是对的：它本身就是"回到前台时执行一次"的语义。
     * 注意不要在这里用 repeatOnLifecycle——那是给 onCreate 里启动的常驻协程用的，
     * 放在 onResume 里属于误用（lint 会直接报错）。
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    private fun askNotificationPermissionIfNeeded() {
        // POST_NOTIFICATIONS 是 API 33 引入的，低版本不需要申请。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
