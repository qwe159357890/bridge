package my.bridge.com.core.network.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Process
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.random.Random

/**
 * Bridge WebSocket 客户端。
 *
 * 负责与服务端 `/ws` 的长连接全生命周期，与后端协议一一对应：
 *   - 握手：连接后首条消息 hello（携带 device_id + 可选 token + 可选昵称）；
 *   - 心跳：每 [WsConfig.HEARTBEAT_INTERVAL_MS] 发 ping，超时未收 pong 判断线；
 *   - 断线检测 + 指数退避重连（含 ±20% 抖动）；
 *   - 顶旧/踢下线：收到 close 且 reason=replaced/server_kick 时停止重连；
 *   - 命令：收到 command 消息 → 查注册表执行 → command_result 回执。
 *
 * 对外暴露 [state]（连接状态）与 [listener]（推送回调）。
 *
 * @param okHttpClient 共享的 OkHttpClient（统一超时/日志策略）
 * @param gson JSON 序列化器
 * @param deviceIdProvider 设备标识提供者（ANDROID_ID，惰性读取避免初始化时序耦合）
 * @param tokenProvider token 提供者（可为空，auth.enabled=false 时允许无 token）
 * @param deviceNameProvider 设备昵称提供者（可为空，缺省用 device_id）
 * @param uptimeSecondsProvider 进程运行时长提供者（秒，可为空，缺省用系统计算）
 * @param onBeforeHello 每次连接成功、发送 hello 之前调用的钩子（挂起）。用于「注册设备」
 *   确保服务端注册表与连接同步——服务端重启后注册表清空，重连时借此自动补注册。
 * @param listener 推送回调（可选）
 * @param commandExecutors 命令执行器表（command 名 -> 执行器）
 */
class BridgeWebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val deviceIdProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val deviceNameProvider: () -> String? = { null },
    private val uptimeSecondsProvider: () -> Long? = { null },
    private val onBeforeHello: (suspend () -> Unit)? = null,
    private val listener: WsMessageListener? = null,
    private val commandExecutors: Map<String, CommandExecutor> = emptyMap(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    /** 当前等待 pong 的 CompletableDeferred（收到 pong 时 complete）。 */
    private val pongSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile
    private var reconnectAttempt: Int = 0

    private var connectJob: Job? = null

    /** 是否被顶旧/踢下线/主动停止（停止自动重连）。 */
    @Volatile
    private var shouldStop: Boolean = false

    /** 启动连接（幂等：已连接或正在连接则忽略）。 */
    fun start() {
        if (connectJob?.isActive == true) return
        shouldStop = false
        reconnectAttempt = 0
        connectJob = scope.launch { connectLoop() }
    }

    /** 主动停止：关闭当前连接并停止重连。 */
    fun stop() {
        shouldStop = true
        connectJob?.cancel()
        connectJob = null
        try {
            webSocket?.close(1000, "client stop")
        } catch (e: Exception) {
            Timber.w(e, "关闭 WebSocket 失败")
        }
        webSocket = null
        pongSignal.getAndSet(null)?.complete(Unit)
        _state.value = WsConnectionState.STOPPED
    }

    /** 服务地址变更后重连：关闭当前连接，按新地址重新连接。 */
    fun reconnect() {
        Timber.i("服务地址已更新，触发重连：${ServerAddress.wsUrl()}")
        // 关闭旧连接（不设 shouldStop，允许自动重连）
        connectJob?.cancel()
        connectJob = null
        try {
            webSocket?.close(1000, "server address changed")
        } catch (e: Exception) {
            Timber.w(e, "关闭旧 WebSocket 失败")
        }
        webSocket = null
        pongSignal.getAndSet(null)?.complete(Unit)
        // 重置退避，重新开始连接循环
        shouldStop = false
        reconnectAttempt = 0
        connectJob = scope.launch { connectLoop() }
    }

    /**
     * 连接主循环：连接 → 读消息直到断线 → 按退避重连。
     */
    private suspend fun connectLoop() {
        while (scope.isActive && !shouldStop) {
            connectOnce()
            if (shouldStop) break

            // 指数退避 + 抖动
            val backoff = backoffDelay(reconnectAttempt)
            _state.value = WsConnectionState.RECONNECTING
            Timber.d("重连等待 ${backoff}ms（第 ${reconnectAttempt + 1} 次）")
            delay(backoff)
            reconnectAttempt++
        }
    }

    /**
     * 单次连接尝试：阻塞直到连接结束（onClosed / onFailure）。
     */
    private suspend fun connectOnce() {
        _state.value = WsConnectionState.CONNECTING

        val request = Request.Builder().url(ServerAddress.wsUrl()).build()
        val closed = CompletableDeferred<Unit>()

        val listenerImpl = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@BridgeWebSocketClient.webSocket = webSocket
                _state.value = WsConnectionState.CONNECTED
                reconnectAttempt = 0
                // 连接成功：先执行「注册设备」钩子（幂等），确保服务端注册表与本连接同步，
                // 再发送 hello 握手。注册失败不阻断连接（auth 关闭时允许无 token）。
                scope.launch {
                    try {
                        onBeforeHello?.invoke()
                    } catch (e: Exception) {
                        Timber.w(e, "连接前注册钩子失败（继续握手）")
                    }
                    sendHello(webSocket)
                    launchHeartbeat(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.i("WebSocket 已关闭：code=$code reason=$reason")
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w("WebSocket 连接失败/断开：${t.message}")
                closed.complete(Unit)
            }
        }

        okHttpClient.newWebSocket(request, listenerImpl)
        closed.await()
        this@BridgeWebSocketClient.webSocket = null
        pongSignal.getAndSet(null)?.complete(Unit)
    }

    /** 发送 hello 握手消息。 */
    private fun sendHello(webSocket: WebSocket) {
        val data = JsonObject().apply {
            addProperty("device_id", deviceIdProvider())
            tokenProvider()?.let { addProperty("token", it) }
            deviceNameProvider()?.let { addProperty("device_name", it) }
            // 上报进程运行时长（秒），供管理后台展示「APP 已运行 XX」
            val uptime = uptimeSecondsProvider() ?: defaultUptimeSeconds()
            addProperty("uptime_seconds", uptime)
        }
        val envelope = WsEnvelope.make(WsEnvelope.TYPE_HELLO, data)
        webSocket.send(envelope.toJson(gson))
        Timber.i("已发送 hello（device=${data["device_id"]}）")
    }

    /** 默认的进程运行时长：基于进程启动到现在的耗时（秒）。 */
    private fun defaultUptimeSeconds(): Long {
        // Process.getStartElapsedRealtime() 返回进程启动时刻的 elapsedRealtime（API 24+）
        return (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
    }

    /** 心跳循环：周期发 ping，超时未收 pong 则主动断开触发重连。 */
    private fun launchHeartbeat(webSocket: WebSocket) {
        scope.launch {
            while (isActive && !shouldStop && webSocket == this@BridgeWebSocketClient.webSocket) {
                delay(WsConfig.HEARTBEAT_INTERVAL_MS)
                if (webSocket != this@BridgeWebSocketClient.webSocket) break

                val deferred = CompletableDeferred<Unit>()
                pongSignal.set(deferred)
                webSocket.send(WsEnvelope.make(WsEnvelope.TYPE_PING).toJson(gson))

                val gotPong = withTimeoutOrNull(WsConfig.PONG_TIMEOUT_MS) {
                    deferred.await()
                } != null

                if (!gotPong && webSocket == this@BridgeWebSocketClient.webSocket) {
                    Timber.w("ping 超时未收到 pong，主动断开重连")
                    webSocket.close(1000, "pong timeout")
                    break
                }
                pongSignal.compareAndSet(deferred, null)
            }
        }
    }

    /** 处理入站消息。 */
    private fun handleMessage(text: String) {
        val envelope = WsEnvelope.fromJson(gson, text) ?: run {
            Timber.d("忽略非法消息：$text")
            return
        }
        when (envelope.type) {
            WsEnvelope.TYPE_PONG -> {
                pongSignal.getAndSet(null)?.complete(Unit)
            }
            WsEnvelope.TYPE_CLOSE -> {
                val reason = envelope.data?.get("reason")?.asString
                Timber.i("收到服务端 close：reason=$reason")
                // 被顶旧或踢下线 → 停止重连
                if (reason == "replaced" || reason == "server_kick") {
                    shouldStop = true
                }
                webSocket?.close(1000, "server close")
            }
            WsEnvelope.TYPE_MESSAGE -> {
                Timber.d("收到推送：${envelope.data}")
                try {
                    listener?.onMessage(envelope.data)
                } catch (e: Exception) {
                    Timber.w(e, "推送回调异常")
                }
            }
            WsEnvelope.TYPE_COMMAND -> {
                handleCommand(envelope.data)
            }
            else -> {
                Timber.d("忽略消息类型：${envelope.type}")
            }
        }
    }

    /** 处理服务端下发的命令：查注册表执行 → 回执。 */
    private fun handleCommand(data: JsonObject?) {
        val command = Command.fromData(data)
        if (command == null) {
            Timber.w("收到非法命令消息：$data")
            return
        }
        val executor = commandExecutors[command.command]
        if (executor == null) {
            Timber.w("未注册的命令：${command.command}")
            sendCommandResult(
                CommandResult(command.commandId, command.command, false, "不支持的命令：${command.command}")
            )
            return
        }
        scope.launch {
            val result = try {
                executor.execute(command)
            } catch (e: Exception) {
                Timber.e(e, "命令执行异常：${command.command}")
                CommandResult(command.commandId, command.command, false, "执行异常：${e.message}")
            }
            sendCommandResult(result)
        }
    }

    /** 发送命令执行回执。 */
    private fun sendCommandResult(result: CommandResult) {
        val envelope = WsEnvelope.make(WsEnvelope.TYPE_COMMAND_RESULT, result.toJsonObject())
        webSocket?.send(envelope.toJson(gson))
        Timber.i("命令回执：${result.command} success=${result.success} ${result.message}")
    }

    /** 计算指数退避 + 抖动。 */
    private fun backoffDelay(attempt: Int): Long {
        val exp = WsConfig.RECONNECT_BASE_MS * 2.0.pow(attempt.coerceAtLeast(0)).toLong()
        val capped = exp.coerceAtMost(WsConfig.RECONNECT_MAX_MS)
        val jitter = 1 + Random.nextDouble(-WsConfig.RECONNECT_JITTER, WsConfig.RECONNECT_JITTER)
        return (capped * jitter).toLong()
    }
}
