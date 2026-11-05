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

    /** 页面是否可见（决定是否轮询）。 */
    @Volatile
    var pageVisible: Boolean = false
        private set

    /** 是否保留连接（页面不可见时是否断开 BLE）。默认释放，省电。 */
    @Volatile
    var keepConnection: Boolean = false

    /** 轮询间隔（毫秒）。 */
    @Volatile
    var pollIntervalMs: Long = 2000

    private var pollJob: Job? = null
    private var lastReportedJson: String = ""

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
                }
                reportStatus()
                Timber.i(TAG, "连接状态变化：connected=$connected")
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
        val ok = JikongBleClient.connect(context, address)
        if (ok) startPolling()
        ok
    }

    fun disconnect() {
        stopPolling()
        JikongBleClient.disconnect()
    }

    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
        if (visible) {
            if (JikongBleClient.connected) startPolling()
        } else {
            if (!keepConnection) {
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
        pollJob = scope.launch {
            while (isActive && JikongBleClient.connected) {
                requestRealtimeData()
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ==================== 数据读取 ====================

    /** 下发一轮读帧：基本信息 + 单体电压 + 温度 + 告警。 */
    suspend fun requestRealtimeData() {
        runCatching { JikongBleClient.write(JikongProtocol.buildDdRead(JikongProtocol.DD_CMD_BASIC)) }
        runCatching { JikongBleClient.write(JikongProtocol.buildDdRead(JikongProtocol.DD_CMD_CELL_VOLTAGE)) }
        runCatching { JikongBleClient.write(JikongProtocol.buildDdRead(JikongProtocol.DD_CMD_TEMP)) }
        runCatching { JikongBleClient.write(JikongProtocol.buildDdRead(JikongProtocol.DD_CMD_ALARM)) }
    }

    /** 处理收到的原始字节：尝试解码并合并进状态。任何异常都吞掉，绝不崩溃。 */
    private fun handleData(bytes: ByteArray) {
        try {
            val frame = JikongProtocol.decode(bytes) ?: return
            scope.launch {
                try {
                    mergeFrame(frame)
                } catch (e: Exception) {
                    Timber.w(e, "合并极空帧失败（已忽略，不影响运行）")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "解码极空数据失败（已忽略，不影响运行）")
        }
    }

    private fun mergeFrame(frame: JikongFrame) {
        val s = status
        when (frame.protocol) {
            "0xDD", "0xDD-W" -> mergeDdFrame(s, frame)
            "NW" -> mergeNwFrame(s, frame)
        }
        s.lastUpdateAt = System.currentTimeMillis()
        // 单体/温度解析后重算极值与压差/温差
        recomputeExtremes(s)
        reportStatus()
    }

    private fun mergeDdFrame(s: JikongStatus, frame: JikongFrame) {
        val d = frame.data
        when (frame.command) {
            JikongProtocol.DD_CMD_BASIC -> {
                // 基本信息：总压(2B, 0.01V)、电流(2B, 0.01A 有符号)、SOC(1B)
                if (d.size >= 4) {
                    s.totalVoltage = readU16(d, 0) / 100f
                    s.current = readI16(d, 2) / 100f
                }
                if (d.size >= 5) s.soc = d[4].toInt() and 0xFF
                if (d.size >= 7) {
                    s.remainCapacity = readU16(d, 5) / 100f
                }
                s.power = s.totalVoltage * s.current
            }
            JikongProtocol.DD_CMD_CELL_VOLTAGE -> {
                // 单体电压：每串 2B（0.001V）。串数自动探测 = 数据长度/2
                if (d.size >= 2) {
                    val n = d.size / 2
                    val volts = (0 until n).map { readU16(d, it * 2) / 1000f }
                    s.cellCount = n
                    s.cellVoltages = volts
                }
            }
            JikongProtocol.DD_CMD_TEMP -> {
                // 温度：每传感器 2B（0.1℃）。传感器数量 = 数据长度/2
                if (d.size >= 2) {
                    val n = d.size / 2
                    val temps = (0 until n).map { readI16(d, it * 2) / 10f }
                    s.tempCount = n
                    s.temperatures = temps
                }
            }
            JikongProtocol.DD_CMD_ALARM -> {
                // 告警位：逐位映射到常见保护项（具体映射以真机固件为准）
                parseAlarmBits(s, d)
            }
            JikongProtocol.DD_CMD_DEVICE_INFO -> {
                parseDeviceInfo(s, d)
            }
            else -> Timber.d(TAG, "未处理的 0xDD 命令码 0x${frame.command.toString(16)}")
        }
    }

    private fun mergeNwFrame(s: JikongStatus, frame: JikongFrame) {
        // NW 帧按数据标识码（markCode）解析
        val d = frame.data
        when (frame.markCode) {
            0x79 -> if (d.size >= 2) { s.totalVoltage = readU16(d, 0) / 100f; s.power = s.totalVoltage * s.current }
            0x7A -> if (d.size >= 2) s.current = readI16(d, 0) / 100f
            0x7B -> if (d.size >= 2) s.soc = readU16(d, 0) and 0xFF
            0x7C, 0x7D, 0x7E, 0x7F -> { /* 单体电压（多标识码分段） */ }
            else -> Timber.d(TAG, "未处理的 NW 标识码 0x${frame.markCode.toString(16)}")
        }
    }

    // ==================== 控制命令 ====================

    /**
     * 控制 MOS 开关。
     * @param charge 充电 MOS；discharge 放电 MOS；on 开/关。
     */
    suspend fun setMos(charge: Boolean?, discharge: Boolean?, on: Boolean): Boolean {
        // 控制帧数据：字节 0 表示操作对象（0x01 充电 MOS / 0x02 放电 MOS / 0x03 两者）
        val target = when {
            charge == true && discharge == true -> 0x03
            charge == true -> 0x01
            discharge == true -> 0x02
            else -> 0x03
        }
        val data = byteArrayOf(target.toByte(), (if (on) 0x01 else 0x00).toByte())
        return JikongBleClient.write(JikongProtocol.buildDdWrite(JikongProtocol.DD_CMD_CONTROL, data))
    }

    /** 控制均衡开关。 */
    suspend fun setBalance(on: Boolean): Boolean {
        val data = byteArrayOf(0x02.toByte(), (if (on) 0x01 else 0x00).toByte())
        return JikongBleClient.write(JikongProtocol.buildDdWrite(JikongProtocol.DD_CMD_CONTROL, data))
    }

    /** 写单个参数（保护阈值等）。value 为原始 2 字节，单位由调用方按参数类型换算。 */
    suspend fun writeParam(paramId: Int, value: Int): Boolean {
        val data = byteArrayOf(
            paramId.toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return JikongBleClient.write(JikongProtocol.buildDdWrite(JikongProtocol.DD_CMD_WRITE_PARAM, data))
    }

    // ==================== 上报 ====================

    /** 状态变化时上报服务端（去重：仅变化时发送）。任何异常都吞掉，不影响运行。 */
    private fun reportStatus() {
        try {
            val json = status.toJson().toString()
            if (json == lastReportedJson) return
            lastReportedJson = json
            val ws = _webSocketClient ?: return
            val data = JsonObject().apply {
                addProperty("event", "jikong_state")
                add("status", status.toJson())
            }
            ws.sendBusinessMessage(data)
        } catch (e: Exception) {
            Timber.w(e, "极空状态上报失败（已忽略）")
        }
    }

    /** 强制立即上报一次（服务端查询/重连对齐时调用）。 */
    fun forceReport() {
        lastReportedJson = ""
        reportStatus()
    }

    // ==================== 工具 ====================

    private fun readU16(d: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= d.size) return 0
        return ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
    }

    private fun readI16(d: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= d.size) return 0
        val v = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
        return if (v and 0x8000 != 0) v - 0x10000 else v
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

    private fun parseAlarmBits(s: JikongStatus, d: ByteArray) {
        if (d.isEmpty()) return
        val states = mutableMapOf<String, Boolean>()
        val names = listOf(
            "单体过压", "单体欠压", "总压过压", "总压欠压",
            "充电过流", "放电过流", "充电过温", "放电过温",
            "短路", "反接",
        )
        var bit = 0
        for (i in d.indices) {
            val b = d[i].toInt() and 0xFF
            for (j in 0 until 8) {
                if (bit >= names.size) break
                states[names[bit]] = (b and (1 shl j)) != 0
                bit++
            }
        }
        s.protectionStates = states
    }

    private fun parseDeviceInfo(s: JikongStatus, d: ByteArray) {
        // 设备信息帧具体布局以固件为准，这里做宽松解析（ASCII 字符串提取）
        val ascii = d.filter { it.toInt() in 32..126 }.map { it.toInt().toChar() }.joinToString("")
        if (ascii.isNotBlank()) s.model = ascii
    }
}
