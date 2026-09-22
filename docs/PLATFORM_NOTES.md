# Android 平台踩坑记录

开发这个"长按器"时踩到的一些坑。记下来有两个原因：一是**其中几条官方文档没有写明、
只能靠实验确认**；二是它们都属于"现象很怪、原因很难猜"的类型，下次遇到同类问题可以少走弯路。

所有结论都在 **Android 17（API 37）模拟器**上实测过；标注了"实测"的地方都有数据。

---

## 1. Activity 无法显示在其他应用之上（架构层面的前提）

**现象**：一开始很自然会想"用一个 Activity 显示控制面板"。

**结论**：不行。Activity 只有自己处于前台时才可见，用户一切到别的 App 就消失了。
这是窗口管理机制，不是权限问题。

**正确做法**：跨应用的悬浮界面必须由 **Service** 通过 `WindowManager.addView()` 添加，
并且要用前台 Service（`startForeground()` + 常驻通知），否则进程随时可能被回收。

对应代码：`service/FloatingWindowService.kt`

---

## 2. `MaterialCardView` 在 Service 里必定崩溃 ⭐

**现象**：点「启动长按器」直接闪退。日志是：

```
java.lang.RuntimeException: Unable to create service ...FloatingWindowService
Caused by: android.view.InflateException: Error inflating class
           com.google.android.material.card.MaterialCardView
Caused by: java.lang.IllegalArgumentException: The style on this component requires
           your app theme to be Theme.MaterialComponents (or a descendant)
```

**为什么难查**：同一个 APK 里，**Activity 用 Material 主题渲染完全正常**，
只有 Service 崩。因为 Activity 会应用 `<activity android:theme>`，
而 **Service 的 Context 拿不到 Activity 的主题**，退回平台默认主题，
于是 Material 控件的主题校验直接抛异常。

**结论 / 做法**：**悬浮窗里的界面不要依赖主题属性。**
- 用系统原生控件（`FrameLayout` + 自绘背景、`android.widget.Button` + 自绘 selector）
- 颜色写死成 `@color` 常量，不要引用 `?attr/colorSurface`、`?attr/textAppearanceBodySmall` 这类
- 保险起见，inflate 时用 `ContextThemeWrapper` 显式套上应用主题
  （主题要从 `applicationInfo.theme` 读，`obtainStyledAttributes(android.R.attr.theme)` 在 Service 里读不到）

对应代码：`res/layout/overlay_sidebar.xml`、`service/FloatingWindowService.kt`

---

## 3. `willContinue = true` 会让手势被系统提前截断 ⭐⭐

**这是本项目最反直觉的一条，官方文档没有写。**

**背景**：单次手势有 60 秒上限（`MAX_GESTURE_DURATION_MS`），
所以"无限期按住"必须跨手势接力：让一笔标上 `willContinue = true`（结束时指针不抬起），
下一笔用 `continueStroke()` 接着按。

**实测对照**（同一台设备、同一坐标，只改 `willContinue`）：

| 笔画时长 | `willContinue` | 系统实际执行 |
|---|---|---|
| 5000 ms | `false` | **5021 ms** ✅ 完整 |
| 5000 ms | `true`  | **2520 ms** ⚠️ 只有一半 |
| 10000 ms | `false` | **10015 ms** ✅ 完整 |
| 10000 ms | `true`  | **5038 ms** ⚠️ 只有一半 |

**结论**：`willContinue = true` 的笔画大约只能跑**一半时长**就被判定结束。
于是"每段 1 秒、一段接一段"的方案会变成每 1~2 秒就要接力一次，
而**每次接力都要派发新手势、派发新手势又会取消进行中的手势** → 长按不断被打断。

**最终做法**：反过来——**一次手势就放一笔 `willContinue = false` 的长笔画**，
让它完整执行。实测 58000 ms 能跑满 **58014 / 58081 / 58007 ms**，
也就是"一次连续按压接近 1 分钟"；结束后立刻接力下一笔，两次之间只隔约 **65 ms**。

对应代码：`service/LongPressGestureDispatcher.kt`

---

## 4. `continueStroke` 的 `startTime` 是"相对本次手势起点"的绝对时间 ⭐

**踩坑经过**：想在一个手势里排 12 段（每段 5 秒）来减少接力次数，
每段 `startTime` 都写了 `0`，结果整个手势**只按了 5 秒**。

**原因**：
- 官方文档：`startTime` = "The time, in milliseconds, **from the start of the new gesture**,
  to the time this stroke should start." —— 是相对**手势起点**的绝对时间，不是相对上一段的偏移。
- 而 `GestureDescription.getTotalDuration()` 取的是**所有笔画 `endTime` 的最大值**
  （见 `GestureDescription.java`）。每段都从 0 开始 → 全部重叠 → 总时长只有一段那么长。

**结论**：多段时必须让 `startTime` **依次累加**（0, 5000, 10000, …）。
（本项目的最终方案只用一笔，所以不涉及；但记下来免得以后再踩。）

---

## 5. 只有标了 `willContinue` 的笔画才能被 `continueStroke` 续接

**现象**：停止时对当前笔画调用 `continueStroke()` 抛异常：

```
java.lang.IllegalStateException: Only strokes marked willContinue can be continued
    at GestureDescription$StrokeDescription.continueStroke(GestureDescription.java:372)
```

**连锁反应很坑**：这个异常被 `catch` 吞掉后，**手指根本没被抬起**——
用户按了「停止」却毫无反应，得等 58 秒自己结束；
而且派发器里残留了失效状态，导致第二次点「启动」立刻自己退出。

**结论**：`willContinue` 是**跨手势交棒**用的，所以**不要**用 `continueStroke` 去实现"停止"。
停止要派发一支**全新的、极短的**笔画（见下一条）。

---

## 6. 没有取消手势的 API —— 但"派发新手势"可以当取消用 ⭐

官方文档：

> **Dispatch a gesture to the touch screen. Any gestures currently in progress,
> whether from the user, this service, or another service, will be cancelled.**

**结论**：虽然不能"取消某个手势"，但只要**派发一支新的极短笔画**，
系统就会取消掉正在进行的长按 —— 这一下同时完成了两件事：

1. 取消 58 秒长按 → **指针抬起，这就是"停止"**；
2. 用一支 50 ms 的笔画顶替它 → 短到用户察觉不到。

所以停止逻辑完全不依赖 `continueStroke`，也不受 `willContinue` 取值影响。

对应代码：`LongPressGestureDispatcher.stopHold()`

---

## 7. 注入的触摸会被"最上层的可触摸窗口"接收 ⭐

**问题**：准星正好压在目标坐标上。如果它还能接收触摸，
那么注入的长按会被**我们自己的悬浮窗**吃掉，被长按的 App 收不到任何事件 ——
现象是"点了启动但目标 App 毫无反应"，而且极难排查。

**做法**：
- 长按期间给**准星**加 `FLAG_NOT_TOUCHABLE`（看得见、摸不着），触摸穿透到下层；
- **侧边栏绝对不能一起加**，否则「停止」按钮也点不动了（这个错误我犯过一次，
  现象就是"开启长按后点不到停止"）；
- 停止后要在**所有结束路径**上恢复可触摸（正常停止 / 被系统取消 / 无障碍掉线），
  否则用户会发现准星"拖不动了"。

**副作用**：侧边栏必须保持可点击，代价是它万一压在目标上就会吞掉长按。
这个无法自动解决，App 会在启动时检测并提示用户把侧边栏拖开。

---

## 8. 零长度 Path 的边界行为

文档说零长度 path（单个 `moveTo`）就是"按住不动"的触摸。
实测中给 1 像素的极小位移更稳（大概与"真正的路径"的打点逻辑有关），
肉眼当然看不出移动。属于防御性写法。

---

## 9. 无障碍服务的状态检测

**不要**去读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 然后自己按 `:` 和 `/` 拆分比较：
那个设置项是**无障碍框架内部格式**（把包名/类名里的 `.` 换成了 `/`），手写拼接容易漏规则。

**推荐**：用 `AccessibilityManager.getEnabledAccessibilityServiceList()` 拿到已启用列表，
再用 `ComponentName` 比对（本项目同时用 `flattenToString()` 直接比和反解再比，双重保险）。

对应代码：`permission/AccessibilityPermission.kt`

---

## 10. 关闭无障碍时系统**不会**通知悬浮窗

用户在系统设置里关掉无障碍服务时，悬浮窗这边收不到任何回调；
无障碍服务自己的 `onUnbind` 在进程已被回收时也不会执行。

**结论**：只能由悬浮窗这边**定时去问系统**（本项目每秒查一次，只在变化时写状态）。
另外，无障碍服务自己的 `onServiceConnected` / `onUnbind` 也要同步状态，两条路一起用才可靠。

---

## 11. `am force-stop` 会连带清掉无障碍的启用状态

调试时发现的：执行 `adb shell am force-stop <包名>` 之后，
`enabled_accessibility_services` 会变成 `null`、`accessibility_enabled` 变成 `0`。

这是 Android 的设计（强制停止会停掉该应用的无障碍服务），
但会让调试很迷惑 —— **"我明明开了无障碍，怎么又变成没开"**。
调试时记得重新打开，或者改用 `am kill`（但它杀不掉持有前台 Service 的进程）。

---

## 12. `adb shell am start` 不会重新投递 intent 给已在栈顶的 Activity

用 `am start --ez someFlag true` 传参数做调试入口时，
如果目标 Activity 已经在前台，命令会返回 `result code=3` 且 **`onNewIntent` 不会触发**
（表现为"调试开关没生效"）。需要先 `force-stop` 或按 HOME 切走，做一次真正的冷启动。

---

## 13. 前台 Service 的类型（Android 14+）

Android 14（API 34）起 `startForeground()` 必须带 `foregroundServiceType`，
否则抛异常直接崩。本项目用 `specialUse`（"与其他应用交互的悬浮控制面板"），
并在 Manifest 里配了对应的 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`。

另外 Android 12+ 有"从后台启动前台服务"的限制，
所以 `startForegroundService()` 要包 `try/catch`，否则用户点一下按钮就闪退。

---

## 14. 悬浮窗坐标系：不要碰 `FLAG_LAYOUT_NO_LIMITS`

`dispatchGesture()` 需要的是**屏幕绝对坐标**（原点 = 真实屏幕左上角，含状态栏/刘海区域）。

本项目的做法是让准星窗口尺寸**恰好等于**准星尺寸（60dp × 60dp），并且
**不加** `FLAG_LAYOUT_NO_LIMITS`，于是：

```
准星圆心的屏幕坐标 = (窗口 x + 30dp, 窗口 y + 30dp)
```

不需要任何跨版本换算。为什么刻意避开那个 flag：加了它之后窗口坐标系会变成
"包含状态栏/导航栏的全部区域"，而它的实现在 **API ≥ 30 用 `displayFrame`、
API 24~29 用状态栏高度与导航栏可见性推算**，两套算法结论不一致，
很容易写出只在一部分手机上正确的代码。

另外算可用区域时，起点要用**真实屏幕尺寸**
（API 30+ 用 `maximumWindowMetrics`，24~29 用 `Display.getRealSize()`），
**不要**用 `resources.displayMetrics` —— 后者在部分机型上不含系统栏，会导致坐标整体偏移。

对应代码：`service/ScreenGeometry.kt`
