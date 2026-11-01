package my.bridge.com.wechat

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
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
 * 常驻通知（且 autoCancel 会让用户把前台服务通知划掉）。
 *
 * v0.2.23：**渠道一律静音**，`sound` 参数只作标记不再驱动渠道发声——
 * 提示音统一由 [WechatSound] 播放自带 wav（系统默认通知铃声在多数 ROM 上是「无」，
 * 这就是为什么真机上「收到新消息没声音」）。若渠道自己也发声就会和自播声**双响**。
 * 渠道 id 后缀 `_v2`：渠道创建后属性不可变，换 id 才能让老安装也生效。
 *
 * v0.2.26：**与保活常驻通知彻底分开**（用户反馈「微信消息通知和『Bridge 后台服务』在一起」）：
 * - 通知 id 从 2101 换成 **2301**（2101 与 `media/MediaCaptureService.NOTIFICATION_ID` 撞号）；
 * - 渠道升级 `_v3`：归入**独立渠道组**「微信消息」，且「有提示音」时重要性提到 IMPORTANCE_HIGH
 *   （消息类通知应醒目；渠道自身仍静音，声音由 [WechatSound] 自播，避免双响）；
 * - 显式 [GROUP_KEY]：与「未分组」的保活常驻通知**永不互相折叠**（分组 key 不同即不会合成一组）；
 * - 对侧：`KeepAliveService` 的常驻渠道降为 IMPORTANCE_LOW（见台账 §1）。
 */
object WechatNotifier {

    private const val TAG = "微信通知"
    private const val CHANNEL_SOUND = "wechat_message_sound_v3"
    private const val CHANNEL_SILENT = "wechat_message_silent_v3"
    private const val CHANNEL_NAME = "微信消息"
    /** 渠道组：与「常驻保活」等渠道分开，设置页与部分 ROM 按组分开展示。 */
    private const val CHANNEL_GROUP_ID = "wechat_message_channel_group"
    private const val CHANNEL_GROUP_NAME = "微信消息"
    /** 通知分组 key：只有本条（及其后续顶替）属于该组，不会与未分组的保活通知合并。 */
    private const val GROUP_KEY = "wechat_messages"

    /** 通知 id：2301（2001=保活/远程，2101=媒体采集，2201=更新，2202=管理端，均避开）。 */
    private const val BASE_ID = 2301

    /**
     * v0.2.25：**全局只用这一条通知 id**——后到的消息直接顶替前一条（同 id `notify()` = 更新）。
     *
     * 改前是按会话分散 id（`BASE_ID + hash % 500`），10 个会话来消息就刷 10 条通知
     * （用户反馈 #4：「每条消息都单独一条通知」）。现在改成单条：`setNumber` 显示累计未读，
     * 点进去打开**最新**那条会话。
     */
    const val NOTIFICATION_ID = BASE_ID

    private fun channelId(context: Context, sound: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (sound) CHANNEL_SOUND else CHANNEL_SILENT
        if (nm.getNotificationChannel(id) != null) return id
        // 渠道组：把「微信消息」与「常驻保活」等渠道分开
        runCatching {
            nm.createNotificationChannelGroup(
                NotificationChannelGroup(CHANNEL_GROUP_ID, CHANNEL_GROUP_NAME)
            )
        }.onFailure { DiagLog.w(TAG, "创建渠道组失败：${it.message}") }
        val channel = NotificationChannel(
            id,
            CHANNEL_NAME,
            // 开了提示音 ⇒ 归入高重要性（醒目、置顶、可悬浮）；关了提示音 ⇒ 普通重要性。
            // 注意：渠道自身静音（setSound(null)），声音始终由 WechatSound 自播，避免双响。
            if (sound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "中心服务器微信新消息"
            group = CHANNEL_GROUP_ID
            setShowBadge(true)
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
        val pi = PendingIntent.getActivity(app, NOTIFICATION_ID, openIntent, flags)

        val builder = NotificationCompat.Builder(app, channelId(app, sound))
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(content)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)   // 同 id 连续更新时不再重复提醒（避免连环响动）
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // v0.2.26：显式分组 ⇒ 与「未分组」的保活常驻通知永不折叠成一组。
            // 本组始终只有这一条消息通知（后到的顶替前一条），无 summary，系统按单条展示。
            .setGroup(GROUP_KEY)
            .setGroupSummary(false)
        if (unreadCount > 1) builder.setNumber(unreadCount.coerceIn(1, 99))

        runCatching {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        }.onFailure {
            DiagLog.e(TAG, "通知发送失败", it)
        }
    }

    /** 撤掉那唯一一条消息通知（未读全部清零时调用）。 */
    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        }.onFailure { DiagLog.w(TAG, "撤销通知失败：${it.message}") }
    }
}
