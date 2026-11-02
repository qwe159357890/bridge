package my.bridge.com.ssh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.Security
import my.bridge.com.MainActivity
import my.bridge.com.R
import my.bridge.com.core.common.DiagLog
import org.apache.sshd.mina.MinaServiceFactoryFactory
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * SSH 服务器前台服务（v0.2.34）——持有 MINA SSHD [SshServer] 实例。
 *
 * 骨架照抄 [my.bridge.com.vnt.VntVpnService]（崩溃台账 §5 逐条落实）：
 * - onCreate 挂前台；onStartCommand 每次幂等补挂（义务按次计）；
 * - startForeground try/catch + 失败 stopSelf 防崩；
 * - 返回 START_STICKY（与 VNT 的 NOT_STICKY 相反——已拍板「完整自愈链」：
 *   进程被杀系统重建服务时，onStartCommand 收到 null intent，按意愿自动重启 server）。
 *
 * ★ 与 VNT 的结构差异：MINA SSHD 自带 acceptor/IO 线程，`sshd.start()` 返回即绑定完成，
 * **无需 VNT 式 awaitTimeout 长循环**——worker 线程只做装配与启动，停止由 [shutdown]
 * 直接 `sshd.stop(true)` 收口（generation 防「启动中途被停」竞态）。
 *
 * ★ Android 适配（调研报告 §八逐条防）：
 * - 传输层换 [MinaServiceFactoryFactory]（默认 NIO2 的 AsynchronousSocketChannel 是 API 26+）；
 * - 启动前重装 BouncyCastle provider（Android 9+ 系统 BC 弃用）；
 * - OsUtils.setAndroid(true) + home 目录显式指定（Android 无 user.home 概念）；
 * - 全部网络操作在 worker 线程（NetworkOnMainThreadException）。
 */
class SshServerService : Service() {

    companion object {
        const val ACTION_START = "my.bridge.com.ssh.START"
        const val ACTION_STOP = "my.bridge.com.ssh.STOP"

        /** 独立通知 ID：保活 2001、中继 2002、媒体采集 2101、VNT 2102 → SSH 取 2103。 */
        const val NOTIFICATION_ID = 2103
        const val CHANNEL_ID = "sshServerChannel"

        private const val TAG = "SSH"

        @Volatile
        private var instance: SshServerService? = null

        /** 服务进程内是否在干活（含 STARTING）——供 Controller.stop 收口判定。 */
        fun isActive(): Boolean = instance?.let { it.serverActive || it.starting } == true

        /** 本机 IPv4 地址列表（WiFi wlan0 为主），供状态页与通知展示。 */
        fun localIpAddresses(): List<String> {
            val out = mutableListOf<String>()
            try {
                val nis = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                while (nis.hasMoreElements()) {
                    val ni = nis.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name ?: continue
                    if (name != "wlan0" && !name.startsWith("swlan") && !name.startsWith("ap")) {
                        // 只取无线接口（wlan0 / 热点 swlan0·ap0）；VNT 虚拟网 IP 由组网状态单独展示
                        continue
                    }
                    val addrs = ni.inetAddresses ?: continue
                    while (addrs.hasMoreElements()) {
                        val addr: InetAddress = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            out.add(addr.hostAddress ?: continue)
                        }
                    }
                }
            } catch (e: Exception) {
                DiagLog.e(TAG, "枚举本机 IP 失败", e)
            }
            return out.distinct()
        }
    }

    private val lock = Any()

    /** MINA SSHD 实例；启动成功后由 [lock] 保护赋值，停止时取走置 null。 */
    private var server: SshServer? = null

    @Volatile
    private var serverActive = false

    /** STARTING 中间态：runServer 尚未完成装配/绑定。 */
    @Volatile
    private var starting = false

    /** 代次：停止时自增，让「正在装配中的 runServer」发现被停后自行回滚。 */
    @Volatile
    private var generation = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // 崩溃台账 §5：onCreate 立刻挂前台（try/catch + 失败 stopSelf 在 promoteForeground 内）
        promoteForeground("正在启动 SSH 服务…")
    }

    /**
     * 每次拉起都履行一次前台化义务（义务按次计，服务运行中重复拉起不走 onCreate）。
     * 返回 START_STICKY：进程被杀后系统重建服务、以 null intent 重进本方法，
     * 按「用户意愿」（SshPrefs.enabled）自动重启——完整自愈链的最后一环。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground("正在启动 SSH 服务…")
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> startServer()
            else -> {
                // START_STICKY 重建（intent == null）或无 action 拉起
                if (SshPrefs.enabled(this)) {
                    DiagLog.i(TAG, "系统重建服务且意愿为开，自动重启 SSH 服务")
                    startServer()
                } else {
                    DiagLog.i(TAG, "无 action 且意愿为关，直接收尾")
                    shutdown()
                }
            }
        }
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 前台化
    // ------------------------------------------------------------------

    private fun promoteForeground(text: String) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            DiagLog.e(TAG, "startForeground(specialUse) 失败，stopSelf 防崩", e)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pendingStop = PendingIntent.getService(
            this, 1,
            Intent(this, SshServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SSH 服务运行中")
            .setContentText(text)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun refreshNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SSH 服务", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持 SSH 服务器（SFTP / 终端）在后台运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------------
    // 启动（worker 线程装配 MINA SSHD）
    // ------------------------------------------------------------------

    private fun startServer() {
        if (serverActive || starting) {
            DiagLog.i(TAG, "已在运行/启动中，跳过重复 START")
            return
        }
        starting = true
        val gen = ++generation
        Thread({ runServer(gen) }, "ssh-server").apply { isDaemon = false; start() }
    }

    private fun runServer(gen: Long) {
        try {
            reinstallBouncyCastle()

            val port = SshPrefs.port(this)
            SshServerController.onStarting(port)

            val sshd = buildServer(port)
            // 绑定动作必须在后台线程（NetworkOnMainThreadException）
            sshd.start()

            // 装配/绑定期间收到停止指令 → 立即回滚，不对外假装已启动
            synchronized(lock) {
                if (gen != generation) {
                    runCatching { sshd.stop(true) }
                    return
                }
                server = sshd
            }
            serverActive = true
            starting = false

            acquireWakeLock()
            val ip = localIpAddresses().firstOrNull()
            SshServerController.onStarted(port)
            refreshNotification("端口 $port · ${ip ?: "IP 未知"} · 用户名 ${SshPrefs.USERNAME}")
            DiagLog.i(TAG, "SSH 服务已启动：0.0.0.0:$port，home=${SshPrefs.homeDir(this)}")
        } catch (e: Throwable) {
            starting = false
            DiagLog.e(TAG, "SSH 服务启动失败", e)
            SshServerController.onError("SSH 启动失败：${e.message}")
            releaseWakeLock()
            stopSelf()
        }
    }

    /**
     * 装配 MINA SSHD。注意：setUpDefaultServer 不含 SFTP 子系统与命令工厂，需显式挂载。
     */
    private fun buildServer(port: Int): SshServer {
        val home = SshPrefs.homeDir(this)

        // Android 无 user.home/user.dir 概念（调研报告 §3.5 官方 hooks）。
        // ★ v0.2.35 双修复（首次真机实锤，见 ssh-integration-change-ledger §四）：
        // ① 2.20 签名是 setAndroid(java.lang.Boolean)（包装类型），之前反射用
        //    javaPrimitiveType（原始 boolean）→ NoSuchMethodException → Android 模式没设上。
        //    主开关改用官方系统属性 org.apache.sshd.androidMode=true（javap 实证
        //    ANDROID_MODE_OVERRIDE_PROP），反射退为兜底。
        // ② 仅 setAndroid=true 不够：PathUtils$LazyDefaultUserHomeFolderHolder.<clinit>
        //    仍会读 System.getProperty("user.home")，Android 上为 null →
        //    ValidateUtils.checkNotNullAndNotEmpty 抛异常 → ExceptionInInitializerError，
        //    之后该类永久失败 → 连锁 NoClassDefFoundError: ServerBuilder（真机日志实证）。
        //    官方设计是 Android 上必须 setUserHomeFolderResolver 主动提供目录。
        //    用应用私有 filesDir（sshd 内部解析用，不对外暴露；对外 home 由
        //    NativeFileSystemFactory.usersHomeDir 单独控制）。
        System.setProperty("org.apache.sshd.androidMode", "true")
        java.nio.file.Paths.get(filesDir.absolutePath) // 预热：确认 NIO 可用，失败直接进 catch 汇报
        try {
            org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver {
                java.nio.file.Paths.get(filesDir.absolutePath)
            }
            DiagLog.i(TAG, "PathUtils.setUserHomeFolderResolver 已设置 → ${filesDir.absolutePath}")
        } catch (e: Throwable) {
            DiagLog.w(TAG, "setUserHomeFolderResolver 失败（版本差异）：${e.message}")
        }
        try {
            val osUtils = Class.forName("org.apache.sshd.common.util.OsUtils")
            val boxed = try {
                osUtils.getMethod("setAndroid", java.lang.Boolean::class.java)
            } catch (e: NoSuchMethodException) {
                osUtils.getMethod("setAndroid", java.lang.Boolean.TYPE)
            }
            boxed.invoke(null, java.lang.Boolean.TRUE)
            DiagLog.i(TAG, "OsUtils.setAndroid(true) 成功（Android 模式已开启）")
        } catch (e: Throwable) {
            DiagLog.w(TAG, "OsUtils.setAndroid 不可用（版本差异），继续：${e.message}")
        }

        val sshd = SshServer.setUpDefaultServer()
        // ★ minSdk 24：默认 NIO2 传输（AsynchronousSocketChannel）是 API 26+，换 MINA I/O 层
        sshd.ioServiceFactoryFactory = MinaServiceFactoryFactory()
        sshd.port = port
        // 不设 host = 绑 0.0.0.0：同时服务 WiFi 局域网与 VNT 虚拟网（调研报告 §6.4）

        // 主机密钥：首次生成 EC P-256 并持久化
        sshd.keyPairProvider = SshHostKeyStore.provider(this)

        // 认证：密码（用户名 bridge）；公钥认证 P1 再开，显式拒绝避免默认放行
        sshd.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            val ok = username == SshPrefs.USERNAME && password == SshPrefs.password(this)
            DiagLog.i(TAG, "密码认证：$username ${if (ok) "通过" else "拒绝"}")
            ok
        }
        sshd.publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> false }

        // 文件系统：home = 全盘 /sdcard（已授权）或应用外部目录（基础档）
        // 2.20 实证：setter 为 setUsersHomeDir / setCreateHome（javap 核对）
        sshd.fileSystemFactory = object : NativeFileSystemFactory() {
            init {
                usersHomeDir = home
                isCreateHome = false
            }
        }

        // SFTP 子系统（setUpDefaultServer 不含，必须显式挂载）
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())

        // 远程终端：交互式 /system/bin/sh（完整 sh，用户拍板）。
        // ★ v0.2.36 修复（javap + 桌面/真机双复现实证）：2.20 的 ProcessShellFactory
        //   在 resolveEffectiveCommand 里【无视调用者传入的 elements】，非 Windows 一律
        //   硬编码执行 ["/bin/sh", "-c", 构造器第一个参数]——Android 端 exec 通道实际跑
        //   `/bin/sh -c "/system/bin/sh"`（起一个无人喂数据的交互 sh）→ MobaXterm 执行
        //   命令卡死、SCP 同源受影响；shell 通道只是碰巧可用。
        //   修复 = 绕开 OS 推断，用 ProcessShell 直接构造器（精确命令列表）+ InvertedShellWrapper。
        sshd.shellFactory = ShellFactory { _ ->
            InvertedShellWrapper(ProcessShell("/system/bin/sh", "-i"))
        }

        // 命令通道：scp 命令走 SCP，其余 exec 命令经 /system/bin/sh -c（完整 sh 语义）
        // 2.20 实证：委托入口是 setDelegateCommandFactory（javap 核对，非 setDelegate）
        val scpFactory = ScpCommandFactory()
        scpFactory.setDelegateCommandFactory(CommandFactory { _, command ->
            InvertedShellWrapper(ProcessShell("/system/bin/sh", "-c", command))
        })
        sshd.commandFactory = scpFactory

        // 会话计数（状态页「活动会话 N」）
        sshd.addSessionListener(object : SessionListener {
            override fun sessionCreated(session: Session) {
                DiagLog.i(TAG, "会话接入：${session.remoteAddress}")
                SshServerController.onSessionOpened()
            }

            override fun sessionClosed(session: Session) {
                SshServerController.onSessionClosed()
            }
        })

        return sshd
    }

    /** Android 9+ 系统 BC provider 被弃用（调研报告 §3.4）：重装为我们自带的 BouncyCastle。 */
    private fun reinstallBouncyCastle() {
        try {
            val existing = Security.getProvider("BC")
            if (existing != null && existing.javaClass.name.startsWith("com.android.")) {
                Security.removeProvider("BC")
            }
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
                DiagLog.i(TAG, "已重装 BouncyCastle provider")
            }
        } catch (e: Exception) {
            DiagLog.e(TAG, "BouncyCastle provider 处理失败（KEX 可能受限）", e)
        }
    }

    // ------------------------------------------------------------------
    // 停止与释放
    // ------------------------------------------------------------------

    private fun shutdown() {
        DiagLog.i(TAG, "收到停止指令")
        ++generation
        starting = false
        val s = synchronized(lock) {
            val x = server
            server = null
            x
        }
        val had = serverActive || s != null
        serverActive = false
        SshServerController.onStopping()
        // MINA SSHD stop(immediately=true)：关闭 acceptor 与全部活动会话（断开会话）
        runCatching { s?.stop(true) }
        releaseWakeLock()
        // 无论 had 与否都收尾：通知撤除 + 状态归位 + stopSelf，绝不留僵尸前台服务
        SshServerController.onStopped()
        stopSelf()
    }

    // ------------------------------------------------------------------
    // WakeLock（拍板：运行期默认持 partial 锁，服务停止即释放；Prefs 可关）
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (!SshPrefs.wakeLockEnabled(this)) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:sshd").apply {
                setReferenceCounted(false)
                acquire()
            }
            DiagLog.i(TAG, "已持有 partial WakeLock（灭屏保连接）")
        } catch (e: Exception) {
            DiagLog.e(TAG, "WakeLock 获取失败（继续运行，不做硬依赖）", e)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        val s = synchronized(lock) {
            val x = server
            server = null
            x
        }
        runCatching { s?.stop(true) }
        serverActive = false
        if (instance === this) instance = null
        super.onDestroy()
    }
}
