package my.bridge.com.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新完成广播接收器（借鉴 Aurelay）。
 * 无条件拉起常驻保活服务；Android 15+ 受限时 try-catch 兜底。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BootReceiver", "收到广播：$action")
        val app = context.applicationContext

        if (!KeepAlivePrefs.getBoolean(app, KeepAlivePrefs.KEY_KEEP_ALIVE_BOOT)) {
            Log.i("BootReceiver", "开机自启开关已关闭")
            return
        }

        try {
            KeepAliveService.start(app)
        } catch (e: Exception) {
            Log.e("BootReceiver", "拉起保活服务失败（Android 15+ 开机限制属预期）", e)
        }
    }
}
