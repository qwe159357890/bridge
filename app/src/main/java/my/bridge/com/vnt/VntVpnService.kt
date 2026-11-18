package my.bridge.com.vnt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vnt.ChangeApplyResult
import com.vnt.NetworkResult
import com.vnt.RuntimeChange
import com.vnt.RuntimeEvent
import com.vnt.VirtualIpv6
import com.vnt.VntApi
import com.vnt.VntException
import com.vnt.VntManager
import com.vnt.VntNetwork
import my.bridge.com.MainActivity
import my.bridge.com.R
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.common.LogThrottle

/**
 * VNT 组网前台服务（v0.2.131，2.x / vnt-dev v2.0.12；断线自动重连自 v0.2.38 沿用）——
 * 持有 [VpnService] 通道与 [VntNetwork] 实例。
 *
 * 对照官方 `AndroidVpnExample.java`（267 行骨架）与 `VntVpnService.java`（665 行生产实现）：
 * `VntManager.init()` → `createNetwork(config)` → `getNetwork()` → 建立 VPN 接口拿 fd →
 * `startTun(fd)` → `getApi()` → `nextEvent()/applyRuntimeChange` 变更循环 → `stop()`。
 *
 * ★ 与 1.x 的**时序完全相反**（这是 2.x 的核心）：
 * - 2.x：Java 先 `VpnService.Builder.establish()` 拿 fd → `net.startTun(fd)` 传进 native；
 * - 1.x：`new Vnt(Config, CallBack)` → native 在 `generateTun` 回调里反向向 Java 要 fd。
 *
 * ★ v0.2.38 断线自动重连（沿用，错误通道从 CallBack.error 换成 nextEvent）：
 * - **根因**：运行期 native 报错（切网后连接断开）→ worker 直接 throw → 服务死亡，
 *   无人重试。官方 VntApp 靠 `START_STICKY` + null intent 自动恢复，本项目为
 *   「退出即整体结束」有意删掉 STICKY，故在应用层补监督重连。
 * - **修复：进程内监督重连**——worker 改为「一次尝试 [tryConnectOnce] + 退避重试」的
 *   监督循环，**可重试失败不停服务、不 stopSelf**：
 *   - 不新增 `startForegroundService` 调用 ⇒ 天然绕开 Android 12+ 后台启动限制；
 *   - 错误分类：`VntManager.init()` 失败 / `createNetwork` 返回 null / `getNetwork`
 *     抛异常 / VPN 授权被抢（establish 返回 null）为**致命**，转 ERROR 终态；
 *     `nextEvent` 抛异常 / 收到 `instance_stopped`（实例自行退出=断线）为**可重试**；
 *   - 指数退避 2/4/8/16/30/60s 封顶（照 `RelayClient` 既有范式）；连接成功过即重置；
 *   - `registerDefaultNetworkCallback`：网络恢复/切换瞬间唤醒退避等待并重置退避。
 * - 生命周期语义不变：**只有用户点「停止组网」/ `AppExit.kill` / 致命错误才真正停止**；
 *   监督循环期间状态为 [VntStatus.RECONNECTING]，UI 主按钮仍显示「停止组网」可随时终止。
 *
 * ★ 崩溃台账 §5 检查清单（逐条落实）：
 * - manifest 已声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限；
 * - 所有 `startForegroundService` 调用点在 [VntMeshController.start] 里 try/catch；
 * - **onCreate 挂前台**，且 `startForeground` 有 try/catch + 失败 `stopSelf()`；
 * - **onStartCommand 对每次拉起都幂等补挂前台**（不依赖 onCreate 一定执行）；
 * - 返回 `START_NOT_STICKY`：进程被杀后系统**不自动复活**服务（冷启动由 `VntAutoStart` 恢复）；
 * - 调用点已登记到 `docs/dev/crash-ledger.md` §3.1。
 *
 * ★ 路由红线：**绝不加 `0.0.0.0/0`**（会劫持整机流量）。
 */
class VntVpnService : VpnService() {

    companion object {
        const val ACTION_START = "my.bridge.com.vnt.START"
        const val ACTION_STOP = "my.bridge.com.vnt.STOP"

        /** 独立通知 ID：保活 2001、中继 2002、媒体采集 2101，避免误删常驻通知。 */
        const val NOTIFICATION_ID = 2102
        const val CHANNEL_ID = "vntConnectionChannel"

        private const val TAG = "组网"
        private const val DEFAULT_MTU = 1380
        /** Rust 核心 P2P 栈的 IPv6 MTU 下限（vnt-core MIN_MTU）。 */
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 9000

        /** 指数退避表（秒）：2/4/8/16/30/60 封顶，照 RelayClient 既有范式。 */
        private val BACKOFF_SEC = intArrayOf(2, 4, 8, 16, 30, 60)

        /** 探活间隔（毫秒，v0.2.133）：与界面刷新同频，断线后通知最多延迟一个周期更新。 */
        private const val PROBE_INTERVAL_MS = 5_000L

        /** 连续失联确认次数（v0.2.134）：连续 N 次探活都失联才更新通知为「服务器连接中断」，过滤过渡态抖动。 */
        private const val DOWN_CONFIRM_COUNT = 3

        @Volatile
        private var instance: VntVpnService? = null

        fun isRunning(): Boolean = instance?.running == true

        /** 由本机虚拟 IPv4 + 前缀长度计算网段网络地址：86.74.128.30/24 → 86.74.128.0。 */
        private fun networkAddress(ip: String, prefixLen: Int): String {
            val octets = ip.split(".")
            if (octets.size != 4) throw IllegalArgumentException("无效 IPv4 地址：$ip")
            var value = 0L
            for (o in octets) {
                val part = o.toIntOrNull()
                    ?: throw IllegalArgumentException("无效 IPv4 地址：$ip")
                if (part !in 0..255) throw IllegalArgumentException("无效 IPv4 地址：$ip")
                value = (value shl 8) or part.toLong()
            }
            val p = prefixLen.coerceIn(0, 32)
            val mask = if (p <= 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
            value = value and mask
            return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}." +
                "${(value shr 8) and 0xFF}.${value and 0xFF}"
        }
    }

    private val nativeLock = Any()

    /**
     * 是否已挂前台。首次用 [android.app.Service.startForeground] 履行前台义务，
     * 之后所有通知更新走 [NotificationManager.notify]——部分国产 ROM 上重复调用
     * startForeground 不会刷新已有通知内容，导致「正在启动 → 已连接」切不动。
     */
    private var foregroundStarted = false

    /** 组网实例；由 [nativeLock] 保护。 */
    private var network: VntNetwork? = null

    /** worker 线程（监督循环跑在这里）。 */
    private var worker: Thread? = null

    /** 探活线程（v0.2.133）：连接成功后周期性查服务端连接状态，失联即主动 stop 触发重连。 */
    private var probeThread: Thread? = null

    @Volatile
    private var running = false

    /** 代次：停止/新拉起时自增，让阻塞在 nextEvent / 退避等待的旧循环退出。 */
    @Volatile
    private var generation = 0L

    /** 当前 VPN 接口使用的 CIDR；快照未携带固定 IP 时沿用。 */
    private var establishedCidr: String? = null
    private var currentMtu: Int = DEFAULT_MTU

    /** 网络回调（onAvailable 唤醒重连等待），onDestroy 注销。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** onAvailable 置 true：监督循环据此重置退避（新一轮故障从 2s 重新数）。 */
    @Volatile
    private var networkJustRecovered = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // 崩溃台账 §5：onCreate 立刻挂前台（try/catch + 失败 stopSelf 在 promoteForeground 内）
        promoteForeground("正在准备虚拟网络…", connected = false)
        registerNetworkCallback()
    }

    /**
     * 每次拉起都履行一次前台化义务（义务按次计，服务运行中重复拉起不走 onCreate）。
     * 返回 START_NOT_STICKY：进程被杀不自动复活，符合「退出即整体结束」的最高优先级
     * （断线恢复不靠 STICKY，靠 worker 内监督循环）。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground("正在准备虚拟网络…", connected = false)
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> startConnect()
            else -> DiagLog.i(TAG, "onStartCommand 无有效 action，忽略")
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 前台化
    // ------------------------------------------------------------------

    private fun promoteForeground(text: String, connected: Boolean) {
        try {
            val notification = buildNotification(text, connected)
            if (foregroundStarted) {
                // 已挂前台：用 notify 更新通知（重复 startForeground 在部分 ROM 不刷新）
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            } else {
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            }
        } catch (e: Exception) {
            DiagLog.e(TAG, "startForeground(specialUse) 失败，stopSelf 防崩", e)
            stopSelf()
        }
    }

    private fun buildNotification(text: String, connected: Boolean): android.app.Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pendingStop = PendingIntent.getService(
            this, 1,
            Intent(this, VntVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                when {
                    connected -> "VNT 虚拟网络已连接"
                    text.startsWith("断线重连中") -> "VNT 断线重连"
                    text.startsWith("服务器连接中断") -> "VNT 服务器连接中断"
                    else -> "VNT 正在启动"
                }
            )
            .setContentText(text)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VNT 连接状态", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持 VNT 虚拟网络在后台运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------------
    // 网络监听（v0.2.38：网络恢复立即重连，照 AudioRelayService 范式）
    // ------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 只置标志，不在回调里做任何重活；监督循环的退避等待以 250ms 粒度轮询。
                networkJustRecovered = true
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { DiagLog.w(TAG, "注册默认网络回调失败（不影响重连循环，靠退避兜底）：${it.message}") }
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    // ------------------------------------------------------------------
    // 连接主流程（worker 线程：监督循环 + 单次尝试）
    // ------------------------------------------------------------------

    private fun startConnect() {
        if (running) {
            DiagLog.i(TAG, "已在运行，跳过重复 START")
            return
        }
        running = true
        networkJustRecovered = false
        val gen = ++generation
        worker = Thread({ runSupervisor(gen) }, "vnt-network").apply {
            isDaemon = true
            start()
        }
    }

    /** 一次尝试的结局：`null` = 干净退出（停止/被接管），否则为失败与是否可重试。 */
    private class AttemptResult(
        val retryable: Boolean,
        val reason: String,
        /** 本次尝试是否曾连接成功（用于成功后重置退避计数）。 */
        val hadConnected: Boolean,
        /** 连接是否稳定存活超过一个探活周期（只有稳定连接才重置退避，抖动不重置）。 */
        val stable: Boolean = false,
    )

    /** 致命错误（重试无意义）：初始化/配置错、VPN 授权被抢。 */
    private class FatalVntException(message: String) : IllegalStateException(message)

    /**
     * 监督循环（v0.2.38 沿用）：失败 → 退避 → 重建，直到用户停止 / 被新拉起接管 / 致命错误。
     * 服务**不因可重试失败而 stopSelf**——这是「断线自动重连」的核心。
     */
    private fun runSupervisor(gen: Long) {
        var attempt = 0
        var fatal: String? = null
        while (running && gen == generation) {
            val res = tryConnectOnce(gen) ?: break
            // 只有「稳定连接」才重置退避；「连上即断」的抖动不重置，让退避逐步拉大，
            // 避免探活/过渡态导致的死循环永远停在「第 1 次」。
            if (res.stable) attempt = 0
            if (!res.retryable) {
                fatal = res.reason
                break
            }
            attempt++
            val delaySec = BACKOFF_SEC[(attempt - 1).coerceAtMost(BACKOFF_SEC.size - 1)]
            LogThrottle.event(
                "vnt-retry", res.reason, "W", TAG,
                "组网失败（可重试）：${res.reason}；第 $attempt 次重连将在 ${delaySec}s 后进行",
            )
            VntMeshController.onReconnecting(attempt, delaySec, res.reason)
            promoteForeground("断线重连中 · 第 $attempt 次（${delaySec}s 后重试）", connected = false)
            if (!sleepInterruptible(gen, delaySec)) break
            // 网络刚恢复 ⇒ 新一轮故障从 2s 重新数，不等满旧退避
            if (networkJustRecovered) {
                networkJustRecovered = false
                attempt = 0
            }
        }

        // ---- 收尾 ----
        val superseded = running                  // running 仍为 true = 新 startConnect 已接管
        if (!superseded) releaseNative()
        VntMeshController.clearApi()
        if (fatal != null) {
            DiagLog.e(TAG, "组网失败（不可重试）：$fatal")
            VntMeshController.onError("组网失败：$fatal（需人工处理，重试无意义）")
        } else if (!superseded) {
            VntMeshController.onStopped()
        }
        if (!superseded) {
            running = false
            stopSelf()
        }
    }

    /**
     * 可中断的退避等待（250ms 轮询，最长 60s，开销可忽略）：
     * 停止 / 新拉起 / 网络恢复都会提前结束。返回 false = 该退出监督循环。
     */
    private fun sleepInterruptible(gen: Long, seconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (running && gen == generation) {
            if (System.currentTimeMillis() >= deadline) return true
            if (networkJustRecovered) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * 单次连接尝试（2.x 时序：createNetwork → getNetwork → establishVpn → startTun →
     * getApi → runChangeLoop）。
     * 返回 null = 干净退出（用户停止 / 被接管）；否则带失败原因与可重试性。
     * finally 只释放**本轮**的 native 实例与 TUN；以 generation 校验防止
     * 「接管竞态下误关新 attempt 的实例」。
     */
    private fun tryConnectOnce(gen: Long): AttemptResult? {
        var net: VntNetwork? = null
        val hadConnected = java.util.concurrent.atomic.AtomicBoolean(false)
        // 连接成功时刻（毫秒）：用于判定「是否稳定连接」。存活不足一个探活周期
        // 即断的，多为探活/过渡态抖动，不应重置退避计数。
        var connectedAtMs = 0L
        try {
            networkJustRecovered = false     // 清掉上一轮残留标志

            // 0) 全局初始化（幂等，官方 AndroidVpnExample 同款）
            if (!VntManager.init()) {
                throw FatalVntException("Rust 核心初始化失败")
            }

            val cfg = VntMeshPrefs.load(this)
            val config = VntConfigBuilder.build(this, cfg)

            // 1) 创建网络实例（后台连服务器并注册）
            net = VntManager.createNetwork(config)
                ?: throw VntException("createNetwork 返回 null（配置或网络不可达）")
            val nativeNet = net
            synchronized(nativeLock) { network = nativeNet }

            // 2) 等待注册完成，取分配的网段信息
            val registered: NetworkResult = nativeNet.getNetwork()
            val cidr = registered.toCidr()
            establishedCidr = cidr
            currentMtu = cfg.mtu.coerceIn(MIN_MTU, MAX_MTU)

            // 3) 建立 VPN 接口并把 detached fd 交给 VNT
            val tunFd = establishVpn(cidr, currentMtu, registered.getRoutes())
            nativeNet.startTun(tunFd)

            // 4) 对外发布查询接口 + 状态
            val api: VntApi = nativeNet.getApi()
            if (gen == generation) {
                hadConnected.set(true)
                connectedAtMs = System.currentTimeMillis()
                VntMeshController.onConnected(api, registered, cfg, currentMtu)
                promoteForeground("已连接 · ${registered.getIp()}", connected = true)
                LogThrottle.summaryIfAny("vnt-retry", TAG, "组网重连")
                LogThrottle.reset("vnt-attempt-fail")
                DiagLog.i(TAG, "VNT 已连接：$cidr")
            }

            // 4.5) 探活线程：nextEvent 断线感知有延迟（依赖 native 心跳超时），
            //      这里每 5 秒查一次服务端连接状态，失联即主动 stop 触发重连，
            //      让通知与界面「未连上服务端」保持同步。
            startProbe(nativeNet, api, gen)

            // 5) 变更循环（阻塞 nextEvent，直到实例停止或异常）
            runChangeLoop(nativeNet, gen)

            // 到这里若还在跑且仍是当前代次 ⇒ native 自行退出（instance_stopped）——按可重试处理
            if (running && gen == generation) {
                return AttemptResult(true, "组网实例已停止（视为断线）", hadConnected.get(), isStable(hadConnected.get(), connectedAtMs))
            }
            return null                                        // 用户停止 / 被接管
        } catch (e: Throwable) {
            val retryable = when {
                e is FatalVntException -> false                // 初始化失败 / VPN 授权被抢
                e is VntException && e.message?.contains("createNetwork") == true -> false
                else -> true                                   // nextEvent 抛异常 / 普通异常
            }
            // 注意：createNetwork 返回 null 时 net 仍为 null，上面的 VntException 分支已处理致命；
            // 但更稳的做法是把「establish 返回 null」单独识别为致命（见 establishVpn 抛 FatalVntException）
            if (LogThrottle.shouldEmit("vnt-attempt-fail", e.javaClass.simpleName + "|" + e.message, TAG)) {
                DiagLog.e(TAG, "组网尝试失败", e)
            }
            return AttemptResult(retryable, e.message ?: "未知异常", hadConnected.get(), isStable(hadConnected.get(), connectedAtMs))
        } finally {
            // 探活线程随本轮尝试结束（无论成败），避免旧代次探活线程残留误停新实例。
            stopProbe()
            // 只释放本轮自己的实例；generation 已变（被接管）时不碰共享字段。
            val stillCurrent = gen == generation
            if (stillCurrent) synchronized(nativeLock) { network = null }
            // detachFd() 后 fd 已归 native 所有，由 net.stop() 负责关闭；
            // Java 侧绝不能再 close，否则 double-close 会误关系统复用后的 fd。
            runCatching { net?.stop() }
        }
    }

    /**
     * 按参数建立 VPN 接口并返回 detached fd。
     *
     * - `addAddress` 不会自动生成路由，必须显式把虚拟网段指向 tun；
     * - **不加 0.0.0.0/0**；
     * - 虚拟 IPv6 与固定路由按官方骨架补齐；
     * - `subnetRoutes` 是子网转发预留位（本期恒为空）。
     */
    private fun establishVpn(cidr: String, mtu: Int, subnetRoutes: List<String>): Int {
        val parts = cidr.split("/", limit = 2)
        val ip = parts[0]
        val prefixLen = parts.getOrNull(1)?.toIntOrNull() ?: 24

        val builder = Builder()
            .setMtu(mtu.coerceIn(MIN_MTU, MAX_MTU))
            .addAddress(ip, prefixLen)
            .addAddress(VirtualIpv6.address(ip), VirtualIpv6.PREFIX_LEN)
            .addRoute("fd76:6e74::", VirtualIpv6.PREFIX_LEN)
            .addRoute("ff00::", 8)
            .setSession("Bridge VNT")

        // Android 的 addAddress 不自动生成路由，显式把虚拟网段指向 tun
        builder.addRoute(networkAddress(ip, prefixLen), prefixLen)

        // 服务端下发的完整入站路由（格式 "CIDR,下一跳"），逐个取出 CIDR 部分
        for (route in subnetRoutes) {
            val cidrPart = route.split(",", limit = 2)[0]
            val rp = cidrPart.split("/", limit = 2)
            if (rp.size == 2) {
                runCatching {
                    val addr = rp[0]
                    val prefix = rp[1].toInt()
                    if (prefix in 0..128) builder.addRoute(addr, prefix)
                }
            }
        }

        val pfd: ParcelFileDescriptor = builder.establish()
            ?: throw FatalVntException(
                "VpnService.Builder.establish() 返回 null（VPN 授权可能已被撤销，" +
                    "或系统已把唯一 VPN 通道给了其它 App）"
            )
        return pfd.detachFd()
    }

    /**
     * 运行期变更循环：nextEvent 只吐两类事件（InstanceStopped / Changed），
     * **没有设备上下线事件**——设备列表只能靠 [VntMeshController] 的 5 秒轮询。
     */
    private fun runChangeLoop(net: VntNetwork, gen: Long) {
        while (running && gen == generation) {
            val event: RuntimeEvent = net.nextEvent()
            if (gen != generation) break
            if (event.isInstanceStopped) {
                DiagLog.i(TAG, "组网实例已停止，退出变更循环")
                break
            }
            val change: RuntimeChange = event.change ?: continue
            val result: ChangeApplyResult = if (change.isVpnRebuild || change.isInstanceRebuild) {
                // 快照未携带固定 IP（服务端动态分配）时保持当前接口地址
                val c = change.ip ?: establishedCidr
                    ?: throw VntException("运行期变更缺少可用 CIDR")
                val m = (change.mtu ?: currentMtu).coerceIn(MIN_MTU, MAX_MTU)
                val fd = establishVpn(c, m, change.routes)
                establishedCidr = c
                currentMtu = m
                net.applyRuntimeChange(fd)
            } else {
                // 纯策略/服务器类变更：原地生效，无需新接口
                net.applyRuntimeChange()
            }
            if (!result.isApplied) {
                DiagLog.w(TAG, "运行期变更未应用：$result，退出循环")
                break
            }
            // 实例可能被 native 内部重建，刷新对外查询接口
            runCatching { VntMeshController.onApiRefreshed(net.getApi()) }
        }
    }

    /** 收尾兜底：清共享字段（正常路径各 attempt 的 finally 已自行清理）。 */
    private fun releaseNative() {
        val n = synchronized(nativeLock) { network }
        runCatching { n?.stop() }
        synchronized(nativeLock) { network = null }
    }

    /**
     * 是否「稳定连接」：本轮曾连上，且从连上到结束存活超过一个探活周期。
     * 探活/过渡态抖动导致的「连上即断」存活极短，不应重置退避计数。
     */
    private fun isStable(hadConnected: Boolean, connectedAtMs: Long): Boolean {
        if (!hadConnected || connectedAtMs <= 0L) return false
        return System.currentTimeMillis() - connectedAtMs >= PROBE_INTERVAL_MS
    }

    // ------------------------------------------------------------------
    // 探活（v0.2.135）：只更新通知，不 stop 实例——「服务器断连」≠「实例死亡」
    // ------------------------------------------------------------------

    /**
     * 启动探活线程。连接成功后调用，独立于 worker（监督循环）：
     * 每 [PROBE_INTERVAL_MS] 查一次 [VntApi.getServerList]，仅根据服务端连接
     * 状态**更新通知**，**绝不 stop 实例**。
     *
     * 为什么（v0.2.135 根治，此前 v0.2.133/134 方向错了）：
     * 「连不到 VNTS 服务器」时，VNT 实例其实**还活着**（已拿到 VIP、TUN 接口正常），
     * 只是与服务器的实时连接断了；`getServerList().connected=false` 反映的是
     * 「服务器断连」，**不是「实例死亡」**。若据此 stop 实例并重连，重连后服务器
     * 依然连不上 → `connected` 依然 false → 又 stop，陷入死循环（高频刷屏）。
     * 实例真正死亡应由 [runChangeLoop] 的 `nextEvent()` 发 `instance_stopped`
     * 驱动重连（既有正确机制），探活不越俎代庖。
     *
     * 设计要点：
     * - **仅更新通知**：连续 [DOWN_CONFIRM_COUNT] 次（约 15s）服务端全失联 →
     *   通知改「服务器连接中断」；任一次发现「仍连」→ 通知改回「已连接」。
     * - **首查延迟一个周期**：避开连接建立瞬间的过渡态假失联。
     * - **独立 sleep**（[probeSleep]）：不响应 [networkJustRecovered]，避免 VPN
     *   接口建立触发的 onAvailable 打断等待、把「连续 N 次」压到 1 秒内凑齐。
     * - 列表为空（查询异常/时序）视为「尚未确认」，不更新通知（保守）。
     *
     * 用 [gen] 做代次校验，停止/新拉起/探活线程切换时旧线程自行退出。
     */
    private fun startProbe(net: VntNetwork, api: VntApi, gen: Long) {
        stopProbe()
        val t = Thread({
            // 首查前先睡满一个周期，避开连接建立瞬间的过渡态假失联
            if (!probeSleep(gen, (PROBE_INTERVAL_MS / 1000).toInt())) return@Thread
            var downCount = 0
            while (running && gen == generation) {
                try {
                    val servers = api.getServerList()
                    val allDown = servers.isNotEmpty() && servers.none { it.isConnected }
                    if (allDown) {
                        downCount++
                        if (downCount >= DOWN_CONFIRM_COUNT && running && gen == generation) {
                            // 服务器持续断连：仅更新通知，不 stop 实例
                            DiagLog.w(TAG, "探活：服务端持续失联，通知改「服务器连接中断」")
                            promoteForeground("服务器连接中断 · 等待服务端恢复", connected = false)
                        }
                    } else if (servers.isNotEmpty()) {
                        // 服务端「仍连」：若之前是「中断」状态，恢复通知为「已连接」
                        if (downCount >= DOWN_CONFIRM_COUNT) {
                            DiagLog.i(TAG, "探活：服务端恢复，通知改回「已连接」")
                            promoteForeground("已连接 · ${registeredIpOrUnknown()}", connected = true)
                        }
                        downCount = 0
                    }
                    // 列表为空：不更新通知、也不清零（保守，交给 nextEvent 兜底）
                } catch (e: Throwable) {
                    // getServerList 在实例停止/重建瞬间会抛 VntException，属正常，
                    // 不升级为断线；nextEvent 会以 instance_stopped 兜底。
                    DiagLog.e(TAG, "探活查询失败（忽略，交给 nextEvent 兜底）", e)
                }
                if (!probeSleep(gen, (PROBE_INTERVAL_MS / 1000).toInt())) break
            }
        }, "vnt-probe").apply {
            isDaemon = true
            start()
        }
        probeThread = t
    }

    /**
     * 探活专用可中断等待：**只**响应「代次变了」与「线程中断」，**不响应
     * [networkJustRecovered]**。探活要的是稳定的固定周期，不能被网络回调打断
     * （VPN 接口建立的 onAvailable 会把 `networkJustRecovered` 置 true，若复用
     * [sleepInterruptible] 会导致探活等待被瞬间打断、疯狂轮询）。
     */
    private fun probeSleep(gen: Long, seconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (running && gen == generation) {
            if (System.currentTimeMillis() >= deadline) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** 当前已分配的虚拟 IP（探活恢复通知时用），未取得则回退占位符。 */
    private fun registeredIpOrUnknown(): String = establishedCidr?.substringBefore("/") ?: "已连接"

    /** 停止探活线程（幂等）：本轮尝试结束或新尝试接管时调用。 */
    private fun stopProbe() {
        val t = probeThread ?: return
        probeThread = null
        t.interrupt()
    }

    // ------------------------------------------------------------------
    // 停止
    // ------------------------------------------------------------------

    /**
     * 停止：只发 `stop()` 信号让 nextEvent 返回 + 唤醒退避等待，
     * **不在别处 close native 对象**，由 worker 的 finally 统一收尾。
     */
    private fun shutdown() {
        DiagLog.i(TAG, "收到停止指令")
        // v0.2.33：先记录「是否确有运行中的连接」。服务可能被 ACTION_STOP 直接拉起
        // （未组网时重复下发 vnt_stop / 通知栏点停止），此时 worker 根本没启动，
        // 必须在下面直接收尾，否则空前台服务常驻、状态卡在 STOPPING。
        val hadConnection = running || worker?.isAlive == true
        ++generation
        running = false
        VntMeshController.onStopping()
        val n = synchronized(nativeLock) { network }
        runCatching { n?.stop() }
        if (!hadConnection) {
            VntMeshController.clearApi()
            VntMeshController.onStopped()
            releaseNative()
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        ++generation
        stopProbe()
        val n = synchronized(nativeLock) { network }
        runCatching { n?.stop() }
        synchronized(nativeLock) { network = null }
        unregisterNetworkCallback()
        VntMeshController.clearApi()
        worker = null
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
