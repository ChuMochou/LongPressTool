package com.example.longpresstool.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.longpresstool.BuildConfig
import com.example.longpresstool.MainActivity
import com.example.longpresstool.R
import com.example.longpresstool.model.LongPressPhase
import com.example.longpresstool.model.LongPressStateHolder
import com.example.longpresstool.model.SidebarPosition
import com.example.longpresstool.permission.AccessibilityPermission
import com.example.longpresstool.permission.AppPreferences
import com.example.longpresstool.permission.OverlayPermission
import com.example.longpresstool.service.LongPressAccessibilityService
import com.example.longpresstool.ui.widget.TouchIndicatorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮界面的宿主 Service。
 *
 * ===== 为什么必须是 Service，不能是 Activity？ =====
 * 需求要求侧边栏"在其他 App 运行时继续存在"。Activity 只有自己在前台时才可见，
 * 用户一切到别的应用，界面就没了。这是 Android 的窗口管理机制，不是权限问题。
 * 所以跨应用的悬浮界面只能由 Service 通过 WindowManager 添加。
 *
 * ===== 为什么是"前台 Service"？ =====
 * Android 8.0 起，带有长期可见界面的 Service 必须 startForeground() 并显示常驻通知，
 * 否则进程随时可能被系统回收，悬浮窗就消失了。Android 14 (API 34) 起还必须声明
 * foregroundServiceType（本应用为 specialUse）。
 *
 * ===== 本类管两个悬浮窗 =====
 * 1. 控制侧边栏（overlay_sidebar.xml）—— 按钮操作区；
 * 2. 圆形位置指示器（overlay_position_indicator.xml）—— 只在"选择位置"时出现。
 * 两者是独立窗口：指示器可以拖到任何地方，不会因为侧边栏挡着而选不了位置。
 *
 * ===== 坐标系（本项目最容易出错的地方，务必看懂） =====
 * dispatchGesture() 需要的是**屏幕绝对坐标**（原点 = 真实屏幕左上角，包含状态栏和刘海区域）。
 *
 * 指示器窗口的做法是：窗口尺寸正好 60dp x 96dp，圆形占左上角 60dp x 60dp，并且
 * **故意不加 FLAG_LAYOUT_NO_LIMITS 之类的特殊 flag**。这样窗口位置就是相对于
 * 真实屏幕左上角计算的，于是：
 *
 *     圆心屏幕坐标 = (窗口 x + 30dp, 窗口 y + 30dp)
 *
 * 不需要任何跨版本的坐标换算。之所以强调这点：
 * 加了 FLAG_LAYOUT_NO_LIMITS 后窗口坐标系会变成"包含状态栏/导航栏的全部区域"，
 * 而且这个行为在 API >= 30 时用的是 displayFrame、在 24~29 时用的是
 * 状态栏高度与导航栏可见性推算，**不同版本结论不一致**，很容易写出只在一部分手机上正确的代码。
 * 我们直接绕开这个坑：不碰那个 flag，坐标就是绝对坐标。
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager

    /** 侧边栏的根视图与窗口参数。 */
    private var sidebarView: View? = null
    private var sidebarParams: WindowManager.LayoutParams? = null

    /** 圆形位置指示器的根视图与窗口参数。 */
    private var indicatorRootView: View? = null
    private var indicatorView: TouchIndicatorView? = null
    private var indicatorLabelView: TextView? = null
    private var indicatorParams: WindowManager.LayoutParams? = null

    /** Service 自己的协程作用域，用来订阅状态流。onDestroy 必须取消。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 侧边栏里的视图引用。showSidebar() 之后才有效，所以都用可空类型 ----
    private var statusDotView: View? = null
    private var statusTextView: TextView? = null
    private var hintTextView: TextView? = null
    private var limitTextView: TextView? = null
    private var selectPositionButton: Button? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var closeButton: Button? = null

    // ---- 侧边栏拖动过程中的临时数据 ----
    private var sidebarDragStartRawX = 0f
    private var sidebarDragStartRawY = 0f
    private var sidebarDragStartParamX = 0
    private var sidebarDragStartParamY = 0
    private var isDraggingSidebar = false

    // ---- 指示器拖动过程中的临时数据 ----
    private var indicatorDragStartRawX = 0f
    private var indicatorDragStartRawY = 0f
    private var indicatorDragStartParamX = 0
    private var indicatorDragStartParamY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务被再次 start 时（例如调试命令、或用户重复点启动）会走到这里。
     * 正常情况下什么都不用做：侧边栏已经在 onCreate 里显示好了。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleDebugCommand(intent)
        // NOT_STICKY：侧边栏是我们的界面，被系统杀掉后自动重启一个"没有界面需求"的
        // Service 没有意义，反而可能让用户看到孤立的通知。需要时由用户重新点启动。
        return START_NOT_STICKY
    }

    /**
     * 专门用来加载悬浮窗布局的 Context。
     *
     * 为什么要这么绕：悬浮窗是 Service 添加的，而 **Service 的 Context 主题
     * 和 Activity 不是一回事**。如果布局里用了依赖主题的属性（哪怕只是
     * ?attr/textAppearanceBodySmall 这种文字样式），在部分设备上会取不到值，
     * 严重时直接抛异常导致闪退（本项目就踩过 MaterialCardView 那个坑）。
     *
     * 这里的做法是显式给布局加载器套上应用自己的主题，让结果与设备无关。
     * 即使主题解析失败（例如被 ROM 改动），也只是回退到无主题包装，
     * 因为布局本身已经做到零主题依赖，不会崩。
     */
    private val layoutContext: Context by lazy {
        val themeResId = resolveAppThemeResId()
        if (themeResId != 0) {
            ContextThemeWrapper(this, themeResId)
        } else {
            Log.w(TAG, "未能解析到应用主题 Theme.LongPressTool，悬浮窗将使用默认外观")
            this
        }
    }

    private fun resolveAppThemeResId(): Int {
        // 应用的 android:theme 就记录在 ApplicationInfo.theme 里，直接读它最可靠。
        // 别用 obtainStyledAttributes(intArrayOf(android.R.attr.theme)) —— 在 Service 里
        // 它可能读不到 application 上的主题（本项目实测返回 0）。
        return try {
            applicationInfo.theme
        } catch (e: Exception) {
            0
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)

        // 升为前台 Service。内部已经处理了失败情况，不会抛异常。
        promoteToForeground()

        // 整个 onCreate 都不允许因为单个界面问题把 App 拖崩（需求第十三节：Service 异常退出）。
        // 侧边栏起不来时，至少要把前台服务干净地结束掉，而不是抛异常崩溃。
        try {
            showSidebar()
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮侧边栏失败", e)
            stopSelf()
            return
        }

        observeState()
    }

    // ==================== 前台 Service 与通知 ====================

    /**
     * Android 14 (API 34) 起，startForeground() 必须带上 foregroundServiceType，
     * 否则会抛异常直接崩溃。类型必须与 AndroidManifest 里声明的一致。
     */
    private fun promoteToForeground() {
        try {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
        } catch (e: Exception) {
            // 例如用户在设置里禁用了本应用的通知、或 ROM 限制严格。
            // 侧边栏照常显示（功能优先），但持久性会差一些。绝不因此崩溃。
            isRunning = true
        }
    }

    private fun createNotificationChannel() {
        // 通知渠道从 Android 8.0 (API 26) 起才需要，本应用 minSdk = 24，所以要判断。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW   // 低重要性：不响铃、不弹横幅
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)   // 常驻，用户不能划掉（通过侧边栏的「关闭」结束）
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== 悬浮窗的通用参数 ====================

    /**
     * 选一个当前系统版本真正支持的悬浮窗类型。
     *
     * - Android 8.0 (API 26) 及以上：只能用 TYPE_APPLICATION_OVERLAY。
     *   老类型（TYPE_PHONE / TYPE_SYSTEM_ALERT 等）从 26 起对普通应用全面失效，
     *   继续使用会抛 BadTokenException 直接崩溃。
     * - Android 7.x (API 24 / 25)：系统还不认识 TYPE_APPLICATION_OVERLAY，只能用当时的 TYPE_PHONE。
     *
     * TYPE_PHONE 虽然标记为 @Deprecated，但这是兼容 24/25 的唯一做法；
     * API 26+ 永远不会走到这个分支。
     */
    @Suppress("DEPRECATION")
    private fun resolveWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** 两个悬浮窗共用的 flag：不抢焦点，但**不影响点击**。 */
    private val baseWindowFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    // ==================== 侧边栏 ====================

    private fun showSidebar(intent: Intent? = null) {
        if (sidebarView != null) return   // 已经显示，避免重复添加窗口

        if (!OverlayPermission.isGranted(this)) {
            Toast.makeText(this, R.string.overlay_permission_hint_not_granted, Toast.LENGTH_LONG)
                .show()
            stopSelf()
            return
        }

        // 给 inflate() 传一个临时父容器（而不是 null），这样布局根节点上的
        // layout_* 参数才会被正确解析。
        // 用 layoutContext 而不是 this，理由见 layoutContext 的注释。
        val view = LayoutInflater.from(layoutContext)
            .inflate(R.layout.overlay_sidebar, FrameLayout(this), false)

        val saved = AppPreferences.loadSidebarPosition(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            resolveWindowType(),
            baseWindowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(saved.xDp)
            y = dpToPx(saved.yDp)
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 例如权限刚被撤销、或同类型窗口被系统拒绝。不要崩溃。
            stopSelf()
            return
        }

        sidebarView = view
        sidebarParams = params

        bindSidebarViews(view)
        setupSidebarClickListeners()
        setupSidebarDrag(view)
        observeInsetsChanges(view)
        clampSidebarIntoScreen(params)
        safeUpdateLayout(view, params)
    }

    /**
     * 【仅 debug 构建】用 adb 直接触发一次长按，便于在真机/模拟器上排查长按问题：
     *
     *   adb shell am start-foreground-service \
     *     -n <包名>/.service.FloatingWindowService \
     *     --ei debug_x 540 --ei debug_y 1200
     *
     * 注意：这个服务是 exported=false，所以这条 adb 命令**只能由 shell/root 执行**，
     * 普通应用无法调用；而且只在 BuildConfig.DEBUG 时生效，
     * 正式发布（release）构建里这段逻辑不会被执行。
     */
    private fun handleDebugCommand(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val x = intent?.getIntExtra(EXTRA_DEBUG_X, -1) ?: -1
        val y = intent?.getIntExtra(EXTRA_DEBUG_Y, -1) ?: -1
        if (x < 0 || y < 0) return

        Log.d(TAG, "debug: 在 ($x, $y) 触发长按")
        LongPressStateHolder.setTargetPosition(x, y)
        startLongPress(x, y)
    }

    private fun bindSidebarViews(root: View) {
        statusDotView = root.findViewById(R.id.status_dot)
        statusTextView = root.findViewById(R.id.text_status)
        hintTextView = root.findViewById(R.id.text_hint)
        limitTextView = root.findViewById(R.id.text_limit)
        selectPositionButton = root.findViewById(R.id.button_select_position)
        startButton = root.findViewById(R.id.button_start)
        stopButton = root.findViewById(R.id.button_stop)
        closeButton = root.findViewById(R.id.button_close)
    }

    private fun setupSidebarClickListeners() {
        closeButton?.setOnClickListener { closeEverything() }

        selectPositionButton?.setOnClickListener {
            val state = LongPressStateHolder.state.value
            if (state.isSelectingPosition) {
                // 再次点击 = 完成选择：隐藏指示器，但保留刚才记录的坐标。
                LongPressStateHolder.setSelectingPosition(false)
            } else {
                LongPressStateHolder.setSelectingPosition(true)
            }
        }

        startButton?.setOnClickListener {
            val state = LongPressStateHolder.state.value

            // 启动前把前提条件查一遍，缺什么就明确告诉用户缺什么（需求第七节）。
            if (!state.hasSelectedPosition) {
                Toast.makeText(this, R.string.error_no_position_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AccessibilityPermission.isEnabled(this)) {
                // 用户在系统设置里把无障碍关掉了：给出提示并引导回去开启。
                Toast.makeText(this, R.string.error_accessibility_disabled, Toast.LENGTH_LONG).show()
                AccessibilityPermission.openSettings(this)
                return@setOnClickListener
            }

            startLongPress(state.targetX, state.targetY)
        }

        stopButton?.setOnClickListener {
            stopLongPress()
        }
    }

    // ==================== 长按的启动与停止 ====================

    /**
     * 开始长按。
     *
     * 顺序很重要：
     * 1. 先让两个悬浮窗"触摸穿透"（FLAG_NOT_TOUCHABLE）；
     * 2. 再派发手势。
     *
     * 为什么必须这样（这是 Phase 5 最容易翻车的地方）：
     * dispatchGesture 注入的触摸，会交给**该坐标上最上层的可触摸窗口**。
     * 指示器圆形默认就压在目标点上，如果它还能接收触摸，
     * 那么这次"长按"会被我们自己的悬浮窗吃掉，被长按的那个 App 根本收不到事件。
     * 所以长按期间指示器必须"看得见但摸不着"。
     */
    private fun startLongPress(x: Int, y: Int) {
        setOverlaysTouchable(false)

        val result = LongPressAccessibilityService.startHold(x, y)

        if (result == null) {
            // startHold 内部会通过状态流驱动界面，这里不用手动改状态。
            Toast.makeText(this, R.string.overlay_status_pressing_short, Toast.LENGTH_SHORT).show()
            return
        }

        // 启动失败：把触摸能力还回去，否则用户连指示器都拖不动了。
        setOverlaysTouchable(true)

        val messageRes = when (result) {
            HoldStartResult.ServiceNotConnected -> R.string.error_accessibility_not_connected
            HoldStartResult.UnsupportedAndroidVersion -> R.string.error_unsupported_android_version
            HoldStartResult.AlreadyRunning -> R.string.error_already_pressing
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    /** 停止长按。手势会在 1 秒内自然抬起（无法取消已派发的手势，见派发器的说明）。 */
    private fun stopLongPress() {
        LongPressAccessibilityService.stopHold()
        // 立刻把触摸能力还回去，用户马上又能拖动指示器。
        setOverlaysTouchable(true)
    }

    /**
     * 控制两个悬浮窗是否接收触摸。
     *
     * @param touchable false = 加 FLAG_NOT_TOUCHABLE，窗口变成"看得见但摸不着"，
     *                  触摸穿过它交给下层的其他应用。长按期间必须是 false。
     *
     * 注意：仅仅改 params.flags 是不够的，必须调用 updateViewLayout() 才会生效。
     */
    private fun setOverlaysTouchable(touchable: Boolean) {
        applyTouchableFlag(sidebarView, sidebarParams, touchable)
        applyTouchableFlag(indicatorRootView, indicatorParams, touchable)
    }

    private fun applyTouchableFlag(
        view: View?,
        params: WindowManager.LayoutParams?,
        touchable: Boolean
    ) {
        if (view == null || params == null) return

        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        safeUpdateLayout(view, params)
    }

    /**
     * 让整个侧边栏可以用手指拖动。
     *
     * 两个关键点：
     * 1. 用 rawX / rawY（相对整个屏幕）而不是 x / y（相对被触摸的 View）。
     *    拖动时 View 自己在移动，用相对坐标会越拖越飘。
     * 2. 必须自己区分"拖动"和"点击"：OnTouchListener 在 onTouchEvent **之前**拿到事件，
     *    无条件返回 true 按钮就永远收不到点击。所以只有位移超过系统阈值才算拖动。
     *
     * 补充：View 自己"可点击"时不会触发它的 OnTouchListener，
     * 所以按钮上的点击手势天然不会和拖动手势冲突。
     */
    private fun setupSidebarDrag(root: View) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        root.setOnTouchListener { _, event ->
            val params = sidebarParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sidebarDragStartRawX = event.rawX
                    sidebarDragStartRawY = event.rawY
                    sidebarDragStartParamX = params.x
                    sidebarDragStartParamY = params.y
                    isDraggingSidebar = false
                    false   // 返回 false，让子 View（按钮）有机会处理这次点击
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - sidebarDragStartRawX
                    val dy = event.rawY - sidebarDragStartRawY

                    if (!isDraggingSidebar && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDraggingSidebar = true
                    }

                    if (isDraggingSidebar) {
                        params.x = sidebarDragStartParamX + dx.toInt()
                        params.y = sidebarDragStartParamY + dy.toInt()
                        safeUpdateLayout(root, params)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingSidebar) {
                        clampSidebarIntoScreen(params)
                        safeUpdateLayout(root, params)
                        AppPreferences.saveSidebarPosition(
                            this,
                            SidebarPosition(pxToDp(params.x), pxToDp(params.y))
                        )
                        isDraggingSidebar = false
                        true
                    } else {
                        // 按无障碍规范，用了 OnTouchListener 就要在判定为点击时补一次 performClick()，
                        // 否则 TalkBack 等辅助功能无法触发该控件的点击。
                        root.performClick()
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun clampSidebarIntoScreen(params: WindowManager.LayoutParams) {
        val view = sidebarView ?: return
        val bounds = usableBounds()
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight

        params.x = params.x.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left))
        params.y = params.y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
    }

    // ==================== 圆形位置指示器 ====================

    private fun showIndicator() {
        if (indicatorRootView != null) return
        if (!OverlayPermission.isGranted(this)) return

        val root = LayoutInflater.from(layoutContext)
            .inflate(R.layout.overlay_position_indicator, FrameLayout(this), false)

        val params = WindowManager.LayoutParams(
            dpToPx(INDICATOR_SIZE_DP),          // 固定宽度：与布局保持一致
            dpToPx(INDICATOR_WINDOW_HEIGHT_DP), // 固定高度
            resolveWindowType(),
            baseWindowFlags,                    // 注意：不加 FLAG_LAYOUT_NO_LIMITS，理由见类注释
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 有记录就用记录的位置，否则放在屏幕中央附近。
            val bounds = usableBounds()
            val saved = AppPreferences.loadIndicatorPosition(this@FloatingWindowService)
            if (saved != null) {
                x = dpToPx(saved.xDp)
                y = dpToPx(saved.yDp)
            } else {
                x = bounds.centerX() - dpToPx(INDICATOR_SIZE_DP) / 2
                y = bounds.centerY() - dpToPx(INDICATOR_SIZE_DP) / 2
            }
        }

        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            return   // 加不上就算了，不要崩溃
        }

        indicatorRootView = root
        indicatorParams = params
        indicatorView = root.findViewById(R.id.touch_indicator)
        indicatorLabelView = root.findViewById(R.id.text_indicator_coords)

        setupIndicatorDrag(root)
        clampIndicatorIntoScreen(params)
        safeUpdateLayout(root, params)

        // 记录初始位置，并让 App 的其他部分知道"位置已经变了"。
        reportIndicatorCenter()
    }

    /**
     * 拖动指示器。
     *
     * 和侧边栏不同，指示器窗口里没有任何可点击的子 View，
     * 所以这里可以放心地在 ACTION_DOWN 就返回 true 并独占整个手势。
     *
     * 拖动过程中就实时把坐标写进全局状态，侧边栏上的坐标会跟着跳，
     * 用户能一边拖一边看数值。松手时再持久化到 SharedPreferences。
     */
    private fun setupIndicatorDrag(root: View) {
        root.setOnTouchListener { _, event ->
            val params = indicatorParams ?: return@setOnTouchListener false
            val myRoot = indicatorRootView ?: return@setOnTouchListener false

            // 长按期间两个窗口都是 FLAG_NOT_TOUCHABLE，本来收不到事件；
            // 但"刚加上 flag 的那一瞬间"可能还有一个在途的 UP/CANCEL。
            // 这里直接吃掉，避免把正在长按的坐标改掉。
            if (LongPressStateHolder.state.value.isPressing) {
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    indicatorDragStartRawX = event.rawX
                    indicatorDragStartRawY = event.rawY
                    indicatorDragStartParamX = params.x
                    indicatorDragStartParamY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = indicatorDragStartParamX + (event.rawX - indicatorDragStartRawX).toInt()
                    params.y = indicatorDragStartParamY + (event.rawY - indicatorDragStartRawY).toInt()
                    safeUpdateLayout(myRoot, params)
                    reportIndicatorCenter()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampIndicatorIntoScreen(params)
                    safeUpdateLayout(myRoot, params)
                    reportIndicatorCenter()
                    AppPreferences.saveIndicatorPosition(
                        this,
                        SidebarPosition(pxToDp(params.x), pxToDp(params.y))
                    )
                    root.performClick()   // 无障碍规范要求
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 把"圆心在屏幕上的绝对坐标"写进全局状态，并刷新指示器下方的坐标文字。
     * 这个坐标就是 Phase 5 要交给 dispatchGesture() 的那个点。
     */
    private fun reportIndicatorCenter() {
        val params = indicatorParams ?: return
        val size = dpToPx(INDICATOR_SIZE_DP)
        val centerX = params.x + size / 2
        val centerY = params.y + size / 2

        LongPressStateHolder.setTargetPosition(centerX, centerY)
        indicatorLabelView?.text = getString(R.string.indicator_coords, centerX, centerY)
    }

    private fun clampIndicatorIntoScreen(params: WindowManager.LayoutParams) {
        val bounds = usableBounds()
        val size = dpToPx(INDICATOR_SIZE_DP)
        val windowHeight = dpToPx(INDICATOR_WINDOW_HEIGHT_DP)

        params.x = params.x.coerceIn(bounds.left, (bounds.right - size).coerceAtLeast(bounds.left))
        params.y = params.y.coerceIn(bounds.top, (bounds.bottom - windowHeight).coerceAtLeast(bounds.top))
    }

    private fun hideIndicator() {
        val root = indicatorRootView ?: return

        // 隐藏前把最后的坐标存下来，下次打开还在原处。
        indicatorParams?.let {
            AppPreferences.saveIndicatorPosition(
                this,
                SidebarPosition(pxToDp(it.x), pxToDp(it.y))
            )
        }

        try {
            windowManager.removeView(root)
        } catch (e: Exception) {
            // 视图可能已经被系统移除，忽略。
        }

        indicatorRootView = null
        indicatorView = null
        indicatorLabelView = null
        indicatorParams = null
    }

    // ==================== 状态订阅与界面刷新 ====================

    /**
     * 订阅全局状态。任何地方改了状态，两个悬浮窗立刻跟着变，
     * 不需要谁去手动同步，也就不可能出现"显示的状态和真实状态不一致"。
     *
     * 同时每秒主动查一次无障碍服务的开启状态。
     * 为什么必须主动查（需求第十三节"用户关闭 AccessibilityService"）：
     * 用户在系统设置里关掉无障碍服务时，系统**不会**通知悬浮窗，
     * 无障碍服务自己的 onUnbind 在进程已被回收的情况下也不会执行。
     * 所以只能由这边定时问系统，才能及时把「启动」按钮置灰并给出提示。
     * 每秒一次只是读系统里一个列表，开销可以忽略。
     */
    private fun observeState() {
        var indicatorVisible = false
        var lastAccessibilityEnabled = LongPressStateHolder.state.value.isAccessibilityEnabled
        var lastPressing = LongPressStateHolder.state.value.isPressing

        serviceScope.launch {
            LongPressStateHolder.state.collectLatest { state ->
                renderSidebar(state)

                // 指示器只在"选择位置"和"长按中"出现：
                // 选择时要能拖，长按时要能看到视觉状态变化（需求第八节）。
                val shouldShow = state.isSelectingPosition || state.isPressing

                // 先决定"能不能摸"，再创建窗口，避免新建出来的窗口带着错误的 flag。
                // 长按期间必须穿透，理由见 startLongPress() 的说明。
                if (shouldShow) {
                    setOverlaysTouchable(!state.isPressing)
                }

                if (shouldShow && !indicatorVisible) {
                    showIndicator()
                } else if (!shouldShow && indicatorVisible) {
                    hideIndicator()
                }
                indicatorVisible = shouldShow

                // 长按结束（正常停止、被系统取消、无障碍服务掉线）时，把触摸能力还给用户。
                // 放在这里而不是只写在「停止」按钮里，是为了兜住所有结束路径，
                // 否则一旦漏掉某条路径，用户会发现悬浮窗"点不动了"。
                if (lastPressing && !state.isPressing) {
                    setOverlaysTouchable(true)
                }
                lastPressing = state.isPressing

                indicatorView?.setPressing(state.isPressing)
            }
        }

        serviceScope.launch {
            while (true) {
                val enabled = AccessibilityPermission.isEnabled(this@FloatingWindowService)
                if (enabled != lastAccessibilityEnabled) {
                    lastAccessibilityEnabled = enabled
                    // 只在变化时写状态，避免每秒触发一次无意义的重组。
                    LongPressStateHolder.setAccessibilityState(
                        enabled = enabled,
                        connected = LongPressAccessibilityService.isConnected()
                    )
                }
                delay(ACCESSIBILITY_POLL_INTERVAL_MS)
            }
        }
    }

    private fun renderSidebar(state: com.example.longpresstool.model.LongPressUiState) {
        val phase = when {
            state.isPressing -> LongPressPhase.PRESSING
            state.isSelectingPosition -> LongPressPhase.SELECTING_POSITION
            state.hasSelectedPosition -> LongPressPhase.POSITION_SELECTED
            else -> LongPressPhase.NO_POSITION
        }

        // ---- 状态文字 ----
        statusTextView?.text = when (phase) {
            LongPressPhase.PRESSING -> getString(R.string.overlay_status_pressing, state.targetX, state.targetY)
            LongPressPhase.NO_POSITION -> getString(R.string.overlay_status_not_selected)
            LongPressPhase.SELECTING_POSITION -> getString(R.string.overlay_status_selecting)
            LongPressPhase.POSITION_SELECTED -> getString(R.string.overlay_status_selected, state.targetX, state.targetY)
        }

        // ---- 状态指示灯颜色 ----
        val dotColorRes = when (phase) {
            LongPressPhase.PRESSING -> R.color.status_pressing
            LongPressPhase.SELECTING_POSITION -> R.color.status_selecting
            LongPressPhase.NO_POSITION -> R.color.status_idle
            LongPressPhase.POSITION_SELECTED -> R.color.status_ready
        }
        statusDotView?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        // ---- 提示文字 ----
        // 无障碍服务被关掉时，优先提示这件事：此时其他提示都没意义（启动不了）。
        hintTextView?.text = when {
            !state.isAccessibilityEnabled -> getString(R.string.overlay_hint_accessibility_off)
            phase == LongPressPhase.PRESSING -> getString(R.string.overlay_hint_pressing)
            phase == LongPressPhase.SELECTING_POSITION -> getString(R.string.overlay_hint_selecting)
            phase == LongPressPhase.POSITION_SELECTED -> getString(R.string.overlay_hint_ready)
            else -> getString(R.string.overlay_hint_choose_position)
        }

        // 只在长按时显示"60 秒分段"的限制说明，平时不打扰用户。
        limitTextView?.visibility = if (phase == LongPressPhase.PRESSING) View.VISIBLE else View.GONE

        // ---- 按钮 ----
        selectPositionButton?.text =
            if (phase == LongPressPhase.SELECTING_POSITION) getString(R.string.action_finish_selecting)
            else getString(R.string.action_select_position)

        // 长按过程中不允许改位置，避免指示器和实际按下的点不一致。
        selectPositionButton?.isEnabled = phase != LongPressPhase.PRESSING
        // 启动需要三个条件同时成立，判断逻辑集中在 LongPressUiState.canStartLongPress，
        // 避免这里和首页各写一套导致不一致。
        startButton?.isEnabled = state.canStartLongPress
        stopButton?.isEnabled = phase == LongPressPhase.PRESSING
    }

    // ==================== 关闭与生命周期 ====================

    /**
     * 关闭所有悬浮窗并结束 Service。
     * 顺序很重要：先移除视图再 stopSelf，否则 onDestroy 里再 removeView 容易抛异常。
     */
    private fun closeEverything() {
        hideIndicator()
        removeSidebar()
        stopSelf()
    }

    private fun removeSidebar() {
        val view = sidebarView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 视图可能已被系统移除，忽略。
        }
        sidebarView = null
        sidebarParams = null

        // 清空引用，避免持有已销毁的 View 造成内存泄漏。
        statusDotView = null
        statusTextView = null
        hintTextView = null
        limitTextView = null
        selectPositionButton = null
        startButton = null
        stopButton = null
        closeButton = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        hideIndicator()
        removeSidebar()

        // 复位"运行中"相关的状态，但**保留已选位置**：
        // 需求第九节明确要求停止/关闭后保留用户之前选的位置。
        LongPressStateHolder.resetRunningState()

        isRunning = false
        super.onDestroy()
    }

    /**
     * 屏幕旋转 / 尺寸变化时系统回调这里。
     *
     * 这里不重建悬浮窗，只把两个窗口重新夹回新的可用区域内
     * （横屏变窄后，原来贴右边的窗口可能跑到屏幕外），并把新位置按 dp 存下来。
     *
     * AndroidManifest 里给这个 Service 声明了 configChanges，
     * 让系统在旋转时不要重建 Service，避免悬浮窗闪一下。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        sidebarParams?.let {
            clampSidebarIntoScreen(it)
            sidebarView?.let { view -> safeUpdateLayout(view, it) }
            AppPreferences.saveSidebarPosition(this, SidebarPosition(pxToDp(it.x), pxToDp(it.y)))
        }

        indicatorParams?.let {
            clampIndicatorIntoScreen(it)
            indicatorRootView?.let { view -> safeUpdateLayout(view, it) }
            reportIndicatorCenter()
        }
    }

    // ==================== 坐标与尺寸工具 ====================

    /**
     * 当前可以安全放置悬浮窗的屏幕区域（屏幕绝对坐标）。
     *
     * 需求第六节要求处理状态栏、导航栏、刘海屏。做法是：
     * 1. 起点用**真实屏幕尺寸**（包含状态栏、刘海、导航栏），
     *    而不是 resources.displayMetrics —— 后者在部分版本/机型上不包含系统栏，
     *    会算出一个比真实屏幕小的坐标系，导致坐标偏上或偏左；
     * 2. 再用当前窗口的 insets 把状态栏、刘海、导航栏所在的安全区减掉，
     *    保证用户不会把指示器拖到挖孔下面或导航栏里。
     *
     * 注意 API 30 是分界线：
     * - 30+：WindowMetrics + WindowInsets.getInsets()，官方推荐方式；
     * - 24~29：只能用已废弃的 Display.getRealSize() 和 insets 的 left/top/right/bottom 字段。
     *   这里做版本判断，两个分支都保留，就是需求里说的"对关键 API 进行版本判断"。
     */
    private fun usableBounds(): Rect {
        val fullScreen = Rect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            fullScreen.set(0, 0, bounds.width(), bounds.height())
        } else {
            // API 24~29：只能用已废弃的 getRealSize()。它给出的是真实屏幕尺寸，
            // 包含状态栏和导航栏区域，正是我们要的坐标系。
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
            fullScreen.set(0, 0, size.x, size.y)
        }

        // 取某个悬浮窗当前生效的 insets，用来扣掉系统栏和刘海区域。
        val insets = currentWindowInsets() ?: return fullScreen

        return Rect(
            fullScreen.left + insets.left,
            fullScreen.top + insets.top,
            fullScreen.right - insets.right,
            fullScreen.bottom - insets.bottom
        )
    }

    private fun currentWindowInsets(): Rect? {
        val view = sidebarView ?: indicatorRootView ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = view.rootWindowInsets ?: return null
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        } else {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return null
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            Rect(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        }
    }

    /**
     * 监听 insets 变化：状态栏/导航栏隐藏或显示（例如进入全屏的其他 App 后返回）时，
     * 重新把悬浮窗夹回可见区域，避免它被系统栏压住。
     */
    private fun observeInsetsChanges(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view, OnApplyWindowInsetsListener { _, insets ->
            sidebarParams?.let { clampSidebarIntoScreen(it) }
            indicatorParams?.let { clampIndicatorIntoScreen(it) }
            insets
        })
    }

    private fun safeUpdateLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 窗口可能已经被系统移除（例如权限被撤销），忽略即可，绝不崩溃。
        }
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun pxToDp(px: Int): Int =
        (px / resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "long_press_tool_overlay"
        private const val NOTIFICATION_ID = 1001

        /** 圆形指示器的直径，必须与 overlay_position_indicator.xml 里的 60dp 一致。 */
        private const val INDICATOR_SIZE_DP = 60

        /** 指示器窗口总高度 = 圆形 60dp + 下方坐标文字 36dp。 */
        private const val INDICATOR_WINDOW_HEIGHT_DP = 96

        /** 检查无障碍服务是否仍开启的间隔（毫秒）。 */
        private const val ACCESSIBILITY_POLL_INTERVAL_MS = 1_000L

        /** 【仅 debug】用 adb 触发一次长按的坐标参数。 */
        const val EXTRA_DEBUG_X = "debug_x"
        const val EXTRA_DEBUG_Y = "debug_y"

        /**
         * Service 是否正在运行。
         * 用 @Volatile 是因为可能被不同线程读写；存静态布尔值而不是 Service 实例，避免内存泄漏。
         */
        @Volatile
        private var isRunning: Boolean = false

        fun isRunning(): Boolean = isRunning

        /**
         * 启动悬浮界面。Activity 只需调用这个方法，不用关心 Intent 细节。
         * 已经运行时直接返回，防止重复添加窗口（重复添加会抛 BadTokenException）。
         */
        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, FloatingWindowService::class.java)
            // minSdk = 24；startForegroundService 从 API 26 才有，
            // 24/25 上后台 Service 限制较宽松，用 startService 即可。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 请求关闭悬浮界面。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
