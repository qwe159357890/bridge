package my.bridge.com

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import my.bridge.com.core.common.DiagLog
import my.bridge.com.keepalive.KeepAliveService
import my.bridge.com.media.MediaCaptureService
import my.bridge.com.relay.AudioRelayService
import my.bridge.com.ui.AppExitPrefs
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * 结束整个 APP 进程（v0.2.27）——两个入口：
 *
 * 1. 设置 → 通用 → **切到后台立即结束**（[AppExitPrefs.killOnBackground]，默认关）：
 *    一旦所有 Activity 都 stop（切后台 / 熄屏 / 锁屏），立即走 [kill]；
 * 2. 设置 → 关于 → **退出** 按钮：用户主动点，立即走 [kill]，回到桌面。
 *
 * ⚠️ 为什么不能直接 `killProcess`：
 * - `KeepAliveService` / `AudioRelayService` / `MediaCaptureService` 都是 START_STICKY 服务，
 *   进程被杀后系统会按 sticky 语义**重新拉起进程**，看起来就像「没退出」；
 * - **有可见 Activity 时杀进程，AMS 会为恢复可见任务而立刻重启进程**（经典自杀又复活现象）。
 *
 * 因此顺序固定为：**显式 stopService → finishAffinity（回桌面）→ 延迟 300ms → killProcess + exitProcess**。
 */
object AppExit {

    private const val TAG = "退出"

    /** 关闭页面后延迟杀进程：给 AMS 一点时间销毁任务，避免「自杀被复活」。 */
    private const val KILL_DELAY_MS = 300L

    /** 处于 started 状态的 Activity 数量：归零即「已切到后台」。 */
    @Volatile
    private var foregroundCount = 0

    /** 杀进程只做一次（后台回调与用户点击可能同时到达）。 */
    @Volatile
    private var killing = false

    /** 当前 Activity（弱引用，避免长持有导致泄漏）。 */
    private var current: WeakReference<Activity>? = null

    /**
     * 在 Application 里安装前台/后台监听。
     *
     * 用 [Application.ActivityLifecycleCallbacks] 计数而非 `ProcessLifecycleOwner`：
     * 现有依赖不含 `lifecycle-process`，且后者的 ON_STOP 有 700ms 延迟——这里要的是「立刻」。
     */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                current = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                current = WeakReference(activity)
                foregroundCount++
            }

            override fun onActivityResumed(activity: Activity) {
                current = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                foregroundCount = (foregroundCount - 1).coerceAtLeast(0)
                if (foregroundCount == 0) onBackground(application)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (current?.get() === activity) current = null
            }
        })
    }

    private fun onBackground(context: Context) {
        if (!AppExitPrefs.killOnBackground(context)) return
        DiagLog.i(TAG, "APP 已切到后台（开关已开）→ 立即结束进程")
        kill(context, "切到后台（已开启「切到后台立即结束」）")
    }

    /**
     * 结束整个进程：停掉自己拉起的服务 → 释放进程级资源 → 关页面回桌面 → 杀进程。
     *
     * @param reason 仅用于日志，便于在 DiagLog 里区分是「切后台自动结束」还是「用户点退出」。
     */
    fun kill(context: Context, reason: String) {
        if (killing) return
        killing = true
        DiagLog.i(TAG, "结束整个进程：$reason")
        Timber.i("结束整个进程：$reason")

        val app = context.applicationContext

        // 1) 显式停掉自己拉起的（sticky）服务——不做这步，杀进程后会被系统重新拉起
        runCatching { app.stopService(Intent(app, KeepAliveService::class.java)) }
            .onFailure { Timber.w(it, "停止保活服务失败") }
        runCatching { app.stopService(Intent(app, AudioRelayService::class.java)) }
            .onFailure { Timber.w(it, "停止音频中继服务失败") }
        runCatching { app.stopService(Intent(app, MediaCaptureService::class.java)) }
            .onFailure { Timber.w(it, "停止媒体采集服务失败") }

        // 2) 释放微信进程级资源（WS 连接 / 60s 心跳 / 重连 / HTTP 客户端 / 内存数据）
        runCatching { my.bridge.com.wechat.WechatRuntime.stopAll(reason) }
            .onFailure { Timber.w(it, "释放微信资源失败") }

        // 3) 关掉所有页面回到桌面（finishAffinity 只结束本任务，不影响其它 APP）
        runCatching { current?.get()?.finishAffinity() }
            .onFailure { Timber.w(it, "关闭页面失败") }

        // 4) 延迟一点再杀：等 AMS 销毁任务，避免「可见 Activity 未销毁」导致进程被重启
        Handler(Looper.getMainLooper()).postDelayed({
            DiagLog.i(TAG, "killProcess pid=${android.os.Process.myPid()}")
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }, KILL_DELAY_MS)
    }
}
