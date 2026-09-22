package com.example.longpresstool.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * App 的 Compose 主题。
 *
 * 与 XML 里的 `Theme.LongPressTool`（见 res/values/themes.xml）是两回事：
 * - 这个 [LongPressToolTheme] 只作用于 **Compose 界面**（首页）；
 * - XML 主题作用于窗口本身和传统 View（悬浮窗用的是传统 View）。
 * 两边都基于 Material 3，所以观感一致。
 *
 * [dynamicColor] 打开时，Android 12+ 会取用系统壁纸提取的配色（Material You），
 * 这是官方的推荐做法；低版本自动回退到内置配色。
 */
@Composable
fun LongPressToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}