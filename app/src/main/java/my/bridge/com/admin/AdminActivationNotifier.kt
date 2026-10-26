package my.bridge.com.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.core.app.NotificationCompat
import my.bridge.com.R
import timber.log.Timber

/**
 * 设备管理员激活引导通知（lock_screen 命令前置）。
 *
 * 为什么用通知而不是直接 startActivity：v0.1.36 及以前在 WS 命令上下文里
 * 裸 startActivity 拉起激活页，被 Android 10+ 后台启动 Activity 限制**静默拦截**
 * （catch 后误报「已弹出激活页」，实际什么都没发生）。通知的 contentIntent
 * 属于「用户点击」路径，系统放行——这是后台场景拉起页面的正规通道。
 *
 * 点击行为：跳系统「设备管理员激活页」并预选 Bridge（仅申请 force-lock 锁屏
 * 策略），用户点「激活」后重新下发 lock_screen 即可正常锁屏。
 */
object AdminActivationNotifier {

    private const val CHANNEL_ID = "system_guide"
    private const val NOTIFICATION_ID = 2202

    /** 发送激活引导通知（高优先级横幅；通知权限未授予时静默失败，回执文案有兜底指引）。 */
    fun post(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "权限引导", NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val activate = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, BridgeAdminReceiver::class.java))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Bridge 需要设备管理员权限以支持远程锁屏（仅使用「锁定屏幕」能力，" +
                        "可在设置中随时停用）",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                activate,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Bridge 需要激活锁屏权限")
                .setContentText("点按此处完成设备管理员激活（仅锁屏能力），激活后即可远程锁屏")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("点按此处完成设备管理员激活（仅锁屏能力）。激活完成后请在管理后台重新下发「锁屏」命令。"),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
            Timber.i("已发送设备管理员激活引导通知")
        } catch (e: Exception) {
            Timber.w(e, "发送激活引导通知失败（忽略，回执文案有手动指引）")
        }
    }
}
