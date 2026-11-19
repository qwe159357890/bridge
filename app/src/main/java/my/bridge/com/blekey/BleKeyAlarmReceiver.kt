package my.bridge.com.blekey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 蓝牙钥匙闹钟兜底接收器（v0.3.011）：由 AlarmManager 每 10 分钟精确唤醒。
 *
 * ## 为什么 60 秒 Handler 巡检之外还需要它
 *
 * 巡检基于 Handler.postDelayed，**CPU 深度休眠期间不触发**（实测息屏后连续
 * 2 小时零日志，15:20–17:20）。深睡期间若 ROM 顺带停掉了广播会话、或服务被杀，
 * Handler 巡检完全无法自救——这正是「息屏一段时间后 ESP 显示钥匙不在」的潜在
 * 白天场景。本接收器由 setExactAndAllowWhileIdle 触发，Doze 深睡也能唤醒
 * （系统对精确闹钟的限流约 9 分钟一次，故周期取 10 分钟）。
 *
 * ## 职责
 *
 * 1. 拉起 [BleKeyBroadcastService]：服务死了则复活（广播接收器自带约 10 秒临时
 *    白名单，允许 startForegroundService）；服务活着则进入 onStartCommand 的
 *    ACTION_ALARM 分支强制重建广播会话。
 * 2. goAsync 保持广播「处理中」4 秒，撑住解冻窗口让 Service.onCreate 正常派发
 *    （照 KeepAliveReceiver v0.2.00 崩溃修复同款，防止 onReceive 立即返回后
 *    进程被厂商缓存冻结导致前台服务超时崩溃）。
 *
 * 下一次闹钟由服务的 onStartCommand / onCreate 续排（同一个 PendingIntent 幂等，
 * 不会双排）；总开关关闭时不再续排，闹钟链自然终止。
 */
class BleKeyAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BleKeyAlarmReceiver"

        /** goAsync 保持「处理中」的时长：撑住解冻窗口，让 Service.onCreate 派发完成。 */
        private const val HOLD_MS = 4_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Log.i(TAG, "闹钟兜底唤醒（深睡/Doze 可达）")
        // 拉起服务：死了复活；活着则触发强制重建广播会话（治 ROM 静默停播）。
        try {
            BleKeyBroadcastService.evaluate(app)
        } catch (e: Exception) {
            Log.e(TAG, "拉起蓝牙钥匙服务失败", e)
        }
        // 续排下一轮闹钟（开关开着才续；同一个 PendingIntent，与服务内续排幂等）。
        if (BleKeyPrefs.enabled(app)) {
            try {
                BleKeyBroadcastService.scheduleAlarm(app)
            } catch (e: Exception) {
                Log.e(TAG, "续排蓝牙钥匙兜底闹钟失败", e)
            }
        }
        // goAsync + 延迟 finish：广播「处理中」期间进程不可冻结（见类注释）
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, HOLD_MS)
    }
}
