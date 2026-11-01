package my.bridge.com.wechat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 微信页 UI 状态快照（进程级，UI 只读）。
 *
 * 与销售页不同：这些状态**不随页面销毁而清空**——数据活在 [WechatRuntime] 里，
 * 页面只是观察者（订阅/退订），所以离开微信页再回来，数据与连接都还在。
 */
data class WechatUiState(
    val configured: Boolean = false,
    val serverUrl: String = "",
    val connState: WechatConnState = WechatConnState.DISCONNECTED,
    val agents: List<WechatAgent> = emptyList(),
    val currentWxid: String = "",
    val sessions: List<WechatSession> = emptyList(),
    val loadingSessions: Boolean = false,
    val sessionMode: String = "friend",       // friend | subscription
    val contactsFilter: String = "all",
    val contacts: List<WechatContact> = emptyList(),
    val unreadTotal: Int = 0,
    val notifyEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val muteAll: Boolean = false,
    val toast: String = "",
    /** 通知点击带来的「待打开会话」（Compose 侧消费后清空）。 */
    val pendingOpenSession: String = "",
    /**
     * 新消息版本号：每收到一条（非本端回弹的）消息就 +1。
     * 聊天面板据此把服务端最新消息合并进本地列表——否则停在会话里时，
     * 新消息只更新会话列表摘要、聊天详情里却看不到（v0.2.22 修复）。
     */
    val messageVersion: Int = 0,
    /** 是否已释放（用户主动退出微信 / 开启离开即释放后离开页面）。UI 据此显示「已退出」态。 */
    val released: Boolean = false,
    /** 会话分页：是否已拉到最后一批（列表据此显示「已经加载全部」）。 */
    val sessionsEnded: Boolean = false,
    /** 会话分页：正在加载下一页（防重入）。 */
    val loadingMoreSessions: Boolean = false,
    /** 联系人分页：是否已拉到最后一批。 */
    val contactsEnded: Boolean = false,
    /** 联系人分页：正在加载下一页（防重入）。 */
    val loadingMoreContacts: Boolean = false,
    /**
     * 「退出微信后回主页」事件令牌（v0.2.23）：MainScreen 观察该计数，>0 且变化时
     * 切到底部「主页」Tab。用自增计数器而非 Boolean，保证连续两次退出也能被感知。
     */
    val exitToHomeToken: Int = 0,
    /** 「双击聊天按钮 → 跳到最新未读会话」事件令牌（同 [exitToHomeToken] 的用法）。 */
    val jumpUnreadToken: Int = 0,
) {
    /** 当前账号实例（发送上下文）。 */
    val currentInstance: WechatInstance?
        get() = agents.asSequence()
            .flatMap { it.instances.asSequence() }
            .firstOrNull { it.wxid == currentWxid }

    /** 按当前模式（好友/公众号）过滤后的会话（会话是全局的，不按账号过滤）。 */
    val visibleSessions: List<WechatSession>
        get() {
            val official = sessionMode == "subscription"
            return sessions
                .filter { it.isOfficial == official }
                .sortedWith(compareByDescending<WechatSession> { it.isPinned }
                    .thenByDescending { it.updateTimestamp })
        }

    /**
     * 未读总数口径（v0.2.24，照参考前端 `web-frontend/src/stores/session.ts`
     * 的 `totalUnreadCount`）：**只统计当前模式（好友/公众号）下的非免打扰会话**。
     *
     * v0.2.23 是对 `sessions` 全量求和，而列表只渲染当前模式的会话 ⇒
     * 未读若落在被模式过滤掉的一类，就会出现「Tab 角标 54、列表里一个角标都没有」。
     */
    fun unreadOf(list: List<WechatSession>): Int {
        val official = sessionMode == "subscription"
        return list
            .filter { it.isOfficial == official && !it.isMuted }
            .sumOf { it.unreadCount }
    }
}

object WechatState {

    private var _state by mutableStateOf(WechatUiState())
    val state: WechatUiState get() = _state

    @Synchronized
    private fun mutate(block: (WechatUiState) -> WechatUiState) {
        _state = block(_state)
    }

    fun initFromPrefs(
        configured: Boolean,
        serverUrl: String,
        currentWxid: String,
        sessionMode: String,
        contactsFilter: String,
        notifyEnabled: Boolean,
        soundEnabled: Boolean,
        muteAll: Boolean,
    ) = mutate {
        it.copy(
            configured = configured,
            serverUrl = serverUrl,
            currentWxid = currentWxid,
            sessionMode = sessionMode,
            contactsFilter = contactsFilter,
            notifyEnabled = notifyEnabled,
            soundEnabled = soundEnabled,
            muteAll = muteAll,
        )
    }

    fun setConfigured(configured: Boolean, serverUrl: String) = mutate {
        it.copy(configured = configured, serverUrl = serverUrl)
    }

    fun setConnState(s: WechatConnState) = mutate { it.copy(connState = s) }

    fun setAgents(list: List<WechatAgent>) = mutate { it.copy(agents = list) }

    fun setCurrentWxid(wxid: String) = mutate { it.copy(currentWxid = wxid) }

    /**
     * 覆盖写入会话（第 1 页 / 全量刷新）。
     *
     * v0.2.24：与旧会话逐条合并 **未读数取较大值**。
     * 原因：服务端 `sessions.unread_count` 是**客户端写回**的（center-server 收到新消息时
     * 并不自增，全库只有 `db.update_session_unread()` 被客户端 PUT 调用），
     * 因此服务端值可能小于本端 WS 累计值；直接覆盖会把刚进页面时看到的角标抹成 0。
     */
    fun setSessions(list: List<WechatSession>, unreadTotal: Int) = mutate { st ->
        val known = st.sessions.associateBy { it.userWxid }
        val merged = list.map { s ->
            val old = known[s.userWxid]
            if (old != null && old.unreadCount > s.unreadCount) s.copy(unreadCount = old.unreadCount) else s
        }
        st.copy(sessions = merged, unreadTotal = unreadTotal, loadingSessions = false)
    }

    /** 追加一页会话（去重后加在尾部），并更新「是否已到底」。 */
    fun appendSessions(more: List<WechatSession>, ended: Boolean): Int = run {
        var added = 0
        mutate { st ->
            val known = st.sessions.map { it.userWxid }.toSet()
            val fresh = more.filter { it.userWxid !in known }
            added = fresh.size
            st.copy(
                sessions = st.sessions + fresh,
                loadingMoreSessions = false,
                // v0.2.24：整页都是已存在的（added == 0）⇒ 判定已到底。
                // 否则 offset 不变（= sessions.size），下次请求拿回同一批，
                // footer 会一直显示「滑动到底部加载更多」却永远加载不出新内容。
                sessionsEnded = ended || added == 0,
            )
        }
        added
    }

    fun setSessionsEnded(ended: Boolean) = mutate { it.copy(sessionsEnded = ended) }

    fun setLoadingMoreSessions(v: Boolean) = mutate { it.copy(loadingMoreSessions = v) }

    fun setContactsEnded(ended: Boolean) = mutate { it.copy(contactsEnded = ended) }

    fun setLoadingMoreContacts(v: Boolean) = mutate { it.copy(loadingMoreContacts = v) }

    /** 追加一页联系人（去重后加在尾部），并更新「是否已到底」。返回新增条数。 */
    fun appendContacts(more: List<WechatContact>, ended: Boolean): Int = run {
        var added = 0
        mutate { st ->
            val known = st.contacts.map { it.wxid }.toSet()
            val fresh = more.filter { it.wxid !in known }
            added = fresh.size
            st.copy(
                contacts = st.contacts + fresh,
                loadingMoreContacts = false,
                // 同 [appendSessions]：整页都是重复项 ⇒ 判定已到底
                contactsEnded = ended || added == 0,
            )
        }
        added
    }

    /** 退出微信后请求回到 APP 主页（自增令牌，MainScreen 观察）。 */
    fun requestExitToHome() = mutate { it.copy(exitToHomeToken = it.exitToHomeToken + 1) }

    /** 请求跳到最新一条未读会话（自增令牌，微信页观察）。 */
    fun requestJumpUnread() = mutate { it.copy(jumpUnreadToken = it.jumpUnreadToken + 1) }

    fun setLoadingSessions(loading: Boolean) = mutate { it.copy(loadingSessions = loading) }

    fun setSessionMode(mode: String) = mutate { it.copy(sessionMode = mode) }

    fun setContactsFilter(f: String) = mutate { it.copy(contactsFilter = f) }

    fun setContacts(list: List<WechatContact>) = mutate { it.copy(contacts = list) }

    fun setUnreadTotal(total: Int) = mutate { it.copy(unreadTotal = total) }

    fun setNotifyEnabled(v: Boolean) = mutate { it.copy(notifyEnabled = v) }

    fun setSoundEnabled(v: Boolean) = mutate { it.copy(soundEnabled = v) }

    fun setMuteAll(v: Boolean) = mutate { it.copy(muteAll = v) }

    fun toast(msg: String) = mutate { it.copy(toast = msg) }

    fun clearToast() = mutate { it.copy(toast = "") }

    /** 通知点击后由 MainActivity 写入，UI 消费后清空（解决 App 已在前台时 intent 不刷新）。 */
    fun setPendingOpenSession(wxid: String) = mutate { it.copy(pendingOpenSession = wxid) }

    /** 新消息到达（供聊天面板增量合并最新消息）。 */
    fun bumpMessageVersion() = mutate { it.copy(messageVersion = it.messageVersion + 1) }

    /**
     * 清空内存中的业务数据（会话/联系人/未读/实例），**保留配置**（地址、Key、开关），
     * 便于重新进入微信页时直接重连。用于「退出微信」与「离开即释放」。
     */
    fun clearData() = mutate {
        it.copy(
            agents = emptyList(),
            sessions = emptyList(),
            contacts = emptyList(),
            unreadTotal = 0,
            connState = WechatConnState.DISCONNECTED,
            pendingOpenSession = "",
            sessionsEnded = false,
            loadingMoreSessions = false,
            contactsEnded = false,
            loadingMoreContacts = false,
        )
    }

    fun setReleased(v: Boolean) = mutate { it.copy(released = v) }

    fun clearPendingOpenSession() = mutate { it.copy(pendingOpenSession = "") }

    /** 单条会话字段变更（置顶/免打扰/未读/最后消息）。 */
    fun updateSession(wxid: String, block: (WechatSession) -> WechatSession) = mutate { st ->
        st.copy(sessions = st.sessions.map { if (it.userWxid == wxid) block(it) else it })
    }

    fun upsertSession(session: WechatSession) = mutate { st ->
        val list = st.sessions.toMutableList()
        val idx = list.indexOfFirst { it.userWxid == session.userWxid }
        if (idx >= 0) list[idx] = session else list += session
        st.copy(sessions = list)
    }

    fun removeSession(wxid: String) = mutate { st ->
        st.copy(sessions = st.sessions.filter { it.userWxid != wxid })
    }
}
