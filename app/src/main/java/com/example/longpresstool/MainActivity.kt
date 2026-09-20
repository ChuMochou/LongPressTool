package com.example.longpresstool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.longpresstool.ui.MainScreen
import com.example.longpresstool.ui.theme.LongPressToolTheme

/**
 * App 唯一的一个 Activity（单 Activity 架构）。
 *
 * 它的职责非常少，只做两件事：
 * 1. 打开「边到边」显示（enableEdgeToEdge），让 Compose 页面自己处理状态栏 / 导航栏的内边距；
 * 2. 把界面交给 Compose：用 App 主题包住 [MainScreen]。
 *
 * 注意：Activity 只是普通界面，它无法显示在别的 App 上面。
 * 后续阶段的「悬浮侧边栏」必须放在 Service 里（见 Phase 2），不会写在这个文件里。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让内容延伸到状态栏、导航栏区域，具体留白由 Compose 的 WindowInsets 处理。
        enableEdgeToEdge()

        setContent {
            LongPressToolTheme {
                MainScreen()
            }
        }
    }
}
