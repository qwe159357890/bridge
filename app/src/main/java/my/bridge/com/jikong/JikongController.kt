package my.bridge.com.jikong

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.bridge.com.core.network.ws.BridgeWebSocketClient
import timber.log.Timber

/**
 * 极空 BMS 控制器（进程级单例）：连接管理 + 轮询读取 + 数据缓存 + 主动上报。
 *
 * 职责：
 * 1. 连接/断开 BLE（复用 [JikongBleClient]）；
 * 2. 连接后周期性下发读帧（基本信息/单体电压/温度/告警），把 BLE 通知的原始字节
 *    解码进 [JikongStatus]；
 * 3. 状态变化经 WS 上报服务端（event=jikong_state），供管理后台远程查看；
 * 4. 接收管理后台下发的控制/写参数命令（由 CommandModule 调用本类的方法）。
 *
 * 省电优先：仅在「页面可见 + 已连接」时轮询；页面销毁即停止轮询（连接是否保留
 * 由 [keepConnection] 控制，默认释放——与参考项目省电策略一致）。
 */
class JikongController private constructor() {
    companion object {
        const val TAG = "Jikong"

        /**
         * 自动重连窗口（v0.2.77）：意外断开后持续重试，窗口内**一次都没成功过**才放弃；
         * 期间任一次成功即归零（成功退出本循环，下次断开重新起算窗口）。
         */
        const val RECONNECT_WINDOW_MS = 180_000L

        /** 自动重连重试间隔（常规档）。 */
        const val RECONNECT_RETRY_MS = 3_000L

        /** 扫描暂存陈旧阈值（v0.2.91 防僵尸）：进入极空页时距上次扫描超过此值即清空列表。 */
        const val SCAN_STALE_MS = 10 * 60_000L

        /** 自动重连重试间隔（快速档，GATT status=8 信号监督超时触发时使用，v0.2.78）。 */
        const val RECONNECT_FAST_RETRY_MS = 800L

        /** GATT status=8：连接监督超时（设备走远/遮挡，信号恢复即可能回连成功）。 */
        const val GATT_STATUS_CONN_TIMEOUT = 8

        @Volatile
        private var INSTANCE: JikongController? = null

        /** UI / 命令层访问入口（BridgeApplication 里 attach 后可用）。 */
        val instance: JikongController
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: JikongController().also { INSTANCE = it }
            }

        /**
         * 应用启动时注入 WebSocketClient（BridgeApplication 调用一次）。
         * 极空状态上报走 WS message 通道（event=jikong_state）。
         */
        fun attach(webSocketClient: BridgeWebSocketClient) {
            instance._webSocketClient = webSocketClient
        }
    }

    @Volatile
    private var _webSocketClient: BridgeWebSocketClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** 当前状态快照（UI 订阅此对象的重组字段）。 */
    @Volatile
    var status: JikongStatus = JikongStatus()
        private set

    /**
     * 状态版本号（Compose 可观察）：每次成功合并一帧数据后自增。
     * UI 在 Composable 里读取 `statusVersion.value` 即可订阅重组——数据一变就实时刷新，
     * 无需手动切页。字段变化频率对齐 BLE 0x02 数据流（约每 1s 一帧）。
     */
    val statusVersion = androidx.compose.runtime.mutableStateOf(0L)

    /** 页面是否可见（决定是否轮询）。 */
    @Volatile
    var pageVisible: Boolean = false
        private set

    /** 是否保留连接（页面不可见时是否断开 BLE）。默认释放，省电。 */
    @Volatile
    var keepConnection: Boolean = false

    /** 演示模式进行中（enterDemoMode 置位；disconnect / 真机连接成功后清除）。 */
    @Volatile
    var demoMode: Boolean = false

    /**
     * 上一帧 SOC（v0.2.89）：每帧判定「电量与上一帧不同」用；-1 = 本连接还没有前帧。
     * 连接/重连成功时重置（无前帧时只与「上次播报值」比较，与 v0.2.88 首帧行为一致）。
     */
    @Volatile
    private var prevAnnounceSoc = -1

    /**
     * 进程启动后「首次收到电量」的强制播报标记（v0.2.90，用户拍板）：
     * 小喇叭静音**不拦截**这一次播报（每次 App 启动后连接成功收到电量的那一次永远播）；
     * 真正播出才消耗标记，进程存活期内至多一次。
     */
    @Volatile
    private var firstAnnouncePending = true

    /** 当前会话是否收到过 0x02 实时数据帧（UI 用于区分「无数据」与「真 0 值」，v0.2.77）。 */
    @Volatile
    var hasDataFrame: Boolean = false
        private set

    // ---- 自动重连（v0.2.77）----
    /** 应用上下文（connect 时缓存，供后台重连使用）。 */
    @Volatile
    private var appContext: Context? = null

    /** 自动重连开关：用户成功连接后置位；用户主动断开 / 放弃 / 离开页面时清除。 */
    @Volatile
    private var autoReconnectEnabled: Boolean = false

    /** 最近一次用户连接的设备地址（重连目标）。 */
    @Volatile
    private var lastConnectAddress: String = ""

    private var reconnectJob: Job? = null

    /** 主动重建链路的连续次数（收到 0x02 即归零；防死循环重建，v0.2.80）。 */
    @Volatile
    private var datalessRecycles = 0

    /** 置位后立刻主动断开 → onConnectionChanged 触发快档自动重连（v0.2.80）。 */
    @Volatile
    private var dataRecyclePending = false

    /** 轮询间隔（毫秒）。 */
    @Volatile
    var pollIntervalMs: Long = 2000

    private var pollJob: Job? = null
    private var lastReportedJson: String = ""

    /** 最近一次收到 0x02 数据帧的时间（仅 0x02 刷新；0x01/0x03 不算，供断流自愈判断）。 */
    @Volatile
    private var lastDataFrameAt: Long = 0L

    // ---- 扫描结果暂存（进程级，切页不丢失；刷新才重新扫描）----
    @Volatile
    var cachedDevices: List<BleDevice> = emptyList()
        private set

    @Volatile
    var lastScanAt: Long = 0L
        private set

    /** 暂存扫描结果（扫描完成后调用）。 */
    fun cacheScanResult(devices: List<BleDevice>) {
        cachedDevices = devices
        lastScanAt = System.currentTimeMillis()
    }

    /**
     * 清空扫描暂存（v0.2.91 防僵尸）：①连接成功时调用（连上的设备不属于列表）；
     * ②进入极空页且距上次扫描超过 [SCAN_STALE_MS] 时调用（陈旧 RSSI/已消失设备）；
     * ③进入演示模式时调用（v0.2.92 用户拍板：进演示也算）。
     * 只清内存暂存，重新点扫描即可刷新。
     */
    fun clearScanCache() {
        if (cachedDevices.isNotEmpty()) {
            cachedDevices = emptyList()
            Timber.tag(TAG).i("扫描暂存已清空（防僵尸）")
        }
    }

    init {
        // 连接状态变化时同步 status.connected 并上报（Binder 线程回调，任何异常都吞掉）
        JikongBleClient.onConnectionChanged = { connected ->
            try {
                val s = status
                s.connected = connected
                s.deviceName = JikongBleClient.connectedDeviceName
                s.deviceAddress = JikongBleClient.connectedDeviceAddress
                s.lastUpdateAt = System.currentTimeMillis()
                if (!connected) {
                    // 断开时复位实时数据，避免管理后台看到陈旧数值
                    s.totalVoltage = 0f; s.current = 0f; s.soc = 0
                    s.cellVoltages = emptyList(); s.temperatures = emptyList()
                    hasDataFrame = false
                    // v0.2.79 自动重连：非用户主动断开（开关仍为 true）且页面在前台时自动恢复。
                    // 真机 2026-11-06 日志实证：三次会话均以 GATT status=8（连接监督超时=信号丢失）
                    // 断开，存活 16s/25s/97s——用户要求「只要不主动断开就一直尝试，180s 内一次没成功才放弃」。
                    if (autoReconnectEnabled && (pageVisible || keepConnection)) {
                        startReconnect(fast = dataRecyclePending)
                    }
                }
                reportStatus()
                // 连接状态变化也要触发 UI 重组（否则断开后界面仍停留「已连接」，
                // 表现为「点断开没反应」——其实已断开，只是没刷新）。
                statusVersion.value = statusVersion.value + 1
                Timber.tag(TAG).i("连接状态变化：connected=$connected")
            } catch (e: Exception) {
                Timber.w(e, "连接状态回调异常（已忽略）")
            }
        }
        // 收到通知字节 → 解码进状态
        JikongBleClient.onDataReceived = { bytes ->
            handleData(bytes)
        }
    }

    // ==================== 连接控制 ====================

    suspend fun connect(context: Context, address: String): Boolean = mutex.withLock {
        appContext = context.applicationContext
        lastConnectAddress = address
        // 用户手动连接优先：终止可能正在跑的自动重连循环
        reconnectJob?.cancel()
        reconnectJob = null
        val ok = JikongBleClient.connect(context, address)
        if (ok) {
            // 真机连接成功即退出演示模式
            demoMode = false
            // v0.2.77：成功连接后启用自动重连（此后意外断开将自动恢复；重连成功即归零）
            autoReconnectEnabled = true
            // v0.2.89：重置「上一帧 SOC」，连接后首帧只与上次播报值比较
            prevAnnounceSoc = -1
            // v0.2.91：连接成功即清空扫描暂存（防僵尸）
            clearScanCache()
            startPolling()
        }
        ok
    }

    fun disconnect() {
        // 用户主动断开：关闭自动重连（v0.2.77，用户明确要求「不主动断开才自动重连」）
        autoReconnectEnabled = false
        dataRecyclePending = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopPolling()
        JikongBleClient.disconnect()
        if (demoMode) {
            // v0.2.67 修复：演示模式断开无效——演示模式本就没有 BLE 连接，
            // onConnectionChanged 回调不会触发，status.connected 永远停在 true。
            // 此处手动复位状态并触发重组，回到扫描/连接界面。
            // v0.2.76 完善：复位所有展示字段。之前只清电压/电流/SOC/单体/温度，
            // 剩余容量/循环/充电器/极值等 demo 残留会混进下一次真机连接的界面
            //（2026-11-06 真机截图实证：剩余 72.00Ah、循环 10 次、最高温 29℃ 全是 demo 旧值）。
            demoMode = false
            status = JikongStatus()
            lastDataFrameAt = 0L
            hasDataFrame = false
            reportStatus()
            statusVersion.value = statusVersion.value + 1
            Timber.tag(TAG).i("退出演示模式（用户点击断开）")
        }
    }

    /**
     * 进入「演示模式」：不连接保护板，直接灌入一份真实示例数据（真机 v0.2.59 实测值），
     * 并把 connected 置为 true，让用户无需靠近设备即可查看/验证三页 UI 是否复刻官方 APP。
     * 再次调用 disconnect() 或真机连接后即退出演示。
     */
    fun enterDemoMode() {
        stopPolling()
        demoMode = true
        // v0.2.92 防僵尸补口：进入演示模式同样清空扫描暂存（用户拍板：进演示也算，与连接成功同口径）
        clearScanCache()
        val s = status
        s.connected = true
        s.deviceName = "演示设备 JK-BD6A24S8PD"
        s.deviceAddress = "AA:BB:CC:DD:EE:FF"
        s.model = "JK-BD6A24S8PD"
        s.hardwareVersion = "20H"
        s.softwareVersion = "20.20"

        // 总览
        s.totalVoltage = 67.5f
        s.current = 7.8f            // 充电
        s.power = s.totalVoltage * s.current
        s.soc = 72
        s.nominalCapacity = 100f    // 标称 100Ah
        s.remainCapacity = 72f

        // 单体：20 串真实（3.37x 附近微差），最高/最低动态可辨
        val volts = floatArrayOf(
            3.377f, 3.376f, 3.375f, 3.378f, 3.376f, 3.375f, 3.377f, 3.376f,
            3.375f, 3.374f, 3.376f, 3.377f, 3.375f, 3.376f, 3.374f, 3.378f,
            3.376f, 3.375f, 3.377f, 3.376f,
        ).toList()
        s.cellCount = volts.size
        s.cellVoltages = volts
        s.avgCellVoltage = volts.average().toFloat()
        s.maxCellVoltage = volts.max()
        s.minCellVoltage = volts.min()
        s.maxCellIndex = volts.indexOf(s.maxCellVoltage)
        s.minCellIndex = volts.indexOf(s.minCellVoltage)
        s.diffVoltage = s.maxCellVoltage - s.minCellVoltage

        // 内阻：20 串
        s.cellResistances = List(20) { 0.34f }

        // 温度
        s.temperatures = listOf(28.5f, 29.0f, 28.8f)
        s.tempCount = 3
        s.maxTemperature = s.temperatures.max()
        s.minTemperature = s.temperatures.min()
        s.tempDiff = s.maxTemperature - s.minTemperature

        // MOS / 均衡
        s.chargeMosOn = true
        s.dischargeMosOn = true
        s.balanceOn = false
        s.balanceCurrent = 0f

        // 设备信息 / 扩展字段
        s.cycleCount = 10
        s.chargeCount = 12
        s.soh = 98
        s.batteryType = "铁锂"
        s.cycleCapacity = 812.5f
        s.heaterOn = false
        s.heaterCurrent = 0f
        s.chargerState = "插入"
        s.remainTimeSeconds = 4 * 3600 + 22 * 60 + 18   // 4:22:18

        // 参数（参数设置页展示）
        s.cellFullVoltage = 3.65f
        s.cellEmptyVoltage = 2.60f
        s.chargeOverTemp = 55f
        s.dischargeOverTemp = 60f
        s.chargeCurrentLimit = 50f
        s.dischargeCurrentLimit = 100f

        s.lastUpdateAt = System.currentTimeMillis()
        // v0.2.91：演示模式视为「已读到数据」——统计页记一笔门控放行（配合内存演示台账）
        hasDataFrame = true
        statusVersion.value = statusVersion.value + 1
        // v0.2.86：演示数据也上报服务端——管理后台「极空页 1:1 复刻」需要用演示模式
        // 调试界面/联调，后台据此显示「已连接 · 演示设备」而非未连接。
        reportStatus()
        Timber.tag(TAG).i("进入演示模式（未连接保护板，演示数据已上报服务端）")
    }

    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
        if (visible) {
            if (JikongBleClient.connected) startPolling()
        } else {
            if (!keepConnection) {
                // 离开页面即主动释放连接（省电优先）：等同用户离开，不算意外断开，
                // 关闭自动重连，避免后台反复回连耗电（v0.2.77）。
                autoReconnectEnabled = false
                reconnectJob?.cancel()
                reconnectJob = null
                stopPolling()
                // 释放连接（省电优先；页面重新进入时再连）
                JikongBleClient.disconnect()
            } else {
                // 保留连接但不轮询
                stopPolling()
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        // 新会话从零起算：启动加速重试依赖「还没收到过 0x02」这个状态
        lastDataFrameAt = 0L
        hasDataFrame = false
        pollJob = scope.launch {
            // JK02 时序（BHP1000/pippofo 实证）：连接后发 0x97（设备信息）→ 等 500ms
            // → 发 0x96（读单体）触发 0x01+0x03+0x02 burst，之后 BMS 持续流式推 0x02。
            // 关键：0x97 与 0x96 之间必须有间隔，否则背靠背连发会命令冲突，
            // BMS 只循环回 0x01+0x03，永不进入 0x02 数据流（v0.2.58 实测正是此现象）。
            // 首帧 0x97 立即下发（连接成功后立刻拉取，不再空等 500ms），仅保留命令间隔。
            Timber.tag(TAG).i("发送 0x97（读设备信息）")
            val ok97 = runCatching { JikongBleClient.write(JikongProtocol.buildDeviceInfo()) }
                .getOrDefault(false)
            if (!ok97) Timber.tag(TAG).w("0x97 首发失败（GATT 忙），交由启动 ramp 重试")
            delay(500)
            Timber.tag(TAG).i("发送 0x96（触发数据流）")
            runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }

            val connectAt = System.currentTimeMillis()
            var lastTriggerAt = connectAt
            var last97At = if (ok97) lastTriggerAt else 0L
            while (isActive && JikongBleClient.connected) {
                delay(400)
                val now = System.currentTimeMillis()
                if (lastDataFrameAt > 0L) {
                    // 0x02 断流自愈守护（v0.2.76 修复）：只看 0x02 数据帧，超过 4s 没收到
                    // 就补发 0x96（限频 3s 一次，防止风暴）。
                    val sinceData = now - lastDataFrameAt
                    if (sinceData > 4000 && now - lastTriggerAt > 3000) {
                        lastTriggerAt = now
                        Timber.tag(TAG).w("0x02 断流 %ds，补发 0x96 触发数据流", sinceData / 1000)
                        runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }
                    }
                } else {
                    // v0.2.77 启动加速（用户反馈：连接后要几秒~10s 才出数据）：
                    // 启动阶段还没有任何 0x02 时，每 1.2s 就重试一次 0x96（旧逻辑要等 4s
                    // 才第一次补发）。真机日志实测连接后 ~1s 内数据即可到达，剩余延迟全是
                    // 触发失败后的等待——最坏情况 ~2.4s 内拉起数据流。
                    if (now - lastTriggerAt > 1200) {
                        lastTriggerAt = now
                        // v0.2.80 首连半死链自愈（用户真机实证）：蓝牙闲置 ~10 分钟后首连，
                        // 链路建立但数据通道打不通（BMS 蓝牙模块休眠未醒），旧流程要干等
                        // ~1min 直到 status=8 断开；而自动重连建的第 2 条链路立即有数据。
                        // ⇒ 12s 内仍无任何 0x02 就主动断开重建（dataRecyclePending → 快档
                        // 重连），把 1 分钟缩短到 ~15s。连续重建限 3 次，防「休眠↔重建」死循环。
                        if (now - connectAt > 12_000) {
                            if (datalessRecycles < 3) {
                                datalessRecycles++
                                dataRecyclePending = true
                                Timber.tag(TAG).w(
                                    "连接 %ds 仍无 0x02（疑似首连半死链），主动重建链路（第 %d/3 次）",
                                    (now - connectAt) / 1000, datalessRecycles,
                                )
                                JikongBleClient.disconnect()  // → 快档自动重连 → startPolling
                                return@launch
                            }
                            Timber.tag(TAG).w("已连续重建 3 次仍无 0x02，保持当前链路继续 ramp（等模块自行苏醒）")
                        }
                        // v0.2.79：设备信息还没拿到（0x97 首发失败）→ 按「0x97→500ms→0x96」
                        // 完整时序重试，否则首连后 型号/硬件/软件版本 一直空白。
                        if (status.model.isBlank() && now - last97At > 3000) {
                            last97At = now
                            Timber.tag(TAG).w("启动 ramp：设备信息缺失，重发 0x97（完整时序）")
                            runCatching { JikongBleClient.write(JikongProtocol.buildDeviceInfo()) }
                            delay(500)
                        }
                        Timber.tag(TAG).w("启动阶段未见 0x02，重发 0x96 触发数据流")
                        runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }
                    }
                }
            }
        }
    }

    /**
     * v0.2.77 自动重连：连接意外断开后周期性回连 [lastConnectAddress]。
     * - 放弃条件：[RECONNECT_WINDOW_MS]（180s）内**一次都没成功**；期间任一次成功即退出
     *   本循环并归零（下次断开重新起算窗口）——即「只要成功过就继续努力」。
     * - 终止条件：用户点断开（disconnect 清开关）/ 离开页面（setPageVisible 清开关）。
     * - 重试频率分两档（v0.2.78，用户拍板）：断开原因是 **GATT status=8（信号监督超时）**
     *   或 [fast]=true（v0.2.80 主动重建链路）时用 [RECONNECT_FAST_RETRY_MS]（800ms）
     *   高频快连；其它原因走 [RECONNECT_RETRY_MS]（3s）常规档。两档窗口同为 180s。
     * - 成功后走 startPolling 拉起数据流。
     */
    private fun startReconnect(fast: Boolean = false) {
        if (reconnectJob?.isActive == true) return
        dataRecyclePending = false
        val ctx = appContext ?: return
        if (lastConnectAddress.isBlank()) return
        // 以「触发本次重连的断开原因」定档：循环期间新尝试产生的 status 变化不改档
        val fastMode = fast || JikongBleClient.lastDisconnectStatus == GATT_STATUS_CONN_TIMEOUT
        val retryMs = if (fastMode) RECONNECT_FAST_RETRY_MS else RECONNECT_RETRY_MS
        val windowStart = System.currentTimeMillis()
        Timber.tag(TAG).i(
            "连接意外断开，启动自动重连（档位=%s / 间隔 %dms / 窗口 %ds / 目标 %s）",
            if (fastMode) "快速(status=8)" else "常规",
            retryMs, RECONNECT_WINDOW_MS / 1000, lastConnectAddress,
        )
        reconnectJob = scope.launch {
            while (isActive && autoReconnectEnabled && (pageVisible || keepConnection) &&
                !JikongBleClient.connected
            ) {
                if (System.currentTimeMillis() - windowStart > RECONNECT_WINDOW_MS) {
                    Timber.tag(TAG).w(
                        "自动重连：%ds 内一次都没成功，放弃（需用户手动重连）",
                        RECONNECT_WINDOW_MS / 1000,
                    )
                    autoReconnectEnabled = false
                    break
                }
                val ok = runCatching { JikongBleClient.connect(ctx, lastConnectAddress) }
                    .getOrDefault(false)
                if (ok) {
                    Timber.tag(TAG).i("自动重连成功（失败窗口归零）")
                    demoMode = false
                    // v0.2.89：重连成功同样重置「上一帧 SOC」（首帧只与上次播报值比较）
                    prevAnnounceSoc = -1
                    // v0.2.91：连接成功即清空扫描暂存（防僵尸）
                    clearScanCache()
                    startPolling()
                    break
                }
                delay(retryMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 语音播报判定（v0.2.89，用户逐条拍板；取代 v0.2.88「连接后首帧判一次」规则）：
     * - **每帧持续判定**：0x02 帧到达即判定（不再限连接后首帧）。
     * - 仅页面可见时播报；演示模式不播。
     * - 播报条件（任一满足即播）：
     *   ①SOC ≠ 上一帧 或 ≠ 上次播报值 → 播（本连接无前帧时只与上次播报值比较）；
     *   ②SOC 相同且距上次播报 ≥ 播报间隔 → 播（用户拍板保留）；
     *   ③SOC 低于预警阈值且距上次带提醒播报 ≥ 预警提醒间隔（设置可调，默认 5 分钟）→ 播。
     *   其中「首次跌破阈值」必然伴随 SOC 变化，由 ① 立即播报带提醒——即 1A 语义。
     * - 话术：「当前电量 N%」；低于低电量阈值追加「电量低，请注意充电」。
     * - 「上次播报值/时间」「上次带提醒播报时间」持久化（JikongPrefs），进程重启不误播。
     * - v0.2.90 静音：小喇叭静音（持久化）拦截一切播报；唯一例外=进程启动后首次收到电量
     *   的强制播报（[firstAnnouncePending]，不受静音影响，真正播出才消耗）。
     */
    private fun maybeAnnounce(s: JikongStatus) {
        val soc = s.soc
        if (soc !in 0..100) return
        val prev = prevAnnounceSoc
        prevAnnounceSoc = soc        // 每帧都推进「上一帧」，与页面可见性无关（真实前帧语义）
        if (demoMode || !pageVisible) return
        val ctx = appContext ?: return
        if (!JikongPrefs.speakEnabled(ctx)) return
        // v0.2.90：小喇叭静音（持久化）拦截一切播报；唯一例外=进程启动后首次（forced），
        // 「连接成功并收到剩余电量」的那一次永远播（用户拍板），真正播出才消耗强制机会。
        val forced = firstAnnouncePending
        if (!forced && JikongPrefs.muted(ctx)) return
        val now = System.currentTimeMillis()
        val lastSoc = JikongPrefs.lastSpokenSoc(ctx)
        val changed = (prev != -1 && soc != prev) || soc != lastSoc
        val due = now - JikongPrefs.lastSpokenAt(ctx) >=
            JikongPrefs.speakIntervalMin(ctx) * 60_000L
        val low = soc < JikongPrefs.speakLowSoc(ctx)
        val warnDue = low && now - JikongPrefs.lastWarnAt(ctx) >=
            JikongPrefs.speakWarnRepeatMin(ctx) * 60_000L
        if (!(forced || changed || due || warnDue)) {
            Timber.tag(TAG).i("语音播报判定：不播（SOC=%d 未变、间隔未到、无预警到期）", soc)
            return
        }
        firstAnnouncePending = false
        val text = "当前电量 $soc%" + if (low) "，电量低，请注意充电" else ""
        JikongPrefs.setLastSpoken(ctx, soc, now)
        if (low) JikongPrefs.setLastWarnAt(ctx, now)
        JikongSpeaker.speak(ctx, text)
        Timber.tag(TAG).i(
            "语音播报：%s（上次 SOC=%d，changed=%s due=%s warnDue=%s forced=%s muted=%s）",
            text, lastSoc, changed, due, warnDue, forced, JikongPrefs.muted(ctx),
        )
    }

    // ==================== 数据读取 ====================

    /**
     * 手动下发一轮读帧（页面「刷新」按钮 / 服务端命令触发用）。
     * 先 0x97 再 0x96，中间间隔 500ms，触发 BMS 数据流。
     */
    suspend fun requestRealtimeData() {
        runCatching { JikongBleClient.write(JikongProtocol.buildDeviceInfo()) }
        delay(500)
        runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }
    }

    /**
     * 处理收到的原始字节：BLE 通知按 MTU（默认 20B）分包，极空一帧常超 20B 被拆散。
     * 这里做流式缓冲重组：把分片拼进缓冲区，反复尝试从中切出完整帧再解码。
     * 任何异常都吞掉，绝不崩溃。
     */
    private val rxStream = java.io.ByteArrayOutputStream()

    private fun handleData(bytes: ByteArray) {
        try {
            synchronized(rxStream) {
                rxStream.write(bytes)
                // 逐帧提取：每次从缓冲区头部解析出一帧就切掉，直到无法再切出完整帧
                while (true) {
                    val buf = rxStream.toByteArray()
                    if (buf.isEmpty()) break
                    val frame = JikongProtocol.extractFrame(buf)
                    if (frame == null) {
                        // 非协议帧头（AT 心跳 41 54..、C8 ACK AA 55 90 EB 等）或半包：
                        // 向前搜索下一个响应帧头 55 AA EB 90，跳过垃圾字节，避免缓冲无限增长。
                        val syncIdx = findFrameHead(buf)
                        if (syncIdx > 0) {
                            // 丢弃帧头之前的垃圾字节
                            rxStream.reset()
                            rxStream.write(buf.copyOfRange(syncIdx, buf.size))
                            continue
                        }
                        break
                    }
                    // 切掉已消费的帧（含帧头到帧尾的完整长度）
                    val consumed = frame.consumedLength
                    val rest = buf.copyOfRange(consumed, buf.size)
                    rxStream.reset()
                    rxStream.write(rest)
                    dispatchFrame(frame.frame)
                }
                // 防止缓冲区异常增长（无帧边界时）——超过 4KB 丢弃重建
                if (rxStream.size() > 4096) rxStream.reset()
            }
        } catch (e: Exception) {
            Timber.w(e, "处理极空字节流失败（已忽略，不影响运行）")
        }
    }

    /** 在字节流中查找下一个响应帧头 `55 AA EB 90` 的位置；未找到返回 -1。 */
    private fun findFrameHead(buf: ByteArray): Int {
        for (i in 0..(buf.size - 4)) {
            if ((buf[i].toInt() and 0xFF) == 0x55 &&
                (buf[i + 1].toInt() and 0xFF) == 0xAA &&
                (buf[i + 2].toInt() and 0xFF) == 0xEB &&
                (buf[i + 3].toInt() and 0xFF) == 0x90
            ) {
                return i
            }
        }
        return -1
    }

    private fun dispatchFrame(frame: JikongFrame) {
        scope.launch {
            try {
                mergeFrame(frame)
            } catch (e: Exception) {
                Timber.w(e, "合并极空帧失败（已忽略，不影响运行）")
            }
        }
    }

    private fun mergeFrame(frame: JikongFrame) {
        val s = status
        when (frame.command) {
            JikongProtocol.FRAME_DATA -> {
                parseDataFrame(s, frame.data)          // 0x02
                lastDataFrameAt = System.currentTimeMillis()
                hasDataFrame = true
                datalessRecycles = 0   // v0.2.80：数据通了，重建计数归零
                maybeAnnounce(s)       // v0.2.89：每帧判定播报（变化/间隔/预警，规则见函数注释）
            }
            JikongProtocol.FRAME_SETTINGS -> parseSettingsFrame(s, frame.data)  // 0x01
            JikongProtocol.FRAME_DEVICE_INFO -> parseDeviceFrame(s, frame.data) // 0x03
            else -> Timber.tag(TAG).d("未处理的 JK02 帧类型 0x${frame.command.toString(16)}")
        }
        s.lastUpdateAt = System.currentTimeMillis()
        recomputeExtremes(s)
        reportStatus()
        // 通知 Compose 重组：mutableStateOf 的 snapshot 系统线程安全，可在任意线程自增。
        // UI 读到新版本号即触发重组，数据实时刷新（无需手动切页）。
        statusVersion.value = statusVersion.value + 1
    }

    /**
     * 解析 0x02 数据帧（JK02 格式字段偏移，来源 peff74/Arduino-jk-bms + BHP1000 实证，
     * 经 v0.2.59 真机字节逐字段校准确认）。
     * data 为完整原始帧（含 4 字节帧头 + 1 字节帧类型）。
     *
     * 关键偏移（字节下标从 0 起，均已真机验证）：
     *   6+i*2   单体电压（u16 ×0.001V，遇到 0 截止）
     *   74      （注：此偏移为 24S 老固件，32S 布局下平均电压在 90，见下方）
     *   96+i*2  内阻（u16 ×0.001Ω，32S 布局 = 64+32+i*2）
     *   144     MOS 温度（i16 ×0.1℃，32S = 112+32）
     *   150     总电压（u32 ×0.001V）
     *   158     电流（i32 ×0.001A，正=充电）
     *   162     电池温度1（i16 ×0.1℃）
     *   164     电池温度2（i16 ×0.1℃）
     *   170     均衡电流（i16 ×0.001A）
     *   172     均衡动作（u8，>0=均衡中）
     *   173     SOC（u8 %）
     *   174     剩余容量（u32 ×0.001Ah）
     *   178     标称容量（u32 ×0.001Ah）
     *   182     循环次数（u32）
     *   186     循环容量（u32 ×0.001Ah）
     *   190     SOH（u8 %）
     *   198     充电 MOS（u8，>0=on）
     *   199     放电 MOS（u8，>0=on）
     *   215     加热状态（u8，>0=开）
     *   218     应急时间/剩余时间（u16，秒）
     *   236     加热电流（i16 ×0.001A）
     *   245     充电器状态（u8，>0=已插入）
     *   275     电池类型（u8，0=铁锂/1=三元/2=钛酸锂）
     *
     * 以上偏移 2026-11-06 用真机 0x02 帧逐字段实证（JK-BD6A24S8PD, hw 20H / sw 20.20）：
     * 20 串总和 65.073V ≈ 总压@150=65.133V、剩余@174=41.513Ah = 标称@178=50.0Ah × SOC@173=83%，
     * 循环@182=10、SOH@190=100、类型@275=0(铁锂)、温度@144/162/164、MOS@198/199 全部自洽。
     * 布局归属 = 「新布局」（syssi JK02_32S 选项 / batmon-ha 的 fw≥11 分支，即 legacy 基础偏移 +32；
     * syssi 明确说明 JK02_24S/32S 是帧格式选项而非串数）。例外：内阻实位 @80（非 96）、
     * 平均电压实位 @74（非 90）——已改为重算，见 parseDataFrame 内注释。
     * 交叉验证来源：syssi/esphome-jk-bms、fl4p/batmon-ha、wenwumumu/JK-BMS-4G、AppleSang/jkbms-web。
     */
    private fun parseDataFrame(s: JikongStatus, d: ByteArray) {
        // 单体电压：offset 6，每串 2B LE，×0.001V。串数=遇到 0 截止，最多 32 串。
        val maxCells = 32
        val cells = ArrayList<Float>(maxCells)
        val resistances = ArrayList<Float>(maxCells)
        for (i in 0 until maxCells) {
            val off = 6 + i * 2
            if (off + 1 >= d.size) break
            val mv = readU16(d, off)
            if (mv == 0) break // 遇到 0 说明已到串数末尾
            cells.add(mv * 0.001f)
        }
        s.cellCount = cells.size
        s.cellVoltages = cells

        // 内阻：offset 80 + i*2，每串 2B LE，单位 0.001mΩ（µΩ），存为 mΩ。
        // 2026-11-06 真机帧实证（JK-BD6A24S8PD, sw 20.20）：20 串值落在 80..118
        // （333~345，均匀 0.33x mΩ，恰好到串数截止、后随全 0），@96 读出的是内阻数组
        // 中段而非起点。v0.2.64 曾按 32S 文档改 96，对本机反而是错的。
        // 注意：不同固件布局可能不同，如他机异常需重新实证。
        for (i in 0 until cells.size) {
            val off = 80 + i * 2
            if (off + 1 >= d.size) break
            resistances.add(readU16(d, off) * 0.001f)
        }
        s.cellResistances = resistances

        // 单体平均电压：不读协议字段（本机 @74 才是平均值、@90 读出 0.34V 垃圾值），
        // 直接由 cells 重算，跨固件最稳（recomputeExtremes 同思路）。
        if (cells.isNotEmpty()) s.avgCellVoltage = cells.average().toFloat()

        // 总电压：offset 150-153，uint32 LE，×0.001V
        if (d.size >= 154) s.totalVoltage = readU32(d, 150) * 0.001f

        // 电流：offset 158-161，int32 LE，×0.001A（正=充电）
        if (d.size >= 162) s.current = readI32(d, 158) * 0.001f

        s.power = s.totalVoltage * s.current

        // 温度：MOS 温度 offset 144，电池温度1 offset 162，电池温度2 offset 164，均 i16 ×0.1℃
        val temps = ArrayList<Float>()
        if (d.size >= 146) temps.add(readI16(d, 144) / 10f)      // MOS 温度
        if (d.size >= 164) temps.add(readI16(d, 162) / 10f)      // 电池温度1
        if (d.size >= 166) temps.add(readI16(d, 164) / 10f)      // 电池温度2
        s.tempCount = temps.size
        s.temperatures = temps

        // SOC：offset 173
        if (d.size >= 174) s.soc = d[173].toInt() and 0xFF

        // 剩余容量：offset 174-177，uint32 LE，×0.001Ah
        if (d.size >= 178) s.remainCapacity = readU32(d, 174) * 0.001f

        // 标称容量：offset 178-181，uint32 LE，×0.001Ah
        if (d.size >= 182) s.nominalCapacity = readU32(d, 178) * 0.001f

        // 循环次数：offset 182-185，uint32 LE
        if (d.size >= 186) s.cycleCount = readU32(d, 182).toInt()

        // 循环容量：offset 186-189，uint32 LE，×0.001Ah
        // （syssi/esphome-jk-bms + fl4p/batmon-ha 均确认：32S 布局 total_charge_throughput @186）
        if (d.size >= 190) s.cycleCapacity = readU32(d, 186) * 0.001f

        // SOH：offset 190
        if (d.size >= 191) s.soh = d[190].toInt() and 0xFF

        // 加热状态：offset 215（0=关，1=开）
        if (d.size >= 216) s.heaterOn = (d[215].toInt() and 0xFF) > 0

        // 应急时间（剩余时间）：offset 218-219，uint16 LE，单位秒
        if (d.size >= 220) s.remainTimeSeconds = readU16(d, 218)

        // 加热电流：offset 236-237，i16 LE，×0.001A
        if (d.size >= 238) s.heaterCurrent = readI16(d, 236) / 1000f

        // 充电器状态：offset 245（0=未插入，1=已插入）
        if (d.size >= 246) s.chargerState = if ((d[245].toInt() and 0xFF) > 0) "插入" else "未插入"

        // 电池类型：offset 275（243+32），0=LFP(铁锂)、1=Li-ion(三元)、2=LTO(钛酸锂)
        if (d.size >= 276) {
            s.batteryType = when (d[275].toInt() and 0xFF) {
                0 -> "铁锂"
                1 -> "三元"
                2 -> "钛酸锂"
                else -> ""
            }
        }

        // 均衡电流：offset 170-171，i16 LE，×0.001A
        if (d.size >= 172) s.balanceCurrent = readI16(d, 170) / 1000f

        // 均衡状态：offset 172（>0=正在均衡）。2026-11-06 真机 0x02 帧实证：该字节与
        // 均衡电流完美联动（0.597A↔1，0A↔0）——语义是「正在均衡」（实时动作），不是
        // 均衡开关设置。UI 修完强跳过冻结后，此灯随 BMS 实际均衡动作实时亮灭。
        if (d.size >= 173) s.balanceOn = (d[172].toInt() and 0xFF) > 0

        // MOS 状态：offset 198 充电，199 放电
        if (d.size >= 200) {
            s.chargeMosOn = (d[198].toInt() and 0xFF) > 0
            s.dischargeMosOn = (d[199].toInt() and 0xFF) > 0
        }
    }

    /**
     * 解析 0x01 设置/参数帧（JK02 偏移，来源 syssi/esphome-jk-bms + BHP1000 实证）。
     * data 为完整原始帧（含 4 字节帧头 + 1 字节帧类型 + 1 字节帧计数）。
     */
    private fun parseSettingsFrame(s: JikongStatus, d: ByteArray) {
        // 电压阈值（V，×0.001）
        if (d.size >= 22) s.cellEmptyVoltage = readU32(d, 10) * 0.001f      // 单体欠压保护
        if (d.size >= 26) s.cellFullVoltage = readU32(d, 18) * 0.001f       // 单体过压保护

        // 电流阈值（A，×0.001）
        if (d.size >= 54) s.chargeCurrentLimit = readU32(d, 50) * 0.001f    // 充电限流
        if (d.size >= 66) s.dischargeCurrentLimit = readU32(d, 62) * 0.001f // 放电限流

        // 温度阈值（℃，×0.1）
        if (d.size >= 86) s.chargeOverTemp = readU32(d, 82) * 0.1f          // 充电过温
        if (d.size >= 94) s.dischargeOverTemp = readU32(d, 90) * 0.1f       // 放电过温

        // 标称容量（配置，×0.001Ah）
        if (d.size >= 134) s.nominalCapacity = readU32(d, 130) * 0.001f
    }

    /**
     * 解析 0x03 设备信息帧。
     * data 为完整原始帧。设备名在 offset 46（16B），硬件版本 offset 22（8B），
     * 软件版本 offset 30（8B）。字符串为 null 结尾 ASCII。
     */
    private fun parseDeviceFrame(s: JikongStatus, d: ByteArray) {
        fun readAscii(off: Int, len: Int): String {
            if (off + len > d.size) return ""
            val sb = StringBuilder()
            for (i in off until off + len) {
                val c = d[i].toInt() and 0xFF
                if (c == 0) break
                if (c in 32..126) sb.append(c.toChar())
            }
            return sb.toString().trim()
        }
        val hw = readAscii(22, 8)
        val sw = readAscii(30, 8)
        val name = readAscii(46, 16)
        if (hw.isNotBlank()) s.hardwareVersion = hw
        if (sw.isNotBlank()) s.softwareVersion = sw
        if (name.isNotBlank()) s.model = name
    }

    // ==================== 控制命令 ====================

    /**
     * 控制 MOS 开关（JK02：0x1D 充电 MOS、0x1E 放电 MOS，需先写密码 123456）。
     * @param charge 充电 MOS；discharge 放电 MOS；on 开/关。
     */
    suspend fun setMos(charge: Boolean?, discharge: Boolean?, on: Boolean): Boolean {
        // 写密码解锁
        runCatching { JikongBleClient.write(JikongProtocol.buildPassword()) }
        val value = if (on) 1L else 0L
        var ok = true
        if (charge == true || (charge == null && discharge == null)) {
            ok = JikongBleClient.write(JikongProtocol.buildCommand(JikongProtocol.CMD_CHARGE_MOS, value)) && ok
        }
        if (discharge == true || (charge == null && discharge == null)) {
            ok = JikongBleClient.write(JikongProtocol.buildCommand(JikongProtocol.CMD_DISCHARGE_MOS, value)) && ok
        }
        return ok
    }

    /** 控制均衡开关（JK02 无独立均衡命令，均衡由 BMS 自动；此处保留接口返回 true 占位）。 */
    suspend fun setBalance(on: Boolean): Boolean {
        // JK02 协议均衡由保护板自动管理，无独立开关命令；先写密码，保持接口兼容。
        runCatching { JikongBleClient.write(JikongProtocol.buildPassword()) }
        return true
    }

    /** 写单个参数（保护阈值等）。JK02 协议需先写密码；此处写密码后按寄存器写。
     *  @param paramId 寄存器地址（JK02 寄存器映射以固件为准） */
    suspend fun writeParam(paramId: Int, value: Int): Boolean {
        runCatching { JikongBleClient.write(JikongProtocol.buildPassword()) }
        // JK02 写寄存器：命令码 = 寄存器地址，值 4B LE
        return JikongBleClient.write(JikongProtocol.buildCommand(paramId, value.toLong() and 0xFFFFFFFFL))
    }

    // ==================== 上报 ====================

    /** 状态变化时上报服务端（去重：仅变化时发送）。任何异常都吞掉，不影响运行。
     *  v0.2.86：status 内附带 dash（时速仪表盘快照）与 app（本地设置）两块，
     *  供管理后台「极空页 1:1 复刻」展示；字段缺失时后台显示占位，不影响旧版。 */
    private fun reportStatus() {
        try {
            val json = status.toJson().toString()
            if (json == lastReportedJson) return
            lastReportedJson = json
            val ws = _webSocketClient ?: return
            val data = JsonObject().apply {
                addProperty("event", "jikong_state")
                add("status", buildReportStatus())
            }
            ws.sendBusinessMessage(data)
        } catch (e: Exception) {
            Timber.w(e, "极空状态上报失败（已忽略）")
        }
    }

    /** status.toJson() + dash/app 扩展（v0.2.86）。异常安全：任一块失败只丢该块。 */
    private fun buildReportStatus(): JsonObject = try {
        status.toJson().apply {
            // ---- 时速仪表盘快照（SpeedDash 单例，GPS 数据仅页面可见+已连接时新鲜） ----
            try {
                val d = SpeedDash
                val ctx = appContext
                add("dash", JsonObject().apply {
                    addProperty("speed_kmh", d.speedKmh)
                    addProperty("gps_fresh", d.gpsFresh)
                    addProperty("range_km", d.rangeKm)
                    addProperty("inst_wh_km", d.instWhKm)
                    addProperty("avg_wh_km", d.avgWhKm)
                    addProperty("trip_km", d.tripKm)
                    addProperty("trip_wh", d.tripWh)
                    addProperty("trip_seconds", d.tripSeconds)
                    addProperty("bms_stale", d.bmsStale)
                    // 累计统计（JikongPrefs 持久化值 + 当次行程，与设置页口径一致）
                    if (ctx != null) {
                        addProperty("total_km", JikongPrefs.totalKm(ctx) + d.tripKm)
                        addProperty("total_wh", JikongPrefs.totalWh(ctx) + d.tripWh)
                        addProperty("hist_avg_wh_km",
                            if (d.avgWhKm > 0f) d.avgWhKm else JikongPrefs.histAvgWhKm(ctx))
                    }
                    addProperty("updated_at", System.currentTimeMillis())
                })
            } catch (_: Exception) { }
            // ---- 手机本地设置（后台参数设置页展示用） ----
            try {
                val ctx = appContext
                if (ctx != null) {
                    add("app", JsonObject().apply {
                        addProperty("theme", JikongPrefs.theme(ctx))
                        addProperty("dash_enabled", JikongPrefs.dashEnabled(ctx))
                    })
                }
            } catch (_: Exception) { }
        }
    } catch (_: Exception) {
        status.toJson()
    }

    /** 强制立即上报一次（服务端查询/重连对齐时调用）。 */
    fun forceReport() {
        lastReportedJson = ""
        reportStatus()
    }

    // ==================== 工具 ====================

    // JK02 协议为**小端（little-endian）**：低字节在前。

    private fun readU16(d: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= d.size) return 0
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readI16(d: ByteArray, off: Int): Int {
        val v = readU16(d, off)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun readU32(d: ByteArray, off: Int): Long {
        if (off < 0 || off + 3 >= d.size) return 0L
        var v = 0L
        for (i in 0 until 4) {
            v = v or ((d[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun readI32(d: ByteArray, off: Int): Long {
        val v = readU32(d, off)
        return if (v and 0x80000000L != 0L) v - 0x100000000L else v
    }

    private fun recomputeExtremes(s: JikongStatus) {
        if (s.cellVoltages.isNotEmpty()) {
            s.maxCellVoltage = s.cellVoltages.max()
            s.minCellVoltage = s.cellVoltages.min()
            s.maxCellIndex = s.cellVoltages.indexOf(s.maxCellVoltage)
            s.minCellIndex = s.cellVoltages.indexOf(s.minCellVoltage)
            s.diffVoltage = s.maxCellVoltage - s.minCellVoltage
        }
        if (s.temperatures.isNotEmpty()) {
            s.maxTemperature = s.temperatures.max()
            s.minTemperature = s.temperatures.min()
            s.tempDiff = s.maxTemperature - s.minTemperature
        }
    }
}
