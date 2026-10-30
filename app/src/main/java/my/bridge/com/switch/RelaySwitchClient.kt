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
 * 主动出站连接参考项目中继服务器（方案 A：服务端零改动），协议照
 * relay-control/src/main.ts 的 connectWebSocket / handleServerMessage 移植：
 * - 连接 /ws/client，可在 URL 带 ?token=<apiKey> 认证；
 * - onOpen 后拉设备列表（GET /api/devices）并 subscribe/get_status；
 * - 收到 status/device_online/device_offline/devices_list/error 更新状态。
 *
 * 连接健壮性借鉴 RelayClient.kt 的成熟经验：generation 会话代次防旧回调污染、
 * 指数退避重连（封顶 30s）、心跳超时判死兜底。
 */
class RelaySwitchClient {

    private var httpClient: OkHttpClient? = null

    /** 主线程 Handler：用于 set 后延迟拉取状态刷新。 */
    private val statusHandler = Handler(Looper.getMainLooper())

    /** 每个设备的状态轮询计数：deviceId -> 已轮询次数（用于 set 后多次 getStatus 兜底）。 */
    private val pollCounts = mutableMapOf<String, Int>()

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
        pollCounts.clear()
        try { retryThread?.interrupt() } catch (e: Exception) { }
        retryThread = null
        RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
        DiagLog.i("智能控制", "继电器客户端已停止")
    }

    fun isRunning(): Boolean = running

    fun isConnected(): Boolean = webSocket != null && running

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
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_SUBSCRIBE)
            put("device_id", deviceId)
        })
    }

    /** 取消订阅设备。 */
    fun unsubscribe(deviceId: String) {
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_UNSUBSCRIBE)
            put("device_id", deviceId)
        })
    }

    /** 请求设备状态。 */
    fun getStatus(deviceId: String) {
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_GET_STATUS)
            put("device_id", deviceId)
        })
    }

    /** 设置继电器（value：0=开，1=关）。发送成功后进入「执行中」态，并轮询 getStatus 兜底刷新。 */
    fun setRelay(deviceId: String, pio: Int, value: Int) {
        val sent = send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_SET)
            put("device_id", deviceId)
            put("pio", pio)
            put("value", value)
        })
        if (!sent) return
        // 标记「执行中」，并启动状态轮询兜底：
        // 服务端 set 后靠设备上报 status 广播，但 ESP32 可能休眠（2~10s 不等），
        // 故首次 500ms 后开始，每隔 2s get_status 一次，最多 8 次（约 16.5s）覆盖最坏休眠；
        // 一旦收到真实 status 即停止轮询并退出「执行中」。
        RelaySwitchState.markPending(deviceId)
        pollCounts[deviceId] = 0
        statusHandler.postDelayed(
            { pollStatus(deviceId) },
            RelaySwitchProtocol.STATUS_REFRESH_DELAY_MS,
        )
        // 兜底超时：即便始终无状态回报，也退出「执行中」，避免卡死。
        statusHandler.postDelayed(
            { RelaySwitchState.clearPending(deviceId) },
            RelaySwitchProtocol.PENDING_TIMEOUT_MS,
        )
    }

    /** 状态轮询一次：getStatus，并按最大次数决定是否继续。 */
    private fun pollStatus(deviceId: String) {
        getStatus(deviceId)
        val count = (pollCounts[deviceId] ?: 0) + 1
        pollCounts[deviceId] = count
        if (count < RelaySwitchProtocol.STATUS_POLL_MAX_COUNT) {
            statusHandler.postDelayed(
                { pollStatus(deviceId) },
                RelaySwitchProtocol.STATUS_POLL_INTERVAL_MS,
            )
        }
    }

    /** 停止某设备的状态轮询（收到真实 status 或连接终止时调用）。 */
    private fun stopPolling(deviceId: String) {
        pollCounts.remove(deviceId)
    }

    /** 拼出 WS URL（server 形如 host:port）。 */
    private fun buildWsUrl(server: String, apiKey: String): String {
        val cleaned = server.removePrefix("ws://").removePrefix("wss://").trim()
        var url = "ws://$cleaned${RelaySwitchProtocol.CLIENT_PATH}"
        if (apiKey.isNotBlank()) {
            url += "?token=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
        }
        return url
    }

    private fun connectOnce(context: Context) {
        val client = httpClient ?: return
        val myGen = generation
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val url = buildWsUrl(server, apiKey)

        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale(): Boolean = !running || generation != myGen

            override fun onOpen(socket: WebSocket, response: Response) {
                if (isStale()) { try { socket.close(1000, "stale") } catch (e: Exception) {}; return }
                webSocket = socket
                lastAliveAt = System.currentTimeMillis()
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.CONNECTED)
                DiagLog.i("智能控制", "已连接继电器服务器")
                // 拉设备列表（走 REST，需 API Key 作 Bearer）
                fetchDevices(context)
                // 订阅当前设备并取状态（照参考项目 onOpen：subscribe + get_status）
                val deviceId = RelaySwitchPrefs.deviceId(context)
                subscribe(deviceId)
                getStatus(deviceId)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (isStale()) return
                lastAliveAt = System.currentTimeMillis()
                handleMessage(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                DiagLog.e("智能控制", "连接异常：${t.message}", t)
                webSocket = null
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
                scheduleReconnect(context)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                DiagLog.i("智能控制", "连接已关闭：code=$code reason=$reason")
                webSocket = null
                RelaySwitchState.updateConnection(RelaySwitchState.Connection.DISCONNECTED)
                if (!intentionalClose) scheduleReconnect(context)
            }
        })
    }

    /** 解析服务器消息并更新状态（照 handleServerMessage 移植）。 */
    private fun handleMessage(text: String) {
        try {
            val data = JSONObject(text)
            when (data.optString("type")) {
                RelaySwitchProtocol.TYPE_STATUS -> {
                    val deviceId = data.optString("device_id")
                    val statusData = data.optJSONObject("data") ?: return
                    val map = mutableMapOf<String, Int>()
                    statusData.keys().forEach { key ->
                        map[key] = statusData.optInt(key)
                    }
                    RelaySwitchState.updateStatus(deviceId, map)
                    // 收到真实状态即退出「执行中」态，并停止轮询兜底
                    RelaySwitchState.clearPending(deviceId)
                    stopPolling(deviceId)
                }
                RelaySwitchProtocol.TYPE_DEVICE_ONLINE -> {
                    val deviceId = data.optString("device_id")
                    val name = data.optString("name").takeIf { it.isNotBlank() }
                    RelaySwitchState.updateDeviceOnline(deviceId, name)
                }
                RelaySwitchProtocol.TYPE_DEVICE_OFFLINE -> {
                    val deviceId = data.optString("device_id")
                    RelaySwitchState.updateDeviceOffline(deviceId)
                }
                RelaySwitchProtocol.TYPE_DEVICES_LIST -> {
                    val arr = data.optJSONArray("devices") ?: return
                    val order = data.optJSONArray("device_order")?.let { orderArr ->
                        (0 until orderArr.length()).map { orderArr.optString(it) }
                    } ?: emptyList()
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
                    RelaySwitchState.updateDevices(list, order)
                }
                RelaySwitchProtocol.TYPE_ERROR -> {
                    val message = data.optString("message", "操作失败")
                    RelaySwitchState.notifyError(message)
                }
            }
        } catch (e: Exception) {
            DiagLog.w("智能控制", "解析消息失败：$text")
        }
    }

    /** 通过 REST 拉设备列表（照 fetchDevicesList 移植）。 */
    private fun fetchDevices(context: Context) {
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val url = "http://$server/api/devices"
        Thread({
            try {
                val builder = Request.Builder().url(url)
                if (apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $apiKey")
                }
                val response = httpClient?.newCall(builder.build())?.execute() ?: return@Thread
                val body = response.body?.string() ?: return@Thread
                val result = JSONObject(body)
                val arr = result.optJSONArray("devices") ?: return@Thread
                val order = result.optJSONArray("device_order")?.let { orderArr ->
                    (0 until orderArr.length()).map { orderArr.optString(it) }
                } ?: emptyList()
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
                RelaySwitchState.updateDevices(list, order)
                DiagLog.i("智能控制", "已拉取设备列表：${list.size} 个")
            } catch (e: Exception) {
                DiagLog.w("智能控制", "拉取设备列表失败：${e.message}")
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
