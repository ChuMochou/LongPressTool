package com.example.longpresstool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.longpresstool.R
import com.example.longpresstool.ui.theme.LongPressToolTheme

/** 状态指示灯的颜色，和悬浮侧边栏保持同一套语义。 */
private val ColorIdle = Color(0xFF9E9E9E)      // 灰：未启动
private val ColorReady = Color(0xFF2E7D32)     // 绿：已启动
private val ColorPressing = Color(0xFFD32F2F)  // 红：长按中

/**
 * 首页。
 *
 * 职责只有"画界面"：状态全部来自 [MainViewModel]，动作也全部交给它。
 * 这种写法叫单向数据流（UI 只读状态、只发事件），是 Compose 推荐的模式，
 * 好处是界面永远不可能和真实运行状态脱节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    // collectAsStateWithLifecycle：界面不可见时自动停止收集，比 collectAsState 更省电、更安全。
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                .padding(innerPadding)   // 避开状态栏和导航栏
                .verticalScroll(rememberScrollState())   // 屏幕小 + 两张权限卡片时能滚动
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusCard(
                isLongPressModeOn = uiState.isServiceRunning,
                isPressing = uiState.isPressing
            )

            Button(
                onClick = { viewModel.startLongPressMode() },
                enabled = !uiState.isServiceRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_toggle_long_press_mode),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 已经启动时，给一个从首页关闭的入口（平时应该用侧边栏里的「关闭」）。
            if (uiState.isServiceRunning) {
                TextButton(
                    onClick = { viewModel.stopLongPressMode() },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(text = stringResource(R.string.overlay_close))
                }
            }

            // ---- 权限清单：缺哪个就显示哪个 ----
            // 这两项都是"特殊权限"，只能跳系统设置手动开，所以每一项都带说明 + 跳转按钮 + 重新检查。
            // 用户从设置返回时 onResume 会重新检查，通过后对应卡片会自动消失。
            if (!uiState.overlayPermissionGranted || !uiState.isAccessibilityEnabled) {
                Text(
                    text = stringResource(R.string.permission_checklist_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!uiState.overlayPermissionGranted) {
                PermissionCard(
                    title = stringResource(R.string.overlay_permission_title),
                    reason = stringResource(R.string.overlay_permission_reason),
                    actionLabel = stringResource(R.string.action_open_overlay_settings),
                    onAction = { viewModel.openOverlaySettings() },
                    retryLabel = stringResource(R.string.action_retry_check),
                    onRetry = { viewModel.refreshPermissions() },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (!uiState.isAccessibilityEnabled) {
                PermissionCard(
                    title = stringResource(R.string.accessibility_permission_title),
                    reason = stringResource(R.string.accessibility_permission_reason),
                    actionLabel = stringResource(R.string.action_open_accessibility_settings),
                    onAction = { viewModel.openAccessibilitySettings() },
                    retryLabel = stringResource(R.string.action_retry_check),
                    onRetry = { viewModel.refreshPermissions() },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * 状态卡片：显示当前状态，并带一个状态指示灯。
 */
@Composable
private fun StatusCard(
    isLongPressModeOn: Boolean,
    isPressing: Boolean,
    modifier: Modifier = Modifier
) {
    // 详细坐标之类的信息放在悬浮侧边栏显示，首页只表达"整体处于什么状态"。
    val statusText = when {
        isPressing -> stringResource(R.string.status_pressing)
        isLongPressModeOn -> stringResource(R.string.status_running)
        else -> stringResource(R.string.status_not_started)
    }

    val dotColor = when {
        isPressing -> ColorPressing
        isLongPressModeOn -> ColorReady
        else -> ColorIdle
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color = dotColor, shape = CircleShape)
            )

            Text(
                text = statusText,
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 通用权限引导卡片。
 *
 * 悬浮窗权限和无障碍服务的引导流程完全一样，所以抽成一个组件：
 *   检查 -> 没有 -> 显示说明 -> 跳系统设置 -> 用户操作 -> 返回 App -> 重新检查
 * 最后一步"返回后重新检查"由 MainActivity 在 onResume 里触发，不在这里。
 */
@Composable
private fun PermissionCard(
    title: String,
    reason: String,
    actionLabel: String,
    onAction: () -> Unit,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Text(
                text = reason,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(text = actionLabel)
            }

            Text(
                text = stringResource(R.string.permission_retry_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )

            // 有些用户在设置页找不到开关就直接返回了，给一个手动重试入口。
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(text = retryLabel)
            }
        }
    }
}

// ---- 预览：不装到手机 / 模拟器上也能在 Android Studio 里看到界面 ----

@Preview(showBackground = true, name = "首页 - 未启动")
@Composable
private fun MainScreenPreview() {
    LongPressToolTheme {
        PreviewContent(isLongPressModeOn = false, isPressing = false)
    }
}

@Preview(showBackground = true, name = "首页 - 已启动")
@Composable
private fun MainScreenRunningPreview() {
    LongPressToolTheme {
        PreviewContent(isLongPressModeOn = true, isPressing = false)
    }
}

/** 预览用的简化版首页：绕过 ViewModel，只画状态卡片和主按钮。 */
@Composable
private fun PreviewContent(isLongPressModeOn: Boolean, isPressing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StatusCard(isLongPressModeOn = isLongPressModeOn, isPressing = isPressing)
        Button(
            onClick = {},
            enabled = !isLongPressModeOn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
        ) {
            Text(text = stringResource(R.string.action_toggle_long_press_mode))
        }
    }
}
