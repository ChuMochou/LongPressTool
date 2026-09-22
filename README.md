# 长按器（LongPressTool）

一个用 **Kotlin + Jetpack Compose** 写的 Android 小工具：在你**指定的屏幕坐标**上，
用 Android 官方的无障碍手势 API 执行**真实的、持续按住不放的长按**。

> 个人自用项目，非商业发布。不使用 Root，不修改系统文件，不绕过 Android 权限机制 ——
> 它用的就是 Android 官方为"辅助功能"提供的 `AccessibilityService` + `dispatchGesture()`。

---

## 一、它能做什么

1. 打开 App，点「**启动长按器**」（首次需要授予两个权限，见下）
2. 屏幕上出现两个悬浮窗：
   - **控制侧边栏**：可拖动，含「启动」「停止」「关闭」和当前状态
   - **圆形准星**：可拖动，对准你想长按的位置
3. 把准星拖到目标位置（例如某个 App 的按钮、聊天消息、桌面图标）
4. 点「**启动**」→ 该位置开始持续按住不放（准星变红 + 转动的虚线环 + 扩散波纹）
5. 点「**停止**」→ 立刻松手
6. 侧边栏或首页的「**关闭**」→ 结束长按并移除两个悬浮窗

首页还有一个「**退出**」按钮：关闭悬浮窗并退出应用（同时从最近任务列表移除）。

---

## 二、运行前必须开启的两个权限

这两个都是 Android 的**特殊权限**，App 无法自己申请，必须由用户手动去系统设置里打开。
App 首页会显示缺哪个、并给一个直达设置的按钮。

### 1. 显示在其他应用上层（`SYSTEM_ALERT_WINDOW`）

侧边栏和准星要浮在其他 App 之上，必须有它。

**设置 → 应用 → 长按器 → 显示在其他应用上层 → 允许**

### 2. 无障碍服务（`AccessibilityService`）

这是**唯一**能让 App 向其他 App 注入触摸事件的官方途径，长按功能完全依赖它。

**设置 → 无障碍 → 已下载的应用 → 长按器 → 打开**

本服务只申请了「执行手势」这一项能力，**没有**申请「读取界面内容」
（见 `res/xml/accessibility_service_config.xml` 里的 `canRetrieveWindowContent="false"`）。

> **⚠️ Android 13 及以上：可能出现「受限设置」**
>
> 如果你是**用 Android Studio 直接安装**或**手动装 APK**（而不是从应用商店安装），
> 系统会把它判定为侧载应用，默认**禁止开启它的无障碍服务**。表现为：在无障碍列表里
> 能看到「长按器」，但开关点不动、或自己弹回去。
>
> 解决办法：
> **设置 → 应用 → 长按器 → 右上角三个点 → 「允许受限设置」**，
> 然后再回到无障碍页面打开开关。

---

## 三、如何构建与运行

### 环境要求

| 项目 | 版本 |
|---|---|
| JDK | 17 或更高（Android Studio 自带 JBR 即可） |
| Gradle | 9.6.0（仓库自带 wrapper，无需手动安装） |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.2.10 |
| compileSdk / targetSdk | 37 |
| **minSdk** | **24（Android 7.0）** |

### 用 Android Studio（推荐）

1. `File → Open`，选择本项目根目录
2. 等 Gradle Sync 完成
3. 连接设备，点 ▶ Run

### 用命令行

```powershell
# 编译 debug 包
.\gradlew.bat :app:assembleDebug

# 产物位置
# app\build\outputs\apk\debug\app-debug.apk

# 跑单元测试
.\gradlew.bat :app:testDebugUnitTest

# 静态检查
.\gradlew.bat :app:lintDebug
```

### 版本兼容性说明

**长按功能需要 Android 8.0（API 26）及以上。**

- API 26+：完整功能（持续按住，一次可达约 1 分钟，之后自动接力）
- API 24 / 25：系统不支持跨手势接力，单次手势最多 60 秒；App 会明确提示，不会假装能用

---

## 四、项目结构

```
app/src/main/java/com/example/longpresstool/
│
├── LongPressToolApp.kt              Application 入口：全局未捕获异常兜底
├── MainActivity.kt                  唯一的 Activity（单 Activity 架构）
│
├── model/                           状态层（唯一数据源）
│   ├── LongPressUiState.kt          全局状态 + StateFlow 容器 + 运行阶段枚举
│   └── SidebarPosition.kt           悬浮窗锚点位置（dp）
│
├── permission/                      两个特殊权限的检查与引导
│   ├── OverlayPermission.kt         悬浮窗权限
│   ├── AccessibilityPermission.kt   无障碍服务状态检测
│   └── AppPreferences.kt            用 SharedPreferences 记住两个悬浮窗的位置
│
├── service/                         后台服务（本项目的主体）
│   ├── FloatingWindowService.kt     前台 Service：管理侧边栏 + 准星两个悬浮窗
│   ├── ScreenGeometry.kt            屏幕坐标与可用区域计算（纯逻辑）
│   ├── LongPressAccessibilityService.kt  无障碍服务：持有手势派发器
│   └── LongPressGestureDispatcher.kt     手势派发：真正的长按实现
│
├── ui/
│   ├── MainScreen.kt                首页 Compose 界面
│   ├── MainViewModel.kt             首页状态与动作
│   ├── theme/                       Compose 主题（模板，未改动）
│   └── widget/
│       ├── TouchIndicatorView.kt    自绘圆形准星（三态 + 动画）
│       └── StatusDotView.kt         自绘状态指示灯（颜色过渡 + 脉冲）
│
└── res/
    ├── layout/overlay_sidebar.xml           侧边栏界面（传统 View）
    ├── layout/overlay_position_indicator.xml 准星界面
    ├── xml/accessibility_service_config.xml  无障碍服务能力声明
    └── values/                         colors / strings / themes
```

### 为什么会有"传统 View"和"Compose"两套界面

- **首页**用 Compose：它是普通 Activity 界面，Compose 最合适。
- **悬浮窗**用传统 View：悬浮窗不属于任何 Activity，在里面用 Compose 需要额外搭
  `ComposeView` + `LifecycleOwner` + `SavedStateRegistryOwner` 三件套，
  对一个只有几个按钮的面板来说是纯粹的负担。

---

## 五、架构与数据流

```
                    ┌─────────────────────────────┐
                    │  LongPressStateHolder        │
                    │  （object 单例 + StateFlow）  │
                    │  ← 全应用唯一数据源            │
                    └──────────┬──────────────────┘
                               │ 订阅
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
   MainViewModel        FloatingWindowService   LongPressAccessibilityService
   （首页界面）           （侧边栏 + 准星）        （手势派发中/状态）
          │                    │                    │
          └──── 只读状态、只发事件（单向数据流）──────┘
```

- **单向数据流**：界面只读状态、只发事件，永远不自己存一份。这样界面不可能和真实状态不一致。
- **两个 Service 都在同一个进程**，所以直接用共享的 `StateFlow` 通信，不需要 AIDL/Binder。
- **为什么必须用 Service 而不是 Activity**：Activity 只有自己在前台时才可见，
  用户一切到别的 App 就消失了；跨应用的悬浮界面只能由 Service 通过 `WindowManager` 添加。

---

## 六、已知限制

这些是 **Android 平台的限制**，不是实现缺陷（详细原理见 [`docs/PLATFORM_NOTES.md`](docs/PLATFORM_NOTES.md)）：

1. **单次手势最长 60 秒**（`GestureDescription.MAX_GESTURE_DURATION_MS`）。
   本应用的做法是一次按住 58 秒，结束后立刻接力下一笔，实测两次之间只隔约 65ms。
2. **没有取消已派发手势的 API**。停止的做法是派发一支全新的极短笔画 ——
   因为"派发新手势会取消进行中的手势"，这一下就完成了松手。
3. **长按期间准星必须"看得见但摸不着"**（`FLAG_NOT_TOUCHABLE`），
   否则注入的触摸会被自己的悬浮窗吃掉，被长按的 App 收不到事件。
   但**侧边栏必须保持可点击**，否则按不了「停止」。
4. **如果侧边栏正好压在长按目标上**，注入的触摸会被侧边栏接收。
   这种情况无法自动解决，App 会在启动时提示你把它拖开。
5. **用户手指触摸屏幕会打断注入的手势**（系统行为），此时长按会结束。

---

## 七、开发过程

本项目按 8 个阶段逐步开发，每一阶段的提交记录保留了完整的演进过程：

| 阶段 | 内容 |
|---|---|
| Phase 1 | 项目初始化 + Compose 首页 |
| Phase 2 | 悬浮窗权限流程 + 前台 Service + 可拖动侧边栏 |
| Phase 3 | 圆形准星、拖动、坐标持久化 |
| Phase 4 | AccessibilityService 接入 + 权限引导 |
| Phase 5 | 用 `dispatchGesture` 实现真实长按 |
| Phase 6 | 状态动画（就绪 / 长按中 / 停止） |
| Phase 7 | 异常处理与生命周期健壮性 |
| Phase 8 | 代码整理、单元测试、文档 |

开发中踩到的 Android 平台坑（含实测数据）都记在 [`docs/PLATFORM_NOTES.md`](docs/PLATFORM_NOTES.md)，
其中几条是官方文档没有写明、只能靠实验确认的行为，对以后写同类功能很有参考价值。
