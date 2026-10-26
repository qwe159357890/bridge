package my.bridge.com.data

import android.content.Context
import android.provider.Telephony
import timber.log.Timber

/**
 * 短信仓库（Bridge 内置短信页「会话」Tab 的数据源）。
 *
 * 与 [SmsReader]（get_sms 命令数据源，扁平列表）互补：
 * - 会话视图：全表按 thread_id 分组取最新一条 + 每会话未读数；
 * - 会话详情：某 thread 的全部消息（时间正序）；
 * - 标记已读：ContentResolver.update 置 read=1——**必须已是默认短信应用**
 *   （见 [SmsDefaultHelper] 注释），由调用方先行校验。
 *
 * ROM 兼容：沿袭 SmsReader 的教训（ColorOS 对未知列投影抛 IllegalArgumentException），
 * thread_id 为短信表标准列，仍带降级：分组查询失败时回退按 address 分组。
 */
object SmsRepository {

    /** 一个会话（thread）。 */
    data class Conversation(
        val threadId: String,
        val address: String,      // 对端号码（无联系人匹配时直接展示号码）
        val lastBody: String,
        val lastDateMs: Long,
        val unreadCount: Int,
        val messageCount: Int,
    )

    /** 会话内一条消息。 */
    data class ThreadMessage(
        val id: String,
        val body: String,
        val dateMs: Long,
        val type: Int,     // 1 收件 / 2 已发，其余类型按收发侧展示
        val read: Boolean,
    )

    /**
     * 会话列表（按最新消息时间倒序）。
     * @param windowLimit 参与分组的消息窗口大小（取最近 N 条分组；默认 1000）
     */
    fun listConversations(context: Context, windowLimit: Int = 1000): List<Conversation> {
        val cr = context.contentResolver
        val projection = arrayOf("_id", "address", "body", "date", "read", "type", "thread_id")
        val cursor = try {
            cr.query(
                Telephony.Sms.CONTENT_URI, projection,
                null, null, "date DESC LIMIT $windowLimit",
            )
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "短信库查询失败，会话列表降级为空")
            null
        }

        data class Acc(
            val threadId: String,
            var address: String,
            var lastBody: String,
            var lastDateMs: Long,
            var count: Int,
        )

        val byThread = LinkedHashMap<String, Acc>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val date = c.getLong(3)
                val type = c.getInt(5)
                // 只收最新一条：date DESC 下首个即最新
                val key = c.getString(6)?.takeIf { it.isNotEmpty() } ?: address
                val acc = byThread.getOrPut(key) {
                    Acc(key, address, body, date, 0)
                }
                acc.count++
            }
        }
        if (byThread.isEmpty()) return emptyList()

        // 未读数：独立小查询（read=0 通常很少），按 thread_id/address 精确计数
        val unreadByThread = HashMap<String, Int>()
        try {
            cr.query(
                Telephony.Sms.CONTENT_URI, arrayOf("thread_id"),
                "read = 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0)?.takeIf { it.isNotEmpty() } ?: continue
                    unreadByThread[t] = (unreadByThread[t] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "未读计数查询失败（未读数按 0 展示）")
        }

        return byThread.values
            .map { acc ->
                Conversation(
                    threadId = acc.threadId,
                    address = acc.address,
                    lastBody = acc.lastBody,
                    lastDateMs = acc.lastDateMs,
                    unreadCount = unreadByThread[acc.threadId] ?: 0,
                    messageCount = acc.count,
                )
            }
            .sortedByDescending { it.lastDateMs }
    }

    /** 某会话的全部消息（时间正序，供详情页展示）。 */
    fun listThreadMessages(context: Context, threadId: String, limit: Int = 500): List<ThreadMessage> {
        val cr = context.contentResolver
        val projection = arrayOf("_id", "body", "date", "type", "read")
        val cursor = try {
            cr.query(
                Telephony.Sms.CONTENT_URI, projection,
                "thread_id = ?", arrayOf(threadId),
                "date ASC LIMIT $limit",
            )
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "会话详情查询失败")
            null
        }
        val result = mutableListOf<ThreadMessage>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                result.add(
                    ThreadMessage(
                        id = c.getString(0) ?: "0",
                        body = c.getString(1) ?: "",
                        dateMs = c.getLong(2),
                        type = c.getInt(3),
                        read = c.getInt(4) > 0,
                    ),
                )
            }
        }
        return result
    }

    /**
     * 批量设置短信已读状态（双向：已读/未读；要求本应用已是默认短信应用，
     * 调用方先校验）。
     * @param read true=标记已读，false=标记未读
     * @return 受影响行数；失败抛异常
     */
    fun setReadState(
        context: Context,
        read: Boolean,
        selection: String,
        selectionArgs: Array<String>,
    ): Int {
        val values = android.content.ContentValues().apply { put("read", if (read) 1 else 0) }
        val affected = context.contentResolver.update(
            Telephony.Sms.CONTENT_URI, values, selection, selectionArgs,
        )
        Timber.i(
            "标记短信%s：affected=%d（%s）",
            if (read) "已读" else "未读", affected, selection,
        )
        return affected
    }
}
