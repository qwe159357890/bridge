package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 短信读取器（get_sms 命令的数据源，只读不写不删）。
 *
 * 借鉴 references/autoxClient autoxTcpServer/modules/sms.js 的两条避坑经验：
 * - 直接查 [Telephony.Sms] 全表 URI（content://sms）并**显式按 type 字段筛选**，
 *   而不用 Sms.Inbox 等视图——部分 ROM 的视图会漏验证码短信；
 * - 不做任何写操作（标记已读等需要「默认短信应用」身份，Android 4.4+ 实测
 *   无法绕过，参考项目已弃用，本模块同样不碰）。
 *
 * 权限：READ_SMS 运行时授权（启动批量申请）。
 */
object SmsReader {

    /** 一条短信。 */
    data class SmsEntry(
        val key: String,      // 设备端 _id；_id 异常时退化为内容摘要（服务端去重键）
        val address: String,
        val body: String,
        val type: Int,        // 1收件 2已发 3草稿 4发送中 5失败 6队列
        val dateMs: Long,
        val read: Boolean,
        val simSlot: Int,     // 收/发经手的卡槽序号（0 基）；-1 = 未知（老数据/无权限）
        val simNumber: String, // 该卡本机号码（运营商未写入时为空）
    )

    /** catalog 的 sms_type 参数 → Android type 值（all 返回 null = 不过滤）。 */
    fun typeCode(param: String): Int? = when (param) {
        "inbox" -> 1
        "sent" -> 2
        "draft" -> 3
        "outbox" -> 4
        "failed" -> 5
        else -> null // all
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 读取短信（时间倒序）。
     *
     * 卡来源：短信库的 ``sub_id`` 列（Android 5.1+ 双卡设备记录收/发经手的
     * subscription id）→ 经 [SimHelper] 映射为卡槽序号 + 本机号码。
     * ROM 兼容：个别 ROM（实测一加 ColorOS）对未知列投影直接抛
     * IllegalArgumentException 而非返回 -1 索引——先带 sub_id 查询，失败后
     * 去掉该列降级重查（卡来源按未知处理）。
     * 无 READ_PHONE_STATE 时卡来源同样按未知（-1/""）。
     *
     * @param typeParam catalog 的 sms_type：all/inbox/sent/draft/outbox/failed
     * @param limit 最多返回条数（5–1000）
     */
    fun read(context: Context, typeParam: String, limit: Int): List<SmsEntry> {
        val cr = context.contentResolver
        val typeFilter = typeCode(typeParam)
        val selection = if (typeFilter != null) "type = ?" else null
        val selectionArgs = if (typeFilter != null) arrayOf(typeFilter.toString()) else null
        val order = "date DESC"

        // sub_id → 卡信息映射（一次性构建；无权限/无卡时为空 map）
        val sims = SimHelper.listSims(context)
        val subToSim = sims.associateBy { it.subscriptionId }

        val baseProjection = arrayOf("_id", "address", "body", "date", "read", "type")
        // 带 sub_id 查询；ROM 不认该列时降级重查（见方法注释）
        val cursor = try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                baseProjection + "sub_id",
                selection, selectionArgs, order,
            )
        } catch (e: IllegalArgumentException) {
            Timber.w("短信库无 sub_id 列，降级为不含卡来源查询：%s", e.message)
            try {
                cr.query(Telephony.Sms.CONTENT_URI, baseProjection, selection, selectionArgs, order)
            } catch (e2: Exception) {
                Timber.e(e2, "短信查询失败（降级路径）")
                null
            }
        }

        val result = mutableListOf<SmsEntry>()
        cursor?.use { c ->
            val subIdx = c.getColumnIndex("sub_id") // 降级路径下返回 -1
            while (c.moveToNext() && result.size < limit) {
                val id = c.getString(0) ?: "0"
                val address = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val date = c.getLong(3)
                val read = c.getInt(4) > 0
                val type = c.getInt(5)
                // sub_id 列存在且能映射到激活卡 → 卡槽/号码；否则单卡兜底/未知
                val sim = if (subIdx >= 0 && !c.isNull(subIdx)) {
                    subToSim[c.getInt(subIdx)]
                } else null
                    // 设备只有一张激活卡时短信必然经它（无权限/读不到卡时不猜测）
                    ?: if (sims.size == 1) sims[0] else null
                result.add(
                    SmsEntry(
                        key = if (id != "0") id else stableKey(date, address, body),
                        address = address,
                        body = body,
                        type = type,
                        dateMs = date,
                        read = read,
                        simSlot = sim?.slotIndex ?: -1,
                        simNumber = sim?.number ?: "",
                    )
                )
            }
        }
        return result
    }

    /** _id=0（部分 ROM 异常情况）时的稳定去重键：内容摘要前 16 位。 */
    private fun stableKey(dateMs: Long, address: String, body: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest("$dateMs|$address|$body".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
