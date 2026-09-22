package com.example.longpresstool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.longpresstool.model.LongPressStateHolder
import com.example.longpresstool.service.FloatingWindowService
import com.example.longpresstool.service.LongPressAccessibilityService
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
                MainScreen(
                    viewModel = viewModel,
                    // 「退出」按钮：结束界面，并把本应用从最近任务列表里移除，
                    // 这样用户感觉是"真的退出了"，而不是留在后台。
                    onExit = { finishAndRemoveTask() }
                )
            }
        }

        askNotificationPermissionIfNeeded()

        // 【仅 debug】处理"用 adb 直接触发一次长按"的排查入口。
        // 这个 action 只在 src/debug/AndroidManifest.xml 里注册，
        // 正式包里不存在，所以 release 构建不会被触发。
        handleDebugHoldIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugHoldIntent(intent)
    }

    private fun handleDebugHoldIntent(intent: Intent?) {
        // 【仅 debug】排查入口：见文件末尾说明。
        // 不需要在 Manifest 里注册任何 action：MainActivity 已经 exported=true，
        // 而这条 intent **没有声明 action**，只有能用 adb 的 shell/root 能构造出来。
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra(EXTRA_DEBUG_SELFTEST, false) == true) {
            // 自测：启动 -> 停止 -> 再启动，验证"停止后再启动会不会立刻退出"。
            LongPressAccessibilityService.runSelfTest()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_DEBUG_FAKE_KILL, false) == true) {
            // 自测：模拟"进程曾被系统回收"，验证侧边栏仍能重新打开。
            FloatingWindowService.simulateProcessWasKilled()
            return
        }
        val x = intent?.getIntExtra(EXTRA_DEBUG_X, -1) ?: -1
        val y = intent?.getIntExtra(EXTRA_DEBUG_Y, -1) ?: -1
        android.util.Log.d("LongPressDebug", "handleDebugHoldIntent x=$x y=$y")
        if (x < 0 || y < 0) return
        LongPressStateHolder.setTargetPosition(x, y)
        val result = LongPressAccessibilityService.startHold(x, y)
        android.util.Log.d("LongPressDebug", "startHold($x,$y) result=$result")
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

    companion object {
        /**
         * 【仅 debug】排查用参数：在启动 Activity 时带上坐标，直接触发一次长按。
         *
         *   adb shell am start -n <包名>/.MainActivity \
         *     --ei debug_x 540 --ei debug_y 1200
         *
         * 为什么要留这个入口：长按涉及"手势接力"，出问题时（例如只按一下就停）
         * 必须能在真机/模拟器上快速复现并看日志，靠手点很难稳定复现。
         * 它在 release 构建里不会执行（有 BuildConfig.DEBUG 判断）。
         */
        private const val EXTRA_DEBUG_X = "debug_x"
        private const val EXTRA_DEBUG_Y = "debug_y"
        private const val EXTRA_DEBUG_SELFTEST = "debug_selftest"
        private const val EXTRA_DEBUG_FAKE_KILL = "debug_fake_kill"
    }
}
