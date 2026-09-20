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
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.longpresstool.MainActivity
import com.example.longpresstool.R
import com.example.longpresstool.model.LongPressPhase
import com.example.longpresstool.model.LongPressStateHolder
import com.example.longpresstool.model.SidebarPosition
import com.example.longpresstool.permission.AppPreferences
import com.example.longpresstool.permission.OverlayPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮侧边栏的宿主 Service。
 *
 * ===== 为什么必须是 Service，不能是 Activity？ =====
 * 需求要求侧边栏"在其他 App 运行时继续存在"。Activity 只有在它自己处于前台时才可见，
 * 用户一切走到别的应用，Activity 就进入后台、界面不可见了。这是 Android 的窗口管理机制，
 * 不是权限问题。所以跨应用的悬浮界面只能由 Service（在这里）通过 WindowManager 添加。
 *
 * ===== 为什么是"前台 Service"？ =====
 * Android 8.0 起，后台 Service 会被系统随时限制；有长期可见界面的 Service 必须
 * 用 startForeground() 提升为前台 Service 并显示一条常驻通知。
 * 这不是可选项——不做的话进程随时可能被杀，悬浮窗就消失了。
 *
 * ===== 窗口类型为什么用 TYPE_APPLICATION_OVERLAY？ =====
 * 这是 Android 8.0 起唯一对普通应用开放的悬浮窗类型（旧类型如 TYPE_PHONE 在 26+ 已失效），
 * 它需要 SYSTEM_ALERT_WINDOW 权限。
 * 另一条路线是 TYPE_ACCESSIBILITY_OVERLAY（不需要这个权限，但要开无障碍服务），
 * 会在 Phase 4 引入无障碍服务时一起讨论。
 *
 * ===== 本类当前范围（Phase 2） =====
 * 只负责：显示侧边栏、拖动、关闭、按状态更新文字。
 * "选择位置"和"启动/停止"目前只改状态或给出提示，真正的功能在 Phase 3 和 Phase 5 实现。
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager

    /** 添加到 WindowManager 的根视图。 */
    private var sidebarView: View? = null

    /** 侧边栏当前的窗口参数，拖动时直接改它再 updateViewLayout。 */
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Service 自己的协程作用域，用来订阅状态流、更新界面。onDestroy 时必须取消。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 视图引用：只在 showSidebar() 之后有效，用 ""!!"" 会崩，所以用可空 + 判空 ----
    private var statusDotView: View? = null
    private var statusTextView: TextView? = null
    private var hintTextView: TextView? = null
    private var limitTextView: TextView? = null
    private var selectPositionButton: Button? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var closeButton: Button? = null

    // ---- 拖动过程中的临时数据 ----
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)

        // 设为前台 Service 并显示常驻通知。
        // 即使因为通知被禁用等原因失败，promoteToForeground() 也不会抛异常，
        // 侧边栏会照常显示（功能优先），所以这里不需要额外分支。
        promoteToForeground()

        showSidebar()
        observeState()
    }

    /**
     * 成为前台 Service。
     *
     * Android 14 (API 34) 起，startForeground() 必须声明 foregroundServiceType
     * （在 AndroidManifest.xml 里声明为 specialUse，并申请 FOREGROUND_SERVICE_SPECIAL_USE），
     * 否则会抛异常直接崩掉。这里做版本判断。
     */
    private fun promoteToForeground(): Boolean {
        return try {
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
            true
        } catch (e: Exception) {
            // 例如用户在设置里禁用了本应用的通知、或某些 ROM 限制严格。
            // 不要因此崩溃：侧边栏照常显示，只是持久性差一些。
            isRunning = true
            false
        }
    }

    private fun createNotificationChannel() {
        // 通知渠道从 Android 8.0 (API 26) 起才需要，本应用 minSdk = 24，所以要判断。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW   // 低重要性：不响铃、不弹横幅，常驻即可
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 点通知回到首页。
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
            .setOngoing(true)          // 常驻，用户不能划掉（只能通过关闭侧边栏结束）
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== 显示侧边栏 ====================

    private fun showSidebar() {
        if (sidebarView != null) return   // 已经显示，避免重复添加窗口

        // 权限可能被用户在设置里撤销，加窗口前必须再确认一次，否则会抛异常。
        if (!OverlayPermission.isGranted(this)) {
            Toast.makeText(this, R.string.overlay_permission_hint_not_granted, Toast.LENGTH_LONG)
                .show()
            stopSelf()
            return
        }

        // 给 inflate() 传一个临时父容器（而不是 null），这样布局根节点上的
        // layout_* 参数才会被正确解析。
        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_sidebar, FrameLayout(this), false)
        val params = createLayoutParams()

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 例如权限刚被撤销、或同类型窗口被系统拒绝。不要崩溃。
            sidebarView = null
            stopSelf()
            return
        }

        sidebarView = view
        layoutParams = params

        bindViews(view)
        setupClickListeners()
        // 整个侧边栏都可以拖动（按钮除外，见 setupDragToMove 的说明）
        setupDragToMove(view)
    }

    /**
     * 悬浮窗的窗口参数。
     *
     * 两个 flag 值得解释：
     * - FLAG_NOT_FOCUSABLE：侧边栏不抢输入焦点，否则会挡住其他应用输入法、返回键也失效。
     *   但它**不影响按钮点击**，点击仍然正常。
     * - FLAG_LAYOUT_IN_SCREEN：让窗口的坐标系以整个屏幕左上角为原点，
     *   与后面 dispatchGesture() 使用的屏幕绝对坐标一致，省掉一层坐标换算。
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val saved = AppPreferences.loadSidebarPosition(this)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            resolveWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 以屏幕左上角为基准定位，x / y 就是距离左上角的偏移。
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(saved.xDp)
            y = dpToPx(saved.yDp)
        }
    }

    /**
     * 选一个当前系统版本真正支持的悬浮窗类型。
     *
     * 这是本项目里第一个必须做版本判断的关键 API：
     *
     * - Android 8.0 (API 26) 及以上：只能用 TYPE_APPLICATION_OVERLAY。
     *   老类型（TYPE_PHONE / TYPE_SYSTEM_ALERT 等）从 26 起对普通应用全面失效，
     *   继续使用会直接抛 BadTokenException 导致崩溃。
     * - Android 7.x (API 24 / 25)：系统还不认识 TYPE_APPLICATION_OVERLAY
     *   （它的常量值 2038 是 26 才存在的），所以只能用当时的 TYPE_PHONE。
     *
     * TYPE_PHONE 在这里被标记为 @Deprecated，但这不是"用了过时 API"，
     * 而是为了兼容 24/25 的**唯一**可行做法。API 26+ 永远不会走到这个分支。
     */
    @Suppress("DEPRECATION")
    private fun resolveWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun bindViews(root: View) {
        statusDotView = root.findViewById(R.id.status_dot)
        statusTextView = root.findViewById(R.id.text_status)
        hintTextView = root.findViewById(R.id.text_hint)
        limitTextView = root.findViewById(R.id.text_limit)
        selectPositionButton = root.findViewById(R.id.button_select_position)
        startButton = root.findViewById(R.id.button_start)
        stopButton = root.findViewById(R.id.button_stop)
        closeButton = root.findViewById(R.id.button_close)
    }

    private fun setupClickListeners() {
        closeButton?.setOnClickListener { closeSidebar() }

        selectPositionButton?.setOnClickListener {
            // Phase 3 会在这里创建"圆形位置指示器"窗口。
            Toast.makeText(this, "位置选择将在 Phase 3 实现", Toast.LENGTH_SHORT).show()
        }

        startButton?.setOnClickListener {
            // Phase 5 会在这里通过无障碍服务派发真实的长按手势。
            Toast.makeText(this, "长按执行将在 Phase 5 实现", Toast.LENGTH_SHORT).show()
        }

        stopButton?.setOnClickListener {
            // Phase 5 会在这里结束长按手势。
            Toast.makeText(this, "停止功能将在 Phase 5 实现", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 让整个侧边栏可以用手指拖动。
     *
     * 这里有两个关键点，初学者很容易踩坑：
     *
     * 1. 用 rawX / rawY（相对整个屏幕）而不是 x / y（相对被触摸的 View）。
     *    因为拖动过程中 View 自己在移动，用相对坐标会越拖越飘。
     *
     * 2. 必须自己区分"拖动"和"点击"：
     *    View 的 OnTouchListener 会在 onTouchEvent **之前**拿到事件，
     *    如果这里无条件返回 true，按钮就永远收不到点击了。
     *    所以只有移动距离超过系统触摸阈值时才算拖动，否则返回 false 把事件交还给子 View。
     *
     * 补充：按钮上的 OnTouchListener 在 View 自己"可点击"时不会触发，
     * 所以拖动手势天然不会和按钮点击冲突。
     */
    private fun setupDragToMove(root: View) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        root.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartParamX = params.x
                    dragStartParamY = params.y
                    isDragging = false
                    false   // 返回 false，让子 View（按钮）有机会处理这次点击
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY

                    if (!isDragging &&
                        (abs(dx) > touchSlop || abs(dy) > touchSlop)
                    ) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = dragStartParamX + dx.toInt()
                        params.y = dragStartParamY + dy.toInt()
                        // 拖动时允许超出屏幕一点，松手后再拉回来（见 ACTION_UP）
                        safeUpdateLayout(params)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        clampIntoScreen(params)
                        safeUpdateLayout(params)
                        saveSidebarPosition(params)
                        isDragging = false
                        true
                    } else {
                        // 这次算一次"点击"（手指基本没移动）。按无障碍规范，
                        // 使用 OnTouchListener 时必须补一次 performClick()，
                        // 否则使用 TalkBack 等辅助功能的用户无法触发该控件的点击。
                        root.performClick()
                        false
                    }
                }

                else -> false
            }
        }
    }

    /** 把窗口限制在屏幕可见范围内，避免用户把侧边栏拖到看不见的地方。 */
    private fun clampIntoScreen(params: WindowManager.LayoutParams) {
        val view = sidebarView ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val viewWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val viewHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight

        params.x = params.x.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
    }

    private fun safeUpdateLayout(params: WindowManager.LayoutParams) {
        val view = sidebarView ?: return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 窗口可能已经被系统移除（比如权限被撤销），忽略即可，不要崩溃。
        }
    }

    private fun saveSidebarPosition(params: WindowManager.LayoutParams) {
        AppPreferences.saveSidebarPosition(
            this,
            SidebarPosition(xDp = pxToDp(params.x), yDp = pxToDp(params.y))
        )
    }

    // ==================== 状态订阅与界面刷新 ====================

    /**
     * 订阅全局状态，任何地方改了状态，侧边栏立刻跟着变。
     * 这样"状态显示"永远和真实状态一致，不需要谁去手动同步。
     */
    private fun observeState() {
        serviceScope.launch {
            LongPressStateHolder.state.collectLatest { state ->
                // 用户可能在"选择位置"过程中把位置定下来了，这里据此切换按钮文案。
                val phase = when {
                    state.isPressing -> LongPressPhase.PRESSING
                    state.isSelectingPosition -> LongPressPhase.SELECTING_POSITION
                    state.hasSelectedPosition -> LongPressPhase.POSITION_SELECTED
                    else -> LongPressPhase.NO_POSITION
                }
                renderState(phase, state.hasSelectedPosition, state.targetX, state.targetY)
            }
        }
    }

    private fun renderState(
        phase: LongPressPhase,
        hasPosition: Boolean,
        x: Int,
        y: Int
    ) {
        val context = this

        // ---- 状态文字 ----
        val statusText = when (phase) {
            LongPressPhase.PRESSING ->
                getString(R.string.overlay_status_pressing, x, y)
            LongPressPhase.NO_POSITION ->
                getString(R.string.overlay_status_not_selected)
            else ->
                getString(R.string.overlay_status_selected, x, y)
        }
        statusTextView?.text = statusText

        // ---- 状态指示灯颜色 ----
        val dotColorRes = when (phase) {
            LongPressPhase.PRESSING -> R.color.status_pressing
            LongPressPhase.NO_POSITION -> R.color.status_idle
            else -> R.color.status_ready
        }
        statusDotView?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, dotColorRes))

        // ---- 提示文字 ----
        hintTextView?.text = when (phase) {
            LongPressPhase.PRESSING ->
                getString(R.string.overlay_hint_pressing)
            LongPressPhase.POSITION_SELECTED ->
                getString(R.string.overlay_hint_ready)
            else ->
                getString(R.string.overlay_hint_choose_position)
        }

        // 只有正在长按时才显示"60 秒分段"的限制说明，平时不打扰用户。
        limitTextView?.visibility =
            if (phase == LongPressPhase.PRESSING) View.VISIBLE else View.GONE

        // ---- 按钮文案与可用性 ----
        selectPositionButton?.text = if (phase == LongPressPhase.SELECTING_POSITION) {
            getString(R.string.action_finish_selecting)
        } else {
            getString(R.string.action_select_position)
        }

        // 长按过程中不允许再改位置，避免指示器和实际按下的点不一致。
        selectPositionButton?.isEnabled = phase != LongPressPhase.PRESSING
        startButton?.isEnabled = hasPosition && phase != LongPressPhase.PRESSING
        stopButton?.isEnabled = phase == LongPressPhase.PRESSING
    }

    // ==================== 关闭 ====================

    /**
     * 关闭侧边栏并结束 Service。
     *
     * 顺序很重要：先移除视图，再 stopSelf。
     * 反过来写的话，onDestroy 里再去 removeView 容易因为窗口已被系统处理而抛异常。
     */
    private fun closeSidebar() {
        removeSidebar()
        stopSelf()
    }

    private fun removeSidebar() {
        val view = sidebarView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 视图可能已经被系统移除，忽略。
        }
        sidebarView = null
        layoutParams = null

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
        removeSidebar()

        // 复位全局状态：侧边栏没了，就不该再显示"运行中"。
        // 注意 resetRunningState() 会保留已选位置（需求要求停止/关闭后保留位置）。
        LongPressStateHolder.resetRunningState()

        isRunning = false
        super.onDestroy()
    }

    /**
     * 屏幕旋转 / 尺寸变化时系统会回调这里。
     *
     * 这里**不重建**侧边栏，只做两件事：
     * 1. 把窗口重新夹回新的屏幕范围内（横屏变窄后，原来贴右边的侧边栏可能跑到屏幕外）；
     * 2. 位置以 dp 保存，所以竖屏时看起来仍在相近的相对位置。
     *
     * 同时在 AndroidManifest 里给 Service 声明了 configChanges，
     * 让系统在旋转时不要重建 Service，避免侧边栏闪一下。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val params = layoutParams ?: return
        clampIntoScreen(params)
        safeUpdateLayout(params)
        saveSidebarPosition(params)
    }

    // ==================== 小工具 ====================

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun pxToDp(px: Int): Int =
        (px / resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "long_press_tool_overlay"
        private const val NOTIFICATION_ID = 1001

        /**
         * Service 是否正在运行。
         *
         * 用 @Volatile 是因为它可能被不同线程读写。
         * 用 Application 级静态变量而不是静态引用 Service 实例，避免内存泄漏。
         */
        @Volatile
        private var isRunning: Boolean = false

        fun isRunning(): Boolean = isRunning

        /**
         * 启动侧边栏。Activity 只需要调用这个方法，不用关心 Intent 细节。
         *
         * 已经运行时直接返回，防止重复添加窗口（重复添加会抛 BadTokenException）。
         */
        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, FloatingWindowService::class.java)
            // minSdk = 24，而 startForegroundService 从 API 26 才有；
            // 24/25 上系统对后台 Service 的限制宽松一些，用 startService 即可。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 请求关闭侧边栏（触发 closeSidebar -> stopSelf）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
