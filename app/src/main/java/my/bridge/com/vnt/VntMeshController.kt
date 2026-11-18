package my.bridge.com.vnt

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.vnt.NetworkResult
import com.vnt.VntApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.bridge.com.core.common.DiagLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * VNT 组网状态机 + 启停编排 + 设备列表刷新（v0.2.131，2.x / v2.0.12）。
 *
 * 生命周期（已拍板）：**服务常驻 + 显式开关**。切 Tab / 退后台 / 锁屏都不断连；
 * 只有点「停止组网」、进程结束（[my.bridge.com.AppExit]）或系统把 VPN 通道给了别的 App 才断。
 * 页面只订阅 [state]，不在 `DisposableEffect` 里启停连接（照 `AudioRelayPage` 做法）。
 *
 * 刷新策略（已拍板，见调研报告 §2.5 / §九）：
 * - **仅 App 在前台 + 处于组网页**时才刷新；
 * - 进页面**立即刷一次**（修掉 VntApp 原版「初始延迟 3 秒才出数据」的空白观感）；
 * - 之后每 **5 秒**一次（官方 3 秒，设备列表不需要 3 秒级精度，请求量降 40%）；
 * - 离开页面 / 退后台 / 锁屏 → 取消定时任务并 `resetTrafficSamples()`，**一次都不再拉**；
 * - `getClientList()` 是**主动 RPC 且最多阻塞 3 秒**，绝不在主线程调；
 * - 快速进出页面用 **generation 校验**防止慢请求回来覆盖新状态。
 *
 * ★ v0.2.38 沿用：断线重连的 [onReconnecting] + RECONNECTING 状态由 [VntVpnService]
 *   监督循环驱动，本 Controller 只负责状态发布与启停编排。
 */
object VntMeshController {

    private const val TAG = "组网"
    private const val REFRESH_INTERVAL_SEC = 5L

    private val _state = MutableStateFlow(VntMeshState())
    val state: StateFlow<VntMeshState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 当前组网实例的查询接口；可能为 null（未连接 / 实例内部重建瞬间）。 */
    @Volatile
    private var api: VntApi? = null

    /** 刷新调度器（仅页面可见期间存在）。 */
    private var refreshExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var refreshFuture: ScheduledFuture<*>? = null

    /** 是否正在刷新（startRefresh/stopRefresh 幂等守卫）。 */
    @Volatile
    private var refreshing = false

    /** 代次：每次启动/停止刷新都自增，用于在途慢请求失效。 */
    @Volatile
    private var refreshGeneration = 0L

    /** 每个对端 IP 的上一次累计流量采样（速率 = 差分 ÷ 间隔）。 */
    private data class Sample(val tx: Long, val rx: Long, val at: Long)
    private val samples = ConcurrentHashMap<String, Sample>()

    // ------------------------------------------------------------------
    // 状态发布（统一切主线程写，与 AudioRelayPage 的 mainHandler.post 惯例一致）
    // ------------------------------------------------------------------

    private fun publish(next: VntMeshState) {
        mainHandler.post { _state.value = next }
    }

    private fun publish(block: (VntMeshState) -> VntMeshState) {
        mainHandler.post { _state.value = block(_state.value) }
    }

    /** 连接成功：保存查询接口并切换到 RUNNING。 */
    fun onConnected(
        newApi: VntApi,
        result: NetworkResult,
        cfg: VntMeshConfig,
        mtu: Int,
    ) {
        api = newApi
        resetTrafficSamples()
        publish {
            it.copy(
                status = VntStatus.RUNNING,
                virtualIp = result.ip,
                prefixLen = result.prefixLen,
                virtualIpv6 = result.virtualIpv6,
                mtu = mtu,
                networkCode = cfg.networkCode,
                deviceName = cfg.deviceName,
                deviceId = cfg.deviceId,
                message = "已连接",
                lastUpdatedAt = System.currentTimeMillis(),
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    /** 运行期实例内部重建后，native 侧会换查询句柄，必须刷新对外接口。 */
    fun onApiRefreshed(newApi: VntApi) {
        api = newApi
    }

    /** 清空查询接口（停止 / 出错 / 监督循环收尾时调用）。 */
    fun clearApi() {
        api = null
    }

    fun onError(message: String) {
        api = null
        resetTrafficSamples()
        publish {
            it.copy(
                status = VntStatus.ERROR,
                message = message,
                peers = emptyList(),
                servers = emptyList(),
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    /**
     * v0.2.38 沿用：进入断线重连（服务内监督循环继续工作，非终态）。
     * 由 [VntVpnService] 在「可重试失败」后调用；`reason` 供 UI/admin 展示断线原因。
     */
    fun onReconnecting(attempt: Int, delaySec: Int, reason: String) {
        api = null
        resetTrafficSamples()
        publish {
            it.copy(
                status = VntStatus.RECONNECTING,
                message = reason,
                peers = emptyList(),
                servers = emptyList(),
                retryAttempt = attempt,
                retryDelaySec = delaySec,
            )
        }
    }

    fun onStopping() {
        publish {
            it.copy(
                status = VntStatus.STOPPING,
                message = "正在停止…",
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    fun onStopped() {
        api = null
        resetTrafficSamples()
        publish {
            it.copy(
                status = VntStatus.STOPPED,
                message = "已断开",
                virtualIp = null,
                virtualIpv6 = null,
                natType = null,
                serverRttMs = null,
                peers = emptyList(),
                servers = emptyList(),
                lastUpdatedAt = 0L,
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    // ------------------------------------------------------------------
    // 启停（服务常驻）
    // ------------------------------------------------------------------

    /** 是否已获得系统 VPN 授权（未授权时 UI 必须先弹 `VpnService.prepare()` 授权框）。 */
    fun isPrepared(context: Context): Boolean = VpnService.prepare(context) == null

    /**
     * 开始组网。调用方（UI）必须先确认 `isPrepared()`。
     *
     * `startForegroundService` 每调一次欠一次前台义务，按崩溃台账 §5 由
     * [VntVpnService.onStartCommand] 每次幂等补挂；这里 try/catch 防后台启动限制抛异常。
     */
    fun start(context: Context): Boolean {
        val s = _state.value.status
        // v0.2.38 沿用：RECONNECTING 也视为「已在工作」（监督循环会自动建回来），勿重复拉起
        if (s == VntStatus.RUNNING || s == VntStatus.STARTING || s == VntStatus.RECONNECTING) {
            return true
        }
        val intent = Intent(context, VntVpnService::class.java)
            .setAction(VntVpnService.ACTION_START)
        return try {
            ContextCompat.startForegroundService(context, intent)
            publish {
                it.copy(status = VntStatus.STARTING, message = "正在连接服务器并注册网络…")
            }
            true
        } catch (e: Exception) {
            DiagLog.e(TAG, "startForegroundService 启动失败", e)
            publish {
                it.copy(status = VntStatus.ERROR, message = "启动失败：${e.message}（请在前台操作）")
            }
            false
        }
    }

    /**
     * 停止组网：走普通 startService（无前台义务），服务内部 stop native 后 stopSelf。
     *
     * v0.2.33 沿用：未组网且服务没在跑时直接收状态，**不再 startService** —— 否则
     * Android 会新建一个空的 VntVpnService（页面「停止组网」/通知栏「停止」同样会
     * 走到这里），前台通知常驻且状态卡在 STOPPING。真正的收尾由服务自己完成。
     */
    fun stop(context: Context) {
        val s = _state.value.status
        if ((s == VntStatus.STOPPED || s == VntStatus.ERROR) && !VntVpnService.isRunning()) {
            onStopped()
            return
        }
        onStopping()
        try {
            context.startService(
                Intent(context, VntVpnService::class.java).setAction(VntVpnService.ACTION_STOP)
            )
        } catch (e: Exception) {
            DiagLog.e(TAG, "下发停止失败", e)
            publish { it.copy(status = VntStatus.ERROR, message = "停止失败：${e.message}") }
        }
    }

    /**
     * 保存配置后重连（配置弹窗「保存并重连」）。
     * 先 stop，等 native 释放 fd / 服务 stopSelf 后再 start；同步把状态置 STOPPED
     * 以绕过 [start] 的「已在运行」守卫。
     */
    fun restart(context: Context) {
        val wasRunning = _state.value.status == VntStatus.RUNNING
        stop(context)
        if (!wasRunning) return
        mainHandler.postDelayed({
            _state.value = _state.value.copy(status = VntStatus.STOPPED, message = "正在重连…")
            start(context)
        }, 1200L)
    }

    /** 供 `vnt_status` 命令回执：只读本地快照，**刻意不调 getClientList()**（那是 RPC）。 */
    fun statusText(): String {
        val s = _state.value
        return when (s.status) {
            VntStatus.RUNNING ->
                "运行中 · 虚拟IP ${s.virtualIp ?: "?"} · NAT ${s.natType ?: "?"} · " +
                    "服务端RTT ${s.serverRttMs ?: "?"}ms · 在线设备 ${s.peers.count { it.online }}" +
                    "（详细列表需打开组网页，命令不主动拉 RPC）"
            VntStatus.STARTING -> "正在建立连接（${s.message}）"
            VntStatus.RECONNECTING ->
                "断线重连中（第 ${s.retryAttempt} 次，${s.retryDelaySec}s 后重试 · ${s.message}）"
            VntStatus.STOPPING -> "正在停止（${s.message}）"
            VntStatus.STOPPED -> "未连接"
            VntStatus.ERROR -> "异常：${s.message}"
        }
    }

    // ------------------------------------------------------------------
    // 刷新调度（页面可见期间）
    // ------------------------------------------------------------------

    /** 进入组网页 / ON_RESUME 时调用：立即刷一次 + 5 秒周期。幂等。 */
    fun startRefresh() {
        synchronized(lock) {
            if (refreshing) return
            refreshing = true
            val gen = ++refreshGeneration
            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "vnt-refresh").apply { isDaemon = true }
            }
            refreshExecutor = exec
            // ★ 立即刷一次，避免进页面后空等第一个周期（VntApp 原版的观感缺陷）
            exec.execute { refreshOnce(gen) }
            refreshFuture?.cancel(false)
            refreshFuture = exec.scheduleWithFixedDelay(
                { refreshOnce(gen) },
                REFRESH_INTERVAL_SEC, REFRESH_INTERVAL_SEC, TimeUnit.SECONDS,
            )
        }
    }

    /** 离开组网页 / ON_PAUSE / onDispose 时调用：取消定时任务、清采样基准。幂等。 */
    fun stopRefresh() {
        synchronized(lock) {
            if (!refreshing) return
            refreshing = false
            ++refreshGeneration           // 使在途慢请求失效
            refreshFuture?.cancel(false)
            refreshFuture = null
            refreshExecutor?.shutdownNow()
            refreshExecutor = null
            resetTrafficSamples()         // ★ 否则回到页面时差分会算出虚假巨大尖峰
        }
    }

    /** 页面隐藏→再显示必须重置采样基准（由 startRefresh/stopRefresh 内部调用）。 */
    private fun resetTrafficSamples() {
        samples.clear()
    }

    private fun refreshOnce(gen: Long) {
        val a = api ?: return
        if (gen != refreshGeneration || !refreshing) return
        try {
            val now = System.currentTimeMillis()
            // ★ 唯一会发网络包的调用：主动 RPC，最多阻塞 3 秒，必须在后台线程
            val clients = a.getClientList()
            if (gen != refreshGeneration || !refreshing) return

            // 以下都是纯本地内存读取，不发网络包
            val serverList = runCatching { a.getServerList() }.getOrNull() ?: emptyList()
            val routeTable = runCatching { a.getRouteTable() }.getOrNull() ?: emptyList()
            val natType = runCatching { a.getNatInfo()?.natType }.getOrNull()
            val serverRtt = serverList.firstOrNull { it.isConnected }?.rtt

            val peers = clients.map { c ->
                val ip = c.ip
                val direct = runCatching { a.isDirect(ip) }.getOrDefault(false)
                var rttMs: Int? = null
                var estimated = false
                // 有路由表记录 = 真实 RTT；无路由（中继）= 服务端 RTT × 2 的估算值
                val routeRtt = routeTable.firstOrNull { it.ip == ip }
                    ?.routes?.minByOrNull { it.metric }?.rtt
                if (routeRtt != null && routeRtt > 0) {
                    rttMs = routeRtt
                } else if (serverRtt != null) {
                    rttMs = serverRtt * 2
                    estimated = true
                }

                var txRate = 0L
                var rxRate = 0L
                runCatching { a.getTrafficInfo(ip) }.getOrNull()?.let { t ->
                    val prev = samples[ip]
                    if (prev != null) {
                        val dt = now - prev.at
                        if (dt > 0) {
                            txRate = (t.txBytes - prev.tx).coerceAtLeast(0) * 1000 / dt
                            rxRate = (t.rxBytes - prev.rx).coerceAtLeast(0) * 1000 / dt
                        }
                    }
                    samples[ip] = Sample(t.txBytes, t.rxBytes, now)
                }

                VntPeer(
                    ip = ip,
                    online = c.isOnline,
                    direct = direct,
                    rttMs = rttMs,
                    rttEstimated = estimated,
                    txBytesPerSec = txRate,
                    rxBytesPerSec = rxRate,
                )
            }

            val servers = serverList.map {
                VntServer(address = it.serverAddr, connected = it.isConnected, rttMs = it.rtt)
            }
            if (gen != refreshGeneration || !refreshing) return
            publish {
                it.copy(
                    peers = peers,
                    servers = servers,
                    natType = natType ?: it.natType,
                    serverRttMs = serverRtt ?: it.serverRttMs,
                    lastUpdatedAt = System.currentTimeMillis(),
                )
            }
        } catch (e: Throwable) {
            // getClientList 在实例停止/重建瞬间会抛 VntException，属正常，不升级为页面错误
            DiagLog.e(TAG, "刷新设备列表失败", e)
        }
    }
}
