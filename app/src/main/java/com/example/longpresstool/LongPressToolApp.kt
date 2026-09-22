package com.example.longpresstool

import android.app.Application
import android.util.Log
import com.example.longpresstool.model.LongPressStateHolder
import com.example.longpresstool.service.LongPressAccessibilityService

/**
 * 应用入口（在 AndroidManifest 的 <application android:name> 里注册）。
 *
 * Phase 7 里它承担一件事：**兜底处理未捕获异常**。
 *
 * ===== 为什么需要它 =====
 * 需求第十三节要求"出现异常时不要直接崩溃"。我们已经把能想到的每一处都加了
 * try/catch，但总有遗漏的可能（尤其是与系统交互的 API）。这里加一层兜底，
 * 目的不是"让崩溃消失"——那既不现实也不该做——而是：
 *
 * 1. 把崩溃原因完整写进日志，方便定位（而不是让用户只看到"已停止运行"）；
 * 2. 确保**正在执行的长按被结束**。手势是派发给系统执行的，
 *    如果进程直接崩掉而不主动抬手，用户会遇到"手指一直按在屏幕上"，
 *    这比崩溃本身更让人困惑。
 *
 * 注意：这里**不做**任何"自动重启应用/自动重启悬浮窗"的动作。
 * 崩溃后立刻自愈往往会把问题掩盖成反复重启，属于反模式。
 */
class LongPressToolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashSafetyNet()
    }

    private fun installCrashSafetyNet() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 先把原因记录下来（默认处理器也会打印，这里额外加一个便于检索的标签）
                Log.e(TAG, "未捕获异常，线程=${thread.name}", throwable)

                // 结束正在执行的长按，避免"手指被一直按着"
                LongPressAccessibilityService.stopHold()

                // 复位"运行中"的状态，避免下次启动时界面显示错误状态
                LongPressStateHolder.resetRunningState()
            } catch (e: Throwable) {
                // 兜底代码本身绝不能再抛异常，否则会掩盖原始崩溃
                Log.e(TAG, "崩溃兜底处理时又出错", e)
            } finally {
                // 一定把控制权交回给默认处理器，让它正常结束进程
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "LongPressToolApp"
    }
}
