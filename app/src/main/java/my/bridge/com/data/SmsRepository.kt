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

        // 未读数（v0.1.57）：扫描最近窗口（date DESC LIMIT）逐条叠加内部已读
        // 映射计数——系统 read=0 且映射非「强制已读」、或 read=1 但映射「强制
        // 未读」都算未读。ColorOS 拒绝第三方写系统 read 列（见 setReadState），
        // 系统计数已不可信。
        val unreadByThread = HashMap<String, Int>()
        try {
            SmsReadStore.warmUp(context)
            cr.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "read"),
                null, null, "date DESC LIMIT 2000",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val t = c.getString(1)?.takeIf { it.isNotEmpty() } ?: continue
                    val effectiveRead = SmsReadStore.isRead(c.getInt(2) > 0, id)
                    if (!effectiveRead) unreadByThread[t] = (unreadByThread[t] ?: 0) + 1
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

    /** 某会话的全部消息（时间正序，供详情页展示；已读状态叠加内部映射）。 */
    fun listThreadMessages(context: Context, threadId: String, limit: Int = 500): List<ThreadMessage> {
        val cr = context.contentResolver
        SmsReadStore.warmUp(context)
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
                val id = c.getString(0) ?: "0"
                result.add(
                    ThreadMessage(
                        id = id,
                        body = c.getString(1) ?: "",
                        dateMs = c.getLong(2),
                        type = c.getInt(3),
                        read = SmsReadStore.isRead(c.getInt(4) > 0, id),
                    ),
                )
            }
        }
        return result
    }

    /**
     * 批量设置短信已读状态（双向：已读/未读）。
     * @param read true=标记已读，false=标记未读
     * @return 生效条数（系统库写入成功数与内部映射记录数取较大者）
     *
     * v0.1.57 拍板：ColorOS 等定制 SmsProvider 对第三方 update 系统库**整体
     * 拒绝**（v0.1.56 三级回退实测批量/单行/Inbox 全部 0 行）——已读状态改由
     * [SmsReadStore] 内部映射承载（展示层叠加，映射优先），系统库写入降级为
     * 「尽力而为」（默认身份 + 宽容 ROM 下仍会尝试，原生短信应用可受益）。
     * 因此**不再要求默认短信应用身份**，映射记录先于系统写入。
     */
    fun setReadState(
        context: Context,
        read: Boolean,
        selection: String,
        selectionArgs: Array<String>,
    ): Int {
        val values = android.content.ContentValues().apply { put("read", if (read) 1 else 0) }
        val cr = context.contentResolver

        // ① 先按 selection 查出目标 _id 并记录内部映射（保证无论系统库是否
        //    接受写入，Bridge 界面内的已读状态一定生效）
        val ids = ArrayList<String>()
        try {
            cr.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id"), selection, selectionArgs, null,
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0).toString())
            }
        } catch (e: Exception) {
            Timber.w(e, "标记短信查目标 _id 失败（%s）", selection)
        }
        val mapped = SmsReadStore.mark(context, ids, read)

        // ② 系统库写入（尽力而为：批量全表 → 逐条单行 URI → Inbox 视图）
        var affected = try {
            cr.update(Telephony.Sms.CONTENT_URI, values, selection, selectionArgs)
        } catch (e: Exception) {
            Timber.w(e, "批量标记短信已读失败（回退逐条更新）")
            0
        }
        if (affected <= 0) {
            var count = 0
            try {
                for (pass in 0 until 2) {
                    if (count > 0) break
                    val base = if (pass == 0) Telephony.Sms.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
                    for (idStr in ids) {
                        val id = idStr.toLongOrNull() ?: continue
                        count += try {
                            cr.update(android.content.ContentUris.withAppendedId(base, id), values, null, null)
                        } catch (e: Exception) {
                            0
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "逐条标记短信已读失败")
            }
            affected = count
        }
        Timber.i(
            "标记短信%s：映射=%d 系统库=%d（%s）",
            if (read) "已读" else "未读", mapped, affected, selection,
        )
        return maxOf(mapped, affected)
    }
}
