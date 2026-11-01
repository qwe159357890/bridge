package my.bridge.com.wechat

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.bridge.com.core.common.DiagLog
import my.bridge.com.MainActivity
import org.json.JSONObject

/**
 * 微信（中心服务器）**进程级运行时** —— 本功能与其它集成最大的不同。
 *
 * 销售页/智能控制的连接是**页面级**（进入页面才连、离开释放）；微信的实时性是硬需求，
 * 因此连接抬到进程级：
 * - [start] 在 Application 启动时调用，连接**常开**，不随页面销毁而断；
 * - 页面只做订阅/退订（[onPageEnter] / [onPageLeave]），**绝不断连**；
 * - 断线固定 2s 重连（照参考项目，不做指数退避），网络恢复**即刻**重连；
 * - 进程存活复用既有保活（KeepAliveService 常驻 FGS + 电池白名单 + 开机自启 + 双兜底），
 *   **不新增前台服务**，避免触碰崩溃台账 §5 的高危面。
 */
object WechatRuntime : WechatWsClient.Listener {

    private const val TAG = "微信"
    private const val RECONNECT_MS = 2_000L

    /** 分页页大小：会话与联系人都改成「滚到底再拉下一页」，不再一次拉 200 条（v0.2.23）。 */
    private const val SESSION_PAGE = 50
    private const val CONTACT_PAGE = 50

    /**
     * 单次请求条数上限（v0.2.24）：刷新时要按已加载条数取回（见 [refreshSessions]），
     * 服务端 `query.max_limit` 是 500（`center-server/config`），超了会被 422 拒。
     */
    private const val SESSION_MAX = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var app: Context? = null

    @Volatile
    private var api: WechatApiClient? = null

    private val ws = WechatWsClient()

    @Volatile
    private var started = false

    /** 是否已被释放（主动退出 / 离开即释放）——为 true 时不再自动重连，直到重新进入微信页。 */
    @Volatile
    private var stopped = false

    @Volatile
    private var pageActive = false

    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ------------------------------------------------------------------
    // 生命周期（进程级）
    // ------------------------------------------------------------------

    /** Application 启动时调用：读配置 → 若已配置则连接。 */
    @Synchronized
    fun start(context: Context) {
        val appCtx = context.applicationContext
        app = appCtx
        if (api == null) api = WechatApiClient(appCtx)
        WechatState.initFromPrefs(
            configured = WechatPrefs.configured(appCtx),
            serverUrl = WechatPrefs.serverUrl(appCtx),
            currentWxid = WechatPrefs.currentWxid(appCtx),
            sessionMode = WechatPrefs.sessionMode(appCtx),
            contactsFilter = WechatPrefs.contactsFilter(appCtx),
            notifyEnabled = WechatPrefs.notifyEnabled(appCtx),
            soundEnabled = WechatPrefs.soundEnabled(appCtx),
            muteAll = WechatPrefs.muteAll(appCtx),
            // v0.2.25：公众号默认静默（照参考项目 web/PC/外部端），可在设置页打开
            officialNotify = WechatPrefs.officialNotify(appCtx),
        )
        ws.setListener(this)
        registerNetworkCallback(appCtx)
        started = true
        DiagLog.i(TAG, "Runtime 启动：configured=${WechatPrefs.configured(appCtx)} url=${WechatPrefs.serverUrl(appCtx)}")
        connect()
        refreshAll()
    }

    /** 配置变更（地址/Key 保存后）：重连并重新拉数据。 */
    fun onConfigChanged(context: Context) {
        val appCtx = context.applicationContext
        app = appCtx
        WechatState.setConfigured(WechatPrefs.configured(appCtx), WechatPrefs.serverUrl(appCtx))
        reconnectNow("配置变更")
        refreshAll()
    }

    /**
     * 页面进入：**只订阅**（拉一次最新数据）。
     * 若此前被释放过（主动退出 / 离开即释放），这里重新建连——与销售/智能控制「进入即连」一致。
     */
    fun onPageEnter(context: Context) {
        val appCtx = context.applicationContext
        app = appCtx
        if (!started) start(appCtx)
        pageActive = true
        if (stopped) {
            stopped = false
            WechatState.setReleased(false)
            DiagLog.i(TAG, "重新进入微信页，恢复连接")
            if (api == null) api = WechatApiClient(appCtx)
            connect()
        }
        refreshAll()
    }

    /**
     * 页面离开：**只退订**（停止为 UI 刷新数据）。
     * 默认连接保持在线——断开就等于放弃后台实时收消息。
     * 但当「离开微信页立即释放」开关打开时，立刻释放全部资源（等同用户主动退出）。
     */
    fun onPageLeave() {
        pageActive = false
        val ctx = app ?: return
        if (WechatPrefs.releaseOnLeave(ctx)) stopAll("离开微信页（已开启离开即释放）")
    }

    /**
     * 释放微信占用的全部资源：WS 连接、重连任务、HTTP 连接池与线程池、语音播放、内存数据。
     *
     * 两种触发：①用户主动「退出微信」；②开启「离开微信页立即释放」后离开页面。
     * 与销售 / 智能控制 / 快递扫码的 `stop()` 彻底释放模式一致（v0.2.22）。
     * 释放后再次进入微信页会自动重连（见 [onPageEnter]）。
     */
    @Synchronized
    fun stopAll(reason: String) {
        DiagLog.i(TAG, "释放微信全部资源：$reason")
        reconnectJob?.cancel()
        reconnectJob = null
        ws.disconnect()
        stopVoice()
        api?.close()
        api = null
        WechatState.clearData()
        WechatState.setReleased(true)
        stopped = true
    }

    /** 用户主动退出微信（设置页「退出微信」按钮）：释放资源 + 通知 UI 回到 APP 主页。 */
    fun logout(context: Context) {
        app = context.applicationContext
        stopAll("用户主动退出微信")
        // v0.2.23：用户反馈「点了退出还停在微信页」。退出后跳回主页 Tab（MainScreen 观察该令牌）。
        WechatState.requestExitToHome()
    }

    // ------------------------------------------------------------------
    // 连接与重连
    // ------------------------------------------------------------------

    /**
     * 发起连接。返回**是否真的发起了握手**（false = 未配置 / 已在连接 / 地址无法推导成 WS）。
     *
     * v0.2.23：地址推导失败时立刻把状态打回 DISCONNECTED——老代码会让状态永远停在
     * CONNECTING（没有任何回调触发），用户看到的就是「实时推送连接中」却永远连不上。
     */
    private fun connect(): Boolean {
        val ctx = app ?: return false
        if (!WechatPrefs.configured(ctx)) {
            DiagLog.i(TAG, "未配置服务器，跳过连接")
            WechatState.setConnState(WechatConnState.DISCONNECTED)
            return false
        }
        if (ws.isConnected()) return true
        WechatState.setConnState(WechatConnState.CONNECTING)
        val launched = ws.connect(
            serverUrl = WechatPrefs.serverUrl(ctx),
            apiKey = WechatPrefs.apiKey(ctx),
            deviceId = WechatPrefs.deviceId(ctx),
            deviceName = android.os.Build.MODEL,
        )
        if (!launched) {
            WechatState.setConnState(WechatConnState.DISCONNECTED)
            DiagLog.w(TAG, "WS 连接未能发起，请检查服务器地址：${WechatPrefs.serverUrl(ctx)}")
        }
        return launched
    }

    private fun reconnectNow(reason: String) {
        DiagLog.i(TAG, "重连（$reason）")
        ws.disconnect()
        scope.launch { connect() }
    }

    /** 固定 2s 重连（照参考项目，不做指数退避，优先保证恢复速度）。 */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (isActive && !ws.isConnected()) {
                delay(RECONNECT_MS)
                if (!ws.isConnected()) {
                    // 连不上且不是「已在连接中」（如地址非法、未配置）：停止空转重试，
                    // 等配置变更或网络恢复再触发——否则会 2s 一次无意义地打日志。
                    // ⚠️ 只能用 break 退出本循环，绝不能调 cancel()——那会取消共享的
                    // Runtime scope（SupervisorJob），把整个微信运行时一起干掉。
                    if (!connect()) break
                }
            }
        }
    }

    /** 网络恢复即刻重连——不等心跳/Pong 超时（否则最长 60s 才感知断线）。 */
    private fun registerNetworkCallback(ctx: Context) {
        if (networkCallback != null) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (stopped) return
                DiagLog.i(TAG, "网络可用，检查连接")
                if (WechatPrefs.configured(ctx) && !ws.isConnected()) reconnectNow("网络恢复")
            }
        }
        networkCallback = cb
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(), cb
                )
            }
        }.onFailure { DiagLog.w(TAG, "注册网络回调失败：${it.message}") }
    }

    // ------------------------------------------------------------------
    // WS 事件
    // ------------------------------------------------------------------

    override fun onConnected(clientId: String) {
        val ctx = app ?: return
        WechatState.setConnState(WechatConnState.CONNECTED)
        reconnectJob?.cancel()
        if (clientId.isNotBlank()) WechatPrefs.setLocalClientId(ctx, clientId)
        DiagLog.i(TAG, "WS 已连接 clientId=$clientId")
        // 重连成功后校准一次未读，防止断线期间漏计
        refreshAll()
    }

    override fun onDisconnected(reason: String) {
        WechatState.setConnState(WechatConnState.DISCONNECTED)
        DiagLog.w(TAG, "WS 断开：$reason")
        // 已主动释放（退出微信 / 离开即释放）：不要自动重连，否则退出会被重连机制立刻撤销
        if (stopped) return
        scheduleReconnect()
    }

    override fun onIncomingMessage(raw: JSONObject) {
        val ctx = app ?: return
        // 广播去自扰：本端发出的动作回弹回来时忽略
        val fromClient = raw.optString("from_client")
        val localId = WechatPrefs.localClientId(ctx)
        if (fromClient.isNotBlank() && localId.isNotBlank() && fromClient == localId) return

        val msg = raw.opt("data") as? JSONObject ?: raw
        val isSelf = msg.optInt("is_self", 0) == 1 || msg.optBoolean("is_self", false)
        val sessionWxid = msg.optString("from_group").ifBlank { msg.optString("from_wxid") }
            .ifBlank { msg.optString("session_id") }
        if (sessionWxid.isBlank()) return
        val content = msg.optString("content")
        val msgType = msg.optInt("msg_type", WechatMsgType.TEXT)

        val session = WechatState.state.sessions.firstOrNull { it.userWxid == sessionWxid }
        val muted = session?.isMuted ?: false
        val summary = summaryOf(msgType, content)

        val updated = (session ?: WechatSession(
            userWxid = sessionWxid,
            userNickname = msg.optString("sender_nickname").ifBlank { sessionWxid },
            lastMsg = "", lastTime = "", updateTimestamp = 0L,
            unreadCount = 0, isPinned = false, isMuted = false,
        )).copy(
            lastMsg = summary,
            lastTime = msg.optString("create_time"),
            updateTimestamp = (System.currentTimeMillis() / 1000),
            unreadCount = if (isSelf) 0 else (session?.unreadCount ?: 0) + 1,
        )
        WechatState.upsertSession(updated)
        recalcUnread()
        // 通知聊天面板增量合并最新消息。注意必须放在下面的「正看着该会话就 return」之前——
        // 用户停留在该会话时不弹通知，但消息本身必须出现在聊天详情里（v0.2.22 修复）。
        WechatState.bumpMessageVersion()

        // v0.2.25：公众号静默（默认）——照参考项目 web/PC/外部端，公众号消息照常入库、
        // 照常更新列表与未读，但**不通知、不响铃**；用户在设置页打开开关后才提醒。
        if (updated.isOfficial && !WechatPrefs.officialNotify(ctx)) {
            DiagLog.i(TAG, "公众号消息静默：$sessionWxid")
            return
        }
        // 自己发的不提醒；免打扰不提醒但仍计未读（与参考前端一致）
        if (isSelf || muted) return
        if (pageActive && WechatController.isViewing(sessionWxid)) {
            // 用户正看着这个会话：不打扰
            return
        }
        remind(ctx, updated, summary)
    }

    /**
     * 提示：通知 + 提示音（提醒方式可关，连接不可关）。
     *
     * ⚠️ **免打扰会话到此为止**：`onIncomingMessage` 里已有 `if (isSelf || muted) return`，
     * 这里再兜一道（防御后续改动绕过上层判断）——免打扰会话只计未读，不弹通知、不响提示音。
     */
    private fun remind(ctx: Context, session: WechatSession, summary: String) {
        if (session.isMuted) {
            DiagLog.i(TAG, "免打扰会话不提醒：${session.userWxid}")
            return
        }
        if (WechatPrefs.notifyEnabled(ctx)) {
            // v0.2.29：显式构造 Intent，**不再用 getLaunchIntentForPackage()**。
            // 原因（台账 §3 条件 ①）：后者返回的就是 ACTION_MAIN + CATEGORY_LAUNCHER + 本 Activity，
            // 与 task 的 base intent **完全相同**（Intent.filterEquals() 不比较 extras），
            // 于是 AMS 在「task 已存在、只是从后台恢复」这条路径上判定「还是同一个启动」，
            // 只把 task 带到前台而**不回调 onNewIntent** ⇒ pendingOpenSession 从未写入 ⇒
            // 点了通知只是回到原页面（主页）。冷启动时走 onCreate 则一定送达 ⇒
            // 这正是「大多数时候能进详情、有时不行」的差异来源。
            // 修法：专用 action + 唯一 data（含 wxid）+ NEW_TASK|CLEAR_TOP|SINGLE_TOP。
            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = WECHAT_ACTION_OPEN_SESSION
                data = Uri.parse("bridge://wechat/session/${Uri.encode(session.userWxid)}")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(WECHAT_EXTRA_OPEN_SESSION, session.userWxid)
            }
            WechatNotifier.post(
                context = ctx,
                sessionWxid = session.userWxid,
                title = session.userNickname,
                content = summary,
                sound = WechatPrefs.soundEnabled(ctx) && !WechatPrefs.muteAll(ctx),
                unreadCount = WechatState.state.unreadTotal,
                openIntent = intent,
            )
        }
        if (WechatPrefs.soundEnabled(ctx) && !WechatPrefs.muteAll(ctx)) {
            WechatSound.play(ctx)
        }
    }

    override fun onUnreadCleared(sessionId: String, fromClient: String?) {
        if (isSelf(fromClient)) return
        WechatState.updateSession(sessionId) { it.copy(unreadCount = 0) }
        recalcUnread()
        // v0.2.25：通知是**全局唯一一条**（见 WechatNotifier.NOTIFICATION_ID），
        // 只有当所有会话都没未读了才撤掉，否则会把别人会话的通知一并干掉。
        if (WechatState.state.sessions.none { it.unreadCount > 0 }) {
            app?.let { WechatNotifier.cancel(it) }
        }
    }

    override fun onPinChanged(sessionId: String, pinned: Boolean, fromClient: String?) {
        if (isSelf(fromClient)) return
        WechatState.updateSession(sessionId) { it.copy(isPinned = pinned) }
    }

    override fun onMuteChanged(sessionId: String, muted: Boolean, fromClient: String?) {
        if (isSelf(fromClient)) return
        WechatState.updateSession(sessionId) { it.copy(isMuted = muted) }
        recalcUnread()
    }

    override fun onSessionUpdate(sessionId: String, fromClient: String?) {
        if (isSelf(fromClient)) return
        refreshSessions()
    }

    override fun onSessionCleared(sessionId: String) {
        WechatState.updateSession(sessionId) { it.copy(lastMsg = "", unreadCount = 0) }
        recalcUnread()
    }

    override fun onSessionDeleted(sessionId: String) {
        WechatState.removeSession(sessionId)
        recalcUnread()
        // 同 [onUnreadCleared]：通知全局唯一，没有未读了才撤
        if (WechatState.state.sessions.none { it.unreadCount > 0 }) {
            app?.let { WechatNotifier.cancel(it) }
        }
    }

    override fun onInstanceChange() {
        refreshAgents()
    }

    override fun onPlaySound() {
        app?.let { if (WechatPrefs.soundEnabled(it) && !WechatPrefs.muteAll(it)) WechatSound.play(it) }
    }

    private fun isSelf(fromClient: String?): Boolean {
        val ctx = app ?: return false
        val local = WechatPrefs.localClientId(ctx)
        return !fromClient.isNullOrBlank() && local.isNotBlank() && fromClient == local
    }

    private fun recalcUnread() {
        // v0.2.24：口径照参考前端——当前模式（好友/公众号）下的非免打扰会话。
        // 旧口径对全量 sessions 求和，未读落在被模式过滤掉的一类时，
        // 会出现「Tab 有角标、列表里一个角标都没有」。
        WechatState.setUnreadTotal(WechatState.state.unreadOf(WechatState.state.sessions))
    }

    private fun summaryOf(msgType: Int, content: String): String = when (msgType) {
        WechatMsgType.IMAGE -> "[图片]"
        WechatMsgType.VIDEO -> "[视频]"
        WechatMsgType.VOICE -> "[语音]"
        WechatMsgType.EMOJI -> "[表情]"
        WechatMsgType.LOCATION -> "[位置]"
        WechatMsgType.XML -> "[应用消息]"
        else -> WechatEmoji.replaceCodes(content).ifBlank { "[消息]" }
    }

    // ------------------------------------------------------------------
    // 数据刷新
    // ------------------------------------------------------------------

    fun refreshAll() {
        val ctx = app ?: return
        if (!WechatPrefs.configured(ctx)) return
        // 已释放（退出微信 / 离开即释放）或 api 尚未就绪：不要拉数据（api 已置 null）
        if (stopped || api == null) return
        refreshAgents()
        refreshSessions()
    }

    fun refreshAgents() {
        val ctx = app ?: return
        scope.launch {
            runCatching {
                val list = api!!.agents()
                withContext(Dispatchers.Main.immediate) { WechatState.setAgents(list) }
                // 账号失效（实例下线）时清空当前选择，避免用不存在的 wxid 发送
                val current = WechatPrefs.currentWxid(ctx)
                if (current.isNotBlank() && list.none { a -> a.instances.any { it.wxid == current } }) {
                    WechatPrefs.setCurrentWxid(ctx, "")
                    withContext(Dispatchers.Main.immediate) { WechatState.setCurrentWxid("") }
                }
            }.onFailure { DiagLog.w(TAG, "拉取代理/实例失败：${it.message}") }
        }
    }

    /**
     * 首页会话（v0.2.23 起分页，v0.2.24 修正「刷新打回第 1 页」）。
     *
     * 触发者很多（WS `session_update`、`onConnected`、进入页面、发送成功后），
     * v0.2.23 每次都拉 `limit=50, offset=0` 并整体覆盖 ⇒ 用户翻到第 3 页后，
     * 来一条新消息列表就缩回 50 条（真机问题 #2）。
     *
     * 修正：**按已加载条数取回**（`want = max(50, 当前条数)`，上限 [SESSION_MAX]），
     * 保证刷新前后列表长度不变；未读总数按当前模式过滤（见 [WechatUiState.unreadOf]）。
     */
    fun refreshSessions() {
        val ctx = app ?: return
        if (WechatState.state.loadingSessions) return
        WechatState.setLoadingSessions(true)
        // 已在内存里的条数（可能已翻过几页）：刷新要取回同样多，不能打回第 1 页
        val want = WechatState.state.sessions.size
            .coerceAtLeast(SESSION_PAGE)
            .coerceAtMost(SESSION_MAX)
        scope.launch {
            runCatching {
                val list = api!!.sessions(limit = want, offset = 0)
                withContext(Dispatchers.Main.immediate) {
                    WechatState.setSessions(list, WechatState.state.unreadOf(list))
                    // 返回条数不足请求量 ⇒ 后面没有了（列表据此显示「已经加载全部」）
                    WechatState.setSessionsEnded(list.size < want)
                }
            }.onFailure {
                DiagLog.w(TAG, "拉取会话失败：${it.message}")
                WechatState.setLoadingSessions(false)
            }
        }
    }

    /** 加载下一页会话（列表滑到底调用）。已在加载 / 已到底则直接返回。 */
    fun loadMoreSessions() {
        val ctx = app ?: return
        val st = WechatState.state
        if (st.loadingMoreSessions || st.sessionsEnded || st.loadingSessions) return
        WechatState.setLoadingMoreSessions(true)
        scope.launch {
            runCatching {
                val offset = WechatState.state.sessions.size
                val list = api!!.sessions(limit = SESSION_PAGE, offset = offset)
                withContext(Dispatchers.Main.immediate) {
                    WechatState.appendSessions(list, list.size < SESSION_PAGE)
                }
            }.onFailure {
                DiagLog.w(TAG, "加载更多会话失败：${it.message}")
                WechatState.setLoadingMoreSessions(false)
            }
        }
    }

    /** 首页联系人（v0.2.23 起分页）。 */
    fun refreshContacts() {
        val ctx = app ?: return
        scope.launch {
            runCatching {
                val account = WechatPrefs.currentWxid(ctx)
                val filter = WechatPrefs.contactsFilter(ctx)
                var list = api!!.contacts(accountWxid = account, filter = filter, limit = CONTACT_PAGE, offset = 0)
                // 兜底（v0.2.22 修复「通讯录空空如也」）：contacts 表里的 account_wxid 未必回填完整，
                // 此时带账号过滤会永远返回空列表。空结果时再不带账号拉一次全局联系人。
                if (list.isEmpty() && account.isNotBlank()) {
                    DiagLog.i(TAG, "按账号过滤无联系人，回退拉取全部联系人")
                    list = api!!.contacts(accountWxid = "", filter = filter, limit = CONTACT_PAGE, offset = 0)
                }
                val ended = list.size < CONTACT_PAGE
                withContext(Dispatchers.Main.immediate) {
                    WechatState.setContacts(list)
                    WechatState.setContactsEnded(ended)
                }
            }.onFailure { DiagLog.w(TAG, "拉取联系人失败：${it.message}") }
        }
    }

    /** 加载下一页联系人（列表滑到底调用）。 */
    fun loadMoreContacts() {
        val ctx = app ?: return
        val st = WechatState.state
        if (st.loadingMoreContacts || st.contactsEnded) return
        WechatState.setLoadingMoreContacts(true)
        scope.launch {
            runCatching {
                val account = WechatPrefs.currentWxid(ctx)
                val filter = WechatPrefs.contactsFilter(ctx)
                val offset = WechatState.state.contacts.size
                var list = api!!.contacts(accountWxid = account, filter = filter, limit = CONTACT_PAGE, offset = offset)
                if (list.isEmpty() && account.isNotBlank()) {
                    list = api!!.contacts(accountWxid = "", filter = filter, limit = CONTACT_PAGE, offset = offset)
                }
                withContext(Dispatchers.Main.immediate) {
                    WechatState.appendContacts(list, list.size < CONTACT_PAGE)
                }
            }.onFailure {
                DiagLog.w(TAG, "加载更多联系人失败：${it.message}")
                WechatState.setLoadingMoreContacts(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // 业务动作（供页面调用）
    // ------------------------------------------------------------------

    /** 测试连接：不传参用已保存配置；传参则测输入值（保存前先测，避免存下连不上的地址）。 */
    suspend fun testConnection(context: Context, url: String = "", apiKey: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { WechatApiClient(context, url, apiKey).testConnection() }.getOrDefault(false)
        }

    fun saveConfig(context: Context, url: String, apiKey: String) {
        WechatPrefs.setServerUrl(context, url)
        WechatPrefs.setApiKey(context, apiKey)
        onConfigChanged(context)
    }

    fun selectAccount(context: Context, wxid: String) {
        WechatPrefs.setCurrentWxid(context, wxid)
        WechatState.setCurrentWxid(wxid)
        refreshContacts()
    }

    fun setSessionMode(context: Context, mode: String) {
        WechatPrefs.setSessionMode(context, mode)
        WechatState.setSessionMode(mode)
    }

    fun setContactsFilter(context: Context, filter: String) {
        WechatPrefs.setContactsFilter(context, filter)
        WechatState.setContactsFilter(filter)
        refreshContacts()
    }

    fun setNotifyEnabled(context: Context, enabled: Boolean) {
        WechatPrefs.setNotifyEnabled(context, enabled)
        WechatState.setNotifyEnabled(enabled)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        WechatPrefs.setSoundEnabled(context, enabled)
        WechatState.setSoundEnabled(enabled)
    }

    fun setMuteAll(context: Context, muted: Boolean) {
        WechatPrefs.setMuteAll(context, muted)
        WechatState.setMuteAll(muted)
    }

    /** 公众号消息提醒开关（v0.2.25，默认关 = 静默）。 */
    fun setOfficialNotify(context: Context, on: Boolean) {
        WechatPrefs.setOfficialNotify(context, on)
        WechatState.setOfficialNotify(on)
    }

    /** 进入会话：本地清零 + 通知撤回 + 服务端已读 + WS 广播。 */
    fun openSession(context: Context, sessionWxid: String) {
        WechatState.updateSession(sessionWxid) { it.copy(unreadCount = 0) }
        recalcUnread()
        // v0.2.25：通知全局唯一，只有全都没未读了才撤（否则会连其它会话的通知一起撤掉）
        if (WechatState.state.sessions.none { it.unreadCount > 0 }) {
            WechatNotifier.cancel(context)
        }
        ws.markRead(sessionWxid)
        scope.launch {
            runCatching { WechatApiClient(context).markRead(sessionWxid) }
                .onFailure { DiagLog.w(TAG, "标记已读失败：${it.message}") }
        }
    }

    fun togglePin(context: Context, sessionWxid: String, pinned: Boolean) {
        WechatState.updateSession(sessionWxid) { it.copy(isPinned = pinned) }
        ws.togglePin(sessionWxid, pinned)
    }

    fun toggleMute(context: Context, sessionWxid: String, muted: Boolean) {
        WechatState.updateSession(sessionWxid) { it.copy(isMuted = muted) }
        recalcUnread()
        ws.toggleMute(sessionWxid, muted)
    }

    /** 清空聊天记录：本地清空 + WS 广播 + REST 删除服务端消息（DELETE messages/sessions/{id}/messages）。 */
    fun clearSession(context: Context, sessionWxid: String) {
        WechatState.updateSession(sessionWxid) { it.copy(lastMsg = "", unreadCount = 0) }
        recalcUnread()
        if (WechatState.state.sessions.none { it.unreadCount > 0 }) {
            WechatNotifier.cancel(context)
        }
        ws.clearSession(sessionWxid)
        scope.launch {
            runCatching { WechatApiClient(context).clearSessionMessages(sessionWxid) }
                .onFailure { DiagLog.w(TAG, "清空聊天记录失败：${it.message}") }
        }
    }

    /** 删除单条消息（本地移除 + REST；服务端会广播，其它端自行同步）。 */
    suspend fun deleteMessage(context: Context, sessionWxid: String, msgId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { WechatApiClient(context).deleteMessage(sessionWxid, msgId) }
        }

    suspend fun sendText(context: Context, targetWxid: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val wxid = WechatPrefs.currentWxid(context)
            if (wxid.isBlank()) return@withContext Result.failure(IllegalStateException("请先选择微信账号"))
            runCatching { WechatApiClient(context).sendText(wxid, targetWxid, text) }
        }

    suspend fun sendImage(context: Context, targetWxid: String, fileName: String, mime: String, bytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            val wxid = WechatPrefs.currentWxid(context)
            if (wxid.isBlank()) return@withContext Result.failure(IllegalStateException("请先选择微信账号"))
            runCatching {
                // 手机端只能走 multipart 上传：send_image 的参数是服务器文件路径
                WechatApiClient(context).uploadAndSend(wxid, targetWxid, fileName, mime, bytes)
            }
        }

    /** 发送图片/视频/文件：手机端统一走 multipart `wechat/upload_and_send`。 */
    suspend fun sendFile(context: Context, targetWxid: String, fileName: String, mime: String, bytes: ByteArray): Result<Unit> =
        sendImage(context, targetWxid, fileName, mime, bytes)

    suspend fun loadMessages(context: Context, sessionWxid: String, limit: Int = 50, offset: Int = 0): List<WechatMessage> =
        withContext(Dispatchers.IO) {
            runCatching { WechatApiClient(context).messages(sessionWxid, limit = limit, offset = offset) }
                .getOrDefault(emptyList())
        }

    fun attachmentUrl(context: Context, objectName: String): String =
        WechatApiClient(context).attachmentUrl(objectName)

    /** 头像 URL（Bearer 鉴权，由 [WechatImageLoader] 带 key 请求）。 */
    fun avatarUrl(context: Context, wxid: String): String =
        WechatApiClient(context).avatarUrl(wxid)

    fun apiKey(context: Context): String = WechatApiClient(context).apiKeyValue()

    /** 设置页展示用：实际 WS 端点（排查「REST 通但 WS 连不上」时第一手信息）。 */
    fun wsEndpoint(context: Context): String =
        WechatWsClient.toWsUrl(WechatPrefs.serverUrl(context))
            ?: "（地址无效，无法推导 WS 地址）"

    /** 保存附件到 `/sdcard/bridge/wechat/{images,videos,voice,files}`。 */
    suspend fun saveAttachment(
        context: Context,
        objectName: String,
        fileName: String,
        kind: WechatDownloader.Kind,
    ): Result<android.net.Uri> {
        val url = attachmentUrl(context, objectName)
        if (url.isBlank()) return Result.failure(IllegalStateException("附件地址无效"))
        return WechatDownloader.save(context, url, fileName, kind)
    }

    /** 语音播放/停止（先下载到缓存再本地播放，见 [WechatVoicePlayer]）。 */
    suspend fun toggleVoice(context: Context, msgId: String, objectName: String) {
        val url = attachmentUrl(context, objectName)
        if (url.isBlank()) return
        WechatVoicePlayer.toggle(context, msgId, url)
    }

    fun stopVoice() = WechatVoicePlayer.stop()

}

/** 通知点击时携带的会话 wxid（MainActivity 读取后跳转微信页并打开该会话）。 */
const val WECHAT_EXTRA_OPEN_SESSION = "wechat_open_session"

/**
 * 通知点击的**专用 action**（v0.2.29）：必须区别于 launcher 的 `ACTION_MAIN`，
 * 否则 AMS 会认为「与 task 的启动 intent 相同」而只把 task 带前台、不回调 onNewIntent。
 * 详见 `docs/dev/wechat-notification-jump-ledger-v0.2.29.md` §3 条件 ①。
 */
const val WECHAT_ACTION_OPEN_SESSION = "my.bridge.com.action.OPEN_WECHAT_SESSION"
