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
