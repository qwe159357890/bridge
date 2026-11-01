package my.bridge.com.wechat

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import my.bridge.com.BuildConfig
import my.bridge.com.core.common.DiagLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 微信（中心服务器）WebSocket 客户端 —— 纯连接层，不做重连策略。
 *
 * 契约照 references/center-server：
 * - 地址 `ws(s)://{host}/ws/client?token={api_key}`（`utils/apiUrl.ts` 第 47~64 行）；
 * - **连上后 5 秒内必须发 register**，否则服务端只记录空设备信息（`api/routes/client.py` 第 606~617 行）；
 * - 心跳 `{type:'ping'}` → `{type:'pong'}`，默认 15s / Pong 超时 60s（`utils/websocket.ts` 第 520~598 行）；
 * - 客户端可发：ping / mark_read / toggle_pin / toggle_mute / clear_session / delete_session；
 * - 下行事件带 `from_client`，与本端 client_id 相同时忽略（去自扰）。
 *
 * 重连策略交给 [WechatRuntime]（固定 2s + 网络恢复即刻重连），本类只负责「连/断/收发/心跳」。
 */
class WechatWsClient {

    interface Listener {
        fun onConnected(clientId: String)
        fun onDisconnected(reason: String)
        fun onIncomingMessage(msg: JSONObject)
        fun onUnreadCleared(sessionId: String, fromClient: String?)
        fun onPinChanged(sessionId: String, pinned: Boolean, fromClient: String?)
        fun onMuteChanged(sessionId: String, muted: Boolean, fromClient: String?)
        fun onSessionUpdate(sessionId: String, fromClient: String?)
        fun onSessionCleared(sessionId: String)
        fun onSessionDeleted(sessionId: String)
        fun onInstanceChange()
        fun onPlaySound()
    }

    companion object {
        private const val TAG = "微信WS"
        // 心跳 60s：服务端 CLIENT_HEARTBEAT_TIMEOUT=300s，留 5 倍余量，服务端零改动即可。
        private const val HEARTBEAT_MS = 60_000L
        // Pong 超时 30s（原 60s）：随间隔放宽同步收紧，把「连接静默死亡」的最长发现窗口
        // 从 60+60=120s 压回 60+30=90s。30s 对任何合理的 WS 往返仍是极大余量。
        private const val PONG_TIMEOUT_MS = 30_000L

        /**
         * REST 地址 → WS 地址（**返回字符串**，不再返回 [okhttp3.HttpUrl]）。
         * 服务端 WS 路由是 `/ws/client`（`api/app.py:133`），鉴权走 query `token`（`api/auth.py:83`）。
         *
         * ⚠️ 致命坑（v0.2.23 修复的真机问题 #1）：**OkHttp 的 `HttpUrl` 只支持 http/https**，
         * jar 里 `HttpUrl.class` 根本搜不到 `wss` 字样，`toHttpUrlOrNull()` 遇到 `ws://`/`wss://`
         * 一律返回 null。老代码拼出 `ws://…` 后直接 `toHttpUrlOrNull()` ⇒ **恒为 null** ⇒
         * `connect()` 第一行就 return false，**从未真正发起过握手**（设置页因此一直显示
         * 「地址无效，无法推导 WS 地址 · 实时推送连接中」，且新消息/通知/提示音全哑）。
         *
         * okhttp 支持 ws 的正确入口是 `Request.Builder.url(String)`——它内部有
         * 「Silently replace web socket URLs with HTTP URLs」的 `ws→http` / `wss→https` 转换。
         * 所以这里只产出字符串，解析交给它；本函数仅用「换回 http 试解析」做一次合法性校验。
         */
        fun toWsUrl(serverUrl: String): String? {
            if (serverUrl.isBlank()) return null
            val s = serverUrl.trim().trimEnd('/')
            val ws = when {
                s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
                s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
                s.startsWith("ws://") || s.startsWith("wss://") -> s
                else -> "ws://$s"
            }
            val raw = ws + "/ws/client"
            val probe = when {
                raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
                raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
                else -> raw
            }
            return if (probe.toHttpUrlOrNull() != null) raw else null
        }

        /** 带鉴权 token 的 WS 地址（连接用；[toWsUrl] 的无参版供设置页展示，不带密钥）。 */
        fun toWsUrl(serverUrl: String, apiKey: String): String? {
            val base = toWsUrl(serverUrl) ?: return null
            val sep = if (base.contains("?")) "&" else "?"
            return base + sep + "token=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 传输层 ping（OkHttp pingInterval）已于 v0.2.21 移除：
        // ① 它对「保活 NAT」与应用层 ping 完全重复，只是多一次无线唤醒；
        // ② WS ping 帧由 uvicorn 在协议层自动回 pong，传不到 ASGI 应用层，
        //    因而**不会刷新** center-server 的 last_ping（client.py:635），
        //    真正防止被 300s 踢掉的只有应用层 ping。
        // 判死改为完全依赖应用层 Pong 看门狗，见 sendPing()。
        .build()

    private val thread = HandlerThread("wechat-ws").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var generation = 0L

    @Volatile
    private var intentionalClose = false

    private var listener: Listener? = null

    private val heartbeat = Runnable { sendPing() }
    private var pongWatchdog: Runnable? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 连接；已在连接/已连上则忽略。返回是否发起了新连接。 */
    fun connect(serverUrl: String, apiKey: String, deviceId: String, deviceName: String): Boolean {
        if (socket != null) return false
        // 【v0.2.23】用带 token 的字符串版地址；Request.Builder.url(String) 内部会把
        // ws/wss 换成 http/https 再交给 HttpUrl 解析（HttpUrl 本身不认 ws，见 toWsUrl 注释）。
        val url = toWsUrl(serverUrl, apiKey) ?: run {
            DiagLog.w(TAG, "服务器地址无法转换为 WS：$serverUrl")
            return false
        }
        intentionalClose = false
        val gen = ++generation
        DiagLog.i(TAG, "连接 WS：$url")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                DiagLog.i(TAG, "WS 已连接，发送 register")
                // 5 秒内必须 register
                sendRegister(deviceId, deviceName)
                scheduleHeartbeat()
                handler.post {
                    listener?.onConnected("")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                DiagLog.w(TAG, "WS 失败：${t.message}")
                teardown()
                listener?.onDisconnected(t.message ?: "连接失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                teardown()
                listener?.onDisconnected("已关闭($code)")
            }
        })
        return true
    }

    fun disconnect() {
        intentionalClose = true
        teardown()
    }

    fun isConnected(): Boolean = socket != null

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    private fun send(obj: JSONObject): Boolean = try {
        socket?.send(obj.toString()) ?: false
        true
    } catch (e: Exception) {
        DiagLog.w(TAG, "WS 发送失败：${e.message}")
        false
    }

    private fun sendRegister(deviceId: String, deviceName: String) {
        val info = JSONObject().apply {
            put("client_type", "bridge")
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("os_info", "Android " + Build.VERSION.RELEASE)
            put("app_version", BuildConfig.VERSION_NAME)
            put("user_agent", "Bridge/" + BuildConfig.VERSION_NAME)
        }
        send(JSONObject().put("type", "register").put("device_info", info))
    }

    private fun sendPing() {
        handler.removeCallbacks(heartbeat)
        // 【修复】无论本次发送成功与否都必须重排下一次心跳。
        // 旧写法 `if (!send(...)) return` 会在发送失败时让 heartbeat 永久停摆
        // （已 removeCallbacks 且不再 postDelayed）——改前有传输层 ping 兜底掩盖，
        // 去掉传输层 ping 后这会导致心跳静默死亡、直到服务端 300s 才踢才被发现。
        val sent = send(JSONObject().put("type", "ping"))
        if (sent) {
            // Pong 超时：PONG_TIMEOUT_MS 内没收到 pong 判定连接已死
            val watchdog = Runnable {
                DiagLog.w(TAG, "Pong 超时，判定连接已死")
                teardown()
                listener?.onDisconnected("Pong 超时")
            }
            pongWatchdog = watchdog
            handler.postDelayed(watchdog, PONG_TIMEOUT_MS)
        } else {
            DiagLog.w(TAG, "心跳发送失败，等待下一栅格重试（不再依赖传输层 ping）")
        }
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    fun markRead(sessionId: String) {
        send(JSONObject().put("type", "mark_read").put("session_id", sessionId))
    }

    fun togglePin(sessionId: String, pinned: Boolean) {
        send(JSONObject().put("type", "toggle_pin").put("session_id", sessionId).put("is_pinned", pinned))
    }

    fun toggleMute(sessionId: String, muted: Boolean) {
        send(JSONObject().put("type", "toggle_mute").put("session_id", sessionId).put("is_muted", muted))
    }

    fun clearSession(sessionId: String) {
        send(JSONObject().put("type", "clear_session").put("session_id", sessionId))
    }

    // ------------------------------------------------------------------
    // 接收
    // ------------------------------------------------------------------

    private fun handleText(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "connected" -> {
                val id = obj.optString("client_id")
                listener?.onConnected(id)
            }
            "pong" -> {
                pongWatchdog?.let { handler.removeCallbacks(it) }
                pongWatchdog = null
            }
            "message" -> listener?.onIncomingMessage(obj)
            "unread_cleared" -> listener?.onUnreadCleared(obj.optString("session_id"), obj.optString("from_client").ifBlank { null })
            "pin_changed" -> listener?.onPinChanged(obj.optString("session_id"), obj.optBoolean("is_pinned", false), obj.optString("from_client").ifBlank { null })
            "mute_changed" -> listener?.onMuteChanged(obj.optString("session_id"), obj.optBoolean("is_muted", false), obj.optString("from_client").ifBlank { null })
            "session_update" -> listener?.onSessionUpdate(obj.optString("session_id"), obj.optString("from_client").ifBlank { null })
            "session_cleared" -> listener?.onSessionCleared(obj.optString("session_id"))
            "session_deleted" -> listener?.onSessionDeleted(obj.optString("session_id"))
            "instance_change" -> listener?.onInstanceChange()
            "play_sound" -> listener?.onPlaySound()
        }
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    /**
     * 距下一个心跳栅格的毫秒数（取值区间 (0, HEARTBEAT_MS]）。
     *
     * 与 Bridge 主 WS（`WsConfig.delayToNextGrid`）使用**同一套栅格**
     * （Unix 毫秒时间的 HEARTBEAT 整数倍），让两条长连接的 ping 落在同一时刻、
     * 共用一次无线唤醒，而不是各自错相触发两次（详见 docs/dev/heartbeat-inventory.md §3.3）。
     * 每次按当前时间重算，可自我纠正累积漂移。
     */
    private fun delayToNextGrid(): Long =
        HEARTBEAT_MS - (System.currentTimeMillis() % HEARTBEAT_MS)

    private fun teardown() {
        handler.removeCallbacks(heartbeat)
        pongWatchdog?.let { handler.removeCallbacks(it) }
        pongWatchdog = null
        generation++
        try {
            socket?.cancel()
        } catch (e: Exception) {
            DiagLog.w(TAG, "关闭 WS 异常：${e.message}")
        }
        socket = null
    }

}
