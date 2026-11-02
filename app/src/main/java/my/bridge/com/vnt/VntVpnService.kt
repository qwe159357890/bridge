package my.bridge.com.vnt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import my.bridge.com.MainActivity
import my.bridge.com.R
import my.bridge.com.core.common.DiagLog
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
 * VNT 组网前台服务（v0.2.32，1.x / VntApp v1.2.9）——持有 [VpnService] 通道与 [Vnt] 实例。
 *
 * ★ 与 2.x 的**时序完全相反**（这是本次重写的核心）：
 * - 2.x：Java 先 `VpnService.Builder.establish()` 拿 fd → `net.startTun(fd)` 传进 native；
 * - 1.x：`new Vnt(Config, CallBack)` → native 在 **`CallBack.generateTun(DeviceConfig)` 回调里
 *   反过来向 Java 要 fd**，Java 在回调内建 VPN 接口并 `return fd` → 之后 `awaitTimeout` 循环。
 *   对照官方 `MyVpnService.run0()`。
 *
 * 调用时序：
 * `Vnt(config, callback)` → connect → handshake → register → **generateTun(建 TUN 返回 fd)** →
 * success → `awaitTimeout(2000)` 循环（期间 `list()` 由 Controller 前台 5 秒轮询）→
 * 停止时 `vnt.stop()` 让 awaitTimeout 返回 true → `vnt.close()` 释放 native。
 *
 * ★ 崩溃台账 §5 检查清单（逐条落实）：
 * - manifest 已声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限；
 * - 所有 `startForegroundService` 调用点在 [VntMeshController.start] 里 try/catch；
 * - **onCreate 挂前台**，且 `startForeground` 有 try/catch + 失败 `stopSelf()`；
 * - **onStartCommand 对每次拉起都幂等补挂前台**（不依赖 onCreate 一定执行）；
 * - 返回 `START_NOT_STICKY`：进程被杀后系统**不自动复活**服务；
 * - 调用点已登记到 `docs/dev/crash-ledger.md` §3.1。
 *
 * ★ 路由红线：**绝不加 `0.0.0.0/0`**（会劫持整机流量）。
 *
 * ★ native 对象释放：官方 v1.2.9 的 `MyVpnService` 从不调用 `Vnt.close()`（drop0），
 * 仅 `stop()` + 置 null。这里在 worker **确认退出 awaitTimeout 循环之后**才 `close()`，
 * 避免「另一个线程还在 awaitTimeout 时 drop 掉 Rust 对象」的 use-after-free；
 * `shutdown()` / `onDestroy()` 只发 `stop()` 信号，不做 `close()`。
 */
class VntVpnService : VpnService() {

    companion object {
        const val ACTION_START = "my.bridge.com.vnt.START"
        const val ACTION_STOP = "my.bridge.com.vnt.STOP"

        /** 独立通知 ID：保活 2001、中继 2002、媒体采集 2101，避免误删常驻通知。 */
        const val NOTIFICATION_ID = 2102
        const val CHANNEL_ID = "vntConnectionChannel"

        private const val TAG = "组网"

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

    /** 代次：停止时自增，让阻塞在 awaitTimeout 的旧循环退出。 */
    @Volatile
    private var generation = 0L

    /** native 在 `Vnt()` 构造期间回调的错误（此时 vnt 尚未赋值，不能调 stop）。 */
    @Volatile
    private var startupError: String? = null

    /** native 在运行期回调的错误。 */
    @Volatile
    private var runtimeError: String? = null

    /** 握手时服务端返回的版本，供 UI 核对 1.x 服务端。 */
    @Volatile
    private var serverVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // 崩溃台账 §5：onCreate 立刻挂前台（try/catch + 失败 stopSelf 在 promoteForeground 内）
        promoteForeground("正在准备虚拟网络…", connected = false)
    }

    /**
     * 每次拉起都履行一次前台化义务（义务按次计，服务运行中重复拉起不走 onCreate）。
     * 返回 START_NOT_STICKY：进程被杀不自动复活，符合「退出即整体结束」的最高优先级。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground("正在准备虚拟网络…", connected = false)
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> startConnect()
            else -> DiagLog.i(TAG, "onStartCommand 无有效 action，忽略（不自动重连）")
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
            .setContentTitle(if (connected) "VNT 虚拟网络已连接" else "VNT 正在启动")
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
    // 连接主流程（worker 线程）
    // ------------------------------------------------------------------

    private fun startConnect() {
        if (running) {
            DiagLog.i(TAG, "已在运行，跳过重复 START")
            return
        }
        running = true
        startupError = null
        runtimeError = null
        serverVersion = null
        val gen = ++generation
        worker = Thread({ runConnect(gen) }, "vnt-network").apply {
            isDaemon = true
            start()
        }
    }

    private fun runConnect(gen: Long) {
        var v: Vnt? = null
        var failed = false
        try {
            val cfg = VntMeshPrefs.load(this)
            val config: Config = VntConfigBuilder.build(this, cfg)
            val mtu = config.mtu ?: VntMeshConfig.DEFAULT_MTU

            val callback = object : CallBack {
                override fun success() {
                    DiagLog.i(TAG, "native 回调 success（注册完成）")
                }

                override fun createTun(info: DeviceInfo?) {
                    // 仅 windows/linux/macos 使用，Android 走 generateTun
                }

                override fun connect(info: ConnectInfo?) {
                    DiagLog.i(TAG, "连接服务端：${info?.address}（第 ${info?.count} 次）")
                }

                override fun handshake(info: HandshakeInfo?): Boolean {
                    serverVersion = info?.version
                    DiagLog.i(TAG, "握手：服务端版本=${info?.version}，指纹=${info?.finger}")
                    return true                 // 确认握手
                }

                override fun register(info: RegisterInfo?): Boolean {
                    val ip = info?.let { IpUtils.intToIpAddress(it.virtualIp) } ?: "?"
                    DiagLog.i(TAG, "注册成功：$ip")
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
                        ?: throw IllegalStateException(
                            "VpnService.Builder.establish() 返回 null（VPN 授权可能已被撤销，" +
                                "或系统已把唯一 VPN 通道给了其它 App）"
                        )
                    synchronized(nativeLock) { pfd = f }

                    VntMeshController.onConnected(ip, prefix, cfg, mtu, serverVersion)
                    promoteForeground("已连接 · $ip", connected = true)
                    DiagLog.i(TAG, "VNT 已连接：$ip/$prefix，服务端版本 ${serverVersion ?: "?"}")
                    return f.fd
                }

                override fun peerClientList(infoArray: Array<PeerClientInfo>?) {
                    // 设备列表统一由 Controller 经 Vnt.list() 前台轮询，这里不重复处理
                }

                override fun error(info: ErrorInfo?) {
                    val e = info ?: return
                    val text = "${e.code}" + (e.msg?.let { "：$it" } ?: "")
                    DiagLog.e(TAG, "native 回调错误：$text")
                    // ★ 构造期间 vnt 尚未赋值，绝不能在这里调 v.stop()
                    //   （官方 MyVpnService 这里会 NPE，属其已知缺陷）。
                    val ready = synchronized(nativeLock) { vnt != null }
                    if (ready) runtimeError = text else startupError = text
                }

                override fun stop() {
                    DiagLog.i(TAG, "native 回调 stop")
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
            // 不能假装连上了，立刻转 ERROR 状态。
            startupError?.let { throw IllegalStateException(it) }

            // 运行期循环：awaitTimeout(2000) 是 1.x 实例保活与接收停止事件的唯一机制，
            // 不发网络包；设备列表刷新由 Controller 在「前台 + 页面可见」时另行调度。
            while (running && gen == generation) {
                runtimeError?.let { throw IllegalStateException(it) }
                val out = nativeVnt.awaitTimeout(2000)
                if (out || gen != generation) break
            }
        } catch (e: Throwable) {
            failed = true
            DiagLog.e(TAG, "组网失败", e)
            VntMeshController.onError("组网失败：${e.message}")
        } finally {
            // 到这里 awaitTimeout 已返回（或构造抛异常），native 线程不再使用该对象，
            // 可以安全释放；shutdown()/onDestroy() 只发 stop 信号，不在这里之外 close。
            synchronized(nativeLock) { vnt = null }
            runCatching { v?.stop() }
            runCatching { v?.close() }
            val f = synchronized(nativeLock) {
                val x = pfd
                pfd = null
                x
            }
            runCatching { f?.close() }

            VntMeshController.clearProvider()
            // v0.2.31 修的坑沿用：失败时**保留 ERROR 状态**，别被 onStopped() 覆盖成「未连接」。
            if (!failed) VntMeshController.onStopped()
            running = false
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 停止
    // ------------------------------------------------------------------

    /**
     * 停止：只发 `stop()` 信号让 awaitTimeout 返回，**不在别处 close native 对象**，
     * 由 worker 的 finally 统一收尾（避免并发 drop 造成 use-after-free）。
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
        VntMeshController.clearProvider()
        worker = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
