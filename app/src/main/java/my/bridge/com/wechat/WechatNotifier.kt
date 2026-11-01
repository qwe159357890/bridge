package my.bridge.com.wechat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import my.bridge.com.core.common.DiagLog

/**
 * 微信新消息通知。
 *
 * ⚠️ 关键：**绝不能复用 id 2001**——`interact/NotificationPoster.NOTIFICATION_ID` 与
 * `keepalive.KeepAliveService.NOTIFICATION_ID` 都是 2001，微信消息若复用会顶掉保活
 * 常驻通知（且 autoCancel 会让用户把前台服务通知划掉）。这里从 [BASE_ID] 开始按会话分配。
 *
 * v0.2.23：**渠道一律静音**，`sound` 参数只作标记不再驱动渠道发声——
 * 提示音统一由 [WechatSound] 播放自带 wav（系统默认通知铃声在多数 ROM 上是「无」，
 * 这就是为什么真机上「收到新消息没声音」）。若渠道自己也发声就会和自播声**双响**。
 * 渠道 id 后缀 `_v2`：渠道创建后属性不可变，换 id 才能让老安装也生效。
 */
object WechatNotifier {

    private const val TAG = "微信通知"
    private const val CHANNEL_SOUND = "wechat_message_sound_v2"
    private const val CHANNEL_SILENT = "wechat_message_silent_v2"
    private const val CHANNEL_NAME = "微信消息"

    /** 通知 id 起始值（2001 已被保活/远程通知占用）。 */
    private const val BASE_ID = 2101

    /** 同一会话复用同一 id：连续消息只更新不刷屏。 */
    fun idOf(sessionWxid: String): Int = BASE_ID + (sessionWxid.hashCode() and 0x0FFF) % 500

    private fun channelId(context: Context, sound: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (sound) CHANNEL_SOUND else CHANNEL_SILENT
        if (nm.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "中心服务器微信新消息"
            setShowBadge(true)
            // 声音交给 WechatSound 统一播放（自带音源），渠道自身不发声，避免双响
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
        return id
    }

    /**
     * 发一条新消息通知。
     *
     * @param openIntent 点击通知要打开的 Intent（通常为 MainActivity + 会话 deep link）
     */
    fun post(
        context: Context,
        sessionWxid: String,
        title: String,
        content: String,
        sound: Boolean,
        unreadCount: Int,
        openIntent: Intent,
    ) {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            DiagLog.w(TAG, "系统通知未开启，跳过通知")
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(app, idOf(sessionWxid), openIntent, flags)

        val builder = NotificationCompat.Builder(app, channelId(app, sound))
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(content)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (unreadCount > 1) builder.setNumber(unreadCount.coerceIn(1, 99))

        runCatching {
            NotificationManagerCompat.from(app).notify(idOf(sessionWxid), builder.build())
        }.onFailure {
            DiagLog.e(TAG, "通知发送失败", it)
        }
    }

    /** 会话已读后撤掉该会话通知（角标随之归零）。 */
    fun cancel(context: Context, sessionWxid: String) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(idOf(sessionWxid))
        }.onFailure { DiagLog.w(TAG, "撤销通知失败：${it.message}") }
    }
}
