package my.bridge.com.data

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/** 单个号码的短信发送明细（回执逐号码展示用）。 */
data class SmsSendDetail(
    val phone: String,
    val success: Boolean,
    val message: String,
)

/**
 * 短信发送器（send_sms 命令的实现核心）。
 *
 * 借鉴 SmsForwarder `PhoneUtils.sendSms`（多号码 + 长短信切分）与
 * autoxClient `sms.js`（sendTextMessage 基础用法），并补上两者都没做全的
 * **发送结果确认**：每条短信挂一个 sent PendingIntent + 动态注册的
 * BroadcastReceiver，等待系统回传 RESULT_OK / RESULT_ERROR_* 后才计入结果，
 * 避免同步调用不抛异常但实际发送失败（无 SIM/射频关闭）时回执虚报成功。
 *
 * 权限：SEND_SMS（dangerous），Manifest 已声明、App 启动时随批量权限申请；
 * 执行前由调用方（CommandModule）校验。
 */
object SmsSender {

    private const val ACTION_SENT = "my.bridge.com.sms.SMS_SENT"
    private const val SENT_TIMEOUT_MS = 15_000L

    /** PendingIntent requestCode 计数器（同一进程内不重复，避免 broadcast 串扰）。 */
    private val requestCodeSeq = AtomicInteger(1)

    /** 是否持有 SEND_SMS 运行时权限。 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 号码合法性：可选 + 开头、其余 3~20 位数字。
     * 系统侧只在无线链路失败时才报错，本地先挡掉明显不合法的输入。
     */
    fun isValidPhone(raw: String): Boolean =
        Regex("^\\+?[0-9]{3,20}$").matches(raw.trim())

    /**
     * 逐号码发送短信（串行，避免多张发送排队时序混乱）。
     *
     * @param phones  已解析的号码列表（调用方负责拆分分隔符）
     * @param content 内容（超 70 字自动 multipart 切分）
     * @param subscriptionId 指定发送卡的 subscriptionId；null = 系统默认卡
     * @return 每个号码一条明细，顺序与入参一致
     */
    suspend fun send(
        context: Context,
        phones: List<String>,
        content: String,
        subscriptionId: Int? = null,
    ): List<SmsSendDetail> {
        val smsManager: SmsManager? = try {
            if (subscriptionId == null) {
                context.getSystemService(SmsManager::class.java)
            } else {
                // 指定卡发送（API 22+ 静态方法，minSdk 24 均可用）
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        } catch (e: Exception) {
            Timber.w(e, "获取指定卡 SmsManager 失败（subId=%s）", subscriptionId)
            null
        }
        val details = mutableListOf<SmsSendDetail>()
        for (phone in phones) {
            details += sendOne(context, smsManager, phone, content)
        }
        return details
    }

    private suspend fun sendOne(
        context: Context,
        smsManager: SmsManager?,
        phone: String,
        content: String,
    ): SmsSendDetail {
        if (!isValidPhone(phone)) {
            return SmsSendDetail(phone, success = false, message = "号码格式不合法")
        }
        if (smsManager == null) {
            return SmsSendDetail(phone, success = false, message = "设备短信服务不可用")
        }
        // 指定卡由调用方解析 subId 后传入（见 send 的 subscriptionId 参数）

        val requestCode = requestCodeSeq.getAndIncrement()
        val sentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val sentIntent = Intent(ACTION_SENT).setPackage(context.packageName)
        val sentPI = PendingIntent.getBroadcast(context, requestCode, sentIntent, sentFlags)

        val deferred = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                deferred.complete(resultCode)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_SENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val parts = smsManager.divideMessage(content)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(
                    phone, null, parts,
                    ArrayList(listOf(sentPI)), null,
                )
            } else {
                smsManager.sendTextMessage(phone, null, content, sentPI, null)
            }
            val resultCode = withTimeoutOrNull(SENT_TIMEOUT_MS) { deferred.await() }
            return when (resultCode) {
                null -> SmsSendDetail(phone, success = false, message = "发送超时（系统未回执）")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                    SmsSendDetail(phone, success = false, message = "发送失败（通用错误）")
                SmsManager.RESULT_ERROR_NO_SERVICE ->
                    SmsSendDetail(phone, success = false, message = "发送失败（无移动网络服务）")
                SmsManager.RESULT_ERROR_NULL_PDU ->
                    SmsSendDetail(phone, success = false, message = "发送失败（PDU 无效）")
                SmsManager.RESULT_ERROR_RADIO_OFF ->
                    SmsSendDetail(phone, success = false, message = "发送失败（射频关闭/无 SIM）")
                else -> SmsSendDetail(phone, success = true, message = "已发送")
            }
        } catch (e: Exception) {
            Timber.e(e, "send_sms 发送异常：%s", phone)
            return SmsSendDetail(phone, success = false, message = "发送异常：${e.message}")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
