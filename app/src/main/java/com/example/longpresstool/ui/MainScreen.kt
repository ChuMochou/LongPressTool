package com.example.longpresstool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.longpresstool.R
import com.example.longpresstool.ui.theme.LongPressToolTheme

/**
 * 首页（Phase 1）。
 *
 * 目前只做三件事：
 * 1. 显示标题「长按器」；
 * 2. 显示当前状态（未启动 / 已开启）；
 * 3. 提供一个「启动长按器」按钮负责切换状态。
 *
 * 这个按钮现在还没有任何真实能力：它只改一个布尔值。
 * 真正的悬浮窗会在 Phase 2 接进来（届时会把它换成启动 FloatingWindowService）。
 *
 * 关于状态写法：
 * 先用最简单的 rememberSaveable + mutableStateOf，让初学者一眼看懂「状态变了 UI 就重组」。
 * 等 Phase 2 有多个界面和 Service 需要共享状态时，再升级成 ViewModel + StateFlow。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {

    // true = 已经进入长按器模式；false = 未启动。
    // 用 rememberSaveable 而不是 remember：屏幕旋转 / 系统重建界面后状态不会丢。
    // 注意它只在这一块界面内有效，还不能代表 Service 的真实运行状态，
    // 所以 Phase 2 必须换成真实的状态来源，否则界面会和实际状态不一致。
    var isLongPressModeOn by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)   // 让内容避开状态栏和导航栏
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ---- 状态区域 ----
            StatusCard(isLongPressModeOn = isLongPressModeOn)

            // ---- 主按钮 ----
            Button(
                onClick = { isLongPressModeOn = !isLongPressModeOn },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_toggle_long_press_mode),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 给初学者的一句提示，说明这一阶段还不是真的能用。
            Text(
                text = stringResource(R.string.phase1_hint),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 状态卡片：把「状态：未启动 / 已开启」单独抽出来，方便后面复用到悬浮侧边栏里。
 */
@Composable
private fun StatusCard(
    isLongPressModeOn: Boolean,
    modifier: Modifier = Modifier
) {
    val statusText = stringResource(
        if (isLongPressModeOn) R.string.status_running else R.string.status_not_started
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- 预览：不装到手机 / 模拟器上也能在 Android Studio 里看到界面 ----

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    LongPressToolTheme {
        MainScreen()
    }
}
