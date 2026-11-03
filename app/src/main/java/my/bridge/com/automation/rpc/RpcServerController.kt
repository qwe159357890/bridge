package my.bridge.com.automation.rpc

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import my.bridge.com.core.network.ws.Command
import my.bridge.com.core.network.ws.CommandExecutor
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Bridge Python RPC 服务器（v0.2.39）。
 *
 * 手机内嵌 HTTP/REST 服务，供 Python 脚本按需连入驱动自动化（通讯方向与 Droidline
 * 相反：Droidline 是手机连出，这里 Python 连入）。按需拉起（rpc_start/rpc_stop），
 * token 鉴权（安全红线）。
 *
 * 实现：手写极简 HTTP/1.1 server（ServerSocket + 线程池），零外部依赖、专为 Android
 * 精简——只支持 GET/POST + query/JSON body + JSON/二进制响应，足以覆盖 Python 脚本
 * 驱动自动化的全部需求（Android 无 com.sun.net.httpserver，故不依赖 JDK 内置 HTTP）。
 *
 * 复用 [CommandExecutor] 表：HTTP 请求 → 构造 Command → 调对应执行器 → 序列化回执。
 * 动作层零重复实现，与后台命令通道行为完全一致。
 *
 * 端点（均需 `?token=` 或 `Authorization: Bearer` 鉴权）：
 *   GET  /dump          → ui_dump（max_nodes/max_depth）
 *   POST /find          → ui_find（selector/max_results/timeout_ms）
 *   POST /click         → ui_click（selector 或 x,y）
 *   POST /set_text      → ui_set_text（selector/x,y + text）
 *   POST /gesture       → ui_gesture（action/x1,y1,x2,y2/duration_ms）
 *   POST /swipe         → 滑动（x1,y1,x2,y2,duration_ms）
 *   POST /back          → ui_back
 *   POST /home          → ui_home
 *   GET  /screenshot    → 截屏（返回 JPEG 二进制）
 *   GET  /status        → 无障碍就绪状态
 *
 * 返回结构统一：{"success":bool, "error_code":str?, "retryable":bool, "message":str, "data":...}
 */
object RpcServerController {

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    data class State(
        val status: Status = Status.STOPPED,
        val port: Int = 0,
        val token: String = "",
        val startedAt: Long = 0L,
        val requestCount: Int = 0,
        val message: String = "未运行",
    )

    private val _state = AtomicReference(State())
    val state: State get() = _state.get()

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)
    private val requestCount = AtomicInteger(0)

    @Volatile
    private var executors: Map<String, CommandExecutor> = emptyMap()

    @Volatile
    private var appContext: Context? = null

    /** 由 CommandModule 在装配时注入。 */
    fun bind(executors: Map<String, CommandExecutor>, context: Context) {
        this.executors = executors
        this.appContext = context
    }

    fun isRunning(): Boolean = running.get()

    /** 启动：监听 0.0.0.0（所有网卡），默认端口 15161，支持本机/局域网/VNT 组网连入，生成 token。 */
    fun start(port: Int = 15161): Boolean {
        if (running.get()) return true
        return try {
            _state.set(State(Status.STARTING, port = port, message = "正在启动…"))
            val token = generateToken()
            // 绑定 0.0.0.0：任何本机地址（127.0.0.1 / 局域网 192.168.x.x / VNT 虚拟网 86.x.x.x）均可连入。
            // Android 对监听 0.0.0.0 无特殊权限要求（仅 <1024 特权端口受限，动态端口无碍）。
            val ss = ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
            serverSocket = ss
            running.set(true)
            requestCount.set(0)
            val pool = Executors.newCachedThreadPool()
            // accept 循环线程
            Thread {
                while (running.get()) {
                    try {
                        val socket = ss.accept()
                        pool.execute { handleConnection(socket, token) }
                    } catch (e: Exception) {
                        if (running.get()) Timber.w(e, "RPC accept 异常")
                    }
                }
            }.apply { isDaemon = true; start() }
            _state.set(
                State(
                    status = Status.RUNNING,
                    port = ss.localPort,
                    token = token,
                    startedAt = System.currentTimeMillis(),
                    message = "运行中",
                )
            )
            Timber.i("RPC 服务器已启动：0.0.0.0:${ss.localPort}（本机 127.0.0.1 / 局域网 / VNT 均可连入） token=$token")
            true
        } catch (e: Exception) {
            Timber.e(e, "RPC 服务器启动失败")
            _state.set(State(Status.ERROR, message = "启动失败：${e.message}"))
            false
        }
    }

    /** 停止：释放端口。 */
    fun stop() {
        if (!running.get()) {
            _state.set(State(Status.STOPPED))
            return
        }
        try {
            running.set(false)
            serverSocket?.close()
            serverSocket = null
            _state.set(State(Status.STOPPED))
            Timber.i("RPC 服务器已停止")
        } catch (e: Exception) {
            Timber.e(e, "RPC 服务器停止失败")
            _state.set(State(Status.ERROR, message = "停止失败：${e.message}"))
        }
    }

    /** 供 rpc_status 命令回执。 */
    fun statusText(): String {
        val s = _state.get()
        return when (s.status) {
            Status.RUNNING ->
                "运行中 · 监听 0.0.0.0:${s.port} · token ${s.token} · 已处理 ${s.requestCount} 次请求" +
                    "（可从 127.0.0.1 / 局域网 IP / VNT 虚拟网 IP 连入，Python 客户端 pip install bridge-rpc 或 requests 带 token 调用）"
            Status.STARTING -> "正在启动…"
            Status.STOPPED -> "未运行"
            Status.ERROR -> "异常：${s.message}"
        }
    }

    // ------------------------------------------------------------------
    // 内部：连接处理 + 路由 + 鉴权 + 执行器复用
    // ------------------------------------------------------------------

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun handleConnection(socket: Socket, token: String) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                // 读请求行
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val pathAndQuery = parts[1]
                // 读 headers
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                // 读 body（按 Content-Length）
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val (path, query) = splitPathQuery(pathAndQuery)
                val queryParams = parseQuery(query)

                // 鉴权
                val authed = queryParams["token"] == token ||
                    headers["authorization"]?.removePrefix("Bearer ")?.trim() == token
                if (!authed) {
                    writeJson(s, RpcResult.fail("鉴权失败：token 无效", errorCode = "UNAUTHORIZED", retryable = false).toJson(), 401)
                    return
                }
                requestCount.incrementAndGet()

                val result = route(method, path, queryParams, body)
                if (result is BinaryResult) {
                    writeBinary(s, result.bytes, result.contentType)
                } else {
                    writeJson(s, (result as RpcResult).toJson())
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "RPC 连接处理异常")
        }
    }

    private fun splitPathQuery(pathAndQuery: String): Pair<String, String> {
        val idx = pathAndQuery.indexOf('?')
        return if (idx < 0) pathAndQuery to "" else pathAndQuery.substring(0, idx) to pathAndQuery.substring(idx + 1)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
    }

    private fun route(method: String, path: String, query: Map<String, String>, body: String): Any {
        return when (path) {
            "/dump" -> exec("ui_dump", query)
            "/status" -> a11yStatus()
            "/screenshot" -> screenshot()
            "/find" -> exec("ui_find", parseBody(body))
            "/click" -> exec("ui_click", parseBody(body))
            "/long_click" -> exec("ui_long_click", parseBody(body))
            "/set_text" -> exec("ui_set_text", parseBody(body))
            "/gesture" -> exec("ui_gesture", parseBody(body))
            "/swipe" -> execSwipe(parseBody(body))
            "/back" -> exec("ui_back", JsonObject())
            "/home" -> exec("ui_home", JsonObject())
            // 通用端点：透传任意白名单内的命令（Python 脚本流程控制需要）
            "/exec" -> execGeneric(parseBody(body))
            else -> RpcResult.fail("未知端点：$path", errorCode = "NOT_FOUND", retryable = false)
        }
    }

    /**
     * 通用 /exec：接受 {command, ...params}，透传任意已注册命令执行器。
     * 带白名单（仅自动化域命令可经 RPC 暴露，避免把整个命令面开给网络）。
     */
    private val rpcAllowedCommands = setOf(
        "ui_dump", "ui_find", "ui_click", "ui_long_click", "ui_set_text", "ui_gesture",
        "ui_back", "ui_home", "ui_scroll_to", "ui_wait_idle", "ui_wait_gone", "ui_clear",
        "ui_sendkey", "ui_exists", "ui_get_text", "ui_checked", "ui_enabled", "ui_selected",
        "ui_count", "ui_which", "ui_in_app", "ui_keyboard_shown", "ui_last_toast", "ui_color",
        "ui_recents", "ui_open_notifications", "ui_quick_settings",
        "ui_launch", "ui_open_url", "ui_intent", "ui_apps", "ui_installed", "ui_screen_on",
        "ui_locked", "ui_wake", "ui_orientation", "ui_info", "ui_launch_app", "ui_package_name",
        "ui_app_name", "ui_open_app_setting", "ui_set_brightness", "ui_get_brightness",
        "ui_get_mem", "ui_is_charging", "ui_find_color", "ui_find_multi_colors", "ui_sensors",
        "ui_file_read", "ui_file_write", "ui_file_append", "ui_file_exists", "ui_file_list",
        "ui_file_copy", "ui_file_move", "ui_file_remove", "ui_media_seek", "ui_media_volume",
        "ui_media_position", "ui_media_duration", "ui_media_state",
        "ui_notifications", "ui_has_notification", "ui_notification_reply", "ui_notification_click",
        "ui_notification_dismiss", "ui_clipboard",
    )

    private fun execGeneric(body: JsonObject): RpcResult {
        val cmdName = body.get("command")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return RpcResult.fail("缺少 command 字段", errorCode = "BAD_ARGS", retryable = false)
        if (cmdName !in rpcAllowedCommands) {
            return RpcResult.fail("命令 $cmdName 不允许经 RPC 暴露", errorCode = "FORBIDDEN", retryable = false)
        }
        // 去掉 command 字段，剩余作为 params
        val params = JsonObject()
        for ((k, v) in body.entrySet()) if (k != "command") params.add(k, v)
        return exec(cmdName, params)
    }

    private fun parseBody(body: String): JsonObject =
        if (body.isBlank()) JsonObject() else runCatching {
            JsonParser.parseString(body).asJsonObject
        }.getOrElse { JsonObject() }

    /** 复用命令执行器：构造 Command → 调 execute → 包装为 RpcResult。 */
    private fun exec(cmdName: String, body: JsonObject): RpcResult {
        val executor = executors[cmdName]
            ?: return RpcResult.fail("命令 $cmdName 未注册", errorCode = "NOT_REGISTERED", retryable = false)
        val command = Command(cmdName, "rpc_${System.currentTimeMillis()}", body)
        val result = runBlocking { executor.execute(command) }
        return RpcResult(
            success = result.success,
            message = result.message,
            data = result.message,
            errorCode = if (result.success) null else "COMMAND_FAILED",
            retryable = !result.success,
        )
    }

    private fun exec(cmdName: String, params: Map<String, String>): RpcResult {
        val body = JsonObject()
        params.forEach { (k, v) -> body.addProperty(k, v) }
        return exec(cmdName, body)
    }

    private fun execSwipe(body: JsonObject): RpcResult {
        val gesture = JsonObject().apply {
            addProperty("action", "swipe")
            body.get("x1")?.let { add("x1", it) }
            body.get("y1")?.let { add("y1", it) }
            body.get("x2")?.let { add("x2", it) }
            body.get("y2")?.let { add("y2", it) }
            body.get("duration_ms")?.let { add("duration_ms", it) }
        }
        return exec("ui_gesture", gesture)
    }

    private fun a11yStatus(): RpcResult {
        val ready = my.bridge.com.automation.a11y.BridgeAccessibilityService.isReady()
        return RpcResult(
            success = true,
            message = if (ready) "无障碍服务已就绪" else "无障碍服务未开启",
            data = if (ready) "{\"accessibility\":true}" else "{\"accessibility\":false}",
        )
    }

    private fun screenshot(): Any {
        val ctx = appContext
            ?: return RpcResult.fail("应用上下文未初始化", errorCode = "INTERNAL", retryable = false)
        return try {
            val path = my.bridge.com.automation.a11y.BridgeAccessibilityService.takeScreenshotToFile(ctx)
            BinaryResult(java.io.File(path).readBytes(), "image/jpeg")
        } catch (e: Exception) {
            RpcResult.fail("截屏失败：${e.message}", errorCode = "SCREENSHOT_FAILED", retryable = true)
        }
    }

    private fun writeJson(s: Socket, json: String, code: Int = 200) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out: OutputStream = s.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeBinary(s: Socket, bytes: ByteArray, contentType: String) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out: OutputStream = s.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}

/** RPC 统一返回结构。 */
data class RpcResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("success", success)
        addProperty("message", message)
        if (data != null) addProperty("data", data)
        if (errorCode != null) addProperty("error_code", errorCode)
        addProperty("retryable", retryable)
    }.toString()

    companion object {
        fun fail(message: String, errorCode: String = "COMMAND_FAILED", retryable: Boolean = true): RpcResult =
            RpcResult(success = false, message = message, errorCode = errorCode, retryable = retryable)
    }
}

/** 二进制响应（截图）。 */
data class BinaryResult(val bytes: ByteArray, val contentType: String)
