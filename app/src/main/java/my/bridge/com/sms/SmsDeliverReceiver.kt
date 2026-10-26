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
import android.telephony.SmsMessage
import timber.log.Timber

/**
 * 默认短信应用收信接收器（SMS_DELIVER）。
 *
 * 仅当 Bridge 被设为「默认短信应用」时系统才会投递 SMS_DELIVER（仅投给默认应用）。
 * 职责链：**写库（消息不丢）→ 本地通知（弥补原短信 App 通知缺失）**。
 * 切回原短信应用后，其从共享短信库读出 Bridge 期间写入的消息，正常展示。
 *
 * 刻意只监听 SMS_DELIVER 而不监听 SMS_RECEIVED：
 * SMS_RECEIVED 是非默认应用也能收到的广播，若同样写库会造成
 * 「默认应用已写一条 + 我们再写一条」的重复入库。
 *
 * Manifest 声明时挂 BROADCAST_SMS 系统权限（Android 官方默认短信应用组件规范）。
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "bridge_sms_inbox"
        private const val CHANNEL_NAME = "短信接收"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages: Array<SmsMessage> = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Timber.e(e, "SMS_DELIVER 解析失败")
            return
        }
        if (messages.isEmpty()) return

        // 同一条长短信可能拆成多段（同一发件人同批次），拼接为一条入库
        val address = messages[0].originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val dateMs = messages[0].timestampMillis.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        val saved = insertToInbox(context, address, body, dateMs)
        notifyIncoming(context, address, body)
        Timber.i(
            "SMS_DELIVER：已入库(%b)「%s：%s」",
            saved, address, body.take(30),
        )
    }

    /** 写入系统短信库（收件箱、未读）。 */
    private fun insertToInbox(
        context: Context,
        address: String,
        body: String,
        dateMs: Long,
    ): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, dateMs)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.SEEN, 0)
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null
    } catch (e: Exception) {
        Timber.e(e, "短信入库失败（消息可能丢失，请检查默认短信应用状态）")
        false
    }

    /** 发本地通知（IMPORTANCE_HIGH 横幅 + 声音；通知总开关关闭时静默跳过）。 */
    private fun notifyIncoming(context: Context, address: String, body: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID, CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_HIGH,
                        ),
                    )
                }
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                address.hashCode(),
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context).apply {
                setContentTitle(if (address.isBlank()) "新短信" else address)
                setContentText(body)
                setSmallIcon(context.applicationInfo.icon)
                setContentIntent(contentIntent)
                setWhen(System.currentTimeMillis())
                setAutoCancel(true)
                setStyle(Notification.BigTextStyle().bigText(body))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setChannelId(CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    setPriority(Notification.PRIORITY_HIGH)
                    @Suppress("DEPRECATION")
                    setDefaults(
                        Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE,
                    )
                }
            }
            // 每条新短信独立通知（不覆盖），id 取毫秒戳防碰撞
            nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), builder.build())
        } catch (e: Exception) {
            Timber.w(e, "短信本地通知发送失败")
        }
    }
}
