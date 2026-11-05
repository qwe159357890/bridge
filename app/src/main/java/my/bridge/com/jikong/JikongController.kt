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
            // JK02 时序（BHP1000/pippofo 实证）：连接后发 0x97（设备信息）→ 等 500ms
            // → 发 0x96（读单体）触发 0x01+0x03+0x02 burst，之后 BMS 持续流式推 0x02。
            // 关键：0x97 与 0x96 之间必须有间隔，否则背靠背连发会命令冲突，
            // BMS 只循环回 0x01+0x03，永不进入 0x02 数据流（v0.2.58 实测正是此现象）。
            delay(500)
            runCatching { JikongBleClient.write(JikongProtocol.buildDeviceInfo()) }
            delay(500)
            runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }

            // 触发后进入守护循环：正常情况下 BMS 持续推 0x02，无需重复下发；
            // 仅当长时间未收到任何数据帧时才补发 0x96 兜底（间隔放宽到 5s，对齐 BHP1000）。
            while (isActive && JikongBleClient.connected) {
                delay(pollIntervalMs)
                val since = System.currentTimeMillis() - status.lastUpdateAt
                if (since > pollIntervalMs * 2) {
                    runCatching { JikongBleClient.write(JikongProtocol.buildCellInfo()) }
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
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
            JikongProtocol.FRAME_DATA -> parseDataFrame(s, frame.data)          // 0x02
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
     *   74      平均电压（u16 ×0.001V）
     *   76      压差（u16 ×0.001V）
     *   80+i*2  内阻（u16 ×0.001mΩ）
     *   144     MOS 温度（i16 ×0.1℃）
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
     *   190     SOH（u8 %）
     *   198     充电 MOS（u8，>0=on）
     *   199     放电 MOS（u8，>0=on）
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

        // 内阻：offset 80 + i*2，每串 2B LE，×0.001Ω（毫欧转欧姆）
        for (i in 0 until cells.size) {
            val off = 80 + i * 2
            if (off + 1 >= d.size) break
            resistances.add(readU16(d, off) * 0.001f)
        }
        s.cellResistances = resistances

        // 单体平均电压：offset 74-75，u16 LE，×0.001V
        if (d.size >= 76) s.avgCellVoltage = readU16(d, 74) * 0.001f

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

        // SOH：offset 190
        if (d.size >= 191) s.soh = d[190].toInt() and 0xFF

        // 均衡电流：offset 170-171，i16 LE，×0.001A
        if (d.size >= 172) s.balanceCurrent = readI16(d, 170) / 1000f

        // 均衡状态：offset 172（>0=均衡中）
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
