package my.bridge.com.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import my.bridge.com.core.common.DiagLog
import my.bridge.com.keepalive.SilentPlayer

/**
 * 音频中继接收服务（手机放音：只保留中转链路，砍掉局域网直连/电脑放音/TLS）。
 *
 * 完整链路：中转服务器（039039.xyz:15151，后端零改动）→ WebSocket 出站 → 收到
 * AURL 字节流 → 解析 8 字节包头判定编码 → Opus 解码（或裸 PCM）→ AudioTrack 播放。
 *
 * 作为 mediaPlayback 类型前台服务常驻，通知栏显示「正在接收电脑声音」。
 * 播放期间让位 Bridge 的 SilentPlayer 无声锚点（避免底噪混入）。
 *
 * 自研流协议（AURL，与 Aurelay 完全一致）：
 *   包头（8 字节）：[0..3]="AURL" [4]=版本1 [5]=编码(0=PCM,1=Opus)
 *                    [6]=声道(1/2) [7]=采样率代码(0=44100,1=48000)
 *   包头之后：每帧 = 4 字节大端长度 + 帧数据
 */
class AudioRelayService : Service() {

    private val relayClient = RelayClient()
    private var relayThread: Thread? = null

    // 网络状态监听：切换网络（WiFi↔蜂窝、断网）时主动触发重连。
    // 这是「用户不点停止就永远连接」的关键一环——不等 onFailure/onClosed（半开连接下
    // 它们可能永远不触发），网络一丢失就立即 forceReconnect。
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var isServerRunning = false

    private var audioTrack: AudioTrack? = null

    // 当前 AudioTrack 采用的采样率与声道数（流协商结果，用于判断是否需要重建）
    private var currentSampleRate: Int = 0
    private var currentOutChannels: Int = 0
    private var currentBufferBytes: Int = 0

    // 最近一次设置的音量：新建 AudioTrack 时恢复，避免重建后音量被重置为 1.0
    @Volatile
    private var currentVolume: Float = 1.0f

    // 音频会话代次：每开始一路新的流处理 +1（旧会话 finally 不得暂停新会话音轨）
    @Volatile
    private var sessionGeneration: Long = 0

    // 中转链路「扫描魔数失败」的连续次数与最近一次触发重连的时间戳
    @Volatile
    private var relayHeaderMissCount: Int = 0
    @Volatile
    private var relayLastForceReconnectAt: Long = 0L
    @Volatile
    private var relayHeaderEof: Boolean = false

    // readHeader 因「包头没读够就超时」而放弃时置位
    @Volatile
    private var headerIncomplete: Boolean = false

    // 波形与状态监听器（供 UI 订阅）。
    // 注意：这里用实例字段 + companion 静态兜底双写。UI 的 listener 必须在服务
    // stopSelf() 销毁、重新 onCreate 后依然存活，否则「第二次开始接收」时新实例
    // 拿不到 UI 的 listener，导致状态/波形不刷新（只能退出页面重进才恢复）。
    @Volatile
    private var stateListener: RelayListener? = null
    @Volatile
    private var levelsListener: AudioLevelsListener? = null

    /** 中继状态监听器 */
    interface RelayListener {
        fun onState(state: RelayClient.State, detail: String)
    }

    /** 24 柱波形监听器 */
    interface AudioLevelsListener {
        fun onLevels(levels: FloatArray)
    }

    companion object {
        const val ACTION_START = "my.bridge.com.relay.START"
        const val ACTION_STOP = "my.bridge.com.relay.STOP"
        const val ACTION_SET_VOLUME = "my.bridge.com.relay.SET_VOLUME"
        const val EXTRA_VOLUME = "volume"

        // 独立通知 ID：保活服务占用 2001，中继必须用不同 ID，
        // 否则 stopForeground/cancel 会误删保活服务的常驻通知（「Bridge 后台服务」）。
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = "audioRelayChannel"

        // 音频接收端口（上报约定用，中转链路下仅作日志占位）
        const val AUDIO_PORT = 5000

        // 自研流协议常量（与 Aurelay 一致）
        const val STREAM_MAGIC = "AURL"
        const val STREAM_VERSION = 1
        const val CODEC_PCM = 0
        const val CODEC_OPUS = 1
        const val RATE_44100 = 0
        const val RATE_48000 = 1
        const val HEADER_SIZE = 8

        private const val IDLE_TIMEOUT_MS = 10_000L

        // 单例访问（UI 通过它订阅状态/波形、控制启停）
        @Volatile
        private var instance: AudioRelayService? = null

        fun instance(): AudioRelayService? = instance

        /** 是否正在运行（供 UI 判断按钮状态）。 */
        fun isRunning(): Boolean = instance?.isServerRunning == true

        /** 当前中继状态（供 UI 初值）。 */
        fun currentState(): RelayClient.State = instance?.relayState ?: RelayClient.State.IDLE

        /** 启动中继（startForegroundService）。 */
        fun start(context: Context) {
            val intent = Intent(context, AudioRelayService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 停止中继。 */
        fun stop(context: Context) {
            // 服务未运行时直接忽略：绝不能用 startService 重新拉起已销毁的服务，
            // 否则 onCreate 会被触发、进而可能重新挂通知覆盖保活通知。
            val inst = instance
            if (inst == null || !inst.isServerRunning) return
            val intent = Intent(context, AudioRelayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** 设置中继播放音量（0~1）。 */
        fun setVolume(context: Context, volume: Float) {
            // 服务未运行时只持久化音量、不拉起服务（避免重新挂通知）。
            RelayPrefs.setFloat(context, RelayPrefs.KEY_VOLUME, volume)
            val inst = instance
            if (inst == null || !inst.isServerRunning) return
            val intent = Intent(context, AudioRelayService::class.java).apply {
                action = ACTION_SET_VOLUME
                putExtra(EXTRA_VOLUME, volume)
            }
            context.startService(intent)
        }

        /** 订阅中继状态。listener 存于静态字段（跨服务实例存活），订阅后立即回放当前状态。 */
        fun setStateListener(listener: RelayListener?) {
            sStateListener = listener
            // 同步到当前实例（若存在），保证实例内的回调能取到
            instance?.stateListener = listener
            // 立即回放一次当前状态，避免「服务已就绪但 UI 刚订阅」导致的初值空缺
            if (listener != null) {
                val st = instance?.relayState ?: RelayClient.State.IDLE
                listener.onState(st, stateDetailFor(st))
            }
        }

        /** 订阅 24 柱波形。listener 存于静态字段（跨服务实例存活）。 */
        fun setLevelsListener(listener: AudioLevelsListener?) {
            sLevelsListener = listener
            instance?.levelsListener = listener
        }

        /** 当前状态对应的说明文案（供订阅回放）。 */
        private fun stateDetailFor(state: RelayClient.State): String = when (state) {
            RelayClient.State.IDLE -> "已停止"
            RelayClient.State.CONNECTING -> "正在连接中转服务器"
            RelayClient.State.READY -> "已接入中转服务器"
            RelayClient.State.RECEIVING -> "正在接收电脑声音"
            RelayClient.State.ERROR -> "连接异常"
        }

        // 静态监听器（跨服务实例存活；服务 stopSelf 后重建时，UI 的订阅依然有效）
        @Volatile
        private var sStateListener: RelayListener? = null
        @Volatile
        private var sLevelsListener: AudioLevelsListener? = null
    }

    // 记录上一次中继状态（供 UI 订阅初值）
    @Volatile
    private var relayState: RelayClient.State = RelayClient.State.IDLE

    // 单次接收会话的诊断统计
    private class DiagSession(val branch: String) {
        var frames = 0L
        var bytes = 0L
        var packets = 0L
        var maxChunk = 0
        var peak = 0
        var startedAt = System.currentTimeMillis()
        var firstChunkLogged = false
        var firstPacketLogged = false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 从静态字段恢复 UI 订阅（服务 stopSelf 后重建时，UI 的 listener 依然有效，
        // 否则「第二次开始接收」后新实例拿不到 UI 回调，状态/波形不刷新）。
        sStateListener?.let { stateListener = it }
        sLevelsListener?.let { levelsListener = it }
        createNotificationChannel()
        // 注意：这里**不**立即挂前台通知。通知只在真正「开始接收」（ACTION_START → startRelay）
        // 时挂起。否则 STOP / SET_VOLUME 用 startService 重新拉起已销毁的服务时，会误挂
        // 一个「声音中继已开启」的通知覆盖保活通知。
        DiagLog.i("中继", "音频中继接收服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRelay()
                return START_NOT_STICKY
            }
            ACTION_SET_VOLUME -> {
                // 仅在服务确实在运行时才处理音量；否则直接忽略并确保服务结束，
                // 避免「SET_VOLUME 重新拉起已停止的服务」导致通知被误挂。
                if (isServerRunning) {
                    val volume = intent.getFloatExtra(EXTRA_VOLUME, 0.8f)
                    setVolumeInternal(volume)
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                startRelay()
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopRelay()
        releaseAudioTrack()
        instance = null
        DiagLog.i("中继", "音频中继接收服务已销毁")
    }

    // ------------------------------------------------------------------
    // 链路控制
    // ------------------------------------------------------------------

    private fun startRelay() {
        if (isServerRunning) return
        // 兜底：若上次停止遗留的消费线程还在，先终结，避免新旧线程并发消费同一管道
        val oldThread = relayThread
        if (oldThread != null && oldThread.isAlive) {
            DiagLog.w("中继", "发现上一次遗留的消费线程，先终结")
            oldThread.interrupt()
            relayThread = null
        }
        isServerRunning = true
        // 真正开始接收：此时才挂前台通知（唯一挂通知入口），并切到「连接中」态。
        // 关键：必须调用 startForeground（而非 notify），否则 startForegroundService 启动后
        // 未在时限内前台化，系统会抛 ForegroundServiceDidNotStartInTimeException 直接崩溃。
        relayState = RelayClient.State.CONNECTING
        stateListener?.onState(RelayClient.State.CONNECTING, "正在连接中转服务器")
        startForegroundCompat(buildNotification(notificationTextFor(RelayClient.State.CONNECTING)))

        // 从 RelayPrefs 读音量与自动重连开关
        currentVolume = RelayPrefs.volume(this)

        relayClient.listener = object : RelayClient.Listener {
            override fun onStateChanged(state: RelayClient.State, detail: String) {
                // 防状态降级：若已进入「正在接收」态（真实音频在播），
                // 收到迟到的 ready 不应把状态拉回 READY（音频持续，UI 会闪回「已就绪」）。
                if (relayState == RelayClient.State.RECEIVING && state == RelayClient.State.READY) {
                    return
                }
                relayState = state
                stateListener?.onState(state, detail)
                // 状态变化同步刷新前台通知文案（连接中/就绪/错误各显示对应文案）。
                // 注意：READY 也必须刷新通知（显示「已就绪，等待电脑声音」），否则 UI/通知
                // 会一直停留在「连接中」。真正「正在接收」的文案由 handleStream 收到音频后
                // 切换到 RECEIVING 状态再刷新。
                refreshNotification(notificationTextFor(state))
            }

            override fun onAudioData(size: Int) {
                // 波形由解码后的 PCM 计算，这里不处理
            }
        }

        // 注入「重连前重新上报」钩子：每次重连动作前把设备在线状态重新报到中转服务器，
        // 让 PC 端重新撮合，避免「旧会话残留/配对丢失」导致重连后收不到音频。
        relayClient.onBeforeReconnect = {
            if (isServerRunning) {
                RelayReporter.reportNow(this@AudioRelayService)
            }
        }

        // 注册网络监听：网络丢失/切换时立即强制重连（「永远连接」的关键一环）。
        registerNetworkCallback()

        relayThread = Thread({
            try {
                relayClient.start(this@AudioRelayService, RelayPrefs.autoReconnect(this@AudioRelayService))
                // 立即上报 device_id 到中心服务器：PC 端要靠它查到本机才能配对（缺了会一直「连接中」）
                RelayReporter.reportNow(this@AudioRelayService)
                while (relayClient.isRunning() && isServerRunning) {
                    val input = relayClient.audioInput()
                    if (input == null) {
                        try { Thread.sleep(100) } catch (e: InterruptedException) { break }
                        continue
                    }
                    handleStream(input, "中转服务器")
                }
            } catch (e: Exception) {
                DiagLog.e("中继", "中转接收异常", e)
            }
        }, "BridgeRelayStream").also { it.start() }
        DiagLog.i("中继", "已选择服务器中转：主动出站连接中转服务器")
    }

    private fun stopRelay() {
        // 幂等守卫：停止流程只执行一次，避免 onDestroy 里二次调用重复通知/重复释放。
        if (!isServerRunning && relayThread == null && relayState == RelayClient.State.IDLE) {
            // 已经停止过了（例如 onDestroy 里第二次调用），只保证通知已移除即可
            return
        }
        // 先置停止标记，让所有读流线程（readHeader/readFully/scanRelayMagic）立即退出
        isServerRunning = false
        // 取消网络监听（停止后不再需要感知网络变化）
        unregisterNetworkCallback()
        // 解绑重连前上报钩子
        relayClient.onBeforeReconnect = null
        // 先解绑 listener：停止阶段不再让 RelayClient 的状态回调刷新通知/UI，
        // 避免 stop() 内部 notifyState(IDLE) 把中继通知重新弹出来（最终仍会被移除）。
        relayClient.listener = null
        try {
            relayClient.stop()
        } catch (e: Exception) {
            DiagLog.w("中继", "停止中转客户端失败：${e.message}")
        }

        // 关键：消费线程阻塞在 PipedInputStream.read()（该 read 不响应 Thread.interrupt），
        // 但 relayClient.stop() 已 closePipe()，会令其 read() 抛 IOException 而退出循环。
        // 这里**必须**先等消费线程真正退出，再释放 AudioTrack —— 否则消费线程还在
        // writeToTrack() 里对 AudioTrack.write()，主线程却 releaseAudioTrack()，
        // 对已释放的 AudioTrack 调用 write 会触发 native 崩溃（多次「接收→停止」后必现）。
        val t = relayThread
        relayThread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.interrupt()
                t.join(1500)
            } catch (e: InterruptedException) {
                DiagLog.w("中继", "等待消费线程退出被打断")
            }
            if (t.isAlive) {
                DiagLog.w("中继", "消费线程 1.5s 内未退出，强制中断（音轨将在写入被保护后释放）")
            }
        }

        // 让位结束：恢复无声保活锚点（保活模块不受中继启停影响）
        SilentPlayer.setScenarioAllowed(this, true)
        // 彻底释放音轨：此时消费线程已退出，不会再对已释放音轨 write()
        releaseAudioTrack()
        relayState = RelayClient.State.IDLE
        stateListener?.onState(RelayClient.State.IDLE, "已停止")
        // 移除中继前台通知并停止服务
        stopForegroundCompat()
        stopSelf()
    }

    /** 移除前台通知（停止接收后通知栏不再显示任何中继相关条目）。 */
    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            DiagLog.w("中继", "退出前台服务失败：${e.message}")
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (e: Exception) { }
    }

    /**
     * 注册网络状态监听：默认网络丢失（onLost）时立即强制重连。
     *
     * 为什么需要它：切换 WiFi↔蜂窝、断网、信号丢失时，底层 TCP 会变成「半开连接」，
     * OkHttp 的 onFailure/onClosed 可能迟迟不触发（OS 不发 FIN/RST），心跳超时兜底要等
     * 60s。网络监听能在「网络接口消失」的第一时间（秒级）就触发重连，是「永远连接」的快路径。
     */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            connectivityManager = cm
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    // 网络丢失：主动强制重连，让 RelayClient 走退避重连重新建连。
                    // 仅在服务运行中处理（停止后不重连，符合「用户点停止即彻底停止」）。
                    if (!isServerRunning) return
                    DiagLog.w("中继", "检测到网络丢失，主动触发中继重连")
                    try {
                        relayClient.forceReconnect()
                    } catch (e: Exception) {
                        DiagLog.e("中继", "网络丢失触发重连失败：${e.message}", e)
                    }
                }

                override fun onAvailable(network: Network) {
                    // 网络恢复：立即触发重连 + 重新上报设备在线状态，让中转服务器与 PC 端
                    // 重新撮合。这是「切断网络→恢复后自动恢复播放」的关键快路径：
                    // 若只靠 onLost 那次重连，恢复瞬间可能正卡在 connect 超时里，导致
                    // 手机迟迟不重连（服务器侧表现为「接收方连接结束后再无接入」）。
                    if (!isServerRunning) return
                    DiagLog.i("中继", "检测到网络恢复，立即触发中继重连并重新上报")
                    try {
                        RelayReporter.reportNow(this@AudioRelayService)
                        relayClient.forceReconnect()
                    } catch (e: Exception) {
                        DiagLog.e("中继", "网络恢复触发重连失败：${e.message}", e)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: android.net.NetworkCapabilities,
                ) {
                    // 网络能力变化（如 WiFi↔蜂窝切换时连接能力重建）：同样视为「需要重连」的
                    // 信号，避免切换网络后旧连接悬空、新网络已可用却无人重连的窗口。
                    if (!isServerRunning) return
                    val hasInternet = networkCapabilities.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    if (hasInternet) {
                        DiagLog.i("中继", "网络能力变化（已具备上网能力），触发中继重连")
                        try {
                            relayClient.forceReconnect()
                        } catch (e: Exception) {
                            DiagLog.e("中继", "网络能力变化触发重连失败：${e.message}", e)
                        }
                    }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
            DiagLog.i("中继", "已注册网络监听（网络丢失时自动重连）")
        } catch (e: Exception) {
            DiagLog.w("中继", "注册网络监听失败：${e.message}")
        }
    }

    /** 取消网络监听（停止接收时调用）。 */
    private fun unregisterNetworkCallback() {
        try {
            val cm = connectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            DiagLog.w("中继", "取消网络监听失败：${e.message}")
        }
        connectivityManager = null
        networkCallback = null
    }

    // ------------------------------------------------------------------
    // 流处理（AURL 解析 + Opus 解码 + AudioTrack 播放）
    // ------------------------------------------------------------------

    private fun handleStream(rawInput: InputStream, peerLabel: String) {
        var decoder: OpusDecoder? = null
        var session: DiagSession? = null
        val myGeneration = ++sessionGeneration
        DiagLog.i("中继", "开始处理来自 $peerLabel 的音频流（会话代次 #$myGeneration）")

        // 真实音频开始播放：切换到「正在接收」状态，通知与 UI 一并更新，
        // 同时无声播放锚点让位（避免底噪混入输出）。
        enterReceivingState()

        try {
            val input = PushbackInputStream(rawInput, HEADER_SIZE)
            headerIncomplete = false
            relayHeaderEof = false

            val isRelayPeer = peerLabel == "中转服务器"
            val header = readHeader(input, isRelayPeer)
            if (header == null && headerIncomplete) {
                DiagLog.w("中继", "流包头未收全，放弃本次会话（不按裸 PCM 处理）")
                if (isRelayPeer) onRelayHeaderMissed()
                return
            }

            var framed = false
            var codec = CODEC_PCM
            var streamChannels = 2
            var streamSampleRate = 44100

            if (header != null) {
                if (isRelayPeer) relayHeaderMissCount = 0
                framed = true
                codec = header[5].toInt() and 0xFF
                streamChannels = if ((header[6].toInt() and 0xFF) == 1) 1 else 2
                streamSampleRate = if ((header[7].toInt() and 0xFF) == RATE_48000) 48000 else 44100
                DiagLog.i("中继", "收到自研 AURL 包头：" +
                    "编码=${if (codec == CODEC_OPUS) "Opus" else "裸PCM"} " +
                    "声道=$streamChannels 采样率=$streamSampleRate")
            } else {
                DiagLog.i("中继", "未检测到 AURL 包头，按官方裸 PCM 流处理（44100Hz / 立体声）")
            }

            val isOpus = framed && codec == CODEC_OPUS
            val playbackRate = if (isOpus) 48000 else streamSampleRate

            val outChannels = if (supportsStereo(playbackRate)) 2 else 1
            val track = ensureAudioTrack(playbackRate, outChannels)
            if (track == null) {
                DiagLog.e("音轨", "AudioTrack 创建失败，本次连接无法出声音，主动断开")
                return
            }

            if (isOpus) {
                decoder = OpusDecoder(streamChannels, playbackRate)
                if (!decoder.start()) {
                    DiagLog.e("解码", "Opus 解码器初始化失败，断开本次连接")
                    return
                }
            }

            val currentSession = DiagSession(branchName(framed, isOpus))
            session = currentSession
            DiagLog.i("中继", "本次接收分支：「${currentSession.branch}」，输出采样率=$playbackRate 输出声道=$outChannels")

            if (framed) {
                if (isOpus) {
                    streamFramedOpus(input, track, decoder, outChannels, streamChannels * 2, currentSession)
                } else {
                    streamFramedPcm(input, track, outChannels, currentSession)
                }
            } else {
                streamRawPcm(input, track, outChannels, currentSession)
            }
        } catch (e: Exception) {
            DiagLog.e("中继", "客户端会话异常：${e.message}", e)
        } finally {
            try { decoder?.stop() } catch (e: Exception) { }
            val finished = session
            if (finished != null) {
                DiagLog.i("中继", "接收结束：${sessionSummary(finished)}")
            }
            // 只暂停并清空缓冲、不释放音轨；仅当本会话仍是最新代次才暂停
            if (myGeneration == sessionGeneration) {
                synchronized(this) {
                    try {
                        audioTrack?.pause()
                        audioTrack?.flush()
                    } catch (e: Exception) { }
                }
            }
            val isLatest = myGeneration == sessionGeneration
            if (isLatest && isServerRunning) {
                // 服务仍在运行时（对端断流/重连）：退出「正在接收」态，恢复到「已就绪」，
                // 恢复保活锚点，通知与 UI 一并回到「等待接收」态。
                exitReceivingState()
            }
        }
    }

    /** 进入「正在接收音频」态：更新状态、通知与 UI，并让位保活无声锚点。 */
    private fun enterReceivingState() {
        if (!isServerRunning) return
        relayState = RelayClient.State.RECEIVING
        SilentPlayer.setScenarioAllowed(this, false)
        refreshNotification(notificationTextFor(RelayClient.State.RECEIVING))
        stateListener?.onState(RelayClient.State.RECEIVING, "正在接收电脑声音")
    }

    /** 退出「正在接收音频」态：恢复到「已就绪，等待电脑声音」，并恢复保活锚点。 */
    private fun exitReceivingState() {
        if (!isServerRunning) return
        relayState = RelayClient.State.READY
        SilentPlayer.setScenarioAllowed(this, true)
        refreshNotification(notificationTextFor(RelayClient.State.READY))
        stateListener?.onState(RelayClient.State.READY, "已接入中转服务器")
    }

    /** 中转链路扫描魔数失败时，受控触发一次中转重连（让 PC 重新发包头）。 */
    private fun onRelayHeaderMissed() {
        if (!relayClient.isRunning()) return
        if (relayHeaderEof) {
            DiagLog.w("中继", "中转管道已读到 EOF（对端断开），静默等待自动重连重建管道")
            return
        }
        val now = System.currentTimeMillis()
        relayHeaderMissCount++
        val minIntervalMs = 5000L
        val needReconnect = relayHeaderMissCount >= 1 && (now - relayLastForceReconnectAt) >= minIntervalMs
        if (!needReconnect) {
            DiagLog.w("中继", "中转包头缺失计数 ${relayHeaderMissCount}，距上次重连过近，暂不重连")
            return
        }
        relayHeaderMissCount = 0
        relayLastForceReconnectAt = now
        DiagLog.w("中继", "中转链路扫描不到 AURL 包头，触发受控重连让 PC 重新发包头")
        try {
            relayClient.forceReconnect()
        } catch (e: Exception) {
            DiagLog.e("中继", "触发中转重连失败：${e.message}", e)
        }
    }

    /** 读取 8 字节流包头。 */
    private fun readHeader(input: PushbackInputStream, isRelayPeer: Boolean = false): ByteArray? {
        val header = ByteArray(HEADER_SIZE)
        var filled = 0
        var idleMillis = 0L
        while (filled < HEADER_SIZE) {
            if (!isServerRunning) {
                headerIncomplete = true
                return null
            }
            try {
                val n = input.read(header, filled, HEADER_SIZE - filled)
                if (n < 0) {
                    if (filled > 0) input.unread(header, 0, filled)
                    headerIncomplete = true
                    return null
                }
                filled += n
                idleMillis = 0
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    if (filled > 0) input.unread(header, 0, filled)
                    headerIncomplete = true
                    return null
                }
            }
        }
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        if (magic == STREAM_MAGIC && (header[4].toInt() and 0xFF) == STREAM_VERSION) {
            return header
        }
        // 中转：极可能是「旧会话残留帧」或「包头被 WebSocket 帧边界拆散」，扫描魔数
        if (isRelayPeer) {
            return scanRelayMagic(input, header)
        }
        DiagLog.w("中继", "未检测到 AURL 包头，按官方裸 PCM 处理")
        input.unread(header, 0, HEADER_SIZE)
        return null
    }

    /** 中转链路上扫描 AURL 魔数：逐字节跳过残留，直到找到合法包头或超时放弃。 */
    private fun scanRelayMagic(input: PushbackInputStream, first: ByteArray): ByteArray? {
        input.unread(first, 0, first.size)
        val win = ByteArray(4)
        var winLen = 0
        var skipped = 0
        var idleMillis = 0L
        val maxSkip = 64 * 1024
        while (skipped < maxSkip) {
            if (!isServerRunning) {
                headerIncomplete = true
                return null
            }
            val b: Int
            try {
                b = input.read()
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    headerIncomplete = true
                    return null
                }
                continue
            }
            if (b < 0) {
                headerIncomplete = true
                relayHeaderEof = true
                return null
            }
            idleMillis = 0
            skipped++
            if (winLen < 4) {
                win[winLen] = b.toByte()
                winLen++
            } else {
                win[0] = win[1]
                win[1] = win[2]
                win[2] = win[3]
                win[3] = b.toByte()
            }
            if (winLen == 4 && win[0] == 'A'.code.toByte() && win[1] == 'U'.code.toByte() &&
                win[2] == 'R'.code.toByte() && win[3] == 'L'.code.toByte()
            ) {
                val rest = ByteArray(4)
                var filled = 0
                while (filled < 4) {
                    if (!isServerRunning) {
                        headerIncomplete = true
                        return null
                    }
                    try {
                        val n = input.read(rest, filled, 4 - filled)
                        if (n < 0) {
                            headerIncomplete = true
                            return null
                        }
                        filled += n
                    } catch (e: java.net.SocketTimeoutException) {
                        idleMillis += 100
                        if (idleMillis >= IDLE_TIMEOUT_MS) {
                            headerIncomplete = true
                            return null
                        }
                    }
                }
                val full = ByteArray(HEADER_SIZE)
                System.arraycopy("AURL".toByteArray(Charsets.US_ASCII), 0, full, 0, 4)
                System.arraycopy(rest, 0, full, 4, 4)
                if ((full[4].toInt() and 0xFF) == STREAM_VERSION) {
                    if (skipped > 4) {
                        DiagLog.i("中继", "中转链路跳过 ${skipped - 4} 字节残留后定位到 AURL 包头")
                    }
                    return full
                }
                winLen = 0
            }
        }
        DiagLog.e("中继", "中转链路扫描 $maxSkip 字节仍未找到 AURL 包头，放弃本次会话")
        headerIncomplete = true
        return null
    }

    /** 官方裸 PCM 流：直接写 AudioTrack。 */
    private fun streamRawPcm(input: InputStream, track: AudioTrack, outChannels: Int, session: DiagSession) {
        val buffer = ByteArray(4096)
        var read: Int
        var frameCount = 0
        var consecutiveErrors = 0
        val frameSize = 4
        while (isServerRunning && consecutiveErrors < 5) {
            try {
                read = input.read(buffer)
                if (read == -1) break
                consecutiveErrors = 0
                if (read > 0) {
                    recordChunk(session, buffer, read)
                    writeToTrack(track, buffer, read, frameSize, outChannels)
                    if (++frameCount % 5 == 0) {
                        levelsListener?.onLevels(calculateAudioLevels(buffer, read))
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: java.io.IOException) {
                consecutiveErrors++
                DiagLog.w("接收", "裸 PCM 读取失败 $consecutiveErrors/5：${e.message}")
            }
        }
    }

    /** 自研分帧 PCM 流：每帧 = 4 字节大端长度 + PCM 数据。 */
    private fun streamFramedPcm(input: PushbackInputStream, track: AudioTrack, outChannels: Int, session: DiagSession) {
        val frameSize = outChannels * 2
        var frameCount = 0
        while (isServerRunning) {
            val payload = readFrame(input) ?: break
            if (payload.isEmpty()) continue
            recordChunk(session, payload, payload.size)
            writeToTrack(track, payload, payload.size, frameSize, outChannels)
            if (++frameCount % 5 == 0) {
                levelsListener?.onLevels(calculateAudioLevels(payload, payload.size))
            }
        }
    }

    /** 自研分帧 Opus 流：每帧为 1 个 Opus 包，解码后写 AudioTrack。 */
    private fun streamFramedOpus(
        input: PushbackInputStream,
        track: AudioTrack,
        decoder: OpusDecoder?,
        outChannels: Int,
        inputFrameSize: Int,
        session: DiagSession,
    ) {
        if (decoder == null) return
        var frameCount = 0
        while (isServerRunning) {
            val packet = readFrame(input) ?: break
            if (packet.isEmpty()) continue
            session.packets++
            if (!session.firstPacketLogged) {
                session.firstPacketLogged = true
                val preview = packet.take(minOf(packet.size, 16)).joinToString(" ") { "%02x".format(it) }
                DiagLog.i("接收", "首个 Opus 包已到达：长度=${packet.size} 字节，前 ${minOf(packet.size, 16)} 字节=[$preview]")
            }
            decoder.decode(packet, packet.size) { pcm, size ->
                recordChunk(session, pcm, size)
                writeToTrack(track, pcm, size, inputFrameSize, outChannels)
                if (++frameCount % 5 == 0) {
                    levelsListener?.onLevels(calculateAudioLevels(pcm, size))
                }
            }
        }
    }

    /** 读取一个音频帧：4 字节大端长度 + 数据；流结束返回 null。 */
    private fun readFrame(input: PushbackInputStream): ByteArray? {
        while (true) {
            val lenBuf = ByteArray(4)
            if (!readFully(input, lenBuf, 4)) return null
            val length = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                ((lenBuf[1].toInt() and 0xFF) shl 16) or
                ((lenBuf[2].toInt() and 0xFF) shl 8) or
                (lenBuf[3].toInt() and 0xFF)
            // 若这 4 字节恰好是 AURL 魔数，说明 PC 端在推流中途重连并重发了新包头
            val isAurlMagic = lenBuf[0] == 'A'.code.toByte() && lenBuf[1] == 'U'.code.toByte() &&
                lenBuf[2] == 'R'.code.toByte() && lenBuf[3] == 'L'.code.toByte()
            if (isAurlMagic) {
                DiagLog.w("中继", "帧流中检测到新 AURL 包头（PC 重连重发），跳过包头继续解析后续帧")
                val rest = ByteArray(4)
                if (!readFully(input, rest, 4)) return null
                continue
            }
            if (length <= 0 || length > 1024 * 1024) {
                Log.w("AudioRelay", "非法帧长度 $length，结束本次流")
                return null
            }
            val payload = ByteArray(length)
            if (!readFully(input, payload, length)) return null
            return payload
        }
    }

    /** 尽力读满指定字节数。 */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var filled = 0
        var idleMillis = 0L
        while (filled < length) {
            if (!isServerRunning) return false
            try {
                val n = input.read(buffer, filled, length - filled)
                if (n < 0) return false
                filled += n
                idleMillis = 0
            } catch (e: java.net.SocketTimeoutException) {
                idleMillis += 100
                if (idleMillis >= IDLE_TIMEOUT_MS) {
                    return false
                }
            }
        }
        return true
    }

    /** 把一段 PCM 写入 AudioTrack（必要时做立体声转单声道与帧对齐）。 */
    private fun writeToTrack(
        track: AudioTrack,
        buffer: ByteArray,
        length: Int,
        inputFrameSize: Int,
        outChannels: Int,
    ) {
        // 停止后（isServerRunning 已置 false）不再写音轨，避免对已释放的 AudioTrack 调用 write
        if (!isServerRunning) return
        val alignedBytes = (length / inputFrameSize) * inputFrameSize
        if (alignedBytes <= 0) return
        // 关键：write 必须与 releaseAudioTrack / ensureAudioTrack 的 release 在同一把锁内完成，
        // 彻底消除「锁外 write 时音轨已被另一线程 release」的竞态窗口。这是 native 崩溃
        // （对已释放音轨 write → SIGSEGV）的直接根因，部分 ROM（ColorOS 等）不会抛 Java
        // 异常而是直接 native 崩溃，故必须用锁把「判等 + 状态检查 + write」原子化。
        synchronized(this) {
            val activeTrack = audioTrack
            if (activeTrack == null || activeTrack !== track) return
            if (activeTrack.state != AudioTrack.STATE_INITIALIZED) return
            // MODE_STREAM 必须用阻塞写：非阻塞写（WRITE_NON_BLOCKING）在缓冲满时会返回
            // 部分写入/0，且与「已 release」的竞态更易触发 native 层异常。统一用阻塞写，
            // 由 isServerRunning 守卫保证停止后尽快退出，不再依赖非阻塞的「立即返回」。
            val writeMode = AudioTrack.WRITE_BLOCKING
            try {
                val result = if (outChannels == 2 && inputFrameSize == 4) {
                    activeTrack.write(buffer, 0, alignedBytes, writeMode)
                } else if (outChannels == 1 && inputFrameSize == 4) {
                    val mono = convertStereoToMono(buffer, alignedBytes)
                    activeTrack.write(mono, 0, mono.size, writeMode)
                } else {
                    activeTrack.write(buffer, 0, alignedBytes, writeMode)
                }
                if (result < 0) {
                    DiagLog.w("音轨", "AudioTrack.write 返回错误码 $result（写入长度=$alignedBytes）")
                }
            } catch (e: IllegalStateException) {
                // AudioTrack 已被 release，忽略（停止后正常现象）
                DiagLog.w("音轨", "AudioTrack 已释放，跳过写入")
            } catch (e: Exception) {
                DiagLog.e("音轨", "AudioTrack 写入异常：${e.message}", e)
            }
        }
    }

    private fun supportsStereo(sampleRate: Int): Boolean {
        val size = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return size != AudioTrack.ERROR_BAD_VALUE && size != AudioTrack.ERROR && size > 0
    }

    /** 按流协商出的采样率与声道数创建（或复用）AudioTrack。 */
    @Synchronized
    private fun ensureAudioTrack(sampleRate: Int, outChannels: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null &&
            currentSampleRate == sampleRate &&
            currentOutChannels == outChannels &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            // AudioTrack.stop() 不改变 state，复用前必须确认它真的在播放
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    existing.flush()
                    existing.play()
                    DiagLog.w("音轨", "复用音轨时发现它不在播放态，已 flush 并重新 play")
                } catch (e: Exception) {
                    DiagLog.e("音轨", "AudioTrack 恢复播放失败：${e.message}", e)
                }
            } else {
                DiagLog.i("音轨", "复用已存在的音轨")
            }
            return existing
        }
        try {
            existing?.stop()
            existing?.release()
        } catch (e: Exception) { }
        audioTrack = null

        val channelConfig = if (outChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufSize <= 0) {
            DiagLog.e("音轨", "该采样率不被支持：getMinBufferSize 返回 $minBufSize（采样率=$sampleRate）")
            return null
        }
        val bufferSizeBytes = maxOf(minBufSize, minBufSize * 4)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioTrackFormat = AudioFormat.Builder()
            .setChannelMask(channelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioTrackFormat)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val built = builder.build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            DiagLog.e("音轨", "AudioTrack 初始化失败，state=${built.state}")
            built.release()
            return null
        }
        try { built.setVolume(currentVolume) } catch (e: Exception) { }
        built.play()
        audioTrack = built
        currentSampleRate = sampleRate
        currentOutChannels = outChannels
        currentBufferBytes = bufferSizeBytes
        DiagLog.i("音轨", "新建音轨成功：sampleRate=$sampleRate channels=$outChannels buffer=$bufferSizeBytes")
        return built
    }

    private fun setVolumeInternal(volume: Float) {
        try {
            val clamped = volume.coerceIn(0f, 1f)
            currentVolume = clamped
            RelayPrefs.setFloat(this, RelayPrefs.KEY_VOLUME, clamped)
            // 加锁访问，避免与 releaseAudioTrack 的 release 竞态（对已释放音轨 setVolume 同样危险）
            synchronized(this) {
                audioTrack?.setVolume(clamped)
            }
            DiagLog.i("音轨", "中继音量已设为：$clamped")
        } catch (e: Exception) {
            DiagLog.e("音轨", "设置音量失败：${e.message}", e)
        }
    }

    @Synchronized
    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { }
        audioTrack = null
        // 重置协商参数，下次 start 时按新流重新创建音轨
        currentSampleRate = 0
        currentOutChannels = 0
        currentBufferBytes = 0
    }

    private fun convertStereoToMono(stereoBuffer: ByteArray, length: Int): ByteArray {
        val monoSize = length / 2
        val monoBuffer = ByteArray(monoSize)
        var monoIndex = 0
        var i = 0
        while (i + 3 < length) {
            val left = (stereoBuffer[i].toInt() and 0xFF) or ((stereoBuffer[i + 1].toInt() and 0xFF) shl 8)
            val right = (stereoBuffer[i + 2].toInt() and 0xFF) or ((stereoBuffer[i + 3].toInt() and 0xFF) shl 8)
            val leftSigned = left.toShort().toInt()
            val rightSigned = right.toShort().toInt()
            val mono = ((leftSigned + rightSigned) / 2).toShort()
            monoBuffer[monoIndex] = (mono.toInt() and 0xFF).toByte()
            monoBuffer[monoIndex + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()
            i += 4
            monoIndex += 2
        }
        return monoBuffer
    }

    /** 计算 24 柱频谱（简化：按时域采样块均分，供波形动画）。 */
    private fun calculateAudioLevels(buffer: ByteArray, size: Int): FloatArray {
        val bands = 24
        val levels = FloatArray(bands)
        val samplesPerBand = size / (bands * 2)
        if (samplesPerBand <= 0) return levels
        for (i in 0 until bands) {
            var sum = 0f
            val start = i * samplesPerBand * 2
            val end = minOf(start + samplesPerBand * 2, size)
            for (j in start until end step 2) {
                if (j + 1 < size) {
                    val sample = (buffer[j].toInt() and 0xFF) or ((buffer[j + 1].toInt() and 0xFF) shl 8)
                    val normalized = sample.toShort().toFloat() / 32768f
                    sum += kotlin.math.abs(normalized)
                }
            }
            levels[i] = (sum / samplesPerBand).coerceIn(0f, 1f)
        }
        return levels
    }

    private fun branchName(framed: Boolean, isOpus: Boolean): String = when {
        !framed -> "官方裸PCM"
        isOpus -> "自研分帧Opus"
        else -> "自研分帧PCM"
    }

    private fun recordChunk(session: DiagSession, data: ByteArray, size: Int) {
        session.frames++
        session.bytes += size
        if (size > session.maxChunk) session.maxChunk = size
        var localPeak = 0
        var index = 0
        while (index + 1 < size) {
            val sample = ((data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > localPeak) localPeak = abs
            index += 2
        }
        if (localPeak > session.peak) session.peak = localPeak
        if (!session.firstChunkLogged) {
            session.firstChunkLogged = true
            val preview = data.take(minOf(size, 16)).joinToString(" ") { "%02x".format(it) }
            DiagLog.i("接收", "首个音频数据到达：分支=${session.branch} 长度=$size 字节，前 ${minOf(size, 16)} 字节=[$preview]")
        }
    }

    private fun sessionSummary(session: DiagSession): String {
        val seconds = (System.currentTimeMillis() - session.startedAt) / 1000.0
        return "分支=${session.branch} 时长=${String.format(Locale.US, "%.1f", seconds)}s " +
            "收到Opus包=${session.packets} 写入音轨块数=${session.frames} " +
            "写入字节=${session.bytes} 单块最大=${session.maxChunk}字节 全程峰值=${session.peak}"
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频中继服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "手机放音时显示接收状态"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, my.bridge.com.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, AudioRelayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridge 音频中继")
            .setContentText(text)
            .setSmallIcon(my.bridge.com.R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止接收", stopPendingIntent)
            .build()
    }

    private fun refreshNotification(text: String) {
        val manager = NotificationManagerCompat.from(this)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** 按中继状态给出前台通知文案。 */
    private fun notificationTextFor(state: RelayClient.State): String = when (state) {
        RelayClient.State.CONNECTING -> "正在连接中继服务器…"
        RelayClient.State.READY -> "中继已就绪，等待电脑声音…"
        RelayClient.State.RECEIVING -> "正在接收电脑声音 · 点此管理"
        RelayClient.State.ERROR -> "中继连接异常，点此管理"
        RelayClient.State.IDLE -> "声音中继已开启 · 点此管理"
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
