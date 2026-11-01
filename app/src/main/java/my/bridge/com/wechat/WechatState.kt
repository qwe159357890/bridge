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

    fun setSessions(list: List<WechatSession>, unreadTotal: Int) = mutate {
        it.copy(sessions = list, unreadTotal = unreadTotal, loadingSessions = false)
    }

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
