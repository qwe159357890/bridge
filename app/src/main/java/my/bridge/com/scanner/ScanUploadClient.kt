package my.bridge.com.scanner

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 扫码上传客户端（v0.2.02）：把扫码结果实时上报给 PC 端 SalesRecordSystem，
 * 协议 = Barcode to PC 超集（helo / ping / putScanSessions + 扩展回执 scanResult）。
 *
 * 兼容性约定：
 * - 只发送 Barcode to PC 白名单内的 action（未知 action 会被服务端踢线）；
 * - 服务端不回 scanResult（旧版本）时静默容错，2s 内无回执不做任何事；
 * - 断线自动重连（指数退避 5s→60s 封顶），不影响本地扫码闭环。
 *
 * 生命周期：跟随扫码页可见（进入 start / onDispose stop），与智能控制页惯例一致。
 */
class ScanUploadClient(
    private val context: Context,
    private val onReceipt: (Receipt) -> Unit = {},
    private val onStateChanged: (State) -> Unit = {},
) {

    enum class State { IDLE, CONNECTING, ONLINE, OFFLINE, LEGACY }

    /** 服务端 scanResult 回执（result: hit=命中首扫 / duplicate=重复 / miss=未匹配）。 */
    data class Receipt(
        val id: String,
        val text: String,
        val result: String,
        val receiver: String,
        val town: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        // 传输层心跳：Qt QWebSocket 按 RFC 6455 自动回 pong，无需服务端改动
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    var state: State = State.IDLE
        private set

    private var ws: WebSocket? = null
    private var backoffMs = 5_000L
    private var stopped = false

    /**
     * 旧版服务器识别（v0.2.06，纯信息特征、零版本维护）：
     * 新版 PC 每条上传必回 scanResult；旧版只回空确认 {}。
     * 每次上传 pendingReceipts++ 并起一个 6s 检测——期间没有任何 scanResult
     * 到达（计数未减）→ LEGACY；之后若真回执来了（只是慢）自愈回 ONLINE。
     */
    @Volatile
    private var pendingReceipts = 0

    private fun scheduleLegacyCheck() {
        scope.launch {
            delay(6_000)
            if (!stopped && pendingReceipts > 0 && state == State.ONLINE) {
                Timber.tag(TAG).w("上传 6s 无 scanResult 回执，判定 PC 端为旧版本")
                setState(State.LEGACY)
            }
        }
    }

    fun start() {
        if (state != State.IDLE || stopped) return
        connect()
    }

    fun stop() {
        stopped = true
        scope.cancel()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        setState(State.IDLE)
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private fun setState(s: State) {
        state = s
        onStateChanged(s)
    }

    private fun connect() {
        if (stopped) return
        val url = ScanPrefs.uploadUrl(context)
        if (url == null) {
            Timber.tag(TAG).w("上传地址无效：%s", ScanPrefs.uploadHost(context))
            setState(State.OFFLINE)
            return
        }
        setState(State.CONNECTING)
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                if (stopped) return
                backoffMs = 5_000L
                pendingReceipts = 0  // 重连重置：旧判定不作数，重新观察
                setState(State.ONLINE)
                // helo 握手（Barcode to PC 协议原样，服务端 debug 日志+回执）
                webSocket.send(
                    JSONObject()
                        .put("action", "helo")
                        .put("version", "1.0")
                        .put("deviceName", "Bridge")
                        .put("deviceId", "bridge-scanner")
                        .toString(),
                )
                Timber.tag(TAG).i("已连接 %s", url)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped) return
                runCatching {
                    val obj = JSONObject(text)
                    if (obj.optString("action") != "scanResult") return
                    // 收到回执：待回计数递减；若曾误判 LEGACY（服务器只是慢），自愈回 ONLINE
                    pendingReceipts = (pendingReceipts - 1).coerceAtLeast(0)
                    if (state == State.LEGACY) setState(State.ONLINE)
                    val results = obj.optJSONArray("results") ?: return
                    for (i in 0 until results.length()) {
                        val r = results.optJSONObject(i) ?: continue
                        onReceipt(
                            Receipt(
                                id = r.optString("id"),
                                text = r.optString("text"),
                                result = r.optString("result", "miss"),
                                receiver = r.optString("receiver"),
                                town = r.optString("town"),
                            ),
                        )
                    }
                }.onFailure { Timber.tag(TAG).w(it, "回执解析失败：%s", text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (stopped) return
                Timber.tag(TAG).w(t, "连接失败，%dms 后重连", backoffMs)
                setState(State.OFFLINE)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped) return
                setState(State.OFFLINE)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            connect()
        }
    }

    /**
     * 逐条实时上报：把刚入库的一条记录包成单条 scanSession 发送
     * （putScanSessions 为 Barcode to PC 原生 action，服务端零改动即可消费）。
     */
    fun upload(session: ScanSession, record: ScanRecord) {
        if (state != State.ONLINE && state != State.LEGACY) return  // LEGACY（旧版）也能收单，只是无回执
        val socket = ws ?: return
        runCatching {
            val scanning = JSONObject()
                .put("id", record.id.toString())
                .put("repeated", false)
                .put("date", formatTime(record.timestamp))
                .put("text", record.value)
                .put("displayValue", record.value)
            val sessionJson = JSONObject()
                .put("id", session.key)
                .put("name", session.name)
                .put("date", session.date)
                .put("selected", true)
                .put("scannings", JSONArray().put(scanning))
            val payload = JSONObject()
                .put("action", "putScanSessions")
                .put("sendKeystrokes", false)
                .put("deviceId", "bridge-scanner")
                .put("scanSessions", JSONArray().put(sessionJson))
            socket.send(payload.toString())
            pendingReceipts++
            scheduleLegacyCheck()
            Timber.tag(TAG).d("已上报：%s", record.value)
        }.onFailure { Timber.tag(TAG).w(it, "上报失败（不影响本地闭环）") }
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(ts))

    /**
     * 补报/手动上报（v0.2.04）：把任意单号包成单条 scanSession 发送，不入本地库。
     * 用于：①重复提示卡点击补报；②记录列表单击上报；③多选批量发送。
     * [session] 传 null 时用合成会话名。回执 scanResult 与扫码路径完全同构。
     */
    fun uploadText(session: ScanSession?, text: String) {
        if (state != State.ONLINE && state != State.LEGACY) return  // LEGACY（旧版）也能收单，只是无回执
        val socket = ws ?: return
        if (text.isBlank()) return
        runCatching {
            val scanning = JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("repeated", true)
                .put("date", formatTime(System.currentTimeMillis()))
                .put("text", text)
                .put("displayValue", text)
            val sessionJson = JSONObject()
                .put("id", session?.key ?: "bridge-manual")
                .put("name", session?.name ?: "Bridge 手动上报")
                .put("date", session?.date ?: formatTime(System.currentTimeMillis()).take(10))
                .put("selected", true)
                .put("scannings", JSONArray().put(scanning))
            val payload = JSONObject()
                .put("action", "putScanSessions")
                .put("sendKeystrokes", false)
                .put("deviceId", "bridge-scanner")
                .put("scanSessions", JSONArray().put(sessionJson))
            socket.send(payload.toString())
            pendingReceipts++
            scheduleLegacyCheck()
            Timber.tag(TAG).d("补报上报：%s", text)
        }.onFailure { Timber.tag(TAG).w(it, "补报失败（不影响本地闭环）") }
    }

    private companion object {
        const val TAG = "快递扫码"
    }
}
