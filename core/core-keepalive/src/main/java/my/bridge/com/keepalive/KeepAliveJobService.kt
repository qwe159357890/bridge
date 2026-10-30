package my.bridge.com.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 保活兜底周期任务：setPersisted(true) 重启后仍有效。
 *
 * ## v0.2.00 崩溃修复（关键）
 *
 * 线上实测崩溃链（adb dropbox + events log 实锤，v0.1.95~99 共 10+ 次）：
 * 1. 后台兜底任务触发 → onStartJob 调 KeepAliveService.start()（startForegroundService）
 * 2. onStartJob **立即返回 false** → 任务结束 → 进程失去「执行中」保护
 * 3. ColorOS/厂商缓存冻结瞬间生效，排队的 Service.onCreate **永远派发不下来**
 *    （实测 30 秒窗口内进程零日志；服务内部任何修复都无机会执行）
 * 4. 系统超时（实测 ~30s）抛 ForegroundServiceDidNotStartInTimeException → 崩溃
 *
 * 修复：返回 true 让任务保持「执行中」，4 秒后再 jobFinished——系统不会冻结
 * 有任务在跑的进程，Service.onCreate 在窗口内正常派发 → 挂前台成功。
 */
class KeepAliveJobService : JobService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "JobScheduler 兜底任务触发")
        try {
            KeepAliveService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "拉起保活服务失败", e)
        }
        // 保持任务「执行中」4 秒撑住解冻窗口（广播/任务组件存活期间进程不可冻结）
        handler.postDelayed({ jobFinished(params, false) }, ALIVE_WINDOW_MS)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 任务被系统取消：撤掉待发的 jobFinished（onStopJob 本身即结束语义）
        handler.removeCallbacksAndMessages(null)
        return false
    }

    companion object {
        private const val TAG = "KeepAliveJobService"

        /** 兜底组件保持「处理中」的时长：远小于广播 10s/任务超时预算，足够 onCreate 派发。 */
        const val ALIVE_WINDOW_MS = 4_000L
    }
}
