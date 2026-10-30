package my.bridge.com.switch

import android.content.Context
import android.os.Handler
import android.os.Looper
import my.bridge.com.core.common.DiagLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 智能控制（ESP32 继电器）WebSocket 客户端。
 *
 * 主动出站连接参考项目中继服务器（方案 A：服务端零改动）。
 * 消息交换逻辑严格照 relay-control/src/main.ts 移植：
 *
 * - connectWebSocket：server 地址 `http://host` → WS `ws://host/ws/client`（`https` → `wss`），
 *   URL 可带 `?token=<apiKey>` 认证；
 * - onOpen：先 REST 拉设备列表 `GET /api/devices`（await），再按模式订阅 + 逐个 get_status；
 * - togglePaneMode：窗格模式订阅全部设备；单设备模式只保留当前设备订阅、退订其余；
 * - handleServerMessage：status / device_online / device_offline / devices_list / error；
 * - sendRelayCommandForDevice：只发 set，UI 更新完全靠订阅广播回来。
 *
 * 本项目在参考实现之上保留了两处增强（经用户确认）：
 * ① set 后进入「执行中」提示态并短轮询兜底（应对 ESP32 休眠不主动上报）；
 * ② device_offline 走宽限延迟确认，避免休眠导致在线↔离线反复闪烁。
 *
 * 连接健壮性借鉴 RelayClient.kt：generation 会话代次防旧回调污染、
 * 指数退避重连（封顶 30s）、心跳超时判死兜底。
 */
class RelaySwitchClient {

    private var httpClient: OkHttpClient? = null

    /** 主线程 Handler：延迟任务（状态轮询、执行中超时、离线宽限）。 */
    private val statusHandler = Handler(Looper.getMainLooper())

    /** 每个设备的状态轮询计数：deviceId -> 已轮询次数（set 后多次 getStatus 兜底）。 */
    private val pollCounts = mutableMapOf<String, Int>()

    /** 离线宽限监视任务：deviceId -> 到期执行的 Runnable（宽限期内重新上线即取消）。 */
    private val offlineWatch = mutableMapOf<String, Runnable>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var intentionalClose = false

    /** 会话代次：每次 start() 自增，作废旧连接残留回调。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var lastAliveAt = 0L

    /** 完成取消订阅的设备集合：用于 set subscribe 范围变化时做增量退订。 */
    private val subscribed = mutableSetOf<String>()

    private val lock = Any()

    private var retryThread: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        intentionalClose = false
        generation++
        val app = context.applicationContext

        httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        RelaySwitchState.updateConnection(RelaySwitchState.Connection.CONNECTING)
        connectOnce(app)
        DiagLog.i("智能控制", "继电器客户端启动")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        intentionalClose = true
        generation++
        closeWebSocket()
        httpClient = null
        statusHandler.removeCallbacksAndMessages(null)
        clearTransient()
        try { retryThread?.interrupt() } catch (e: Exception) { }
        retryThread = null
        RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
        DiagLog.i("智能控制", "继电器客户端已停止")
    }

    fun isRunning(): Boolean = running

    fun isConnected(): Boolean = webSocket != null && running

    /** 清理瞬时状态（轮询计数、离线宽限、订阅集合）。 */
    private fun clearTransient() {
        synchronized(lock) {
            pollCounts.clear()
            offlineWatch.clear()
            subscribed.clear()
        }
    }

    // ------------------------------------------------------------------
    // 发送消息（照 main.ts 的 ws.send 调用）
    // ------------------------------------------------------------------

    /** 发送一条客户端消息（subscribe/unsubscribe/get_status/set）。 */
    fun send(msg: JSONObject): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(msg.toString())
            true
        } catch (e: Exception) {
            DiagLog.w("智能控制", "发送消息失败：${e.message}")
            false
        }
    }

    /** 订阅设备。 */
    fun subscribe(deviceId: String) {
        if (deviceId.isBlank()) return
        if (send(JSONObject().apply {
                put("type", RelaySwitchProtocol.TYPE_SUBSCRIBE)
                put("device_id", deviceId)
            })
        ) {
            synchronized(lock) { subscribed.add(deviceId) }
        }
    }

    /** 取消订阅设备。 */
    fun unsubscribe(deviceId: String) {
        if (deviceId.isBlank()) return
        if (send(JSONObject().apply {
                put("type", RelaySwitchProtocol.TYPE_UNSUBSCRIBE)
                put("device_id", deviceId)
            })
        ) {
            synchronized(lock) { subscribed.remove(deviceId) }
        }
    }

    /** 请求设备状态。 */
    fun getStatus(deviceId: String) {
        if (deviceId.isBlank()) return
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_GET_STATUS)
            put("device_id", deviceId)
        })
    }

    /**
     * 设置继电器（value：0=开，1=关）。
     *
     * 照 sendRelayCommandForDevice：只发 set，不本地改状态；UI 最终由设备上报的
     * status 广播驱动刷新。发送成功后进入「执行中」提示态并启动短轮询兜底。
     */
    fun setRelay(deviceId: String, pio: Int, value: Int) {
        val sent = send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_SET)
            put("device_id", deviceId)
            put("pio", pio)
            put("value", value)
        })
        DiagLog.i("智能控制", "setRelay device=$deviceId pio=$pio value=$value sent=$sent")
        if (!sent) {
            RelaySwitchState.notifyError("未连接到服务器")
            return
        }
        // 标记「执行中」：服务端 set 后靠设备上报 status 广播；但 ESP32 可能休眠（2~10s 不等），
        // 故同时启动轮询兜底 —— 首次 500ms 后开始，每隔 2s get_status 一次，最多 8 次；
        // 一旦收到真实 status（updateStatus）即自动停止轮询并退出「执行中」。
        RelaySwitchState.markPending(deviceId)
        synchronized(lock) { pollCounts[deviceId] = 0 }
        statusHandler.postDelayed(
            { pollStatus(deviceId) },
            RelaySwitchProtocol.STATUS_REFRESH_DELAY_MS,
        )
        // 兜底超时：即便始终无状态回报，也退出「执行中」，避免卡死。
        statusHandler.postDelayed(
            {
                RelaySwitchState.clearPending(deviceId)
                DiagLog.w("智能控制", "执行中超时兜底退出 device=$deviceId")
            },
            RelaySwitchProtocol.PENDING_TIMEOUT_MS,
        )
    }

    /** 状态轮询一次：getStatus，并按最大次数决定是否继续。 */
    private fun pollStatus(deviceId: String) {
        if (!running) return
        getStatus(deviceId)
        val count = synchronized(lock) {
            val next = (pollCounts[deviceId] ?: 0) + 1
            pollCounts[deviceId] = next
            next
        }
        DiagLog.i("智能控制", "轮询 getStatus device=$deviceId 第$count/${RelaySwitchProtocol.STATUS_POLL_MAX_COUNT}次")
        if (count < RelaySwitchProtocol.STATUS_POLL_MAX_COUNT) {
            statusHandler.postDelayed(
                { pollStatus(deviceId) },
                RelaySwitchProtocol.STATUS_POLL_INTERVAL_MS,
            )
        } else {
            DiagLog.w("智能控制", "轮询达上限 device=$deviceId，转由设备主动上报")
        }
    }

    /** 停止某设备的状态轮询（收到真实 status 时调用）。 */
    private fun stopPolling(deviceId: String) {
        synchronized(lock) { pollCounts.remove(deviceId) }
    }

    // ------------------------------------------------------------------
    // 地址归一化（★ 修复：参考的 serverUrl 是带 http:// 前缀的完整 URL）
    // ------------------------------------------------------------------

    /**
     * 解析用户填写的服务器地址，统一成 (httpBase, wsBase)。
     *
     * 参考实现 GET 用 `${serverUrl}/api/devices`、WS 用 `serverUrl.replace(/^http/,'ws')+'/ws/client'`，
     * 其默认值为 `http://localhost:10888` —— 即**带协议前缀**。
     * 旧实现直接拼 `ws://$server`，遇 `http://host` 会拼出 `ws://http://host`（DNS 解析 "http" 失败）。
     * 这里同时兼容四种写法：`http://host`、`https://host`、`host:port`、`//host`。
     */
    private fun resolveServer(raw: String): Pair<String, String> {
        var s = raw.trim().trimEnd('/')
        val secure: Boolean
        when {
            s.startsWith("https://", ignoreCase = true) -> {
                secure = true
                s = s.drop(8)
            }
            s.startsWith("http://", ignoreCase = true) -> {
                secure = false
                s = s.drop(7)
            }
            s.startsWith("//") -> {
                secure = false
                s = s.drop(2)
            }
            else -> secure = false
        }
        if (s.isBlank()) s = RelaySwitchProtocol.DEFAULT_SERVER
        val hostPort = s.trimEnd('/')
        val httpBase = (if (secure) "https://" else "http://") + hostPort
        val wsBase = (if (secure) "wss://" else "ws://") + hostPort
        return httpBase to wsBase
    }

    private fun buildWsUrl(server: String, apiKey: String): String {
        val (_, wsBase) = resolveServer(server)
        var url = "$wsBase${RelaySwitchProtocol.CLIENT_PATH}"
        if (apiKey.isNotBlank()) {
            url += "?token=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
        }
        return url
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    private fun connectOnce(context: Context) {
        val client = httpClient ?: return
        val myGen = generation
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val url = buildWsUrl(server, apiKey)

        val request = Request.Builder().url(url).build()
        DiagLog.i("智能控制", "连接 WS url=$url")
        client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale(): Boolean = !running || generation != myGen

            override fun onOpen(socket: WebSocket, response: Response) {
                if (isStale()) { try { socket.close(1000, "stale") } catch (e: Exception) {}; return }
                webSocket = socket
                lastAliveAt = System.currentTimeMillis()
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.CONNECTED)
                DiagLog.i("智能控制", "已连接继电器服务器")
                synchronized(lock) { subscribed.clear() }
                // 照参考 onOpen：先 await 设备列表，再订阅 + 取状态
                fetchDevices(context) { syncSubscriptions(context) }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (isStale()) return
                lastAliveAt = System.currentTimeMillis()
                handleMessage(context, text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                DiagLog.e("智能控制", "连接异常：${t.message}", t)
                webSocket = null
                clearTransient()
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
                scheduleReconnect(context)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                DiagLog.i("智能控制", "连接已关闭：code=$code reason=$reason")
                webSocket = null
                clearTransient()
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
                if (!intentionalClose) scheduleReconnect(context)
            }
        })
    }

    /**
     * 按当前模式同步订阅范围并拉取状态（照 onOpen / togglePaneMode 的订阅逻辑）。
     *
     * - 窗格模式：**所有设备**都 subscribe + get_status（旧实现只订阅当前设备，
     *   导致窗格里其它设备永远收不到 status 广播，卡片一直卡在「获取中…」——这正是
     *   「窗格模式状态不实时」的根因）。
     * - 单设备模式：只保留当前设备订阅，退订其余，并对当前设备 get_status。
     */
    fun syncSubscriptions(context: Context) {
        if (!running || webSocket == null) return
        val devices = RelaySwitchState.state.devices
        val paneMode = RelaySwitchPrefs.paneMode(context)
        val keep = if (paneMode) {
            devices.map { it.id }.toSet()
        } else {
            setOf(RelaySwitchPrefs.deviceId(context))
        }
        DiagLog.i("智能控制", "同步订阅 paneMode=$paneMode keep=${keep.size} 台")
        // 退订范围外设备
        val stale = synchronized(lock) { subscribed.toSet() } - keep
        stale.forEach { unsubscribe(it) }
        // 订阅范围内设备并逐个取真实状态
        devices.filter { it.id in keep }.forEach { device ->
            subscribe(device.id)
            getStatus(device.id)
        }
        // 单设备模式下，当前设备可能尚未出现在 devices 列表（服务端未配置该 id）
        if (!paneMode) {
            val currentId = RelaySwitchPrefs.deviceId(context)
            if (devices.none { it.id == currentId }) {
                subscribe(currentId)
                getStatus(currentId)
            }
        }
    }

    // ------------------------------------------------------------------
    // 消息处理（照 handleServerMessage）
    // ------------------------------------------------------------------

    /** 解析服务器消息并更新状态。 */
    private fun handleMessage(context: Context, text: String) {
        try {
            val data = JSONObject(text)
            val type = data.optString("type")
            when (type) {
                RelaySwitchProtocol.TYPE_STATUS -> {
                    val deviceId = data.optString("device_id")
                    val statusData = data.optJSONObject("data") ?: return
                    val map = mutableMapOf<String, Int>()
                    statusData.keys().forEach { key ->
                        map[key] = statusData.optInt(key)
                    }
                    DiagLog.i("智能控制", "收到 status device=$deviceId data=$map")
                    // updateStatus 内部会在收到真实状态时自动退出「执行中」态
                    RelaySwitchState.updateStatus(deviceId, map)
                    stopPolling(deviceId)
                }
                RelaySwitchProtocol.TYPE_DEVICE_ONLINE -> {
                    val deviceId = data.optString("device_id")
                    val name = data.optString("name").takeIf { it.isNotBlank() }
                    DiagLog.i("智能控制", "设备上线 device=$deviceId name=$name")
                    // 取消离线宽限
                    cancelOfflineWatch(deviceId)
                    RelaySwitchState.updateDeviceOnline(deviceId, name)
                    // 照参考 device_online 分支：重新订阅并立即拉一次真实状态
                    subscribe(deviceId)
                    getStatus(deviceId)
                }
                RelaySwitchProtocol.TYPE_DEVICE_OFFLINE -> {
                    val deviceId = data.optString("device_id")
                    DiagLog.i("智能控制", "设备离线通知 device=$deviceId（进入 ${RelaySwitchProtocol.OFFLINE_GRACE_MS}ms 宽限）")
                    handleDeviceOffline(deviceId)
                }
                RelaySwitchProtocol.TYPE_DEVICES_LIST -> {
                    val arr = data.optJSONArray("devices") ?: return
                    val order = data.optJSONArray("device_order")?.let { orderArr ->
                        (0 until orderArr.length()).map { orderArr.optString(it) }
                    } ?: emptyList()
                    val list = parseDeviceArray(arr)
                    DiagLog.i("智能控制", "收到设备列表 ${list.size} 个")
                    RelaySwitchState.updateDevices(list, order)
                    // 设备列表就绪后立即同步订阅范围 + 拉状态（保证窗格模式所有卡片都有数据）
                    syncSubscriptions(context)
                }
                RelaySwitchProtocol.TYPE_ERROR -> {
                    val message = data.optString("message", "操作失败")
                    DiagLog.w("智能控制", "收到服务器 error: $message")
                    RelaySwitchState.notifyError(message)
                }
            }
        } catch (e: Exception) {
            DiagLog.w("智能控制", "解析消息失败：$text")
        }
    }

    /**
     * 设备离线处理：进入宽限期后再真正标记离线。
     *
     * ESP32 休眠会断开 WS，服务端立刻广播 device_offline，但设备几秒后就重连。
     * 若收到即置灰，UI 会反复闪烁成「等待连接」，表现为「明明在线却显示不在线」。
     */
    private fun handleDeviceOffline(deviceId: String) {
        cancelOfflineWatch(deviceId)
        val task = Runnable {
            synchronized(lock) { offlineWatch.remove(deviceId) }
            RelaySwitchState.updateDeviceOffline(deviceId)
            DiagLog.i("智能控制", "离线确认（宽限结束）device=$deviceId")
        }
        synchronized(lock) { offlineWatch[deviceId] = task }
        statusHandler.postDelayed(task, RelaySwitchProtocol.OFFLINE_GRACE_MS)
    }

    private fun cancelOfflineWatch(deviceId: String) {
        val task = synchronized(lock) { offlineWatch.remove(deviceId) } ?: return
        statusHandler.removeCallbacks(task)
    }

    private fun parseDeviceArray(arr: org.json.JSONArray): List<RelaySwitchState.Device> {
        val list = mutableListOf<RelaySwitchState.Device>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                RelaySwitchState.Device(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    online = obj.optBoolean("online"),
                )
            )
        }
        return list
    }

    /** 通过 REST 拉设备列表（照 fetchDevicesList 移植）。 */
    private fun fetchDevices(context: Context, onDone: (() -> Unit)? = null) {
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val (httpBase, _) = resolveServer(server)
        val url = "$httpBase/api/devices"
        Thread({
            try {
                val builder = Request.Builder().url(url)
                if (apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $apiKey")
                }
                val response = httpClient?.newCall(builder.build())?.execute() ?: return@Thread
                val body = response.body?.string().orEmpty()
                val result = JSONObject(body)
                val arr = result.optJSONArray("devices") ?: return@Thread
                val order = result.optJSONArray("device_order")?.let { orderArr ->
                    (0 until orderArr.length()).map { orderArr.optString(it) }
                } ?: emptyList()
                val list = parseDeviceArray(arr)
                RelaySwitchState.updateDevices(list, order)
                DiagLog.i("智能控制", "已拉取设备列表 url=$url 共 ${list.size} 个")
            } catch (e: Exception) {
                DiagLog.w("智能控制", "拉取设备列表失败 url=$url：${e.message}")
            } finally {
                onDone?.let { cb -> statusHandler.post { cb() } }
            }
        }, "BridgeSwitchFetch").start()
    }

    /** 断线后按退避策略重连（借鉴 RelayClient 的常驻看门狗）。 */
    private fun scheduleReconnect(context: Context) {
        if (!running || intentionalClose) return
        val existing = retryThread
        if (existing != null && existing.isAlive) return
        retryThread = Thread({
            var attempt = 0
            while (running && !intentionalClose) {
                if (webSocket != null) {
                    // 半死连接判死兜底
                    val lastAlive = lastAliveAt
                    if (lastAlive > 0 &&
                        System.currentTimeMillis() - lastAlive >= RelaySwitchProtocol.HEARTBEAT_TIMEOUT_MS
                    ) {
                        DiagLog.w("智能控制", "心跳超时判死，强制重连")
                        closeWebSocket()
                        continue
                    }
                    try { Thread.sleep(500) } catch (e: InterruptedException) { continue }
                    continue
                }
                val index = attempt.coerceAtMost(RelaySwitchProtocol.BACKOFF_MS.size - 1)
                val delay = RelaySwitchProtocol.BACKOFF_MS[index]
                attempt++
                try { Thread.sleep(delay) } catch (e: InterruptedException) { continue }
                if (!running || intentionalClose) break
                if (webSocket != null) continue
                DiagLog.i("智能控制", "第 $attempt 次重连")
                connectOnce(context)
                try { Thread.sleep(1000) } catch (e: InterruptedException) { continue }
            }
        }, "BridgeSwitchRetry").also { it.start() }
    }

    private fun closeWebSocket() {
        val ws = webSocket
        webSocket = null
        if (ws == null) return
        try {
            ws.close(1000, "client closing")
        } catch (e: Exception) {
            try { ws.cancel() } catch (e2: Exception) { }
        }
    }
}
