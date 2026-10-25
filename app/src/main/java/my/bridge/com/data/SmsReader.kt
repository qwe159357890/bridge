package my.bridge.com.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

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
     * @param typeParam catalog 的 sms_type：all/inbox/sent/draft/outbox/failed
     * @param limit 最多返回条数（5–1000）
     */
    fun read(context: Context, typeParam: String, limit: Int): List<SmsEntry> {
        val cr = context.contentResolver
        val typeFilter = typeCode(typeParam)
        val selection = if (typeFilter != null) "type = ?" else null
        val selectionArgs = if (typeFilter != null) arrayOf(typeFilter.toString()) else null

        val result = mutableListOf<SmsEntry>()
        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "read", "type"),
            selection, selectionArgs,
            "date DESC",
        )?.use { c ->
            while (c.moveToNext() && result.size < limit) {
                val id = c.getString(0) ?: "0"
                val address = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val date = c.getLong(3)
                val read = c.getInt(4) > 0
                val type = c.getInt(5)
                result.add(
                    SmsEntry(
                        key = if (id != "0") id else stableKey(date, address, body),
                        address = address,
                        body = body,
                        type = type,
                        dateMs = date,
                        read = read,
                    )
                )
            }
        }
        return result
    }

    /** _id=0（部分 ROM 异常情况）时的稳定去重键：内容摘要前 16 位。 */
    private fun stableKey(dateMs: Long, address: String, body: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest("$date|$address|$body".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
