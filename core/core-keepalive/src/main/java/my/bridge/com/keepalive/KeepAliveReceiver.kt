package my.bridge.com.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 保活兜底广播接收器：由 AlarmManager 每 15 分钟唤醒，重新拉起保活服务。
 *
 * ## v0.2.00 崩溃修复（关键，同 KeepAliveJobService）
 *
 * onReceive 立即返回后进程会瞬间失去「处理中」保护，被厂商缓存冻结，
 * 排队的 Service.onCreate 派发不下来 → 超时崩溃。改用 goAsync() 保持广播
 * 「处理中」4 秒，撑住解冻窗口让 onCreate 正常派发。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KEEP_ALIVE = "my.bridge.com.keepalive.action.KEEP_ALIVE"
        private const val TAG = "KeepAliveReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Log.i(TAG, "收到保活兜底唤醒")
        try {
            KeepAliveService.start(app)
        } catch (e: Exception) {
            Log.e(TAG, "拉起常驻保活服务失败", e)
        }
        // goAsync + 延迟 finish：广播「处理中」期间进程不可冻结（见类注释）
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, KeepAliveJobService.ALIVE_WINDOW_MS)
    }
}
