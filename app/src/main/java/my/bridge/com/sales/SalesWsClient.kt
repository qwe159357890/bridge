package my.bridge.com.sales

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 销售页 WebSocket 客户端。
 *
 * 协议照 references/SalesRecordRemote/web/src/composables/useWebSocket.ts：
 * - 连接 `/ws/after-sales?token=...&device_info=...`，onopen 再发 `{type:'auth',token}`；
 * - 应用层心跳 30s ping，60s 无 pong 判死关闭重连；固定 3s 自动重连；
 * - 支持 online_users / edit_lock / edit_unlock / notification / create-update-delete / force_logout。
 */
class SalesWsClient {

    interface Listener {
        fun onConnectedChanged(connected: Boolean)
        fun onClientId(clientId: String)
        fun onOnlineUsers(users: List<SalesOnlineUser>)
        fun onUserOnline(user: SalesOnlineUser)
        fun onUserOffline(username: String)
        fun onUserDeviceChange(username: String, deviceCount: Int, devices: List<SalesOnlineDevice>)
        fun onEditLockChanged(lockKey: String, lock: SalesEditLock?)
        fun onEditLockConflict(conflict: SalesLockConflict)
        fun onNotification(notification: SalesNotification)
        fun onBusiness(action: String, type: SalesProductType, record: SalesRecord?, recordId: Int?, changes: Map<String, String>)
        fun onForceLogout(message: String)
    }

    companion object {
        private const val TAG = "销售页"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_TIMEOUT_MS = 60_000L
        private const val RECONNECT_INTERVAL_MS = 3_000L
        private const val LOCK_TIMEOUT_MS = 5_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // 传输层 ping 兜底半死连接；业务层仍按参考项目 30s/60s JSON ping/pong
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var intentionalClose = false

    @Volatile
    private var generation = 0L

    @Volatile
    private var listener: Listener? = null

    private var heartbeatRunnable: Runnable? = null
    private var heartbeatTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    private val pendingLocks = mutableMapOf<String, (Boolean) -> Unit>()
    private val pendingLockTimeouts = mutableMapOf<String, Runnable>()

    @Synchronized
    fun connect(context: Context, newListener: Listener) {
        listener = newListener
        val token = SalesPrefs.token(context)
        if (token.isBlank()) return
        if (connected && webSocket != null) {
            listener?.onConnectedChanged(true)
            return
        }
        intentionalClose = false
        generation++
        cancelReconnect()
        open(context, token, generation)
    }

    @Synchronized
    fun disconnect() {
        intentionalClose = true
        generation++
        listener = null
        cancelHeartbeat()
        cancelReconnect()
        clearPendingLocks()
        val ws = webSocket
        webSocket = null
        connected = false
        if (ws != null) {
            runCatching { ws.close(1000, "client closing") }
        }
    }

    fun isConnected(): Boolean = connected && webSocket != null

    fun send(data: JSONObject): Boolean {
        val ws = webSocket ?: return false
        if (!connected) return false
        return runCatching { ws.send(data.toString()) }.getOrDefault(false)
    }

    fun requestOnlineUsers() {
        send(JSONObject().put("type", "online_users"))
    }

    fun unlockEdit(type: SalesProductType, recordId: Int) {
        send(
            JSONObject()
                .put("type", "edit_unlock")
                .put("product_type", type.key)
                .put("record_id", recordId),
        )
    }

    /** 编辑锁：5s 无回执默认成功（照 useWebSocket.lockEdit）。 */
    fun lockEdit(type: SalesProductType, recordId: Int, callback: (Boolean) -> Unit) {
        val key = SalesState.lockKey(type, recordId)
        synchronized(pendingLocks) {
            pendingLockTimeouts.remove(key)?.let { mainHandler.removeCallbacks(it) }
            pendingLocks[key] = callback
            val timeout = Runnable {
                val cb = synchronized(pendingLocks) {
                    pendingLockTimeouts.remove(key)
                    pendingLocks.remove(key)
                }
                cb?.invoke(true)
            }
            pendingLockTimeouts[key] = timeout
            mainHandler.postDelayed(timeout, LOCK_TIMEOUT_MS)
        }
        send(
            JSONObject()
                .put("type", "edit_lock")
                .put("product_type", type.key)
                .put("record_id", recordId),
        )
    }

    private fun open(context: Context, token: String, myGen: Long) {
        val wsBase = SalesPrefs.serverUrl(context).replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
        val deviceInfo = JSONObject().apply {
            put("client_type", "apk")
            put("os", "Android " + android.os.Build.VERSION.RELEASE)
            put("browser", "Bridge")
            put("app_version", my.bridge.com.BuildConfig.VERSION_NAME)
        }
        val url = wsBase + "/ws/after-sales?token=" + URLEncoder.encode(token, "UTF-8") +
            "&device_info=" + URLEncoder.encode(deviceInfo.toString(), "UTF-8")
        DiagLog.i(TAG, "连接销售 WS：$wsBase/ws/after-sales")
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            private fun stale(): Boolean = intentionalClose || generation != myGen

            override fun onOpen(socket: WebSocket, response: Response) {
                if (stale()) {
                    runCatching { socket.close(1000, "stale") }
                    return
                }
                webSocket = socket
                connected = true
                listener?.onConnectedChanged(true)
                send(JSONObject().put("type", "auth").put("token", token))
                requestOnlineUsers()
                startHeartbeat(myGen)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (stale()) return
                handleMessage(context, text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (stale()) return
                DiagLog.w(TAG, "销售 WS 异常：${t.message}")
                markDisconnected(context, myGen, scheduleReconnect = true)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (stale()) return
                DiagLog.i(TAG, "销售 WS 关闭：code=$code reason=$reason")
                if (code == 4001) {
                    listener?.onForceLogout("登录已过期，请重新登录")
                    markDisconnected(context, myGen, scheduleReconnect = false)
                } else {
                    markDisconnected(context, myGen, scheduleReconnect = true)
                }
            }
        })
    }

    private fun markDisconnected(context: Context, myGen: Long, scheduleReconnect: Boolean) {
        connected = false
        webSocket = null
        cancelHeartbeat()
        clearPendingLocks()
        listener?.onConnectedChanged(false)
        if (scheduleReconnect && !intentionalClose && generation == myGen) {
            scheduleReconnect(context)
        }
    }

    private fun startHeartbeat(myGen: Long) {
        cancelHeartbeat()
        val ping = object : Runnable {
            override fun run() {
                if (intentionalClose || generation != myGen) return
                send(JSONObject().put("type", "ping"))
                scheduleHeartbeatTimeout(myGen)
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = ping
        mainHandler.postDelayed(ping, HEARTBEAT_INTERVAL_MS)
    }

    private fun scheduleHeartbeatTimeout(myGen: Long) {
        heartbeatTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (intentionalClose || generation != myGen) return@Runnable
            DiagLog.w(TAG, "销售 WS 心跳超时，主动关闭重连")
            runCatching { webSocket?.close(1000, "heartbeat timeout") }
        }
        heartbeatTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, HEARTBEAT_TIMEOUT_MS)
    }

    private fun cancelHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatTimeoutRunnable = null
    }

    private fun scheduleReconnect(context: Context) {
        if (reconnectRunnable != null) return
        val myGen = generation
        val task = Runnable {
            reconnectRunnable = null
            if (intentionalClose || generation != myGen) return@Runnable
            val token = SalesPrefs.token(context)
            if (token.isNotBlank()) open(context, token, generation)
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, RECONNECT_INTERVAL_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun clearPendingLocks() {
        synchronized(pendingLocks) {
            pendingLockTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
            pendingLockTimeouts.clear()
            val callbacks = pendingLocks.values.toList()
            pendingLocks.clear()
            callbacks.forEach { it(false) }
        }
    }

    private fun resolveLock(lockKey: String, success: Boolean) {
        val callback = synchronized(pendingLocks) {
            pendingLockTimeouts.remove(lockKey)?.let { mainHandler.removeCallbacks(it) }
            pendingLocks.remove(lockKey)
        }
        callback?.invoke(success)
    }

    private fun handleMessage(context: Context, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = obj.optString("type").ifBlank { obj.optString("action") }
        when (type) {
            "pong" -> {
                heartbeatTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                heartbeatTimeoutRunnable = null
            }
            "client_id" -> {
                val clientId = obj.optString("client_id")
                SalesPrefs.setClientId(context, clientId)
                listener?.onClientId(clientId)
            }
            "online_users" -> listener?.onOnlineUsers(parseOnlineUsers(obj.optJSONArray("users")))
            "user_online" -> listener?.onUserOnline(parseOnlineUser(obj))
            "user_offline" -> listener?.onUserOffline(obj.optString("username"))
            "user_device_change" -> listener?.onUserDeviceChange(
                obj.optString("username"),
                obj.optInt("device_count", 1),
                parseDevices(obj.optJSONArray("devices")),
            )
            "edit_locked" -> {
                val key = obj.optString("lock_key")
                listener?.onEditLockChanged(
                    key,
                    SalesEditLock(
                        username = obj.optString("username"),
                        display_name = obj.optString("display_name"),
                        role = obj.optString("role"),
                        locked_at = obj.optString("locked_at"),
                    ),
                )
            }
            "edit_unlocked" -> listener?.onEditLockChanged(obj.optString("lock_key"), null)
            "edit_lock_result" -> {
                val key = obj.optString("lock_key")
                val success = obj.optBoolean("success", false)
                resolveLock(key, success)
                if (!success) listener?.onEditLockConflict(parseConflict(obj))
            }
            "edit_lock_conflict" -> listener?.onEditLockConflict(parseConflict(obj))
            "notification" -> listener?.onNotification(
                SalesNotification(
                    username = obj.optString("username"),
                    display_name = obj.optString("display_name"),
                    action = obj.optString("action"),
                    product_type = obj.optString("product_type"),
                    record_id = obj.opt("record_id")?.toString()?.toIntOrNull(),
                ),
            )
            "force_logout" -> {
                intentionalClose = true
                cancelHeartbeat()
                cancelReconnect()
                connected = false
                listener?.onConnectedChanged(false)
                listener?.onForceLogout(obj.optString("message", "您已被强制下线"))
                runCatching { webSocket?.close(1000, "force logout") }
                webSocket = null
            }
            "create", "update", "delete" -> {
                val productType = SalesProductType.fromKey(obj.optString("product_type"))
                val record = obj.optJSONObject("record")?.let(::parseRecord)
                val recordId = when {
                    obj.has("record_id") -> obj.opt("record_id")?.toString()?.toIntOrNull()
                    else -> null
                }
                val changes = mutableMapOf<String, String>()
                obj.optJSONObject("changes")?.let { changesObj ->
                    changesObj.keys().forEach { key -> changes[key] = valueToCellString(changesObj.opt(key)) }
                }
                listener?.onBusiness(type, productType, record, recordId, changes)
            }
        }
    }

    private fun parseConflict(obj: JSONObject): SalesLockConflict = SalesLockConflict(
        lock_key = obj.optString("lock_key"),
        locked_by = obj.optString("locked_by"),
        locked_by_display_name = obj.optString("locked_by_display_name"),
        role = obj.optString("role").ifBlank { obj.optString("locked_by_role") },
        message = obj.optString("message"),
    )

    private fun parseOnlineUsers(arr: JSONArray?): List<SalesOnlineUser> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseOnlineUser) }
    }

    private fun parseOnlineUser(obj: JSONObject): SalesOnlineUser = SalesOnlineUser(
        username = obj.optString("username"),
        display_name = obj.optString("display_name"),
        role = obj.optString("role"),
        connected_at = obj.optString("connected_at"),
        device_count = obj.optInt("device_count", 1),
        devices = parseDevices(obj.optJSONArray("devices")),
    )

    private fun parseDevices(arr: JSONArray?): List<SalesOnlineDevice> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SalesOnlineDevice(
                    client_type = it.optString("client_type"),
                    os = it.optString("os"),
                    browser = it.optString("browser"),
                    connected_at = it.optString("connected_at"),
                )
            }
        }
    }

    private fun parseRecord(obj: JSONObject): SalesRecord = SalesRecord(
        id = obj.optInt("id"),
        serial = obj.optString("serial"),
        shop_serial = obj.optString("shop_serial"),
        date = obj.optString("date"),
        receiver = obj.optString("receiver"),
        phone = obj.optString("phone"),
        goods = obj.optString("goods"),
        order_no = obj.optString("order_no"),
        send_express = obj.optString("send_express"),
        return_express = obj.optString("return_express"),
        return_express_company = obj.optString("return_express_company"),
        return_freight = obj.optDouble("return_freight"),
        signed = obj.optString("signed", "未签收"),
        picked_up = obj.optInt("picked_up"),
        remarks = obj.optString("remarks"),
        created_at = obj.optString("created_at"),
        row_color = obj.optString("row_color"),
    )

    private fun valueToCellString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is Double -> if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        is Float -> if (value % 1.0f == 0.0f) value.toInt().toString() else value.toString()
        else -> value.toString()
    }
}
