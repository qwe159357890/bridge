package my.bridge.com.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 保活兜底广播接收器：由 AlarmManager 每 15 分钟唤醒，重新拉起保活服务。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KEEP_ALIVE = "my.bridge.com.keepalive.action.KEEP_ALIVE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Log.i("KeepAliveReceiver", "收到保活兜底唤醒")
        try {
            KeepAliveService.start(app)
        } catch (e: Exception) {
            Log.e("KeepAliveReceiver", "拉起常驻保活服务失败", e)
        }
    }
}
