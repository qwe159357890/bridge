package my.bridge.com.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import timber.log.Timber
import java.io.File

/**
 * 彩信推送接收器（WAP_PUSH_DELIVER，默认短信应用组件之二）。
 *
 * 首期策略（调研时已与用户确认）：彩信 PDU 解析复杂（multipart + part 表），
 * 本期**不写彩信库、不解析内容**，做两件保底事：
 * 1. 原始 PDU 字节存到应用私有目录 files/mms/（数据不丢，后续版本可解析回填）；
 * 2. 发一条本地通知提醒「收到彩信」。
 *
 * 局限：Bridge 作为默认应用期间收到的彩信正文不在短信库中，切回原短信应用后
 * 原应用看不到该条彩信记录（正文在上述文件里）。完整彩信支持留待后续版本。
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "bridge_mms_inbox"
        private const val CHANNEL_NAME = "彩信接收"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("pdu")
        if (pdu == null || pdu.isEmpty()) {
            Timber.w("WAP_PUSH_DELIVER：PDU 为空，忽略")
            return
        }

        val saved = runCatching {
            val dir = File(context.filesDir, "mms").apply { mkdirs() }
            val file = File(dir, "mms_${System.currentTimeMillis()}.pdu")
            file.writeBytes(pdu)
            file
        }.getOrNull()

        notifyIncoming(context, saved != null)
        Timber.i(
            "WAP_PUSH_DELIVER：彩信 PDU 已保存(%b)，大小 %d 字节（暂不解析展示）",
            saved != null, pdu.size,
        )
    }

    private fun notifyIncoming(context: Context, savedOk: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_ID) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context).apply {
                setContentTitle("收到彩信")
                setContentText(
                    if (savedOk) "彩信内容暂不支持查看，原始数据已保留在设备上"
                    else "彩信数据保存失败",
                )
                setSmallIcon(context.applicationInfo.icon)
                setContentIntent(contentIntent)
                setWhen(System.currentTimeMillis())
                setAutoCancel(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setChannelId(CHANNEL_ID)
                }
            }
            nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), builder.build())
        } catch (e: Exception) {
            Timber.w(e, "彩信本地通知发送失败")
        }
    }
}
