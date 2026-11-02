package my.bridge.com.vnt

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.bridge.com.core.common.DiagLog
import top.wherewego.vnt.jni.IpUtils
import top.wherewego.vnt.jni.PeerRouteInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 由 [VntVpnService] 注册的设备列表提供者。
 *
 * 1.x 的 `Vnt.list()` 读的是 native 侧缓存的路由表（**不是网络 RPC**），
 * 但仍是 JNI 调用，必须在后台线程执行；Controller 只持有这个接口，
 * 不直接持有 `Vnt` 实例，避免 Service 结束后对象泄漏。
 */
fun interface PeerListProvider {
    fun list(): Array<PeerRouteInfo>?
}

/**
 * VNT 组网状态机 + 启停编排 + 设备列表刷新（v0.2.32，1.x）。
 *
 * 生命周期（已拍板）：**服务常驻 + 显式开关**。切 Tab / 退后台 / 锁屏都不断连；
 * 只有点「停止组网」、进程结束（[my.bridge.com.AppExit]）或系统把 VPN 通道给了别的 App 才断。
 * 页面只订阅 [state]，不在 `DisposableEffect` 里启停连接（照 `AudioRelayPage` 做法）。
 *
 * 刷新策略（已拍板，见调研报告 §2.5 / §九）：
 * - **仅 App 在前台 + 处于组网页**时才刷新；
 * - 进页面**立即刷一次**，之后每 **5 秒**一次；
 * - 离开页面 / 退后台 / 锁屏 → 取消定时任务，**一次都不再拉**；
 * - `Vnt.list()` 是本地内存读（不是 RPC），省电成本主要在定时器与 JNI 调用；
 * - 快速进出页面用 **generation 校验**防止慢请求回来覆盖新状态。
 *
 * ★ 与 2.x 的差异：没有 getClientList RPC、没有速率采样、没有 RuntimeEvent 变更循环；
 * P2P/中继由 `Route.metric == 1` 判定（官方 ConnectActivity 同款逻辑）。
 */
object VntMeshController {

    private const val TAG = "组网"
    private const val REFRESH_INTERVAL_SEC = 5L

    private val _state = MutableStateFlow(VntMeshState())
    val state: StateFlow<VntMeshState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 由 Service 在 `Vnt` 构造成功后注册，停止/出错时清空。 */
    @Volatile
    private var provider: PeerListProvider? = null

    /** 刷新调度器（仅页面可见期间存在）。 */
    private var refreshExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var refreshFuture: ScheduledFuture<*>? = null

    @Volatile
    private var refreshing = false

    /** 代次：每次启动/停止刷新都自增，用于在途慢请求失效。 */
    @Volatile
    private var refreshGeneration = 0L

    // ------------------------------------------------------------------
    // 状态发布（统一切主线程写，与 AudioRelayPage 的 mainHandler.post 惯例一致）
    // ------------------------------------------------------------------

    private fun publish(next: VntMeshState) {
        mainHandler.post { _state.value = next }
    }

    private fun publish(block: (VntMeshState) -> VntMeshState) {
        mainHandler.post { _state.value = block(_state.value) }
    }

    /**
     * 连接成功：由 [VntVpnService] 在 1.x 的 `CallBack.generateTun()` 回调里调用
     * （那时虚拟 IP 与网段已确定、VPN 接口刚建立）。
     */
    fun onConnected(
        virtualIp: String,
        prefixLen: Int,
        cfg: VntMeshConfig,
        mtu: Int,
        serverVersion: String?,
    ) {
        publish {
            it.copy(
                status = VntStatus.RUNNING,
                virtualIp = virtualIp,
                prefixLen = prefixLen,
                mtu = mtu,
                token = cfg.token,
                deviceName = cfg.deviceName,
                deviceId = cfg.deviceId,
                server = cfg.server,
                tcp = cfg.tcp,
                serverVersion = serverVersion,
                message = "已连接",
                lastUpdatedAt = System.currentTimeMillis(),
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    /** `Vnt` 构造成功后注册列表提供者（构造期间还不能调 list()）。 */
    fun onVntReady(p: PeerListProvider) {
        provider = p
    }

    fun clearProvider() {
        provider = null
    }

    fun onError(message: String) {
        provider = null
        publish {
            it.copy(
                status = VntStatus.ERROR,
                message = message,
                peers = emptyList(),
                retryAttempt = 0,
                retryDelaySec = 0,
            )
        }
    }

    /**
     * v0.2.38：进入断线重连（服务内监督循环继续工作，非终态）。
     * 由 [VntVpnService] 在「可重试失败」后调用；`reason` 供 UI/admin 展示断线原因。
     */
    fun onReconnecting(attempt: Int, delaySec: Int, reason: String) {
        provider = null
        publish {
            it.copy(
                status = VntStatus.RECONNECTING,
                message = reason,
                peers = emptyList(),
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
        provider = null
        publish {
            it.copy(
                status = VntStatus.STOPPED,
                message = "已断开",
                virtualIp = null,
                serverVersion = null,
                peers = emptyList(),
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
        // v0.2.38：RECONNECTING 也视为「已在工作」（监督循环会自动建回来），勿重复拉起
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
     * v0.2.33：未组网且服务没在跑时直接收状态，**不再 startService** —— 否则
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

    /** 供 `vnt_status` 命令回执：只读本地快照，**刻意不调 list()**。 */
    fun statusText(): String {
        val s = _state.value
        return when (s.status) {
            VntStatus.RUNNING ->
                "运行中 · 虚拟IP ${s.virtualIp ?: "?"} · Token ${s.token} · " +
                    "服务端 ${s.server}（${if (s.tcp) "TCP" else "UDP"}）· " +
                    "在线设备 ${s.peers.count { it.online }} · 服务端版本 ${s.serverVersion ?: "?"}" +
                    "（详细列表需打开组网页，命令不主动拉列表）"
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
            exec.execute { refreshOnce(gen) }          // ★ 立即刷一次，避免进页面空白
            refreshFuture?.cancel(false)
            refreshFuture = exec.scheduleWithFixedDelay(
                { refreshOnce(gen) },
                REFRESH_INTERVAL_SEC, REFRESH_INTERVAL_SEC, TimeUnit.SECONDS,
            )
        }
    }

    /** 离开组网页 / ON_PAUSE / onDispose 时调用：取消定时任务。幂等。 */
    fun stopRefresh() {
        synchronized(lock) {
            if (!refreshing) return
            refreshing = false
            ++refreshGeneration           // 使在途慢请求失效
            refreshFuture?.cancel(false)
            refreshFuture = null
            refreshExecutor?.shutdownNow()
            refreshExecutor = null
        }
    }

    private fun refreshOnce(gen: Long) {
        val p = provider ?: return
        if (gen != refreshGeneration || !refreshing) return
        try {
            // Vnt.list()：本地内存读（非网络 RPC），但仍是 JNI，必须在后台线程
            val arr = p.list() ?: return
            if (gen != refreshGeneration || !refreshing) return
            val peers = arr.map { pr ->
                val route = pr.route
                VntPeer(
                    ip = IpUtils.intToIpAddress(pr.virtualIp),
                    name = pr.name.orEmpty(),
                    status = pr.status.orEmpty(),
                    online = isOnline(pr.status),
                    direct = route?.metric?.toInt() == 1,   // 官方判定：metric==1 即 p2p
                    rt = route?.rt,
                )
            }
            if (gen != refreshGeneration || !refreshing) return
            publish {
                it.copy(peers = peers, lastUpdatedAt = System.currentTimeMillis())
            }
        } catch (e: Throwable) {
            // 实例停止/重建瞬间 native 可能抛异常，属正常，不升级为页面错误
            DiagLog.e(TAG, "刷新设备列表失败", e)
        }
    }

    /** native 的 status 字符串没有枚举契约，做宽松判定并原样展示。 */
    private fun isOnline(status: String?): Boolean {
        val s = status?.trim()?.lowercase() ?: return false
        return s != "offline" && s != "off" && s != "0" && s != "false" && s.isNotEmpty()
    }
}
