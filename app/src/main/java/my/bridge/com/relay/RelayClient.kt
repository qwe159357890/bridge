package my.bridge.com.relay

import android.content.Context
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import my.bridge.com.core.common.DiagLog

/**
 * 音频中继客户端（手机放音：主动出站连中转服务器接收 PC 推来的音频）。
 *
 * 后端零改动：复用 Aurelay 中心服务器（039039.xyz:15151）与 WebSocket 路径
 * /api/aurelay/relay。蜂窝网络下手机没有公网入站能力，改为主动出站，
 * 由服务器把 PC 推来的音频字节原样转发过来。
 *
 * 帧约定：**文本帧 = 控制消息（hello / ready / error / ping / pong），
 * 二进制帧 = 音频数据（原样透传的 AURL 字节，本身已是 Opus 压缩结果）**。
 *
 * 心跳 30 秒一次文本 ping；断线按 1/2/4/8/16/30 秒退避重连（可由 RelayPrefs 关闭）。
 */
class RelayClient {

    /** 连接状态 */
    enum class State {
        IDLE,       // 未连接
        CONNECTING, // 正在连接或重连
        READY,      // 已就绪（收到 ready，可收发数据）
        ERROR       // 出错（握手被拒或网络异常）
    }

    /** 状态与数据回调 */
    interface Listener {
        fun onStateChanged(state: State, detail: String)
        fun onAudioData(size: Int)
    }

    var listener: Listener? = null

    // 连接参数（来自 RelayPrefs）
    private var host: String = ""
    private var port: Int = RelayProtocol.DEFAULT_PORT
    private var url: String = ""
    private var deviceId: String = ""
    private var token: String = ""
    private var fallbackIp: String = ""

    @Volatile
    private var running = false

    // 会话代次：每次 start() 自增。所有异步回调（onOpen/onMessage/onFailure/onClosed）
    // 都携带发起连接时的代次，回调落地时若代次已过期则丢弃，避免「停止→启动」后
    // 旧连接的残留回调污染新会话状态。
    @Volatile
    private var generation = 0L

    // 是否允许断线自动重连（由服务层从 RelayPrefs 读入；关闭后断线即彻底停止）
    @Volatile
    private var autoReconnect = true

    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    private var pingThread: Thread? = null
    private var retryThread: Thread? = null

    // 管道：把收到的音频数据转成一条连续的 InputStream 供上层解析
    private var pipeOut: PipedOutputStream? = null
    private var pipeIn: PipedInputStream? = null

    @Volatile
    private var lastAliveAt = 0L

    /**
     * 启动中转连接（已在运行时忽略）。
     *
     * @param context 任意上下文（读取中转服务器地址与令牌）
     * @param autoReconnect 断线是否自动重连（来自 RelayPrefs.KEY_AUTO_RECONNECT）
     */
    @Synchronized
    fun start(context: Context, autoReconnect: Boolean = true) {
        if (running) return
        // 会话代次自增：作废所有旧连接的残留回调
        generation++
        this.autoReconnect = autoReconnect
        val app = context.applicationContext
        val server = RelayPrefs.relayServer(app)
        val cleaned = server.removePrefix("ws://").removePrefix("wss://")
        val separator = cleaned.lastIndexOf(':')
        host = if (separator > 0) cleaned.substring(0, separator) else cleaned
        port = if (separator > 0) cleaned.substring(separator + 1).toIntOrNull() ?: RelayProtocol.DEFAULT_PORT
            else RelayProtocol.DEFAULT_PORT
        // url 必须在 connectOnce() 之前拼好（否则每次连接都抛 "no scheme was found"）
        url = "ws://$host:$port${RelayPrefs.RELAY_PATH}"
        deviceId = RelayPrefs.deviceId(app)
        token = RelayPrefs.reportToken(app)
        fallbackIp = RelayPrefs.relayFallbackIp(app)
        running = true

        // 不在 start 里建管道：管道统一由 onOpen 回调重建，避免与消费线程竞态。
        httpClient = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (e: UnknownHostException) {
                        DiagLog.w("中继", "DNS 解析失败：$hostname，改用兜底 IP $fallbackIp")
                        listOf(InetAddress.getByName(fallbackIp))
                    }
                }
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        pingThread = Thread({ pingLoop() }, "BridgeRelayPing").also { it.start() }
        connectOnce()
        DiagLog.i("中继", "中转客户端启动：$host:$port")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        // 会话代次自增：作废所有在途回调（含 onFailure/onClosed 的重连判断）
        generation++
        closeWebSocket()
        closePipe()
        httpClient = null
        // 彻底中断内部线程，避免心跳/重连线程在 sleep 中空耗（停止后不得再耗资源）
        try { pingThread?.interrupt() } catch (e: Exception) { }
        try { retryThread?.interrupt() } catch (e: Exception) { }
        pingThread = null
        retryThread = null
        DiagLog.i("中继", "中转客户端已停止")
        notifyState(State.IDLE, "已停止")
    }

    fun isRunning(): Boolean = running

    /** 强制重连：主动断开当前 WebSocket 并触发一次全新连接。 */
    @Synchronized
    fun forceReconnect() {
        if (!running) return
        DiagLog.w("中继", "强制重连：丢弃当前管道与旧会话残留")
        closeWebSocket()
        closePipe()
        scheduleReconnect()
    }

    /** 取音频输入流（供上层解析 AURL 流）。 */
    fun audioInput(): InputStream? = synchronized(this) { pipeIn }

    /** 重建音频管道（新连接建立时调用）。 */
    @Synchronized
    private fun rebuildPipe() {
        try { pipeOut?.close() } catch (e: Exception) { }
        val out = PipedOutputStream()
        val ins = PipedInputStream(out, 256 * 1024)
        pipeOut = out
        pipeIn = ins
    }

    /** 关闭并置空音频管道（断线或停止时调用）。 */
    @Synchronized
    private fun closePipe() {
        try { pipeOut?.close() } catch (e: Exception) { }
        pipeOut = null
        pipeIn = null
    }

    fun lastAliveAt(): Long = lastAliveAt

    /** 建立一次 WebSocket 连接。 */
    private fun connectOnce() {
        val client = httpClient ?: return
        val myGen = generation
        notifyState(State.CONNECTING, "正在连接中转服务器")
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            /** 当前回调是否仍属于本代次（过期则丢弃，防止污染新会话）。 */
            private fun isStale(): Boolean = !running || generation != myGen

            override fun onOpen(socket: WebSocket, response: Response) {
                if (isStale()) { try { socket.close(1000, "stale") } catch (e: Exception) {}; return }
                webSocket = socket
                lastAliveAt = System.currentTimeMillis()
                rebuildPipe()
                DiagLog.i("中继", "已连上中转服务器")
                val hello = JSONObject()
                hello.put("type", "hello")
                hello.put("device_id", deviceId)
                hello.put("token", token)
                hello.put("role", "R")
                socket.send(hello.toString())
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (isStale()) return
                lastAliveAt = System.currentTimeMillis()
                handleControl(text)
            }

            override fun onMessage(socket: WebSocket, bytes: okio.ByteString) {
                if (isStale()) return
                lastAliveAt = System.currentTimeMillis()
                val data = bytes.toByteArray()
                try {
                    val out = synchronized(this@RelayClient) { pipeOut }
                    if (out != null) {
                        out.write(data, 0, data.size)
                        out.flush()
                        listener?.onAudioData(data.size)
                    }
                } catch (e: IOException) {
                    DiagLog.w("中继", "音频管道已断开，忽略残余数据帧")
                } catch (e: Exception) {
                    DiagLog.e("中继", "写入音频管道失败", e)
                }
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                DiagLog.e("中继", "中转连接异常：${t.message}", t)
                notifyState(State.ERROR, "连接异常：${t.message ?: "未知"}")
                webSocket = null
                closePipe()
                scheduleReconnect()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                DiagLog.i("中继", "中转连接已关闭：code=$code reason=$reason")
                webSocket = null
                closePipe()
                scheduleReconnect()
            }
        })
    }

    /** 处理文本控制消息（ready / error / pong）。 */
    private fun handleControl(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "ready" -> {
                    notifyState(State.READY, "已接入中转服务器")
                    DiagLog.i("中继", "收到 ready，中转链路就绪")
                }
                "error" -> {
                    val code = obj.optInt("code", -1)
                    val message = obj.optString("message", "")
                    notifyState(State.ERROR, "错误 $code：$message")
                    DiagLog.e("中继", "服务器返回错误：code=$code message=$message")
                }
                "pong" -> { /* 心跳应答，lastAliveAt 已在入口刷新 */ }
                else -> DiagLog.i("中继", "忽略未知控制消息：$text")
            }
        } catch (e: Exception) {
            DiagLog.w("中继", "解析控制消息失败：$text")
        }
    }

    /** 心跳循环：每 30 秒发一次文本 ping。 */
    private fun pingLoop() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(RelayProtocol.PING_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
            if (!running) break
            try {
                webSocket?.send("{\"type\":\"ping\"}")
            } catch (e: Exception) {
                DiagLog.w("中继", "发送心跳失败：${e.message}")
            }
        }
    }

    /** 优雅关闭当前 WebSocket。 */
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

    private fun notifyState(state: State, detail: String) {
        DiagLog.i("中继", "状态：$state · $detail")
        try {
            listener?.onStateChanged(state, detail)
        } catch (e: Exception) {
            DiagLog.e("中继", "通知状态变化失败", e)
        }
    }

    /** 断线后按退避策略重连（1/2/4/8/16/30 秒），可被 RelayPrefs 关闭。 */
    private fun scheduleReconnect() {
        if (!running) return
        // 自动重连关闭时不再重连：断开即进入停止态（用户已在设置中二次确认关闭）
        if (!autoReconnect) {
            DiagLog.i("中继", "自动重连已关闭，断线后不重连")
            return
        }
        val myGen = generation
        val existing = retryThread
        if (existing != null && existing.isAlive) return
        retryThread = Thread({
            var attempt = 0
            while (running && generation == myGen && webSocket == null) {
                val index = attempt.coerceAtMost(RelayProtocol.BACKOFF_MS.size - 1)
                val delay = RelayProtocol.BACKOFF_MS[index]
                attempt++
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    break
                }
                if (!running || generation != myGen || webSocket != null) break
                DiagLog.i("中继", "第 $attempt 次重连")
                connectOnce()
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "BridgeRelayRetry").also { it.start() }
    }
}
