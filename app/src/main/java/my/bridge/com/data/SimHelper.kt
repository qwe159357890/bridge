package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * SIM 卡信息助手（send_sms / dial_phone 选卡、数据采集卡来源标记共用）。
 *
 * 数据源 [SubscriptionManager.activeSubscriptionInfoList]（需 READ_PHONE_STATE，
 * 启动批量申请）：每个激活的 subscription 对应一张可用卡，含
 * - subscriptionId：发短信指定卡用（SmsManager.getSmsManagerForSubscriptionId）
 * - simSlotIndex：0 基卡槽序号（后台显示「卡1/卡2」的依据）
 * - number：本机号码（运营商未写入时为空，显示层降级为只显示卡序号）
 *
 * 注意：catalog 的 sim 参数是静态选项（sim1/sim2/sim3），后台不知道设备实际
 * 卡数——选了不存在的卡由客户端回执失败；「auto」永远可用（走系统默认卡）。
 */
object SimHelper {

    /** 一张激活的 SIM 卡。 */
    data class SimInfo(
        val subscriptionId: Int,
        val slotIndex: Int,   // 0 基卡槽序号
        val number: String,   // 本机号码（可能为空）
    ) {
        val slotLabel: String get() = "卡${slotIndex + 1}"
        val displayLabel: String
            get() = if (number.isNotBlank()) "$slotLabel（$number）" else slotLabel
    }

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** catalog 的 sim 参数 → 0 基卡槽序号（auto → null）。 */
    fun slotParam(param: String): Int? = when (param) {
        "sim1" -> 0
        "sim2" -> 1
        "sim3" -> 2
        else -> null // auto
    }

    /** 列出全部激活的 SIM 卡（无权限 / 无卡 / 系统异常时返回空列表）。 */
    fun listSims(context: Context): List<SimInfo> {
        if (!hasPhoneStatePermission(context)) return emptyList()
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()
            val subs: List<SubscriptionInfo> =
                sm.activeSubscriptionInfoList ?: return emptyList()
            subs.map {
                SimInfo(
                    subscriptionId = it.subscriptionId,
                    slotIndex = it.simSlotIndex,
                    number = normalizeNumber(it.number),
                )
            }.sortedBy { it.slotIndex }
        } catch (e: Exception) {
            Timber.w(e, "查询 SIM 卡列表失败")
            emptyList()
        }
    }

    /** 按卡槽序号找卡；找不到返回 null。 */
    fun findBySlot(context: Context, slotIndex: Int): SimInfo? =
        listSims(context).firstOrNull { it.slotIndex == slotIndex }

    /**
     * 直接拨打电话时按卡槽序号取对应 [android.telecom.PhoneAccountHandle]
     * （ACTION_CALL 的 PHONE_ACCOUNT_HANDLE extra 用，可直接作为 Parcelable extra）。
     *
     * 索引对应关系与 autoxClient phone.js 相同：getCallCapablePhoneAccounts()
     * 的顺序 ≈ 卡槽顺序（AOSP 约定，个别 ROM 可能不一致，不一致时调用方退回默认卡）。
     */
    fun phoneAccountForSlot(context: Context, slotIndex: Int): android.telecom.PhoneAccountHandle? = try {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return null
        val accounts = tm.callCapablePhoneAccounts ?: return null
        if (slotIndex in 0 until accounts.size) accounts[slotIndex] else null
    } catch (e: Exception) {
        Timber.w(e, "查询 PhoneAccount 失败（slot=%d）", slotIndex)
        null
    }

    /** 卡状态摘要（回执/诊断用），如「2 张卡：卡1（138xxx）、卡2（未知）」。 */
    fun summarize(context: Context): String {
        val sims = listSims(context)
        if (sims.isEmpty()) return "未读到可用 SIM 卡（可能无权限或未插卡）"
        val detail = sims.joinToString("、") {
            "${it.slotLabel}（${if (it.number.isNotBlank()) it.number else "号码未知"}）"
        }
        return "${sims.size} 张卡：$detail"
    }

    /** 清洗号码显示：去空格与连字符，把「unknown」类占位符归为空。 */
    private fun normalizeNumber(raw: String?): String =
        raw?.replace(Regex("[\\s\\-]"), "")
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) || it == "null" }
            ?: ""
}
