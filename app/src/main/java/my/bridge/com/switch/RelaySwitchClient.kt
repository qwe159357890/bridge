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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 智能控制（ESP32 继电器）WebSocket 客户端。
 *
 * 主动出站连接参考项目中继服务器（方案 A：服务端零改动）。
 * **交互流程严格照 references/relay-control/src/main.ts 逐函数移植**：
 *
 * | 参考（main.ts）            | 本类                          |
 * |---------------------------|-------------------------------|
 * | initApp()                 | [start]：先 REST 拉设备列表，再连 WS |
 * | connectWebSocket()        | [connectOnce]                 |
 * | ws.onopen                 | onOpen：fetchDevices → 按模式 subscribe + get_status |
 * | handleServerMessage()     | [handleMessage]：status / device_online / device_offline / devices_list / error |
 * | sendRelayCommandForDevice | [setRelay]：只发 set + Toast，UI 等设备上报 status 驱动 |
 * | togglePaneMode()          | [onPaneModeChanged]：窗格订阅全部；单设备退订其它 |
 * | pane-card click           | [onDeviceSelected]：选中设备并切单卡，退订其它 + 订阅当前 |
 * | scheduleReconnect()       | 固定 3 秒重连（RECONNECT_INTERVAL_MS） |
 *
 * 显示状态不在此层做中间态（无「执行中」/轮询/离线宽限）——
 * 完全由 RelaySwitchState 的 connected + online + statusCache 推导，与参考一致。
 */
class RelaySwitchClient {

    private var httpClient: OkHttpClient? = null

    /** 主线程 Handler：仅用于固定间隔重连定时（对应参考的 reconnectTimer）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var running = false

    /** 对应参考的 intentionalClose：主动断开时不自动重连。 */
    @Volatile
    private var intentionalClose = false

    /** 会话代次：每次 start 自增，作废旧连接的残留回调。 */
    @Volatile
    private var generation = 0L

    /** 当前挂起的重连定时任务（对应参考的 reconnectTimer），null 表示无。 */
    private var pendingReconnect: Runnable? = null

    // ------------------------------------------------------------------
    // 生命周期（对应 initApp / disconnectWebSocket）
    // ------------------------------------------------------------------

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
            // 协议层 ping 保活：浏览器 WS 自带，OkHttp 需显式开启，否则移动网络下半死连接无法察觉
            .pingInterval(RelaySwitchProtocol.WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

        // 照 initApp：先拉设备列表，再连 WS（connect 内部 onOpen 还会再拉一次并订阅）
        fetchDevices(app)
        connectOnce(app)
        DiagLog.i("智能控制", "继电器客户端启动")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        intentionalClose = true
        generation++
        cancelReconnect()
        closeWebSocket()
        httpClient = null
        RelaySwitchState.setConnected(false)
        DiagLog.i("智能控制", "继电器客户端已停止")
    }

    fun isRunning(): Boolean = running

    /** 设置保存后整体重连（照参考 save-settings：disconnectWebSocket → connectWebSocket）。 */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    // ------------------------------------------------------------------
    // 发送消息（对应 ws.send 调用点）
    // ------------------------------------------------------------------

    /** 发送一条客户端消息；返回是否成功入队。 */
    private fun send(msg: JSONObject): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(msg.toString())
        } catch (e: Exception) {
            DiagLog.w("智能控制", "发送消息失败：${e.message}")
            false
        }
    }

    private fun sendSubscribe(deviceId: String) {
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_SUBSCRIBE)
            put("device_id", deviceId)
        })
    }

    private fun sendUnsubscribe(deviceId: String) {
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_UNSUBSCRIBE)
            put("device_id", deviceId)
        })
    }

    private fun sendGetStatus(deviceId: String) {
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_GET_STATUS)
            put("device_id", deviceId)
        })
    }

    /**
     * 设置继电器（对应 sendRelayCommandForDevice）。
     *
     * value：0=开，1=关。**只发 set 并 Toast 提示，不本地改状态**——
     * UI 最终由设备执行后上报的 status 广播驱动刷新（与参考完全一致）。
     */
    fun setRelay(deviceId: String, pio: Int, value: Int) {
        // 对应参考的 ws.readyState === WebSocket.OPEN 判断
        if (webSocket == null || !RelaySwitchState.state.connected) {
            RelaySwitchState.toast("未连接到服务器")
            return
        }
        send(JSONObject().apply {
            put("type", RelaySwitchProtocol.TYPE_SET)
            put("device_id", deviceId)
            put("pio", pio)
            put("value", value)
        })
        DiagLog.i("智能控制", "setRelay device=$deviceId pio=$pio value=$value")
        RelaySwitchState.toast(if (value == 0) "正在开启..." else "正在关闭...")
    }

    /** 手动刷新当前设备状态（对应设置弹窗的「刷新」按钮 refresh-status）。 */
    fun refreshCurrent(context: Context) {
        if (webSocket == null || !RelaySwitchState.state.connected) {
            RelaySwitchState.toast("未连接到服务器")
            return
        }
        sendGetStatus(RelaySwitchPrefs.deviceId(context))
        RelaySwitchState.toast("正在刷新...")
    }

    /**
     * 窗格模式切换（对应 togglePaneMode 的 WS 部分）。
     *
     * - 切入窗格：对**所有设备** subscribe + get_status；
     * - 切回单设备：退订其它设备，只保留当前设备，并对当前设备 get_status。
     */
    fun onPaneModeChanged(context: Context, paneMode: Boolean) {
        if (webSocket == null || !RelaySwitchState.state.connected) return
        val devices = RelaySwitchState.state.devices
        if (paneMode) {
            devices.forEach { device ->
                sendSubscribe(device.id)
                sendGetStatus(device.id)
            }
        } else {
            val currentId = RelaySwitchPrefs.deviceId(context)
            devices.forEach { device ->
                if (device.id != currentId) sendUnsubscribe(device.id)
            }
            sendGetStatus(currentId)
        }
        DiagLog.i("智能控制", "切换窗格模式 paneMode=$paneMode")
    }

    /**
     * 窗格模式点击卡片选中设备（对应 pane-card click 的 WS 部分）：
     * 退订其它设备 + 订阅并拉取当前设备状态。deviceId 的持久化由 UI 层完成。
     */
    fun onDeviceSelected(context: Context, deviceId: String) {
        if (webSocket == null || !RelaySwitchState.state.connected) return
        RelaySwitchState.state.devices.forEach { device ->
            if (device.id != deviceId) sendUnsubscribe(device.id)
        }
        sendSubscribe(deviceId)
        sendGetStatus(deviceId)
        DiagLog.i("智能控制", "选中设备 device=$deviceId")
    }

    // ------------------------------------------------------------------
    // 地址归一化 + 连接
    // ------------------------------------------------------------------

    /**
     * 解析服务器地址为 (httpBase, wsBase)。
     *
     * 参考用法：GET `${serverUrl}/api/devices`、WS `serverUrl.replace(/^http/,'ws')+'/ws/client'`，
     * 默认 serverUrl 是带协议前缀的完整 URL（http://localhost:10888）。
     * 这里兼容四种写法：`http://host`、`https://host`、`host:port`、`//host`。
     */
    private fun resolveServer(raw: String): Pair<String, String> {
        var s = raw.trim().trimEnd('/')
        val secure: Boolean
        when {
            s.startsWith("https://", ignoreCase = true) -> { secure = true; s = s.drop(8) }
            s.startsWith("http://", ignoreCase = true) -> { secure = false; s = s.drop(7) }
            s.startsWith("//") -> { secure = false; s = s.drop(2) }
            else -> secure = false
        }
        if (s.isBlank()) s = RelaySwitchProtocol.DEFAULT_SERVER
        val hostPort = s.trimEnd('/')
        return (if (secure) "https://" else "http://") + hostPort to
            (if (secure) "wss://" else "ws://") + hostPort
    }

    private fun buildWsUrl(server: String, apiKey: String): String {
        val (_, wsBase) = resolveServer(server)
        var url = "$wsBase${RelaySwitchProtocol.CLIENT_PATH}"
        if (apiKey.isNotBlank()) {
            url += "?token=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
        }
        return url
    }

    /** 建立一次 WS 连接（对应 connectWebSocket）。 */
    private fun connectOnce(context: Context) {
        val client = httpClient ?: return
        val myGen = generation
        cancelReconnect()
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val url = buildWsUrl(server, apiKey)

        DiagLog.i("智能控制", "连接 WS url=$url")
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            private fun isStale(): Boolean = !running || generation != myGen

            override fun onOpen(socket: WebSocket, response: Response) {
                if (isStale()) { try { socket.close(1000, "stale") } catch (e: Exception) {} ; return }
                webSocket = socket
                RelaySwitchState.setConnected(true)
                DiagLog.i("智能控制", "已连接继电器服务器")
                // 照 ws.onopen：先 await 设备列表，再按模式订阅 + 逐个 get_status
                fetchDevices(context) { subscribeByMode(context) }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (isStale()) return
                handleMessage(context, text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                DiagLog.e("智能控制", "连接异常：${t.message}", t)
                webSocket = null
                // 照 ws.onclose：setConnected(false)，UI 随即显示「等待连接」
                RelaySwitchState.setConnected(false)
                scheduleReconnect(context)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                DiagLog.i("智能控制", "连接已关闭：code=$code reason=$reason")
                webSocket = null
                RelaySwitchState.setConnected(false)
                if (!intentionalClose) scheduleReconnect(context)
            }
        })
    }

    /**
     * 按当前模式订阅（对应 ws.onopen 的后半段）：
     * 窗格模式订阅所有设备；单设备模式只订阅当前设备。每个订阅都紧跟一次 get_status。
     */
    private fun subscribeByMode(context: Context) {
        if (webSocket == null) return
        val devices = RelaySwitchState.state.devices
        if (RelaySwitchPrefs.paneMode(context)) {
            devices.forEach { device ->
                sendSubscribe(device.id)
                sendGetStatus(device.id)
            }
            DiagLog.i("智能控制", "窗格模式：订阅全部 ${devices.size} 台")
        } else {
            val currentId = RelaySwitchPrefs.deviceId(context)
            sendSubscribe(currentId)
            sendGetStatus(currentId)
            DiagLog.i("智能控制", "单设备模式：订阅当前 device=$currentId")
        }
    }

    /** 固定间隔重连（对应 scheduleReconnect：reconnectInterval=3000ms，固定不退避）。 */
    private fun scheduleReconnect(context: Context) {
        if (!running || intentionalClose) return
        cancelReconnect()
        val myGen = generation
        val task = Runnable {
            pendingReconnect = null
            if (!running || intentionalClose || generation != myGen) return@Runnable
            if (webSocket != null) return@Runnable
            DiagLog.i("智能控制", "断线重连")
            connectOnce(context)
        }
        pendingReconnect = task
        mainHandler.postDelayed(task, RelaySwitchProtocol.RECONNECT_INTERVAL_MS)
    }

    private fun cancelReconnect() {
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
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

    // ------------------------------------------------------------------
    // 消息处理（严格对照 handleServerMessage 五个分支）
    // ------------------------------------------------------------------

    private fun handleMessage(context: Context, text: String) {
        try {
            val data = JSONObject(text)
            when (data.optString("type")) {
                // 对应 case 'status'：更新状态缓存，UI 自动按当前模式刷新
                RelaySwitchProtocol.TYPE_STATUS -> {
                    val deviceId = data.optString("device_id")
                    val statusData = data.optJSONObject("data") ?: return
                    val map = mutableMapOf<String, Int>()
                    statusData.keys().forEach { key -> map[key] = statusData.optInt(key) }
                    RelaySwitchState.updateStatus(deviceId, map)
                    DiagLog.i("智能控制", "收到 status device=$deviceId data=$map")
                }
                // 对应 case 'device_online'
                RelaySwitchProtocol.TYPE_DEVICE_ONLINE -> {
                    val deviceId = data.optString("device_id")
                    val name = data.optString("name").takeIf { it.isNotBlank() }
                    RelaySwitchState.updateDeviceOnline(deviceId, name)
                    DiagLog.i("智能控制", "设备上线 device=$deviceId name=$name")
                    if (RelaySwitchPrefs.paneMode(context)) {
                        // 窗格模式：订阅新上线的设备并获取状态
                        sendSubscribe(deviceId)
                        sendGetStatus(deviceId)
                    } else if (deviceId == RelaySwitchPrefs.deviceId(context)) {
                        // 单设备模式且是当前设备：拉一次状态
                        sendGetStatus(deviceId)
                    }
                }
                // 对应 case 'device_offline'：标记离线 + 删除状态缓存（UI 随即显示等待连接）
                RelaySwitchProtocol.TYPE_DEVICE_OFFLINE -> {
                    val deviceId = data.optString("device_id")
                    RelaySwitchState.updateDeviceOffline(deviceId)
                    DiagLog.i("智能控制", "设备离线 device=$deviceId")
                }
                // 对应 case 'devices_list'：更新设备列表缓存
                RelaySwitchProtocol.TYPE_DEVICES_LIST -> {
                    val arr = data.optJSONArray("devices") ?: return
                    val order = parseOrder(data)
                    val list = parseDeviceArray(arr)
                    RelaySwitchState.updateDevices(list, order)
                    DiagLog.i("智能控制", "收到设备列表 ${list.size} 个")
                }
                // 对应 case 'error'：Toast 提示
                RelaySwitchProtocol.TYPE_ERROR -> {
                    val message = data.optString("message", "操作失败")
                    DiagLog.w("智能控制", "收到服务器 error: $message")
                    RelaySwitchState.toast(message)
                }
            }
        } catch (e: Exception) {
            DiagLog.w("智能控制", "解析消息失败：$text")
        }
    }

    // ------------------------------------------------------------------
    // REST 拉设备列表（对应 fetchDevicesList）
    // ------------------------------------------------------------------

    private fun fetchDevices(context: Context, onDone: (() -> Unit)? = null) {
        val server = RelaySwitchPrefs.server(context)
        val apiKey = RelaySwitchPrefs.apiKey(context)
        val (httpBase, _) = resolveServer(server)
        val url = "$httpBase${RelaySwitchProtocol.DEVICES_API_PATH}"
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
                val list = parseDeviceArray(arr)
                RelaySwitchState.updateDevices(list, parseOrder(result))
                DiagLog.i("智能控制", "已拉取设备列表 url=$url 共 ${list.size} 个")
            } catch (e: Exception) {
                DiagLog.w("智能控制", "拉取设备列表失败 url=$url：${e.message}")
            } finally {
                onDone?.let { cb -> mainHandler.post { cb() } }
            }
        }, "BridgeSwitchFetch").start()
    }

    private fun parseDeviceArray(arr: JSONArray): List<RelaySwitchState.Device> {
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

    private fun parseOrder(data: JSONObject): List<String> =
        data.optJSONArray("device_order")?.let { orderArr ->
            (0 until orderArr.length()).map { orderArr.optString(it) }
        } ?: emptyList()
}
