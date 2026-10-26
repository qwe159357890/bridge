package my.bridge.com.interact

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 远程通知发送器（send_notification 命令的实现载体）。
 *
 * 设计要点：
 * - Android 没有公开的「设置桌面角标」API，角标由各 ROM 的 launcher 依据
 *   应用通知自动绘制（通知驱动）：小米/华为/三星显示 [Notification.number] 数字，
 *   OPPO/vivo 显示红点。因此「发通知」即「显示角标」，「撤通知」即「归零角标」。
 * - 本应用的保活前台服务通知属常驻/低重要性通知，各 ROM 均不计入角标，
 *   不会导致桌面常年挂角标。
 * - 固定使用同一个 notificationId（[NOTIFICATION_ID]）：重复下发更新同一条通知，
 *   「清除模式」cancel 该 id 即可撤回并归零角标。
 * - 提示音/震动通过两个预建渠道（[CHANNEL_SOUND]/[CHANNEL_SILENT]）区分——
 *   NotificationChannel 的声音属性创建后不可变，只能按需选渠道。
 * - 点击通知跳转本应用（包名 launch intent）。
 */
object NotificationPoster {

    /** 远程通知的固定 id（重复下发覆盖同一条，清除模式撤回它）。 */
    const val NOTIFICATION_ID = 2001

    private const val CHANNEL_SOUND = "bridge_remote_sound"
    private const val CHANNEL_SILENT = "bridge_remote_silent"
    private const val CHANNEL_NAME = "远程下发通知"

    /** 本应用通知是否已启用（总开关；API 24+ 全版本可用）。 */
    fun areEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /**
     * 打开本应用的通知设置页引导用户开启（同 lock_screen/弹窗的引导模式）。
     * @return 是否成功拉起系统页面
     */
    fun launchNotificationSettings(context: Context): Boolean = try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Timber.w(e, "send_notification：打开通知设置页失败")
        false
    }

    /** 清除模式：撤回远程通知，桌面角标随之归零。 */
    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        Timber.i("send_notification：远程通知已清除（角标归零）")
    }

    /**
     * 发送远程通知。
     * @param title 标题
     * @param content 正文
     * @param sound 是否提示音+震动（API 26+ 走渠道；低版本走 setDefaults）
     * @param badge 角标数字（1~99，写入 [Notification.number] 供支持的 ROM 显示）
     * @return 回执描述（含角标数字）
     * @throws IllegalStateException 通知发送失败
     */
    fun post(context: Context, title: String, content: String, sound: Boolean, badge: Int): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel(nm, if (sound) CHANNEL_SOUND else CHANNEL_SILENT, sound)
        } else ""

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context).apply {
            setContentTitle(title)
            setContentText(content)
            setSmallIcon(context.applicationInfo.icon)
            setContentIntent(contentIntent)
            setWhen(System.currentTimeMillis())
            setAutoCancel(true)   // 用户点开通知即消失 → 角标归零
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(channelId)
        } else {
            @Suppress("DEPRECATION")
            builder.setPriority(
                if (sound) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT
            )
            @Suppress("DEPRECATION")
            builder.setDefaults(
                if (sound) Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE
                else Notification.DEFAULT_LIGHTS
            )
        }

        val shown = if (badge > 0) "，桌面角标显示 ${badge.coerceIn(1, 99)}" else ""
        try {
            // Notification.number 是通知驱动的角标数字来源（小米/华为/三星等 launcher 读取），
            // framework Notification.Builder 无 setNumber，只能在 build 后对字段赋值
            val notification = builder.build()
            notification.number = badge.coerceIn(0, 99)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            throw IllegalStateException("通知发送失败：${e.message}", e)
        }
        Timber.i("send_notification：通知已发送（角标=%d, sound=%b）", badge, sound)
        return "已发送通知「$title」$shown"
    }

    /** 确保远程通知渠道存在（声音属性创建后不可变，按 sound 分两个渠道）。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel(nm: NotificationManager, channelId: String, sound: Boolean): String {
        nm.getNotificationChannel(channelId)?.let { return channelId }
        val channel = NotificationChannel(
            channelId, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setShowBadge(true)   // 允许渠道产生桌面角标
            if (!sound) setSound(null, null)
        }
        nm.createNotificationChannel(channel)
        return channelId
    }
}
