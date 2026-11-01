package my.bridge.com.wechat

/**
 * 微信（center-server 中心服务器）数据模型。
 *
 * 字段严格照 references/center-server：
 * - 会话：`center-server/core/database.py` get_sessions 的 SQL 字段
 *   （user_wxid/user_nickname/last_msg/last_time/update_timestamp/unread_count/is_pinned/is_muted）
 *   ——**会话没有账号维度，是全局的**，不要按当前账号过滤（早期臆造，已修正）。
 * - 消息/联系人：有 account_wxid（`web-frontend/src/types/index.ts` 的 MessageItem/ContactItem）。
 * - 实例/代理：`center-server/api/routes/agent.py` 的返回结构。
 */
object WechatMsgType {
    const val TEXT = 1
    const val IMAGE = 3
    const val VOICE = 34
    const val VIDEO = 43
    const val EMOJI = 47
    const val LOCATION = 48
    const val XML = 49
    const val VOIP = 50
    const val SYSTEM = 10000
    const val SYSTEM_NOTICE = 10002

    /** XML(49) 的子类型：微信支付卡片（参考前端 ChatPanel 第 66 行）。 */
    const val SUB_WECHAT_PAY = "wechat_pay"
}

/** 会话（全局，无账号字段）。 */
data class WechatSession(
    val userWxid: String,
    val userNickname: String,
    val lastMsg: String,
    val lastTime: String,
    val updateTimestamp: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
) {
    /** 公众号判定：参考前端按 wxid 前缀 `gh_` 与 is_official 兜底（utils/contactType.ts）。 */
    val isOfficial: Boolean get() = userWxid.startsWith("gh_")
}

/** 消息。content 为文本/占位；附件信息放 attachmentJson（原样保留，UI 按需解析）。 */
data class WechatMessage(
    val id: Long,
    val sessionId: String,
    val accountWxid: String,
    val msgId: String,
    val msgType: Int,
    val content: String,
    val attachmentJson: String,
    val createTime: String,
    val timestamp: Long,
    val isSelf: Boolean,
    val senderWxid: String,
    val senderNickname: String,
)

/** 联系人（0 个人 / 1 群聊 / 2 公众号，另由 wxid 前缀兜底）。 */
data class WechatContact(
    val wxid: String,
    val accountWxid: String,
    val nickname: String,
    val contactType: Int,
) {
    val typeLabel: String
        get() = when (contactType) {
            1 -> "群聊"
            2 -> "公众号"
            else -> "个人"
        }
}

/** 微信实例（一个 agent 上可多开多个微信）。 */
data class WechatInstance(
    val wxid: String,
    val accountNumber: Int,
    val nickname: String,
    val pid: Int,
    val port: Int,
)

/** 代理服务器（Windows 上跑微信 hook 的机器）。 */
data class WechatAgent(
    val agentId: String,
    val agentName: String,
    val displayName: String,
    val isConnected: Boolean,
    val connectedAt: String,
    val lastPing: String,
    val instances: List<WechatInstance>,
)

/** 连接状态（UI 只读取，不负责连接）。 */
enum class WechatConnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 「同一次发送的双写记录」判重窗口（秒）。
 *
 * v0.2.25 真机问题 #6：发一张图，本端会话里出现**两条**自己发的图片（缩略图尺寸不同），
 * 而真实微信（接收方）只收到一条。客户端确认只发了一次（唯一的
 * `POST /api/wechat/upload_and_send`，见 `WechatApiClient.uploadAndSend`），
 * 所以是服务端/agent 侧对同一次发送写了两条记录（两条 `msg_id`/`id` 不同，
 * 现有「按 msgId/id 去重」判成两条新消息）。参考仓库不含消息表的 INSERT 代码，无法从服务端根治，
 * 故在客户端兜底折叠。
 *
 * 窗口取 2 秒：人工连发两张不同图必然 > 2s（每次都要过选择器 + multipart 上传），
 * 不会误伤；而 hook 回写的两条记录时间戳几乎相同。
 */
private const val SENT_DUP_WINDOW_SEC = 2L

private fun isSentDuplicate(a: WechatMessage, b: WechatMessage): Boolean =
    a.sessionId == b.sessionId &&
        a.isSelf && b.isSelf &&                       // 只折叠「自己发的」
        a.msgType == b.msgType &&
        kotlin.math.abs(a.timestamp - b.timestamp) <= SENT_DUP_WINDOW_SEC

/**
 * 消息列表去重，用于聊天面板的**首次加载、增量合并、翻页插入**三处：
 *
 * 1. 按 `msgId ?: "id:$id"` 去重（原有逻辑）；
 * 2. 折叠 [isSentDuplicate] 命中的记录——保留**后到的**（id 更大，通常是 hook 回写的
 *    完整记录，缩略图/原图字段更全）。
 */
fun List<WechatMessage>.wechatDedupe(): List<WechatMessage> {
    if (size <= 1) return this
    val out = ArrayList<WechatMessage>(size)
    val seen = HashSet<String>()
    for (m in this) {
        val key = m.msgId.ifBlank { "id:" + m.id }
        if (!seen.add(key)) continue
        // 只与前 3 条比对：双写记录必然相邻（同一秒）。
        // ⚠️ out 为空时必须跳过——`(-1 downTo 0)` 会产出 -1，取 out[-1] 直接越界。
        val dupIdx = if (out.isEmpty()) null else {
            val from = (out.size - 1).coerceAtLeast(0)
            val to = (out.size - 3).coerceAtLeast(0)
            (from downTo to).firstOrNull { isSentDuplicate(out[it], m) }
        }
        if (dupIdx != null) {
            // 保留后到的那条（id 更大）
            if (m.id > out[dupIdx].id) out[dupIdx] = m
            continue
        }
        out.add(m)
    }
    return out
}
