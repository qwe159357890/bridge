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
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import my.bridge.com.MainActivity
import my.bridge.com.R
import my.bridge.com.core.common.DiagLog
import my.bridge.com.core.common.LogThrottle
import top.wherewego.vnt.jni.CallBack
import top.wherewego.vnt.jni.Config
import top.wherewego.vnt.jni.IpUtils
import top.wherewego.vnt.jni.Vnt
import top.wherewego.vnt.jni.param.ConnectInfo
import top.wherewego.vnt.jni.param.DeviceConfig
import top.wherewego.vnt.jni.param.DeviceInfo
import top.wherewego.vnt.jni.param.ErrorInfo
import top.wherewego.vnt.jni.param.HandshakeInfo
import top.wherewego.vnt.jni.param.PeerClientInfo
import top.wherewego.vnt.jni.param.RegisterInfo

/**
 * VNT 组网前台服务（v0.2.32，1.x / VntApp v1.2.9；v0.2.38 加断线自动重连）——
 * 持有 [VpnService] 通道与 [Vnt] 实例。
 *
 * ★ 与 2.x 的**时序完全相反**（这是 v0.2.32 重写的核心）：
 * - 2.x：Java 先 `VpnService.Builder.establish()` 拿 fd → `net.startTun(fd)` 传进 native；
 * - 1.x：`new Vnt(Config, CallBack)` → native 在 **`CallBack.generateTun(DeviceConfig)` 回调里
 *   反过来向 Java 要 fd**，Java 在回调内建 VPN 接口并 `return fd` → 之后 `awaitTimeout` 循环。
 *   对照官方 `MyVpnService.run0()`。
 *
 * ★ v0.2.38 断线自动重连（本次变更的核心，见台账 §七）：
 * - **根因**：v0.2.32 里运行期 native 报错（切网后 `ErrorInfo.code == Disconnect`）→
 *   worker 直接 throw → 释放 + `stopSelf()` → 服务死亡，ERROR 终态，无人重试。
 *   官方 VntApp 靠 `START_STICKY` + null intent 自动恢复，v0.2.30 为「退出即整体结束」
 *   有意删掉了 STICKY，却没有在应用层补替代 ⇒ 断线后永远躺平。
 * - **修复：进程内监督重连**——worker 改为「一次尝试 [tryConnectOnce] + 退避重试」的
 *   监督循环，**可重试失败不停服务、不 stopSelf**：
 *   - 不需要新的 `startForegroundService` 调用 ⇒ 天然绕开 Android 12+ 后台启动前台服务
 *     限制，**不新增 FGS 义务点**（崩溃台账 §5 无新风险）；
 *   - 错误分类：`TokenError` / `InvalidIp` / VPN 授权被抢（establish 返回 null）为**致命**，
 *     转 ERROR 终态等用户处理；`Disconnect` / `AddressExhausted` / `IpAlreadyExists` /
 *     `Unknown` / 普通异常为**可重试**；
 *   - 指数退避 2/4/8/16/30/60s 封顶（照 `RelayClient` 既有范式）；连接成功过即重置退避；
 *   - `registerDefaultNetworkCallback`：网络恢复/切换瞬间唤醒退避等待并重置退避
 *     （照 `AudioRelayService` 既有范式）——否则「网络刚恢复还要干等满退避计时」。
 * - 生命周期语义不变：**只有用户点「停止组网」/ `AppExit.kill` / 致命错误才真正停止**；
 *   监督循环期间状态为 [VntStatus.RECONNECTING]，UI 主按钮仍显示「停止组网」可随时终止。
 *
 * ★ 崩溃台账 §5 检查清单（逐条落实，v0.2.32 起未变）：
 * - manifest 已声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限；
 * - 所有 `startForegroundService` 调用点在 [VntMeshController.start] 里 try/catch；
 * - **onCreate 挂前台**，且 `startForeground` 有 try/catch + 失败 `stopSelf()`；
 * - **onStartCommand 对每次拉起都幂等补挂前台**（不依赖 onCreate 一定执行）；
 * - 返回 `START_NOT_STICKY`：进程被杀后系统**不自动复活**服务（冷启动由 `VntAutoStart` 恢复）；
 * - 调用点已登记到 `docs/dev/crash-ledger.md` §3.1。
 *
 * ★ 路由红线：**绝不加 `0.0.0.0/0`**（会劫持整机流量）。
 *
 * ★ native 对象释放：官方 v1.2.9 的 `MyVpnService` 从不调用 `Vnt.close()`（drop0），
 * 仅 `stop()` + 置 null。这里在 worker **确认退出 awaitTimeout 循环之后**才 `close()`，
 * 避免「另一个线程还在 awaitTimeout 时 drop 掉 Rust 对象」的 use-after-free；
 * `shutdown()` / `onDestroy()` 只发 `stop()` 信号，不做 `close()`。
 * 重连循环每轮 attempt 在 [tryConnectOnce] 的 finally 里**只释放本轮自己的实例**
 * （以 generation 校验防「接管竞态误关新实例」，见台账 §7.4）。
 */
class VntVpnService : VpnService() {

    companion object {
        const val ACTION_START = "my.bridge.com.vnt.START"
        const val ACTION_STOP = "my.bridge.com.vnt.STOP"

        /** 独立通知 ID：保活 2001、中继 2002、媒体采集 2101，避免误删常驻通知。 */
        const val NOTIFICATION_ID = 2102
        const val CHANNEL_ID = "vntConnectionChannel"

        private const val TAG = "组网"

        /** 指数退避表（秒）：2/4/8/16/30/60 封顶，照 RelayClient 既有范式。 */
        private val BACKOFF_SEC = intArrayOf(2, 4, 8, 16, 30, 60)

        @Volatile
        private var instance: VntVpnService? = null

        fun isRunning(): Boolean = instance?.running == true
    }

    private val nativeLock = Any()

    /** native 实例；由 [nativeLock] 保护。构造完成前为 null。 */
    private var vnt: Vnt? = null

    /**
     * VPN 接口的 ParcelFileDescriptor。
     * ⚠️ 官方用 `getFd()`（不是 `detachFd()`）并**持有 PFD 对象**直到停止；
     * 若这里改成 detachFd 就必须自己 `Os.close(fd)`，且 PFD 被 GC 会提前关掉 fd。
     */
    private var pfd: ParcelFileDescriptor? = null

    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 代次：停止/新拉起时自增，让阻塞在 awaitTimeout / 退避等待的旧循环退出。 */
    @Volatile
    private var generation = 0L

    /** native 在 `Vnt()` 构造期间回调的错误（此时 vnt 尚未赋值，不能调 stop）。 */
    @Volatile
    private var startupError: String? = null

    @Volatile
    private var startupErrorCode: ErrorInfo.ErrorCodeEnum? = null

    /** native 在运行期回调的错误。 */
    @Volatile
    private var runtimeError: String? = null

    @Volatile
    private var runtimeErrorCode: ErrorInfo.ErrorCodeEnum? = null

    /** 握手时服务端返回的版本，供 UI 核对 1.x 服务端。 */
    @Volatile
    private var serverVersion: String? = null

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
            startForeground(NOTIFICATION_ID, buildNotification(text, connected))
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
                // 只置标志，不在回调里做任何重活（回调跑在系统线程）；
                // 监督循环的退避等待以 250ms 粒度轮询该标志（最长 60s，开销可忽略）。
                // v0.2.130 日志治理：改前此处每次网络波动都打一条 I 日志（实测 42 分钟
                // 18 条，是全项目最高频的单点），但它只说明「置了个布尔标志」，真正的
                // 恢复结果由下面「VNT 已连接」状态变更日志体现，无独立排障价值 → 删除。
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
        clearAttemptErrors()
        val gen = ++generation
        worker = Thread({ runSupervisor(gen) }, "vnt-network").apply {
            isDaemon = true
            start()
        }
    }

    private fun clearAttemptErrors() {
        startupError = null
        startupErrorCode = null
        runtimeError = null
        runtimeErrorCode = null
        serverVersion = null
    }

    /** 一次尝试的结局：`null` = 干净退出（停止/被接管），否则为失败与是否可重试。 */
    private class AttemptResult(
        val retryable: Boolean,
        val reason: String,
        /** 本次尝试是否曾连接成功（用于成功后重置退避计数）。 */
        val hadConnected: Boolean,
    )

    /** 致命错误（重试无意义）：token/配置错、VPN 授权被抢。 */
    private class FatalVntException(message: String) : IllegalStateException(message)

    /**
     * 监督循环（v0.2.38）：失败 → 退避 → 重建，直到用户停止 / 被新拉起接管 / 致命错误。
     * 服务**不因可重试失败而 stopSelf**——这是「断线自动重连」的核心。
     */
    private fun runSupervisor(gen: Long) {
        var attempt = 0
        var fatal: String? = null
        while (running && gen == generation) {
            val res = tryConnectOnce(gen) ?: break
            if (res.hadConnected) attempt = 0          // 一次成功即重置退避
            if (!res.retryable) {
                fatal = res.reason
                break
            }
            attempt++
            val delaySec = BACKOFF_SEC[(attempt - 1).coerceAtMost(BACKOFF_SEC.size - 1)]
            // v0.2.130：同一失败原因连续重连时静默计数，只在首轮/原因变化时详记。
            // 签名取 res.reason（不含 attempt 次数），失败原因变化视为状态翻转重新详记。
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

        // ---- 收尾（与 v0.2.32 的 finally 语义对齐）----
        val superseded = running                  // running 仍为 true = 新 startConnect 已接管
        if (!superseded) releaseNative()
        VntMeshController.clearProvider()
        if (fatal != null) {
            // v0.2.31 坑沿用：失败保留 ERROR，别被 onStopped() 覆盖成「未连接」
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
     * 单次连接尝试（原 v0.2.32 的 runConnect 主体）。
     * 返回 null = 干净退出（用户停止 / 被接管）；否则带失败原因与可重试性。
     * finally 只释放**本轮**的 native 实例与 TUN；以 generation 校验防止
     * 「接管竞态下误关新 attempt 的实例」（详见台账 §7.4 改后回填）。
     */
    private fun tryConnectOnce(gen: Long): AttemptResult? {
        var v: Vnt? = null
        // generateTun 在 native 线程执行、worker 线程读取 ⇒ 必须原子引用保证可见性
        val hadConnected = java.util.concurrent.atomic.AtomicBoolean(false)
        // 本轮自己的 TUN 句柄：关闭只认它，共享 [pfd] 字段仅供 onDestroy 兜底
        val localPfd = java.util.concurrent.atomic.AtomicReference<ParcelFileDescriptor?>(null)
        try {
            clearAttemptErrors()
            networkJustRecovered = false     // 清掉上一轮残留标志，避免陈旧「网络恢复」跳过退避
            val cfg = VntMeshPrefs.load(this)
            val config: Config = VntConfigBuilder.build(this, cfg)
            val mtu = config.mtu ?: VntMeshConfig.DEFAULT_MTU

            val callback = object : CallBack {
                override fun success() {
                    // v0.2.130：每轮重连都会触发，签名固定 → 连续风暴时静默计数。
                    LogThrottle.event(
                        "vnt-cb-success", "registered", "I", TAG,
                        "native 回调 success（注册完成）",
                    )
                }

                override fun createTun(info: DeviceInfo?) {
                    // 仅 windows/linux/macos 使用，Android 走 generateTun
                }

                override fun connect(info: ConnectInfo?) {
                    // v0.2.130：签名含服务端地址（地址变化=状态翻转要重新详记），
                    // 不含 count（每次递增会导致节流永不生效）。
                    LogThrottle.event(
                        "vnt-cb-connect", "addr=${info?.address}", "I", TAG,
                        "连接服务端：${info?.address}（第 ${info?.count} 次）",
                    )
                }

                override fun handshake(info: HandshakeInfo?): Boolean {
                    serverVersion = info?.version
                    // v0.2.130：签名含版本+指纹，服务端升级/换端时重新详记。
                    LogThrottle.event(
                        "vnt-cb-handshake", "v=${info?.version} fp=${info?.finger}", "I", TAG,
                        "握手：服务端版本=${info?.version}，指纹=${info?.finger}",
                    )
                    return true                 // 确认握手
                }

                override fun register(info: RegisterInfo?): Boolean {
                    val ip = info?.let { IpUtils.intToIpAddress(it.virtualIp) } ?: "?"
                    // v0.2.130：签名含虚拟 IP，换 IP 时重新详记。
                    LogThrottle.event(
                        "vnt-cb-register", "ip=$ip", "I", TAG,
                        "注册成功：$ip",
                    )
                    return true                 // 确认注册信息
                }

                /**
                 * ★ 1.x 专属：native 在这个回调里**向 Java 要 fd**。
                 * 官方 `MyVpnService.run0()` 同款实现：建 VpnService.Builder、
                 * `establish()` 得到 PFD、`return pfd.fd`（不是 detachFd）。
                 */
                override fun generateTun(info: DeviceConfig?): Int {
                    val dc = info ?: throw IllegalStateException("native 返回空的 DeviceConfig")
                    val ip = IpUtils.intToIpAddress(dc.virtualIp)
                    val prefix = IpUtils.subnetMaskToPrefixLength(dc.virtualNetmask)
                    // 官方：ipRoute = gateway & netmask，显式 addRoute（Android 的 addAddress
                    // 不会自动生成路由）；绝不加 0.0.0.0/0。
                    val route = IpUtils.intToIpAddress(dc.virtualGateway and dc.virtualNetmask)

                    val ext = dc.externalRoute
                    if (!ext.isNullOrEmpty()) {
                        DiagLog.i(TAG, "收到 ${ext.size} 条点对网额外路由，本期不做，忽略")
                    }

                    val builder = Builder()
                        .setSession("Bridge VNT")
                        .setBlocking(false)
                        .setMtu(mtu.coerceIn(VntMeshConfig.MIN_MTU, VntMeshConfig.MAX_MTU))
                        .addAddress(ip, prefix)
                        .addRoute(route, prefix)
                        .allowFamily(OsConstants.AF_INET)

                    val f: ParcelFileDescriptor = builder.establish()
                        ?: throw FatalVntException(
                            "VpnService.Builder.establish() 返回 null（VPN 授权可能已被撤销，" +
                                "或系统已把唯一 VPN 通道给了其它 App）"
                        )
                    localPfd.set(f)
                    synchronized(nativeLock) { pfd = f }

                    if (gen == generation) {
                        hadConnected.set(true)
                        VntMeshController.onConnected(ip, prefix, cfg, mtu, serverVersion)
                        promoteForeground("已连接 · $ip", connected = true)
                        // v0.2.130：连接成功 = 风暴收尾。先打汇总（若此前有静默轮次），
                        // 再打状态变更骨架日志；两者都是排障必需，不参与节流。
                        LogThrottle.summaryIfAny("vnt-retry", TAG, "组网重连")
                        // 清「失败类」键：保证下一轮新风暴的首条失败/错误仍详记；
                        // 过程类键（连接服务端/握手/注册/success/stop）保留长期节流，
                        // 不因每次重连成功就重新打印一遍。
                        LogThrottle.reset("vnt-attempt-fail")
                        LogThrottle.reset("vnt-cb-error")
                        DiagLog.i(TAG, "VNT 已连接：$ip/$prefix，服务端版本 ${serverVersion ?: "?"}")
                    }
                    return f.fd
                }

                override fun peerClientList(infoArray: Array<PeerClientInfo>?) {
                    // 设备列表统一由 Controller 经 Vnt.list() 前台轮询，这里不重复处理
                }

                override fun error(info: ErrorInfo?) {
                    val e = info ?: return
                    val text = "${e.code}" + (e.msg?.let { "：$it" } ?: "")
                    // v0.2.130：同一错误码+消息连续出现时静默计数，换错误时重新详记。
                    LogThrottle.event("vnt-cb-error", text, "E", TAG, "native 回调错误：$text")
                    // ★ 构造期间 vnt 尚未赋值，绝不能在这里调 v.stop()
                    //   （官方 MyVpnService 这里会 NPE，属其已知缺陷）。
                    val ready = synchronized(nativeLock) { vnt != null }
                    if (ready) {
                        runtimeError = text
                        runtimeErrorCode = e.code
                    } else {
                        startupError = text
                        startupErrorCode = e.code
                    }
                }

                override fun stop() {
                    LogThrottle.event("vnt-cb-stop", "stopped", "I", TAG, "native 回调 stop")
                }
            }

            // ★ 构造是同步的：内部完成注册并回调 generateTun，返回后才拿到 Vnt 实例
            v = Vnt(config, callback)
            val nativeVnt = v ?: throw IllegalStateException("Vnt 构造返回空实例")
            synchronized(nativeLock) { vnt = nativeVnt }

            VntMeshController.onVntReady(
                PeerListProvider { synchronized(nativeLock) { vnt?.list() } }
            )

            // 构造期间若已发生错误（TokenError / AddressExhausted / InvalidIp ...），
            // 不能假装连上了，立刻按错误码分类。
            startupError?.let { throw IllegalStateException(it) }

            // 运行期循环：awaitTimeout(2000) 是 1.x 实例保活与接收停止事件的唯一机制，
            // 不发网络包；设备列表刷新由 Controller 在「前台 + 页面可见」时另行调度。
            while (running && gen == generation) {
                runtimeError?.let { throw IllegalStateException(it) }
                val out = nativeVnt.awaitTimeout(2000)
                if (out || gen != generation) break
            }
            // 到这里若还在跑且仍是当前代次 ⇒ native 自行退出（stop 回调）——按可重试处理
            if (running && gen == generation) {
                return AttemptResult(true, "native 实例自行退出（视为断线）", hadConnected.get())
            }
            return null                                        // 用户停止 / 被接管
        } catch (e: Throwable) {
            // v0.2.130：异常签名取「类名+消息+错误码」，同一故障连续重连时静默计数。
            // 注意 DiagLog.e(tag, msg, throwable) 会附堆栈；节流命中时不产生任何行。
            val code = runtimeErrorCode ?: startupErrorCode
            val sig = "${e.javaClass.simpleName}|${e.message}|$code"
            if (LogThrottle.shouldEmit("vnt-attempt-fail", sig, TAG)) {
                DiagLog.e(TAG, "组网尝试失败", e)
            }
            val retryable = when {
                e is FatalVntException -> false                // VPN 授权被抢
                code == ErrorInfo.ErrorCodeEnum.TokenError -> false
                code == ErrorInfo.ErrorCodeEnum.InvalidIp -> false
                else -> true    // Disconnect / AddressExhausted / IpAlreadyExists / Unknown / 普通异常
            }
            return AttemptResult(retryable, e.message ?: "未知异常", hadConnected.get())
        } finally {
            // 只释放本轮自己的实例；generation 已变（被接管）时不碰共享字段，
            // 防止旧 worker 关掉新 attempt 刚建好的 vnt/pfd。
            val stillCurrent = gen == generation
            if (stillCurrent) synchronized(nativeLock) { vnt = null; pfd = null }
            runCatching { v?.stop() }
            runCatching { v?.close() }
            runCatching { localPfd.get()?.close() }
        }
    }

    /** 收尾兜底：清共享字段（正常路径各 attempt 的 finally 已自行清理）。 */
    private fun releaseNative() {
        val v = synchronized(nativeLock) { vnt }
        runCatching { v?.stop() }
        synchronized(nativeLock) {
            vnt = null
            val f = pfd
            pfd = null
            runCatching { f?.close() }
        }
    }

    // ------------------------------------------------------------------
    // 停止
    // ------------------------------------------------------------------

    /**
     * 停止：只发 `stop()` 信号让 awaitTimeout 返回 + 唤醒退避等待，
     * **不在别处 close native 对象**，由 worker 的 finally 统一收尾
     * （避免并发 drop 造成 use-after-free）。
     */
    private fun shutdown() {
        DiagLog.i(TAG, "收到停止指令")
        // v0.2.33：先记录「是否确有运行中的连接」。服务可能被 ACTION_STOP 直接拉起
        // （未组网时重复下发 vnt_stop / 通知栏点停止），此时 worker 根本没启动，
        // worker 的 finally 里 onStopped()/stopSelf() 永远不会被调用 ⇒ 空前台服务
        // 常驻、状态卡在 STOPPING。这类情况必须在下面直接收尾。
        val hadConnection = running || worker?.isAlive == true
        ++generation
        running = false
        VntMeshController.onStopping()
        val v = synchronized(nativeLock) { vnt }
        runCatching { v?.stop() }
        if (!hadConnection) {
            VntMeshController.clearProvider()
            VntMeshController.onStopped()
            releaseNative()
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        ++generation
        val v = synchronized(nativeLock) { vnt }
        runCatching { v?.stop() }
        synchronized(nativeLock) { vnt = null }
        val f = synchronized(nativeLock) {
            val x = pfd
            pfd = null
            x
        }
        runCatching { f?.close() }
        unregisterNetworkCallback()
        VntMeshController.clearProvider()
        worker = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
