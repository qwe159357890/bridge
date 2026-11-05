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
import my.bridge.com.vnt.VntVpnService
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
 *   `VntVpnService`（v0.2.30）为 START_NOT_STICKY，但持有系统唯一 VPN 通道与 native 句柄，
 *   同样要显式 stopService 释放；
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
                if (foregroundCount == 0) {
                    onBackground(application)
                    // v0.2.43：APP 切后台 = 可能「离开了微信页」（按 Home/锁屏/切 App）。
                    // 「微信离开后立即结束」开关开启时，若正停在微信页，释放微信资源
                    // （补 onPageLeave 只覆盖「切底部 Tab」的缺口，见 WechatRuntime.onAppBackground）。
                    my.bridge.com.wechat.WechatRuntime.onAppBackground()
                }
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

        // 0) 同步取消保活兜底定时器（AlarmManager + JobScheduler）。
        //    ⚠️ 不能只依赖 stopService → onDestroy → ResourceHolder.releaseAll 这条异步链：
        //    stopService 是异步的，onDestroy 在主线程 Looper 排队，而 300ms 后 killProcess 可能
        //    抢先执行——此时兜底定时器尚未取消，进程被杀后 Alarm/Job 到点把 KeepAliveService
        //    重新拉起（startedAt 重置 = 运行时长变长，但组网不自动起），正是「退出后几秒又复活」的根因。
        //    这里显式同步 releaseAll（public、幂等）先取消兜底，再走后续 stopService/killProcess。
        runCatching { my.bridge.com.keepalive.ResourceHolder.releaseAll(app) }
            .onFailure { Timber.w(it, "取消保活兜底失败") }

        // 1) 显式停掉自己拉起的（sticky）服务——不做这步，杀进程后会被系统重新拉起。
        //    v0.2.46：保活服务改走 KeepAliveService.stop（ACTION_STOP），同步撤销前台 + sticky
        //    重建义务（裸 stopService 只异步 onDestroy，300ms 后 killProcess 可能抢先 → 退不掉）。
        runCatching { KeepAliveService.stop(app) }
            .onFailure { Timber.w(it, "停止保活服务失败") }
        runCatching { app.stopService(Intent(app, AudioRelayService::class.java)) }
            .onFailure { Timber.w(it, "停止音频中继服务失败") }
        runCatching { app.stopService(Intent(app, MediaCaptureService::class.java)) }
            .onFailure { Timber.w(it, "停止媒体采集服务失败") }
        // v0.2.30：VNT 组网服务虽是 START_NOT_STICKY，仍必须显式停——
        // 它持有系统唯一 VPN 通道与 native 句柄，不先释放会残留到进程终止才断开；
        // 「退出应用 / 切后台立即结束」是最高优先级，组网作为 APP 的一部分一并结束。
        runCatching { app.stopService(Intent(app, VntVpnService::class.java)) }
            .onFailure { Timber.w(it, "停止组网服务失败") }

        // v0.2.45：SSH 服务是 START_STICKY（SshServerService），进程被杀后系统会按 sticky
        // 语义重建它（onStartCommand(null) → SshPrefs.enabled=true 时自动重启 sshd）→ 进程复活。
        // 走 SshServerController.stop：未运行直接收状态（不新建僵尸服务），运行中发 ACTION_STOP
        // 触发 shutdown() → stopSelf()，把 START_STICKY 的重建义务一起撤销。
        runCatching { my.bridge.com.ssh.SshServerController.stop(app) }
            .onFailure { Timber.w(it, "停止 SSH 服务失败") }

        // v0.2.47：关闭无障碍服务绑定（真正退出）。无障碍是系统级绑定角色，进程被杀后系统
        // 会强制重启进程恢复它——这是「退出后几秒内复活」的最终根因（无线 adb logcat 实锤：
        // "Start proc ... for service {BridgeAccessibilityService}"）。退出前先从
        // ENABLED_ACCESSIBILITY_SERVICES 移除本组件，进程才能真正被杀掉；
        // 下次冷启动由 BridgeApplication.onCreate 调 enableInSettings 无感恢复。
        runCatching { my.bridge.com.automation.a11y.BridgeAccessibilityService.disableInSettings(app) }
            .onFailure { Timber.w(it, "关闭无障碍服务失败") }

        // 2) 释放微信进程级资源（WS 连接 / 60s 心跳 / 重连 / HTTP 客户端 / 内存数据）
        runCatching { my.bridge.com.wechat.WechatRuntime.stopAll(reason) }
            .onFailure { Timber.w(it, "释放微信资源失败") }

        // 2.5) 取消 WorkManager 周期任务（周期位置上报兜底）：
        //    它是**登记在系统里**的，进程死了照样到点触发并新建进程执行 Worker，
        //    APP 会在十几分钟后自己「复活」。取消后下次冷启动会由 ReportManager 重新排队。
        runCatching {
            androidx.work.WorkManager.getInstance(app)
                .cancelUniqueWork(my.bridge.com.report.ReportManager.WORK_NAME)
        }.onFailure { Timber.w(it, "取消 WorkManager 周期任务失败") }

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
