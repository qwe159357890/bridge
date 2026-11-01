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

/**
 * VNT 组网前台服务（v0.2.30）——持有 [VpnService] 通道与 [VntNetwork] 实例。
 *
 * 对照官方 `AndroidVpnExample.java`（267 行骨架）与 `VntVpnService.java`（665 行生产实现）：
 * `VntManager.init()` → `createNetwork(config)` → `getNetwork()` → 建立 VPN 接口拿 fd →
 * `startTun(fd)` → `getApi()` → `nextEvent()/applyRuntimeChange` 变更循环 → `stop()`。
 *
 * ★ 崩溃台账 §5 检查清单（逐条落实）：
 * - manifest 已声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限；
 * - 所有 `startForegroundService` 调用点在 [VntMeshController.start] 里 try/catch；
 * - **onCreate 挂前台**，且 `startForeground` 有 try/catch + 失败 `stopSelf()`；
 * - **onStartCommand 对每次拉起都幂等补挂前台**（不依赖 onCreate 一定执行）；
 * - 早退分支（STOP / intent==null）返回前义务已履行；
 * - 返回 `START_NOT_STICKY`：进程被杀后系统**不自动复活**服务，满足
 *   「退出应用 / 切后台即结束」最高优先级（[my.bridge.com.AppExit.kill] 仍显式 stopService
 *   以释放 VPN 通道与 native 资源）；
 * - 调用点已登记到 `docs/dev/crash-ledger.md` §3.1。
 *
 * ★ 路由红线：**绝不加 `0.0.0.0/0`**（会劫持整机流量，普通上网也进 tun）。
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

        @Volatile
        private var instance: VntVpnService? = null

        fun isRunning(): Boolean = instance?.running == true

        /** 由本机虚拟 IPv4 + 前缀长度计算网段网络地址：10.26.0.5/24 → 10.26.0.0。 */
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

    /** 组网 worker 线程持有实例；外部只经 [VntMeshController] 读查询接口。 */
    private var network: VntNetwork? = null
    private var networkThread: Thread? = null

    @Volatile
    private var running = false

    /** 代次：停止时自增，让阻塞在 nextEvent 的旧循环退出，避免干扰新连接。 */
    @Volatile
    private var generation = 0L

    /** 当前 VPN 接口使用的 CIDR；快照未携带固定 IP 时沿用。 */
    private var establishedCidr: String? = null
    private var currentMtu: Int = DEFAULT_MTU

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
            // FGS 类型/权限校验或后台免疫窗口竞态都可能抛；接住后 stopSelf 是唯一防崩出口
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
            // 已运行：只补挂前台（幂等），不重复建网
            DiagLog.i(TAG, "已在运行，跳过重复 START")
            return
        }
        running = true
        val gen = ++generation
        networkThread = Thread({ runConnect(gen) }, "vnt-network").apply {
            isDaemon = true
            start()
        }
    }

    private fun runConnect(gen: Long) {
        var net: VntNetwork? = null
        var failed = false
        try {
            if (!VntManager.init()) throw VntException("Rust 核心初始化失败")

            val cfg = VntMeshPrefs.load(this)
            val config = VntConfigBuilder.build(this, cfg)

            // 1) 创建网络实例（后台连服务器并注册）
            net = VntManager.createNetwork(config)
                ?: throw VntException("createNetwork 返回 null（配置或网络不可达）")
            network = net

            // 2) 等待注册完成，取分配的网段信息
            val registered: NetworkResult = net.getNetwork()
            val cidr = registered.toCidr()
            establishedCidr = cidr
            currentMtu = cfg.mtu.coerceIn(MIN_MTU, MAX_MTU)

            // 3) 建立 VPN 接口并把 detached fd 交给 VNT
            val tunFd = establishVpn(cidr, currentMtu, registered.getRoutes())
            net.startTun(tunFd)

            // 4) 对外发布查询接口 + 状态
            val api: VntApi = net.getApi()
            VntMeshController.onConnected(api, registered, cfg, currentMtu)
            promoteForeground("已连接 · ${registered.getIp()}", connected = true)
            DiagLog.i(TAG, "VNT 已连接：$cidr")

            // 5) 变更循环（阻塞 nextEvent，直到实例停止或异常）
            runChangeLoop(net, gen)
        } catch (e: Throwable) {
            failed = true
            DiagLog.e(TAG, "组网失败", e)
            VntMeshController.onError("组网失败：${e.message}")
        } finally {
            runCatching { net?.stop() }
            network = null
            // v0.2.31：失败时**保留 ERROR 状态**，别被 onStopped() 覆盖成「未连接」——
            // 否则用户只看到按钮变回「开始组网」，永远看不到失败原因（v0.2.30 的坑）。
            // 停止 / 重连 / 再次点开始会分别经 onStopping / onStopped / start 正常清理。
            if (!failed) VntMeshController.onStopped()
            running = false
            stopSelf()
        }
    }

    /**
     * 按参数建立 VPN 接口并返回 detached fd。
     *
     * - `addAddress` 不会自动生成路由，必须显式把虚拟网段指向 tun；
     * - **不加 0.0.0.0/0**；
     * - 虚拟 IPv6 与固定路由按官方骨架补齐；
     * - `subnetRoutes` 是子网转发预留位（本期恒为空，见 [VntConfigBuilder] TODO）。
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
            ?: throw VntException(
                "VpnService.Builder.establish() 返回 null（VPN 授权可能已被撤销，或系统已把通道给了其它 App）"
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

    // ------------------------------------------------------------------
    // 停止
    // ------------------------------------------------------------------

    private fun shutdown() {
        DiagLog.i(TAG, "收到停止指令")
        ++generation
        running = false
        VntMeshController.onStopping()
        val net = network
        network = null
        // nativeStop 会让阻塞在 nextEvent 的守护线程在下一次事件到达时退出
        runCatching { net?.stop() }
        VntMeshController.onStopped()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        ++generation
        runCatching { network?.stop() }
        network = null
        networkThread = null
        VntMeshController.onStopped()
        runCatching { VntManager.destroy() }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
